package com.app.pixelheat.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

private fun locale(): Locale = Locale.getDefault()
private fun zone(): ZoneId = ZoneId.systemDefault()

fun Float.fmt(decimals: Int = 1): String = String.format(locale(), "%.${decimals}f", this)
fun Double.fmt(decimals: Int = 1): String = String.format(locale(), "%.${decimals}f", this)

fun formatTemp(c: Float?, decimals: Int = 1): String = c?.takeIf { !it.isNaN() }?.let { "${it.fmt(decimals)} °C" } ?: "—"

fun formatSignedTemp(c: Float): String = (if (c >= 0) "+" else "") + "${c.fmt(1)} °C"

fun formatPower(w: Float): String = "${w.fmt(if (abs(w) < 10) 2 else 1)} W"

fun formatRate(kBps: Float): String =
    if (kBps >= 1024f) "${(kBps / 1024f).fmt(1)} MB/s" else "${kBps.toInt()} KB/s"

fun formatTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale()).withZone(zone()).format(Instant.ofEpochMilli(ms))

fun formatDate(ms: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale()).withZone(zone()).format(Instant.ofEpochMilli(ms))

fun formatDayTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale()).withZone(zone())
        .format(Instant.ofEpochMilli(ms))

fun formatShortDay(ms: Long): String =
    DateTimeFormatter.ofPattern("MMM d", locale()).withZone(zone()).format(Instant.ofEpochMilli(ms))

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return when {
        totalSec < 60 -> "$totalSec s"
        totalSec < 3600 -> "${totalSec / 60} min"
        else -> "${totalSec / 3600} h ${(totalSec % 3600) / 60} min"
    }
}
