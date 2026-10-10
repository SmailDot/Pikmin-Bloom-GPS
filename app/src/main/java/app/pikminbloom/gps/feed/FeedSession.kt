package app.pikminbloom.gps.feed

import app.pikminbloom.gps.vision.RgbImage
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/**
 * What the feed run can do to the phone. The Android implementation is the accessibility service (gestures) and
 * its screenshot (frames).
 */
interface FeedIo {
    suspend fun frame(): RgbImage?

    /** Zoom out: two strokes together, see [FeedGestures.pinch]. */
    suspend fun pinch(w: Int, h: Int): Boolean

    /**
     * Drags from ([fromX], [fromY]) to the start of a circle of [radius] around ([toX], [toY]), then holds there:
     * the circle is repeated for [holdMs]. The drag ends at (toX + radius, toY), where the circle starts and ends.
     */
    suspend fun dragHoldCircle(fromX: Int, fromY: Int, toX: Int, toY: Int, radius: Int, holdMs: Long): Boolean

    /** One continuous stroke through [points], taking [durationMs]. */
    suspend fun path(points: List<Pair<Int, Int>>, durationMs: Long): Boolean

    suspend fun wait(ms: Long)
}

enum class FeedStop { DONE, NOT_ON_FEED, NO_BLOOM, LOST, CANCELLED }

data class FeedResult(val rounds: Int, val stop: FeedStop)

/**
 * 自動餵精華: [run] feeds rounds on the feed screen. Each round: poll for the feed screen, zoom out, check the
 * zoomed frame is still the feed screen, drag the nectar to the feed point and hold, then look for blooms and
 * harvest from the first one, sweeping again while blooms are left. Every gesture comes after a fresh frame showed
 * the screen it belongs to.
 *
 * Single use per run: [run] resets the count, and [roundsSoFar] stays readable after a cancellation.
 */
class FeedSession(
    private val io: FeedIo,
    /** The navigation bar's height as a fraction of the screen's height (0 with none): every frame is cut to the game above it. */
    private val bottomInsetFraction: Double = 0.0,
    private val isFeed: (RgbImage) -> Boolean = { FeedVision.isFeedScreen(it) },
    private val blooms: (RgbImage, RgbImage) -> List<Pair<Int, Int>> = FeedVision::findBlooms,
    private val log: (String) -> Unit = {},
) {
    private var rounds = 0

    /** Rounds fed so far in this run. */
    val roundsSoFar: Int get() = rounds

    suspend fun run(target: Int): FeedResult {
        require(target >= 1) { "rounds must be at least 1, was $target" }
        rounds = 0
        val stop = try {
            drive(target)
        } catch (e: CancellationException) {
            log("feed cancelled after $rounds")
            throw e
        }
        log("feed stopped: $stop after $rounds")
        return FeedResult(rounds, stop)
    }

    private suspend fun drive(target: Int): FeedStop {
        var misses = 0 // rounds in a row whose look found no bloom
        while (rounds < target) {
            val feed = feedFrame()
            if (feed == null) {
                log("the feed screen did not show")
                return if (rounds == 0) FeedStop.NOT_ON_FEED else FeedStop.LOST
            }
            val w = feed.width
            val h = feed.height
            if (!io.pinch(w, h)) return FeedStop.LOST
            io.wait(PINCH_SETTLE_MS)
            val before = frame()
            if (before == null || !isFeed(before)) return FeedStop.LOST
            val feedX = (0.50 * w).toInt()
            val feedY = (0.54 * h).toInt()
            val bubbleY = (0.895 * h).toInt()
            if (!io.dragHoldCircle(feedX, bubbleY, feedX, feedY, (0.04 * w).toInt(), HOLD_MS)) return FeedStop.LOST
            io.wait(AFTER_DRAG_MS)
            val start = firstBloom(before)
            if (start == null) {
                misses++
                if (rounds == 0 || misses >= 2) {
                    log("no bloom: stopping")
                    return FeedStop.NO_BLOOM
                }
                log("round ${rounds + 1}: no bloom, carrying on")
            } else {
                misses = 0
                if (!harvest(before, w, h, start)) return FeedStop.LOST
            }
            rounds++
            log("fed round $rounds")
        }
        return FeedStop.DONE
    }

    /**
     * One harvest from [first], then the sweep: after each pass the flowers are looked at again, and while blooms are
     * left, another pass starts on the first of them, up to [EXTRA_PASSES] extra passes. False when a stroke was refused.
     */
    private suspend fun harvest(before: RgbImage, w: Int, h: Int, first: Pair<Int, Int>): Boolean {
        var start = first
        for (pass in 0..EXTRA_PASSES) {
            if (pass > 0) log("extra harvest pass $pass")
            if (!io.path(FeedGestures.spiral(w, h, start), HARVEST_MS)) return false
            io.wait(HARVEST_SETTLE_MS)
            if (pass == EXTRA_PASSES) break
            val left = frame()?.let { blooms(before, it) }.orEmpty()
            start = left.firstOrNull() ?: break
        }
        return true
    }

    /** The game area of [img]: the rows above the navigation bar, which the rules and the taps are measured on. */
    private fun game(img: RgbImage): RgbImage = img.cropBottom((bottomInsetFraction * img.height).roundToInt())

    /** The next frame as the game area, or null when the screenshot had none. */
    private suspend fun frame(): RgbImage? = io.frame()?.let(::game)

    /** The first frame that is the feed screen, looking up to [FEED_LOOKS] times, [FEED_LOOK_MS] apart. */
    private suspend fun feedFrame(): RgbImage? {
        repeat(FEED_LOOKS) {
            io.wait(FEED_LOOK_MS)
            val img = frame()
            if (img != null && isFeed(img)) return img
        }
        return null
    }

    /** The strongest bloom from the first look that finds any, up to [BLOOM_LOOKS] looks, [BLOOM_LOOK_MS] apart. */
    private suspend fun firstBloom(before: RgbImage): Pair<Int, Int>? {
        repeat(BLOOM_LOOKS) {
            io.wait(BLOOM_LOOK_MS)
            val after = frame() ?: return@repeat
            val found = blooms(before, after)
            if (found.isNotEmpty()) return found.first()
        }
        return null
    }

    private companion object {
        const val FEED_LOOKS = 3
        const val FEED_LOOK_MS = 700L
        const val PINCH_SETTLE_MS = 1000L
        const val HOLD_MS = 4000L
        const val AFTER_DRAG_MS = 1500L
        const val BLOOM_LOOKS = 3
        const val BLOOM_LOOK_MS = 1000L
        const val HARVEST_MS = 6000L
        const val HARVEST_SETTLE_MS = 1500L
        const val EXTRA_PASSES = 2
    }
}
