package com.app.pixelheat.privilege

import android.content.pm.PackageManager
import com.app.pixelheat.data.prefs.AppPreferences
import com.app.pixelheat.model.PrivilegeState
import com.app.pixelheat.model.RootStatus
import com.app.pixelheat.model.ShizukuStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class PrivilegeManager(
    context: android.content.Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
) {
    private val rootShell = RootShell()
    private val shizukuShell = ShizukuShell(context)

    @Volatile
    private var rootStatus = if (RootShell.isSuAvailable()) RootStatus.AVAILABLE else RootStatus.UNAVAILABLE

    private val _state = MutableStateFlow(PrivilegeState(root = rootStatus))
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { onShizukuChanged() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        shizukuShell.reset()
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == SHIZUKU_REQUEST_CODE) onShizukuChanged()
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        scope.launch { shizukuShell.connected.collect { refresh() } }
        // During the permission cards, root waits for its own card. Reconnect on later launches.
        if (prefs.rootGranted && prefs.accessIntroDone && rootStatus == RootStatus.AVAILABLE) {
            scope.launch { requestRoot() }
        }
        refresh()
    }

    fun requestShizuku() {
        try {
            when {
                !Shizuku.pingBinder() || Shizuku.isPreV11() -> Unit
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> onShizukuChanged()
                else -> Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            }
        } catch (_: Throwable) {
        }
        refresh()
    }

    suspend fun requestRoot(): Boolean {
        val ok = rootShell.open()
        rootStatus = when {
            ok -> RootStatus.GRANTED
            RootShell.isSuAvailable() -> RootStatus.DENIED
            else -> RootStatus.UNAVAILABLE
        }
        prefs.rootGranted = ok
        refresh()
        return ok
    }

    /** Root is preferred: the persistent `su` session is cheaper than a binder round-trip per command. */
    fun activeShell(): PrivilegedShell? {
        if (rootStatus == RootStatus.GRANTED && rootShell.lost) {
            rootStatus = if (RootShell.isSuAvailable()) RootStatus.DENIED else RootStatus.UNAVAILABLE
            prefs.rootGranted = false
            refresh()
        }
        if (rootStatus == RootStatus.GRANTED) return rootShell
        if (shizukuShell.isConnected) return shizukuShell
        // The user service can die on its own; rebind while the permission is still there.
        if (shizukuStatus() == ShizukuStatus.GRANTED) shizukuShell.bind()
        return null
    }

    private fun onShizukuChanged() {
        if (shizukuStatus() == ShizukuStatus.GRANTED) shizukuShell.bind()
        refresh()
    }

    private fun refresh() {
        val shizuku = shizukuStatus()
        _state.value = PrivilegeState(
            root = rootStatus,
            shizuku = shizuku,
            shizukuUid = if (shizuku >= ShizukuStatus.GRANTED) runCatching { Shizuku.getUid() }.getOrNull() else null,
            active = activeShell()?.kind,
        )
    }

    private fun shizukuStatus(): ShizukuStatus = try {
        when {
            !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuStatus.UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                if (shizukuShell.isConnected) ShizukuStatus.CONNECTED else ShizukuStatus.GRANTED
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuStatus.DENIED
            else -> ShizukuStatus.PERMISSION_REQUIRED
        }
    } catch (_: Throwable) {
        ShizukuStatus.NOT_RUNNING
    }

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 4242
    }
}
