package app.pikminbloom.gps

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * getString(R.string.x, args) is String.format underneath: a %d placeholder fed a String throws
 * IllegalFormatConversionException and takes the Activity down. The vehicle-speed change turned
 * the km/h argument into a String (17.5 must survive), which is exactly what happened to the
 * 移動方式 dialog on 2026-09-14 (reported as 「無回應」). Checked in all three languages.
 */
class StringFormatTest {
    private fun resource(folder: String, file: String, name: String): String {
        val f = listOf(File("src/main/res/$folder/$file"), File("app/src/main/res/$folder/$file")).first { it.exists() }
        val m = Regex("""<string name="$name">(.*?)</string>""").find(f.readText()) ?: error("$name missing in $folder")
        return m.groupValues[1].replace("\\n", "\n").replace("\\'", "'")
    }

    @Test
    fun travelModeItemsFormatWithAStringSpeed() {
        assertEquals("汽機車（45 km/h）", String.format(resource("values","strings_live.xml", "travel_item"), "汽機車", "45"))
        assertEquals("腳踏車（17.5 km/h，不計步）", String.format(resource("values","strings_live.xml", "travel_item_no_steps"), "腳踏車", "17.5"))
        for (folder in listOf("values-en", "values-ja")) {
            val item = String.format(resource(folder, "strings_live.xml", "travel_item"), "X", "45")
            val noSteps = String.format(resource(folder, "strings_live.xml", "travel_item_no_steps"), "X", "17.5")
            assert("45" in item && "X" in item) { "$folder: $item" }
            assert("17.5" in noSteps && "X" in noSteps) { "$folder: $noSteps" }
        }
    }
}
