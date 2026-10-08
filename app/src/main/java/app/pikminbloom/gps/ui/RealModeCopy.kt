package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.LocationJump
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.service.RealMode

/**
 * Every word of the 真實位置 ⇄ 虛擬位置 switch (T18: the user could not tell whether 「真實位置」 was where
 * the phone is or what a tap does, and one poke jumped the game 2,230 km). The state says where every
 * app sees the phone; the action starts with its verb and ends with 「…」 where a confirmation follows;
 * whatever asks for the action names the jump (跳躍). Never a bare 「真實位置」／「虛擬位置」 as a label.
 */
object RealModeCopy {
    private const val VIRTUAL = "虛擬位置（模擬中）"
    private const val REAL = "真實位置（真實 GPS）"
    private const val TO_REAL = "切到真實位置"
    private const val TO_VIRTUAL = "回到虛擬位置"

    /** Main screen, next to the switch: where every app sees the phone right now. */
    fun stateLine(suspended: Boolean): String = "定位：" + state(suspended)

    /** The main screen's switch: the action, and 「…」 for the confirmation that follows. */
    fun switchLabel(suspended: Boolean): String = action(suspended) + "…"

    fun confirmTitle(toReal: Boolean): String = confirmButton(toReal) + "？"

    fun confirmButton(toReal: Boolean): String = if (toReal) TO_REAL else TO_VIRTUAL

    /** [jumpM] from [jumpMeters]; null leaves the distance out. */
    fun confirmMessage(toReal: Boolean, jumpM: Double?): String {
        val about = jumpM?.let { "，約 ${LocationJump.distanceText(it)}" }.orEmpty()
        return if (toReal) {
            "手機上所有 App（包含遊戲和 Google 地圖）會馬上看到你真正的位置，遊戲會看到一次位置跳躍$about。\n\n" +
                "虛擬巡邏會停在原處等你，之後要按「${switchLabel(suspended = true)}」才會跳回去（那也是一次跳躍）。"
        } else {
            "遊戲會看到一次位置跳躍：從你的真實位置跳回巡邏停放的位置$about。\n\n回去之後巡邏會回到切走之前的狀態。"
        }
    }

    /** The question asked when Google Maps comes to the front (ui/MapsPrompt, 2026-10-08). */
    const val MAPS_TITLE = "要讓 Google 地圖看到真實位置嗎？"
    const val MAPS_NO = "不用"

    /** Why asking beats switching: Android cannot un-mock Maps alone, and reading reviews needs no real GPS. */
    fun mapsMessage(jumpM: Double?): String {
        val about = jumpM?.let { "（約 ${LocationJump.distanceText(it)}）" }.orEmpty()
        return "Android 沒辦法只讓 Google 地圖看到真實 GPS：切過去，遊戲也會看到，等於跳一次$about；之後回到虛擬位置又會再跳一次。\n\n" +
            "只是查店家、看評論不用切，地圖照樣能用。要導航或確認自己在哪裡再切。"
    }

    /** The bar's icon button for accessibility: the state, then what a tap offers. */
    fun buttonDescription(suspended: Boolean): String = "目前：" + state(suspended) + "，按一下可選擇" + action(suspended)

    /**
     * How far the switch moves the game: from the real fix the patrol started at ([home]) to the patrol's
     * [position], either way. Null when a point is unknown or the move is not a jump (LocationJump's line).
     */
    fun jumpMeters(home: LatLng?, position: LatLng?): Double? {
        if (home == null || position == null) return null
        return GeoMath.distanceM(home, position).takeIf { it >= LocationJump.MIN_REPORTED_M }
    }

    /**
     * Why a switch asked for earlier no longer applies - it is checked before the dialog opens and again on
     * its 確定, which may come from a stale notification - or null when it still applies and may be sent.
     */
    fun staleSwitchText(toReal: Boolean, phase: PatrolPhase): String? {
        val applies = if (toReal) RealMode.canEnter(phase) else phase == PatrolPhase.SUSPENDED
        return when {
            applies -> null
            !toReal && RealMode.canEnter(phase) -> "已經在虛擬位置"
            toReal && phase == PatrolPhase.SUSPENDED -> "已經在真實位置"
            else -> "巡邏沒有在進行，沒有切換"
        }
    }

    private fun state(suspended: Boolean) = if (suspended) REAL else VIRTUAL

    private fun action(suspended: Boolean) = if (suspended) TO_VIRTUAL else TO_REAL
}
