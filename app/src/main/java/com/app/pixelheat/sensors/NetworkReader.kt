package com.app.pixelheat.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.app.pixelheat.model.NetworkInfo
import com.app.pixelheat.model.NetworkType

class NetworkReader(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastMobileRx = -1L
    private var lastMobileTx = -1L
    private var lastAt = 0L
    private var rxRate = 0f
    private var txRate = 0f
    private var cellRate = 0f
    private var wifiRate = 0f
    private var signalAt = 0L
    private var wifiDbm: Int? = null
    private var cellDbm: Int? = null

    @Synchronized
    fun read(): NetworkInfo {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val mobileRx = TrafficStats.getMobileRxBytes()
        val mobileTx = TrafficStats.getMobileTxBytes()
        val now = SystemClock.elapsedRealtime()
        if (rx >= 0 && tx >= 0 && mobileRx >= 0 && mobileTx >= 0) {
            if (lastRx >= 0) {
                val dtSec = (now - lastAt) / 1000f
                if (dtSec >= 1f) {
                    rxRate = ((rx - lastRx) / 1024f / dtSec).coerceAtLeast(0f)
                    txRate = ((tx - lastTx) / 1024f / dtSec).coerceAtLeast(0f)
                    cellRate = ((mobileRx - lastMobileRx + mobileTx - lastMobileTx) / 1024f / dtSec).coerceAtLeast(0f)
                    wifiRate = (rxRate + txRate - cellRate).coerceAtLeast(0f)
                    lastRx = rx
                    lastTx = tx
                    lastMobileRx = mobileRx
                    lastMobileTx = mobileTx
                    lastAt = now
                }
            } else {
                lastRx = rx
                lastTx = tx
                lastMobileRx = mobileRx
                lastMobileTx = mobileTx
                lastAt = now
            }
        }
        if (now - signalAt >= SIGNAL_INTERVAL_MS) {
            wifiDbm = readWifiDbm()
            cellDbm = readCellDbm()
            signalAt = now
        }
        return NetworkInfo(currentType(), rxRate, txRate, wifiDbm, cellDbm, cellRate, wifiRate)
    }

    fun currentType(): NetworkType = typeOf(connectivity.getNetworkCapabilities(connectivity.activeNetwork))

    private fun readWifiDbm(): Int? {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val fromTransport = (caps?.transportInfo as? WifiInfo)?.rssi
        @Suppress("DEPRECATION")
        val rssi = fromTransport ?: wifi?.connectionInfo?.rssi
        return rssi?.takeIf { it in RSSI_MIN..RSSI_MAX }
    }

    private fun readCellDbm(): Int? {
        val fromStrength = runCatching {
            telephony?.signalStrength?.cellSignalStrengths.orEmpty().mapNotNull { levelOf(it) }.maxOrNull()
        }.getOrNull()
        if (fromStrength != null) return fromStrength
        val locationGranted = ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!locationGranted) return null
        return runCatching {
            telephony?.allCellInfo.orEmpty().filter { it.isRegistered }.mapNotNull { levelOf(it) }.maxOrNull()
        }.getOrNull()
    }

    private fun levelOf(info: CellInfo): Int? = when (info) {
        is CellInfoNr -> (info.cellSignalStrength as? CellSignalStrengthNr)?.ssRsrp
        is CellInfoLte -> info.cellSignalStrength.rsrp
        else -> info.cellSignalStrength.dbm
    }?.takeIf { it in RSSI_MIN..RSSI_MAX }

    private fun levelOf(strength: CellSignalStrength): Int? = when (strength) {
        is CellSignalStrengthNr -> strength.ssRsrp
        is CellSignalStrengthLte -> strength.rsrp
        else -> strength.dbm
    }.takeIf { it in RSSI_MIN..RSSI_MAX }

    companion object {
        private const val SIGNAL_INTERVAL_MS = 30_000L
        private const val RSSI_MIN = -140
        private const val RSSI_MAX = -20

        fun typeOf(caps: NetworkCapabilities?): NetworkType = when {
            caps == null -> NetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
    }
}
