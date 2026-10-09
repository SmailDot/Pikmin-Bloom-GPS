package app.pikminbloom.gps.feed

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The feed screen's gestures as plain geometry, so they can be tested without the accessibility service. The
 * service turns these points into strokes.
 */
object FeedGestures {

    /** A straight stroke from one point to another, [durationMs] long. */
    data class Stroke(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long)

    /** Zoom out: two strokes dispatched together along the middle row, each [PINCH_MS], moving towards the centre. */
    fun pinch(w: Int, h: Int): List<Stroke> {
        val y = (0.50 * h).toInt()
        return listOf(
            Stroke((0.20 * w).toInt(), y, (0.43 * w).toInt(), y, PINCH_MS),
            Stroke((0.80 * w).toInt(), y, (0.57 * w).toInt(), y, PINCH_MS),
        )
    }

    /** A closed circle of [steps] segments around (cx, cy). It starts and ends at (cx + r, cy). */
    fun circle(cx: Int, cy: Int, r: Int, steps: Int = CIRCLE_STEPS): List<Pair<Int, Int>> =
        (0..steps).map { k ->
            val a = 2 * PI * k / steps
            (cx + (r * cos(a)).roundToInt()) to (cy + (r * sin(a)).roundToInt())
        }

    /**
     * The harvest spiral centred at (0.50W, 0.54H): radii 0.36W by 0.25H, [SPIRAL_TURNS] turns over
     * [SPIRAL_SEGMENTS] segments, so 161 points. Point 0 is [start], the bloom the harvest begins on.
     */
    fun spiral(w: Int, h: Int, start: Pair<Int, Int>): List<Pair<Int, Int>> {
        val cx = 0.50 * w
        val cy = 0.54 * h
        val rx = 0.36 * w
        val ry = 0.25 * h
        return (0..SPIRAL_SEGMENTS).map { i ->
            if (i == 0) {
                start
            } else {
                val t = i.toDouble() / SPIRAL_SEGMENTS
                val a = 2 * PI * SPIRAL_TURNS * t
                (cx + rx * t * cos(a)).roundToInt() to (cy + ry * t * sin(a)).roundToInt()
            }
        }
    }

    private const val PINCH_MS = 650L
    private const val CIRCLE_STEPS = 24
    private const val SPIRAL_TURNS = 5
    private const val SPIRAL_SEGMENTS = 160
}
