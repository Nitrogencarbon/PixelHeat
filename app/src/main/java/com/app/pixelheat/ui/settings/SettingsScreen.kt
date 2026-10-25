package com.app.pixelheat.ui.settings

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.app.pixelheat.ui.PredictiveBackRegistration
import com.app.pixelheat.ui.components.ConnectedSelector
import com.app.pixelheat.ui.components.SectionCard
import com.app.pixelheat.ui.permissions.accessStatus
import com.app.pixelheat.ui.permissions.openAppDetails
import com.app.pixelheat.ui.permissions.openNotificationSettings
import com.app.pixelheat.ui.permissions.openUsageAccessSettings
import com.app.pixelheat.ui.components.ShapeIcon
import com.app.pixelheat.ui.components.StatusListItem
import com.app.pixelheat.ui.theme.Good
import com.app.pixelheat.ui.theme.Neutral
import com.app.pixelheat.ui.theme.isDarkTheme
import com.app.pixelheat.util.formatDayTime
import com.app.pixelheat.util.formatDuration
import com.app.pixelheat.util.formatTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    PredictiveBackRegistration()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val privilege by viewModel.privilege.collectAsStateWithLifecycle()
    val monitor by viewModel.monitorStatus.collectAsStateWithLifecycle()
    val monitoringEnabled by viewModel.monitoringEnabled.collectAsStateWithLifecycle()
    val weatherConsent by viewModel.weatherConsent.collectAsStateWithLifecycle()
    val autoExport by viewModel.autoExport.collectAsStateWithLifecycle()
    val lastExportAt by viewModel.lastExportAt.collectAsStateWithLifecycle()
    val dataBusy by viewModel.dataBusy.collectAsStateWithLifecycle()
    val dataMessage by viewModel.dataMessage.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.stageImport(uri)
    }

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
            item("weather") { WeatherSection(weatherConsent, viewModel::setWeatherConsent) }
            item("data") {
                DataSection(
                    autoExport = autoExport,
                    lastExportAt = lastExportAt,
                    busy = dataBusy,
                    message = dataMessage,
                    pendingImport = pendingImport,
                    onAutoExport = viewModel::setAutoExport,
                    onExport = viewModel::exportNow,
                    onPickImport = { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onConfirmImport = viewModel::confirmImport,
                    onCancelImport = viewModel::cancelImport,
                    onReset = viewModel::resetCollected,
                    sampleCount = viewModel::collectedSampleCount,
                )
            }
            item("permissions") { PermissionsSection() }
            item("about") {
                SectionCard(title = "About", icon = Icons.Rounded.Info) {
                    AboutRow("Version", viewModel.versionName)
                    AboutRow("Device", "${viewModel.identity.manufacturer} ${viewModel.identity.model} (${viewModel.identity.codename})")
                    AboutRow("Android", "${viewModel.identity.androidVersion} · API ${viewModel.identity.sdkInt}")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Measurements are stored on this device. Your approximate location is sent to Open-Meteo only if you allow it under Local weather; location is only read passively, never actively requested.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppearanceSection(
    appearance: Appearance,
    onThemeMode: (ThemeMode) -> Unit,
    onPureBlack: (Boolean) -> Unit,
) {
    val dark = appearance.isDarkTheme()
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
        SwitchItem(
            index = 0,
            count = 1,
            icon = Icons.Rounded.Contrast,
            title = "Pure black",
            supporting = if (dark) "Black backgrounds in dark theme, easier on OLED" else "Available with the dark theme",
            checked = appearance.pureBlack,
            onChange = onPureBlack,
            enabled = dark,
        )
    }
}

/**
 * Consent for the only network exchange the app makes. Turning it on goes through a dialog that says
 * exactly what is sent; turning it off is immediate.
 */
@Composable
private fun WeatherSection(granted: Boolean, onChange: (Boolean) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    var showWeatherHelp by remember { mutableStateOf(false) }
    SectionCard(
        title = "Local weather",
        icon = Icons.Rounded.Cloud,
        trailing = { HelpButton("How local weather is used") { showWeatherHelp = true } },
    ) {
        SwitchItem(
            index = 0,
            count = 1,
            icon = Icons.Rounded.Public,
            title = "Connect to Open-Meteo",
            supporting = if (granted) {
                "Allowed · approximate location (about 1 km) is sent to fetch the outside temperature"
            } else {
                "Off · nothing is sent; the outside temperature is estimated from the phone only"
            },
            checked = granted,
            onChange = { on -> if (on) confirming = true else onChange(false) },
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(Icons.Rounded.Public, contentDescription = null) },
            title = { Text("Share location with Open-Meteo?") },
            text = {
                Text(
                    "To improve the outside-temperature estimate, Pixel Heat will send your last known location, " +
                        "rounded to about 1 km, to api.open-meteo.com at most every 30 minutes. " +
                        "Only the coordinates are sent: no account, device ID or measurements. " +
                        "You can turn this off at any time, which also deletes the stored weather and coordinates.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onChange(true) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Not now") }
            },
        )
    }
    if (showWeatherHelp) WeatherHelpDialog { showWeatherHelp = false }
}

/** Why the outside temperature is estimated, and what Open-Meteo receives. */
@Composable
private fun WeatherHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Cloud, contentDescription = null) },
        title = { Text("How local weather is used") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AccessTier(
                    title = "What it is for",
                    active = false,
                    lines = listOf(
                        "Pixel phones have no outdoor thermometer. The outside temperature is estimated from the phone: battery and skin warmth, light, and whether you seem to be outside.",
                        "Open-Meteo adds the outdoor temperature, sunlight, wind and whether it is day or night. That reading counts more when the phone seems to be outdoors, and less indoors.",
                    ),
                )
                Spacer(Modifier.height(16.dp))
                AccessTier(
                    title = "What is sent",
                    active = false,
                    lines = listOf(
                        "Only while this switch is on, and only if a location is already available. Pixel Heat never turns the GPS on. It reuses the latest position another app requested.",
                        "That position is rounded to about 1 km and sent to api.open-meteo.com at most every 30 minutes.",
                        "Only the coordinates go out: no account, device ID or measurements.",
                        "Without a location permission there is no position to send, and the outside temperature is estimated from the phone alone.",
                    ),
                )
                Spacer(Modifier.height(16.dp))
                AccessTier(
                    title = "Turning it off",
                    active = false,
                    lines = listOf(
                        "Nothing is sent. The outside temperature goes back to the phone-only estimate.",
                        "The stored weather and the coordinates it was fetched for are deleted.",
                        "A backup in Downloads never includes those coordinates.",
                    ),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SwitchItem(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = { ShapeIcon(icon, if (checked && enabled) MaterialTheme.colorScheme.primary else Neutral) },
        supportingContent = { Text(supporting) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    ) { Text(title) }
}

private const val RESET_WORD = "RESET"

@Composable
private fun DataSection(
    autoExport: Boolean,
    lastExportAt: Long,
    busy: Boolean,
    message: String?,
    pendingImport: ImportPreview?,
    onAutoExport: (Boolean) -> Unit,
    onExport: () -> Unit,
    onPickImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onReset: () -> Unit,
    sampleCount: suspend () -> Int,
) {
    var confirmingReset by remember { mutableStateOf(false) }
    var showDataHelp by remember { mutableStateOf(false) }
    SectionCard(
        title = "Data",
        icon = Icons.Rounded.Folder,
        trailing = { HelpButton("How measurements are kept") { showDataHelp = true } },
    ) {
        Text(
            if (lastExportAt > 0) "Last export ${formatDayTime(lastExportAt)}" else "Not exported yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Saved to Downloads/PixelHeat. The file stays on the phone if you uninstall the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        SwitchItem(
            index = 0,
            count = 1,
            icon = Icons.Rounded.Schedule,
            title = "Automatic export",
            supporting = "Writes a fresh copy to Downloads once a day",
            checked = autoExport,
            onChange = onAutoExport,
            enabled = !busy,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Working…" else "Export now")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Import a backup")
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { confirmingReset = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (busy) 0.3f else 0.7f)),
        ) { Text("Delete collected data") }
    }
    if (showDataHelp) DataHelpDialog { showDataHelp = false }
    pendingImport?.let { preview ->
        AlertDialog(
            onDismissRequest = onCancelImport,
            icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
            title = { Text("Replace the data on this phone?") },
            text = {
                Text(
                    "This backup has ${preview.samples} samples, exported ${formatDayTime(preview.exportedAt)}. " +
                        "Importing it deletes the measurements stored now and puts the backup in their place. " +
                        "Settings stay as they are.",
                )
            },
            confirmButton = { TextButton(onClick = onConfirmImport) { Text("Replace") } },
            dismissButton = { TextButton(onClick = onCancelImport) { Text("Cancel") } },
        )
    }
    if (confirmingReset) {
        ResetDialog(
            sampleCount = sampleCount,
            onDismiss = { confirmingReset = false },
            onConfirm = {
                confirmingReset = false
                onReset()
            },
        )
    }
}

/** The delete button stays disabled until the word [RESET_WORD] is typed, so a stray tap cannot empty the history. */
@Composable
private fun ResetDialog(sampleCount: suspend () -> Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var count by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { count = runCatching { sampleCount() }.getOrNull() }
    val matches = typed.trim() == RESET_WORD
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete collected data?") },
        text = {
            Column {
                Text(
                    buildString {
                        append("This deletes ")
                        append(count?.let { "$it samples" } ?: "every sample")
                        append(", the hardware events, the per-app history and the lifetime averages, ")
                        append("and resets the outside-temperature estimate. Settings, permissions and the backup file stay.")
                    },
                )
                Spacer(Modifier.height(16.dp))
                Text("Type $RESET_WORD to confirm.", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = matches) {
                Text("Delete data", color = if (matches) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PermissionsSection() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var refresh by remember { mutableIntStateOf(0) }
    val state = remember(refresh) { context.accessStatus() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    fun rationale(permission: String) = activity?.shouldShowRequestPermissionRationale(permission) == true

    // The system dialog is skipped once a permission is denied for good. Without a rationale both
    // before and after the request, no dialog was shown, so send the user to the settings instead.
    // A denial in the dialog itself leaves the user where they are.
    var rationaleBefore by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        val dialogSkipped = !rationaleBefore && !rationale(Manifest.permission.POST_NOTIFICATIONS)
        if (!granted && dialogSkipped) context.openNotificationSettings()
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh++
        val dialogSkipped = !rationaleBefore && !rationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true && dialogSkipped) context.openAppDetails()
    }

    var showHelp by remember { mutableStateOf(false) }
    SectionCard(
        title = "Permissions",
        icon = Icons.Rounded.VerifiedUser,
        trailing = { HelpButton("How each permission is used") { showHelp = true } },
    ) {
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
                    !state.notificationsGranted -> {
                        {
                            rationaleBefore = rationale(Manifest.permission.POST_NOTIFICATIONS)
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
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
                        rationaleBefore = rationale(Manifest.permission.ACCESS_FINE_LOCATION)
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
    if (showHelp) PermissionHelpDialog { showHelp = false }
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
    var showReadable by remember { mutableStateOf(false) }
    SectionCard(
        title = "Access & monitoring",
        icon = Icons.Rounded.Shield,
        trailing = { HelpButton("What can be read") { showReadable = true } },
    ) {
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
    if (showReadable) ReadableDataDialog(privilege) { showReadable = false }
}

private const val ACCESS_ITEMS = 4

@Composable
private fun HelpButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            Icons.AutoMirrored.Rounded.HelpOutline,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** How long samples stay at full detail, and what an older hour is reduced to. */
@Composable
private fun DataHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
        title = { Text("How measurements are kept") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AccessTier(
                    title = "The last 30 days",
                    active = false,
                    lines = listOf(
                        "Every sample stays as it was recorded, including checks a few seconds apart while the phone is hot.",
                        "Charts for the last 7 days always use that detail.",
                    ),
                )
                Spacer(Modifier.height(16.dp))
                AccessTier(
                    title = "Older than 30 days",
                    active = false,
                    lines = listOf(
                        "Each hour becomes one row, so the history can continue without a reading every few seconds.",
                        "Temperatures, power and charge or discharge speed are averages weighted by how long each reading covered.",
                        "Screen, charging, GPS, camera and audio keep the share of the hour they were on. The hottest temperature of that hour is kept.",
                        "App use in that hour is added up for time on screen, mobile data and Wi-Fi, and averaged for CPU, GPS and camera.",
                        "Changes such as plugging in or opening the camera are reduced to the strongest one of each kind in that hour.",
                    ),
                )
                Spacer(Modifier.height(16.dp))
                AccessTier(
                    title = "What stays exact",
                    active = false,
                    lines = listOf(
                        "The averages since install are stored on their own. Folding an old hour does not change them.",
                        "Since install can look past 30 days, with one point per hour.",
                        "A backup in Downloads keeps whatever is stored, hourly averages included, and stays on the phone if you uninstall the app.",
                        "A long stretch of old detail is folded about a week of hours at a time, and finishes within about a day. Hours already removed by an older version of the app are not restored.",
                    ),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** What each entry under Permissions is used for, and what stops working without it. */
@Composable
private fun PermissionHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
        title = { Text("How permissions are used") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AccessTier(
                    title = "Notifications",
                    active = false,
                    lines = listOf(
                        "A silent, ongoing notification shows the phone temperature, the thermal status and when the next check is due, for as long as monitoring runs.",
                        "A separate, high-priority alert explains the cause when the phone heats up.",
                        "If Android stops monitoring in the background, a notification asks you to open the app so it can resume.",
                        "Without it, none of these are delivered.",
                    ),
                )
                Spacer(Modifier.height(16.dp))
                AccessTier(
                    title = "Location",
                    active = false,
                    lines = listOf(
                        "Pixel Heat never turns the GPS on. It only reuses a location another app already requested, and only the most recent one.",
                        "Precise location is needed to tell when GPS is in use, and as a fallback to read the cellular signal strength.",
                        "The position is rounded to about 1 km and sent to Open-Meteo only if you allow it under Local weather. Nothing else is attached to it.",
                        "With approximate location only, the weather estimate can still work, but GPS use and the cellular signal fallback cannot.",
                        "Without any location, the outside temperature is estimated from the phone alone.",
                    ),
                )
                Spacer(Modifier.height(16.dp))
                AccessTier(
                    title = "Battery usage",
                    active = false,
                    lines = listOf(
                        "Unrestricted battery use keeps the background samples on their schedule, even while the phone is idle.",
                        "While the app is battery-optimized, Android can postpone those samples, so the history and the heat alerts lag behind.",
                        "This exemption does not let the app read any additional data.",
                    ),
                )
                Spacer(Modifier.height(16.dp))
                AccessTier(
                    title = "Usage access",
                    active = false,
                    lines = listOf(
                        "Names the app on screen and for how long, and how much mobile data and Wi-Fi each app uses.",
                        "Those are matched to the moments the phone warmed up, so Insights and the overheating history can point at the app involved.",
                        "CPU use per process, and which app opened the camera or requested GPS, are not part of this permission: they need Shizuku or root.",
                        "Without it, causes are still diagnosed, but without the names of the apps on screen or using data.",
                    ),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ReadableDataDialog(privilege: PrivilegeState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Visibility, contentDescription = null) },
        title = { Text("What can be read") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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