package com.app.pixelheat.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.app.pixelheat.diagnostics.AppHeatEntry
import com.app.pixelheat.diagnostics.AppHeatRanking
import com.app.pixelheat.ui.components.HeatProgress
import com.app.pixelheat.ui.components.SectionCard
import com.app.pixelheat.util.formatDuration
import com.app.pixelheat.util.formatPower
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppHeatSection(
    ranking: AppHeatRanking?,
    window: ContributionWindow,
    onWindow: (ContributionWindow) -> Unit,
) {
    SectionCard(title = "App heat contribution", icon = Icons.Rounded.Apps, trailing = {
        ContributionWindowMenu(window, onWindow)
    }) {
        if (ranking == null) {
            SectionLoading("Ranking apps…")
            return@SectionCard
        }
        Text(
            "Each app's share of the heat tied to app activity over the whole period.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ranking.apps.isNotEmpty() && ranking.coveredMs < ranking.requestedMs * 0.9f) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Based on the last ${formatDuration(ranking.coveredMs).ifBlank { "<1 min" }} of samples.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (ranking.apps.isEmpty()) {
            Text(
                "No app activity recorded in this period.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            ranking.apps.forEachIndexed { index, app ->
                AppHeatRow(
                    app,
                    primary = index == 0,
                    shape = ListItemDefaults.segmentedShapes(index = index, count = ranking.apps.size).shape,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppHeatRow(app: AppHeatEntry, primary: Boolean, shape: Shape) {
    val bar = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = if (primary) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall,
                )
                if (app.sources.isNotEmpty()) {
                    Text(
                        app.sources,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "${app.sharePct.roundToInt()}% · ${formatPower(app.avgWatts)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        HeatProgress(app.sharePct / 100f, bar)
    }
}
