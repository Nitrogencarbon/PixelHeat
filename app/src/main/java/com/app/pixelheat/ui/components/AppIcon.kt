package com.app.pixelheat.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.pixelheat.R

/**
 * The launcher icon drawn from its adaptive layers. Each layer is 108 dp with only the central 72 dp meant to be
 * visible, so the layers are drawn 1.5× larger than [size] and clipped to [shape], like a launcher mask.
 */
@Composable
fun AppIcon(modifier: Modifier = Modifier, size: Dp = 40.dp, shape: Shape = CircleShape) {
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(size * 1.5f)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = layer)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = layer)
    }
}
