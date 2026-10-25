package com.app.pixelheat.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.pixelheat.data.db.describe
import com.app.pixelheat.diagnostics.DiagnosisReport
import com.app.pixelheat.diagnostics.DiagnosisStatus
import com.app.pixelheat.diagnostics.AppShare
import com.app.pixelheat.diagnostics.CauseContribution
import com.app.pixelheat.diagnostics.HeatEpisode
import com.app.pixelheat.ui.components.HeatProgress
import com.app.pixelheat.ui.components.SectionCard
import com.app.pixelheat.ui.components.ShapeIcon
import com.app.pixelheat.ui.theme.Ember
import com.app.pixelheat.ui.theme.Frost
import com.app.pixelheat.ui.theme.Good
import com.app.pixelheat.ui.theme.Neutral
import com.app.pixelheat.ui.theme.color
import com.app.pixelheat.ui.theme.temperatureColor
import com.app.pixelheat.util.fmt
import com.app.pixelheat.util.formatDayTime
import com.app.pixelheat.util.formatDuration
import com.app.pixelheat.util.formatPower
import com.app.pixelheat.util.formatSignedTemp
import com.app.pixelheat.util.formatTemp
import com.app.pixelheat.util.formatTime
import kotlin.math.roundToInt

private fun DiagnosisStatus.color(): Color = when (this) {
    DiagnosisStatus.INSUFFICIENT_DATA -> Neutral
    DiagnosisStatus.NORMAL -> Good
    DiagnosisStatus.COOLING -> Frost
    DiagnosisStatus.HEATING -> temperatureColor(38f)
    DiagnosisStatus.HOT -> Ember
    DiagnosisStatus.CRITICAL -> temperatureColor(50f)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SectionLoading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusBadge(status: DiagnosisStatus) {
    val c = status.color()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = c.copy(alpha = 0.2f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentColor = c,
    ) {
        Text(status.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DiagnosisSection(
    report: DiagnosisReport?,
    window: ContributionWindow,
    onWindow: (ContributionWindow) -> Unit,
    installedAt: Long,
) {
    SectionCard(
        title = "Cause & effect",
        icon = Icons.Rounded.Insights,
        trailing = { report?.let { StatusBadge(it.status) } },
    ) {
        val motion = MaterialTheme.motionScheme
        // Branch key (loading vs report): report refreshes update in place, only the swap cross-fades.
        AnimatedContent(
            targetState = report,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
            },
            contentKey = { it != null },
            label = "diagnosis",
        ) { current ->
            if (current == null) SectionLoading("Analyzing…") else DiagnosisBody(current, window, onWindow, installedAt)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiagnosisBody(
    report: DiagnosisReport,
    window: ContributionWindow,
    onWindow: (ContributionWindow) -> Unit,
    installedAt: Long,
) {
    Column {
        Text(report.headline, style = MaterialTheme.typography.headlineSmallEmphasized, color = report.status.color())
        Spacer(Modifier.height(8.dp))
        Text(report.explanation, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Estimated heat contribution",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            ContributionWindowMenu(window, onWindow)
        }
        val requestedMin = (System.currentTimeMillis() - window.startAt(System.currentTimeMillis(), installedAt)) / 60_000f
        if (report.causes.isNotEmpty() && report.windowMin < requestedMin * 0.9f) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Based on the last ${formatDuration((report.windowMin * 60_000f).toLong())} of samples.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (report.causes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val shown = report.causes.take(6)
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                shown.forEachIndexed { i, cause ->
                    CauseItem(
                        cause = cause,
                        primary = i == 0,
                        barColor = if (i == 0) report.status.color() else MaterialTheme.colorScheme.secondary,
                        shape = ListItemDefaults.segmentedShapes(index = i, count = shown.size).shape,
                    )
                }
            }
        }

        val impacts = report.eventImpacts.filter { it.significant }.take(4)
        if (impacts.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SubHeader("Hardware events with thermal impact")
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                impacts.forEachIndexed { i, impact ->
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(index = i, count = impacts.size),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        leadingContent = {
                            ShapeIcon(
                                Icons.AutoMirrored.Rounded.TrendingUp,
                                impact.event.type.color(),
                                shape = MaterialShapes.Pill.toShape(),
                            )
                        },
                        supportingContent = { Text("${impact.event.type.label} at ${formatTime(impact.event.timestamp)}") },
                        trailingContent = {
                            Text(
                                "${formatSignedTemp(impact.deltaC)}\n${impact.minutes.roundToInt()} min",
                                style = MaterialTheme.typography.labelLarge,
                                color = temperatureColor(38f + impact.deltaC * 2),
                            )
                        },
                    ) { Text(impact.event.describe()) }
                }
            }
        }

        if (report.advice.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SubHeader("What you can do")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                report.advice.forEach {
                    Row {
                        Icon(
                            Icons.Rounded.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CauseItem(cause: CauseContribution, primary: Boolean, barColor: Color, shape: Shape) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                cause.label,
                style = if (primary) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${cause.sharePct.roundToInt()}% · ${formatPower(cause.avgWatts)}" +
                    (cause.correlation?.let { " · r ${it.fmt(2)}" }.orEmpty()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        HeatProgress(cause.sharePct / 100f, barColor)
        if (cause.apps.isNotEmpty()) AppBreakdown(cause.apps)
    }
}

/** Apps as a sub-scale of their cause: bars split the cause's share, indented under a guide line. */
@Composable
private fun AppBreakdown(apps: List<AppShare>) {
    val accent = MaterialTheme.colorScheme.tertiary
    Row(
        Modifier
            .padding(top = 8.dp, start = 4.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(accent.copy(alpha = 0.35f), RoundedCornerShape(1.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Apps during heating", style = MaterialTheme.typography.labelSmall, color = accent)
            apps.forEach { app ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            app.label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${app.sharePct.roundToInt()}% · active ${app.peakPct.roundToInt()}% of the rise",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    HeatProgress(app.sharePct / 100f, accent, Modifier.height(3.dp))
                }
            }
        }
    }
}

private fun AppShare.peakText() = "$label (${peakPct.roundToInt()}% of the rise)"

private fun episodeDetails(e: HeatEpisode): List<String> = buildList {
    if (e.suspects.isNotEmpty()) add("Active during the peak: " + e.suspects.joinToString(" · ") { it.peakText() })
    addAll(e.hardwareUsers)
    e.peakZone?.let { add("Hottest zone: $it") }
}

@Composable
internal fun ContributionWindowMenu(
    selected: ContributionWindow,
    onSelect: (ContributionWindow) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { expanded = true }) {
            Text(selected.label)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose the duration")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ContributionWindow.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    leadingIcon = if (option == selected) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EpisodesSection(episodes: List<HeatEpisode>) {
    SectionCard(title = "Overheating history · 7 days", icon = Icons.Rounded.Whatshot) {
        if (episodes.isEmpty()) {
            Text(
                "No episodes above 40 °C or with thermal throttling in the last 7 days.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            episodes.forEachIndexed { i, e ->
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = i, count = episodes.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    leadingContent = {
                        ShapeIcon(Icons.Rounded.LocalFireDepartment, temperatureColor(e.peakTemp), shape = MaterialShapes.SoftBurst.toShape())
                    },
                    overlineContent = { Text("${formatDayTime(e.start)} · ${formatDuration(e.end - e.start).ifBlank { "<1 min" }}") },
                    supportingContent = episodeDetails(e).takeIf { it.isNotEmpty() }?.let { lines -> { Text(lines.joinToString("\n")) } },
                    trailingContent = {
                        Text(formatTemp(e.peakTemp), style = MaterialTheme.typography.titleMediumEmphasized, color = temperatureColor(e.peakTemp))
                    },
                ) { Text(e.summary) }
            }
        }
    }
}
