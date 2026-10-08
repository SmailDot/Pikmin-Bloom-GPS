package app.pikminbloom.gps

import app.pikminbloom.gps.data.Decor
import app.pikminbloom.gps.data.LocationJump
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.ui.DecorHuntFormat
import app.pikminbloom.gps.ui.JoystickLayout
import app.pikminbloom.gps.ui.RealModeCopy
import app.pikminbloom.gps.ui.TripChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The texts built in Kotlin, in English and Japanese (2026-10-08); the Chinese ones are tested next to each formatter. */
class LangTextsTest {
    private val han = Regex("[\\u4e00-\\u9fff]")
    private val kana = Regex("[\\u3040-\\u30ff]")

    @Test
    fun langFollowsTheResourceCode() {
        assertEquals(Lang.EN, Lang.of("en"))
        assertEquals(Lang.ZH, Lang.of("zh"))
        assertEquals(Lang.JA, Lang.of("ja"))
        assertEquals("anything unknown is English", Lang.EN, Lang.of("ko"))
    }

    @Test
    fun tripChoicesInEnglishAndJapanese() {
        val cfg = PatrolConfig(speedMps = 18.0 / 3.6, strideM = 0.70)
        val list = TripChoices.build(62_000.0, cfg, injectSteps = true)
        assertEquals("Walk 18 km/h — about 3 h 27 min, writes about 88,571 steps", TripChoices.label(list[0], true, Lang.EN))
        assertEquals("Car 90 km/h — about 42 min, no steps", TripChoices.label(list[2], true, Lang.EN))
        assertEquals("徒歩 18 km/h — 約 3 時間 27 分、約 8.9 万歩を書き込み", TripChoices.label(list[0], true, Lang.JA))
        assertEquals("車 90 km/h — 約 42 分、歩数なし", TripChoices.label(list[2], true, Lang.JA))
        assertEquals("“浅草寺” is about 62 km away. How do you want to get there?", TripChoices.title("浅草寺", 62_000.0, Lang.EN))
        assertEquals("「浅草寺」まで約 62 km。どうやって行きますか？", TripChoices.title("浅草寺", 62_000.0, Lang.JA))
        assertEquals("under 1 min", TripChoices.etaText(0, Lang.EN))
        assertEquals("8,571 歩", TripChoices.stepsText(8_571, Lang.JA))
    }

    @Test
    fun realModeCopyIsTranslated() {
        fun texts(lang: Lang) = listOf(
            RealModeCopy.stateLine(false, lang), RealModeCopy.stateLine(true, lang),
            RealModeCopy.switchLabel(false, lang), RealModeCopy.confirmTitle(true, lang),
            RealModeCopy.confirmMessage(true, 2_230_000.0, lang), RealModeCopy.confirmMessage(false, null, lang),
            RealModeCopy.mapsTitle(lang), RealModeCopy.mapsNo(lang), RealModeCopy.mapsMessage(1_000_000.0, lang),
            RealModeCopy.buttonDescription(true, lang),
            RealModeCopy.staleSwitchText(toReal = false, phase = PatrolPhase.WALKING, lang = lang)!!,
        )
        for (t in texts(Lang.EN)) assertFalse("English: $t", han.containsMatchIn(t))
        texts(Lang.JA).zip(texts(Lang.ZH)).forEach { (ja, zh) -> assertTrue("Japanese differs from Chinese: $ja", ja != zh) }
        assertTrue(texts(Lang.JA).any { kana.containsMatchIn(it) })
        assertEquals("Switch to real location…", RealModeCopy.switchLabel(suspended = false, lang = Lang.EN))
        assertEquals("Location: Virtual location (mocking)", RealModeCopy.stateLine(false, Lang.EN))
        assertTrue(RealModeCopy.confirmMessage(true, 2_230_000.0, Lang.EN).contains("about 2,230 km"))
        assertTrue(RealModeCopy.confirmMessage(true, null, Lang.JA).contains("「仮想位置に戻る…」"))
        assertNull(RealModeCopy.staleSwitchText(toReal = true, phase = PatrolPhase.WALKING, lang = Lang.EN))
    }

    @Test
    fun decorNamesInEveryLanguageAndAFilterInAnyOfThem() {
        for (d in Decor.entries) {
            assertFalse("${d.name}: English", han.containsMatchIn(d.nameIn(Lang.EN)))
        }
        val coffee = Decor.COFFEE_CUP
        assertTrue(DecorHuntFormat.matchesFilter(coffee, "coffee"))
        assertTrue(DecorHuntFormat.matchesFilter(coffee, "コーヒー"))
        assertTrue(DecorHuntFormat.matchesFilter(coffee, "咖啡"))
        assertFalse(DecorHuntFormat.matchesFilter(coffee, "pizza"))
        assertEquals("names saved in any language load back", Decor.COFFEE_CUP, Decor.byName("Coffee cup"))
        assertEquals(Decor.COFFEE_CUP, Decor.byName("咖啡杯"))
        assertEquals(Decor.COFFEE_CUP, Decor.byName("COFFEE_CUP"))
    }

    @Test
    fun decorHuntFormatInEnglishAndJapanese() {
        assertEquals("850 m", DecorHuntFormat.formatDistance(850.0, Lang.EN))
        assertEquals("1.2 km", DecorHuntFormat.formatDistance(1_200.0, Lang.JA))
        assertEquals("Pure spot", DecorHuntFormat.purityLabel(emptyList(), Lang.EN))
        assertEquals("単一スポット", DecorHuntFormat.purityLabel(emptyList(), Lang.JA))
        assertEquals("Checking…", DecorHuntFormat.purityLabel(null, Lang.EN))
        val five = listOf(Decor.STICKER, Decor.CLOVER, Decor.CHEF_HAT, Decor.SHELL, Decor.STAMP)
        assertTrue(DecorHuntFormat.purityLabel(five, Lang.EN).startsWith("Mixed: "))
        assertTrue(DecorHuntFormat.purityLabel(five, Lang.EN).endsWith("and more (5 kinds)"))
        assertTrue(DecorHuntFormat.purityLabel(five, Lang.JA).endsWith("など 5 種類"))
        assertEquals("Walk (4.7 km/h)", DecorHuntFormat.travelModeLabel(TravelMode.WALK, PatrolConfig(), Lang.EN))
        assertEquals("飛行機（600 km/h）", DecorHuntFormat.travelModeLabel(TravelMode.PLANE, PatrolConfig(), Lang.JA))
    }

    @Test
    fun smallLabels() {
        assertEquals(listOf("Walk", "Other", "Car", "Plane"),
            listOf(TravelMode.WALK, TravelMode.CAR, TravelMode.HIGHWAY, TravelMode.PLANE).map { it.labelIn(Lang.EN) })
        assertEquals(listOf("徒歩", "その他", "車", "飛行機"),
            listOf(TravelMode.WALK, TravelMode.CAR, TravelMode.HIGHWAY, TravelMode.PLANE).map { it.labelIn(Lang.JA) })
        assertEquals(listOf("S", "M", "L"), JoystickLayout.SIZES_DP.map { JoystickLayout.label(it, Lang.EN) })
        val min = 60_000L
        assertEquals("Last jump 1,850 km · 42 min ago", LocationJump.readout(LocationJump(0L, 1_850_000.0), 42 * min, Lang.EN))
        assertEquals("前回のジャンプ 850 m · 2 時間 5 分前", LocationJump.readout(LocationJump(0L, 850.0), 125 * min, Lang.JA))
    }
}
