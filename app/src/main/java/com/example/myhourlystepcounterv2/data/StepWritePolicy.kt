package com.example.myhourlystepcounterv2.data

/**
 * Whether an incoming hourly write actually replaces the stored row.
 *
 * [StepDao.saveHourlyStepsAtomic] keeps the higher of the two values, so a write can be
 * silently dropped. Callers need to know which happened: only the write that landed may claim
 * authorship of the hour.
 */
object StepWritePolicy {
    fun persists(existing: Int?, incoming: Int): Boolean =
        existing == null || incoming > existing
}
