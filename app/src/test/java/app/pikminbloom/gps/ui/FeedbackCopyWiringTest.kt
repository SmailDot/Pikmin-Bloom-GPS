package app.pikminbloom.gps.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The auto-run log can be copied from the feedback dialog, and a GitHub report whose link leaves the log out gets a copy
 * of it on the clipboard. Like the other wiring tests, these read the sources: the copy itself needs a phone.
 */
class FeedbackCopyWiringTest {

    private fun source(file: String): String =
        listOf(File("src/main/java/app/pikminbloom/gps/ui/$file"), File("app/src/main/java/app/pikminbloom/gps/ui/$file"))
            .first { it.exists() }.readText()

    private fun layout(file: String): String =
        listOf(File("src/main/res/layout/$file"), File("app/src/main/res/layout/$file")).first { it.exists() }.readText()

    @Test
    fun theClipboardCopyIsLabelledAutoRunLog() {
        assertTrue("the copy does not use the auto-run log clip label", source("FeedbackDialog.kt").contains("ClipData.newPlainText(\"auto-run log\""))
    }

    @Test
    fun theDialogHasACopyButtonForTheLastRunWhenThereIsOne() {
        val dialog = source("FeedbackDialog.kt")
        assertTrue("the dialog has no copy button", dialog.contains("R.id.feedbackCopyLog") && dialog.contains("copyAutoRunLog("))
        assertTrue("the copy button is not in the layout", layout("dialog_feedback.xml").contains("feedbackCopyLog"))
    }

    @Test
    fun aGitHubReportWhoseLinkLeftTheLogOutCopiesIt() {
        assertTrue("the GitHub path does not copy a log it left out", source("FeedbackDialog.kt").contains("link.logLeftOut"))
    }
}
