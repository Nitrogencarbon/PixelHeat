package com.app.pixelheat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.app.pixelheat.PixelHeatApp
import com.app.pixelheat.ui.dashboard.DashboardRoute
import com.app.pixelheat.ui.dashboard.DashboardViewModel
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
    val goBack: () -> Unit = { backStack.removeLastOrNull() }
    val density = LocalDensity.current.density
    val motion = remember { NavMotion() }

    NavDisplay(
        backStack = backStack,
        onBack = {
            motion.popCommitted = true
            goBack()
        },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
            rememberBackMotionDecorator(motion),
        ),
        transitionSpec = { sharedAxisX(forward = true, density, motion) },
        popTransitionSpec = { sharedAxisX(forward = false, density, motion) },
        predictivePopTransitionSpec = { predictiveBack(motion) },
        entryProvider = entryProvider {
            entry<DashboardKey> {
                DashboardRoute(
                    viewModel = dashboard,
                    onOpenSettings = { backStack.add(SettingsKey) },
                    onOpenSensor = { backStack.add(SensorDetailKey(it)) },
                )
            }
            entry<SettingsKey> {
                SettingsScreen(viewModel = viewModel(), onBack = goBack)
            }
            entry<SensorDetailKey> { key ->
                val detail = viewModel(key = key.metricKey) {
                    SensorDetailViewModel(container, Metric.fromKey(key.metricKey), dashboard.liveSamples)
                }
                val live by dashboard.live.collectAsStateWithLifecycle()
                val monitor by dashboard.monitorStatus.collectAsStateWithLifecycle()
                val privilege by dashboard.privilege.collectAsStateWithLifecycle()
                SensorDetailScreen(detail, live, monitor, privilege, onBack = goBack)
            }
        },
    )
}
