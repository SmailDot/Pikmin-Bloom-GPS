package app.pikminbloom.gps.ui

/** The three flower menu actions the demo explains: 立刻前往, 前往後停在這裡, 瞬移. */
enum class GoMode { GO_NOW, GO_AND_STAY, TELEPORT }

/**
 * One frame of a demo row: where the avatar dot is along the track (0 = the left end, 1 = the right end), whether it is
 * shown, whether it is paused, and whether a flash is drawn at it.
 */
data class DemoFrame(val dotX: Float, val visible: Boolean, val paused: Boolean, val flash: Boolean)

/**
 * The go-modes demo as plain timing, so it can be tested without Android. One loop runs from t = 0 to 1. The avatar
 * starts at [START], the flower is at [FLOWER], and a second flower further on is at [FAR].
 */
object GoModesDemo {
    const val START = 0.1f
    const val FLOWER = 0.5f
    const val FAR = 0.9f

    /** The frame at [t] (0 to 1, clamped) of one loop for [mode]. */
    fun frame(mode: GoMode, t: Float): DemoFrame {
        val f = t.coerceIn(0f, 1f)
        return when (mode) {
            GoMode.GO_NOW -> when {
                f < 0.4f -> DemoFrame(lerp(START, FLOWER, f / 0.4f), visible = true, paused = false, flash = false)
                f < 0.8f -> DemoFrame(lerp(FLOWER, FAR, (f - 0.4f) / 0.4f), visible = true, paused = false, flash = false)
                else -> DemoFrame(FAR, visible = true, paused = false, flash = false)
            }
            GoMode.GO_AND_STAY -> when {
                f < 0.5f -> DemoFrame(lerp(START, FLOWER, f / 0.5f), visible = true, paused = false, flash = false)
                else -> DemoFrame(FLOWER, visible = true, paused = true, flash = false)
            }
            GoMode.TELEPORT -> when {
                f < 0.4f -> DemoFrame(START, visible = true, paused = false, flash = false)
                f < 0.5f -> DemoFrame(START, visible = false, paused = false, flash = true)
                else -> DemoFrame(FLOWER, visible = true, paused = false, flash = f < 0.6f)
            }
        }
    }

    /** The menu explanation opens once: the first time a flower is tapped, until it has been seen. */
    fun shouldShowIntro(seen: Boolean): Boolean = !seen

    private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
}
