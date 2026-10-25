package com.app.pixelheat.ui.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.pixelheat.model.PrivilegeState
import com.app.pixelheat.model.RootStatus
import com.app.pixelheat.model.ShizukuStatus
import com.app.pixelheat.ui.components.ShapeIcon
import com.app.pixelheat.ui.dashboard.DashboardViewModel
import com.app.pixelheat.ui.permissions.AccessStatus
import com.app.pixelheat.ui.permissions.accessStatus
import com.app.pixelheat.ui.permissions.notificationSettingsIntent
import com.app.pixelheat.ui.permissions.unrestrictedBatteryIntent
import com.app.pixelheat.ui.permissions.usageAccessIntent
import com.app.pixelheat.ui.theme.Good
import com.app.pixelheat.ui.theme.Neutral
import com.app.pixelheat.util.formatDayTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Permissions and choices explained on every first run, before the optional root cards and the backup page. */
internal val coreIntroSteps = listOf(
    AccessStep.Notifications,
    AccessStep.Location,
    AccessStep.Weather,
    AccessStep.Battery,
    AccessStep.Usage,
)

/** Backup is always last, so root and Shizuku can be inserted in front of it. */
internal fun introSteps(root: RootStatus, shizuku: ShizukuStatus): List<AccessStep> =
    coreIntroSteps + privilegedIntroSteps(root, shizuku) + AccessStep.Backup

/**
 * Root and Shizuku cards appear only when that prompt can actually be shown.
 * Already active, missing, or outdated access is left to Settings.
 */
internal fun privilegedIntroSteps(root: RootStatus, shizuku: ShizukuStatus): List<AccessStep> = buildList {
    if (root == RootStatus.AVAILABLE || root == RootStatus.DENIED) add(AccessStep.Root)
    if (shizuku == ShizukuStatus.PERMISSION_REQUIRED || shizuku == ShizukuStatus.DENIED) add(AccessStep.Shizuku)
}

internal enum class AccessStep(
    val title: String,
    val allowLabel: String,
    val lines: List<String>,
) {
    Notifications(
        title = "Notifications",
        allowLabel = "Allow notifications",
        lines = listOf(
            "A silent notification shows the phone temperature, the thermal status and when the next check is due, for as long as monitoring runs.",
            "A separate alert explains the cause when the phone heats up.",
            "If Android stops monitoring in the background, a notification asks you to open the app so it can resume.",
            "Without this permission, none of these are shown.",
        ),
    ),
    Location(
        title = "Location",
        allowLabel = "Allow location",
        lines = listOf(
            "Pixel Heat never turns the GPS on. It only reuses the latest location another app already requested.",
            "Precise location tells when GPS is in use, and is a fallback for reading the cellular signal.",
            "The position is rounded to about 1 km. The next card is where you choose whether it can be sent to Open-Meteo. Nothing else is attached to it.",
            "Without location, the outside temperature is estimated from the phone alone.",
        ),
    ),
    Weather(
        title = "Open-Meteo",
        allowLabel = "Allow Open-Meteo",
        lines = listOf(
            "Pixel Heat can send the last known location, rounded to about 1 km, to api.open-meteo.com at most every 30 minutes. That improves the outside-temperature estimate.",
            "Only the coordinates are sent: no account, device ID or measurements. The GPS is never turned on for this.",
            "Without a location permission there is no position to send, and the outside temperature is estimated from the phone alone.",
            "You can turn this off later in Settings, which also deletes the stored weather and coordinates.",
        ),
    ),
    Battery(
        title = "Battery usage",
        allowLabel = "Open app info",
        lines = listOf(
            "Unrestricted battery use keeps the background samples on schedule, even while the phone is idle.",
            "While the app is battery-optimized, Android can postpone those samples, so the history and the heat alerts lag behind.",
            "In App info, open App battery usage and choose Unrestricted.",
            "This exemption does not let the app read any additional data.",
        ),
    ),
    Usage(
        title = "Usage access",
        allowLabel = "Open usage access",
        lines = listOf(
            "Names the app on screen and for how long, and how much mobile data and Wi-Fi each app uses.",
            "Those are matched to the moments the phone warmed up, so the history can point at the app involved.",
            "CPU use per process, and which app opened the camera or requested GPS, need Shizuku or root. They are not part of this permission.",
            "Without it, causes are still diagnosed, but without the names of the apps on screen or using data.",
        ),
    ),
    Root(
        title = "Root",
        allowLabel = "Allow root",
        lines = listOf(
            "Root reads the thermal sensors Android hides, the CPU use of each process, and which apps are keeping GPS on.",
            "When root and Shizuku are both allowed, Pixel Heat uses root and leaves Shizuku idle.",
            "The next step is your phone's root prompt. You can skip it and allow root later in Settings.",
        ),
    ),
    Shizuku(
        title = "Shizuku",
        allowLabel = "Allow Shizuku",
        lines = listOf(
            "Shizuku reads the same hidden sensors and per-app detail as root, through the Shizuku service.",
            "It does not replace the permissions on the previous cards.",
            "You can skip this and allow it later in Settings.",
        ),
    ),
    Backup(
        title = "Backup",
        allowLabel = "Continue",
        lines = listOf(
            "A copy of the measurements can be saved in Downloads/PixelHeat, outside the app, so it stays on the phone if you uninstall.",
            "The file is pixel-heat.json. The previous copy is kept as pixel-heat-previous.json.",
            "Automatic export writes a fresh copy once a day. You can also export once now, or leave this off and change it later in Settings.",
        ),
    ),
}

private fun AccessStep.isGranted(
    status: AccessStatus,
    privilege: PrivilegeState,
    weatherConsent: Boolean,
): Boolean = when (this) {
    AccessStep.Notifications -> status.notificationsGranted && status.notificationsEnabled
    AccessStep.Location -> status.fineLocation
    AccessStep.Battery -> status.unrestrictedBattery
    AccessStep.Usage -> status.usageAccess
    AccessStep.Shizuku -> privilege.shizuku == ShizukuStatus.GRANTED || privilege.shizuku == ShizukuStatus.CONNECTED
    AccessStep.Root -> privilege.root == RootStatus.GRANTED
    AccessStep.Weather -> weatherConsent
    AccessStep.Backup -> false
}

private val AccessStep.icon: ImageVector
    get() = when (this) {
        AccessStep.Notifications -> Icons.Rounded.Notifications
        AccessStep.Location -> Icons.Rounded.LocationOn
        AccessStep.Weather -> Icons.Rounded.Cloud
        AccessStep.Battery -> Icons.Rounded.BatteryAlert
        AccessStep.Usage -> Icons.Rounded.QueryStats
        AccessStep.Root -> Icons.Rounded.Security
        AccessStep.Shizuku -> Icons.Rounded.Adb
        AccessStep.Backup -> Icons.Rounded.Folder
    }

/**
 * One card per permission. The system prompt, settings screen, or root dialog opens only
 * after the button on that card — never when the card itself appears.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccessIntro(viewModel: DashboardViewModel, onFinished: () -> Unit) {
    val context = LocalContext.current
    val privilege by viewModel.privilege.collectAsStateWithLifecycle()
    val weatherConsent by viewModel.weatherConsent.collectAsStateWithLifecycle()
    val autoExport by viewModel.autoExport.collectAsStateWithLifecycle()
    val lastExportAt by viewModel.lastExportAt.collectAsStateWithLifecycle()
    val exportBusy by viewModel.setupBusy.collectAsStateWithLifecycle()
    val exportMessage by viewModel.setupMessage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf(context.accessStatus()) }
    // Saved, so rotation, a theme change or a recreated activity keeps the current card.
    val steps = rememberSaveable(
        saver = listSaver(
            save = { list -> list.map { it.name } },
            restore = { names -> mutableStateListOf<AccessStep>().apply { addAll(names.map(AccessStep::valueOf)) } },
        ),
    ) {
        mutableStateListOf<AccessStep>().apply {
            addAll(introSteps(privilege.root, privilege.shizuku))
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(privilege.root, privilege.shizuku) {
        privilegedIntroSteps(privilege.root, privilege.shizuku).forEach { step ->
            if (step in steps) return@forEach
            val beforeBackup = steps.indexOf(AccessStep.Backup).takeIf { it >= 0 } ?: steps.size
            steps.add(beforeBackup, step)
            if (index >= beforeBackup) index++
        }
    }

    var busy by remember { mutableStateOf(false) }
    var advancing by remember { mutableStateOf(false) }
    var waitingExternal by rememberSaveable { mutableStateOf(false) }
    var shizukuArmed by remember { mutableStateOf(false) }
    var shizukuArmedStatus by remember { mutableStateOf(privilege.shizuku) }

    fun advance() {
        if (advancing) return
        advancing = true
        waitingExternal = false
        shizukuArmed = false
        busy = false
        val next = index + 1
        if (next < steps.size) {
            index = next
            advancing = false
        } else {
            onFinished()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        status = context.accessStatus()
        // Returning from a settings screen or the Shizuku dialog. The first resume of this
        // card does not advance: both flags start false.
        if (waitingExternal || shizukuArmed) advance()
    }
    LaunchedEffect(privilege.shizuku) {
        if (shizukuArmed && privilege.shizuku != shizukuArmedStatus) advance()
    }
    // If Shizuku never shows its dialog, release the buttons instead of leaving them disabled.
    LaunchedEffect(shizukuArmed) {
        if (!shizukuArmed) return@LaunchedEffect
        delay(1_500)
        if (shizukuArmed && lifecycle.currentState == Lifecycle.State.RESUMED) {
            shizukuArmed = false
            busy = false
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = context.accessStatus()
        advance()
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        status = context.accessStatus()
        advance()
    }
    val externalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (waitingExternal) {
            status = context.accessStatus()
            advance()
        }
    }

    val step = steps.getOrNull(index)
    BackHandler(enabled = index > 0 && !busy && step != null) { index-- }

    fun launchExternal(intent: Intent) {
        if (busy || advancing) return
        busy = true
        waitingExternal = true
        try {
            externalLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            waitingExternal = false
            busy = false
        }
    }

    fun allow() {
        val current = steps.getOrNull(index) ?: return
        if (busy || advancing) return
        if (current.isGranted(status, privilege, weatherConsent)) {
            advance()
            return
        }
        when (current) {
            AccessStep.Weather -> {
                viewModel.setWeatherConsent(true)
                advance()
            }
            AccessStep.Backup -> advance()
            AccessStep.Notifications -> if (!status.notificationsGranted) {
                busy = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                launchExternal(context.notificationSettingsIntent())
            }
            AccessStep.Location -> {
                busy = true
                locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
            AccessStep.Battery -> launchExternal(context.unrestrictedBatteryIntent())
            AccessStep.Usage -> launchExternal(context.usageAccessIntent())
            AccessStep.Root -> {
                busy = true
                scope.launch {
                    viewModel.requestRootAccess()
                    advance()
                }
            }
            AccessStep.Shizuku -> {
                busy = true
                shizukuArmedStatus = privilege.shizuku
                shizukuArmed = true
                viewModel.requestShizuku()
            }
        }
    }

    Scaffold { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 8.dp),
        ) {
            Text("Set up Pixel Heat", style = MaterialTheme.typography.titleLargeEmphasized)
            Spacer(Modifier.height(4.dp))
            Text(
                "Each step explains what it is for. Nothing is turned on until you choose it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (steps.isNotEmpty()) {
                Text(
                    "${index.coerceAtMost(steps.lastIndex) + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                StepDots(count = steps.size, selected = index.coerceAtMost(steps.lastIndex))
            }
            Spacer(Modifier.height(16.dp))
            if (step == null) {
                Spacer(Modifier.weight(1f))
            } else {
                val motion = MaterialTheme.motionScheme
                AnimatedContent(
                    targetState = step,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                            .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
                    },
                    label = "accessIntro",
                ) { current ->
                    IntroCard(
                        step = current,
                        granted = current.isGranted(status, privilege, weatherConsent),
                        modifier = Modifier.fillMaxSize(),
                        extra = if (current == AccessStep.Backup) {
                            {
                                BackupSetup(
                                    autoExport = autoExport,
                                    lastExportAt = lastExportAt,
                                    busy = exportBusy,
                                    message = exportMessage,
                                    onAutoExport = viewModel::setAutoExport,
                                    onExport = viewModel::exportBackup,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                val granted = step.isGranted(status, privilege, weatherConsent)
                val last = index == steps.lastIndex
                val configures = step == AccessStep.Backup
                if (!granted && !configures) {
                    TextButton(onClick = { advance() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Not now")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = { allow() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            configures && last -> "Done"
                            configures || granted -> if (last) "Done" else "Continue"
                            step == AccessStep.Notifications && status.notificationsGranted -> "Open notification settings"
                            else -> step.allowLabel
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDots(count: Int, selected: Int) {
    Row(
        Modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val active = MaterialTheme.colorScheme.primary
        val idle = MaterialTheme.colorScheme.surfaceContainerHighest
        repeat(count) { i ->
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (i == selected) active else idle),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IntroCard(
    step: AccessStep,
    granted: Boolean,
    modifier: Modifier = Modifier,
    extra: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            ShapeIcon(step.icon, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                step.title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            if (granted) {
                Spacer(Modifier.height(8.dp))
                Text("Already allowed", style = MaterialTheme.typography.labelLarge, color = Good)
            }
            Spacer(Modifier.height(12.dp))
            step.lines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            extra?.invoke()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackupSetup(
    autoExport: Boolean,
    lastExportAt: Long,
    busy: Boolean,
    message: String?,
    onAutoExport: (Boolean) -> Unit,
    onExport: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(
        if (lastExportAt > 0) "Last export ${formatDayTime(lastExportAt)}" else "Not exported yet",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(16.dp))
    SegmentedListItem(
        checked = autoExport,
        onCheckedChange = onAutoExport,
        enabled = !busy,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = {
            ShapeIcon(Icons.Rounded.Schedule, if (autoExport) MaterialTheme.colorScheme.primary else Neutral)
        },
        supportingContent = { Text("Writes a fresh copy to Downloads once a day, and once when you turn this on") },
        trailingContent = { Switch(checked = autoExport, onCheckedChange = null, enabled = !busy) },
    ) { Text("Automatic export") }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "Working…" else "Export now")
    }
}
