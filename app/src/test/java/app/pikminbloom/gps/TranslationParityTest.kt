package app.pikminbloom.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The three languages (2026-10-08) hold the same strings: English in values/ (also every other phone language),
 * Traditional Chinese in values-zh-rTW/, Japanese in values-ja/. A string missing from one of them shows English in the
 * middle of a Chinese screen (or fails the release build), and a format specifier that differs crashes getString.
 */
class TranslationParityTest {
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val folders = listOf("values", "values-zh-rTW", "values-ja")
    private val fmt = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXcbeEgGoh%]""")

    /** name -> text of every translatable <string>, plus "name[i]" for string-array items. */
    private fun strings(folder: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val files = File(res, folder).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
        for (f in files.sortedBy { it.name }) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val root = doc.documentElement
            for (i in 0 until root.childNodes.length) {
                val el = root.childNodes.item(i) as? Element ?: continue
                if (el.getAttribute("translatable") == "false") continue
                val name = el.getAttribute("name")
                when (el.tagName) {
                    "string" -> out["${f.name}:$name"] = el.textContent
                    "string-array" -> {
                        val items = el.getElementsByTagName("item")
                        for (j in 0 until items.length) out["${f.name}:$name[$j]"] = items.item(j).textContent
                    }
                }
            }
        }
        return out
    }

    @Test
    fun everyLanguageHasTheSameStrings() {
        val zh = strings("values-zh-rTW")
        assertTrue("found the Chinese strings", zh.size > 400)
        for (folder in listOf("values", "values-ja")) {
            val other = strings(folder)
            assertEquals("$folder: missing", emptySet<String>(), zh.keys - other.keys)
            assertEquals("$folder: extra", emptySet<String>(), other.keys - zh.keys)
        }
    }

    @Test
    fun formatSpecifiersMatch() {
        val zh = strings("values-zh-rTW")
        for (folder in listOf("values", "values-ja")) {
            val other = strings(folder)
            for ((key, text) in zh) {
                if (key.endsWith(":lang_code")) continue
                val theirs = other[key] ?: continue
                assertEquals("$folder $key", fmt.findAll(text).map { it.value }.sorted().toList(),
                    fmt.findAll(theirs).map { it.value }.sorted().toList())
            }
        }
    }

    @Test
    fun eachFolderSaysWhichLanguageItIs() {
        assertEquals(listOf("en", "zh", "ja"), folders.map { strings(it)["strings.xml:lang_code"] })
    }

    @Test
    fun noChineseLeftInEnglish() {
        val han = Regex("[\\u4e00-\\u9fff]")
        val left = strings("values").filterValues { han.containsMatchIn(it) }
        assertEquals(emptyMap<String, String>(), left)
    }

    @Test
    fun chineseFolderNamesTheTraditionalScript() {
        assertTrue(
            "src/main/res/values-zh-rTW is missing: the Traditional Chinese strings belong there",
            File(res, "values-zh-rTW").isDirectory,
        )
        assertFalse(
            "src/main/res/values-zh exists: Android reads a bare values-zh as Simplified Chinese, so a zh-TW " +
                "(Traditional) phone never matches it and falls back to English. Rename it to values-zh-rTW.",
            File(res, "values-zh").exists(),
        )
    }
}
