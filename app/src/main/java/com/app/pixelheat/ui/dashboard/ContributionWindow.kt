package com.app.pixelheat.ui.dashboard

/** How far back the heat-contribution estimates look. [SINCE_INSTALL] starts at the app's install time. */
enum class ContributionWindow(val label: String, val durationMs: Long?) {
    M15("15 min", 15 * 60_000L),
    M45("45 min", 45 * 60_000L),
    H3("3 h", 3 * 60 * 60_000L),
    H12("12 h", 12 * 60 * 60_000L),
    H24("24 h", 24 * 60 * 60_000L),
    D7("7 d", 7 * 24 * 60 * 60_000L),
    SINCE_INSTALL("Since install", null),
    ;

    fun startAt(now: Long, installedAt: Long): Long = durationMs?.let { now - it } ?: installedAt.coerceAtMost(now)
}
