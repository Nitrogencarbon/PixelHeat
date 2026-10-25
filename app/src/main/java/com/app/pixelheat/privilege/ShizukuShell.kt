package com.app.pixelheat.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.app.pixelheat.model.PrivilegeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class ShizukuShell(context: Context) : PrivilegedShell {
    override val kind = PrivilegeKind.SHIZUKU

    @Volatile
    private var service: IShellService? = null
    private var bound = false

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    val isConnected: Boolean get() = service != null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShellUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shell")
        .version(SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IShellService.Stub.asInterface(binder)
                _connected.value = true
            } else {
                reset()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            reset()
        }
    }

    /** Safe to call often: does nothing while a binding is already in place or pending. */
    @Synchronized
    fun bind() {
        if (bound) return
        bound = try {
            Shizuku.bindUserService(args, connection)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Drops the binding so the next [bind] starts a fresh service process. */
    @Synchronized
    fun reset() {
        val wasBound = bound
        bound = false
        service = null
        _connected.value = false
        if (wasBound) runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    override suspend fun exec(command: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        val s = service ?: return@withContext null
        try {
            s.execTimed(command, timeoutMs)
        } catch (_: Exception) {
            reset()
            null
        }
    }

    private companion object {
        /** Bumped when [IShellService] changes, so Shizuku replaces a service process left from an older build. */
        const val SERVICE_VERSION = 2
    }
}
