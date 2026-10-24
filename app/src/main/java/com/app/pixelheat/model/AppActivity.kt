package com.app.pixelheat.model

/** What an [AppUsage] row measures, and the unit of its value. */
enum class AppResource {
    /** Seconds in the foreground since the previous sample. */
    FOREGROUND,

    /** Share of total CPU time over the last few minutes, percent. */
    CPU,

    /** Kilobytes on mobile data since the previous sample. */
    MOBILE_DATA,

    /** Kilobytes on Wi-Fi since the previous sample. */
    WIFI_DATA,

    /** 1 when the app holds an active GPS request. */
    GPS,

    /** 1 when the app or native process has a camera open. */
    CAMERA,
}

/** One app's use of one resource, recorded with a thermal sample. */
data class AppUsage(val resource: AppResource, val packageName: String, val value: Float)
