package app.pikminbloom.gps.ui

import app.pikminbloom.gps.R

/** One page of the first-run guide: a title and a body, both string resource ids. */
data class GuidePage(val titleRes: Int, val bodyRes: Int)

/**
 * The first-run guide as plain data, so the page order and the show-once rule can be tested without Android.
 * MainActivity builds the dialogs from [pages].
 */
object GuidePages {

    val pages: List<GuidePage> = listOf(
        GuidePage(R.string.guide_1_title, R.string.guide_1_body),
        GuidePage(R.string.guide_2_title, R.string.guide_2_body),
        GuidePage(R.string.guide_3_title, R.string.guide_3_body),
        GuidePage(R.string.guide_4_title, R.string.guide_4_body),
        GuidePage(R.string.guide_5_title, R.string.guide_5_body),
    )

    /** The guide opens once, after the disclaimer is accepted. The 使用教學 menu item always opens it regardless. */
    fun shouldShowGuide(disclaimerAccepted: Boolean, guideSeen: Boolean): Boolean = disclaimerAccepted && !guideSeen
}
