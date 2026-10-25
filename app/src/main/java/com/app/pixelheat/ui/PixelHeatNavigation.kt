package com.app.pixelheat.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.app.pixelheat.PixelHeatApp
import com.app.pixelheat.ui.dashboard.DashboardRoute
import com.app.pixelheat.ui.dashboard.DashboardUnderlay
import com.app.pixelheat.ui.dashboard.DashboardViewModel
import com.app.pixelheat.ui.dashboard.rememberDashboardChrome
import com.app.pixelheat.ui.detail.Metric
import com.app.pixelheat.ui.detail.SensorDetailScreen
import com.app.pixelheat.ui.detail.SensorDetailViewModel
import com.app.pixelheat.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object DashboardKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data class SensorDetailKey(val metricKey: String) : NavKey

@Composable
fun PixelHeatNavigation() {
    val backStack = rememberNavBackStack(DashboardKey)
    // Activity-scoped: the live sampling loop is shared by the dashboard and the detail pages.
    val dashboard: DashboardViewModel = viewModel()
    val container = (LocalContext.current.applicationContext as PixelHeatApp).container
    // The gesture animation already revealed the dashboard. The stack pop must not play a second one.
    // Cleared by the next navigation, so it is still set whenever NavDisplay reads the pop spec.
    var seamlessPop by remember { mutableStateOf(false) }
    // The home entry is never popped: an empty stack makes NavDisplay throw.
    val goBack: () -> Unit = {
        if (backStack.size > 1) {
            seamlessPop = false
            backStack.removeLastOrNull()
        }
    }
    val navigate: (NavKey) -> Unit = { key ->
        seamlessPop = false
        backStack.add(key)
    }
    val density = LocalDensity.current.density
    // Read here (composable) because the NavDisplay transition lambdas below are not.
    val motionScheme = MaterialTheme.motionScheme
    val chrome = rememberDashboardChrome()

    PredictiveBackContainer(
        showUnderlay = backStack.size > 1,
        onSeamlessPop = {
            if (backStack.size > 1) {
                seamlessPop = true
                backStack.removeLastOrNull()
            }
        },
        underlay = { DashboardUnderlay(dashboard, chrome) },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = goBack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            transitionSpec = { sharedAxisX(forward = true, density, motionScheme) },
            popTransitionSpec = {
                if (seamlessPop) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    sharedAxisX(forward = false, density, motionScheme)
                }
            },
            // The edge gesture is drawn by PredictiveBackContainer. This spec only runs if that
            // handler is not the one that received the event.
            predictivePopTransitionSpec = { sharedAxisX(forward = false, density, motionScheme) },
            entryProvider = entryProvider {
                entry<DashboardKey> {
                    // Ignore taps once this entry is no longer RESUMED (double tap, or tap mid-transition).
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DashboardRoute(
                        viewModel = dashboard,
                        chrome = chrome,
                        // The entry can still be composed while a pushed screen is closing. Back belongs
                        // to that screen until the dashboard is alone on the stack.
                        handlesSystemBack = backStack.size == 1,
                        onOpenSettings = dropUnlessResumed { navigate(SettingsKey) },
                        onOpenSensor = { metricKey ->
                            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                navigate(SensorDetailKey(metricKey))
                            }
                        },
                    )
                }
                entry<SettingsKey> {
                    SettingsScreen(viewModel = viewModel(), onBack = dropUnlessResumed { goBack() })
                }
                entry<SensorDetailKey> { key ->
                    val detail = viewModel(key = key.metricKey) {
                        SensorDetailViewModel(container, Metric.fromKey(key.metricKey), dashboard.liveSamples)
                    }
                    val live by dashboard.live.collectAsStateWithLifecycle()
                    val monitor by dashboard.monitorStatus.collectAsStateWithLifecycle()
                    val privilege by dashboard.privilege.collectAsStateWithLifecycle()
                    SensorDetailScreen(detail, live, monitor, privilege, onBack = dropUnlessResumed { goBack() })
                }
            },
        )
    }
}
