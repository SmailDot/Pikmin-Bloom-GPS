package app.pikminbloom.gps

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Taiwanese players say 瞬移, not 跳躍, and an instant change of position is 瞬移回 or 瞬移到, not 跳回. So no
 * traditional-Chinese user-facing text may use 跳躍 or 跳回. Comments and identifiers may keep them. Reads the sources the
 * way RetiredSettingsTest reads the XML.
 */
class WordingTest {

    /** The zh words that must not reach a user: 跳躍 (jump) and 跳回 (jump back). */
    private val forbidden = listOf("跳躍", "跳回")

    private fun root(path: String): File =
        listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")
    }

    private fun offends(line: String): Boolean = forbidden.any { it in line }

    @Test
    fun theTraditionalChineseStringResourcesSayTeleportNotJump() {
        val offenders = root("res/values").listFiles().orEmpty()
            .filter { it.extension == "xml" }
            .flatMap { f -> f.readLines().filter { offends(it) }.map { "${f.name}: ${it.trim()}" } }
        assertTrue("zh string resources still say 跳躍 or 跳回:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun theTraditionalChineseTextsInCodeSayTeleportNotJump() {
        val offenders = root("java").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().filter { offends(it) && !isComment(it) }.map { "${f.name}: ${it.trim()}" } }
            .toList()
        assertTrue("zh texts in code still say 跳躍 or 跳回:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
