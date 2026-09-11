package app.pikminbloom.gps

import app.pikminbloom.gps.data.Decor
import app.pikminbloom.gps.data.OverpassClient.DecorHit
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.ui.DecorHuntFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorHuntFormatTest {

    private val here = LatLng(22.7583, 120.3379)

    private fun hit(competing: List<Decor>?) =
        DecorHit(Decor.BOTTLE_CAP, here, "7-ELEVEN 測試門市", 320.0, competing)

    // ---------------------------------------------------------------- filter

    @Test
    fun blankFilterShowsTheWholeCatalogue() {
        assertEquals(Decor.entries.size, DecorHuntFormat.filterDecor("").size)
        assertEquals(Decor.entries.size, DecorHuntFormat.filterDecor("   ").size)
        assertEquals(Decor.entries.toList(), DecorHuntFormat.filterDecor(""))
    }

    @Test
    fun filterMatchesDecorName() {
        assertEquals(listOf(Decor.BOTTLE_CAP), DecorHuntFormat.filterDecor("瓶蓋"))
        assertEquals(listOf(Decor.BOTTLE_CAP), DecorHuntFormat.filterDecor(" 瓶 "))
    }

    @Test
    fun filterMatchesPlaceName() {
        assertEquals(listOf(Decor.BOTTLE_CAP), DecorHuntFormat.filterDecor("便利商店"))
        val restaurants = DecorHuntFormat.filterDecor("餐廳")
        assertTrue(restaurants.containsAll(listOf(Decor.CHEF_HAT, Decor.PIZZA, Decor.CURRY, Decor.KIMCHI, Decor.TACO, Decor.SUSHI)))
        assertFalse(restaurants.contains(Decor.BOTTLE_CAP))
    }

    @Test
    fun filterWithNoMatchIsEmptyAndKeepsCatalogueOrder() {
        assertTrue(DecorHuntFormat.filterDecor("zzz").isEmpty())
        val shops = DecorHuntFormat.filterDecor("店")
        val expected = Decor.entries.filter { DecorHuntFormat.matchesFilter(it, "店") }
        assertEquals(expected, shops)
        assertTrue(shops.size in 2 until Decor.entries.size)
    }

    // ---------------------------------------------------------------- distance

    @Test
    fun distanceIsMetresBelowOneKilometre() {
        assertEquals("0 公尺", DecorHuntFormat.formatDistance(0.0))
        assertEquals("850 公尺", DecorHuntFormat.formatDistance(850.4))
        assertEquals("999 公尺", DecorHuntFormat.formatDistance(999.2))
        assertEquals("0 公尺", DecorHuntFormat.formatDistance(-3.0))
    }

    @Test
    fun distanceIsKilometresToOneDecimalFromOneKilometre() {
        assertEquals("1.0 公里", DecorHuntFormat.formatDistance(1_000.0))
        assertEquals("1.2 公里", DecorHuntFormat.formatDistance(1_234.0))
        assertEquals("12.3 公里", DecorHuntFormat.formatDistance(12_345.0))
        assertEquals("48.0 公里", DecorHuntFormat.formatDistance(47_960.0))
    }

    // ---------------------------------------------------------------- purity

    @Test
    fun purityLabelWhileUnknown() {
        assertEquals("檢查中…", DecorHuntFormat.purityLabel(null))
        assertEquals("檢查中…", DecorHuntFormat.purityLabel(hit(null)))
    }

    @Test
    fun purityLabelForAPurePoint() {
        assertEquals("純點", DecorHuntFormat.purityLabel(emptyList()))
        val h = hit(emptyList())
        assertEquals(true, h.isPure)
        assertEquals("純點", DecorHuntFormat.purityLabel(h))
    }

    @Test
    fun purityLabelListsUpToThreeCompetingDecorNames() {
        assertEquals("混合：貼紙", DecorHuntFormat.purityLabel(listOf(Decor.STICKER)))
        assertEquals(
            "混合：貼紙、幸運草、廚師帽子",
            DecorHuntFormat.purityLabel(listOf(Decor.STICKER, Decor.CLOVER, Decor.CHEF_HAT)),
        )
        val five = listOf(Decor.STICKER, Decor.CLOVER, Decor.CHEF_HAT, Decor.COFFEE_CUP, Decor.MUSHROOM)
        assertEquals("混合：貼紙、幸運草、廚師帽子 等 5 種", DecorHuntFormat.purityLabel(five))
        assertEquals(false, hit(five).isPure)
    }

    @Test
    fun competingNamesDeduplicates() {
        assertEquals("貼紙、幸運草", DecorHuntFormat.competingNames(listOf(Decor.STICKER, Decor.STICKER, Decor.CLOVER)))
    }

    // ---------------------------------------------------------------- naming + clamps

    @Test
    fun waypointAndRouteNames() {
        assertEquals("瓶蓋·7-ELEVEN 測試門市", DecorHuntFormat.waypointName(Decor.BOTTLE_CAP, "7-ELEVEN 測試門市"))
        assertEquals("瓶蓋·便利商店", DecorHuntFormat.waypointName(Decor.BOTTLE_CAP, "  "))
        assertEquals("找瓶蓋", DecorHuntFormat.routeName(Decor.BOTTLE_CAP))
        assertEquals("找蒲公英", DecorHuntFormat.routeName(Decor.DANDELION))
    }

    @Test
    fun wanderRadiusIsClampedToTheStoreLimits() {
        assertEquals(40.0, DecorHuntFormat.clampWaypointRadius(60.0), 0.0)
        assertEquals(8.0, DecorHuntFormat.clampWaypointRadius(2.0), 0.0)
        assertEquals(25.0, DecorHuntFormat.clampWaypointRadius(25.0), 0.0)
        assertEquals(30, DecorHuntFormat.clampWanderMinutes(45))
        assertEquals(0, DecorHuntFormat.clampWanderMinutes(-1))
        assertEquals(15, DecorHuntFormat.clampWanderMinutes(15))
    }

    @Test
    fun travelModeLabelShowsSpeed() {
        assertEquals("步行（4.7 km/h）", DecorHuntFormat.travelModeLabel(TravelMode.WALK))
        assertEquals("汽機車（45 km/h）", DecorHuntFormat.travelModeLabel(TravelMode.CAR))
        assertEquals("飛機（600 km/h）", DecorHuntFormat.travelModeLabel(TravelMode.PLANE))
    }
}
