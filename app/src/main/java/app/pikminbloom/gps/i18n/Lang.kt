package app.pikminbloom.gps.i18n

import android.content.Context
import app.pikminbloom.gps.R

/**
 * The app's languages (2026-10-08, "造福英文社群和日文社群的玩家"): English in values/, which is also what every
 * other phone language gets, Traditional Chinese in values-zh/ (every Chinese locale) and Japanese in values-ja/.
 *
 * Android picks the XML strings by itself. The texts built in Kotlin - the pure, unit-tested formatters such as
 * TripChoices or RealModeCopy, and names like TravelMode.label - read [current] instead, which [refresh] takes from
 * the resources themselves (R.string.lang_code), so the two always agree, also when a phone lists several languages.
 */
enum class Lang {
    EN, ZH, JA;

    companion object {
        /**
         * Set by PikminGpsApp at start and on every configuration change, before any screen or service builds a text.
         * Chinese until then, which only the JVM unit tests ever see: they were written against the Chinese texts and
         * pass a language explicitly for the others.
         */
        @Volatile
        var current: Lang = ZH

        fun of(code: String): Lang = when (code) {
            "zh" -> ZH
            "ja" -> JA
            else -> EN
        }

        fun refresh(context: Context) {
            current = of(context.getString(R.string.lang_code))
        }
    }
}

/** One piece of text in the three languages; [text] is the one in force. */
data class Tr(val zh: String, val en: String, val ja: String) {
    val text: String get() = of(Lang.current)

    fun of(lang: Lang): String = when (lang) {
        Lang.ZH -> zh
        Lang.EN -> en
        Lang.JA -> ja
    }

    /** True when [query] occurs in any of the three (a filter typed in another language still finds it). */
    fun anyContains(query: String): Boolean = listOf(zh, en, ja).any { it.contains(query, ignoreCase = true) }

    override fun toString(): String = text
}

/** [zh], [en] or [ja], whichever [lang] is. */
fun tr(zh: String, en: String, ja: String, lang: Lang = Lang.current): String = when (lang) {
    Lang.ZH -> zh
    Lang.EN -> en
    Lang.JA -> ja
}
