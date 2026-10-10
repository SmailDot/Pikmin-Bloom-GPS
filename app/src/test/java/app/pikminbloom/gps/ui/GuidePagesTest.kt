package app.pikminbloom.gps.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-run guide: five pages in order, shown once after the disclaimer is accepted. */
class GuidePagesTest {

    @Test
    fun theGuideHasFivePages() {
        assertEquals(5, GuidePages.pages.size)
    }

    @Test
    fun everyPageHasItsOwnTitleAndBody() {
        val titles = GuidePages.pages.map { it.titleRes }
        val bodies = GuidePages.pages.map { it.bodyRes }
        assertEquals(titles.size, titles.distinct().size)
        assertEquals(bodies.size, bodies.distinct().size)
        assertFalse("a page's title and body must differ", GuidePages.pages.any { it.titleRes == it.bodyRes })
    }

    @Test
    fun theGuideShowsAfterTheDisclaimerWhenItHasNotBeenSeen() {
        assertTrue(GuidePages.shouldShowGuide(disclaimerAccepted = true, guideSeen = false))
    }

    @Test
    fun theGuideWaitsForTheDisclaimer() {
        assertFalse(GuidePages.shouldShowGuide(disclaimerAccepted = false, guideSeen = false))
    }

    @Test
    fun theGuideIsNotShownAgainOnceSeen() {
        assertFalse(GuidePages.shouldShowGuide(disclaimerAccepted = true, guideSeen = true))
    }

    @Test
    fun aSeenGuideStaysSeenEvenBeforeTheDisclaimerIsAccepted() {
        assertFalse(GuidePages.shouldShowGuide(disclaimerAccepted = false, guideSeen = true))
    }
}
