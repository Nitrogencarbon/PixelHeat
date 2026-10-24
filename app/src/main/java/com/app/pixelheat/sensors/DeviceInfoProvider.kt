package com.app.pixelheat.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.app.pixelheat.model.DeviceIdentity

class DeviceInfoProvider(private val context: Context) {

    fun identity(): DeviceIdentity {
        val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }
            .joinToString(" ")
            .ifBlank { null }
        val installedAt = runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .firstInstallTime
        }.getOrDefault(System.currentTimeMillis())

        return DeviceIdentity(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            codename = Build.DEVICE,
            soc = soc,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            buildId = Build.ID,
            installedAt = installedAt,
        )
    }
}
