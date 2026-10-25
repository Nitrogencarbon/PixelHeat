package com.app.pixelheat.privilege

import com.app.pixelheat.model.PrivilegeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Keeps a single interactive `su` process alive so frequent reads don't pay the cost
 * (and the root-manager toast) of spawning a new process each time.
 */
class RootShell : PrivilegedShell {
    override val kind = PrivilegeKind.ROOT

    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    suspend fun open(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { ensureStarted().also { if (it) lost = false } }
    }

    /** Set when the shell could not be restarted after being granted, e.g. root was revoked. */
    @Volatile
    var lost = false
        private set

    override suspend fun exec(command: String, timeoutMs: Long): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureStarted()) {
                lost = true
                null
            } else {
                execute(command, timeoutMs)
            }
        }
    }

    private fun ensureStarted(): Boolean {
        if (process?.isAlive == true) return true
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            process = p
            writer = p.outputStream.bufferedWriter()
            reader = p.inputStream.bufferedReader()
            // The root manager may show a consent dialog: wait for the user.
            val out = execute("id", GRANT_TIMEOUT_MS)
            if (out?.contains("uid=0") == true) true else {
                close()
                false
            }
        } catch (_: IOException) {
            close()
            false
        }
    }

    private fun execute(command: String, timeoutMs: Long): String? {
        val p = process ?: return null
        val w = writer ?: return null
        val r = reader ?: return null
        val marker = "__PH_END_${System.nanoTime()}__"
        // Whoever flips this first owns the outcome: a late watchdog must not kill the next command.
        val settled = AtomicBoolean(false)
        val watchdog = thread(isDaemon = true, name = "root-shell-watchdog") {
            try {
                Thread.sleep(timeoutMs)
                if (settled.compareAndSet(false, true)) p.destroy()
            } catch (_: InterruptedException) {
            }
        }
        val out = try {
            w.write("$command 2>/dev/null\necho $marker\n")
            w.flush()
            readUntil(r, marker)
        } catch (_: IOException) {
            null
        }
        val timedOut = !settled.compareAndSet(false, true)
        watchdog.interrupt()
        if (out == null || timedOut) {
            close()
            return null
        }
        return out
    }

    private fun readUntil(r: BufferedReader, marker: String): String? {
        val out = StringBuilder()
        while (true) {
            val line = r.readLine() ?: return null
            if (line.endsWith(marker)) {
                val tail = line.removeSuffix(marker)
                if (tail.isNotEmpty()) out.appendLine(tail)
                return out.toString()
            }
            out.appendLine(line)
        }
    }

    private fun close() {
        runCatching { writer?.close() }
        process?.destroy()
        process = null
        writer = null
        reader = null
    }

    companion object {
        private const val GRANT_TIMEOUT_MS = 20_000L
        private val KNOWN_DIRS = listOf(
            "/system/bin", "/system/xbin", "/sbin", "/su/bin",
            "/debug_ramdisk", "/data/local/xbin", "/data/local/bin",
        )

        fun isSuAvailable(): Boolean {
            val path = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
            return (path + KNOWN_DIRS).distinct().any { File(it, "su").exists() }
        }
    }
}
