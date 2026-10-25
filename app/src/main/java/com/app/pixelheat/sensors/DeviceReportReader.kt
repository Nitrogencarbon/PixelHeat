package com.app.pixelheat.sensors

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import com.app.pixelheat.model.DeviceReport
import com.app.pixelheat.model.XPOSED_ZIP_ENTRIES
import com.app.pixelheat.model.isXposedModule
import com.app.pixelheat.model.rootModuleCount
import com.app.pixelheat.privilege.PrivilegeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

/**
 * Counts launchable and user-installed apps, Magisk/KernelSU modules, Xposed-family modules
 * (classic Xposed, EdXposed, LSPosed, Vector), and user apps left unrestricted.
 */
class DeviceReportReader(
    context: Context,
    private val privilege: PrivilegeManager,
) {
    private val appContext = context.applicationContext
    private val gate = Mutex()
    private val moduleStamp = HashMap<String, Pair<Long, Boolean>>()

    suspend fun read(): DeviceReport = gate.withLock {
        withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            val (packages, hasMeta) = installedPackages(pm)
            val launcher = launcherPackages(pm)
            val apps = packages.mapNotNull { info -> info.applicationInfo?.let { info to it } }
            val seen = apps.map { it.first.packageName }.toSet()
            moduleStamp.keys.retainAll(seen)
            val shell = privilege.activeShell()
            DeviceReport(
                installedApps = apps.count { (info, app) -> isInstalledApp(info.packageName, app, launcher) },
                rootModules = if (shell == null) null else rootModuleCount(shell.exec(MODULE_CMD).orEmpty()),
                xposedModules = apps.count { (info, app) ->
                    isXposedPackage(pm, info.packageName, app, info.lastUpdateTime, hasMeta)
                },
                alwaysOnApps = apps.count { (_, app) -> isUserApp(app) && ignoresBatteryOptimizations(app.packageName) },
            )
        }
    }

    private fun installedPackages(pm: PackageManager): Pair<List<PackageInfo>, Boolean> = try {
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())) to true
    } catch (_: RuntimeException) {
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)) to false
    }

    private fun launcherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
            .toSet()
    }

    private fun isInstalledApp(packageName: String, app: ApplicationInfo, launcher: Set<String>): Boolean {
        if (!app.enabled) return false
        return packageName in launcher || isUserApp(app)
    }

    private fun isUserApp(app: ApplicationInfo): Boolean =
        app.enabled && (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0

    private fun ignoresBatteryOptimizations(packageName: String): Boolean = runCatching {
        appContext.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
    }.getOrDefault(false)

    private fun isXposedPackage(
        pm: PackageManager,
        packageName: String,
        app: ApplicationInfo,
        updatedAt: Long,
        hasMeta: Boolean,
    ): Boolean {
        if (!app.enabled) return false
        val cached = moduleStamp[packageName]
        if (cached != null && cached.first == updatedAt) return cached.second
        val meta = when {
            app.metaData != null -> app.metaData.keySet()
            hasMeta -> emptySet()
            isUserApp(app) -> metadataOf(pm, packageName)
            else -> emptySet()
        }
        var entries: Set<String>? = null
        val module = isXposedModule(meta) { entry ->
            if (!isUserApp(app)) return@isXposedModule false
            val found = entries ?: zipEntries(app.sourceDir).also { entries = it }
            entry in found
        }
        moduleStamp[packageName] = updatedAt to module
        return module
    }

    /** A bulk package query can drop metadata; one package still returns it. */
    private fun metadataOf(pm: PackageManager, packageName: String): Set<String> = runCatching {
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            .applicationInfo?.metaData?.keySet().orEmpty()
    }.getOrDefault(emptySet())

    private fun zipEntries(sourceDir: String?): Set<String> {
        if (sourceDir.isNullOrBlank()) return emptySet()
        return runCatching {
            ZipFile(sourceDir).use { zip -> XPOSED_ZIP_ENTRIES.filter { zip.getEntry(it) != null }.toSet() }
        }.getOrDefault(emptySet())
    }

    private companion object {
        /**
         * `ADB:` separates "Magisk/KernelSU is not installed" from "the directory cannot be read".
         * `MOD:` is one module id. A `remove` file means that module is about to be deleted.
         */
        val MODULE_CMD = $$"""
            err=$(ls -d /data/adb 2>&1)
            echo "ADB:$err"
            for base in /data/adb/modules /data/adb/ksu/modules; do
              if [ -d "$base" ] && [ -r "$base" ] && [ -x "$base" ]; then
                echo "BASE:$base"
                for prop in "$base"/*/module.prop; do
                  [ -f "$prop" ] || continue
                  dir=${prop%/module.prop}
                  [ -e "$dir/remove" ] && continue
                  echo "MOD:${dir##*/}"
                done
              elif [ -e "$base" ]; then
                echo "DENY:$base"
              fi
            done
            """.trimIndent()
    }
}
