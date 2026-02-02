package com.example.myhourlystepcounterv2.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Calendar

internal interface HourBoundaryDelayProvider {
    suspend fun delay(ms: Long)
}

internal class CoroutineDelayProvider : HourBoundaryDelayProvider {
    override suspend fun delay(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }
}

internal interface HourBoundaryTimeProvider {
    fun now(): Calendar
    fun nextHour(from: Calendar): Calendar
}

internal class SystemHourBoundaryTimeProvider : HourBoundaryTimeProvider {
    override fun now(): Calendar = Calendar.getInstance()

    override fun nextHour(from: Calendar): Calendar {
        return Calendar.getInstance().apply {
            timeInMillis = from.timeInMillis
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}

internal class HourBoundaryLoopRunner(
    private val timeProvider: HourBoundaryTimeProvider = SystemHourBoundaryTimeProvider(),
    private val delayProvider: HourBoundaryDelayProvider = CoroutineDelayProvider()
) {
    suspend fun runInnerLoop(
        isActive: () -> Boolean,
        setActive: (Boolean) -> Unit,
        checkMissed: suspend () -> Unit,
        handleBoundary: suspend () -> Unit,
        onBeforeDelay: (delayMs: Long, nextHour: Calendar, now: Calendar) -> Unit = { _, _, _ -> },
        onBoundaryReached: () -> Unit = {},
        onIterationSuccess: () -> Unit = {},
        onIterationFailure: (Exception, Int) -> Unit = { _, _ -> },
        onCheckMissedError: (Exception) -> Unit = {},
        iterationBackoffMsProvider: (Int) -> Long = { failureCount ->
            minOf(60000L * failureCount, 300000L)
        }
    ) {
        setActive(true)
        var failureCount = 0

        try {
            try {
                checkMissed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onCheckMissedError(e)
            }

            while (isActive()) {
                try {
                    val now = timeProvider.now()
                    val nextHour = timeProvider.nextHour(now)
                    val delayMs = nextHour.timeInMillis - now.timeInMillis

                    onBeforeDelay(delayMs, nextHour, now)
                    delayProvider.delay(delayMs)

                    if (!isActive()) break

                    onBoundaryReached()
                    handleBoundary()

                    failureCount = 0
                    onIterationSuccess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failureCount++
                    onIterationFailure(e, failureCount)

                    val backoffMs = iterationBackoffMsProvider(failureCount)
                    delayProvider.delay(backoffMs)
                }
            }
        } finally {
            setActive(false)
        }
    }

    suspend fun runWithRecovery(
        maxRestarts: Int,
        startLoop: suspend () -> Unit,
        onRestart: (attempt: Int, error: Exception) -> Unit,
        onGiveUp: () -> Unit,
        restartBackoffMsProvider: (Int) -> Long = { attempt ->
            minOf(5000L * attempt, 30000L)
        }
    ) {
        var restartCount = 0

        while (restartCount < maxRestarts) {
            try {
                startLoop()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                restartCount++
                onRestart(restartCount, e)

                if (restartCount < maxRestarts) {
                    val backoffMs = restartBackoffMsProvider(restartCount)
                    delayProvider.delay(backoffMs)
                }
            }
        }

        onGiveUp()
    }
}
