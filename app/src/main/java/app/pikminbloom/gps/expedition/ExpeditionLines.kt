package app.pikminbloom.gps.expedition

import java.util.Locale

/**
 * The text of the lines an expedition run writes to its diagnostic log (support/AutoRunLog): how the list's cells counted,
 * the GO state after 自動, our own tap points and how the run stopped. These are screen-kind decisions and our own taps;
 * never pixels, images or a location of the user.
 */
object ExpeditionLines {
    private val KINDS = listOf(ItemKind.POT, ItemKind.FRUIT, ItemKind.GIFT, ItemKind.COVERED, ItemKind.IN_PROGRESS, ItemKind.UNKNOWN)

    /** "list: tabY=2400, cells=[POT×1, FRUIT×0, …]": the cells of each kind on the list; tabY is "-" when no tab row showed. */
    fun list(frame: ExpFrame): String {
        val counts = KINDS.joinToString(", ") { kind -> "$kind×${frame.cells.count { it.kind == kind }}" }
        return "list: tabY=${frame.tabY ?: "-"}, cells=[$counts]"
    }

    /** "select: goActive=true, goSat=0.123": the GO state after 自動, with the saturated fraction it was decided on. */
    fun select(frame: ExpFrame): String = "select: goActive=${frame.goActive}, goSat=${"%.3f".format(Locale.ROOT, frame.goSat)}"

    /** "tap auto at 295,1031": one of our own tap points. */
    fun tap(what: String, x: Int, y: Int): String = "tap $what at $x,$y"

    /** "stopped: LIMIT_REACHED after 3" */
    fun stopped(stop: ExpeditionStop, dispatched: Int): String = "stopped: $stop after $dispatched"
}
