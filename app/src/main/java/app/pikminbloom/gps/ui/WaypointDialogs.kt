package app.pikminbloom.gps.ui

import android.app.Activity
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.pikminbloom.gps.R
import app.pikminbloom.gps.data.Prefs
import app.pikminbloom.gps.data.Waypoint
import app.pikminbloom.gps.data.WaypointStore
import app.pikminbloom.gps.databinding.DialogWaypointBinding
import app.pikminbloom.gps.databinding.ItemWaypointBinding
import app.pikminbloom.gps.databinding.SheetWaypointActionsBinding
import app.pikminbloom.gps.databinding.SheetWaypointListBinding
import app.pikminbloom.gps.geo.LatLng
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

/** Add / edit / reorder dialogs for Big Flower waypoints. Pure UI: everything persists via [WaypointStore]. */
object WaypointDialogs {

    /**
     * Shows the add (when [existing] is null) or edit dialog: coordinates and a name. An edit keeps
     * whatever else the waypoint carries (a decor trip's wander radius and time).
     * [position] is only used to build the default name of a new waypoint ("大花 N").
     */
    fun showEditor(
        activity: Activity,
        existing: Waypoint?,
        lat: Double,
        lon: Double,
        position: Int,
        onSave: (Waypoint) -> Unit,
    ) {
        val binding = DialogWaypointBinding.inflate(LayoutInflater.from(activity))
        // Editable: a long-press lands roughly, and a Google Maps paste ("35.6812, 139.7671" or
        // the maps URL) is how a flower somewhere else gets in at all (asked for 2026-09-14).
        binding.etCoords.setText("%.6f, %.6f".format(java.util.Locale.US, lat, lon))
        binding.etName.setText(existing?.name ?: activity.getString(R.string.default_waypoint_name, position))

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.dlg_add_waypoint_title else R.string.dlg_edit_waypoint_title)
            .setView(binding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                binding.tilCoords.error = null
                binding.tilName.error = null
                val name = binding.etName.text?.toString()?.trim().orEmpty()
                val at = LatLng.parse(binding.etCoords.text?.toString())
                if (at == null) binding.tilCoords.error = activity.getString(R.string.err_coords_invalid)
                if (name.isEmpty()) binding.tilName.error = activity.getString(R.string.err_name_required)
                if (at == null || name.isEmpty()) return@setOnClickListener
                onSave(
                    existing?.copy(name = name, lat = at.lat, lon = at.lon)
                        ?: Waypoint(id = UUID.randomUUID().toString(), name = name, lat = at.lat, lon = at.lon)
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Bottom sheet shown when a waypoint marker is tapped. [running] relabels 從這裡開始 as
     * 立刻前往這朵: mid-patrol the same action makes that flower the next target.
     */
    fun showMarkerActions(
        activity: Activity,
        store: WaypointStore,
        index: Int,
        running: Boolean = false,
        onStartHere: (Int) -> Unit,
        onTeleport: (Int) -> Unit,
        onStayThere: (Int) -> Unit,
    ) {
        val list = store.load()
        val wp = list.getOrNull(index) ?: return
        val binding = SheetWaypointActionsBinding.inflate(LayoutInflater.from(activity))
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(binding.root)

        binding.tvTitle.text = "${index + 1}. ${wp.name}"
        binding.tvSubtitle.text = summary(activity, wp)
        binding.actionMoveUp.isEnabled = index > 0
        binding.actionMoveDown.isEnabled = index < list.size - 1
        binding.actionMoveUp.alpha = if (index > 0) 1f else 0.4f
        binding.actionMoveDown.alpha = if (index < list.size - 1) 1f else 0.4f

        binding.actionEdit.setOnClickListener {
            sheet.dismiss()
            showEditor(activity, wp, wp.lat, wp.lon, index + 1) { store.update(it) }
        }
        binding.actionMoveUp.setOnClickListener { store.move(index, index - 1); sheet.dismiss() }
        binding.actionMoveDown.setOnClickListener { store.move(index, index + 1); sheet.dismiss() }
        binding.actionStartHere.text = if (running) withHint(activity, R.string.action_go_now, R.string.hint_go_now) else withHint(activity, R.string.action_start_here, R.string.hint_start_here)
        binding.actionStartHere.setOnClickListener { sheet.dismiss(); onStartHere(index) }
        binding.actionStayThere.text = if (running) withHint(activity, R.string.action_go_and_stay, R.string.hint_go_and_stay) else withHint(activity, R.string.action_start_and_stay, R.string.hint_start_and_stay)
        binding.actionStayThere.setOnClickListener { sheet.dismiss(); onStayThere(index) }
        binding.actionTeleport.text = if (running) withHint(activity, R.string.action_teleport_now, R.string.hint_teleport_now) else withHint(activity, R.string.action_teleport_here, R.string.hint_teleport_here)
        binding.actionTeleport.setOnClickListener { sheet.dismiss(); onTeleport(index) }
        binding.actionDelete.setOnClickListener {
            sheet.dismiss()
            confirmDelete(activity, wp) { store.remove(wp.id) }
        }
        sheet.show()
    }

    /** A menu label with its one-line hint under it; the hint is drawn smaller. */
    private fun withHint(activity: Activity, label: Int, hint: Int): CharSequence {
        val title = activity.getString(label)
        return SpannableString(title + "\n" + activity.getString(hint)).apply {
            setSpan(RelativeSizeSpan(0.85f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Explains the three flower actions the first time a flower is tapped, before its menu opens. [onDone] opens the
     * menu when the explanation is closed (知道了), and that is the only time it is marked seen.
     */
    fun showGoModesIntro(activity: Activity, prefs: Prefs, onDone: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_go_modes, null)
        view.findViewById<TextView>(R.id.goModesBody).setText(R.string.tour_modes_body)
        val demo = view.findViewById<GoModesDemoView>(R.id.goModesDemo)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tour_modes_title)
            .setView(view)
            .setPositiveButton(R.string.go_modes_ok, null)
            .setOnDismissListener {
                demo.stop()
                prefs.goModesSeen = true
                onDone()
            }
            .show()
        demo.start()
    }

    /** Bottom sheet listing every waypoint with reorder / edit / delete / start-here. */
    fun showList(
        activity: Activity,
        store: WaypointStore,
        prefs: Prefs,
        onFocus: (Waypoint) -> Unit,
        onStartHere: (Int) -> Unit,
        onAddRequested: () -> Unit,
    ) {
        val binding = SheetWaypointListBinding.inflate(LayoutInflater.from(activity))
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(binding.root)

        fun render() {
            val list = store.load()
            binding.tvEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.listContainer.removeAllViews()
            list.forEachIndexed { index, wp ->
                val row = ItemWaypointBinding.inflate(LayoutInflater.from(activity), binding.listContainer, false)
                row.tvIndex.text = (index + 1).toString()
                row.tvName.text = wp.name
                row.tvSummary.text = summary(activity, wp)
                row.btnUp.isEnabled = index > 0
                row.btnDown.isEnabled = index < list.size - 1
                row.root.setOnClickListener { sheet.dismiss(); onFocus(wp) }
                row.root.setOnLongClickListener { sheet.dismiss(); onStartHere(index); true }
                row.btnUp.setOnClickListener { store.move(index, index - 1); render() }
                row.btnDown.setOnClickListener { store.move(index, index + 1); render() }
                row.btnEdit.setOnClickListener {
                    showEditor(activity, wp, wp.lat, wp.lon, index + 1) { store.update(it); render() }
                }
                row.btnDelete.setOnClickListener {
                    confirmDelete(activity, wp) { store.remove(wp.id); render() }
                }
                binding.listContainer.addView(row.root)
            }
        }

        binding.btnRoute.text = store.activeRouteName().ifBlank { activity.getString(R.string.title_waypoint_list) }
        binding.btnRoute.setOnClickListener {
            sheet.dismiss()
            // Re-open this sheet once the user is done picking, so switching routes feels in-place.
            showRoutePicker(activity, store) {
                showList(activity, store, prefs, onFocus, onStartHere, onAddRequested)
            }
        }
        binding.btnAdd.setOnClickListener { sheet.dismiss(); onAddRequested() }
        binding.btnSearchNearby.setOnClickListener {
            sheet.dismiss()
            val host = activity as? androidx.appcompat.app.AppCompatActivity ?: return@setOnClickListener
            // Search around wherever we currently are: the simulated position while patrolling,
            // otherwise the saved home, the last known position, or the first waypoint.
            val center = app.pikminbloom.gps.service.PatrolService.state.value.position
                ?: prefs.home
                ?: prefs.lastPosition
                ?: store.load().firstOrNull()?.latLng
            OverpassSearch.show(host, store, center)
        }
        binding.btnDecorHunt.setOnClickListener {
            sheet.dismiss()
            val host = activity as? androidx.appcompat.app.AppCompatActivity ?: return@setOnClickListener
            val center = app.pikminbloom.gps.service.PatrolService.state.value.position
                ?: prefs.home
                ?: prefs.lastPosition
                ?: store.load().firstOrNull()?.latLng
            DecorHunt.show(host, prefs, store, center)
        }
        render()
        sheet.show()
    }

    /**
     * Route switcher: pick a saved patrol, or create / rename / duplicate / delete one.
     * [onDone] fires after any change (and after a plain dismissal) so the caller can refresh.
     */
    fun showRoutePicker(activity: Activity, store: WaypointStore, onDone: () -> Unit) {
        val routes = store.routeList()
        val activeId = store.activeRouteId.value
        val labels = routes.map { r ->
            activity.getString(R.string.route_item, r.name, r.waypoints.size)
        }.toTypedArray()
        val checkedIndex = routes.indexOfFirst { it.id == activeId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.route_picker_title)
            .setSingleChoiceItems(labels, checkedIndex) { d, which ->
                store.switchTo(routes[which].id)
                d.dismiss()
                onDone()
            }
            .setNeutralButton(R.string.route_manage) { _, _ -> showRouteManage(activity, store, onDone) }
            .setPositiveButton(R.string.route_new) { _, _ -> promptRouteName(activity, null) { name -> store.createRoute(name); onDone() } }
            .setNegativeButton(R.string.action_cancel) { _, _ -> onDone() }
            .show()
    }

    private fun showRouteManage(activity: Activity, store: WaypointStore, onDone: () -> Unit) {
        val routes = store.routeList()
        val labels = routes.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.route_manage)
            .setItems(labels) { _, which ->
                val route = routes[which]
                MaterialAlertDialogBuilder(activity)
                    .setTitle(route.name)
                    .setItems(
                        arrayOf(
                            activity.getString(R.string.route_rename),
                            activity.getString(R.string.route_duplicate),
                            activity.getString(R.string.route_delete),
                        )
                    ) { _, action ->
                        when (action) {
                            0 -> promptRouteName(activity, route.name) { store.renameRoute(route.id, it); onDone() }
                            1 -> { store.createRoute(activity.getString(R.string.route_copy_name, route.name), route.id); onDone() }
                            2 -> MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.route_delete)
                                .setMessage(activity.getString(R.string.route_delete_msg, route.name, route.waypoints.size))
                                .setPositiveButton(R.string.action_delete) { _, _ -> store.deleteRoute(route.id); onDone() }
                                .setNegativeButton(R.string.action_cancel) { _, _ -> onDone() }
                                .show()
                        }
                    }
                    .show()
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> onDone() }
            .show()
    }

    private fun promptRouteName(activity: Activity, current: String?, onName: (String) -> Unit) {
        val input = android.widget.EditText(activity).apply {
            setText(current ?: "")
            setSingleLine()
            setHint(R.string.route_name_hint)
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (current == null) R.string.route_new else R.string.route_rename)
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun confirmDelete(activity: Activity, wp: Waypoint, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dlg_delete_title)
            .setMessage(activity.getString(R.string.dlg_delete_message, wp.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun summary(activity: Activity, wp: Waypoint): String =
        activity.getString(R.string.waypoint_summary, wp.lat, wp.lon)

    fun latLngOf(wp: Waypoint): LatLng = LatLng(wp.lat, wp.lon)
}
