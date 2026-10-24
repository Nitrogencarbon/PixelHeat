package com.app.pixelheat.privilege

import com.app.pixelheat.model.PrivilegeKind

interface PrivilegedShell {
    val kind: PrivilegeKind

    /** Runs [command] with elevated privileges; returns stdout or null on failure. */
    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String?

    companion object {
        const val DEFAULT_TIMEOUT_MS = 4_000L
    }
}
