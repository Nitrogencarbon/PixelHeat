package com.app.pixelheat.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Only listens to fixes other apps already requested (PASSIVE_PROVIDER): it never powers
 * the GNSS receiver itself, so it adds no energy cost.
 */
class PassiveLocationSource(private val context: Context) {
    private val locationManager = context.getSystemService(LocationManager::class.java)

    @Volatile
    private var last: Location? = null
    private var listening = false
    private val listener = LocationListener { last = it }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (listening || !hasPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                ContextCompat.getMainExecutor(context),
                listener,
            )
            listening = true
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    @Synchronized
    fun stop() {
        if (!listening) return
        locationManager.removeUpdates(listener)
        listening = false
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        val now = System.currentTimeMillis()
        last?.takeIf { now - it.time < MAX_AGE_MS }?.let { return it }
        if (!hasPermission()) return null
        return try {
            locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                ?.takeIf { now - it.time < MAX_AGE_MS }
        } catch (_: SecurityException) {
            null
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 60_000L
        const val MIN_DISTANCE_M = 200f
        const val MAX_AGE_MS = 3 * 60 * 60_000L
    }
}
