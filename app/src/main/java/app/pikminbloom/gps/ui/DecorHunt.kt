package app.pikminbloom.gps.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Decor
import app.pikminbloom.gps.data.OverpassClient
import app.pikminbloom.gps.data.OverpassClient.DecorHit
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.TravelMode
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.databinding.DialogDecorConfirmBinding
import app.pikminbloom.gps.databinding.DialogDecorPickBinding
import app.pikminbloom.gps.databinding.DialogDecorResultsBinding
import app.pikminbloom.gps.databinding.ItemDecorHitBinding
import app.pikminbloom.gps.geo.LatLng
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import kotlin.math.min

/**
 * "Find decor" (找飾品): pick a Pikmin Bloom decor, look up real-world places of that category on
 * OpenStreetMap, judge which ones are pure points (純點), and turn the chosen place into a waypoint
 * on the active route or a dedicated route of its own.
 *
 * Flow: [show] -> decor picker -> radius -> Overpass search -> results (purity checked in place)
 * -> confirm (travel mode + wander settings) -> waypoint. The travel mode is persisted in [Prefs]
 * for the patrol to pick up; this object never starts `PatrolService`.
 *
 * Every Overpass call is behind an explicit tap: the picker filters locally, purity checks run for
 * five results at a time and only continue on 「檢查更多」.
 */
object DecorHunt {

    private const val TAG = "PikminGPS"

    private val RADII_KM = intArrayOf(1, 5, 20, 50)
    private const val DEFAULT_RADIUS_INDEX = 1
    private const val RESULT_LIMIT = 20

    /** Purity checks per 「檢查更多」 tap, and the most that may be in flight at once. */
    private const val PURITY_BATCH = 5
    private const val MAX_PURITY_IN_FLIGHT = 5

    private const val MIN_INPUT_RADIUS_M = 1.0
    private const val MAX_INPUT_RADIUS_M = 500.0

    fun show(activity: AppCompatActivity, prefs: Prefs, store: WaypointStore, center: LatLng?) {
        if (center == null) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.decor_hunt_title)
                .setMessage(R.string.decor_need_center)
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }
        showPicker(activity, prefs, store, center)
    }

    // ------------------------------------------------------------------ 1. decor picker

    private fun showPicker(activity: AppCompatActivity, prefs: Prefs, store: WaypointStore, center: LatLng) {
        val binding = DialogDecorPickBinding.inflate(LayoutInflater.from(activity))
        var shown: List<Decor> = Decor.entries
        var selected: Decor? = prefs.lastDecor

        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_list_item_single_choice,
            ArrayList(shown.map { it.label }),
        )
        binding.listDecor.adapter = adapter

        fun applySelection() {
            val idx = shown.indexOf(selected)
            if (idx >= 0) binding.listDecor.setItemChecked(idx, true)
        }

        fun refilter(query: String) {
            shown = DecorHuntFormat.filterDecor(query)
            binding.listDecor.clearChoices()
            adapter.clear()
            adapter.addAll(shown.map { it.label })
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            applySelection()
        }

        binding.listDecor.setOnItemClickListener { _, _, position, _ -> selected = shown.getOrNull(position) }
        binding.etFilter.doAfterTextChanged { refilter(it?.toString().orEmpty()) }
        refilter("")
        // Bring last time's pick into view so a repeat hunt is one tap.
        shown.indexOf(selected).takeIf { it >= 0 }?.let { idx ->
            binding.listDecor.post { binding.listDecor.setSelection((idx - 2).coerceAtLeast(0)) }
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.decor_pick_title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.decor_pick_next, null)   // handled below so it can refuse
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val decor = selected ?: run {
                    Toast.makeText(activity, R.string.decor_pick_none_selected, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.lastDecor = decor
                dialog.dismiss()
                askRadius(activity, prefs, store, center, decor)
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ 2. radius

    private fun askRadius(activity: AppCompatActivity, prefs: Prefs, store: WaypointStore, center: LatLng, decor: Decor) {
        val labels = RADII_KM.map { activity.getString(R.string.decor_radius_item, it) }.toTypedArray()
        var choice = DEFAULT_RADIUS_INDEX
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.decor_radius_title, decor.label))
            .setSingleChoiceItems(labels, choice) { _, which -> choice = which }
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.decor_radius_search) { _, _ ->
                search(activity, prefs, store, center, decor, RADII_KM[choice])
            }
            .show()
    }

    // ------------------------------------------------------------------ 3. search

    private fun search(
        activity: AppCompatActivity,
        prefs: Prefs,
        store: WaypointStore,
        center: LatLng,
        decor: Decor,
        radiusKm: Int,
    ) {
        var job: Job? = null
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.decor_hunt_title)
            .setMessage(activity.getString(R.string.decor_searching, decor.label))
            .setNegativeButton(R.string.action_cancel) { _, _ -> job?.cancel() }
            .setOnCancelListener { job?.cancel() }
            .show()

        Log.i(TAG, "decor hunt: ${decor.name} within $radiusKm km of $center")
        job = activity.lifecycleScope.launch {
            val result = attempt { OverpassClient.findDecor(center, decor, radiusKm * 1_000, RESULT_LIMIT) }
            progress.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch

            result.onFailure { t ->
                Log.w(TAG, "decor hunt: search failed", t)
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.decor_failed_title)
                    .setMessage(activity.getString(R.string.decor_failed_msg, t.message ?: "?"))
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
            }.onSuccess { hits ->
                Log.i(TAG, "decor hunt: ${hits.size} hits for ${decor.name}")
                if (hits.isEmpty()) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.decor_hunt_title)
                        .setMessage(activity.getString(R.string.decor_none, radiusKm, decor.label))
                        .setPositiveButton(R.string.action_ok, null)
                        .show()
                } else {
                    showResults(activity, prefs, store, decor, hits)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 4. results + purity

    private fun showResults(
        activity: AppCompatActivity,
        prefs: Prefs,
        store: WaypointStore,
        decor: Decor,
        hits: List<DecorHit>,
    ) {
        val binding = DialogDecorResultsBinding.inflate(LayoutInflater.from(activity))
        val current = hits.toMutableList()          // replaced in place as purity comes back
        val failed = BooleanArray(hits.size)
        val rows = ArrayList<ItemDecorHitBinding>(hits.size)
        val jobs = ArrayList<Job>()
        val gate = Semaphore(MAX_PURITY_IN_FLIGHT)
        var nextToCheck = 0
        lateinit var dialog: AlertDialog

        fun renderPurity(i: Int) {
            rows[i].tvPurity.text = when {
                failed[i] -> activity.getString(R.string.decor_purity_failed)
                current[i].competingDecor == null && i >= nextToCheck -> activity.getString(R.string.decor_purity_unchecked)
                else -> DecorHuntFormat.purityLabel(current[i])
            }
        }

        fun updateCheckMore() {
            val btn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL) ?: return
            btn.visibility = if (nextToCheck < current.size) View.VISIBLE else View.GONE
        }

        fun checkNextBatch() {
            val from = nextToCheck
            val to = min(from + PURITY_BATCH, current.size)
            nextToCheck = to
            for (i in from until to) {
                renderPurity(i)   // 「檢查中…」
                jobs += activity.lifecycleScope.launch {
                    val result = gate.withPermit { attempt { OverpassClient.checkPurity(current[i]) } }
                    if (!isActive || !dialog.isShowing) return@launch
                    result.onSuccess { current[i] = it }
                        .onFailure { t -> failed[i] = true; Log.w(TAG, "decor hunt: purity check failed for ${current[i].name}: ${t.message}") }
                    renderPurity(i)
                }
            }
            updateCheckMore()
        }

        current.forEachIndexed { i, hit ->
            val row = ItemDecorHitBinding.inflate(LayoutInflater.from(activity), binding.listContainer, false)
            row.tvName.text = hit.name
            row.tvDistance.text = DecorHuntFormat.formatDistance(hit.distanceM)
            row.root.setOnClickListener {
                dialog.dismiss()
                showConfirm(activity, prefs, store, current[i])
            }
            rows += row
            binding.listContainer.addView(row.root)
            renderPurity(i)
        }

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.decor_results_title, decor.label, hits.size))
            .setView(binding.root)
            .setNeutralButton(R.string.decor_results_check_more, null)   // handled below so it does not dismiss
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { checkNextBatch() }
            checkNextBatch()
        }
        dialog.setOnDismissListener { jobs.forEach { it.cancel() } }
        dialog.show()
    }

    // ------------------------------------------------------------------ 5. confirm -> waypoint

    private fun showConfirm(activity: AppCompatActivity, prefs: Prefs, store: WaypointStore, hit: DecorHit) {
        val binding = DialogDecorConfirmBinding.inflate(LayoutInflater.from(activity))
        val decor = hit.decor
        val modes = TravelMode.entries

        fun renderSummary(purityText: String) {
            binding.tvSummary.text = activity.getString(
                R.string.decor_confirm_summary,
                decor.label, hit.name, DecorHuntFormat.formatDistance(hit.distanceM), purityText,
            )
        }
        renderSummary(DecorHuntFormat.purityLabel(hit))

        binding.spTravelMode.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            modes.map { DecorHuntFormat.travelModeLabel(it) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spTravelMode.setSelection(modes.indexOf(TravelMode.suggestFor(hit.distanceM)))

        binding.etRadius.setText(fmt(prefs.tripWanderRadiusM))
        binding.etMinutes.setText(prefs.tripWanderMin.toString())
        binding.etRadius.doAfterTextChanged {
            val v = it?.toString()?.trim()?.toDoubleOrNull()
            binding.tilRadius.helperText = activity.getString(
                if (v != null && v > DecorHuntFormat.MAX_WAYPOINT_RADIUS_M) R.string.decor_confirm_radius_clamped
                else R.string.decor_confirm_radius_helper
            )
        }

        // A result the list had not checked yet (or failed to) is checked here, in place.
        var purityJob: Job? = null
        if (hit.competingDecor == null) {
            purityJob = activity.lifecycleScope.launch {
                val result = attempt { OverpassClient.checkPurity(hit) }
                if (!isActive) return@launch
                result.onSuccess { renderSummary(DecorHuntFormat.purityLabel(it)) }
                    .onFailure { renderSummary(activity.getString(R.string.decor_purity_failed)) }
            }
        }

        binding.tvNewRouteHint.text = activity.getString(R.string.decor_confirm_new_route_hint, DecorHuntFormat.routeName(decor))

        // Two bar buttons only: a third one stacks at large font sizes and the bar gets clipped,
        // so 「建立專用路線」 is a button in the body instead.
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.decor_confirm_title, hit.name))
            .setView(binding.root)
            .setPositiveButton(R.string.decor_confirm_add, null)        // handled below: validate first
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        /** Validated (radius, minutes) as typed, or null after marking the offending field. */
        fun readInputs(): Pair<Double, Int>? {
            binding.tilRadius.error = null
            binding.tilMinutes.error = null
            val radius = binding.etRadius.text?.toString()?.trim()?.toDoubleOrNull()
            val minutes = binding.etMinutes.text?.toString()?.trim()?.toIntOrNull()
            var ok = true
            if (radius == null || radius < MIN_INPUT_RADIUS_M || radius > MAX_INPUT_RADIUS_M) {
                binding.tilRadius.error = activity.getString(R.string.decor_confirm_err_radius); ok = false
            }
            if (minutes == null || minutes < 0 || minutes > DecorHuntFormat.MAX_WANDER_MIN) {
                binding.tilMinutes.error = activity.getString(R.string.decor_confirm_err_minutes); ok = false
            }
            return if (ok) radius!! to minutes!! else null
        }

        fun commit(radius: Double, minutes: Int): Waypoint {
            prefs.tripTravelMode = modes[binding.spTravelMode.selectedItemPosition.coerceIn(modes.indices)]
            prefs.tripWanderRadiusM = radius
            prefs.tripWanderMin = minutes
            return Waypoint(
                id = UUID.randomUUID().toString(),
                name = DecorHuntFormat.waypointName(decor, hit.name),
                lat = hit.position.lat,
                lon = hit.position.lon,
                radiusM = DecorHuntFormat.clampWaypointRadius(radius),
                dwellSec = DecorHuntFormat.clampWanderMinutes(minutes) * 60,
            )
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val (radius, minutes) = readInputs() ?: return@setOnClickListener
                val wp = commit(radius, minutes)
                store.add(wp)
                Log.i(TAG, "decor hunt: added ${wp.name} to route ${store.activeRouteName()} (${prefs.tripTravelMode})")
                Toast.makeText(
                    activity,
                    activity.getString(R.string.decor_added_to_list, wp.name, store.activeRouteName()),
                    Toast.LENGTH_LONG,
                ).show()
                dialog.dismiss()
            }
        }
        binding.btnNewRoute.setOnClickListener {
            val (radius, minutes) = readInputs() ?: return@setOnClickListener
            val wp = commit(radius, minutes)
            // The travel mode lives on the route, so PatrolService drives the leg to this place at
            // that speed and only walks (steps, planting) once inside the waypoint's circle.
            val id = store.createRoute(DecorHuntFormat.routeName(decor), travelMode = prefs.tripTravelMode)
            store.switchTo(id)
            store.save(listOf(wp))
            Log.i(TAG, "decor hunt: created route ${store.activeRouteName()} with ${wp.name} (${prefs.tripTravelMode})")
            Toast.makeText(
                activity,
                activity.getString(R.string.decor_route_created, store.activeRouteName()),
                Toast.LENGTH_LONG,
            ).show()
            dialog.dismiss()
        }
        dialog.setOnDismissListener { purityJob?.cancel() }
        dialog.show()
    }

    // ------------------------------------------------------------------ helpers

    /** runCatching that lets cancellation through, so a cancelled search is not reported as an error. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        Result.failure(t)
    }

    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
}
