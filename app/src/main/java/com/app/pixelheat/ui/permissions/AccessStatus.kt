package com.app.pixelheat.ui.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** Permissions and special access the app asks the user for. */
data class AccessStatus(
    val notificationsGranted: Boolean,
    val notificationsEnabled: Boolean,
    val fineLocation: Boolean,
    val coarseLocation: Boolean,
    val unrestrictedBattery: Boolean,
    val usageAccess: Boolean,
)

fun Context.accessStatus(): AccessStatus {
    fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    val appOps = getSystemService(AppOpsManager::class.java)
    return AccessStatus(
        notificationsGranted = has(Manifest.permission.POST_NOTIFICATIONS),
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled(),
        fineLocation = has(Manifest.permission.ACCESS_FINE_LOCATION),
        coarseLocation = has(Manifest.permission.ACCESS_COARSE_LOCATION),
        unrestrictedBattery = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName),
        usageAccess = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED,
    )
}

fun Context.appDetailsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())

fun Context.usageAccessIntent(): Intent {
    val specific = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri())
    return if (canResolve(specific)) specific else Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}

fun Context.notificationSettingsIntent() =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

/**
 * The app info screen, where App battery usage sets Unrestricted. The one-tap request dialog needs
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Google Play reserves for a few app categories.
 */
fun Context.unrestrictedBatteryIntent(): Intent = appDetailsIntent()

private fun Context.canResolve(intent: Intent): Boolean =
    packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)) != null

fun Context.openAppDetails() = startActivity(appDetailsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

fun Context.openUsageAccessSettings() =
    startActivity(usageAccessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

fun Context.openNotificationSettings() =
    startActivity(notificationSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
