package app.pikminbloom.gps.mock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import app.pikminbloom.gps.data.PatrolConfig
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.sim.Sample
import app.pikminbloom.gps.sim.WalkSimulator
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Thrown when this app is not the selected "mock location app" in Developer options. */
class MockNotAllowedException(msg: String) : Exception(msg)

/**
 * Drives Android's test location providers (and Play services' FLP mock mode) so that other apps —
 * Pikmin Bloom in particular — see the simulated walk instead of the real GPS.
 *
 * Requires the user to pick this app in 開發者選項 → 選擇模擬位置應用程式
 * (`adb shell appops set app.pikminbloom.gps android:mock_location allow`).
 */
class MockLocationController(context: Context) {

    private val app: Context = context.applicationContext
    private val lm: LocationManager? = app.getSystemService(LocationManager::class.java)
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(app)
    }

    /** Providers we successfully installed, in push order. Changed on the engine thread, read by the leak watch on main too. */
    private val activeProviders = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Written by Play services' callbacks on the main thread, read on the engine thread. */
    @Volatile private var flpMockEnabled = false

    /** setMockMode(true) was asked for; its async answer may not be in yet, and [stop] must undo it either way. */
    @Volatile private var flpMockRequested = false

    val isRunning: Boolean get() = activeProviders.isNotEmpty() || flpMockEnabled

    /** What [start] installed with; [repairIfNeeded] re-installs the same. */
    private var config: PatrolConfig? = null

    /** One pushed fix, kept so a repair can put the game straight back where it was. */
    private data class Fix(val position: LatLng, val speedMps: Double, val bearingDeg: Double, val accuracyM: Float, val altitudeM: Double)

    @Volatile private var lastFix: Fix? = null

    /** The last few pushed positions: a fix of ours that comes back late is not mistaken for a leak (MockGuard.isLeak). */
    private val recent = ArrayDeque<LatLng>(RECENT_PUSHES)

    /**
     * Why the mock needs re-installing (a push that failed, a leaked real fix, the permission coming back); null = fine.
     * [repairProviders] = the test providers too, not only Play services' mock mode (a real fix came through them).
     */
    @Volatile private var repairReason: String? = null
    @Volatile private var repairProviders = false
    private var lastRepairMs: Long? = null

    /**
     * Called (main thread) with every location fix the system hands out that is NOT a mock one, while the mock is on.
     * PatrolService decides whether it is a leak (MockGuard). Set it before [start].
     */
    var onForeignFix: ((provider: String, fix: LatLng, accuracyM: Float) -> Unit)? = null

    private var passiveListener: LocationListener? = null
    private var flpWatch: LocationCallback? = null

    /** When [start] ran (elapsed realtime): a real fix from before then (the start's own GPS fix, in flight) is no leak. */
    @Volatile private var startedAtNanos = 0L

    /** When Play services last confirmed mock mode (elapsed realtime); 0 = not yet since [start]. */
    @Volatile private var flpMockSinceNanos = 0L

    /**
     * A fix that can only be a leak ([LeakFilter]): not a mock one, taken after [start], and through something this mock
     * covers - one of our platform providers, or Play services once its mock mode is confirmed (plus a grace period).
     */
    private fun foreign(location: Location, provider: String, viaFlp: Boolean) {
        // Not reset when a push finds mock mode gone: the real fixes Play services serves from then on are the leak.
        if (!LeakFilter.passes(location.isMockFix(), location.elapsedRealtimeNanos, startedAtNanos, viaFlp, flpMockSinceNanos, provider, activeProviders)) return
        onForeignFix?.invoke(provider, location.toLatLng(), location.accuracy)
    }

    // ---------------------------------------------------------------- selection

    /** True when this app is the selected mock location app (AppOps `android:mock_location`). */
    fun isMockAppSelected(): Boolean {
        val ops = app.getSystemService(AppOpsManager::class.java) ?: return false
        val uid = android.os.Process.myUid()
        val pkg = app.packageName
        return try {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, pkg)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, pkg)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            Log.w(TAG, "isMockAppSelected failed", t)
            false
        }
    }

    /** Opens 設定 → 開發者選項 so the user can pick this app as the mock location app. */
    fun openMockAppPicker(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        try {
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "developer settings unavailable, falling back to app details", t)
            try {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            } catch (t2: Throwable) {
                Log.w(TAG, "no settings activity at all", t2)
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Installs the test providers. Throws [MockNotAllowedException] when the platform refuses us
     * (app not selected in Developer options) or when no provider could be installed at all.
     */
    /**
     * @param keepExisting true when RESUMING after a process death: the previous process's test
     *        providers are still registered and the game is still showing their last fix. Removing
     *        them first would expose the real GPS for a moment (a visible teleport), so instead
     *        they are replaced in place (addTestProvider replaces a same-named provider on
     *        Android 11+) and the caller pushes the checkpoint position immediately afterwards.
     */
    fun start(config: PatrolConfig, keepExisting: Boolean = false) {
        val manager = lm ?: throw MockNotAllowedException("LocationManager unavailable")
        activeProviders.clear()
        lastFlpError = null
        this.config = config
        startedAtNanos = SystemClock.elapsedRealtimeNanos()
        synchronized(repairLock) { repairReason = null; repairProviders = false }
        if (!keepExisting) {
            // Belt and braces: a crashed previous run may have left providers behind. Only
            // meaningful while we are the selected mock app (no-op / SecurityException otherwise).
            for (provider in ALL_PROVIDERS) runCatching { manager.removeTestProvider(provider) }
        }

        val wanted = wantedProviders(config)

        var security: SecurityException? = null
        for (provider in wanted) {
            try {
                // A previous run may have left the provider installed. When resuming we rely on
                // addTestProvider replacing it in place instead, to avoid a gap.
                if (!keepExisting) runCatching { manager.removeTestProvider(provider) }
                addTestProvider(manager, provider)
                manager.setTestProviderEnabled(provider, true)
                activeProviders.add(provider)
                Log.i(TAG, "test provider ready: $provider")
            } catch (e: SecurityException) {
                security = e
                Log.w(TAG, "not allowed to mock $provider", e)
            } catch (e: IllegalArgumentException) {
                // Provider already exists / unknown provider on this device: try to just enable it.
                Log.w(TAG, "addTestProvider($provider) rejected", e)
                try {
                    manager.setTestProviderEnabled(provider, true)
                    activeProviders.add(provider)
                } catch (t: Throwable) {
                    Log.w(TAG, "setTestProviderEnabled($provider) failed too", t)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "unexpected failure installing $provider", t)
            }
        }

        if (activeProviders.isEmpty()) {
            throw MockNotAllowedException(
                security?.message ?: "無法啟用模擬位置提供者，請確認已在開發者選項選擇本 App"
            )
        }

        if (config.useFlpMockMode) enableFlpMock()
        startLeakWatch()
    }

    private fun wantedProviders(config: PatrolConfig): List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (config.mockNetworkProvider) add(LocationManager.NETWORK_PROVIDER)
        if (config.mockFusedProvider && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
    }

    /**
     * Puts Play services' FLP in mock mode. Entering it clears the FLP's cached fixes, so the last fix is handed over
     * the moment it is on (no-op at the very start, where there is none yet).
     */
    private fun enableFlpMock() {
        try {
            flpMockRequested = true
            fused.setMockMode(true)
                .addOnSuccessListener {
                    // A stop() that came in meanwhile has turned it off again: do not believe it is on.
                    if (!flpMockRequested) return@addOnSuccessListener
                    flpMockEnabled = true
                    flpMockSinceNanos = SystemClock.elapsedRealtimeNanos()
                    lastFlpError = null
                    Log.i(TAG, "FLP mock mode on")
                    lastFix?.let { f -> pushFlp(f, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()) }
                }
                .addOnFailureListener {
                    flpMockEnabled = false
                    lastFlpError = it.message
                    Log.w(TAG, "FLP setMockMode(true) failed: ${it.message}")
                }
        } catch (t: Throwable) {
            lastFlpError = t.message
            Log.w(TAG, "FLP setMockMode threw", t)
        }
    }

    // ---------------------------------------------------------------- keeping it in place (service/MockGuard)

    private val repairLock = Any()

    /** Something says the mock may not hold any more; the next [repairIfNeeded] re-installs it. Any thread. */
    fun requestRepair(reason: String, providers: Boolean = false) = synchronized(repairLock) {
        if (providers) repairProviders = true
        if (repairReason == null) repairReason = reason
    }

    /**
     * Engine thread. When a repair is pending and the last one is at least [minGapMs] ago: re-installs the test
     * providers (in place, no gap - see [start]'s keepExisting) when they were the problem, turns Play services' mock
     * mode on again, and pushes the last fix at once. Returns the reason it repaired for, else null.
     */
    fun repairIfNeeded(nowMs: Long, minGapMs: Long): String? {
        val last = lastRepairMs
        if (last != null && nowMs - last < minGapMs) return null
        val cfg = config ?: return null
        // Taken together, so a requestRepair(providers = true) from the main thread cannot fall between the two.
        val (reason, providers) = synchronized(repairLock) {
            val r = repairReason ?: return null
            val p = repairProviders
            repairReason = null
            repairProviders = false
            r to p
        }
        lastRepairMs = nowMs
        val manager = lm
        if (providers && manager != null) {
            for (provider in wantedProviders(cfg)) {
                try {
                    addTestProvider(manager, provider)
                    manager.setTestProviderEnabled(provider, true)
                } catch (e: IllegalArgumentException) {
                    runCatching { manager.setTestProviderEnabled(provider, true) }
                        .onFailure { Log.w(TAG, "re-enable $provider failed: ${it.message}") }
                } catch (t: Throwable) {
                    Log.w(TAG, "re-install $provider failed: ${t.message}")
                    continue
                }
                if (provider !in activeProviders) activeProviders.add(provider)
            }
        }
        if (cfg.useFlpMockMode) enableFlpMock()
        lastFix?.let { pushFix(it) }
        return if (providers) "$reason (providers + FLP)" else "$reason (FLP)"
    }

    /** Positions pushed lately, newest last. */
    fun recentPushes(): List<LatLng> = synchronized(recent) { recent.toList() }

    /**
     * Passive listeners on both the platform and Play services: they cost nothing (no fix is computed for them) and see
     * every fix handed to anyone - the game included. Ours are mock fixes; anything else goes to [onForeignFix].
     */
    @SuppressLint("MissingPermission")
    private fun startLeakWatch() {
        stopLeakWatch()
        if (!hasLocationPermission()) return
        lm?.let { manager ->
            // Every method overridden: before Android 11 the others are abstract, and a lambda would crash when called.
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = foreign(location, location.provider ?: "passive", viaFlp = false)
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            runCatching { manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, listener, Looper.getMainLooper()) }
                .onSuccess { passiveListener = listener }
                .onFailure { Log.w(TAG, "passive leak watch failed: ${it.message}") }
        }
        // Play services' FLP is only watched when we put it in mock mode: otherwise its real fixes are expected.
        if (config?.useFlpMockMode != true) return
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) foreign(location, FLP_WATCH_PROVIDER, viaFlp = true)
            }
        }
        runCatching {
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_PASSIVE, FLP_WATCH_INTERVAL_MS).build(), callback, Looper.getMainLooper())
            flpWatch = callback
        }.onFailure { Log.w(TAG, "FLP leak watch failed: ${it.message}") }
    }

    private fun stopLeakWatch() {
        passiveListener?.let { l -> runCatching { lm?.removeUpdates(l) } }
        passiveListener = null
        flpWatch?.let { cb -> runCatching { fused.removeLocationUpdates(cb) } }
        flpWatch = null
    }

    /** Last Fused Location Provider mock-mode failure, if any (null when everything worked). */
    @Volatile var lastFlpError: String? = null
        private set

    private fun addTestProvider(manager: LocationManager, provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val power = if (provider == LocationManager.GPS_PROVIDER) {
                ProviderProperties.POWER_USAGE_HIGH
            } else {
                ProviderProperties.POWER_USAGE_LOW
            }
            val props = ProviderProperties.Builder()
                .setHasNetworkRequirement(false)
                .setHasSatelliteRequirement(false)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(power)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            manager.addTestProvider(provider, props)
        } else {
            @Suppress("DEPRECATION")
            manager.addTestProvider(
                provider,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
        }
    }

    /** Pushes one simulated fix to every active provider. Never throws. */
    fun push(sample: Sample) =
        pushFix(Fix(sample.position, sample.speedMps, sample.bearingDeg, sample.accuracyM, sample.altitudeM))

    /** Pushes a raw position (used for the final "settle at home" fixes). */
    fun pushRaw(
        position: LatLng,
        speedMps: Double = 0.0,
        bearingDeg: Double = 0.0,
        accuracyM: Float = 5f,
        altitudeM: Double = WalkSimulator.ALTITUDE_M,
    ) = pushFix(Fix(position, speedMps, bearingDeg, accuracyM, altitudeM))

    /**
     * Every push goes through here. A failure no longer just logs: it asks for a repair (2026-10-08), because a provider
     * that refuses fixes, or an FLP that left mock mode, is exactly how the real position reaches the game.
     */
    private fun pushFix(f: Fix) {
        lastFix = f
        synchronized(recent) {
            if (recent.size == RECENT_PUSHES) recent.removeFirst()
            recent.addLast(f.position)
        }
        val manager = lm ?: return
        val now = System.currentTimeMillis()
        val nanos = SystemClock.elapsedRealtimeNanos()
        for (provider in activeProviders) {
            try {
                manager.setTestProviderLocation(provider, buildLocation(provider, f, now, nanos))
            } catch (t: Throwable) {
                Log.w(TAG, "setTestProviderLocation($provider) failed: ${t.message}")
                requestRepair("push to $provider failed: ${t.message}", providers = true)
            }
        }
        if (flpMockEnabled) pushFlp(f, now, nanos)
    }

    private fun pushFlp(f: Fix, now: Long, nanos: Long) {
        try {
            fused.setMockLocation(buildLocation(LocationManager.FUSED_PROVIDER, f, now, nanos))
                .addOnFailureListener {
                    // Play services is not in mock mode any more (its client connection dropped, or it restarted):
                    // until it is again, every app asking the FLP gets the real position.
                    Log.w(TAG, "FLP setMockLocation failed: ${it.message}")
                    flpMockEnabled = false
                    lastFlpError = it.message
                    requestRepair("FLP setMockLocation failed: ${it.message}")
                }
        } catch (t: Throwable) {
            Log.w(TAG, "FLP setMockLocation threw", t)
            requestRepair("FLP setMockLocation threw: ${t.message}")
        }
    }

    /** Removes every test provider and turns FLP mock mode off. Never throws. */
    fun stop() {
        // First: from here on real fixes are expected, and must not read as leaks.
        stopLeakWatch()
        synchronized(repairLock) { repairReason = null; repairProviders = false }
        flpMockSinceNanos = 0L
        lastFix = null
        synchronized(recent) { recent.clear() }
        val manager = lm
        if (manager != null) {
            // Remove every provider we may own, including ones left by a killed earlier process
            // (activeProviders is empty after a restart). Harmless when nothing is installed.
            val toRemove = if (activeProviders.isEmpty()) ALL_PROVIDERS else activeProviders.toList()
            for (provider in toRemove) {
                runCatching { manager.setTestProviderEnabled(provider, false) }
                runCatching { manager.removeTestProvider(provider) }
                    .onFailure { Log.w(TAG, "remove $provider: ${it.message}") }
            }
        }
        activeProviders.clear()
        // Also while the async enable has not answered: a quick 回到虛擬位置 → 真實位置 otherwise left FLP
        // mocked, and every FLP client (Google Maps) without a real fix.
        if (flpMockEnabled || flpMockRequested) {
            flpMockEnabled = false
            flpMockRequested = false
            try {
                fused.setMockMode(false)
                    .addOnFailureListener { Log.w(TAG, "FLP setMockMode(false) failed: ${it.message}") }
            } catch (t: Throwable) {
                Log.w(TAG, "FLP setMockMode(false) threw", t)
            }
        }
        Log.i(TAG, "mock stopped")
    }

    // ---------------------------------------------------------------- real position

    /**
     * The device's real position. ONLY meaningful while the mock is stopped — call it before
     * [start]. Returns null when we have no permission or nothing usable within [timeoutMs].
     */
    @SuppressLint("MissingPermission")
    suspend fun currentRealLocation(timeoutMs: Long = 15_000): LatLng? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "currentRealLocation: no ACCESS_FINE_LOCATION")
            return null
        }
        val cts = CancellationTokenSource()
        val fromFlp = try {
            withTimeoutOrNull(timeoutMs) {
                val fresh = try {
                    awaitTask(fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token))
                } catch (t: Throwable) {
                    Log.w(TAG, "getCurrentLocation failed", t); null
                }
                fresh?.takeIf { !it.isMockFix() }?.toLatLng()
                    ?: try {
                        awaitTask(fused.lastLocation)?.takeIf { !it.isMockFix() }?.toLatLng()
                    } catch (t: Throwable) {
                        Log.w(TAG, "lastLocation failed", t); null
                    }
            }
        } finally {
            cts.cancel()   // stop the high-accuracy request when we time out or get cancelled
        }
        return fromFlp ?: lastKnownFromManager()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownFromManager(): LatLng? {
        val manager = lm ?: return null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = try {
                manager.getLastKnownLocation(provider)
            } catch (t: Throwable) {
                Log.w(TAG, "getLastKnownLocation($provider) failed: ${t.message}"); null
            }
            if (loc != null && !loc.isMockFix()) return loc.toLatLng()
        }
        return null
    }

    /**
     * The last fix the system is still serving from a (possibly stale, previous-process) test
     * provider - i.e. where the game currently sees the player parked after a crash. Null when the
     * last known fix is real or missing.
     */
    @SuppressLint("MissingPermission")
    fun lastParkedMockFix(): LatLng? {
        val manager = lm ?: return null
        if (!hasLocationPermission()) return null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (loc.isMockFix()) return loc.toLatLng()
        }
        return null
    }

    /**
     * Whether the platform's network provider is on, which on phones with Play services means 「Google 定位準確度」 is
     * (ui/LocationAccuracyAdvice). Only meaningful while none of our test providers is installed.
     */
    fun networkProviderEnabled(): Boolean =
        runCatching { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true }.getOrDefault(false)

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- helpers

    private fun buildLocation(provider: String, f: Fix, now: Long, nanos: Long): Location =
        buildLocation(provider, f.position, f.speedMps, f.bearingDeg, f.accuracyM, f.altitudeM, now, nanos)

    private fun buildLocation(
        provider: String,
        position: LatLng,
        speedMps: Double,
        bearingDeg: Double,
        accuracyM: Float,
        altitudeM: Double,
        now: Long,
        nanos: Long,
    ): Location = Location(provider).apply {
        latitude = position.lat
        longitude = position.lon
        altitude = altitudeM
        accuracy = accuracyM
        speed = speedMps.toFloat()
        bearing = ((bearingDeg % 360.0 + 360.0) % 360.0).toFloat()
        time = now
        elapsedRealtimeNanos = nanos
        // minSdk is 28, so these O-era setters are always available.
        verticalAccuracyMeters = 1.5f
        speedAccuracyMetersPerSecond = 0.3f
        bearingAccuracyDegrees = 5f
        extras = Bundle().apply {
            putInt("satellites", 9)
            putInt("satellitesUsedInFix", 9)
        }
    }

    private fun Location.toLatLng() = LatLng(latitude, longitude)

    private fun Location.isMockFix(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else {
            @Suppress("DEPRECATION") isFromMockProvider
        }

    private suspend fun <T> awaitTask(task: Task<T>): T? = suspendCancellableCoroutine { cont ->
        task.addOnCompleteListener { done ->
            if (cont.isActive) {
                if (done.isSuccessful) cont.resume(done.result) else {
                    Log.w(TAG, "task failed: ${done.exception?.message}")
                    cont.resume(null)
                }
            }
        }
    }

    companion object {
        const val TAG = "PikminGPS"
        /** The provider name [onForeignFix] reports for a fix that came through Play services' FLP. */
        const val FLP_WATCH_PROVIDER = "Play services FLP"
        /** How many recent pushes count as "ours" for a fix that comes back late. */
        private const val RECENT_PUSHES = 8
        /** The passive FLP watch's nominal interval; passive requests never cause a fix to be computed. */
        private const val FLP_WATCH_INTERVAL_MS = 10_000L
        private val ALL_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused",
        )
    }
}
