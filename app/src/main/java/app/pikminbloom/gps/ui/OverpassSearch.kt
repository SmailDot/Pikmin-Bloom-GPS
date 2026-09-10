package app.pikminbloom.gps.ui

import android.app.Activity
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.OverpassClient
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.geo.GeoMath
import app.pikminbloom.gps.geo.LatLng
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * "Find candidate Big Flowers nearby": queries OpenStreetMap for the kinds of points of interest
 * Niantic tends to accept as Wayspots, lets the user pick which ones to keep, and appends them to
 * the waypoint list.
 *
 * These are candidates, not confirmed Big Flowers - Niantic publishes no such list. See
 * docs/PLAN.md appendix D2.
 */
object OverpassSearch {

    private val RADII = intArrayOf(500, 1000, 2000, 3000)

    fun show(activity: AppCompatActivity, prefs: Prefs, store: WaypointStore, center: LatLng?) {
        if (center == null) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.overpass_title)
                .setMessage(R.string.overpass_need_center)
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }
        // A Material dialog shows either a message or a list, not both, so explain first.
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overpass_title)
            .setMessage(R.string.overpass_explain)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                askRadius(activity, prefs, store, center)
            }
            .show()
    }

    private fun askRadius(activity: AppCompatActivity, prefs: Prefs, store: WaypointStore, center: LatLng) {
        val labels = RADII.map { activity.getString(R.string.overpass_radius_item, it) }.toTypedArray()
        var choice = 1
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overpass_radius_title)
            .setSingleChoiceItems(labels, choice) { _, which -> choice = which }
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.overpass_search) { _, _ ->
                search(activity, prefs, store, center, RADII[choice])
            }
            .show()
    }

    private fun search(
        activity: AppCompatActivity,
        prefs: Prefs,
        store: WaypointStore,
        center: LatLng,
        radiusM: Int,
    ) {
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overpass_title)
            .setMessage(R.string.overpass_searching)
            .setCancelable(false)
            .show()

        activity.lifecycleScope.launch {
            val result = runCatching {
                OverpassClient.findCandidates(
                    center = center,
                    radiusM = radiusM,
                    defaultRadiusM = prefs.defaultRadiusM,
                    defaultDwellSec = prefs.defaultDwellSec,
                )
            }
            progress.dismiss()
            if (activity.isFinishing || activity.isDestroyed) return@launch

            result.onFailure { t ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.overpass_failed_title)
                    .setMessage(activity.getString(R.string.overpass_failed_msg, t.message ?: "?"))
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
            }.onSuccess { found ->
                val fresh = dropAlreadyKnown(found, store.load())
                if (fresh.isEmpty()) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.overpass_title)
                        .setMessage(R.string.overpass_none)
                        .setPositiveButton(R.string.action_ok, null)
                        .show()
                } else {
                    pick(activity, store, fresh, center)
                }
            }
        }
    }

    /** Anything within 30 m of a waypoint the user already has is not a new place. */
    private fun dropAlreadyKnown(found: List<Waypoint>, existing: List<Waypoint>): List<Waypoint> =
        found.filterNot { c -> existing.any { GeoMath.distanceM(it.latLng, c.latLng) < 30.0 } }

    private fun pick(activity: Activity, store: WaypointStore, candidates: List<Waypoint>, center: LatLng) {
        val labels = candidates.map {
            activity.getString(R.string.overpass_item, it.name, GeoMath.distanceM(center, it.latLng))
        }.toTypedArray()
        val checked = BooleanArray(candidates.size) { true }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.overpass_pick_title, candidates.size))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNeutralButton(R.string.overpass_toggle_all, null)   // handled below so it does not dismiss
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.overpass_add) { _, _ ->
                val picked = candidates.filterIndexed { i, _ -> checked[i] }
                if (picked.isNotEmpty()) {
                    store.save(store.load() + picked)
                    android.widget.Toast.makeText(
                        activity,
                        activity.getString(R.string.overpass_added, picked.size),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val allOn = checked.all { it }
                val list: ListView = dialog.listView
                for (i in checked.indices) {
                    checked[i] = !allOn
                    list.setItemChecked(i, !allOn)
                }
                @Suppress("UNCHECKED_CAST")
                (list.adapter as? ArrayAdapter<String>)?.notifyDataSetChanged()
            }
        }
        dialog.show()
    }
}
