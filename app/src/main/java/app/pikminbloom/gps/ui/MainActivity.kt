package app.pikminbloom.gps.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.databinding.ActivityMainBinding
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.service.PatrolEvent
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.steps.StepInjector
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import kotlin.coroutines.resume

/**
 * Map + control screen: shows the Big Flowers, the planned route and the simulated walk, and drives
 * [PatrolService] through the pre-flight checks described in docs/PLAN.md §3.2.
 */
class MainActivity : AppCompatActivity(), MapEventsReceiver {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var store: WaypointStore
    private lateinit var mock: MockLocationController
    private lateinit var steps: StepInjector
    private lateinit var overlays: MapOverlays

    private lateinit var locationLauncher: ActivityResultLauncher<String>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private lateinit var healthLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var exportJsonLauncher: ActivityResultLauncher<String>
    private lateinit var exportGpxLauncher: ActivityResultLauncher<String>

    private var locationCallback: ((Boolean) -> Unit)? = null
    private var notificationCallback: ((Boolean) -> Unit)? = null
    private var healthCallback: ((Set<String>) -> Unit)? = null

    private val trail = ArrayList<GeoPoint>(TRAIL_LIMIT)
    private var trailLast: LatLng? = null
    private var lastBearing = 0f
    private var currentHome: LatLng? = null
    private var centeredOnRealPosition = false

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = Prefs(this)
        store = WaypointStore.get(this)
        mock = MockLocationController(this)
        steps = StepInjector(this)
        currentHome = prefs.home

        registerLaunchers()
        applyInsets()
        setupMap()
        setupButtons()
        collectFlows()

        maybeShowDisclaimer()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        currentHome = PatrolService.state.value.home ?: prefs.home
        rebuildOverlays()
        render(PatrolService.state.value)
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { binding.map.onDetach() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup

    private fun registerLaunchers() {
        locationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            locationCallback?.invoke(granted)
            locationCallback = null
        }
        notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationCallback?.invoke(granted)
            notificationCallback = null
        }
        healthLauncher = registerForActivityResult(steps.permissionContract()) { granted ->
            healthCallback?.invoke(granted)
            healthCallback = null
        }
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }
        exportJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_JSON)) { uri ->
            if (uri != null) exportTo(uri) { store.exportJson() }
        }
        exportGpxLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_GPX)) { uri ->
            if (uri != null) exportTo(uri) { store.exportGpx() }
        }
    }

    private fun applyInsets() {
        val margin = resources.getDimensionPixelSize(R.dimen.space_s)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            (binding.bottomCard.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = bars.bottom + margin
                lp.leftMargin = bars.left + margin
                lp.rightMargin = bars.right + margin
                binding.bottomCard.layoutParams = lp
            }
            insets
        }
    }

    private fun setupMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(DEFAULT_ZOOM)
        }
        overlays = MapOverlays(this, binding.map, MapEventsOverlay(this), ::onWaypointTapped)
        rebuildOverlays()
        centerInitially()
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { startPatrol(0) }
        binding.btnPause.setOnClickListener { PatrolService.pause(this) }
        binding.btnResume.setOnClickListener { PatrolService.resume(this) }
        binding.btnHome.setOnClickListener { PatrolService.returnHome(this) }
        binding.btnStop.setOnClickListener { confirmStop() }
    }

    /** A plain stop leaves the game at the fake position (looks like a teleport); suggest 回家 first. */
    private fun confirmStop() {
        val phase = PatrolService.state.value.phase
        val movingOrPaused = phase == PatrolPhase.WALKING || phase == PatrolPhase.DWELLING || phase == PatrolPhase.PAUSED
        if (!movingOrPaused) {
            PatrolService.stop(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_stop_title)
            .setMessage(R.string.dlg_stop_msg)
            .setPositiveButton(R.string.action_return_home_first) { _, _ -> PatrolService.returnHome(this) }
            .setNegativeButton(R.string.action_stop_anyway) { _, _ -> PatrolService.stop(this) }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    private fun collectFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    store.waypoints.collect {
                        rebuildOverlays()
                        render(PatrolService.state.value)
                    }
                }
                launch { PatrolService.state.collect { render(it) } }
                launch { PatrolService.events.collect { onEvent(it) } }
            }
        }
    }

    // ------------------------------------------------------------------ map

    private fun centerInitially() {
        val known = PatrolService.state.value.position ?: prefs.home ?: prefs.lastPosition
        if (known != null) {
            centeredOnRealPosition = true
            center(known, resetZoom = true)
            return
        }
        center(FALLBACK_CENTER, resetZoom = true)
        if (!Permissions.hasFineLocation(this) || PatrolService.isRunning) return
        lifecycleScope.launch {
            val real = mock.currentRealLocation(REAL_FIX_TIMEOUT_MS)
            if (real != null && !centeredOnRealPosition) {
                centeredOnRealPosition = true
                prefs.lastPosition = real
                center(real, resetZoom = true)
            }
        }
    }

    private fun center(p: LatLng, resetZoom: Boolean = false) {
        val gp = GeoPoint(p.lat, p.lon)
        if (resetZoom) {
            binding.map.controller.setZoom(DEFAULT_ZOOM)
            binding.map.controller.setCenter(gp)
        } else {
            binding.map.controller.animateTo(gp)
        }
    }

    private var overlayGeneration = 0L

    private fun rebuildOverlays() {
        val waypoints = store.load()
        val home = currentHome
        val generation = ++overlayGeneration
        // Draw markers/circles immediately; the route preview (planner) is computed off the main thread.
        overlays.rebuildStatic(waypoints, home, emptyList())
        if (waypoints.isEmpty()) return
        lifecycleScope.launch {
            val route = withContext(Dispatchers.Default) { plannedRoute(waypoints, home) }
            if (generation == overlayGeneration && route.isNotEmpty()) {
                overlays.rebuildStatic(waypoints, home, route)
            }
        }
    }

    /** The route the planner would walk on lap 0, used purely as a preview polyline. */
    private fun plannedRoute(waypoints: List<Waypoint>, home: LatLng?): List<GeoPoint> {
        if (waypoints.isEmpty()) return emptyList()
        val config = prefs.config()
        val start = home ?: waypoints.first().latLng
        val plan = runCatching {
            PatrolPlanner.planLap(
                start = start,
                waypoints = waypoints,
                config = config,
                order = PatrolPlanner.orderFor(0, waypoints.size, config.loopMode),
            )
        }.getOrNull() ?: return emptyList()
        if (plan.isEmpty) return emptyList()
        val points = ArrayList<GeoPoint>(plan.segments.size + 1)
        points.add(GeoPoint(plan.segments.first().from.lat, plan.segments.first().from.lon))
        plan.segments.forEach { points.add(GeoPoint(it.to.lat, it.to.lon)) }
        return points
    }

    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

    override fun longPressHelper(p: GeoPoint?): Boolean {
        if (p == null) return false
        WaypointDialogs.showEditor(this, prefs, null, p.latitude, p.longitude, store.load().size + 1) {
            store.add(it)
        }
        return true
    }

    private fun onWaypointTapped(index: Int) {
        WaypointDialogs.showMarkerActions(this, store, prefs, index) { startPatrol(it) }
    }

    // ------------------------------------------------------------------ state rendering

    private fun render(state: PatrolState) {
        val home = state.home ?: prefs.home
        if (home != currentHome) {
            currentHome = home
            rebuildOverlays()
        }
        renderStatus(state)
        renderButtons(state.phase)
        renderPosition(state)
    }

    private fun renderStatus(state: PatrolState) {
        binding.tvPhase.text = phaseText(state.phase)

        val lines = ArrayList<String>(6)
        val name = state.currentWaypointName
        if (state.phase == PatrolPhase.IDLE || name == null) {
            lines += getString(R.string.status_no_target)
            lines += getString(R.string.status_waypoint_count, store.load().size)
            val h = currentHome
            lines += if (h == null) getString(R.string.status_home_none)
            else getString(R.string.status_home_set, h.toString())
        } else {
            lines += getString(R.string.status_target, name, distanceText(state.distanceToTargetM))
        }
        lines += getString(R.string.status_walked, distanceText(state.distanceWalkedM))
        lines += getString(R.string.status_session_steps, state.sessionSteps)
        lines += getString(R.string.status_today_steps, state.stepsWrittenToday)
        lines += getString(R.string.status_speed, "%.1f".format(state.speedMps * 3.6))
        binding.tvDetails.text = lines.joinToString("\n")

        val error = state.lastError
        binding.tvError.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!error.isNullOrBlank()) binding.tvError.text = getString(R.string.status_error, error)
    }

    private fun renderButtons(phase: PatrolPhase) {
        val moving = phase == PatrolPhase.WALKING || phase == PatrolPhase.DWELLING
        show(binding.btnStart, phase == PatrolPhase.IDLE)
        show(binding.btnPause, moving)
        show(binding.btnResume, phase == PatrolPhase.PAUSED)
        show(binding.btnHome, moving || phase == PatrolPhase.PAUSED)
        // 停止 stays available while STARTING so a slow GPS fix can be aborted.
        show(binding.btnStop, phase != PatrolPhase.IDLE && phase != PatrolPhase.STOPPING)
    }

    private fun renderPosition(state: PatrolState) {
        val p = state.position
        if (state.phase == PatrolPhase.IDLE || p == null) {
            if (trail.isNotEmpty()) {
                trail.clear()
                trailLast = null
                overlays.updateTrail(trail)
            }
            overlays.updatePosition(null, 0f)
            return
        }
        val previous = trailLast
        if (previous == null || GeoMath.distanceM(previous, p) >= TRAIL_MIN_STEP_M) {
            if (previous != null) lastBearing = GeoMath.bearingDeg(previous, p).toFloat()
            trailLast = p
            trail.add(GeoPoint(p.lat, p.lon))
            while (trail.size > TRAIL_LIMIT) trail.removeAt(0)
            overlays.updateTrail(trail)
        }
        overlays.updatePosition(p, lastBearing)
    }

    private fun phaseText(phase: PatrolPhase): String = getString(
        when (phase) {
            PatrolPhase.IDLE -> R.string.phase_idle
            PatrolPhase.STARTING -> R.string.phase_starting
            PatrolPhase.WALKING -> R.string.phase_walking
            PatrolPhase.DWELLING -> R.string.phase_dwelling
            PatrolPhase.PAUSED -> R.string.phase_paused
            PatrolPhase.RETURNING_HOME -> R.string.phase_returning
            PatrolPhase.STOPPING -> R.string.phase_stopping
        }
    )

    private fun onEvent(event: PatrolEvent) {
        when (event) {
            is PatrolEvent.Error -> snack(event.message, Snackbar.LENGTH_LONG)
            is PatrolEvent.ArrivedAtWaypoint ->
                snack(getString(R.string.snack_arrived, event.name), Snackbar.LENGTH_SHORT)
            is PatrolEvent.ReturnedHome ->
                snack(getString(R.string.snack_returned_home), Snackbar.LENGTH_LONG)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_my_location -> { goToMyLocation(); true }
        R.id.action_waypoint_list -> { showWaypointList(); true }
        R.id.action_setup -> { startActivity(Intent(this, SetupActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_clear_home -> { clearHome(); true }
        R.id.action_import -> { importLauncher.launch(arrayOf(MIME_ANY)); true }
        R.id.action_export_json -> { exportJsonLauncher.launch(getString(R.string.export_json_filename)); true }
        R.id.action_export_gpx -> { exportGpxLauncher.launch(getString(R.string.export_gpx_filename)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showWaypointList() {
        WaypointDialogs.showList(
            activity = this,
            store = store,
            prefs = prefs,
            onFocus = { center(LatLng(it.lat, it.lon)) },
            onStartHere = { startPatrol(it) },
            onAddRequested = { addWaypointAtMapCenter() },
        )
    }

    private fun addWaypointAtMapCenter() {
        val center = binding.map.mapCenter
        WaypointDialogs.showEditor(
            this, prefs, null, center.latitude, center.longitude, store.load().size + 1,
        ) { store.add(it) }
    }

    private fun clearHome() {
        prefs.home = null
        currentHome = null
        rebuildOverlays()
        toast(getString(R.string.toast_home_cleared))
    }

    private fun goToMyLocation() {
        val state = PatrolService.state.value
        val simulated = state.position
        if (state.phase != PatrolPhase.IDLE && simulated != null) {
            center(simulated)
            return
        }
        if (!Permissions.hasFineLocation(this)) {
            lifecycleScope.launch { requestLocationPermission() }
            return
        }
        toast(getString(R.string.toast_locating))
        lifecycleScope.launch {
            val real = mock.currentRealLocation(REAL_FIX_TIMEOUT_MS)
            if (real == null) {
                toast(getString(R.string.toast_location_unavailable))
            } else {
                prefs.lastPosition = real
                centeredOnRealPosition = true
                center(real)
            }
        }
    }

    // ------------------------------------------------------------------ import / export

    private fun importFrom(uri: Uri) {
        val existing = store.load().size
        if (existing > 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_import_replace_title)
                .setMessage(getString(R.string.dlg_import_replace_msg, existing))
                .setPositiveButton(R.string.action_replace) { _, _ -> importNow(uri) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            importNow(uri)
        }
    }

    private fun importNow(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                toast(getString(R.string.toast_import_failed)); return@launch
            }
            val count = withContext(Dispatchers.Default) {
                runCatching {
                    if (text.contains(GPX_MARKER, ignoreCase = true)) {
                        store.importGpx(text, prefs.defaultRadiusM, prefs.defaultDwellSec)
                    } else {
                        store.importJson(text)
                    }
                }.getOrDefault(0)
            }
            if (count > 0) toast(getString(R.string.toast_import_done, count))
            else toast(getString(R.string.toast_import_failed))
        }
    }

    private fun exportTo(uri: Uri, content: () -> String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(content().toByteArray()) } != null
                }.getOrDefault(false)
            }
            toast(getString(if (ok) R.string.toast_export_done else R.string.toast_export_failed))
        }
    }

    // ------------------------------------------------------------------ pre-flight + start

    private fun startPatrol(startAtIndex: Int) {
        if (PatrolService.isRunning) return
        lifecycleScope.launch {
            if (!preflight()) return@launch
            val home = resolveHome()
            PatrolService.start(this@MainActivity, home, startAtIndex)
        }
    }

    /** Runs every check in order, offering the fix for each. Returns false when the patrol must not start. */
    private suspend fun preflight(): Boolean {
        // 1. Precise location — mandatory (the service captures "home" from the real GPS).
        if (!Permissions.hasFineLocation(this)) {
            val go = ask(R.string.dlg_need_location_title, R.string.dlg_need_location_msg, R.string.action_grant)
            if (!go || !requestLocationPermission()) {
                toast(getString(R.string.toast_location_denied)); return false
            }
        }

        // 2. Notifications — optional, the foreground service runs either way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.hasNotifications(this)) {
            val go = ask(
                R.string.dlg_need_notification_title, R.string.dlg_need_notification_msg,
                R.string.action_grant, R.string.action_continue,
            )
            if (go) requestNotificationPermission()
        }

        // 3. This app must be the selected mock location app.
        if (!mock.isMockAppSelected()) {
            val go = ask(
                R.string.dlg_need_mock_title, R.string.dlg_need_mock_msg,
                R.string.action_open_developer_options,
            )
            if (go) mock.openMockAppPicker(this)
            return false
        }

        // 4. At least one Big Flower.
        if (store.load().isEmpty()) {
            ask(R.string.dlg_no_waypoints_title, R.string.dlg_no_waypoints_msg, R.string.action_ok, null)
            return false
        }

        // 5. Health Connect, only when step injection is on.
        if (prefs.config().injectSteps) {
            if (!steps.isAvailable) {
                return ask(
                    R.string.dlg_hc_unavailable_title, R.string.dlg_hc_unavailable_msg,
                    R.string.action_continue,
                )
            }
            if (!steps.hasPermissions()) {
                val grant = ask(
                    R.string.dlg_hc_permission_title, R.string.dlg_hc_permission_msg,
                    R.string.action_grant, R.string.action_continue_without_steps,
                )
                if (!grant) return true
                if (!requestHealthPermissions()) {
                    return ask(
                        R.string.dlg_hc_permission_title, R.string.dlg_hc_permission_msg,
                        R.string.action_continue_without_steps,
                    )
                }
            }
        }
        return true
    }

    /** Reuses a recent home (so we do not need a fresh fix) or returns null to let the service locate. */
    private suspend fun resolveHome(): LatLng? {
        val saved = prefs.home ?: return null
        if (prefs.savedHomeAgeMs >= HOME_MAX_AGE_MS) return null
        val reuse = ask(
            titleRes = R.string.dlg_home_title,
            message = getString(R.string.dlg_home_msg, saved.toString(), ageText(prefs.savedHomeAgeMs)),
            positiveRes = R.string.action_reuse_home,
            negativeRes = R.string.action_relocate,
        )
        return if (reuse) saved else null
    }

    private fun maybeShowDisclaimer() {
        if (prefs.disclaimerAccepted) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_disclaimer_title)
            .setMessage(R.string.dlg_disclaimer_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.action_accept) { _, _ -> prefs.disclaimerAccepted = true }
            .setNegativeButton(R.string.action_exit) { _, _ -> finish() }
            .show()
    }

    // ------------------------------------------------------------------ suspend helpers

    private suspend fun requestLocationPermission(): Boolean = suspendCancellableCoroutine { cont ->
        locationCallback = { granted -> if (cont.isActive) cont.resume(granted) }
        locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private suspend fun requestNotificationPermission(): Boolean = suspendCancellableCoroutine { cont ->
        notificationCallback = { granted -> if (cont.isActive) cont.resume(granted) }
        notificationLauncher.launch(NOTIFICATION_PERMISSION)
    }

    private suspend fun requestHealthPermissions(): Boolean = suspendCancellableCoroutine { cont ->
        healthCallback = { granted -> if (cont.isActive) cont.resume(granted.containsAll(steps.requiredPermissions)) }
        healthLauncher.launch(steps.requiredPermissions)
    }

    private suspend fun ask(
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        @StringRes positiveRes: Int,
        @StringRes negativeRes: Int? = R.string.action_cancel,
    ): Boolean = ask(titleRes, getString(messageRes), positiveRes, negativeRes)

    private suspend fun ask(
        @StringRes titleRes: Int,
        message: String,
        @StringRes positiveRes: Int,
        @StringRes negativeRes: Int? = R.string.action_cancel,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setOnDismissListener { if (cont.isActive) cont.resume(false) }
        if (negativeRes != null) builder.setNegativeButton(negativeRes, null)
        val dialog = builder.create()
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
    }

    // ------------------------------------------------------------------ small helpers

    private fun show(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun distanceText(metres: Double): String =
        if (metres >= 1000.0) getString(R.string.fmt_distance_km, metres / 1000.0)
        else getString(R.string.fmt_distance_m, metres)

    private fun ageText(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes < 60) getString(R.string.fmt_minutes_ago, minutes)
        else getString(R.string.fmt_hours_ago, minutes / 60)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun snack(text: String, duration: Int) {
        Snackbar.make(binding.root, text, duration).setAnchorView(binding.bottomCard).show()
    }

    companion object {
        private const val DEFAULT_ZOOM = 17.0
        private const val TRAIL_LIMIT = 2000
        private const val TRAIL_MIN_STEP_M = 1.0
        private const val REAL_FIX_TIMEOUT_MS = 15_000L
        private const val HOME_MAX_AGE_MS = 12L * 60 * 60 * 1000
        private const val MIME_ANY = "*/*"
        private const val MIME_JSON = "application/json"
        private const val MIME_GPX = "application/gpx+xml"
        private const val GPX_MARKER = "<gpx"
        private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"

        /** Taipei 101, used only when nothing at all is known yet. */
        private val FALLBACK_CENTER = LatLng(25.0330, 121.5654)
    }
}
