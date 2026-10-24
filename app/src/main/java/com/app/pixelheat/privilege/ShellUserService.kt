package com.app.pixelheat.privilege

import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Runs inside the Shizuku-spawned process (shell or root uid), not in the app process. */
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String?): String {
        if (command.isNullOrBlank()) return ""
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val watchdog = thread(isDaemon = true) {
            try {
                Thread.sleep(TIMEOUT_MS)
                process.destroy()
            } catch (_: InterruptedException) {
            }
        }
        return try {
            process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            watchdog.interrupt()
            process.destroy()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
