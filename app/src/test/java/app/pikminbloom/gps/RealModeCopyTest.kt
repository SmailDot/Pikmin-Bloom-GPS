package app.pikminbloom.gps

import app.pikminbloom.gps.data.LocationJump
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.ui.RealModeCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words of the 真實位置 ⇄ 虛擬位置 switch (T18, 2026-10-03): the user could not tell the state from
 * the button, and one poke jumped the game 2,230 km. State and action are written apart, every way
 * to the action names the jump (跳躍), and no label is a bare 「真實位置」／「虛擬位置」.
 */
class RealModeCopyTest {
    private val taipei = LatLng(25.033964, 121.564468)
    private val bareNouns = setOf("真實位置", "虛擬位置")
    private val far = 2_230_240.0

    @Test
    fun theStateLineSaysWhereEveryAppSeesThePhone() {
        assertEquals("定位：虛擬位置（模擬中）", RealModeCopy.stateLine(suspended = false))
        assertEquals("定位：真實位置（真實 GPS）", RealModeCopy.stateLine(suspended = true))
    }

    @Test
    fun theSwitchButtonIsTheActionNotTheState() {
        assertEquals("切到真實位置…", RealModeCopy.switchLabel(suspended = false))
        assertEquals("回到虛擬位置…", RealModeCopy.switchLabel(suspended = true))
    }

    @Test
    fun theSwitchButtonStartsWithItsVerbAndAnnouncesTheDialog() {
        for ((suspended, verb) in listOf(false to "切到", true to "回到")) {
            val label = RealModeCopy.switchLabel(suspended)
            assertTrue("$label starts with $verb", label.startsWith(verb))
            assertTrue("$label ends with …", label.endsWith("…"))
        }
    }

    @Test
    fun theConfirmTitleAsksForTheAction() {
        assertEquals("切到真實位置？", RealModeCopy.confirmTitle(toReal = true))
        assertEquals("回到虛擬位置？", RealModeCopy.confirmTitle(toReal = false))
    }

    @Test
    fun theConfirmButtonIsTheActionItself() {
        assertEquals("切到真實位置", RealModeCopy.confirmButton(toReal = true))
        assertEquals("回到虛擬位置", RealModeCopy.confirmButton(toReal = false))
    }

    @Test
    fun goingRealNamesTheJumpAndHowFar() {
        assertEquals(
            "手機上所有 App（包含遊戲和 Google 地圖）會馬上看到你真正的位置，遊戲會看到一次位置跳躍，約 2,230 km。\n\n" +
                "虛擬巡邏會停在原處等你，之後要按「回到虛擬位置…」才會跳回去（那也是一次跳躍）。",
            RealModeCopy.confirmMessage(toReal = true, jumpM = far),
        )
    }

    @Test
    fun goingBackNamesTheJumpAndHowFar() {
        assertEquals(
            "遊戲會看到一次位置跳躍：從你的真實位置跳回巡邏停放的位置，約 2,230 km。\n\n回去之後巡邏會回到切走之前的狀態。",
            RealModeCopy.confirmMessage(toReal = false, jumpM = far),
        )
    }

    @Test
    fun anUnknownJumpLeavesTheDistanceOut() {
        assertEquals(
            "手機上所有 App（包含遊戲和 Google 地圖）會馬上看到你真正的位置，遊戲會看到一次位置跳躍。\n\n" +
                "虛擬巡邏會停在原處等你，之後要按「回到虛擬位置…」才會跳回去（那也是一次跳躍）。",
            RealModeCopy.confirmMessage(toReal = true, jumpM = null),
        )
        assertEquals(
            "遊戲會看到一次位置跳躍：從你的真實位置跳回巡邏停放的位置。\n\n回去之後巡邏會回到切走之前的狀態。",
            RealModeCopy.confirmMessage(toReal = false, jumpM = null),
        )
    }

    @Test
    fun goingRealNamesTheWayBackExactlyAsItsButtonIsLabelled() {
        for (jumpM in listOf(far, null)) {
            val msg = RealModeCopy.confirmMessage(toReal = true, jumpM = jumpM)
            assertTrue(msg, msg.contains("按「${RealModeCopy.switchLabel(suspended = true)}」"))
        }
    }

    @Test
    fun everyConfirmationNamesTheJump() {
        for (toReal in listOf(true, false)) for (jumpM in listOf(far, null)) {
            val msg = RealModeCopy.confirmMessage(toReal, jumpM)
            assertTrue("toReal=$toReal jumpM=$jumpM: $msg", msg.contains("跳躍"))
        }
    }

    @Test
    fun theTwoDirectionsNeverReadTheSame() {
        assertNotEquals(RealModeCopy.confirmMessage(true, far), RealModeCopy.confirmMessage(false, far))
        assertNotEquals(RealModeCopy.confirmTitle(true), RealModeCopy.confirmTitle(false))
        assertNotEquals(RealModeCopy.confirmButton(true), RealModeCopy.confirmButton(false))
    }

    @Test
    fun theBarButtonDescribesTheStateThenWhatATapOffers() {
        assertEquals("目前：虛擬位置（模擬中），按一下可選擇切到真實位置", RealModeCopy.buttonDescription(suspended = false))
        assertEquals("目前：真實位置（真實 GPS），按一下可選擇回到虛擬位置", RealModeCopy.buttonDescription(suspended = true))
    }

    @Test
    fun theJumpIsUnknownWithoutBothPoints() {
        assertNull(RealModeCopy.jumpMeters(null, taipei))
        assertNull(RealModeCopy.jumpMeters(taipei, null))
        assertNull(RealModeCopy.jumpMeters(null, null))
    }

    @Test
    fun aMoveUnderTheJumpThresholdIsNoJump() {
        val near = GeoMath.offsetMeters(taipei, LocationJump.MIN_REPORTED_M - 1.0, 0.0)
        assertNull(RealModeCopy.jumpMeters(taipei, near))
    }

    @Test
    fun fromTheJumpThresholdOnTheJumpIsTheExactDistance() {
        val justOver = GeoMath.offsetMeters(taipei, LocationJump.MIN_REPORTED_M + 1.0, 0.0)
        val m = RealModeCopy.jumpMeters(taipei, justOver)
        assertNotNull("${LocationJump.MIN_REPORTED_M + 1.0} m is a jump", m)
        assertEquals(GeoMath.distanceM(taipei, justOver), m!!, 0.0)
        val tokyo = LatLng(35.681236, 139.767125)
        assertEquals(GeoMath.distanceM(taipei, tokyo), RealModeCopy.jumpMeters(taipei, tokyo)!!, 0.0)
    }

    @Test
    fun noStateLineIsABareNoun() {
        for (s in listOf(false, true)) assertFalse(RealModeCopy.stateLine(s), RealModeCopy.stateLine(s) in bareNouns)
    }

    @Test
    fun noButtonLabelIsABareNoun() {
        for (s in listOf(false, true)) {
            assertFalse(RealModeCopy.switchLabel(s), RealModeCopy.switchLabel(s) in bareNouns)
            assertFalse(RealModeCopy.confirmButton(s), RealModeCopy.confirmButton(s) in bareNouns)
        }
    }

    private val notRunning = "巡邏沒有在進行，沒有切換"

    @Test
    fun aSwitchToRealThatNoLongerAppliesSaysWhy() {
        // Checked before the dialog and again on its 確定 (it may be a stale notification): null = go ahead.
        val expected = mapOf(
            PatrolPhase.IDLE to notRunning,
            PatrolPhase.STARTING to notRunning,
            PatrolPhase.WALKING to null,
            PatrolPhase.DWELLING to null,
            PatrolPhase.PAUSED to null,
            PatrolPhase.RETURNING_HOME to null,
            PatrolPhase.STOPPING to notRunning,
            PatrolPhase.PARKED to null,
            PatrolPhase.MANUAL to null,
            PatrolPhase.SUSPENDED to "已經在真實位置",
        )
        assertEquals("every phase is listed", PatrolPhase.entries.toSet(), expected.keys)
        for ((phase, text) in expected) assertEquals("to real during $phase", text, RealModeCopy.staleSwitchText(toReal = true, phase = phase))
    }

    @Test
    fun aSwitchBackThatNoLongerAppliesSaysWhy() {
        val expected = mapOf(
            PatrolPhase.IDLE to notRunning,
            PatrolPhase.STARTING to notRunning,
            PatrolPhase.WALKING to "已經在虛擬位置",
            PatrolPhase.DWELLING to "已經在虛擬位置",
            PatrolPhase.PAUSED to "已經在虛擬位置",
            PatrolPhase.RETURNING_HOME to "已經在虛擬位置",
            PatrolPhase.STOPPING to notRunning,
            PatrolPhase.PARKED to "已經在虛擬位置",
            PatrolPhase.MANUAL to "已經在虛擬位置",
            PatrolPhase.SUSPENDED to null,
        )
        assertEquals("every phase is listed", PatrolPhase.entries.toSet(), expected.keys)
        for ((phase, text) in expected) assertEquals("back to virtual during $phase", text, RealModeCopy.staleSwitchText(toReal = false, phase = phase))
    }

    @Test
    fun theMapsQuestionSaysItIsAJumpAndThatReviewsDoNotNeedIt() {
        // 2026-10-08: "如果打開Google地圖只是想看某間店的評論就跳轉一次也不大合理".
        val m = RealModeCopy.mapsMessage(2_140_000.0)
        assertTrue(m, m.contains("2,140 km"))
        assertTrue(m, m.contains("跳一次"))
        assertTrue(m, m.contains("評論不用切"))
        assertTrue("no distance when unknown", !RealModeCopy.mapsMessage(null).contains("約"))
        assertEquals("不用", RealModeCopy.MAPS_NO)
    }
}
