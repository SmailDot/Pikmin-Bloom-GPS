package app.pikminbloom.gps.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.ActionMenuView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.LocationJump
import app.pikminbloom.gps.data.PatrolPhase
import app.pikminbloom.gps.data.PatrolState
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.databinding.ActivityMainBinding
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.data.Home
import app.pikminbloom.gps.geo.LatLng
import app.pikminbloom.gps.mock.MockLocationController
import app.pikminbloom.gps.route.PatrolPlanner
import app.pikminbloom.gps.service.PatrolCheckpoint
import app.pikminbloom.gps.service.PatrolEvent
import app.pikminbloom.gps.service.PatrolService
import app.pikminbloom.gps.service.RealMode
import app.pikminbloom.gps.steps.StepInjector
import app.pikminbloom.gps.support.CrashLog
import app.pikminbloom.gps.support.FeedbackKind
import app.pikminbloom.gps.vision.FlowerScanPlan
import app.pikminbloom.gps.vision.FlowerScanner
import app.pikminbloom.gps.vision.ScreenCaptureService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import kotlin.coroutines.resume

/**
 * Map + control screen: shows the Big Flowers, the planned route and the simulated walk, and drives
 * [PatrolService] through the pre-flight checks.
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
    private lateinit var overlayLauncher: ActivityResultLauncher<Intent>
    private lateinit var scanLauncher: ActivityResultLauncher<Intent>

    /** Set by the 「先開始巡邏」 branch of the scan flow: continue to the consent once the walk begins. */
    private var scanAfterPatrolStart = false

    /** Set when the overlay's 掃描 button opened us (EXTRA_START_SCAN); handled in onResume. */
    private var pendingScanRequest = false

    /** Set when the notification's 回到虛擬位置… opened us (EXTRA_CONFIRM_SWITCH); asked in onResume. */
    private var pendingVirtualConfirm = false
    /** Set when the Maps question's notification opened us (MapsPrompt without the overlay permission). */
    private var pendingRealConfirm = false

    /** The state line's own colours, restored when 真實位置 (bold, tinted) ends. */
    private lateinit var locationStateColors: ColorStateList
    private var scanResultsDialog: androidx.appcompat.app.AlertDialog? = null

    private var locationCallback: ((Boolean) -> Unit)? = null
    private var notificationCallback: ((Boolean) -> Unit)? = null
    private var healthCallback: ((Set<String>) -> Unit)? = null

    private val trail = ArrayList<GeoPoint>(TRAIL_LIMIT)
    private var trailLast: LatLng? = null
    private var lastBearing = 0f
    private var currentHome: LatLng? = null
    private var centeredOnRealPosition = false

    /** Last phase we reacted to, so the floating bar is only started/stopped on a real transition. */
    private var overlayPhase: PatrolPhase? = null

    /** Non-null while the crosshair pick is up; receives the picked point on 確定. */
    private var onCenterPicked: ((LatLng) -> Unit)? = null
    private val pickBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = endCenterPick()
    }

    /** The spotlight tour over the main screen, while it runs. Back skips it. */
    private var tour: SpotlightView? = null
    private val tourBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            tour?.skip()
        }
    }

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
        onBackPressedDispatcher.addCallback(this, pickBack)
        onBackPressedDispatcher.addCallback(this, tourBack)
        collectFlows()

        maybeShowDisclaimer()
        handleScanIntent(intent)
        // A recreation (rotation, process restore) re-delivers the launch intent: never ask twice.
        if (savedInstanceState == null) handleSwitchIntent(intent)
        if (savedInstanceState == null) offerCrashReport()
    }

    /**
     * The app crashed since it was last open (CrashLog): offer, once, to report it. A snackbar rather than a dialog, so it
     * never stacks on the resume-from-checkpoint question that usually follows a crash; the menu's 回報問題 still attaches it.
     */
    private fun offerCrashReport() {
        val crash = CrashLog.unoffered(this, System.currentTimeMillis()) ?: return
        CrashLog.markOffered(this, crash)
        Snackbar.make(binding.root, R.string.snack_last_crash, CRASH_OFFER_MS)
            .setAnchorView(binding.bottomCard)
            .setAction(R.string.action_report) { FeedbackDialog.show(this, FeedbackKind.BUG) }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleScanIntent(intent)
        handleSwitchIntent(intent)
    }

    private fun handleScanIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_SCAN, false) == true) {
            intent.removeExtra(EXTRA_START_SCAN)
            pendingScanRequest = true
        }
    }

    private fun handleSwitchIntent(intent: Intent) {
        val wanted = intent.getStringExtra(EXTRA_CONFIRM_SWITCH) ?: return
        intent.removeExtra(EXTRA_CONFIRM_SWITCH)
        // Reopened from Recents, a task the notification started replays its intent: that tap is old news.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        if (wanted == CONFIRM_VIRTUAL) pendingVirtualConfirm = true
        if (wanted == CONFIRM_REAL) pendingRealConfirm = true
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        currentHome = PatrolService.state.value.home ?: prefs.home
        rebuildOverlays()
        render(PatrolService.state.value)
        maybeOfferResume()
        if (pendingVirtualConfirm) { pendingVirtualConfirm = false; confirmRealSwitch(toReal = false) }
        if (pendingRealConfirm) { pendingRealConfirm = false; confirmRealSwitch(toReal = true) }
        if (pendingScanRequest) {
            pendingScanRequest = false
            startScanFlow()
        } else {
            maybeShowScanResults()
        }
    }

    private var resumeDialogShown = false

    /**
     * The previous patrol died without a clean stop (thermal kill, crash). The game is still
     * parked at the checkpoint position and the stale providers are deliberately left in place,
     * so whatever the user picks here can start from that exact spot with nothing jumping.
     * Except after a kill on 真實位置 ([ResumeCopy]): the providers were already gone, so the game sees
     * the real GPS, both ways of resuming are the jump and 放棄 is the one without.
     */
    private fun maybeOfferResume() {
        if (resumeDialogShown || PatrolService.isRunning) return
        val cp = PatrolCheckpoint.resumable(this) ?: return
        resumeDialogShown = true
        val real = ResumeCopy.useRealModeText(prefs.suspendedAtMs, cp.savedAtMs)
        val paused = ResumeCopy.usePausedText(cp.phase, real)   // a PAUSED checkpoint comes back paused, so the text must not say it walks on
        val wpName = store.routeList().firstOrNull { it.id == cp.routeId }?.name ?: store.activeRouteName()
        val msg = getString(
            if (real) R.string.dlg_resume_msg_real else if (paused) R.string.dlg_resume_msg_paused else R.string.dlg_resume_msg,
            ageText(cp.ageMs),
            wpName,
            distanceText(cp.distanceWalkedM),
            distanceText(GeoMath.distanceM(cp.position, cp.home)),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_resume_title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(R.string.action_resume_patrol) { _, _ ->
                lifecycleScope.launch {
                    if (preflight(resuming = true)) PatrolService.resumeFromCheckpoint(this@MainActivity, thenReturnHome = false)
                    else resumeDialogShown = false
                }
            }
            .setNeutralButton(R.string.action_resume_go_home) { _, _ ->
                lifecycleScope.launch {
                    if (preflight(resuming = true)) PatrolService.resumeFromCheckpoint(this@MainActivity, thenReturnHome = true)
                    else resumeDialogShown = false
                }
            }
            .setNegativeButton(R.string.action_discard_checkpoint) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dlg_discard_title)
                    .setMessage(if (real) R.string.dlg_discard_msg_real else R.string.dlg_discard_msg)
                    .setPositiveButton(R.string.action_discard_checkpoint) { _, _ ->
                        PatrolCheckpoint.clear(this)
                        prefs.suspendedAtMs = 0L
                        // Now the stale providers really are stale: hand the game back to real GPS.
                        mock.stop()
                        // The game moves from the parked avatar to the real GPS: a jump, unless it was on the real GPS already.
                        if (!real) LocationJump.of(System.currentTimeMillis(), cp.position, cp.home)?.let { prefs.lastJump = it }
                        toast(getString(R.string.toast_checkpoint_discarded))
                    }
                    .setNegativeButton(R.string.action_cancel) { _, _ -> resumeDialogShown = false }
                    .show()
            }
            .show()
    }

    private fun ageText(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes < 60) getString(R.string.fmt_minutes_ago, minutes)
        else getString(R.string.fmt_hours_ago, minutes / 60)
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
        // The overlay permission screen has no result; we just re-check when the user comes back.
        overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Permissions.canDrawOverlays(this)) {
                prefs.overlayEnabled = true
                showOverlay()
            } else {
                toast(getString(R.string.toast_overlay_denied))
            }
        }
        // MediaProjection consent for the bird's-eye scan. The token in `data` is single-use and
        // short-lived, so the capture service is started right here with it.
        scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                onProjectionGranted(result.resultCode, data)
            } else {
                toast(getString(R.string.toast_scan_denied))
            }
        }
    }

    private fun applyInsets() {
        val margin = resources.getDimensionPixelSize(R.dimen.space_s)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            for (card in listOf(binding.bottomCard, binding.pickCard)) {
                (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.bottomMargin = bars.bottom + margin
                    lp.leftMargin = bars.left + margin
                    lp.rightMargin = bars.right + margin
                    card.layoutParams = lp
                }
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
        // A marker tap must not open start / teleport while the crosshair pick is up; the waypoint list
        // (a deliberate 立刻前往 from its long-press) goes straight to onWaypointTapped and stays allowed.
        overlays = MapOverlays(this, binding.map, MapEventsOverlay(this)) { if (onCenterPicked == null) onWaypointTapped(it) }
        rebuildOverlays()
        centerInitially()
        binding.map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { updatePickCoords(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { updatePickCoords(); return false }
        })
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { startPatrol(0) }
        binding.btnPause.setOnClickListener { PatrolService.pause(this) }
        binding.btnResume.setOnClickListener { PatrolService.resume(this) }
        binding.btnHome.setOnClickListener { pickReturnDestination() }
        binding.btnStop.setOnClickListener { confirmStop() }
        binding.btnTravel.setOnClickListener { showTravelDialog() }
        binding.btnJoystick.setOnClickListener { toggleJoystick() }
        binding.btnRealSwitch.setOnClickListener { confirmRealSwitch(toReal = PatrolService.state.value.phase != PatrolPhase.SUSPENDED) }
        locationStateColors = binding.tvLocationState.textColors
        binding.btnPickOk.setOnClickListener { confirmCenterPick() }
        binding.btnPickCancel.setOnClickListener { endCenterPick() }
    }

    /** A plain stop leaves the game at the fake position (looks like a teleport); always asked, suggesting 回家 first (PatrolDialogs). */
    private fun confirmStop() {
        PatrolDialogs.confirmStop(this, PatrolService.state.value.phase) { pickReturnDestination() }?.show()
            ?: PatrolService.stop(this)   // nothing running: the old plain stop (a no-op for the service)
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
                launch { FlowerScanner.state.collect { renderScan(it) } }
                launch { PatrolService.travelOverride.collect { renderLiveControls() } }
                launch { PatrolService.joystick.map { it.enabled }.distinctUntilChanged().collect { renderLiveControls() } }
            }
        }
    }

    // ------------------------------------------------------------------ live controls (移動方式 / 搖桿)

    private fun renderLiveControls() {
        val mode = PatrolService.travelOverride.value
        binding.btnTravel.text = travelLabel(mode, short = true)
        binding.btnTravel.setIconResource(if (mode == null) R.drawable.ic_walk else R.drawable.ic_car)
        binding.btnJoystick.isChecked = PatrolService.joystick.value.enabled
    }

    private fun travelLabel(mode: TravelMode?, short: Boolean = false): String {
        if (mode == null) {
            return if (short) getString(R.string.travel_short_walk)
            else getString(R.string.travel_walk_configured, "%.0f".format(prefs.config().speedMps * 3.6))
        }
        val kmh = prefs.config().speedTextOf(mode)
        return if (short) "${mode.label} $kmh km/h"
        else getString(if (mode.countsSteps) R.string.travel_item else R.string.travel_item_no_steps, mode.label, kmh)
    }

    /** 移動方式: pick the vehicle for the next leg. Applies immediately, before or during a patrol. */
    private fun showTravelDialog() {
        val choices = PatrolService.OVERRIDE_CHOICES
        val current = PatrolService.travelOverride.value
        // An AlertDialog shows either a message or a list, never both, so build the two by hand.
        val pad = resources.getDimensionPixelSize(R.dimen.space_xl)
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        column.addView(android.widget.TextView(this).apply {
            text = getString(R.string.dlg_travel_msg)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, 0, 0, pad / 2)
        })
        val group = android.widget.RadioGroup(this)
        choices.forEachIndexed { i, mode ->
            group.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = travelLabel(mode)
                tag = i
                isChecked = mode == current
                minHeight = resources.getDimensionPixelSize(R.dimen.overlay_handle)
            })
        }
        column.addView(group)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_travel_title)
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        group.setOnCheckedChangeListener { g, checkedId ->
            val index = g.findViewById<View>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            PatrolService.setTravelOverride(choices[index])
            toast(getString(R.string.snack_travel_changed, travelLabel(choices[index], short = true)))
            dialog.dismiss()
        }
    }

    /**
     * 搖桿 lives on the floating bar (it has to sit on top of the game), so the toggle here makes
     * sure the bar is up and then flips the pad. The service takes the walk over the moment a
     * patrol is running.
     */
    private fun toggleJoystick() {
        when {
            PatrolService.joystick.value.enabled -> {
                PatrolService.setJoystickEnabled(false)
                toast(getString(R.string.toast_joystick_off))
            }
            !Permissions.canDrawOverlays(this) -> {
                toast(getString(R.string.toast_joystick_need_overlay))
                toggleOverlay()
            }
            else -> {
                OverlayService.showJoystick(this)
                toast(getString(if (PatrolService.isRunning) R.string.toast_joystick_on else R.string.toast_joystick_need_patrol))
            }
        }
        // The checkable button flipped itself on the tap; the flow is the truth.
        renderLiveControls()
    }

    /**
     * 切到真實位置？／回到虛擬位置？ (T18), built by [PatrolDialogs] like the floating bar's. Also what the
     * notification's 回到虛擬位置… opens, possibly long after: the dialog checks the phase again before sending.
     */
    private fun confirmRealSwitch(toReal: Boolean) {
        PatrolDialogs.confirmRealSwitch(this, toReal, PatrolService.state.value) { toast(it) }?.show()
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
        if (onCenterPicked != null) return false   // the crosshair pick is up: panning must not open the add-flower editor
        if (p == null) return false
        WaypointDialogs.showEditor(this, null, p.latitude, p.longitude, store.load().size + 1) {
            store.add(it)
        }
        return true
    }

    private fun onWaypointTapped(index: Int) {
        if (GoModesDemo.shouldShowIntro(prefs.goModesSeen)) {
            WaypointDialogs.showGoModesIntro(this, prefs) { openMarkerActions(index) }
        } else {
            openMarkerActions(index)
        }
    }

    private fun openMarkerActions(index: Int) {
        WaypointDialogs.showMarkerActions(this, store, index, running = PatrolService.isRunning,
            onStartHere = { startHereOrGoTo(it) }, onTeleport = { startHereOrGoTo(it, teleport = true) },
            onStayThere = { goThereAndStay(it) })
    }

    /**
     * 前往後停在這裡. A long way on foot first asks how to get there (TripChoices: walking with its steps, or 其他／汽車／
     * 飛機 without); picking a vehicle makes it the one in force for the trip, and arriving drops it as any vehicle arrival
     * does (PatrolService.onArrived). Measured from the patrol's position, or before a start from the last real fix (the
     * start takes a fresh one; this is only the estimate).
     */
    private fun goThereAndStay(index: Int) {
        val wp = store.load().getOrNull(index) ?: return
        val state = PatrolService.state.value
        val from = if (PatrolService.isRunning) state.position else currentHome
        val distance = from?.let { GeoMath.distanceM(it, wp.latLng) }
        if (!TripChoices.shouldAsk(distance, prefs.stayAskKm, PatrolService.travelOverride.value)) {
            startHereOrGoTo(index, stay = true)
            return
        }
        val cfg = prefs.config()
        val choices = TripChoices.build(distance!!, cfg, cfg.injectSteps)
        MaterialAlertDialogBuilder(this)
            .setTitle(TripChoices.title(wp.name, distance))
            .setItems(choices.map { TripChoices.label(it, cfg.injectSteps) }.toTypedArray()) { _, which ->
                // Before a start the pick is the whole story (walking = no vehicle left over from a start that was
                // called off); while running, walking keeps whatever on-foot pace is in force.
                val mode = choices[which].mode
                if (mode != null || !PatrolService.isRunning) PatrolService.setTravelOverride(mode)
                startHereOrGoTo(index, stay = true)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Idle: start the patrol at that flower. Running: make it the next target (立刻前往).
     * [teleport] is the user's explicit per-use choice to jump there instead of travelling (PLAN K).
     * [stay] = 前往後停在這裡: go there (on foot or by the vehicle in force) and pause on arrival.
     */
    private fun startHereOrGoTo(index: Int, teleport: Boolean = false, stay: Boolean = false) {
        if (PatrolService.isRunning) {
            PatrolService.goTo(this, index, teleport, stay)
            // On 真實位置 the service refuses and says why: no 前往／瞬移 toast to contradict it.
            if (PatrolService.state.value.phase != PatrolPhase.SUSPENDED) {
                val toastRes = when { teleport -> R.string.toast_teleport_now; stay -> R.string.toast_go_and_stay; else -> R.string.toast_go_now }
                store.load().getOrNull(index)?.let { toast(getString(toastRes, it.name)) }
            }
        } else {
            startPatrol(index, teleport, stay)
        }
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
        syncOverlay(state.phase)
        continueScanAfterStart(state.phase)
    }

    // ------------------------------------------------------------------ bird's-eye scan

    /**
     * Menu entry 「掃描俯瞰模式大花」. The scan needs a patrol in progress (its walk is the ruler
     * that calibrates the map scale) and a MediaProjection consent, which only an Activity can ask
     * for — so this is the one place the whole flow starts, and the overlay button routes here.
     */
    private fun startScanFlow() {
        val scan = FlowerScanner.state.value
        if (FlowerScanner.isRunning) {
            val b = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_scan_running_title)
                .setMessage(getString(R.string.dlg_scan_running_msg, scanStatusLine(scan)))
                .setNegativeButton(R.string.action_scan_stop) { _, _ ->
                    FlowerScanner.stop(this)
                    toast(getString(R.string.toast_scan_stopped))
                }
                .setNeutralButton(R.string.action_cancel, null)
            if (FlowerScanner.found.isNotEmpty()) {
                b.setPositiveButton(R.string.action_scan_view_results) { _, _ -> showScanResults() }
            }
            b.show()
            return
        }
        if (scan is FlowerScanner.ScanState.Done && scan.found.isNotEmpty()) {
            showScanResults()
            return
        }
        val phase = PatrolService.state.value.phase
        if (phase == PatrolPhase.IDLE || phase == PatrolPhase.STOPPING) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_scan_need_patrol_title)
                .setMessage(R.string.dlg_scan_need_patrol_msg)
                .setPositiveButton(R.string.action_scan_start_patrol) { _, _ ->
                    scanAfterPatrolStart = true
                    startPatrol(0)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        showScanExplanation()
    }

    /** The 「先開始巡邏」 branch: once the walk is under way, pick the flow up at the explanation. */
    private fun continueScanAfterStart(phase: PatrolPhase) {
        if (!scanAfterPatrolStart) return
        when (phase) {
            PatrolPhase.WALKING, PatrolPhase.DWELLING, PatrolPhase.PAUSED -> {
                scanAfterPatrolStart = false
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) showScanExplanation()
            }
            PatrolPhase.IDLE -> scanAfterPatrolStart = false   // the start failed
            else -> Unit
        }
    }

    private fun showScanExplanation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_scan_title)
            .setMessage(R.string.dlg_scan_msg)
            .setPositiveButton(R.string.action_scan_start) { _, _ -> requestProjection() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun requestProjection() {
        val pm = getSystemService(MediaProjectionManager::class.java)
        val intent = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Whole display only: a "single app" capture would show us the game and nothing
                // else, which is fine, but the option confuses the flow and defaults differently
                // across OEM builds.
                pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                pm.createScreenCaptureIntent()
            }
        } catch (t: Throwable) {
            android.util.Log.w(PatrolService.TAG, "createScreenCaptureIntent failed", t)
            toast(getString(R.string.toast_scan_denied))
            return
        }
        runCatching { scanLauncher.launch(intent) }
            .onFailure {
                android.util.Log.w(PatrolService.TAG, "projection consent launch failed", it)
                toast(getString(R.string.toast_scan_denied))
            }
    }

    private fun onProjectionGranted(resultCode: Int, data: Intent) {
        ScreenCaptureService.start(this, resultCode, data)
        // The service goes foreground and creates its virtual display asynchronously.
        lifecycleScope.launch {
            val ok = withTimeoutOrNull(SCAN_START_TIMEOUT_MS) { ScreenCaptureService.isRunning.first { it } } != null
            if (!ok) {
                toast(ScreenCaptureService.lastError ?: getString(R.string.toast_scan_denied))
                return@launch
            }
            FlowerScanner.start(this@MainActivity)
            toast(getString(R.string.toast_scan_started))
            offerToLaunchGame()
        }
    }

    /**
     * Order matters: Pikmin Bloom blanks its own surface (black in every capture) when it notices
     * a capture display appearing while it is running, but not when it is launched after the
     * projection already exists. So the game is opened from here, after the capture has started.
     */
    private fun offerToLaunchGame() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_scan_launch_title)
            .setMessage(R.string.dlg_scan_launch_msg)
            .setNegativeButton(R.string.action_scan_launch_later, null)
        if (Permissions.isInstalled(this, PIKMIN_PACKAGE)) {
            b.setPositiveButton(R.string.action_scan_launch_game) { _, _ -> Permissions.openApp(this, PIKMIN_PACKAGE) }
        }
        b.show()
    }

    private fun renderScan(scan: FlowerScanner.ScanState) {
        val found = scan.foundFlowers
        overlays.updateScanned(found.map { it.waypoint.latLng }, found.map { it.waypoint.name })
        if (scan is FlowerScanner.ScanState.WaitingForBirdsEye && scan.blank) maybeExplainBlankCapture()
    }

    private var blankDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * The capture is black although the screen looks fine: the game blanked itself when the
     * projection appeared. One truncated line on the floating bar cannot explain that, so say it
     * properly here, with the relaunch button that fixes it.
     */
    private fun maybeExplainBlankCapture() {
        if (blankDialog?.isShowing == true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_scan_blank_title)
            .setMessage(R.string.dlg_scan_blank_msg)
            .setNegativeButton(R.string.action_scan_launch_later, null)
            .setOnDismissListener { blankDialog = null }
        if (Permissions.isInstalled(this, PIKMIN_PACKAGE)) {
            b.setPositiveButton(R.string.action_scan_launch_game) { _, _ -> Permissions.openApp(this, PIKMIN_PACKAGE) }
        }
        blankDialog = b.show()
    }

    /** Results dialog, shown on resume whenever the scan has found more than the user has seen. */
    private fun maybeShowScanResults() {
        val found = FlowerScanner.found
        if (found.isEmpty() || found.size <= FlowerScanner.reviewedCount) return
        if (scanResultsDialog?.isShowing == true) return
        showScanResults()
    }

    private fun showScanResults() {
        val found = FlowerScanner.found
        if (found.isEmpty()) {
            toast(getString(R.string.toast_scan_no_results))
            return
        }
        FlowerScanner.reviewedCount = found.size
        val labels = found.map { f ->
            getString(
                R.string.scan_result_item,
                f.waypoint.name,
                distanceText(f.rangeM),
                getString(if (f.stemFound) R.string.scan_confidence_high else R.string.scan_confidence_low),
            )
        }.toTypedArray()
        val checked = BooleanArray(found.size) { true }
        scanResultsDialog?.dismiss()
        scanResultsDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dlg_scan_results_title, found.size))
            .setMultiChoiceItems(labels, checked) { _, index, isChecked -> checked[index] = isChecked }
            .setPositiveButton(R.string.action_scan_add_current) { _, _ -> addScanned(found, checked, newRoute = false) }
            .setNeutralButton(R.string.action_scan_new_route) { _, _ -> addScanned(found, checked, newRoute = true) }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener { scanResultsDialog = null }
            .show()
    }

    private fun addScanned(found: List<FlowerScanPlan.PlannedFlower>, checked: BooleanArray, newRoute: Boolean) {
        val selected = found.filterIndexed { i, _ -> checked.getOrElse(i) { false } }.map { it.waypoint }
        if (selected.isEmpty()) {
            toast(getString(R.string.toast_scan_nothing_selected))
            return
        }
        if (newRoute) store.createRoute(getString(R.string.scan_route_name))
        val existing = store.load()
        val added = ArrayList<Waypoint>()
        for (wp in selected) {
            val dup = (existing + added).any { GeoMath.distanceM(it.latLng, wp.latLng) <= SCAN_DEDUPE_M }
            if (!dup) {
                added += wp.copy(id = UUID.randomUUID().toString())
            }
        }
        store.save(existing + added)
        toast(getString(R.string.toast_scan_added, added.size))
        if (!FlowerScanner.isRunning) FlowerScanner.discardResults()
    }

    private fun scanStatusLine(scan: FlowerScanner.ScanState): String = when (scan) {
        FlowerScanner.ScanState.Idle -> getString(R.string.ovl_stopped)
        FlowerScanner.ScanState.NeedProjection -> getString(R.string.scan_status_need_projection)
        is FlowerScanner.ScanState.WaitingForBirdsEye -> scan.reason
        is FlowerScanner.ScanState.Calibrating -> getString(R.string.scan_status_calibrating, scan.metresSoFar.toInt())
        is FlowerScanner.ScanState.Scanning -> getString(R.string.scan_status_scanning, scan.found.size)
        is FlowerScanner.ScanState.Done -> getString(R.string.scan_status_done, scan.found.size)
        is FlowerScanner.ScanState.Error -> getString(R.string.scan_status_error, scan.message)
    }

    /**
     * Starts the floating control bar when a patrol begins and takes it down when it ends. The
     * service itself never touches the UI, so this lives here; when this activity is not visible the
     * overlay hides itself shortly after the phase goes back to IDLE.
     */
    private fun syncOverlay(phase: PatrolPhase) {
        val previous = overlayPhase
        overlayPhase = phase
        if (previous == phase) return
        when {
            phase != PatrolPhase.IDLE ->
                if (prefs.overlayEnabled && Permissions.canDrawOverlays(this)) OverlayService.start(this)

            previous != null && !prefs.overlayPinned -> OverlayService.stop(this)
        }
    }

    /** Menu toggle: show the bar right now (pinned, so it stays after the patrol) or hide it. */
    private fun toggleOverlay() {
        if (OverlayService.isRunning) {
            prefs.overlayPinned = false
            OverlayService.stop(this)
            toast(getString(R.string.toast_overlay_hidden))
            return
        }
        if (!Permissions.canDrawOverlays(this)) {
            lifecycleScope.launch {
                val go = ask(
                    R.string.dlg_overlay_permission_title, R.string.dlg_overlay_permission_msg,
                    R.string.action_open_overlay_settings,
                )
                if (go) {
                    runCatching { overlayLauncher.launch(Permissions.overlayPermissionIntent(this@MainActivity)) }
                        .onFailure { Permissions.openOverlaySettings(this@MainActivity) }
                }
            }
            return
        }
        showOverlay()
    }

    private fun showOverlay() {
        OverlayService.start(this, pinned = true)
        toast(getString(R.string.toast_overlay_shown))
    }

    private fun renderStatus(state: PatrolState) {
        binding.tvPhase.text = phaseText(state.phase)

        val lines = ArrayList<String>(6)
        val name = state.currentWaypointName
        if (state.phase == PatrolPhase.IDLE || name == null) {
            lines += getString(R.string.status_no_target)
            lines += getString(R.string.status_waypoint_count, store.load().size)
            val h = currentHome
            lines += if (h == null) getString(R.string.status_home_none) else getString(R.string.status_home_set, h.toString())
        } else {
            lines += getString(R.string.status_target, name, distanceText(state.distanceToTargetM))
            state.stayAtName?.let { lines += getString(R.string.status_stay_at, it) }
            if (state.homeIsCustom) lines += getString(R.string.status_home_custom)
        }
        lines += getString(R.string.status_walked, distanceText(state.distanceWalkedM))
        lines += getString(R.string.status_session_steps, state.sessionSteps)
        lines += getString(R.string.status_today_steps, state.stepsWrittenToday)
        lines += getString(R.string.status_speed, "%.1f".format(state.speedMps * 3.6))
        LocationJump.readout(prefs.lastJump, System.currentTimeMillis())?.let { lines += it }
        binding.tvDetails.text = lines.joinToString("\n")

        val error = state.lastError
        binding.tvError.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!error.isNullOrBlank()) binding.tvError.text = getString(R.string.status_error, error)
    }

    private fun renderButtons(phase: PatrolPhase) {
        val moving = phase == PatrolPhase.WALKING || phase == PatrolPhase.DWELLING || phase == PatrolPhase.MANUAL
        show(binding.btnStart, phase == PatrolPhase.IDLE)
        show(binding.btnPause, moving)
        show(binding.btnResume, phase == PatrolPhase.PAUSED || phase == PatrolPhase.PARKED)
        binding.btnResume.setText(if (phase == PatrolPhase.PARKED) R.string.btn_resume_lap else R.string.btn_resume)
        // 回家 stays available while returning so the destination can be changed on the way.
        show(binding.btnHome, moving || phase == PatrolPhase.PAUSED || phase == PatrolPhase.PARKED || phase == PatrolPhase.RETURNING_HOME)
        // 停止 stays available while STARTING so a slow GPS fix can be aborted.
        show(binding.btnStop, phase != PatrolPhase.IDLE && phase != PatrolPhase.STOPPING)
        // 定位 (T18): the state in words, apart from the switch, which only asks (confirmRealSwitch).
        val real = phase == PatrolPhase.SUSPENDED
        // Only where it is true and the switch applies: during 啟動中／停止中 the mock is off and the phase line says so.
        show(binding.realRow, real || RealMode.canEnter(phase))
        binding.tvLocationState.text = RealModeCopy.stateLine(real)
        binding.tvLocationState.setTypeface(null, if (real) Typeface.BOLD else Typeface.NORMAL)
        binding.tvLocationState.setTextColor(
            if (real) ColorStateList.valueOf(ContextCompat.getColor(this, R.color.overlay_phase_real)) else locationStateColors,
        )
        binding.btnRealSwitch.text = RealModeCopy.switchLabel(real)
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
            PatrolPhase.PARKED -> R.string.phase_parked
            PatrolPhase.MANUAL -> R.string.phase_manual
            PatrolPhase.SUSPENDED -> R.string.phase_suspended
        }
    )

    private fun onEvent(event: PatrolEvent) {
        when (event) {
            is PatrolEvent.Error -> snack(event.message, Snackbar.LENGTH_LONG)
            is PatrolEvent.ArrivedAtWaypoint ->
                snack(getString(R.string.snack_arrived, event.name), Snackbar.LENGTH_SHORT)
            is PatrolEvent.ReturnedHome ->
                snack(getString(R.string.snack_returned_home), Snackbar.LENGTH_LONG)
            is PatrolEvent.Resumed ->
                snack(getString(R.string.snack_resumed, ageText(event.checkpointAgeMs)), Snackbar.LENGTH_LONG)
            is PatrolEvent.ParkedAtHome -> snack(getString(R.string.snack_parked), Snackbar.LENGTH_LONG)
            is PatrolEvent.TravelModeChanged ->
                if (event.automatic) snack(getString(R.string.snack_travel_auto_walk), Snackbar.LENGTH_SHORT)
            is PatrolEvent.ConfigChanged ->
                snack(getString(R.string.snack_speed_changed, "%.1f".format(event.speedKmh)), Snackbar.LENGTH_SHORT)
            is PatrolEvent.Replanned -> snack(getString(R.string.snack_replanned), Snackbar.LENGTH_SHORT)
            is PatrolEvent.Nectar -> snack(getString(if (event.ok) R.string.snack_nectar_ok else R.string.snack_nectar_failed, event.flowerName, event.detail), Snackbar.LENGTH_LONG)
            is PatrolEvent.SwitchedToReal -> snack(getString(if (event.reason == PatrolEvent.REASON_LEAK) R.string.snack_real_on_leak else R.string.snack_real_on), Snackbar.LENGTH_LONG)
            is PatrolEvent.SwitchedToVirtual -> snack(getString(R.string.snack_virtual_back), Snackbar.LENGTH_SHORT)
            is PatrolEvent.StayedAt -> snack(getString(R.string.snack_stayed, event.name), Snackbar.LENGTH_LONG)
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
        R.id.action_overlay -> { toggleOverlay(); true }
        R.id.action_scan_flowers -> { startScanFlow(); true }
        R.id.action_setup -> { startActivity(Intent(this, SetupActivity::class.java)); true }
        R.id.action_guide -> { startTour(); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_home -> { showHomeDialog(); true }
        R.id.action_import -> { importLauncher.launch(arrayOf(MIME_ANY)); true }
        R.id.action_export_json -> { exportJsonLauncher.launch(getString(R.string.export_json_filename)); true }
        R.id.action_export_gpx -> { exportGpxLauncher.launch(getString(R.string.export_gpx_filename)); true }
        R.id.action_feedback -> { FeedbackDialog.show(this); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showWaypointList() {
        WaypointDialogs.showList(
            activity = this,
            store = store,
            prefs = prefs,
            onFocus = { center(LatLng(it.lat, it.lon)) },
            onStartHere = { onWaypointTapped(it) },
            onAddRequested = { addWaypointAtMapCenter() },
        )
    }

    private fun addWaypointAtMapCenter() {
        val center = binding.map.mapCenter
        WaypointDialogs.showEditor(
            this, null, center.latitude, center.longitude, store.load().size + 1,
        ) { store.add(it) }
    }

    // ------------------------------------------------------------------ centre pick (準心)

    /**
     * A crosshair on the map centre plus its live coordinates; the user pans until it sits on the
     * right spot and taps 確定 ("設置家的時候應該要有一個準心", 2026-10-03).
     */
    private fun startCenterPick(@StringRes title: Int, onPicked: (LatLng) -> Unit) {
        onCenterPicked = onPicked
        binding.tvPickTitle.setText(title)
        show(binding.crosshair, true)
        show(binding.pickCard, true)
        show(binding.bottomCard, false)
        pickBack.isEnabled = true
        updatePickCoords()
    }

    private fun mapCenterLatLng(): LatLng = binding.map.mapCenter.let { CenterPick.toLatLng(it.latitude, it.longitude) }

    private fun updatePickCoords() {
        if (onCenterPicked == null) return
        binding.tvPickCoords.text = CenterPick.coordText(mapCenterLatLng())
    }

    private fun confirmCenterPick() {
        val callback = onCenterPicked ?: return
        val picked = mapCenterLatLng()
        endCenterPick()
        callback(picked)
    }

    private fun endCenterPick() {
        onCenterPicked = null
        pickBack.isEnabled = false
        show(binding.crosshair, false)
        show(binding.pickCard, false)
        show(binding.bottomCard, true)
    }

    // ------------------------------------------------------------------ saved homes (家的位置)

    /**
     * 家的位置: the list of places 回家 may go to besides the real position. A patrol never starts
     * from one (that would be a teleport); see [pickReturnDestination] for how they are used.
     */
    private fun showHomeDialog() {
        val homes = prefs.homes
        // AlertDialog shows either a message or a list, never both, so the explanation is a row.
        val items = listOf(getString(R.string.action_home_help)) +
            homes.map { "%s  %.4f,%.4f".format(it.name, it.lat, it.lon) } +
            getString(R.string.action_home_add_map_center) + getString(R.string.action_home_add_coords)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_home_title)
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> MaterialAlertDialogBuilder(this).setTitle(R.string.dlg_home_title)
                        .setMessage(R.string.dlg_home_msg).setPositiveButton(R.string.action_ok, null).show()
                    which <= homes.size -> showHomeActions(homes[which - 1])
                    which == homes.size + 1 -> startCenterPick(R.string.pick_home_title) { promptHomeName(it) }
                    else -> promptHomeCoords()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showHomeActions(h: Home) {
        MaterialAlertDialogBuilder(this)
            .setTitle(h.name)
            .setMessage(h.latLng.toString())
            .setPositiveButton(R.string.action_home_center_map) { _, _ -> center(h.latLng) }
            .setNegativeButton(R.string.action_home_delete) { _, _ ->
                prefs.homes = prefs.homes.filter { it != h }
                rebuildOverlays()
                toast(getString(R.string.toast_home_deleted, h.name))
            }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    private fun textInput(hint: String? = null, initial: String? = null): Pair<android.widget.EditText, android.view.View> {
        val input = android.widget.EditText(this).apply { hint?.let { setHint(it) }; setSingleLine(); initial?.let { setText(it) } }
        val pad = resources.getDimensionPixelSize(R.dimen.space_xl)
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        return input to box
    }

    private fun promptHomeCoords() {
        val (input, box) = textInput(hint = getString(R.string.dlg_home_coords_hint))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_home_enter_coords)
            .setView(box)
            .setPositiveButton(R.string.action_next) { _, _ ->
                val p = LatLng.parse(input.text?.toString())
                if (p == null) toast(getString(R.string.toast_home_coords_invalid)) else promptHomeName(p)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptHomeName(p: LatLng) {
        val (input, box) = textInput(initial = Home.defaultName(prefs.homes.size + 1))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_home_name_title)
            .setMessage(p.toString())
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty().ifEmpty { Home.defaultName(prefs.homes.size + 1) }
                prefs.homes = prefs.homes + Home(name, p.lat, p.lon)
                rebuildOverlays()
                center(p)
                toast(getString(R.string.toast_home_added, name, p.toString()))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 回家: every place (real position, then saved homes), each on foot or as a teleport - the user picks per use. */
    private fun pickReturnDestination() {
        PatrolDialogs.returnPicker(this, prefs.homes) { c -> PatrolService.returnHome(this, c.home?.latLng, c.teleport) }.show()
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
                        store.importGpx(text)
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

    private fun startPatrol(startAtIndex: Int, teleport: Boolean = false, stay: Boolean = false) {
        if (PatrolService.isRunning) return
        // An unhandled checkpoint means the game is still parked at a crash point; a fresh start
        // would teleport it home. Route the user back to the resume choice instead.
        if (PatrolCheckpoint.resumable(this) != null) {
            resumeDialogShown = false
            maybeOfferResume()
            return
        }
        lifecycleScope.launch {
            if (!preflight()) return@launch
            // Home is ALWAYS a fresh fix, never a stored one. It is where the patrol walks back to
            // and where the mock providers hand control back to the real GPS, so it has to be where
            // the user is now; people move between sessions, and reusing this morning's home would
            // drop the game avatar there and look like a teleport. Passing null makes PatrolService
            // take the fix itself. (Prefs.home is still written, but only to draw the map marker.)
            // Saved homes (Prefs.homes, 家的位置) are never start points, only 回家 destinations.
            PatrolService.start(this@MainActivity, null, startAtIndex, teleport, stay)
        }
    }

    /** Runs every check in order, offering the fix for each. Returns false when the patrol must not start. */
    /** [resuming]: picking a checkpoint up needs no route - a parked avatar has nothing to walk to. */
    private suspend fun preflight(resuming: Boolean = false): Boolean {
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

        // 3b. 「Google 定位準確度」 (PLAN O6): with it off, a gap in the mock has no real position to leak.
        if (LocationAccuracyAdvice.shouldWarn(mock.networkProviderEnabled(), resuming, prefs.accuracyAdviceMuted)) {
            when (askAccuracyAdvice()) {
                AccuracyChoice.OPEN_SETTINGS -> {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        .onFailure { toast(getString(R.string.toast_no_activity)) }
                    return false
                }
                AccuracyChoice.MUTE -> prefs.accuracyAdviceMuted = true
                AccuracyChoice.START_ANYWAY -> Unit
                AccuracyChoice.CANCEL -> return false
            }
        }

        // 4. At least one Big Flower (a fresh start only; a resume may be PARKED on an empty route).
        if (!resuming && store.load().isEmpty()) {
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

    private fun maybeShowDisclaimer() {
        if (prefs.disclaimerAccepted) {
            maybeStartTour()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_disclaimer_title)
            .setMessage(R.string.dlg_disclaimer_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.action_accept) { _, _ ->
                prefs.disclaimerAccepted = true
                maybeStartTour()
            }
            .setNegativeButton(R.string.action_exit) { _, _ -> finish() }
            .show()
    }

    /** The spotlight tour: once after the disclaimer is accepted (or on a later launch, if it was never finished). */
    private fun maybeStartTour() {
        if (CoachSteps.shouldShowGuide(prefs.disclaimerAccepted, prefs.guideSeen)) startTour()
    }

    /** Lays the tour over the main screen. Does nothing if it is already running. */
    private fun startTour() {
        if (tour != null) return
        val view = SpotlightView(this)
        addContentView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tour = view
        tourBack.isEnabled = true
        view.start(CoachSteps.steps, ::tourTarget) { endTour() }
    }

    /** Where each tour step points, in the content root's pixels (the space the tour is drawn in). Null (a centred card) when it is not on screen. */
    private fun tourTarget(key: TargetKey): Bounds? {
        val screen = when (key) {
            TargetKey.MENU -> overflowButton()?.let { screenBounds(it) }
            TargetKey.MAP -> screenBounds(binding.map)
            TargetKey.START -> screenBounds(binding.btnStart)
            TargetKey.HANDLE -> handleBounds()
        } ?: return null
        return toContent(screen)
    }

    /** [screen] converted to the content root's pixels. */
    private fun toContent(screen: Bounds): Bounds {
        val at = IntArray(2)
        findViewById<View>(android.R.id.content).getLocationOnScreen(at)
        return Bounds(screen.left - at[0], screen.top - at[1], screen.right - at[0], screen.bottom - at[1])
    }

    /** A view's rectangle in screen pixels, or null when it is not shown. */
    private fun screenBounds(view: View): Bounds? {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return null
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return Bounds(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    /**
     * The floating bar's handle in screen pixels: where the bar published it, else its saved window position while the
     * bar runs. Null when the bar is not running.
     */
    private fun handleBounds(): Bounds? =
        OverlayService.handleBounds ?: if (OverlayService.isRunning) savedHandleBounds() else null

    /** The handle's place from the saved window position (the defaults mirror [OverlayService]): a guess, used until the bar publishes. */
    private fun savedHandleBounds(): Bounds {
        val density = resources.displayMetrics.density
        val size = resources.getDimensionPixelSize(R.dimen.overlay_handle)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val x = (prefs.overlayX.takeIf { it != Prefs.OVERLAY_UNSET } ?: (8 * density).toInt())
            .coerceIn(0, maxOf(0, screenW - size))
        val y = (prefs.overlayY.takeIf { it != Prefs.OVERLAY_UNSET } ?: (screenH / 3))
            .coerceIn(0, maxOf(0, screenH - size))
        return Bounds(x, y, x + size, y + size)
    }

    /** The toolbar's ⋮ button: the last child of its action-menu view. */
    private fun overflowButton(): View? {
        val toolbar = binding.toolbar
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ActionMenuView && child.childCount > 0) return child.getChildAt(child.childCount - 1)
        }
        return null
    }

    /** However the tour ends (略過, Back, 完成), it counts as seen. */
    private fun endTour() {
        tour = null
        tourBack.isEnabled = false
        prefs.guideSeen = true
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

    private enum class AccuracyChoice { OPEN_SETTINGS, START_ANYWAY, MUTE, CANCEL }

    /** The 「Google 定位準確度」 advice: 去關掉 (opens Location settings, no start) / 照樣開始 / 不再提醒; back = no start. */
    private suspend fun askAccuracyAdvice(): AccuracyChoice = suspendCancellableCoroutine { cont ->
        fun answer(c: AccuracyChoice) { if (cont.isActive) cont.resume(c) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_accuracy_title)
            .setMessage(R.string.dlg_accuracy_msg)
            .setPositiveButton(R.string.action_accuracy_open) { _, _ -> answer(AccuracyChoice.OPEN_SETTINGS) }
            .setNegativeButton(R.string.action_accuracy_start_anyway) { _, _ -> answer(AccuracyChoice.START_ANYWAY) }
            .setNeutralButton(R.string.action_accuracy_mute) { _, _ -> answer(AccuracyChoice.MUTE) }
            .setOnDismissListener { answer(AccuracyChoice.CANCEL) }
            .create()
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

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun snack(text: String, duration: Int) {
        Snackbar.make(binding.root, text, duration).setAnchorView(binding.bottomCard).show()
    }

    companion object {
        /** How long the crash-report offer stays up. */
        private const val CRASH_OFFER_MS = 10_000
        private const val DEFAULT_ZOOM = 17.0
        private const val TRAIL_LIMIT = 2000
        private const val TRAIL_MIN_STEP_M = 1.0
        private const val REAL_FIX_TIMEOUT_MS = 15_000L
        private const val MIME_ANY = "*/*"
        private const val MIME_JSON = "application/json"
        private const val MIME_GPX = "application/gpx+xml"
        private const val GPX_MARKER = "<gpx"
        private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
        private const val SCAN_START_TIMEOUT_MS = 5_000L
        private const val SCAN_DEDUPE_M = 20.0
        private const val PIKMIN_PACKAGE = "com.nianticlabs.pikmin"

        /** Intent extra from the overlay's 掃描 button: run the scan flow on resume. */
        const val EXTRA_START_SCAN = "start_scan"

        /** Intent extra from the notification's 回到虛擬位置…: ask (never switch) in that direction on resume. */
        const val EXTRA_CONFIRM_SWITCH = "confirm_switch"
        const val CONFIRM_VIRTUAL = "virtual"
        const val CONFIRM_REAL = "real"

        /** Taipei 101, used only when nothing at all is known yet. */
        private val FALLBACK_CENTER = LatLng(25.0330, 121.5654)
    }
}
