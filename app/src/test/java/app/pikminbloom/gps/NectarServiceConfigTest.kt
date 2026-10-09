package app.pikminbloom.gps

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The accessibility service that 自動探險 reads the screen through. AccessibilityService.takeScreenshot
 * fails unless the service declares canTakeScreenshot, and the failure is quiet: the run would just see no frame.
 */
class NectarServiceConfigTest {
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    @Test
    fun theServiceDeclaresCanTakeScreenshot() {
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(res, "xml/accessibility_nectar.xml")).documentElement
        assertEquals(
            "xml/accessibility_nectar.xml must set android:canTakeScreenshot=\"true\": without it takeScreenshot fails " +
                "and 自動探險 sees no frame",
            "true",
            root.getAttributeNS("http://schemas.android.com/apk/res/android", "canTakeScreenshot"),
        )
    }
}
