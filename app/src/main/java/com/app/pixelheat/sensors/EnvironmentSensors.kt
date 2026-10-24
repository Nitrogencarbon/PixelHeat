package com.app.pixelheat.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.app.pixelheat.model.EnvironmentReading
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One-shot reads: each sensor is registered only until it delivers its first value. */
class EnvironmentSensors(context: Context) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val ambient: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private val humidity: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
    private val light: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximity: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    suspend fun read(timeoutMs: Long = 1_500L): EnvironmentReading = coroutineScope {
        val a = async { readOnce(ambient, timeoutMs) }
        val h = async { readOnce(humidity, timeoutMs) }
        val l = async { readOnce(light, timeoutMs) }
        val p = async { readOnce(proximity, timeoutMs) }
        EnvironmentReading(
            ambientSensorC = a.await(),
            humidityPct = h.await(),
            lux = l.await(),
            proximityCm = p.await(),
        )
    }

    private suspend fun readOnce(sensor: Sensor?, timeoutMs: Long): Float? {
        sensor ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensorManager.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.firstOrNull())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        }
    }
}
