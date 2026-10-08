package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.i18n.tr
import java.util.Locale
import kotlin.math.roundToLong

/** One way to cover a 前往後停在這裡 trip: [mode] null = on foot at the configured speed. */
data class TripChoice(val mode: TravelMode?, val kmh: Double, val etaSec: Long, val steps: Long?)

/**
 * 前往後停在這裡 over a long way (2026-10-08): instead of silently walking it - 18 km/h for 8 hours is some 200,000 steps -
 * the app asks once, in one list, how to get there: on foot (with the steps it would write) or by 其他／汽車／飛機 (no
 * steps). Only for 前往後停在這裡; 立刻前往 is for short dashes (mushrooms) and never asks. Pure, so it is unit-tested,
 * in the app's three languages ([Lang]).
 */
object TripChoices {
    /** Above this many kilometres the question is asked, until the user sets another number in Settings. */
    const val DEFAULT_ASK_KM = 15.0

    /** The vehicles offered, in order (the retired bike is not one of them). */
    val VEHICLES = listOf(TravelMode.CAR, TravelMode.HIGHWAY, TravelMode.PLANE)

    /**
     * Ask when on foot (a vehicle in force already writes no steps, so there is nothing to decide), the distance is
     * known and longer than [askKm]; 0 or less never asks.
     */
    fun shouldAsk(distanceM: Double?, askKm: Double, vehicleInForce: TravelMode?): Boolean {
        if (distanceM == null || askKm <= 0.0) return false
        if (vehicleInForce != null && !vehicleInForce.countsSteps) return false
        return distanceM > askKm * 1000.0
    }

    /** Walking first, then [VEHICLES]; steps only on foot, and only when 寫入步數 is on ([injectSteps]). */
    fun build(distanceM: Double, config: PatrolConfig, injectSteps: Boolean): List<TripChoice> {
        val walkKmh = config.speedMps * 3.6
        val walk = TripChoice(null, walkKmh, eta(distanceM, walkKmh), if (injectSteps) (distanceM / config.strideM).roundToLong() else null)
        return listOf(walk) + VEHICLES.map { m -> config.speedKmhOf(m).let { TripChoice(m, it, eta(distanceM, it), null) } }
    }

    /** "走路 18 km/h — 約 3 小時 27 分，寫入約 8.9 萬步" / "Car 90 km/h — about 42 min, no steps". */
    fun label(c: TripChoice, injectSteps: Boolean, lang: Lang = Lang.current): String {
        val name = c.mode?.labelIn(lang) ?: tr("走路", "Walk", "徒歩", lang)
        val steps = when {
            c.mode != null -> tr("不寫步數", "no steps", "歩数なし", lang)
            !injectSteps || c.steps == null ->
                tr("不寫步數（寫入步數已關閉）", "no steps (Write steps is off)", "歩数なし（歩数の書き込みはオフ）", lang)
            else -> tr("寫入約 ${stepsText(c.steps, lang)}", "writes about ${stepsText(c.steps, lang)}", "約 ${stepsText(c.steps, lang)}を書き込み", lang)
        }
        return "$name ${kmhText(c.kmh)} km/h — ${etaText(c.etaSec, lang)}${tr("，", ", ", "、", lang)}$steps"
    }

    fun title(flowerName: String, distanceM: Double, lang: Lang = Lang.current): String {
        val km = String.format(Locale.US, "%,.0f", distanceM / 1000.0)
        return tr(
            "到「$flowerName」約 $km km，要怎麼過去？",
            "\u201C$flowerName\u201D is about $km km away. How do you want to get there?",
            "「$flowerName」まで約 $km km。どうやって行きますか？",
            lang,
        )
    }

    fun etaText(sec: Long, lang: Lang = Lang.current): String {
        val min = (sec + 59) / 60
        return when {
            min < 1 -> tr("不到 1 分鐘", "under 1 min", "1分未満", lang)
            min < 60 -> tr("約 $min 分", "about $min min", "約 $min 分", lang)
            else -> tr("約 ${min / 60} 小時 ${min % 60} 分", "about ${min / 60} h ${min % 60} min", "約 ${min / 60} 時間 ${min % 60} 分", lang)
        }
    }

    /** 萬 (10,000) groups in Chinese and Japanese; English spells the number out. */
    fun stepsText(steps: Long, lang: Lang = Lang.current): String = when {
        lang == Lang.EN -> String.format(Locale.US, "%,d steps", steps)
        steps < 10_000 -> String.format(Locale.US, tr("%,d 步", "", "%,d 歩", lang), steps)
        else -> String.format(Locale.US, tr("%.1f 萬步", "", "%.1f 万歩", lang), steps / 10_000.0)
    }

    private fun eta(distanceM: Double, kmh: Double): Long = if (kmh <= 0.0) 0L else (distanceM / (kmh / 3.6)).roundToLong()

    private fun kmhText(kmh: Double): String =
        if (kmh == kmh.roundToLong().toDouble()) kmh.roundToLong().toString() else String.format(Locale.US, "%.1f", kmh)
}
