package com.app.pixelheat.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MotionScheme
import androidx.navigation3.scene.Scene

// Geometry, not motion: M3 has no token for a shared-axis travel distance.
// The slide distance is a design decision. Timing comes from MaterialTheme.motionScheme.
private const val AXIS_SHIFT_DP = 30

/**
 * Material shared-axis X for the toolbar back button: content slides a short distance along the navigation
 * direction while cross-fading. The slide is a spatial change of a full-screen page (slow spatial spec); the
 * fades are effects (fast out, default in, so the old content is mostly gone as the new one appears).
 */
fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.sharedAxisX(
    forward: Boolean,
    density: Float,
    motionScheme: MotionScheme,
): ContentTransform {
    val sign = if (forward) 1 else -1
    val shift = (AXIS_SHIFT_DP * density).toInt()
    val enter = slideInHorizontally(motionScheme.slowSpatialSpec()) { sign * shift } +
        fadeIn(motionScheme.defaultEffectsSpec())
    val exit = slideOutHorizontally(motionScheme.slowSpatialSpec()) { -sign * shift } +
        fadeOut(motionScheme.fastEffectsSpec())
    return enter togetherWith exit
}
