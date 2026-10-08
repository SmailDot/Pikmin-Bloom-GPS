package app.pikminbloom.gps

import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.ui.ResumeCopy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which 上次巡邏沒有正常結束 text is true: after a kill on 真實位置 the providers were already gone, so
 * the game sees the real GPS and resuming is the jump, not 放棄 (T9 review, 2026-10-03). And what 從中斷點繼續 restores: a
 * checkpoint taken while PAUSED comes back paused, so its text must not promise to walk on (T17, 2026-10-03).
 */
class ResumeCopyTest {
    private val savedAt = 1_000_000L

    @Test
    fun aKillOnTheRealPositionGetsTheRealPositionText() {
        assertTrue(ResumeCopy.useRealModeText(suspendedAtMs = savedAt + 3_000, checkpointSavedAtMs = savedAt))
    }

    @Test
    fun aKillDuringTheVirtualPatrolKeepsTheUsualText() {
        // 0 = no flag, whatever the checkpoint's time (even an impossible 0).
        for (cpAt in listOf(savedAt, 0L)) {
            assertFalse("checkpoint at $cpAt", ResumeCopy.useRealModeText(suspendedAtMs = 0L, checkpointSavedAtMs = cpAt))
        }
    }

    @Test
    fun aFlagLeftOverFromAnEarlierSessionDoesNotRelabelANewerCheckpoint() {
        assertFalse(ResumeCopy.useRealModeText(suspendedAtMs = savedAt - 60_000, checkpointSavedAtMs = savedAt))
    }

    @Test
    fun aSwitchInTheSameMillisecondAsTheLastSaveStillCounts() {
        // 真實位置 never writes a checkpoint, so the last one can at most share the switch's millisecond.
        assertTrue(ResumeCopy.useRealModeText(suspendedAtMs = savedAt, checkpointSavedAtMs = savedAt))
    }

    // ---- T17 (2026-10-03): a patrol left PAUSED for days, killed, then resumed walked away at once (134 m in 27 s on foot,
    // 1200 km/h with a PLANE override). Table style mirrors RealModeTest.

    @Test
    fun aCheckpointTakenWhilePausedIsRestoredPaused() {
        assertTrue("a PAUSED checkpoint must come back PAUSED (it walked away instead)", ResumeCopy.restoresPaused(PatrolPhase.PAUSED, thenReturnHome = false))
    }

    @Test
    fun aPausedCheckpointIsNotRestoredPausedWhenTheUserPicksWalkHome() {
        // 從中斷點走回家 is the user's explicit choice to walk: handleResumeCheckpoint plans the way home, as it always did.
        assertFalse("走回家 on a PAUSED checkpoint must still walk home", ResumeCopy.restoresPaused(PatrolPhase.PAUSED, thenReturnHome = true))
    }

    /** Not paused whichever button was pressed: 從中斷點繼續 (thenReturnHome = false) or 從中斷點走回家 (true). */
    private fun assertNeverRestoredPaused(phase: PatrolPhase?) {
        for (home in listOf(false, true)) {
            assertFalse("${phase?.name ?: "null"}, thenReturnHome=$home", ResumeCopy.restoresPaused(phase, thenReturnHome = home))
        }
    }

    @Test
    fun aParkedCheckpointIsNotRestoredPaused() {
        // Unchanged: it re-parks where the avatar stands (RETURNING_HOME on a zero-length leg, then PARKED), also under 走回家.
        assertNeverRestoredPaused(PatrolPhase.PARKED)
    }

    @Test
    fun aWalkingDwellingOrManualCheckpointIsNotRestoredPaused() {
        // Unchanged: the unvisited flowers are planned from the checkpoint position and walked. A flower being orbited counts
        // as visited; the joystick is process-local and does not come back.
        for (p in listOf(PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.MANUAL)) assertNeverRestoredPaused(p)
    }

    @Test
    fun aCheckpointTakenOnTheWayHomeIsNotRestoredPaused() {
        // Unchanged: it keeps walking home, to the saved home when that 回家 had one (returnTarget), else to the real home.
        assertNeverRestoredPaused(PatrolPhase.RETURNING_HOME)
    }

    @Test
    fun anUnreadableOrImpossiblePhaseIsNotRestoredPaused() {
        // null = no phase at all. IDLE / STARTING / STOPPING / SUSPENDED are never written (writeCheckpoint skips them) and
        // PatrolCheckpoint.fromJson reads an unknown name as WALKING. Unchanged: all of them walk on from the checkpoint position.
        val handledAbove = setOf(PatrolPhase.PAUSED, PatrolPhase.PARKED, PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.MANUAL, PatrolPhase.RETURNING_HOME)
        for (p in (PatrolPhase.entries - handledAbove) + null) assertNeverRestoredPaused(p)
    }

    // The dialog must tell what 從中斷點繼續 will do, and it asks the same rule the service obeys (restoresPaused).

    @Test
    fun aPausedCheckpointGetsThePausedText() {
        assertTrue(
            "the usual text promises to walk on; a PAUSED checkpoint comes back paused",
            ResumeCopy.usePausedText(PatrolPhase.PAUSED, real = false),
        )
    }

    @Test
    fun aPausedCheckpointKilledOnTheRealPositionKeepsTheRealPositionText() {
        // Resuming there is a jump, and the game already sees the real GPS: that is what the text must lead with
        // (the PAUSED-before-the-switch patrol still comes back paused; the jump is recorded as before).
        assertFalse(
            "the real-position text explains the jump and must not be replaced",
            ResumeCopy.usePausedText(PatrolPhase.PAUSED, real = true),
        )
    }

    @Test
    fun everyOtherCheckpointKeepsTheUsualText() {
        // Their 從中斷點繼續 does walk on (or re-park, or walk home), which is what the usual text says.
        for (p in (PatrolPhase.entries - PatrolPhase.PAUSED) + null) {
            assertFalse(p?.name ?: "null", ResumeCopy.usePausedText(p, real = false))
        }
    }

    // The paused text itself, read from strings_resume.xml and formatted like getString(R.string.x, args) does (String.format):
    // a wrong placeholder takes the Activity down (StringFormatTest, 2026-09-14), here at app start, in the resume dialog.
    private fun pausedDialogText(): String {
        val f = listOf(File("src/main/res/values-zh/strings_resume.xml"), File("app/src/main/res/values-zh/strings_resume.xml")).first { it.exists() }
        val m = Regex("""<string name="dlg_resume_msg_paused">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(f.readText())
            ?: error("dlg_resume_msg_paused is missing from strings_resume.xml")
        return m.groupValues[1].replace("\\n", "\n")
    }

    /** What MainActivity.maybeOfferResume passes, in the usual text's order: age, route, distance walked, distance from home. */
    private fun shownPausedDialog(): String = String.format(pausedDialogText(), "AGE", "ROUTE", "WALKED", "FROMHOME")

    @Test
    fun thePausedTextTakesTheDialogsFourArgumentsAndLosesNone() {
        val shown = shownPausedDialog()
        for (arg in listOf("AGE", "ROUTE", "WALKED", "FROMHOME")) assertTrue("lost $arg: $shown", shown.contains(arg))
    }

    @Test
    fun thePausedTextSaysTheAvatarStaysPausedUntilContinue() {
        val shown = shownPausedDialog()
        assertTrue("must say it stays paused: $shown", shown.contains("維持暫停"))
        assertTrue("must say 繼續 is what makes it walk: $shown", shown.contains("按「繼續」"))
    }

    @Test
    fun thePausedTextDoesNotPromiseToWalkOnLikeTheUsualText() {
        // The usual text says 「從中斷點繼續」會直接接回那個位置往下走 - exactly what a paused checkpoint no longer does.
        assertFalse("carried over the usual text's promise: ${shownPausedDialog()}", shownPausedDialog().contains("接回那個位置往下走"))
    }
}
