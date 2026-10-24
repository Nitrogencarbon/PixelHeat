package com.app.pixelheat.ui.settings

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.pixelheat.model.Appearance
import com.app.pixelheat.model.MonitorStatus
import com.app.pixelheat.model.PrivilegeKind
import com.app.pixelheat.model.PrivilegeState
import com.app.pixelheat.model.RootStatus
import com.app.pixelheat.model.ShizukuStatus
import com.app.pixelheat.model.ThemeMode
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.ui.components.ConnectedSelector
import com.app.pixelheat.ui.components.SectionCard
import com.app.pixelheat.ui.components.ShapeIcon
import com.app.pixelheat.ui.components.StatusListItem
import com.app.pixelheat.ui.theme.Good
import com.app.pixelheat.ui.theme.Neutral
import com.app.pixelheat.ui.theme.SeedColors
import com.app.pixelheat.util.formatDuration
import com.app.pixelheat.util.formatTime

private data class PermissionState(
    val notificationsGranted: Boolean,
    val notificationsEnabled: Boolean,
    val fineLocation: Boolean,
    val coarseLocation: Boolean,
    val unrestrictedBattery: Boolean,
    val usageAccess: Boolean,
) {
    companion object {
        fun read(context: Context): PermissionState {
            fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            val appOps = context.getSystemService(AppOpsManager::class.java)
            return PermissionState(
                usageAccess = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                ) == AppOpsManager.MODE_ALLOWED,
                notificationsGranted = has(Manifest.permission.POST_NOTIFICATIONS),
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                fineLocation = has(Manifest.permission.ACCESS_FINE_LOCATION),
                coarseLocation = has(Manifest.permission.ACCESS_COARSE_LOCATION),
                unrestrictedBattery = context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName),
            )
        }
    }
}

private fun Context.openAppDetails() = startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

private fun Context.openUsageAccessSettings() {
    val forApp = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(forApp) }.onFailure {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun Context.openNotificationSettings() = startActivity(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val privilege by viewModel.privilege.collectAsStateWithLifecycle()
    val monitor by viewModel.monitorStatus.collectAsStateWithLifecycle()
    val monitoringEnabled by viewModel.monitoringEnabled.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("appearance") {
                AppearanceSection(
                    appearance = appearance,
                    onThemeMode = viewModel::setThemeMode,
                    onDynamicColor = viewModel::setDynamicColor,
                    onSeed = viewModel::setSeed,
                    onPureBlack = viewModel::setPureBlack,
                )
            }
            item("access") {
                AccessSection(
                    privilege = privilege,
                    monitor = monitor,
                    monitoringEnabled = monitoringEnabled,
                    onToggleMonitoring = viewModel::setMonitoring,
                    onRequestRoot = viewModel::requestRoot,
                    onRequestShizuku = viewModel::requestShizuku,
                )
            }
            item("readable") { ReadableDataSection(privilege) }
            item("permissions") { PermissionsSection() }
            item("about") {
                SectionCard(title = "About", icon = Icons.Rounded.Info) {
                    AboutRow("Version", viewModel.versionName)
                    AboutRow("Device", "${viewModel.identity.manufacturer} ${viewModel.identity.model} (${viewModel.identity.codename})")
                    AboutRow("Android", "${viewModel.identity.androidVersion} · API ${viewModel.identity.sdkInt}")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "All measurements stay on this device. Location is only used passively to estimate the outside temperature.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(
    appearance: Appearance,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onSeed: (Int) -> Unit,
    onPureBlack: (Boolean) -> Unit,
) {
    SectionCard(title = "Appearance", icon = Icons.Rounded.Palette) {
        Label("Theme")
        ConnectedSelector(
            options = ThemeMode.entries,
            selected = appearance.themeMode,
            label = { it.label },
            onSelect = onThemeMode,
            icon = {
                when (it) {
                    ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                    ThemeMode.LIGHT -> Icons.Rounded.LightMode
                    ThemeMode.DARK -> Icons.Rounded.DarkMode
                }
            },
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            SwitchItem(
                index = 0,
                icon = Icons.Rounded.Wallpaper,
                title = "Wallpaper colors",
                supporting = "Use the Material You palette from your wallpaper",
                checked = appearance.dynamicColor,
                onChange = onDynamicColor,
            )
            SwitchItem(
                index = 1,
                icon = Icons.Rounded.Contrast,
                title = "Pure black",
                supporting = "Black backgrounds in dark theme, easier on OLED",
                checked = appearance.pureBlack,
                onChange = onPureBlack,
            )
        }
        Spacer(Modifier.height(20.dp))
        Label(if (appearance.dynamicColor) "Accent color · pick one to stop using wallpaper colors" else "Accent color")
        FlowRow(
            modifier = Modifier.alpha(if (appearance.dynamicColor) 0.6f else 1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SeedColors.forEach { seed ->
                Swatch(
                    name = seed.name,
                    color = Color(seed.argb),
                    selected = !appearance.dynamicColor && appearance.seedArgb == seed.argb,
                    onClick = { onSeed(seed.argb) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Swatch(name: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(52.dp)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
        shape = if (selected) MaterialShapes.Cookie9Sided.toShape() else CircleShape,
        color = color,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                )
            }
        }
    }
}

@Composable
private fun SwitchItem(
    index: Int,
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = 2),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = { ShapeIcon(icon, if (checked) MaterialTheme.colorScheme.primary else Neutral) },
        supportingContent = { Text(supporting) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(title) }
}

@Composable
private fun PermissionsSection() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var refresh by remember { mutableIntStateOf(0) }
    val state = remember(refresh) { PermissionState.read(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        val permanentlyDenied = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
        if (!granted && permanentlyDenied) context.openNotificationSettings()
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh++
        val permanentlyDenied = activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == false
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true && permanentlyDenied) context.openAppDetails()
    }

    SectionCard(title = "Permissions", icon = Icons.Rounded.VerifiedUser) {
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            val notificationsOk = state.notificationsGranted && state.notificationsEnabled
            StatusListItem(
                index = 0,
                count = PERMISSION_ITEMS,
                icon = Icons.Rounded.Notifications,
                title = "Notifications",
                status = if (notificationsOk) "Allowed · heat alerts and monitoring status" else "Not allowed · you won't get heat alerts",
                color = if (notificationsOk) Good else MaterialTheme.colorScheme.error,
                actionLabel = "Allow",
                action = when {
                    notificationsOk -> null
                    !state.notificationsGranted -> { { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
                    else -> { { context.openNotificationSettings() } }
                },
            )
            StatusListItem(
                index = 1,
                count = PERMISSION_ITEMS,
                icon = Icons.Rounded.LocationOn,
                title = "Location",
                status = when {
                    state.fineLocation -> "Precise · detects GPS use and local weather"
                    state.coarseLocation -> "Approximate only · GPS use can't be detected"
                    else -> "Not allowed · outside temperature is less accurate"
                },
                color = when {
                    state.fineLocation -> Good
                    state.coarseLocation -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
                actionLabel = "Allow",
                action = if (state.fineLocation) {
                    null
                } else {
                    {
                        locationLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    }
                },
            )
            StatusListItem(
                index = 2,
                count = PERMISSION_ITEMS,
                icon = Icons.Rounded.BatteryAlert,
                title = "Battery usage",
                status = if (state.unrestrictedBattery) "Unrestricted · samples are never delayed" else "Optimized · background samples may be delayed",
                color = if (state.unrestrictedBattery) Good else MaterialTheme.colorScheme.tertiary,
                actionLabel = "Change",
                action = if (state.unrestrictedBattery) null else { { context.openAppDetails() } },
            )
            StatusListItem(
                index = 3,
                count = PERMISSION_ITEMS,
                icon = Icons.Rounded.QueryStats,
                title = "Usage access",
                status = if (state.usageAccess) {
                    "Allowed · names the apps behind screen, data and CPU heat"
                } else {
                    "Not allowed · causes are shown without app names"
                },
                color = if (state.usageAccess) Good else MaterialTheme.colorScheme.tertiary,
                actionLabel = "Allow",
                action = if (state.usageAccess) null else { { context.openUsageAccessSettings() } },
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { context.openAppDetails() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Open system app info")
        }
    }
}

private const val PERMISSION_ITEMS = 4

@Composable
private fun AccessSection(
    privilege: PrivilegeState,
    monitor: MonitorStatus?,
    monitoringEnabled: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    onRequestRoot: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    SectionCard(title = "Access & monitoring", icon = Icons.Rounded.Shield) {
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            StatusListItem(
                index = 0,
                count = ACCESS_ITEMS,
                icon = Icons.Rounded.Security,
                title = "Root",
                status = privilege.root.label,
                color = when (privilege.root) {
                    RootStatus.GRANTED -> Good
                    RootStatus.AVAILABLE -> MaterialTheme.colorScheme.tertiary
                    RootStatus.DENIED -> MaterialTheme.colorScheme.error
                    RootStatus.UNAVAILABLE -> Neutral
                },
                action = if (privilege.root == RootStatus.AVAILABLE || privilege.root == RootStatus.DENIED) onRequestRoot else null,
            )
            StatusListItem(
                index = 1,
                count = ACCESS_ITEMS,
                icon = Icons.Rounded.Adb,
                title = "Shizuku",
                status = privilege.shizuku.label + (privilege.shizukuUid?.let { if (it == 0) " (root)" else " (adb)" }.orEmpty()),
                color = when (privilege.shizuku) {
                    ShizukuStatus.CONNECTED -> Good
                    ShizukuStatus.GRANTED, ShizukuStatus.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.tertiary
                    ShizukuStatus.DENIED, ShizukuStatus.UNSUPPORTED -> MaterialTheme.colorScheme.error
                    ShizukuStatus.NOT_RUNNING -> Neutral
                },
                action = when (privilege.shizuku) {
                    ShizukuStatus.PERMISSION_REQUIRED, ShizukuStatus.DENIED, ShizukuStatus.GRANTED -> onRequestShizuku
                    else -> null
                },
            )
            StatusListItem(
                index = 2,
                count = ACCESS_ITEMS,
                icon = Icons.Rounded.Sensors,
                title = "Thermal data",
                status = privilege.active?.let { "Full HAL access via ${it.label}" } ?: "Standard Android APIs",
                color = if (privilege.active != null) Good else Neutral,
                action = null,
            )
            SegmentedListItem(
                checked = monitoringEnabled,
                onCheckedChange = onToggleMonitoring,
                shapes = ListItemDefaults.segmentedShapes(index = 3, count = ACCESS_ITEMS),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                leadingContent = { ShapeIcon(Icons.Rounded.History, if (monitoringEnabled) Good else Neutral) },
                supportingContent = {
                    Text(
                        when {
                            !monitoringEnabled -> "Off"
                            monitor == null -> "Starting…"
                            else -> "${monitor.level.label} · every ${formatDuration(monitor.level.intervalMs)} · " +
                                "${ThermalStatus.label(monitor.thermalStatus)} · last ${formatTime(monitor.lastSampleAt)}"
                        },
                    )
                },
                trailingContent = { Switch(checked = monitoringEnabled, onCheckedChange = null) },
            ) { Text("Background monitoring") }
        }
    }
}

private const val ACCESS_ITEMS = 4

@Composable
private fun ReadableDataSection(privilege: PrivilegeState) {
    SectionCard(title = "What can be read", icon = Icons.Rounded.Visibility) {
        Text(
            "Currently ${privilege.active?.label ?: "standard permissions only"}.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The phone score uses skin temperature when that sensor can be read, and battery temperature otherwise. The home score, the notification and the long-term average all follow it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        AccessTier(
            title = "Standard permissions",
            active = privilege.active == null,
            lines = listOf(
                "Battery temperature, charge, power, health and cycle count.",
                "Android thermal status and headroom.",
                "Screen, brightness, network, Wi-Fi and mobile traffic, and signal strength.",
                "Whether GPS, the camera or audio is in use. Location is still required for the outside-temperature estimate and for cellular signal.",
                "With usage access: which app is on screen, and how much mobile data and Wi-Fi each app uses.",
                "CPU, GPU, skin, modem and USB temperatures are usually blocked on Pixel phones, along with their thermal limits, CPU frequency and hardware specifications.",
            ),
        )
        Spacer(Modifier.height(16.dp))
        AccessTier(
            title = "Shizuku",
            active = privilege.active == PrivilegeKind.SHIZUKU,
            lines = listOf(
                "Everything above, plus the thermal HAL: every zone temperature and the throttling limits.",
                "CPU use per app and per system process, and which apps are keeping GPS on.",
                "CPU frequency, and specifications such as battery capacity and CPU and GPU frequency ranges, when the system files are readable.",
                "Started from a computer, Shizuku runs as the shell user. A few system files can still be denied. The thermal HAL read is the one that matters, and it works.",
            ),
        )
        Spacer(Modifier.height(16.dp))
        AccessTier(
            title = "Root",
            active = privilege.active == PrivilegeKind.ROOT,
            lines = listOf(
                "The same sensor and specification reads as Shizuku, through a root shell.",
                "When both are allowed, Pixel Heat uses root and leaves Shizuku idle.",
            ),
        )
    }
}

@Composable
private fun AccessTier(title: String, active: Boolean, lines: List<String>) {
    Text(
        if (active) "$title · in use" else title,
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(6.dp))
    lines.forEach { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
