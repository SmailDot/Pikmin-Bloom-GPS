package app.pikminbloom.gps.expedition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The diagnostic lines an expedition run writes (support/AutoRunLog): what the screen held as counts, the GO state, our
 * own tap points and the stop. Never pixels.
 */
class ExpeditionLinesTest {

    @Test
    fun aListLineCountsEachCellKindIncludingTheZeros() {
        val frame = ExpFrame(
            ExpScreen.LIST,
            cells = listOf(Cell(1, 1, ItemKind.POT), Cell(2, 2, ItemKind.FRUIT), Cell(3, 3, ItemKind.FRUIT)),
            tabY = 2400,
        )
        assertEquals(
            "list: tabY=2400, cells=[POT×1, FRUIT×2, GIFT×0, COVERED×0, IN_PROGRESS×0, UNKNOWN×0]",
            ExpeditionLines.list(frame),
        )
    }

    @Test
    fun aListWithoutATabRowSaysSoInsteadOfInventingOne() {
        assertEquals(
            "list: tabY=-, cells=[POT×0, FRUIT×0, GIFT×0, COVERED×0, IN_PROGRESS×0, UNKNOWN×0]",
            ExpeditionLines.list(ExpFrame(ExpScreen.LIST)),
        )
    }

    @Test
    fun aSelectLineGivesTheGoStateAndTheMeasuredFraction() {
        assertEquals(
            "select: goActive=true, goSat=0.123",
            ExpeditionLines.select(ExpFrame(ExpScreen.SELECT, selectRowY = 1031, goActive = true, goSat = 0.1234)),
        )
        assertEquals(
            "select: goActive=false, goSat=0.000",
            ExpeditionLines.select(ExpFrame(ExpScreen.SELECT, selectRowY = 1031)),
        )
    }

    @Test
    fun aTapLineNamesOurTargetAndItsPoint() {
        assertEquals("tap auto at 295,1031", ExpeditionLines.tap("auto", 295, 1031))
    }

    @Test
    fun theFinalLineSaysHowTheRunStoppedAndHowManyWentOut() {
        assertEquals("stopped: LIMIT_REACHED after 3", ExpeditionLines.stopped(ExpeditionStop.LIMIT_REACHED, 3))
    }
}
