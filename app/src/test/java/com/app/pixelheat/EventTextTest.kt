package com.app.pixelheat

import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.data.db.describe
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class EventTextTest {

    private fun event(type: HardwareEventType, active: Boolean, value: Float? = null) =
        HardwareEventEntity(timestamp = 0L, type = type, active = active, value = value)

    @Test
    fun eventsMigratedWithoutValueStillReadWell() {
        assertEquals("Network connected", event(HardwareEventType.NETWORK, true).describe())
        assertEquals("Thermal status dropped", event(HardwareEventType.THERMAL, false).describe())
        assertEquals("Charger unplugged", event(HardwareEventType.CHARGING, false).describe())
    }

    @Test
    fun valueIsInterpretedPerType() {
        assertEquals("Switched to Wi-Fi", event(HardwareEventType.NETWORK, true, NetworkType.WIFI.ordinal.toFloat()).describe())
        assertEquals(
            "Thermal status rose to moderate",
            event(HardwareEventType.THERMAL, true, ThermalStatus.MODERATE.toFloat()).describe(),
        )
        assertEquals("Brightness raised to 85%", event(HardwareEventType.BRIGHTNESS, true, 85f).describe())
    }
}
