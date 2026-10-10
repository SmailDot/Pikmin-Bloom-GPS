package app.pikminbloom.gps.ui

import app.pikminbloom.gps.data.LocationJump
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.i18n.Lang
import app.pikminbloom.gps.i18n.tr
import app.pikminbloom.gps.service.RealMode

/**
 * Every word of the 真實位置 ⇄ 虛擬位置 switch (T18: the user could not tell whether 「真實位置」 was where
 * the phone is or what a tap does, and one poke jumped the game 2,230 km). The state says where every
 * app sees the phone; the action starts with its verb and ends with 「…」 where a confirmation follows;
 * whatever asks for the action names the jump (跳躍). Never a bare 「真實位置」／「虛擬位置」 as a label.
 * In the app's three languages ([Lang]); [lang] defaults to the one in force.
 */
object RealModeCopy {
    /** Main screen, next to the switch: where every app sees the phone right now. */
    fun stateLine(suspended: Boolean, lang: Lang = Lang.current): String =
        tr("定位：", "Location: ", "位置情報：", lang) + state(suspended, lang)

    /** The main screen's switch: the action, and 「…」 for the confirmation that follows. */
    fun switchLabel(suspended: Boolean, lang: Lang = Lang.current): String = action(suspended, lang) + "…"

    fun confirmTitle(toReal: Boolean, lang: Lang = Lang.current): String =
        confirmButton(toReal, lang) + tr("？", "?", "？", lang)

    fun confirmButton(toReal: Boolean, lang: Lang = Lang.current): String =
        if (toReal) toReal(lang) else toVirtual(lang)

    /** [jumpM] from [jumpMeters]; null leaves the distance out. */
    fun confirmMessage(toReal: Boolean, jumpM: Double?, lang: Lang = Lang.current): String {
        val km = jumpM?.let { LocationJump.distanceText(it) }
        val back = switchLabel(suspended = true, lang)
        return if (toReal) {
            tr(
                "手機上所有 App（包含遊戲和 Google 地圖）會馬上看到你真正的位置，遊戲會看到一次瞬移${km?.let { "，約 $it" }.orEmpty()}。\n\n" +
                    "虛擬巡邏會停在原處等你，之後要按「$back」才會瞬移回去。",
                "Every app on the phone (the game and Google Maps too) sees where you really are at once, and the game sees " +
                    "one location jump${km?.let { " of about $it" }.orEmpty()}.\n\n" +
                    "The virtual patrol waits where it is; only \u201C$back\u201D takes it back (that is a jump too).",
                "ゲームや Google マップを含むスマホのすべてのアプリに、すぐに実際の位置が見えます。ゲームからは位置が1回ジャンプしたように" +
                    "見えます${km?.let { "（約 $it）" }.orEmpty()}。\n\n" +
                    "仮想の巡回はその場で待機します。戻るには「$back」を押してください（これも1回のジャンプです）。",
                lang,
            )
        } else {
            tr(
                "遊戲會看到一次瞬移：從你的真實位置回到巡邏停放的位置${km?.let { "，約 $it" }.orEmpty()}。\n\n回去之後巡邏會回到切走之前的狀態。",
                "The game sees one location jump, from where you really are back to where the patrol waits" +
                    "${km?.let { ", about $it" }.orEmpty()}.\n\nThe patrol then carries on as it was before you switched.",
                "ゲームからは、実際の位置から巡回の待機位置へ1回ジャンプしたように見えます${km?.let { "（約 $it）" }.orEmpty()}。\n\n" +
                    "戻ったあとは、切り替える前の状態から巡回を続けます。",
                lang,
            )
        }
    }

    /** The question asked when Google Maps comes to the front (ui/MapsPrompt, 2026-10-08). */
    fun mapsTitle(lang: Lang = Lang.current): String =
        tr("要讓 Google 地圖看到真實位置嗎？", "Let Google Maps see your real location?", "Google マップに実際の位置を見せますか？", lang)

    fun mapsNo(lang: Lang = Lang.current): String = tr("不用", "No thanks", "いいえ", lang)

    /** Why asking beats switching: Android cannot un-mock Maps alone, and reading reviews needs no real GPS. */
    fun mapsMessage(jumpM: Double?, lang: Lang = Lang.current): String {
        val km = jumpM?.let { LocationJump.distanceText(it) }
        return tr(
            "Android 沒辦法只讓 Google 地圖看到真實 GPS：切過去，遊戲也會看到，等於跳一次${km?.let { "（約 $it）" }.orEmpty()}；之後回到虛擬位置又會再跳一次。\n\n" +
                "只是查店家、看評論不用切，地圖照樣能用。要導航或確認自己在哪裡再切。",
            "Android cannot show the real GPS to Google Maps alone: switch, and the game sees it too - one jump" +
                "${km?.let { " (about $it)" }.orEmpty()}, and another one when you go back to the virtual location.\n\n" +
                "To look up a shop or read reviews there is no need to switch; Maps works as it is. Switch to navigate or to see where you are.",
            "Android では Google マップだけに実際の GPS を見せることはできません。切り替えるとゲームにも見え、1回ジャンプします" +
                "${km?.let { "（約 $it）" }.orEmpty()}。仮想位置に戻るときにもう1回ジャンプします。\n\n" +
                "お店を調べたり口コミを見たりするだけなら、切り替えなくてもマップは使えます。ナビや現在地の確認が必要なときに切り替えてください。",
            lang,
        )
    }

    /** The bar's icon button for accessibility: the state, then what a tap offers. */
    fun buttonDescription(suspended: Boolean, lang: Lang = Lang.current): String = tr(
        "目前：${state(suspended, lang)}，按一下可選擇${action(suspended, lang)}",
        "Now: ${state(suspended, lang)}. Tap to ${action(suspended, lang).replaceFirstChar { it.lowercase() }}",
        "現在：${state(suspended, lang)}。タップで「${action(suspended, lang)}」を選べます",
        lang,
    )

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
    fun staleSwitchText(toReal: Boolean, phase: PatrolPhase, lang: Lang = Lang.current): String? {
        val applies = if (toReal) RealMode.canEnter(phase) else phase == PatrolPhase.SUSPENDED
        return when {
            applies -> null
            !toReal && RealMode.canEnter(phase) -> tr("已經在虛擬位置", "Already on the virtual location", "すでに仮想位置です", lang)
            toReal && phase == PatrolPhase.SUSPENDED -> tr("已經在真實位置", "Already on the real location", "すでに実際の位置です", lang)
            else -> tr("巡邏沒有在進行，沒有切換", "No patrol is running; nothing was switched", "巡回していないため、切り替えていません", lang)
        }
    }

    private fun state(suspended: Boolean, lang: Lang) =
        if (suspended) tr("真實位置（真實 GPS）", "Real location (real GPS)", "実際の位置（実際の GPS）", lang)
        else tr("虛擬位置（模擬中）", "Virtual location (mocking)", "仮想位置（擬似位置）", lang)

    private fun action(suspended: Boolean, lang: Lang) = if (suspended) toVirtual(lang) else toReal(lang)

    private fun toReal(lang: Lang) = tr("切到真實位置", "Switch to real location", "実際の位置に切り替え", lang)

    private fun toVirtual(lang: Lang) = tr("回到虛擬位置", "Back to virtual location", "仮想位置に戻る", lang)
}
