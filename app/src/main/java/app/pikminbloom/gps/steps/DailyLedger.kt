package app.pikminbloom.gps.steps

import java.time.LocalDate

/**
 * 今日已寫入步數 with a real "today". The service used to read the count once at start and add to it
 * forever, so a patrol that crossed local midnight kept yesterday's total and, once the daily cap
 * had been reached, wrote no steps at all until it was restarted (2026-09-14 00:50 JST).
 */
class DailyLedger(day: LocalDate, initial: Long) {
    var day: LocalDate = day
        private set
    var total: Long = initial
        private set

    fun add(n: Long) { total += n }

    /** Steps still allowed today under [cap]; null = no cap (每日步數上限 left empty, 2026-10-08). */
    fun room(cap: Long?): Long = if (cap == null) Long.MAX_VALUE else (cap - total).coerceAtLeast(0)

    /**
     * Call before every flush with the current local date. On the first call of a new day the
     * total restarts from [freshTotal] (what Health Connect already holds for the new day, usually
     * 0) and true is returned so the caller can tell the UI.
     */
    fun rollTo(today: LocalDate, freshTotal: Long = 0): Boolean {
        if (today == day) return false
        day = today
        total = freshTotal
        return true
    }

    companion object {
        /**
         * 每日步數上限 as typed in Settings: "若未輸入則不限制" (2026-10-08), so nothing / blank / anything that is not a
         * positive whole number means no cap. There is no ceiling any more either: 850000 used to become 200000 silently (PLAN N5).
         */
        fun parseCap(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it > 0 }

        /** What Settings accepts: empty (no cap) or a positive whole number. */
        fun isValidCapInput(raw: String?): Boolean = raw.isNullOrBlank() || parseCap(raw) != null
    }
}
