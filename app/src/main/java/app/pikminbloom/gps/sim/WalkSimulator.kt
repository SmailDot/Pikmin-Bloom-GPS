package app.pikminbloom.gps.sim

import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.route.PatrolPlan
import app.pikminbloom.gps.route.SegmentKind
import kotlin.math.min
import kotlin.random.Random

/** One simulated GPS fix. */
data class Sample(
    val position: LatLng,
    val speedMps: Double,
    val bearingDeg: Double,
    val accuracyM: Float,
    val altitudeM: Double,
    val distanceDeltaM: Double,
    val segmentIndex: Int,
    val waypointIndex: Int?,
    val kind: SegmentKind,
    /** Set exactly once per waypoint visit, on the sample that passes the arrival point. */
    val arrivedAtWaypoint: Int?,
    /** True on the sample that reaches the end of the loaded plan. */
    val lapFinished: Boolean,
    /** False on vehicle legs: distance still accrues, but no steps are written for it. */
    val countsSteps: Boolean = true,
    /** Progress along the plan in metres (exact, without lateral noise). */
    val progressM: Double = 0.0,
)

/**
 * Moves along a [PatrolPlan] at walking speed and produces natural-looking fixes.
 *
 * Progress along the path is exact; the *reported* position carries a bounded random-walk lateral
 * noise (<= 1 m), the speed wobbles by +-[PatrolConfig.speedJitterPct] re-drawn every few seconds,
 * accuracy drifts slowly inside the configured band and altitude drifts a few decimetres.
 * Deterministic for a seeded [Random].
 */
class WalkSimulator(
    private val config: PatrolConfig,
    private val random: Random = Random.Default,
) {
    private var plan: PatrolPlan = PatrolPlan.EMPTY
    private var segIdx = 0
    private var distIntoSeg = 0.0
    private var progress = 0.0
    private var elapsedSec = 0.0

    private var targetSpeed = config.speedMps
    private var speedFactor = 1.0
    private var nextSpeedRedrawAt = 0.0

    private var accuracy = ((config.accuracyMinM + config.accuracyMaxM) / 2f)
    private var altitude = config.altitudeM
    private var noiseNorthM = 0.0
    private var noiseEastM = 0.0

    private var lastBearing = 0.0
    private var lastSample: Sample = idleSample(LatLng(0.0, 0.0))

    var finished: Boolean = true
        private set

    fun load(plan: PatrolPlan) {
        this.plan = plan
        segIdx = 0
        distIntoSeg = 0.0
        progress = 0.0
        finished = plan.isEmpty
        val start = plan.start ?: lastSample.position
        if (!plan.isEmpty) lastBearing = plan.segments[0].bearingDeg
        lastSample = idleSample(start).copy(
            segmentIndex = 0,
            waypointIndex = plan.segments.firstOrNull()?.waypointIndex,
            kind = plan.segments.firstOrNull()?.kind ?: SegmentKind.TRAVEL,
        )
    }

    fun setSpeed(mps: Double) {
        targetSpeed = mps.coerceIn(0.2, 10.0)
    }

    /** Last reported fix without moving (used while paused). Speed is reported as 0. */
    fun current(): Sample = lastSample.copy(
        speedMps = 0.0,
        distanceDeltaM = 0.0,
        arrivedAtWaypoint = null,
        lapFinished = false,
    )

    fun advance(dtSec: Double): Sample {
        val dt = dtSec.coerceIn(0.0, 5.0)
        elapsedSec += dt
        if (elapsedSec >= nextSpeedRedrawAt) {
            val j = config.speedJitterPct / 100.0
            // Random.nextDouble(from, until) requires until > from, so 0 % jitter must be special-cased.
            speedFactor = if (j > 1e-9) 1.0 + random.nextDouble(-j, j) else 1.0
            nextSpeedRedrawAt = elapsedSec + random.nextDouble(3.0, 8.0)
        }
        drift()

        if (finished || plan.isEmpty) {
            lastSample = lastSample.copy(
                speedMps = 0.0,
                accuracyM = accuracy,
                altitudeM = altitude,
                distanceDeltaM = 0.0,
                arrivedAtWaypoint = null,
                lapFinished = false,
                position = noisy(exactPosition()),
            )
            return lastSample
        }

        // Time, not distance, is what a tick actually spends. Legs can have very different speeds
        // (a highway leg followed by a walk), so crossing from one into the next mid-tick must
        // switch to the new leg's speed instead of finishing the tick at the old one.
        var remainingTime = dt
        var moved = 0.0
        var arrived: Int? = null
        var lapDone = false
        var lastLegSpeed = 0.0

        while (remainingTime > 1e-9 && !finished) {
            val seg = plan.segments[segIdx]
            // Never consume two arrival points in one tick: each arrival must be reported once.
            if (arrived != null && seg.arrivalAtEnd) break
            val legSpeed = ((seg.travelMode?.speedMps ?: targetSpeed) * speedFactor).coerceAtLeast(1e-6)
            lastLegSpeed = legSpeed
            val left = seg.lengthM - distIntoSeg
            val step = min(left, legSpeed * remainingTime)
            distIntoSeg += step
            remainingTime -= step / legSpeed
            moved += step
            progress += step
            if (distIntoSeg >= seg.lengthM - 1e-9) {
                if (seg.arrivalAtEnd && seg.waypointIndex != null) arrived = seg.waypointIndex
                if (segIdx + 1 < plan.segments.size) {
                    segIdx++
                    distIntoSeg = 0.0
                    // A zero-length leg (only carries a flag) must not stall the loop.
                    if (plan.segments[segIdx].lengthM <= 1e-9) {
                        val s = plan.segments[segIdx]
                        if (s.arrivalAtEnd && s.waypointIndex != null) arrived = s.waypointIndex
                        if (segIdx + 1 < plan.segments.size) { segIdx++ } else { finished = true; lapDone = true }
                    }
                } else {
                    finished = true
                    lapDone = true
                }
            }
        }

        val seg = plan.segments[segIdx]
        if (seg.lengthM > 1e-9) lastBearing = seg.bearingDeg
        val exact = exactPosition()
        lastSample = Sample(
            position = noisy(exact),
            // Report the speed of the leg we ended the tick on, so a fix taken just after a
            // highway leg does not still claim 90 km/h while the avatar is walking.
            speedMps = if (finished) 0.0 else ((seg.travelMode?.speedMps ?: targetSpeed) * speedFactor).takeIf { it > 0 } ?: lastLegSpeed,
            bearingDeg = lastBearing,
            accuracyM = accuracy,
            altitudeM = altitude,
            distanceDeltaM = moved,
            segmentIndex = segIdx,
            waypointIndex = seg.waypointIndex,
            kind = seg.kind,
            arrivedAtWaypoint = arrived,
            lapFinished = lapDone,
            progressM = progress,
            countsSteps = seg.travelMode?.countsSteps ?: true,
        )
        return lastSample
    }

    private fun exactPosition(): LatLng {
        if (plan.isEmpty) return lastSample.position
        val seg = plan.segments[segIdx]
        if (seg.lengthM <= 1e-9) return seg.to
        val f = (distIntoSeg / seg.lengthM).coerceIn(0.0, 1.0)
        return GeoMath.interpolate(seg.from, seg.to, f)
    }

    private fun drift() {
        // Bounded random walks: lateral noise <= 1 m, accuracy inside the band, altitude +-1 m.
        noiseNorthM = (noiseNorthM + random.nextDouble(-0.25, 0.25)).coerceIn(-1.0, 1.0)
        noiseEastM = (noiseEastM + random.nextDouble(-0.25, 0.25)).coerceIn(-1.0, 1.0)
        val mag = kotlin.math.hypot(noiseNorthM, noiseEastM)
        if (mag > 1.0) { noiseNorthM /= mag; noiseEastM /= mag }
        accuracy = (accuracy + random.nextDouble(-0.4, 0.4).toFloat())
            .coerceIn(config.accuracyMinM, config.accuracyMaxM)
        altitude = (altitude + random.nextDouble(-0.05, 0.05)).coerceIn(config.altitudeM - 1.0, config.altitudeM + 1.0)
    }

    private fun noisy(p: LatLng): LatLng = GeoMath.offsetMeters(p, noiseNorthM, noiseEastM)

    private fun idleSample(p: LatLng) = Sample(
        position = p,
        speedMps = 0.0,
        bearingDeg = lastBearing,
        accuracyM = accuracy,
        altitudeM = altitude,
        distanceDeltaM = 0.0,
        segmentIndex = 0,
        waypointIndex = null,
        kind = SegmentKind.TRAVEL,
        arrivedAtWaypoint = null,
        lapFinished = false,
        progressM = progress,
    )
}
