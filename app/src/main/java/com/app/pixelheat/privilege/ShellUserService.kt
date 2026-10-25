package com.app.pixelheat.privilege

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Runs inside the Shizuku-spawned process (shell or root uid), not in the app process. */
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String?): String = execTimed(command, TIMEOUT_MS).orEmpty()

    override fun execTimed(command: String?, timeoutMs: Long): String? {
        if (command.isNullOrBlank()) return ""
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val timedOut = AtomicBoolean(false)
        val watchdog = thread(isDaemon = true) {
            try {
                Thread.sleep(timeoutMs.coerceAtLeast(1))
                timedOut.set(true)
                process.destroy()
            } catch (_: InterruptedException) {
            }
        }
        return try {
            val out = process.inputStream.bufferedReader().use { it.readText() }
            if (timedOut.get()) null else out
        } finally {
            watchdog.interrupt()
            process.destroy()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
