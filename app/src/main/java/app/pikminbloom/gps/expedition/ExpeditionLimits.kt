package app.pikminbloom.gps.expedition

/** Limits for one 自動探險 run, as typed in the bar's dialog. */
object ExpeditionLimits {
    const val DEFAULT_DISPATCH = 10
    const val MIN_DISPATCH = 1
    const val MAX_DISPATCH = 50

    /**
     * The 最多派幾次 field: blank means [DEFAULT_DISPATCH], a whole number in
     * [MIN_DISPATCH]..[MAX_DISPATCH] (surrounding spaces ignored) is that number, and anything
     * else is null so the dialog can refuse it.
     */
    fun parseMax(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DEFAULT_DISPATCH
        val n = trimmed.toIntOrNull() ?: return null
        return n.takeIf { it in MIN_DISPATCH..MAX_DISPATCH }
    }
}
