package app.pikminbloom.gps

import app.pikminbloom.gps.data.Home
import app.pikminbloom.gps.ui.ReturnChoice
import app.pikminbloom.gps.ui.ReturnChoices
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every 回家 destination is offered on foot and as a teleport, walking first (2026-10-03). */
class ReturnChoicesTest {
    private val tokyo = Home("東京の家 🏠", 35.681236, 139.767125)
    private val osaka = Home("家 2", 34.702485, 135.495951)

    @Test
    fun withoutSavedHomesTheRealPositionIsOfferedOnFootThenAsATeleport() {
        assertEquals(listOf(ReturnChoice(null, teleport = false), ReturnChoice(null, teleport = true)), ReturnChoices.build(emptyList()))
    }

    @Test
    fun savedHomesFollowTheRealPositionInTheirOrderEachWalkBeforeTeleport() {
        assertEquals(
            listOf(
                ReturnChoice(null, false), ReturnChoice(null, true),
                ReturnChoice(tokyo, false), ReturnChoice(tokyo, true),
                ReturnChoice(osaka, false), ReturnChoice(osaka, true),
            ),
            ReturnChoices.build(listOf(tokyo, osaka)),
        )
    }

    /** R.string ids are bare ints: name them, so a wrong pick says which string the row got. */
    private fun stringName(id: Int): String =
        R.string::class.java.fields.firstOrNull { it.getInt(null) == id }?.name ?: "#$id"

    private fun assertLabelledWith(expected: Int, choice: ReturnChoice) {
        val actual = ReturnChoices.labelRes(choice)
        assertEquals("$choice is labelled R.string.${stringName(actual)}, expected R.string.${stringName(expected)}", expected, actual)
    }

    /** The label is all the user has to go on: a row that teleports must say 瞬移 and a row that walks must say 走. */
    @Test
    fun eachRowIsLabelledWithTheStringForItsDestinationAndItsWayOfGettingThere() {
        assertLabelledWith(R.string.return_real_position, ReturnChoice(null, teleport = false))
        assertLabelledWith(R.string.return_real_teleport, ReturnChoice(null, teleport = true))
        assertLabelledWith(R.string.return_saved_home, ReturnChoice(tokyo, teleport = false))
        assertLabelledWith(R.string.return_saved_home_teleport, ReturnChoice(tokyo, teleport = true))
    }
}
