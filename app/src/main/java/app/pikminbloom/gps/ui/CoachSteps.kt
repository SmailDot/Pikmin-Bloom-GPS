package app.pikminbloom.gps.ui

import app.pikminbloom.gps.R

/** The spots the tour can point at. A step whose spot is missing or hidden becomes a centred card. */
enum class TargetKey { MENU, MAP, START, HANDLE }

/** What the user is asked to do at the spot. [NONE] is a plain card. */
enum class Gesture { NONE, TAP, LONG_PRESS }

/**
 * One tour card. [target] is the spot it points at (null for a centred card) and [shape] how the spot is highlighted.
 * [fallbackBodyRes] replaces [bodyRes] when the spot is not on screen, so a card never points at nothing.
 */
data class CoachStep(
    val target: TargetKey?,
    val titleRes: Int,
    val bodyRes: Int,
    val gesture: Gesture,
    val shape: SpotShape = SpotShape.RECT,
    val fallbackBodyRes: Int? = null,
)

/**
 * The first-run tour, in order. Step 1 is a centred card; steps 2 to 5 point at the menu, the map, the start button and
 * the homes menu; step 6 points at the floating bar's handle; step 7 is a centred card about the auto actions.
 */
object CoachSteps {

    val steps: List<CoachStep> = listOf(
        CoachStep(null, R.string.guide_1_title, R.string.guide_1_body, Gesture.NONE),
        CoachStep(TargetKey.MENU, R.string.tour_2_title, R.string.tour_2_body, Gesture.TAP),
        CoachStep(TargetKey.MAP, R.string.tour_3_title, R.string.tour_3_body, Gesture.LONG_PRESS, SpotShape.CIRCLE),
        CoachStep(
            TargetKey.START, R.string.tour_4_title, R.string.tour_4_body, Gesture.TAP,
            fallbackBodyRes = R.string.tour_4_fallback_body,
        ),
        CoachStep(TargetKey.MENU, R.string.tour_homes_title, R.string.tour_homes_body, Gesture.TAP),
        CoachStep(
            TargetKey.HANDLE, R.string.tour_5a_title, R.string.tour_5a_body, Gesture.TAP, SpotShape.CIRCLE,
            R.string.tour_5a_fallback_body,
        ),
        CoachStep(null, R.string.tour_5b_title, R.string.tour_5b_body, Gesture.NONE),
    )

    /** The tour opens once, after the disclaimer is accepted. The 使用教學 menu item always opens it regardless. */
    fun shouldShowGuide(disclaimerAccepted: Boolean, guideSeen: Boolean): Boolean = disclaimerAccepted && !guideSeen
}
