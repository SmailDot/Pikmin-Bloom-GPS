package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.TravelMode
import java.util.Locale
import kotlin.math.roundToLong

/** One way to cover a 前往後停在這裡 trip: [mode] null = on foot at the configured speed. */
data class TripChoice(val mode: TravelMode?, val kmh: Double, val etaSec: Long, val steps: Long?)

/**
 * 前往後停在這裡 over a long way (2026-10-08): instead of silently walking it - 18 km/h for 8 hours is some 200,000 steps -
 * the app asks once, in one list, how to get there: on foot (with the steps it would write) or by 其他／汽車／飛機 (no
 * steps). Only for 前往後停在這裡; 立刻前往 is for short dashes (mushrooms) and never asks. Pure, so it is unit-tested;
 * Traditional Chinese by design, like DecorHuntFormat.
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

    /** "走路 18 km/h — 約 3 小時 27 分，寫入約 8.9 萬步" / "汽車 90 km/h — 約 41 分，不寫步數". */
    fun label(c: TripChoice, injectSteps: Boolean): String {
        val name = c.mode?.label ?: "走路"
        val steps = when {
            c.mode != null -> "不寫步數"
            !injectSteps || c.steps == null -> "不寫步數（寫入步數已關閉）"
            else -> "寫入約 ${stepsText(c.steps)}"
        }
        return "$name ${kmhText(c.kmh)} km/h — ${etaText(c.etaSec)}，$steps"
    }

    fun title(flowerName: String, distanceM: Double): String =
        "到「$flowerName」約 ${String.format(Locale.US, "%,.0f", distanceM / 1000.0)} km，要怎麼過去？"

    fun etaText(sec: Long): String {
        val min = (sec + 59) / 60
        return when {
            min < 1 -> "不到 1 分鐘"
            min < 60 -> "約 $min 分"
            else -> "約 ${min / 60} 小時 ${min % 60} 分"
        }
    }

    fun stepsText(steps: Long): String =
        if (steps < 10_000) String.format(Locale.US, "%,d 步", steps) else String.format(Locale.US, "%.1f 萬步", steps / 10_000.0)

    private fun eta(distanceM: Double, kmh: Double): Long = if (kmh <= 0.0) 0L else (distanceM / (kmh / 3.6)).roundToLong()

    private fun kmhText(kmh: Double): String =
        if (kmh == kmh.roundToLong().toDouble()) kmh.roundToLong().toString() else String.format(Locale.US, "%.1f", kmh)
}
