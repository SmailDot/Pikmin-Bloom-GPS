package app.pikminbloom.gps.ui

import kotlin.math.max

/** A rectangle in pixels: [left, right) by [top, bottom). */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class Size(val width: Int, val height: Int)

/** The ring drawn around the spotlight hole. */
data class Ring(val cx: Int, val cy: Int, val radius: Int)

/** Where everything goes for one step: [hole] and [ring] are null for a centred card; the card's top-left corner. */
data class Placement(val hole: Bounds?, val ring: Ring?, val tooltipX: Int, val tooltipY: Int)

/** One frame of the pulsing ring: how far it has grown and how opaque it still is (0..255). */
data class Pulse(val growth: Int, val alpha: Int)

/**
 * The spotlight tour's pixel geometry, as plain logic so it can be tested without Android. The view measures the
 * target and the card, then asks here where the hole, the ring and the card go.
 */
object SpotlightLayout {

    /**
     * The hole is the target grown by [padding] on every side. The ring is centred on the target, with half the
     * hole's longer side as radius. The card goes below the hole if it fits (with [margin] between them), else above,
     * else centred vertically; horizontally it is centred on the target. Its corner is always kept [margin] inside
     * the screen. Without a target the card is centred and nothing is highlighted.
     */
    fun place(screen: Size, target: Bounds?, padding: Int, margin: Int, card: Size): Placement {
        val centredX = (screen.width - card.width) / 2
        val centredY = (screen.height - card.height) / 2
        if (target == null) return Placement(null, null, centredX, centredY)
        val hole = Bounds(target.left - padding, target.top - padding, target.right + padding, target.bottom + padding)
        val ring = Ring(target.centerX, target.centerY, max(hole.width, hole.height) / 2)
        val maxX = (screen.width - card.width - margin).coerceAtLeast(margin)
        val maxY = (screen.height - card.height - margin).coerceAtLeast(margin)
        val x = (target.centerX - card.width / 2).coerceIn(margin, maxX)
        val below = hole.bottom + margin
        val above = hole.top - margin - card.height
        val y = when {
            below + card.height + margin <= screen.height -> below
            above >= margin -> above
            else -> centredY
        }.coerceIn(margin, maxY)
        return Placement(hole, ring, x, y)
    }

    /** The pulse at [fraction] of its cycle (0 to 1): the ring grows from 0 to [maxGrowth] and fades from opaque to clear. */
    fun pulse(fraction: Float, maxGrowth: Int): Pulse {
        val f = fraction.coerceIn(0f, 1f)
        return Pulse(growth = (maxGrowth * f).toInt(), alpha = (255 * (1 - f)).toInt())
    }
}
