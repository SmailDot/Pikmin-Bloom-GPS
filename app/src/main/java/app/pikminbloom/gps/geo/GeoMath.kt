package app.pikminbloom.gps.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical (haversine) geodesy helpers.
 *
 * Pure Kotlin on purpose: no `android.*` imports, so every route / simulation decision can be
 * verified by plain JUnit tests on the JVM. Distances are in metres, bearings in degrees.
 *
 * The sphere radius is the IUGG mean Earth radius (6 371 008.8 m). Over the few kilometres this app
 * ever plans, the error against WGS-84 ellipsoidal distances is well under 0.5 %, which is far below
 * the GPS noise we deliberately inject anyway.
 */
object GeoMath {

    /** IUGG mean Earth radius, metres. */
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    private const val DEG_TO_RAD: Double = Math.PI / 180.0
    private const val RAD_TO_DEG: Double = 180.0 / Math.PI

    /** Great-circle distance between [a] and [b] in metres. */
    fun distanceM(a: LatLng, b: LatLng): Double {
        val phi1 = a.lat * DEG_TO_RAD
        val phi2 = b.lat * DEG_TO_RAD
        val dPhi = (b.lat - a.lat) * DEG_TO_RAD
        val dLambda = wrapDegrees(b.lon - a.lon) * DEG_TO_RAD
        val sinHalfPhi = sin(dPhi * 0.5)
        val sinHalfLambda = sin(dLambda * 0.5)
        val h = sinHalfPhi * sinHalfPhi + cos(phi1) * cos(phi2) * sinHalfLambda * sinHalfLambda
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(h).coerceAtMost(1.0))
    }

    /** Initial great-circle bearing from [a] to [b], in `[0, 360)` degrees clockwise from north. */
    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val phi1 = a.lat * DEG_TO_RAD
        val phi2 = b.lat * DEG_TO_RAD
        val dLambda = wrapDegrees(b.lon - a.lon) * DEG_TO_RAD
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        if (abs(y) < 1e-15 && abs(x) < 1e-15) return 0.0
        return normalizeBearing(atan2(y, x) * RAD_TO_DEG)
    }

    /**
     * Point reached by leaving [from] on the initial bearing [bearingDeg] and following the great
     * circle for [distanceM] metres. Negative distances walk backwards along the same great circle.
     */
    fun destination(from: LatLng, bearingDeg: Double, distanceM: Double): LatLng {
        if (distanceM == 0.0) return from
        val delta = distanceM / EARTH_RADIUS_M
        val theta = bearingDeg * DEG_TO_RAD
        val phi1 = from.lat * DEG_TO_RAD
        val lambda1 = from.lon * DEG_TO_RAD
        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val sinDelta = sin(delta)
        val cosDelta = cos(delta)
        val sinPhi2 = (sinPhi1 * cosDelta + cosPhi1 * sinDelta * cos(theta)).coerceIn(-1.0, 1.0)
        val phi2 = asin(sinPhi2)
        val lambda2 = lambda1 + atan2(
            sin(theta) * sinDelta * cosPhi1,
            cosDelta - sinPhi1 * sinPhi2,
        )
        return LatLng(
            lat = (phi2 * RAD_TO_DEG).coerceIn(-90.0, 90.0),
            lon = wrapDegrees(lambda2 * RAD_TO_DEG),
        )
    }

    /**
     * Point at [fraction] of the way along the great circle from [a] to [b].
     * `0.0` returns [a], `1.0` returns [b]; values outside `[0, 1]` extrapolate.
     */
    fun interpolate(a: LatLng, b: LatLng, fraction: Double): LatLng {
        if (fraction == 0.0) return a
        val total = distanceM(a, b)
        if (total < 1e-9) return a
        if (fraction == 1.0) return b
        return destination(a, bearingDeg(a, b), total * fraction)
    }

    /**
     * Local flat-Earth offset: moves [p] by [northM] metres north and [eastM] metres east.
     * Accurate to a few centimetres for the sub-kilometre offsets this app uses.
     */
    fun offsetMeters(p: LatLng, northM: Double, eastM: Double): LatLng {
        val metresPerDegLat = EARTH_RADIUS_M * DEG_TO_RAD
        val cosLat = cos(p.lat * DEG_TO_RAD)
        val metresPerDegLon = metresPerDegLat * (if (abs(cosLat) < 1e-9) 1e-9 else cosLat)
        val lat = (p.lat + northM / metresPerDegLat).coerceIn(-90.0, 90.0)
        val lon = wrapDegrees(p.lon + eastM / metresPerDegLon)
        return LatLng(lat, lon)
    }

    /** Folds any bearing into `[0, 360)`. */
    fun normalizeBearing(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        val r = deg % 360.0
        return if (r < 0.0) r + 360.0 else r
    }

    /** Folds a longitude-like delta into `[-180, 180)`. */
    private fun wrapDegrees(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        val r = (deg + 180.0) % 360.0
        return (if (r < 0.0) r + 360.0 else r) - 180.0
    }
}
