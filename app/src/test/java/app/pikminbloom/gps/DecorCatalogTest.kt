package app.pikminbloom.gps

import app.pikminbloom.gps.data.Decor
import app.pikminbloom.gps.data.TagRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorCatalogTest {

    @Test
    fun everyDecorHasRulesAndChineseLabels() {
        assertEquals(41, Decor.entries.size)
        for (d in Decor.entries) {
            assertTrue("${d.name} has no rules", d.rules.isNotEmpty())
            assertTrue("${d.name} decorName not Chinese", d.decorName.isNotBlank())
            assertTrue("${d.name} placeName not Chinese", d.placeName.isNotBlank())
            assertTrue(d.label.contains(d.decorName) && d.label.contains(d.placeName))
        }
    }

    @Test
    fun overpassFilterShapes() {
        assertEquals("""["shop"~"^(convenience)${'$'}"]""", TagRule("shop", values = listOf("convenience")).toOverpass())
        assertEquals("""["amenity"~"^(university|college)${'$'}"]""", TagRule("amenity", values = listOf("university", "college")).toOverpass())
        assertEquals("""["cuisine"~"sushi"]""", TagRule("cuisine", valueContains = "sushi").toOverpass())
        assertEquals("""["natural"]""", TagRule("natural").toOverpass())
        assertEquals("""["bridge"~"^(yes)${'$'}"]["name"]""", TagRule("bridge", values = listOf("yes"), requireName = true).toOverpass())
    }

    @Test
    fun localMatcherAgreesWithTheDeclaredRule() {
        val convenience = TagRule("shop", values = listOf("convenience"))
        assertTrue(convenience.matches(mapOf("shop" to "convenience")))
        assertFalse(convenience.matches(mapOf("shop" to "supermarket")))
        assertFalse(convenience.matches(mapOf("amenity" to "cafe")))

        val sushi = TagRule("cuisine", valueContains = "sushi")
        assertTrue(sushi.matches(mapOf("cuisine" to "sushi;japanese")))
        assertTrue(sushi.matches(mapOf("cuisine" to "SUSHI")))
        assertFalse(sushi.matches(mapOf("cuisine" to "ramen")))

        val namedBridge = TagRule("bridge", values = listOf("yes"), requireName = true)
        assertFalse("unnamed bridge should not match", namedBridge.matches(mapOf("bridge" to "yes")))
        assertTrue(namedBridge.matches(mapOf("bridge" to "yes", "name" to "高屏大橋")))
    }

    @Test
    fun classifyFindsEveryCategoryAPlaceBelongsTo() {
        assertEquals(listOf(Decor.BOTTLE_CAP), Decor.classify(mapOf("shop" to "convenience", "name" to "7-11")))
        assertEquals(listOf(Decor.COFFEE_CUP), Decor.classify(mapOf("amenity" to "cafe")))
        // A ramen restaurant is both "restaurant" and "ramen", which is exactly why purity matters.
        val ramen = Decor.classify(mapOf("amenity" to "restaurant", "cuisine" to "ramen"))
        assertTrue(ramen.containsAll(listOf(Decor.CHEF_HAT, Decor.RAMEN_KEYRING)))
        assertTrue(Decor.classify(mapOf("office" to "company")).isEmpty())
    }

    @Test
    fun lookupByEitherName() {
        assertEquals(Decor.DANDELION, Decor.byName("DANDELION"))
        assertEquals(Decor.DANDELION, Decor.byName("蒲公英"))
        assertEquals(null, Decor.byName("不存在的飾品"))
    }

    @Test
    fun purityRadiusMatchesTheGame() {
        assertEquals(120.0, Decor.PURITY_RADIUS_M, 0.0)
    }
}
