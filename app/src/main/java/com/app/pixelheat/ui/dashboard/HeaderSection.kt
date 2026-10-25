package com.app.pixelheat.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.pixelheat.data.db.IN_USE_POWER_W
import com.app.pixelheat.model.DeviceIdentity
import com.app.pixelheat.model.DeviceReport
import com.app.pixelheat.model.LifetimeAverages
import com.app.pixelheat.ui.components.SectionCard
import com.app.pixelheat.ui.components.StatBlock
import com.app.pixelheat.ui.theme.temperatureColor
import com.app.pixelheat.util.fmt
import com.app.pixelheat.util.formatDate
import com.app.pixelheat.util.formatPower
import com.app.pixelheat.util.formatTemp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceHeroCard(identity: DeviceIdentity, lifetime: LifetimeAverages?, report: DeviceReport?) {
    val average = lifetime?.avgPhoneTemp
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull("${identity.manufacturer} ${identity.model}", identity.codename).joinToString(" · "),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                identity.soc?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android ${identity.androidVersion} (API ${identity.sdkInt})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Security patch ${identity.securityPatch} · ${identity.buildId}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                report?.let {
                    Text(
                        it.summaryLine(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatTemp(average),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    color = average?.let { temperatureColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    "Average",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LifetimeSection(lifetime: LifetimeAverages?) {
    SectionCard(title = "Since install", icon = Icons.Rounded.History) {
        if (lifetime == null || lifetime.samples == 0L) {
            Text(
                "Long-term averages will appear after the first samples are recorded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Text(
            "Since ${formatDate(lifetime.since)} · ${lifetime.monitoredHours.fmt(1)} h monitored · ${lifetime.samples} samples",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatBlock("Avg phone", formatTemp(lifetime.avgPhoneTemp), accent = temperatureColor(lifetime.avgPhoneTemp))
            StatBlock(
                "Avg while in use",
                formatTemp(lifetime.avgInUseTemp),
                accent = lifetime.avgInUseTemp?.let { temperatureColor(it) },
            )
            StatBlock("Avg hottest zone", formatTemp(lifetime.avgMaxTemp))
            StatBlock("Peak", formatTemp(lifetime.peakTemp), accent = temperatureColor(lifetime.peakTemp))
            StatBlock("Avg outside", formatTemp(lifetime.avgAmbient))
            StatBlock("Avg drain", lifetime.avgDischargeW?.let { formatPower(it) } ?: "—")
            StatBlock("Charging", "${lifetime.chargingPct.roundToInt()}%")
            StatBlock("Screen on", "${lifetime.screenOnPct.roundToInt()}%")
            StatBlock("Time ≥ 40 °C", "${lifetime.hotPct.fmt(1)}%")
            StatBlock("Throttled", "${lifetime.throttledPct.fmt(1)}%")
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "While in use means the screen is on, or the phone is charging, drawing at least ${IN_USE_POWER_W.fmt(1)} W, or using GPS, the camera, or audio.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PermissionCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.TouchApp, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Recommended permissions", style = MaterialTheme.typography.titleMediumEmphasized)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Notifications for heat alerts, and location to detect GPS usage and estimate the outside temperature (passive only).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGrant, modifier = Modifier.align(Alignment.End)) { Text("Allow") }
        }
    }
}
