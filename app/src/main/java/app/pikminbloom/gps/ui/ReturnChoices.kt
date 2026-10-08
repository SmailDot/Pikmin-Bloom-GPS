package app.pikminbloom.gps.ui

import android.content.Context
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Home

/** One row of the 回家 picker: where to ([home] null = the real position) and how. */
data class ReturnChoice(val home: Home?, val teleport: Boolean)

/**
 * The 回家 picker (one dialog for the main screen and the floating bar, PatrolDialogs.returnPicker): the real position first, then the
 * saved homes in their order, each on foot and as a teleport. Walking is first in every pair because
 * it is the default; a teleport only happens because the user picked that row for this one 回家.
 */
object ReturnChoices {
    fun build(homes: List<Home>): List<ReturnChoice> =
        (listOf<Home?>(null) + homes).flatMap { h -> listOf(ReturnChoice(h, teleport = false), ReturnChoice(h, teleport = true)) }

    /** The string that names [c]'s row; split from [label] so the choice -> text mapping can be tested without a Context. */
    fun labelRes(c: ReturnChoice): Int = when {
        c.home == null -> if (c.teleport) R.string.return_real_teleport else R.string.return_real_position
        else -> if (c.teleport) R.string.return_saved_home_teleport else R.string.return_saved_home
    }

    fun label(context: Context, c: ReturnChoice): String {
        val home = c.home ?: return context.getString(labelRes(c))
        return context.getString(labelRes(c), home.name)
    }
}
