package app.pikminbloom.gps.ui

/**
 * 「Google 定位準確度」(Google Location Accuracy) before a patrol starts (PLAN O6, 2026-10-08).
 *
 * With it on, Play services computes positions from nearby Wi-Fi and cell towers on its own. Whenever the mock has a
 * gap (its mock mode dropped, the permission was reset), that real position is right there for the game: the Japan →
 * Taiwan jump. With it off, the only source left is GPS, which is ours while the patrol runs (narumiruna/kestrel's
 * README recommends exactly this, PLAN O5). On phones with Play services it is what enables the platform's "network"
 * provider, so the check reads that provider - only while no test provider of ours is installed (a fresh start).
 */
object LocationAccuracyAdvice {
    /** Ask before a fresh start, not a resume (whose stale test providers make the network provider look on). */
    fun shouldWarn(networkProviderEnabled: Boolean, resuming: Boolean, muted: Boolean): Boolean =
        networkProviderEnabled && !resuming && !muted
}
