package com.app.pixelheat.diagnostics

import com.app.pixelheat.data.db.HardwareEventEntity

enum class HeatCause(val label: String, val advice: String) {
    CHARGING(
        "Charging",
        "Use a lower-wattage charger or enable Adaptive Charging; avoid heavy use while charging and remove the case.",
    ),
    CPU_LOAD(
        "CPU/GPU load",
        "Close games or heavy apps, check Settings › Battery for the top consumers and turn on Battery Saver.",
    ),
    SCREEN(
        "Display",
        "Lower the brightness or enable Adaptive brightness; drop the refresh rate to 60 Hz.",
    ),
    NETWORK(
        "Network / modem",
        "The modem runs hot on weak signal: prefer Wi-Fi, pause large uploads/downloads or switch from 5G to LTE.",
    ),
    GPS(
        "GPS",
        "Revoke precise location from apps that don't need it and close navigation when you're done.",
    ),
    CAMERA(
        "Camera",
        "4K/60 fps recording generates a lot of heat: take breaks, lower the resolution or keep the phone in the shade.",
    ),
    SUNLIGHT(
        "Sun exposure",
        "Move the phone into the shade: direct sunlight heats the chassis more than any internal component.",
    ),
    AMBIENT(
        "Hot environment",
        "A hot environment limits heat dissipation: avoid pockets, cars in the sun or soft surfaces that insulate the phone.",
    ),
}

enum class DiagnosisStatus(val label: String) {
    INSUFFICIENT_DATA("Collecting data"),
    NORMAL("Normal"),
    COOLING("Cooling down"),
    HEATING("Heating up"),
    HOT("Hot"),
    CRITICAL("Critical"),
}

data class CauseContribution(
    val cause: HeatCause,
    val label: String,
    val sharePct: Float,
    val avgWatts: Float,
    val correlation: Float?,
    val activePct: Float,
    /** Temperature change accumulated while this cause was active. */
    val deltaWhileActiveC: Float,
    /** Apps driving this cause, largest share first. Empty when no per-app data was recorded. */
    val apps: List<AppShare> = emptyList(),
)

/**
 * An app's part in a heat cause. [sharePct] splits the cause between apps by their use *during* heating;
 * [peakPct] is how much of the heating happened while the app was active at all.
 */
data class AppShare(val packageName: String, val label: String, val sharePct: Float, val peakPct: Float = 0f)

data class EventImpact(
    val event: HardwareEventEntity,
    val deltaC: Float,
    val minutes: Float,
    /** Heating rate after the event minus the rate before, °C/min. */
    val rateChange: Float,
) {
    val significant: Boolean get() = deltaC >= 0.5f && rateChange > 0.05f
}

data class DiagnosisReport(
    val status: DiagnosisStatus,
    val referenceTemp: Float?,
    val rateCPerMin: Float,
    val riseC: Float,
    val windowMin: Float,
    val causes: List<CauseContribution>,
    val headline: String,
    val explanation: String,
    val advice: List<String>,
    val eventImpacts: List<EventImpact>,
    val throttling: Boolean,
    val generatedAt: Long,
) {
    val primary: CauseContribution? get() = causes.firstOrNull()

    companion object {
        fun insufficient(referenceTemp: Float?) = DiagnosisReport(
            status = DiagnosisStatus.INSUFFICIENT_DATA,
            referenceTemp = referenceTemp,
            rateCPerMin = 0f,
            riseC = 0f,
            windowMin = 0f,
            causes = emptyList(),
            headline = "Collecting data",
            explanation = "A few minutes of measurements are needed to correlate heat with its causes.",
            advice = emptyList(),
            eventImpacts = emptyList(),
            throttling = false,
            generatedAt = System.currentTimeMillis(),
        )
    }
}

data class HeatEpisode(
    val start: Long,
    val end: Long,
    val peakTemp: Float,
    val peakZone: String?,
    val primaryCause: CauseContribution?,
    val summary: String,
    /** Apps or processes that were active while the temperature climbed to this peak. */
    val suspects: List<AppShare> = emptyList(),
    /** "Camera: Face Unlock"-style notes for camera / GPS users seen around the episode. */
    val hardwareUsers: List<String> = emptyList(),
)
