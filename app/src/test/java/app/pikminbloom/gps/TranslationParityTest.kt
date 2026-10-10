package app.pikminbloom.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The three languages hold the same strings. Traditional Chinese is the default: it lives in values/, which every
 * phone language other than English and Japanese gets. English is in values-en/, Japanese in values-ja/. A string
 * missing from one of them shows the default language in the middle of another screen, and a format specifier that
 * differs crashes getString.
 */
class TranslationParityTest {
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val folders = listOf("values", "values-en", "values-ja")
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
        val zh = strings("values")
        assertTrue("found the Traditional Chinese strings", zh.size > 400)
        for (folder in listOf("values-en", "values-ja")) {
            val other = strings(folder)
            assertEquals("$folder: missing", emptySet<String>(), zh.keys - other.keys)
            assertEquals("$folder: extra", emptySet<String>(), other.keys - zh.keys)
        }
    }

    @Test
    fun formatSpecifiersMatch() {
        val zh = strings("values")
        for (folder in listOf("values-en", "values-ja")) {
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
        assertEquals(listOf("zh", "en", "ja"), folders.map { strings(it)["strings.xml:lang_code"] })
    }

    @Test
    fun noChineseLeftInEnglish() {
        val han = Regex("[\\u4e00-\\u9fff]")
        val en = strings("values-en")
        assertTrue("found the English strings", en.size > 400)
        assertEquals(emptyMap<String, String>(), en.filterValues { han.containsMatchIn(it) })
    }

    @Test
    fun defaultLanguageIsTraditionalChinese() {
        assertEquals("zh", strings("values")["strings.xml:lang_code"])
        assertTrue(
            "src/main/res/values-en is missing: English must live there, or every phone gets Chinese",
            File(res, "values-en").isDirectory,
        )
        // A bare values-zh is read as Simplified; a values-zh-rTW would only reach Traditional phones. Neither is wanted:
        // Traditional Chinese is the default, so no values-zh* folder may exist.
        val zhFolders = res.listFiles { f -> f.name.startsWith("values-zh") }.orEmpty().map { it.name }
        assertEquals("no values-zh* folder: the default folder holds Traditional Chinese", emptyList<String>(), zhFolders)
    }
}
