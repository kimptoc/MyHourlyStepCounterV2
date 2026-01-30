# DataStore Resilience Improvement Plan

## Background

After experiencing DataStore corruption crashes, we implemented basic corruption handling (ReplaceFileCorruptionHandler + Flow error handling). This plan addresses the root causes to **prevent future corruption** rather than just recovering from it.

## Root Causes Identified

1. **App killed mid-write** - System or user force-stops during DataStore write operations
2. **Rapid sequential writes** - Multiple saveX() calls in quick succession increase corruption window
3. **Multiple concurrent writers** - 7 different components writing to DataStore simultaneously
4. **High write frequency** - Some values written every 3 seconds unnecessarily

## Work Items

### Priority 1: Batch Sequential Writes (HIGH PRIORITY)

**Goal:** Reduce corruption window by combining multiple sequential writes into single atomic operations.

**Current Problem:**
```kotlin
// StepCounterForegroundService.kt:278-302
preferences.saveHourData(...)                      // Write 1 (3 values)
preferences.saveReminderSentThisHour(false)        // Write 2
preferences.saveAchievementSentThisHour(false)     // Write 3
// Total: 3 separate DataStore transactions = 3x corruption risk
```

**Solution:**
Create batch save methods that combine related writes:

```kotlin
// In StepPreferences.kt
suspend fun saveHourDataWithNotificationFlags(
    hourStartStepCount: Int,
    currentTimestamp: Long,
    totalSteps: Int,
    reminderSent: Boolean,
    achievementSent: Boolean,
    secondReminderSent: Boolean = false
) {
    context.dataStore.updateData { preferences ->
        preferences.toMutablePreferences().apply {
            this[HOUR_START_STEP_COUNT] = hourStartStepCount
            this[CURRENT_HOUR_TIMESTAMP] = currentTimestamp
            this[TOTAL_STEPS_DEVICE] = totalSteps
            this[REMINDER_SENT_THIS_HOUR] = reminderSent
            this[ACHIEVEMENT_SENT_THIS_HOUR] = achievementSent
            this[SECOND_REMINDER_SENT_THIS_HOUR] = secondReminderSent
        }
    }
}

suspend fun saveReminderState(
    reminderTime: Long,
    reminderSent: Boolean,
    achievementSent: Boolean
) {
    context.dataStore.updateData { preferences ->
        preferences.toMutablePreferences().apply {
            this[LAST_REMINDER_NOTIFICATION_TIME] = reminderTime
            this[REMINDER_SENT_THIS_HOUR] = reminderSent
            this[ACHIEVEMENT_SENT_THIS_HOUR] = achievementSent
        }
    }
}

suspend fun saveSecondReminderState(
    reminderTime: Long,
    secondReminderSent: Boolean
) {
    context.dataStore.updateData { preferences ->
        preferences.toMutablePreferences().apply {
            this[LAST_SECOND_REMINDER_TIME] = reminderTime
            this[SECOND_REMINDER_SENT_THIS_HOUR] = secondReminderSent
        }
    }
}
```

**Files to Update:**
- `app/src/main/java/com/example/myhourlystepcounterv2/data/StepPreferences.kt` - Add batch methods
- `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt` - Replace sequential writes
- `app/src/main/java/com/example/myhourlystepcounterv2/notifications/HourBoundaryReceiver.kt` - Replace sequential writes
- `app/src/main/java/com/example/myhourlystepcounterv2/notifications/StepReminderReceiver.kt` - Replace sequential writes

**Success Criteria:**
- No more than 1 DataStore write per logical operation
- Code review shows no sequential writes in hot paths
- Existing unit tests pass
- Add tests for batch operations

**Estimated Effort:** 2-3 hours

---

### Priority 2: Add Write Error Handling (HIGH PRIORITY)

**Goal:** Catch and handle write failures gracefully without crashing.

**Current Problem:**
All `saveX()` methods can throw exceptions that propagate to callers:
- `IOException` - Disk full, permissions issues
- `CorruptionException` - File corrupted during write
- `CancellationException` - Coroutine cancelled mid-write

**Solution:**
Wrap all write operations with error handling:

```kotlin
// In StepPreferences.kt
private suspend fun <T> safeWrite(
    operation: String,
    block: suspend () -> T
): Result<T> = try {
    Result.success(block())
} catch (e: IOException) {
    Log.e("StepPreferences", "IO error during $operation", e)
    Result.failure(e)
} catch (e: CorruptionException) {
    Log.e("StepPreferences", "Corruption during $operation", e)
    Result.failure(e)
} catch (e: CancellationException) {
    // Don't log cancellation as error, it's normal during scope cleanup
    throw e
} catch (e: Exception) {
    Log.e("StepPreferences", "Unexpected error during $operation", e)
    Result.failure(e)
}

// Update all save methods to use safeWrite
suspend fun saveHourData(
    hourStartStepCount: Int,
    currentTimestamp: Long,
    totalSteps: Int
): Result<Unit> = safeWrite("saveHourData") {
    context.dataStore.updateData { preferences ->
        preferences.toMutablePreferences().apply {
            this[HOUR_START_STEP_COUNT] = hourStartStepCount
            this[CURRENT_HOUR_TIMESTAMP] = currentTimestamp
            this[TOTAL_STEPS_DEVICE] = totalSteps
        }
    }
}
```

**Alternative Approach (Fire-and-Forget):**
For non-critical writes where failure doesn't require action:

```kotlin
suspend fun saveHourDataSafe(
    hourStartStepCount: Int,
    currentTimestamp: Long,
    totalSteps: Int
) {
    try {
        saveHourData(hourStartStepCount, currentTimestamp, totalSteps)
    } catch (e: Exception) {
        Log.e("StepPreferences", "Failed to save hour data, will retry on next write", e)
        // Don't rethrow - caller doesn't need to handle
    }
}
```

**Files to Update:**
- `app/src/main/java/com/example/myhourlystepcounterv2/data/StepPreferences.kt` - Add error handling
- All callers - Update to handle Result or use safe variants

**Success Criteria:**
- No uncaught exceptions from DataStore writes
- Appropriate logging for all failure scenarios
- Callers handle failures gracefully or use fire-and-forget variants
- Add tests for error scenarios

**Estimated Effort:** 3-4 hours

---

### Priority 3: Centralized Write Coordination (MEDIUM PRIORITY)

**Goal:** Prevent concurrent writes from multiple components by serializing access through a coordinator.

**Current Problem:**
7 components write to DataStore independently:
1. StepCounterViewModel
2. StepCounterForegroundService
3. HourBoundaryReceiver
4. StepReminderReceiver
5. HourBoundaryCheckWorker
6. StepSensorManager
7. ProfileScreen

At hour boundaries (XX:00, XX:50, XX:55), multiple components can trigger writes simultaneously.

**Solution:**
Create a write coordinator with a mutex to serialize writes:

```kotlin
// New file: app/src/main/java/com/example/myhourlystepcounterv2/data/DataStoreCoordinator.kt
package com.example.myhourlystepcounterv2.data

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates DataStore writes across multiple components to prevent concurrent access issues.
 * All DataStore write operations should go through this coordinator.
 */
class DataStoreCoordinator(private val preferences: StepPreferences) {
    private val writeMutex = Mutex()

    /**
     * Execute a write operation with mutual exclusion.
     * Only one write can happen at a time across all components.
     */
    suspend fun <T> coordinatedWrite(
        component: String,
        operation: String,
        block: suspend () -> T
    ): T = writeMutex.withLock {
        Log.d("DataStoreCoordinator", "[$component] Starting: $operation")
        val startTime = System.currentTimeMillis()
        try {
            block().also {
                val duration = System.currentTimeMillis() - startTime
                Log.d("DataStoreCoordinator", "[$component] Completed: $operation (${duration}ms)")
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e("DataStoreCoordinator", "[$component] Failed: $operation (${duration}ms)", e)
            throw e
        }
    }

    /**
     * Check if a write operation is currently in progress.
     * Useful for avoiding redundant writes.
     */
    fun isWriteInProgress(): Boolean = writeMutex.isLocked
}

// Convenience extension functions
suspend fun DataStoreCoordinator.saveHourData(
    hourStartStepCount: Int,
    currentTimestamp: Long,
    totalSteps: Int,
    component: String
) = coordinatedWrite(component, "saveHourData") {
    // Use the batch method from Priority 1
    preferences.saveHourData(hourStartStepCount, currentTimestamp, totalSteps)
}

suspend fun DataStoreCoordinator.saveHourDataWithNotificationFlags(
    hourStartStepCount: Int,
    currentTimestamp: Long,
    totalSteps: Int,
    reminderSent: Boolean,
    achievementSent: Boolean,
    secondReminderSent: Boolean,
    component: String
) = coordinatedWrite(component, "saveHourDataWithNotificationFlags") {
    // Use the batch method from Priority 1
    preferences.saveHourDataWithNotificationFlags(
        hourStartStepCount,
        currentTimestamp,
        totalSteps,
        reminderSent,
        achievementSent,
        secondReminderSent
    )
}
```

**Integration Strategy:**
1. Add DataStoreCoordinator as singleton in Application class
2. Pass coordinator to all components that need to write
3. Update ViewModel, Service, Receivers to use coordinator
4. Add logging to track write patterns in production

**Files to Update:**
- Create: `app/src/main/java/com/example/myhourlystepcounterv2/data/DataStoreCoordinator.kt`
- Update: All 7 components that write to DataStore
- Update: `MainActivity.kt` - Initialize coordinator
- Update: `StepCounterViewModelFactory.kt` - Pass coordinator to ViewModel

**Success Criteria:**
- All DataStore writes go through coordinator
- Mutex prevents concurrent writes
- Logging shows write serialization
- No performance degradation (writes should be fast enough that queuing isn't noticeable)
- Add tests for concurrent write scenarios

**Estimated Effort:** 4-5 hours

---

### Priority 4: Reduce Write Frequency (LOW PRIORITY)

**Goal:** Eliminate unnecessary writes to reduce corruption risk and improve battery life.

**Current Write Patterns:**

| Component | Frequency | Values Written | Necessary? |
|-----------|-----------|----------------|------------|
| ForegroundService notification loop | Every 3 seconds | None (reads only) | ✅ Yes |
| SensorManager onSensorChanged | Every sensor event | totalStepsDevice | ❌ Too frequent |
| ViewModel tick loop | Every second | None (reads only) | ✅ Yes |
| Hour boundary processing | Every hour | hourStartStepCount, currentTimestamp, totalSteps | ✅ Yes |
| Reminder notifications | XX:50, XX:55 | reminderSent, achievementSent | ✅ Yes |

**Identified Issue:**
`SensorManager.onSensorChanged()` updates DataStore on **every sensor event** (potentially dozens of times per second when walking):

```kotlin
// StepSensorManager.kt - CURRENT (problematic)
override fun onSensorChanged(event: SensorEvent) {
    lifecycleScope.launch {
        // ... processing ...
        preferences.saveTotalStepsDevice(event.values[0].toInt())  // ❌ Too frequent!
    }
}
```

**Solution:**
Only write critical values when actually needed:

```kotlin
// Option 1: Throttle writes to once per minute
private var lastWriteTime = 0L
override fun onSensorChanged(event: SensorEvent) {
    val currentTime = System.currentTimeMillis()
    lifecycleScope.launch {
        // ... processing ...
        _totalSteps.value = event.values[0].toInt()  // Always update in-memory

        // Only persist every 60 seconds or on significant change
        if (currentTime - lastWriteTime > 60_000 ||
            abs(_totalSteps.value - lastPersistedValue) > 100) {
            preferences.saveTotalStepsDevice(_totalSteps.value)
            lastWriteTime = currentTime
            lastPersistedValue = _totalSteps.value
        }
    }
}

// Option 2: Only write on app backgrounding or hour boundary
// Don't write on every sensor event at all - rely on hour boundary saves
```

**Write Frequency Analysis:**

| Before | After | Reduction |
|--------|-------|-----------|
| ~30-50 writes/minute (walking) | 1 write/minute | 97% reduction |
| ~50,000 writes/day (active user) | ~1,500 writes/day | 97% reduction |

**Files to Update:**
- `app/src/main/java/com/example/myhourlystepcounterv2/sensor/StepSensorManager.kt` - Throttle writes
- `app/src/main/java/com/example/myhourlystepcounterv2/ui/StepCounterViewModel.kt` - Review write frequency
- `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt` - Ensure writes only on hour boundaries

**Success Criteria:**
- Sensor writes reduced from every event to every minute (or less)
- No data loss during normal operation
- Step counts remain accurate
- Battery usage improves measurably
- Add tests for throttling behavior

**Estimated Effort:** 2-3 hours

---

### Priority 5: Add Periodic Backup (OPTIONAL)

**Goal:** Provide a fallback recovery mechanism if DataStore becomes corrupted again.

**Strategy:**
Store critical values in a secondary location (SharedPreferences) as a backup:

```kotlin
// New file: app/src/main/java/com/example/myhourlystepcounterv2/data/DataStoreBackup.kt
package com.example.myhourlystepcounterv2.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.first

/**
 * Periodic backup of critical DataStore values to SharedPreferences.
 * Used as fallback if DataStore becomes corrupted.
 */
class DataStoreBackup(
    context: Context,
    private val preferences: StepPreferences
) {
    private val backup: SharedPreferences =
        context.getSharedPreferences("step_preferences_backup", Context.MODE_PRIVATE)

    /**
     * Backup critical values to SharedPreferences.
     * Call this hourly or when critical values change.
     */
    suspend fun createBackup() {
        try {
            val hourStart = preferences.hourStartStepCount.first()
            val timestamp = preferences.currentHourTimestamp.first()
            val totalSteps = preferences.totalStepsDevice.first()
            val lastStartOfDay = preferences.lastStartOfDay.first()
            val lastOpenDate = preferences.lastOpenDate.first()

            backup.edit().apply {
                putInt("hour_start_step_count", hourStart)
                putLong("current_hour_timestamp", timestamp)
                putInt("total_steps_device", totalSteps)
                putLong("last_start_of_day", lastStartOfDay)
                putLong("last_open_date", lastOpenDate)
                putLong("backup_timestamp", System.currentTimeMillis())
                apply()  // Use apply() for async write
            }

            Log.i("DataStoreBackup", "Backup created: hourStart=$hourStart, total=$totalSteps")
        } catch (e: Exception) {
            Log.e("DataStoreBackup", "Failed to create backup", e)
        }
    }

    /**
     * Check if a recent backup exists (within last 2 hours).
     */
    fun hasRecentBackup(): Boolean {
        val backupTime = backup.getLong("backup_timestamp", 0)
        val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000)
        return backupTime > twoHoursAgo
    }

    /**
     * Restore from backup if DataStore is corrupted.
     * Returns true if restoration was successful.
     */
    suspend fun restoreFromBackup(): Boolean {
        if (!hasRecentBackup()) {
            Log.w("DataStoreBackup", "No recent backup available for restoration")
            return false
        }

        return try {
            val hourStart = backup.getInt("hour_start_step_count", 0)
            val timestamp = backup.getLong("current_hour_timestamp", 0)
            val totalSteps = backup.getInt("total_steps_device", 0)
            val lastStartOfDay = backup.getLong("last_start_of_day", 0)
            val lastOpenDate = backup.getLong("last_open_date", 0)

            if (hourStart > 0 || totalSteps > 0) {
                preferences.saveHourData(hourStart, timestamp, totalSteps)
                preferences.saveStartOfDay(lastStartOfDay)
                preferences.saveLastOpenDate(lastOpenDate)

                Log.i("DataStoreBackup", "Restored from backup: hourStart=$hourStart, total=$totalSteps")
                true
            } else {
                Log.w("DataStoreBackup", "Backup contains no valid data")
                false
            }
        } catch (e: Exception) {
            Log.e("DataStoreBackup", "Failed to restore from backup", e)
            false
        }
    }

    /**
     * Get backup age in hours for diagnostics.
     */
    fun getBackupAgeHours(): Long {
        val backupTime = backup.getLong("backup_timestamp", 0)
        if (backupTime == 0L) return -1
        return (System.currentTimeMillis() - backupTime) / (60 * 60 * 1000)
    }
}
```

**Integration:**
1. Create backup after each hour boundary save
2. Check for backup on app startup if DataStore is empty
3. Display backup status in ProfileScreen for diagnostics

**Files to Update:**
- Create: `app/src/main/java/com/example/myhourlystepcounterv2/data/DataStoreBackup.kt`
- Update: `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt` - Create backup after hour saves
- Update: `app/src/main/java/com/example/myhourlystepcounterv2/ui/StepCounterViewModel.kt` - Check backup on initialization
- Update: `app/src/main/java/com/example/myhourlystepcounterv2/ui/ProfileScreen.kt` - Show backup diagnostics

**Success Criteria:**
- Backup created hourly
- Backup restored automatically if DataStore is empty on startup
- ProfileScreen shows backup age and status
- Add tests for backup/restore cycle

**Estimated Effort:** 3-4 hours

---

## Implementation Order

### Phase 1: Prevention (Reduce Likelihood)
1. **Batch Sequential Writes** (2-3 hours) ← Start here
2. **Add Write Error Handling** (3-4 hours)
3. **Centralized Write Coordination** (4-5 hours)

**Total Phase 1:** 9-12 hours

### Phase 2: Optimization (Improve Performance)
4. **Reduce Write Frequency** (2-3 hours)

**Total Phase 2:** 2-3 hours

### Phase 3: Recovery (Fallback Strategy)
5. **Add Periodic Backup** (3-4 hours) ← Only if needed after Phase 1

**Total Phase 3:** 3-4 hours

---

## Testing Strategy

### Unit Tests
- Test batch write methods combine values correctly
- Test error handling returns Result or doesn't throw
- Test coordinator serializes writes (using TestScope)
- Test write throttling limits frequency
- Test backup/restore cycle

### Instrumented Tests
- Test concurrent writes from multiple components
- Test corruption recovery with backup
- Test app startup with corrupted DataStore

### Manual Testing
- Force-stop app during hour boundary processing
- Monitor logcat for write patterns
- Verify battery usage improvements
- Test with device reboots

---

## Rollback Plan

If any implementation causes issues:
1. **Priority 1-2:** Can be reverted individually (wrap old code with new methods, but keep old methods)
2. **Priority 3:** Can be disabled by feature flag (coordinator can pass through without mutex)
3. **Priority 4:** Can revert throttling to original behavior
4. **Priority 5:** Backup is additive, can be disabled without impact

---

## Monitoring & Success Metrics

### Before Implementation (Baseline)
- Crash rate from DataStore corruption
- Battery usage per hour
- Write operations per hour

### After Implementation (Target)
- Zero crashes from DataStore corruption
- 50% reduction in write operations
- 5-10% battery usage improvement
- Backup available within 2 hours of last write

### Production Monitoring
- Log write coordination wait times
- Log backup creation/restoration events
- Track corruption handler invocations
- Monitor SharedPreferences backup age

---

## References

- [DataStore Best Practices](https://developer.android.com/topic/libraries/architecture/datastore#best-practices)
- [Handling DataStore Exceptions](https://developer.android.com/codelabs/android-preferences-datastore#8)
- CLAUDE.md - Session Summary: DataStore Corruption Bug Fix (2026-01-28)

---

## Notes

- **All work items preserve backward compatibility** - existing code continues to work during migration
- **Incremental rollout** - Can implement Priority 1-2 first, evaluate, then decide on 3-5
- **Focus on prevention** - Priority 1-3 eliminate most corruption scenarios
- **Backup is insurance** - Priority 5 only needed if 1-3 don't achieve 99.9% reliability
