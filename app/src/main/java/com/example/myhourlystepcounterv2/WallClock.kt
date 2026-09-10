package com.example.myhourlystepcounterv2

import java.util.Calendar

/**
 * Start-of-current-hour timestamp derived from wall-clock time, not a saved preference that can
 * go stale while hour-boundary processing lags the clock (issue #27). The service's notification
 * pipeline and the ViewModel's daily-total pipeline both need this same "what hour is it really"
 * answer to agree with each other and with what the checkpoint loop just wrote to the database.
 */
fun wallClockHourTimestamp(nowMs: Long = System.currentTimeMillis()): Long {
    return Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** Start-of-today timestamp derived from wall-clock time. Companion to [wallClockHourTimestamp]. */
fun wallClockStartOfDay(nowMs: Long = System.currentTimeMillis()): Long {
    return Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
