package com.app.pixelheat.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.pixelheat.model.MonitorStatus
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.model.ZoneSource
import com.app.pixelheat.sensors.BatteryReader
import com.app.pixelheat.ui.components.HeatProgress
import com.app.pixelheat.ui.components.ShapeIcon
import com.app.pixelheat.ui.theme.Amber
import com.app.pixelheat.ui.theme.AmbientHeat
import com.app.pixelheat.ui.theme.BatteryHeat
import com.app.pixelheat.ui.theme.CpuHeat
import com.app.pixelheat.ui.theme.Frost
import com.app.pixelheat.ui.theme.Good
import com.app.pixelheat.ui.theme.GpuHeat
import com.app.pixelheat.ui.theme.HeatScale
import com.app.pixelheat.ui.theme.ModemHeat
import com.app.pixelheat.ui.theme.Neutral
import com.app.pixelheat.ui.theme.SkinHeat
import com.app.pixelheat.ui.theme.UsbHeat
import com.app.pixelheat.ui.theme.heatScale
import com.app.pixelheat.ui.theme.temperatureColor
import com.app.pixelheat.ui.theme.thermalStatusColor
import com.app.pixelheat.ui.theme.thermalStatusFraction
import com.app.pixelheat.util.fmt
import com.app.pixelheat.util.formatDuration
import com.app.pixelheat.util.formatPower
import com.app.pixelheat.util.formatRate
import com.app.pixelheat.util.formatTemp
import kotlin.math.abs

data class TileModel(
    val key: String,
    val title: String,
    val value: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    val accent: Color = Neutral,
    val progress: Float? = null,
)

private const val PRIVILEGE_HINT = "Requires root or Shizuku"

fun buildTiles(s: ThermalSnapshot, monitor: MonitorStatus?): List<TileModel> {
    val b = s.battery
    val zonesHint = if (s.zoneSource == ZoneSource.PUBLIC_API) PRIVILEGE_HINT else null
    fun zoneTile(
        key: String,
        title: String,
        icon: ImageVector,
        value: Float?,
        scale: HeatScale,
        extra: String? = null,
    ) = TileModel(
        key = key,
        title = title,
        value = formatTemp(value),
        icon = icon,
        subtitle = if (value == null) zonesHint ?: "Sensor not exposed" else extra,
        accent = scale.color(value),
        progress = scale.fraction(value),
    )

    return buildList {
        add(
            TileModel(
                "battery_temp", "Battery", formatTemp(b.tempC), Icons.Rounded.Thermostat,
                "Health ${BatteryReader.healthLabel(b.health).lowercase()}" + (b.cycleCount?.let { " · $it cycles" }.orEmpty()),
                BatteryHeat.color(b.tempC), BatteryHeat.fraction(b.tempC),
            ),
        )
        add(zoneTile("skin", "Skin", Icons.Rounded.Smartphone, s.skinTemp, SkinHeat))
        add(zoneTile("cpu", "CPU", Icons.Rounded.Memory, s.cpuTemp, CpuHeat, s.cpuFreqPct?.let { "Avg frequency ${it.toInt()}%" }))
        add(zoneTile("gpu", "GPU", Icons.Rounded.DeveloperBoard, s.gpuTemp, GpuHeat))
        add(zoneTile("modem", "Modem / radio", Icons.Rounded.CellTower, s.modemTemp, ModemHeat))
        add(zoneTile("usb", "USB port", Icons.Rounded.Usb, s.usbTemp, UsbHeat))
        s.hottestZone?.let {
            val scale = it.category.heatScale()
            add(
                TileModel(
                    "hottest", "Hottest zone", formatTemp(it.celsius), Icons.Rounded.LocalFireDepartment,
                    it.name, scale.color(it.celsius), scale.fraction(it.celsius),
                ),
            )
        }
        add(
            TileModel(
                "ambient", "Outside", formatTemp(s.ambient.celsius), Icons.Rounded.DeviceThermostat,
                s.ambient.sigma?.let { "Fused · ±${it.fmt(1)} °C" } ?: "Estimate: ${s.ambient.source.label}",
                AmbientHeat.color(s.ambient.celsius), AmbientHeat.fraction(s.ambient.celsius),
            ),
        )
        add(
            TileModel(
                "thermal_status", "Android thermal status", ThermalStatus.label(s.thermalStatus), Icons.Rounded.Speed,
                s.headroom?.let { "Headroom ${it.fmt(2)} (1.0 = throttling)" } ?: "Headroom unavailable",
                thermalStatusColor(s.thermalStatus),
                thermalStatusFraction(s.thermalStatus),
            ),
        )
        add(
            TileModel(
                "level", "Charge", "${b.levelPct}%",
                if (b.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull,
                when {
                    b.isCharging -> "${if (b.fastCharging) "Fast charging" else "Charging"} · ${b.plugType.label}" +
                        (b.chargerMaxW?.let { " up to ${formatPower(it)}" }.orEmpty())
                    else -> "Discharging"
                },
                if (b.isCharging) Good else Neutral, b.levelPct / 100f,
            ),
        )
        add(
            TileModel(
                "power", "Battery power", formatPower(b.powerW), Icons.Rounded.Bolt,
                "${b.currentMa} mA · ${(b.voltageMv / 1000f).fmt(2)} V",
                if (b.powerW >= 0) Good else temperatureColor(36f + abs(b.powerW)),
                (abs(b.powerW) / 15f).coerceIn(0f, 1f),
            ),
        )
        add(
            TileModel(
                "screen", "Display", if (s.screenOn) "On" else "Off", Icons.Rounded.PhoneAndroid,
                s.brightnessPct?.let { "Brightness $it%" },
                if (s.screenOn) Amber else Neutral,
                s.brightnessPct?.div(100f),
            ),
        )
        add(
            run {
                val signal = when (s.network.type) {
                    NetworkType.WIFI -> s.network.wifiDbm
                    NetworkType.CELLULAR -> s.network.cellDbm
                    else -> s.network.wifiDbm ?: s.network.cellDbm
                }
                TileModel(
                    "network", "Network", s.network.type.label,
                    if (s.network.type == NetworkType.CELLULAR) Icons.Rounded.SignalCellularAlt else Icons.Rounded.Wifi,
                    buildString {
                        append("↓ ${formatRate(s.network.rxKBps)} · ↑ ${formatRate(s.network.txKBps)}")
                        signal?.let { append(" · $it dBm") }
                    },
                    if (s.network.type == NetworkType.CELLULAR) temperatureColor(41f) else Frost,
                    (s.network.totalKBps / 4096f).coerceIn(0f, 1f),
                )
            },
        )
        add(
            TileModel(
                "gps", "GPS", if (s.gpsActive) "Active" else "Idle", Icons.Rounded.GpsFixed,
                "Detected from GNSS status", if (s.gpsActive) Good else Neutral,
            ),
        )
        add(
            TileModel(
                "camera", "Camera", if (s.cameraActive) "In use" else "Idle", Icons.Rounded.PhotoCamera,
                null, if (s.cameraActive) temperatureColor(42f) else Neutral,
            ),
        )
        add(
            TileModel(
                "audio", "Audio", if (s.audioActive) "Playing" else "Silent", Icons.Rounded.MusicNote,
                null, if (s.audioActive) Good else Neutral,
            ),
        )
        add(
            TileModel(
                "light", "Ambient light",
                s.environment?.lux?.let { "${it.toInt()} lux" } ?: "—",
                Icons.Rounded.LightMode,
                s.environment?.humidityPct?.let { "Humidity ${it.toInt()}%" } ?: "Read in detailed mode only",
                if ((s.environment?.lux ?: 0f) >= 25_000f) temperatureColor(42f) else Neutral,
            ),
        )
        add(
            TileModel(
                "source", "Thermal data source", s.zoneSource.label, Icons.Rounded.Sensors,
                "${s.zones.size} zones read", if (s.zoneSource == ZoneSource.HAL_PRIVILEGED) Good else Neutral,
            ),
        )
        monitor?.let {
            add(
                TileModel(
                    "monitor", "Sampling", it.level.label, Icons.Rounded.Timer,
                    "Every ${formatDuration(it.level.intervalMs)} · ${ThermalStatus.label(it.thermalStatus)}",
                    thermalStatusColor(it.thermalStatus),
                ),
            )
        }
    }
}

@Composable
fun MetricTile(tile: TileModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val titleStyle = MaterialTheme.typography.labelLarge
    val valueStyle = MaterialTheme.typography.headlineSmall
    val subtitleStyle = MaterialTheme.typography.bodySmall
    val titleBlock = with(LocalDensity.current) { titleStyle.lineHeight.toDp() * TITLE_LINES }
    val headerHeight = if (titleBlock > ICON_SIZE) titleBlock else ICON_SIZE
    val tileHeight = TILE_PADDING * 2 + headerHeight + TITLE_GAP +
        with(LocalDensity.current) { valueStyle.lineHeight.toDp() + subtitleStyle.lineHeight.toDp() * SUBTITLE_LINES } +
        BAR_GAP + BAR_HEIGHT

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(tileHeight),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(TILE_PADDING).fillMaxHeight()) {
            Row(Modifier.height(headerHeight), verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(tile.icon, tile.accent, size = ICON_SIZE)
                Spacer(Modifier.width(10.dp))
                Text(
                    tile.title,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(TITLE_GAP))
            Text(
                tile.value,
                style = valueStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tile.subtitle.orEmpty(),
                style = subtitleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = SUBTITLE_LINES,
                maxLines = SUBTITLE_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (tile.progress != null) {
                HeatProgress(tile.progress, tile.accent, Modifier.height(BAR_HEIGHT))
            } else {
                Spacer(Modifier.height(BAR_HEIGHT))
            }
        }
    }
}

private const val TITLE_LINES = 2
private const val SUBTITLE_LINES = 2
private val ICON_SIZE = 36.dp
private val TILE_PADDING = 16.dp
private val TITLE_GAP = 10.dp
private val BAR_GAP = 10.dp
private val BAR_HEIGHT = 4.dp
