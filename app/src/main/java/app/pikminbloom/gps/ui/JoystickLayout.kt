package app.pikminbloom.gps.ui

import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.i18n.tr

/**
 * Where the floating joystick sits and how big it is (2026-10-08: "卡在左下角而且太大了，有時候會有遮蔽到的問題").
 * It used to be fixed at 150 dp in the bottom-left corner; now its ✥ handle drags it anywhere and its size button
 * cycles three sizes, both remembered (Prefs). Pure, so the rules are unit-tested.
 */
object JoystickLayout {
    /** 小／中／大 in dp. Small is the default: the old 150 dp pad covered the game's own buttons. */
    val SIZES_DP = listOf(110, 140, 170)
    val DEFAULT_SIZE_DP = SIZES_DP.first()

    /** The size after [currentDp] (an unknown value starts over at the smallest). */
    fun nextSize(currentDp: Int): Int {
        val i = SIZES_DP.indexOf(currentDp)
        return SIZES_DP[(i + 1) % SIZES_DP.size]
    }

    /** A stored size that is not one of [SIZES_DP] (older build, hand-edited prefs) falls back to the default. */
    fun validSize(storedDp: Int): Int = if (storedDp in SIZES_DP) storedDp else DEFAULT_SIZE_DP

    fun label(sizeDp: Int, lang: Lang = Lang.current): String = when (SIZES_DP.indexOf(sizeDp)) {
        0 -> tr("小", "S", "小", lang)
        1 -> tr("中", "M", "中", lang)
        else -> tr("大", "L", "大", lang)
    }

    /** Keeps a [sizePx] square fully on a [screenW] x [screenH] screen. */
    fun clamp(x: Int, y: Int, sizePx: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
        x.coerceIn(0, maxOf(0, screenW - sizePx)) to y.coerceIn(0, maxOf(0, screenH - sizePx))
}
