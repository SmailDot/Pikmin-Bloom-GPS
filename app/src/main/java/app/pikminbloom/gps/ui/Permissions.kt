package app.pikminbloom.gps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Tiny wrappers around the runtime permissions / system settings screens the UI needs.
 * Everything here is UI-only; the service does its own checks.
 */
object Permissions {

    private const val TAG = "PikminGPS"

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Always true below API 33, where the runtime permission does not exist. */
    fun hasNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
        else ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** "Display over other apps" (SYSTEM_ALERT_WINDOW) — required by [OverlayService]. */
    fun canDrawOverlays(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (t: Throwable) {
        Log.w(TAG, "canDrawOverlays failed", t); false
    }

    /**
     * Intent for the system's overlay permission screen. Use it with an ActivityResultLauncher
     * (there is no result, but the launcher gives us a callback to re-check on return).
     */
    fun overlayPermissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    )

    /**
     * Opens the overlay permission screen. Falls back to the app info page, because some HyperOS /
     * MIUI builds hide the per-app overlay screen behind 應用程式資訊 → 權限 → 顯示在其他應用程式上層.
     */
    fun openOverlaySettings(context: Context): Boolean =
        start(context, overlayPermissionIntent(context)) ||
            start(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) ||
            openAppDetails(context)

    /** 使用情況存取權 (PACKAGE_USAGE_STATS app-op), needed by 9c. */
    fun hasUsageAccess(context: Context): Boolean = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            // The user never touched the switch: as the platform docs say, the manifest permission then decides.
            AppOpsManager.MODE_DEFAULT -> context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    } catch (t: Throwable) {
        Log.w(TAG, "usage access check failed", t); false
    }

    fun openUsageAccessSettings(context: Context): Boolean = start(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "isIgnoringBatteryOptimizations failed", t); false
    }

    /** Opens the "App info" page of [packageName] (defaults to this app). */
    fun openAppDetails(context: Context, packageName: String = context.packageName): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )
        return start(context, direct) || start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Launches another installed app; false when it is not installed. */
    fun openApp(context: Context, packageName: String): Boolean {
        val intent = try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (t: Throwable) {
            Log.w(TAG, "getLaunchIntentForPackage($packageName) failed", t); null
        } ?: return false
        return start(context, intent)
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getLaunchIntentForPackage(packageName) != null
    } catch (t: Throwable) {
        false
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "cannot start ${intent.action}", t); false
    }
}
