package com.example.myhourlystepcounterv2.data

import kotlinx.coroutines.flow.Flow

class StepRepository(private val stepDao: StepDao) {
    suspend fun saveHourlySteps(timestamp: Long, stepCount: Int) {
        // Use atomic save to prevent race conditions (keeps higher value)
        stepDao.saveHourlyStepsAtomic(timestamp, stepCount)
    }

    suspend fun getStepForHour(timestamp: Long): StepEntity? {
        return stepDao.getStepForHour(timestamp)
    }

    fun getStepsForDay(startOfDay: Long, currentHourTimestamp: Long): Flow<List<StepEntity>> {
        return stepDao.getStepsForDay(startOfDay, currentHourTimestamp)
    }

    fun getTotalStepsForDay(startOfDay: Long): Flow<Int?> {
        return stepDao.getTotalStepsForDay(startOfDay)
    }

    fun getTotalStepsForDayExcludingCurrentHour(startOfDay: Long, currentHourTimestamp: Long): Flow<Int?> {
        return stepDao.getTotalStepsForDayExcludingCurrentHour(startOfDay, currentHourTimestamp)
    }

    suspend fun deleteOldSteps(cutoffTime: Long) {
        stepDao.deleteOldSteps(cutoffTime)
    }
}
