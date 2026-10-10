package app.pikminbloom.gps

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Taiwanese players say 瞬移, not 跳躍: no traditional-Chinese user-facing text may use 跳躍. Comments and identifiers may
 * keep it. Reads the sources the way RetiredSettingsTest reads the XML.
 */
class WordingTest {

    private fun root(path: String): File =
        listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")
    }

    @Test
    fun theTraditionalChineseStringResourcesSayTeleportNotJump() {
        val offenders = root("res/values").listFiles().orEmpty()
            .filter { it.extension == "xml" }
            .flatMap { f -> f.readLines().filter { "跳躍" in it }.map { "${f.name}: ${it.trim()}" } }
        assertTrue("zh string resources still say 跳躍:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun theTraditionalChineseTextsInCodeSayTeleportNotJump() {
        val offenders = root("java").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().filter { "跳躍" in it && !isComment(it) }.map { "${f.name}: ${it.trim()}" } }
            .toList()
        assertTrue("zh texts in code still say 跳躍:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
