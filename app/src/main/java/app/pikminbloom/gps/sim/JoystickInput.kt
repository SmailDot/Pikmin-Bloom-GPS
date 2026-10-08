package app.pikminbloom.gps.sim

import app.pikminbloom.gps.geo.GeoMath

/**
 * What the floating pad asks for. [enabled] is the on/off switch; [bearingDeg] is the last heading the
 * knob was pushed to (null until it has been pushed once) and [magnitude] how far it is pushed now
 * (0 = finger lifted). [cruise] (定速巡航) keeps walking along [bearingDeg] at full speed after the
 * finger lifts; pushing the knob while it is on re-aims it. The engine reads [drive] once per tick.
 */
data class JoystickInput(
    val enabled: Boolean = false,
    val bearingDeg: Double? = null,
    val magnitude: Double = 0.0,
    val cruise: Boolean = false,
) {
    /** (bearing, magnitude) to move with this tick; with no heading yet it stands still. */
    fun drive(): Pair<Double, Double> {
        val heading = bearingDeg ?: return 0.0 to 0.0
        val speed = when {
            !enabled -> 0.0
            magnitude > 0.0 -> magnitude
            cruise -> 1.0
            else -> 0.0
        }
        return heading to speed
    }

    /** A knob report. A lifted knob (0) keeps the last heading: JoystickView sends (0, 0) on release. */
    fun steer(bearing: Double, mag: Double): JoystickInput {
        val m = mag.coerceIn(0.0, 1.0)
        if (m <= 0.0) return copy(magnitude = 0.0)
        return copy(bearingDeg = GeoMath.normalizeBearing(bearing), magnitude = m)
    }

    /**
     * Lock / unlock. A lock is refused on a closed pad and before the knob has ever been pushed (there
     * is no heading to keep, so it would cruise north); unlocking always works.
     */
    fun withCruise(on: Boolean): JoystickInput =
        if (on && (!enabled || bearingDeg == null)) this else copy(cruise = on)
}
