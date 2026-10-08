package app.pikminbloom.gps.mock

/**
 * Which non-mock fixes the passive leak watch passes on (MockLocationController, service/MockGuard). The first version
 * passed every one and reported leaks that were not (review, 2026-10-08): the half second of real fixes Play services
 * still serves after 回到虛擬位置, and real fixes from a provider the user chose not to mock.
 */
object LeakFilter {
    /** Real fixes Play services computed just before its mock mode came on may still be on their way. */
    const val FLP_GRACE_NANOS = 2_000_000_000L

    /**
     * @param fixNanos the fix's elapsedRealtimeNanos; [startedNanos] when the mock was started.
     * @param viaFlp it came through Play services' FLP (else through a platform provider named [provider]).
     * @param flpMockSinceNanos when Play services last confirmed mock mode; 0 = not yet since the start. Kept when a push
     *        finds mock mode gone: the real fixes it serves from then on are exactly the leak.
     * @param ourProviders the platform test providers we installed.
     */
    fun passes(
        isMock: Boolean,
        fixNanos: Long,
        startedNanos: Long,
        viaFlp: Boolean,
        flpMockSinceNanos: Long,
        provider: String,
        ourProviders: Collection<String>,
    ): Boolean {
        if (isMock || fixNanos < startedNanos) return false
        return if (viaFlp) flpMockSinceNanos != 0L && fixNanos >= flpMockSinceNanos + FLP_GRACE_NANOS
        else provider in ourProviders
    }
}
