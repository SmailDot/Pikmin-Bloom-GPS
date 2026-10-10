package app.pikminbloom.gps.ui

import app.pikminbloom.gps.R

/** The spots on the main screen the tour can point at. A step whose spot is missing or hidden becomes a centred card. */
enum class TargetKey { MENU, MAP, START }

/** What the user is asked to do at the spot. [NONE] is a plain card. */
enum class Gesture { NONE, TAP, LONG_PRESS }

data class CoachStep(val target: TargetKey?, val titleRes: Int, val bodyRes: Int, val gesture: Gesture)

/**
 * The first-run tour, in order. Step 1 is a centred card; steps 2 to 4 point at the real controls; step 5 is a centred
 * card about the floating bar and the auto actions.
 */
object CoachSteps {

    val steps: List<CoachStep> = listOf(
        CoachStep(null, R.string.guide_1_title, R.string.guide_1_body, Gesture.NONE),
        CoachStep(TargetKey.MENU, R.string.tour_2_title, R.string.tour_2_body, Gesture.TAP),
        CoachStep(TargetKey.MAP, R.string.tour_3_title, R.string.tour_3_body, Gesture.LONG_PRESS),
        CoachStep(TargetKey.START, R.string.tour_4_title, R.string.tour_4_body, Gesture.TAP),
        CoachStep(null, R.string.tour_5_title, R.string.tour_5_body, Gesture.NONE),
    )

    /** The tour opens once, after the disclaimer is accepted. The 使用教學 menu item always opens it regardless. */
    fun shouldShowGuide(disclaimerAccepted: Boolean, guideSeen: Boolean): Boolean = disclaimerAccepted && !guideSeen
}
