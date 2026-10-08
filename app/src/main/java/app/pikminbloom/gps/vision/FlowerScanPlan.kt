package app.pikminbloom.gps.vision

import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import java.util.Locale

/**
 * Turns a frame of detections into a list of [Waypoint]s ready for `WaypointStore`.
 *
 * Two rules do the real work:
 *
 *  * **Dedupe within [Options.dedupeRadiusM]** - the same Big Flower is often detected twice (two
 *    blobs from one bloom, or the same flower seen in consecutive scans). Big Flowers are never
 *    closer than a few tens of metres in practice, and the game's own flower circle is 40 m, so
 *    collapsing anything within 20 m loses nothing real.
 *  * **Drop anything beyond [Options.maxRadiusM]** - accuracy degrades with distance from the
 *    camera centre. The bird's-eye projection is close to orthographic but not exactly, so a
 *    one-pixel anchor error near the screen edge is worth more metres than the same error near the
 *    middle, and the tilt residual grows the same way. 500 m is a deliberately conservative default.
 */
object FlowerScanPlan {

    data class Options(
        /** Detections closer together than this collapse into one waypoint. */
        val dedupeRadiusM: Double = 20.0,
        /** Detections further than this from the player are discarded. */
        val maxRadiusM: Double = 500.0,
        /** Radius written into each produced [Waypoint]. */
        val waypointRadiusM: Double = Waypoint.DEFAULT_RADIUS_M,
        /** Dwell written into each produced [Waypoint]. */
        val dwellSec: Int = Waypoint.DEFAULT_DWELL_SEC,
        /** Name prefix; the index is appended. */
        val namePrefix: String = "掃描花",
        /**
         * Drop detections whose ground anchor was a fallback (no stem found). Those are typically
         * several metres out. Off by default: a slightly misplaced waypoint still lands inside the
         * flower's 40 m circle most of the time.
         */
        val requireStem: Boolean = false,
        /** Id prefix, so scanned waypoints are distinguishable from hand-placed ones. */
        val idPrefix: String = "scan",
    )

    /** One produced waypoint plus why it was produced, for the review UI. */
    data class PlannedFlower(
        val waypoint: Waypoint,
        val rangeM: Double,
        val mergedCount: Int,
        val stemFound: Boolean,
    )

    /**
     * Full result: what became a waypoint and what was thrown away.
     * Callers should show this to the user before writing anything to `WaypointStore` - this
     * feature guesses, and a wrong guess sends the patrol to the wrong street corner.
     */
    data class Plan(
        val flowers: List<PlannedFlower>,
        val droppedTooFar: Int,
        val droppedNoStem: Int,
        val mergedAway: Int,
    ) {
        val waypoints: List<Waypoint> get() = flowers.map { it.waypoint }
    }

    /**
     * @param hits detections from one bird's-eye frame.
     * @param playerPixel where the player icon is on screen (recenter first - see [MapCalibration]).
     * @param playerPos the world position that icon represents.
     */
    fun build(
        hits: List<FlowerHit>,
        playerPixel: PixelPoint,
        playerPos: LatLng,
        cal: Calibration,
        options: Options = Options(),
    ): Plan {
        var tooFar = 0
        var noStem = 0

        data class Located(val pos: LatLng, val rangeM: Double, val stem: Boolean)

        val located = ArrayList<Located>()
        for (h in hits) {
            if (options.requireStem && !h.stemFound) { noStem++; continue }
            val range = MapCalibration.rangeMetres(h, playerPixel, cal)
            if (range > options.maxRadiusM) { tooFar++; continue }
            located += Located(MapCalibration.toLatLng(h, playerPixel, playerPos, cal), range, h.stemFound)
        }

        // Nearest first: a near detection is the more accurate one, so it wins the merge.
        located.sortBy { it.rangeM }

        val clusters = ArrayList<MutableList<Located>>()
        for (l in located) {
            val existing = clusters.firstOrNull {
                GeoMath.distanceM(it[0].pos, l.pos) <= options.dedupeRadiusM
            }
            if (existing != null) existing += l else clusters += mutableListOf(l)
        }

        val mergedAway = located.size - clusters.size
        val flowers = clusters.mapIndexed { index, cluster ->
            val head = cluster[0]
            PlannedFlower(
                waypoint = Waypoint(
                    id = "${options.idPrefix}-${index + 1}-${fingerprint(head.pos)}",
                    name = "${options.namePrefix}${index + 1}",
                    lat = head.pos.lat,
                    lon = head.pos.lon,
                    radiusM = options.waypointRadiusM,
                    dwellSec = options.dwellSec,
                ),
                rangeM = head.rangeM,
                mergedCount = cluster.size,
                stemFound = cluster.any { it.stem },
            )
        }

        return Plan(flowers, droppedTooFar = tooFar, droppedNoStem = noStem, mergedAway = mergedAway)
    }

    /**
     * Merges a freshly built plan into an existing waypoint list, keeping the existing entries
     * untouched and adding only flowers that are not already covered within [Options.dedupeRadiusM].
     * Returns the combined list.
     */
    fun mergeInto(
        existing: List<Waypoint>,
        plan: Plan,
        options: Options = Options(),
    ): List<Waypoint> {
        val out = existing.toMutableList()
        for (f in plan.flowers) {
            val dup = out.any { GeoMath.distanceM(it.latLng, f.waypoint.latLng) <= options.dedupeRadiusM }
            if (!dup) out += f.waypoint
        }
        return out
    }

    /** Short stable suffix from a coordinate, so ids do not collide between scans. */
    private fun fingerprint(p: LatLng): String =
        String.format(Locale.US, "%.5f%.5f", p.lat, p.lon).replace(".", "").replace("-", "n").takeLast(8)
}
