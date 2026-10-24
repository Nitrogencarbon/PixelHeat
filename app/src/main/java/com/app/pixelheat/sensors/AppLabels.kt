package com.app.pixelheat.sensors

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Resolves package names to the app names shown in the launcher; native processes get a descriptive name. */
class AppLabels(context: Context) {
    private val pm = context.applicationContext.packageManager
    private val cache = ConcurrentHashMap<String, String>()

    fun labelOf(pkg: String): String = cache.getOrPut(pkg) {
        AppActivityReader.nativeLabel(pkg)
            ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }
}
