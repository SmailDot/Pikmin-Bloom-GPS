package app.pikminbloom.gps.geo

/** WGS-84 coordinate in decimal degrees. Immutable value type shared by every module. */
data class LatLng(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lon in -180.0..180.0) { "lon out of range: $lon" }
    }

    override fun toString(): String = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)

    companion object {
        private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")

        /**
         * Lenient parse of what a user pastes from Google Maps: "35.68, 139.77", "35.68 139.77",
         * "35.6812°N 139.7671°E" (S/W negate), or a maps URL containing "@35.68,139.77,17z" — the
         * first two numbers found are lat and lon. Null for anything else or out of range.
         */
        fun parse(text: String?): LatLng? {
            val s = text?.trim().orEmpty()
            if (s.isEmpty()) return null
            val at = s.indexOf('@')
            val body = if (at >= 0) s.substring(at + 1) else s
            val nums = NUMBER.findAll(body).take(2).toList()
            if (nums.size < 2) return null
            fun signed(m: MatchResult): Double? {
                val v = m.value.toDoubleOrNull() ?: return null
                // A hemisphere letter right after the number (optionally after ° and spaces).
                val tail = body.substring(m.range.last + 1).trimStart('°', ' ')
                return if (tail.startsWith('S', true) || tail.startsWith('W', true)) -v else v
            }
            val lat = signed(nums[0]) ?: return null
            val lon = signed(nums[1]) ?: return null
            return runCatching { LatLng(lat, lon) }.getOrNull()
        }
    }
}
