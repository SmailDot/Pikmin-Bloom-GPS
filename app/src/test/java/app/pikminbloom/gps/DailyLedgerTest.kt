package app.pikminbloom.gps

import app.pikminbloom.gps.steps.DailyLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 今日已寫入步數 must start over at local midnight, even mid-patrol (bug reported 2026-09-14 00:50 JST). */
class DailyLedgerTest {
    private val sep13 = LocalDate.of(2026, 9, 13)
    private val sep14 = LocalDate.of(2026, 9, 14)

    @Test
    fun accumulatesWithinTheSameDay() {
        val l = DailyLedger(sep13, initial = 100)
        l.add(50)
        assertFalse(l.rollTo(sep13))
        assertEquals(150, l.total)
    }

    @Test
    fun aNewDayResetsTheTotalAndReportsIt() {
        val l = DailyLedger(sep13, initial = 50_000)
        assertTrue(l.rollTo(sep14))
        assertEquals(0, l.total)
        assertEquals(sep14, l.day)
        assertFalse(l.rollTo(sep14))            // only once per day
    }

    @Test
    fun aNewDayCanStartFromWhatHealthConnectAlreadyHolds() {
        val l = DailyLedger(sep13, initial = 50_000)
        l.rollTo(sep14, freshTotal = 1_200)     // e.g. another patrol already wrote 1,200 today
        assertEquals(1_200, l.total)
    }

    @Test
    fun theDailyCapOpensAgainAfterMidnight() {
        val l = DailyLedger(sep13, initial = 50_000)
        assertEquals(0, l.room(50_000))
        l.rollTo(sep14)
        assertEquals(50_000, l.room(50_000))
        l.add(49_990)
        assertEquals(10, l.room(50_000))
    }

    // 每日步數上限「若未輸入則不限制」(2026-10-08).

    @Test
    fun anEmptyCapFieldMeansNoCap() {
        for (raw in listOf(null, "", "   ")) assertEquals("'$raw'", null, DailyLedger.parseCap(raw))
        val l = DailyLedger(sep13, initial = 1_000_000)
        assertEquals("no cap leaves all the room there is", Long.MAX_VALUE, l.room(null))
    }

    @Test
    fun aTypedCapIsUsedAsIsWithoutTheOld200kCeiling() {
        assertEquals(50_000L, DailyLedger.parseCap("50000"))
        assertEquals(850_000L, DailyLedger.parseCap(" 850000 "))   // used to become 200,000 silently (PLAN N5)
    }

    @Test
    fun onlyEmptyOrAPositiveWholeNumberIsAccepted() {
        for (ok in listOf(null, "", "1", "30000")) assertTrue("'$ok'", DailyLedger.isValidCapInput(ok))
        for (bad in listOf("0", "-5", "abc", "1.5")) {
            assertFalse("'$bad'", DailyLedger.isValidCapInput(bad))
            assertEquals("'$bad' is never read as a cap", null, DailyLedger.parseCap(bad))
        }
    }
}
