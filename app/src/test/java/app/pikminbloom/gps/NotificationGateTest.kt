package app.pikminbloom.gps

import app.pikminbloom.gps.service.NotificationGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ongoing notification is re-posted only when it says something new, at most every 15 s.
 * A pure rules object like RealModeTest's RealMode: the service only feeds it the clock and the text.
 */
class NotificationGateTest {
    private val gap = 15_000L

    @Test
    fun unchangedTextIsNeverPostedAgain() {
        assertFalse("same text, forced refresh (lastPostMs = 0)", NotificationGate.shouldPost(nowMs = 900_000L, lastPostMs = 0L, lastText = "a", text = "a", minGapMs = gap))
    }

    @Test
    fun unchangedTextStaysQuietEvenLongAfterTheLastPost() {
        // The PAUSED case measured on the phone: 12 identical re-posts a minute. No keep-alive refresh either.
        assertFalse("same text, 800 s after the last post", NotificationGate.shouldPost(nowMs = 900_000L, lastPostMs = 100_000L, lastText = "a", text = "a", minGapMs = gap))
    }

    @Test
    fun changedTextWaitsForTheGap() {
        assertFalse("new text 10 s after the last post", NotificationGate.shouldPost(nowMs = 110_000L, lastPostMs = 100_000L, lastText = "a", text = "b", minGapMs = gap))
    }

    @Test
    fun changedTextOneMillisecondBeforeTheGapIsStillHeldBack() {
        assertFalse("new text 14.999 s after the last post", NotificationGate.shouldPost(nowMs = 114_999L, lastPostMs = 100_000L, lastText = "a", text = "b", minGapMs = gap))
    }

    @Test
    fun changedTextPostsOnceTheGapHasPassed() {
        assertTrue("new text exactly 15 s after the last post", NotificationGate.shouldPost(nowMs = 115_000L, lastPostMs = 100_000L, lastText = "a", text = "b", minGapMs = gap))
    }

    @Test
    fun aForcedRefreshWithNewTextPostsAtOnce() {   // the service sets lastNotificationMs = 0 on a phase change
        assertTrue("new text, forced refresh 3 s into the session", NotificationGate.shouldPost(nowMs = 3_000L, lastPostMs = 0L, lastText = "walking", text = "parked", minGapMs = gap))
    }

    @Test
    fun theFirstPostOfASessionGoesOutAtOnce() {   // nothing posted yet: lastText is null, lastPostMs is 0
        assertTrue("first post, nothing to compare with", NotificationGate.shouldPost(nowMs = 1_000L, lastPostMs = 0L, lastText = null, text = "a", minGapMs = gap))
    }
}
