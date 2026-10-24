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
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            reset()
        }
    }

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

    @Synchronized
    fun reset() {
        bound = false
        service = null
        _connected.value = false
    }

    override suspend fun exec(command: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        val s = service ?: return@withContext null
        try {
            s.exec(command)
        } catch (_: Exception) {
            reset()
            null
        }
    }

    private companion object {
        const val SERVICE_VERSION = 1
    }
}
