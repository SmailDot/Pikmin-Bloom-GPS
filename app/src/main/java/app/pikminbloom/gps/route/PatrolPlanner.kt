package app.pikminbloom.gps.route

import app.pikminbloom.gps.data.LoopMode
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import kotlin.math.max
import kotlin.math.sqrt

enum class SegmentKind { TRAVEL, ORBIT }

/**
 * One straight leg of a patrol.
 *
 * @param waypointIndex index (into the waypoint list) this leg belongs to, null for return-home legs.
 * @param arrivalAtEnd  true on the leg whose end is the "arrival" at [waypointIndex] (the simulator
 *                      fires `arrivedAtWaypoint` exactly when that end is passed).
 */
data class RouteSegment(
    val from: LatLng,
    val to: LatLng,
    val waypointIndex: Int?,
    val kind: SegmentKind,
    val arrivalAtEnd: Boolean = false,
) {
    val lengthM: Double = GeoMath.distanceM(from, to)
    val bearingDeg: Double = GeoMath.bearingDeg(from, to)
}

data class PatrolPlan(val segments: List<RouteSegment>, val totalLengthM: Double) {
    val isEmpty: Boolean get() = segments.isEmpty()
    val start: LatLng? get() = segments.firstOrNull()?.from
    val end: LatLng? get() = segments.lastOrNull()?.to

    companion object {
        val EMPTY = PatrolPlan(emptyList(), 0.0)
        fun of(segments: List<RouteSegment>) = PatrolPlan(segments, segments.sumOf { it.lengthM })
    }
}

/**
 * Turns Big Flower waypoints into a walkable plan.
 *
 * Pikmin Bloom plants one flower per 5 x 5 m cell and locks the cell for five minutes, so the time
 * spent inside a flower's circle is used for a boustrophedon ("lawn-mower") sweep with ~7 m spacing
 * that visits many distinct cells, rotated on every lap so consecutive visits do not retrace the
 * same lines.
 */
object PatrolPlanner {

    /** Distance between parallel sweep lines; a little above the 5 m cell size. */
    const val SWEEP_SPACING_M = 7.0

    /** Keep sweep chords this far inside the circle edge so short edge chords are avoided. */
    private const val EDGE_MARGIN_M = 3.5

    /** Shortest leg we are willing to emit. */
    const val MIN_SEGMENT_M = 6.0

    /** Smallest orbit radius that still produces a sensible sweep. */
    const val MIN_ORBIT_RADIUS_M = 8.0

    private const val SWEEP_ROTATION_DEG = 60.0

    fun orderFor(lap: Int, count: Int, mode: LoopMode): List<Int> {
        if (count <= 0 || lap < 0) return emptyList()
        val forward = (0 until count).toList()
        return when (mode) {
            LoopMode.LOOP -> forward
            LoopMode.ONCE -> if (lap == 0) forward else emptyList()
            LoopMode.PINGPONG -> when {
                count == 1 -> forward
                lap % 2 == 0 -> if (lap == 0) forward else forward.drop(1)
                else -> forward.reversed().drop(1)
            }
        }
    }

    /**
     * Plans one lap that starts at [start] and visits the waypoints in [order].
     * [lap] only changes the sweep orientation so repeated laps cover different cells.
     */
    fun planLap(
        start: LatLng,
        waypoints: List<Waypoint>,
        config: PatrolConfig,
        order: List<Int>,
        lap: Int = 0,
    ): PatrolPlan {
        val segments = ArrayList<RouteSegment>()
        var cursor = start
        for (idx in order) {
            val wp = waypoints.getOrNull(idx) ?: continue
            val center = wp.latLng
            val radius = max(wp.radiusM, MIN_ORBIT_RADIUS_M)
            val targetOrbitM = wp.dwellSec.coerceAtLeast(0) * config.speedMps
            val rotation = lap * 37.0 + idx * 11.0

            if (wp.dwellSec <= 0 || targetOrbitM < MIN_SEGMENT_M * 2) {
                // Pass-through visit: walk to the circle edge nearest to us.
                val edge = edgePointTowards(center, radius, cursor)
                // Kept even when tiny: the leg carries the arrival flag.
                segments.add(RouteSegment(cursor, edge, idx, SegmentKind.TRAVEL, arrivalAtEnd = true))
                cursor = edge
                continue
            }

            val orbit = orbitPath(center, radius, targetOrbitM, cursor, rotation)
            val first = orbit.first()
            segments.add(RouteSegment(cursor, first, idx, SegmentKind.TRAVEL, arrivalAtEnd = true))
            for (i in 1 until orbit.size) {
                segments.add(RouteSegment(orbit[i - 1], orbit[i], idx, SegmentKind.ORBIT))
            }
            cursor = orbit.last()
        }
        return PatrolPlan.of(segments)
    }

    fun planReturnHome(from: LatLng, home: LatLng): PatrolPlan =
        PatrolPlan.of(listOf(RouteSegment(from, home, null, SegmentKind.TRAVEL)))

    /** Point on the circle around [center] that is closest to [towards] (or due north if inside/at the center). */
    fun edgePointTowards(center: LatLng, radiusM: Double, towards: LatLng): LatLng {
        val d = GeoMath.distanceM(center, towards)
        val bearing = if (d < 0.5) 0.0 else GeoMath.bearingDeg(center, towards)
        return GeoMath.destination(center, bearing, radiusM)
    }

    /**
     * Boustrophedon sweep(s) inside the circle. Returns the ordered polyline (first point is the
     * sweep entry nearest to [entry]); total length is >= [targetLengthM] unless the circle is tiny,
     * in which case the sweep is repeated with rotated orientation until the target is met.
     */
    fun orbitPath(
        center: LatLng,
        radiusM: Double,
        targetLengthM: Double,
        entry: LatLng,
        rotationDeg: Double = 0.0,
    ): List<LatLng> {
        val r = max(radiusM, MIN_ORBIT_RADIUS_M)
        val path = ArrayList<LatLng>()
        var length = 0.0
        var last = entry
        var sweep = 0
        val approach = GeoMath.bearingDeg(entry, center).let { if (it.isNaN()) 0.0 else it }
        // Safety valve: each sweep adds ~pi*r^2/spacing metres, so this bound is generous.
        val maxSweeps = ((targetLengthM / (Math.PI * r * r / SWEEP_SPACING_M)) + 3).toInt().coerceAtMost(40)
        while (length < targetLengthM && sweep < maxSweeps) {
            val axis = GeoMath.normalizeBearing(approach + rotationDeg + sweep * SWEEP_ROTATION_DEG)
            val lines = sweepLines(center, r, axis)
            if (lines.isEmpty()) break
            // Order lines so that we start at the end nearest to where we are.
            val startFromFirst = GeoMath.distanceM(last, lines.first().first) <= GeoMath.distanceM(last, lines.last().first)
            val ordered = if (startFromFirst) lines else lines.reversed()
            var flip = GeoMath.distanceM(last, ordered.first().first) > GeoMath.distanceM(last, ordered.first().second)
            for (line in ordered) {
                val a = if (flip) line.second else line.first
                val b = if (flip) line.first else line.second
                if (path.isEmpty()) {
                    path.add(a)
                } else if (GeoMath.distanceM(last, a) >= 0.5) {
                    length += GeoMath.distanceM(last, a)
                    path.add(a)
                }
                length += GeoMath.distanceM(a, b)
                path.add(b)
                last = b
                flip = !flip
                if (length >= targetLengthM) break
            }
            sweep++
        }
        if (path.isEmpty()) path.add(edgePointTowards(center, r, entry))
        return path
    }

    /**
     * Parallel chords across the circle. [axisDeg] is the direction along which the chords are
     * offset (chords themselves run perpendicular to it). Each chord is returned as (start, end)
     * with start on the "left" side; the caller alternates direction.
     */
    private fun sweepLines(center: LatLng, r: Double, axisDeg: Double): List<Pair<LatLng, LatLng>> {
        val chordDir = GeoMath.normalizeBearing(axisDeg + 90.0)
        val usable = r - EDGE_MARGIN_M
        if (usable <= 0.0) return emptyList()
        val lines = ArrayList<Pair<LatLng, LatLng>>()
        var offset = -usable
        while (offset <= usable + 1e-9) {
            val half = sqrt(max(0.0, r * r - offset * offset)) - EDGE_MARGIN_M
            if (half * 2 >= MIN_SEGMENT_M) {
                val mid = GeoMath.destination(center, axisDeg, offset)
                val a = GeoMath.destination(mid, chordDir, -half)
                val b = GeoMath.destination(mid, chordDir, half)
                lines.add(a to b)
            }
            offset += SWEEP_SPACING_M
        }
        return lines
    }
}
