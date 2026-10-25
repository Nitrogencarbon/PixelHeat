package com.app.pixelheat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Filled card on `surfaceContainer`, the standard container for every dashboard section. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (title != null || trailing != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    icon?.let {
                        Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    title?.let {
                        Text(it, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.height(16.dp))
            }
            content()
        }
    }
}

/**
 * Modifier for lazy-grid items: placement follows the motion scheme's spatial spec, fades its
 * effects spec (reordering, appearing and disappearing items animate instead of jumping).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("ModifierFactoryExtensionFunction") // needs the grid item scope as receiver for animateItem
@Composable
fun LazyGridItemScope.motionItem(): Modifier {
    val motion = MaterialTheme.motionScheme
    return Modifier.animateItem(
        fadeInSpec = motion.defaultEffectsSpec(),
        placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.defaultEffectsSpec(),
    )
}

/** Icon on a tonal Material shape tinted with a data color; used for tiles and list leading content. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapeIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = MaterialShapes.Cookie9Sided.toShape(),
) {
    val container = tint.copy(alpha = 0.22f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHighest)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * Determinate M3 progress bar with the stop indicator, colored by data semantics.
 * Both the fill and the color follow the motion scheme's effects spec: they are bounded values,
 * so no overshoot (a spatial spring would push the bar past 100%).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HeatProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.motionScheme
    val animatedFraction = animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = motion.defaultEffectsSpec(),
        label = "heatProgress",
    )
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = motion.defaultEffectsSpec(),
        label = "heatProgressColor",
    )
    LinearProgressIndicator(
        progress = { animatedFraction.value.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
        color = animatedColor,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
    )
}

@Composable
fun StatBlock(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Non-interactive tag for read-only values (thermal zones); chips are reserved for actions. */
@Composable
fun ValueTag(label: String, value: String, dotColor: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun LegendItem(label: String, color: Color, square: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .then(if (square) Modifier.size(10.dp) else Modifier.width(16.dp).height(4.dp))
                .clip(if (square) MaterialTheme.shapes.extraSmall else CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
