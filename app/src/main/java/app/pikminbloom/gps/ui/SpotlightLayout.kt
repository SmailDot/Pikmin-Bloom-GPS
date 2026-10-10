package app.pikminbloom.gps.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A rectangle in pixels: [left, right) by [top, bottom). */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class Size(val width: Int, val height: Int)

/** A pixel point: where the hand's fingertip rests. */
data class Point(val x: Int, val y: Int)

/** A circle in pixels: the ring drawn around a spot, or a circular spot itself. */
data class Ring(val cx: Int, val cy: Int, val radius: Int)

/** The shape of the spot a step highlights: the bounds of a view, or a circle at the view's centre. */
enum class SpotShape { RECT, CIRCLE }

/**
 * Where everything goes for one step. [hole] is set for a rectangular spot and [circle] for a circular one; [ring] is
 * the pulse around the spot. [fingertip] is where the hand points, null without a gesture. The card's top-left corner
 * is ([tooltipX], [tooltipY]). Without a target only the card is set.
 */
data class Placement(
    val hole: Bounds?,
    val circle: Ring?,
    val ring: Ring?,
    val fingertip: Point?,
    val tooltipX: Int,
    val tooltipY: Int,
)

/** One frame of the pulsing ring: how far it has grown and how opaque it still is (0..255). */
data class Pulse(val growth: Int, val alpha: Int)

/**
 * The spotlight tour's pixel geometry, as plain logic so it can be tested without Android. The view measures the
 * target and the card, then asks here where the hole, the ring, the hand and the card go.
 */
object SpotlightLayout {
    /** Where the fingertip sits inside the hand icon, as fractions of its size: the icon's top point. */
    private const val FINGER_X = 0.48f
    private const val FINGER_Y = 0.125f

    /**
     * A rectangular spot is the target grown by [padding]; a circular spot is the target's centre with [circleRadius].
     * The ring is centred on the spot. The fingertip is the spot's centre for a long press, and its bottom-left
     * corner (or edge, for a circle) for a tap, so the hand hangs below-left of it and never covers the spot.
     * The card goes below the spot and the hand if it fits (with [margin] between them), else above, else centred
     * vertically; horizontally it is centred on the target. Its corner is kept [margin] inside the screen.
     */
    fun place(
        screen: Size,
        target: Bounds?,
        shape: SpotShape,
        gesture: Gesture,
        padding: Int,
        circleRadius: Int,
        handSize: Int,
        margin: Int,
        card: Size,
    ): Placement {
        val centredX = (screen.width - card.width) / 2
        val centredY = (screen.height - card.height) / 2
        if (target == null) return Placement(null, null, null, null, centredX, centredY)
        val grown = Bounds(target.left - padding, target.top - padding, target.right + padding, target.bottom + padding)
        val circle = if (shape == SpotShape.CIRCLE) Ring(target.centerX, target.centerY, circleRadius) else null
        val hole = if (circle == null) grown else null
        val spot = circle?.let { Bounds(it.cx - it.radius, it.cy - it.radius, it.cx + it.radius, it.cy + it.radius) } ?: grown
        val ring = circle ?: Ring(target.centerX, target.centerY, max(grown.width, grown.height) / 2)
        val fingertip = when {
            gesture == Gesture.NONE -> null
            gesture == Gesture.LONG_PRESS -> Point(target.centerX, target.centerY)
            circle != null -> {
                val d = (circle.radius * sqrt(0.5)).roundToInt() // 225 degrees: both axes take cos and sin of 45
                Point(circle.cx - d, circle.cy + d)
            }
            else -> Point(grown.left, grown.bottom)
        }
        val avoid = fingertip?.let {
            val hand = handOrigin(it, handSize, screen)
            union(spot, Bounds(hand.x, hand.y, hand.x + handSize, hand.y + handSize))
        } ?: spot
        val maxX = (screen.width - card.width - margin).coerceAtLeast(margin)
        val maxY = (screen.height - card.height - margin).coerceAtLeast(margin)
        val x = (target.centerX - card.width / 2).coerceIn(margin, maxX)
        val below = avoid.bottom + margin
        val above = avoid.top - margin - card.height
        val y = when {
            below + card.height + margin <= screen.height -> below
            above >= margin -> above
            else -> centredY
        }.coerceIn(margin, maxY)
        return Placement(hole, circle, ring, fingertip, x, y)
    }

    /** The top-left corner of the hand icon so its fingertip rests on [tip], kept on the screen. */
    fun handOrigin(tip: Point, handSize: Int, screen: Size): Point {
        val x = tip.x - (handSize * FINGER_X).roundToInt()
        val y = tip.y - (handSize * FINGER_Y).roundToInt()
        return Point(x.coerceIn(0, screen.width - handSize), y.coerceIn(0, screen.height - handSize))
    }

    /** The pulse at [fraction] of its cycle (0 to 1): the ring grows from 0 to [maxGrowth] and fades from opaque to clear. */
    fun pulse(fraction: Float, maxGrowth: Int): Pulse {
        val f = fraction.coerceIn(0f, 1f)
        return Pulse(growth = (maxGrowth * f).toInt(), alpha = (255 * (1 - f)).toInt())
    }

    private fun union(a: Bounds, b: Bounds) =
        Bounds(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}
