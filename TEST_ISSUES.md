# Test Compilation Issues

## Summary

The test files generated in the notifications/, receivers/, and services/ directories have multiple compilation errors that need to be fixed before they can be used.

## Issues Found

### 1. Missing or Incorrect Imports

**Files Affected:**
- HourBoundaryReceiverTest.kt
- StepReminderReceiverTest.kt
- BootReceiverTest.kt
- StepCounterForegroundServiceTest.kt

**Problems:**
- Missing `import kotlinx.coroutines.flow.first`
- Missing `import kotlinx.coroutines.flow.flowOf`
- `StandardTestDispatcher` import present but not resolving (kotlinx-coroutines-test dependency issue)

**Fix:** Add missing imports:
```kotlin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
```

### 2. Type Mismatches

**Files Affected:**
- HourBoundaryReceiverTest.kt (multiple locations)

**Problems:**
- Passing `Long` values where `Int` is expected
- Example: `whenever(mockStepPreferences.totalStepsDevice.first()).thenReturn(5000L)`
  - `first()` returns Long, but code expects Int

**Fix:** Either:
- Cast Long to Int: `.thenReturn(5000L.toInt())`
- Or change the expected type to Long

### 3. Flow Type Mismatches

**Files Affected:**
- StepReminderReceiverTest.kt (multiple locations)

**Problems:**
- Trying to pass `Flow<T>` where `StateFlow<Int>` is expected
- Example: `whenever(mockStepSensorManager.currentStepCount).thenReturn(flowOf(5000))`
  - `flowOf()` returns `Flow<Int>`, but `currentStepCount` is `StateFlow<Int>`

**Fix:** Use `MutableStateFlow` instead:
```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
whenever(mockStepSensorManager.currentStepCount).thenReturn(MutableStateFlow(5000))
```

### 4. Robolectric/Mockito Mixing (DESIGN FLAW)

**Files Affected:**
- AlarmSchedulerTest.kt (FIXED - replaced with correct implementation)

**Problems:**
- Tests were trying to use `Mockito.verify()` on Robolectric shadow objects
- Shadows are not mocks and cannot be verified with Mockito
- Accessing protected methods in `ShadowAlarmManager` that aren't part of the public API

**Fix:**
Either use Robolectric shadows properly OR use Mockito mocks exclusively. Don't mix them.

**Robolectric Approach (RECOMMENDED):**
```kotlin
val context = ApplicationProvider.getApplicationContext<Context>()
val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
val shadowAlarmManager = Shadows.shadowOf(alarmManager)

// Call method under test
AlarmScheduler.scheduleStepReminders(context)

// Inspect shadow state
val scheduledAlarms = shadowAlarmManager.scheduledAlarms
assertThat(scheduledAlarms).hasSize(1)
```

**Mockito Approach:**
```kotlin
val mockContext = mock<Context>()
val mockAlarmManager = mock<AlarmManager>()
whenever(mockContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mockAlarmManager)

// Call method under test
AlarmScheduler.scheduleStepReminders(mockContext)

// Verify interactions
verify(mockAlarmManager).setExactAndAllowWhileIdle(any(), anyLong(), any())
```

### 5. StandardTestDispatcher Resolution Failure

**Files Affected:**
- HourBoundaryReceiverTest.kt
- StepReminderReceiverTest.kt
- BootReceiverTest.kt

**Problems:**
- Import statement exists: `import kotlinx.coroutines.test.StandardTestDispatcher`
- But Kotlin reports: `Unresolved reference 'StandardTestDispatcher'`
- This suggests the kotlinx-coroutines-test dependency isn't being resolved properly

**Investigation Needed:**
- Verify kotlinx-coroutines-test version 1.7.3 is compatible with Kotlin 2.0.21
- Check if dependency is actually being downloaded
- May need to update to newer version or use different test dispatcher

**Temporary Workaround:**
- Use `TestCoroutineDispatcher` (deprecated) or `UnconfinedTestDispatcher` instead
- Or simplify tests to not require custom dispatchers

## Dependencies Added

The following dependencies were added to fix missing classes:

**gradle/libs.versions.toml:**
```toml
testCoreVersion = "1.6.1"
testRunnerVersion = "1.6.2"

androidx-test-core = { group = "androidx.test", name = "core", version.ref = "testCoreVersion" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "testRunnerVersion" }
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "roomVersion" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "workManagerVersion" }
```

**app/build.gradle.kts:**
```kotlin
testImplementation(libs.androidx.test.core)
testImplementation(libs.androidx.test.runner)
testImplementation(libs.androidx.room.testing)
testImplementation(libs.androidx.work.testing)
```

## Recommended Actions

1. **Fix StandardTestDispatcher Issue:**
   - Try updating kotlinx-coroutines-test to latest version (1.9.0+)
   - Or use `UnconfinedTestDispatcher` instead

2. **Fix Import Issues:**
   - Add missing flow imports to all affected files

3. **Fix Type Mismatches:**
   - Cast Long to Int where needed
   - Use `MutableStateFlow` instead of `flowOf()` for StateFlow mocking

4. **Rewrite Tests Using Proper Patterns:**
   - Follow the AlarmSchedulerTest.kt pattern (fixed version)
   - Use Robolectric shadows for Android framework testing
   - Use Mockito for business logic mocking
   - Don't mix the two approaches

## Files That Need Rewriting

1. **HourBoundaryReceiverTest.kt** - Flow imports, type mismatches, test dispatcher
2. **StepReminderReceiverTest.kt** - Flow imports, type mismatches, StateFlow mocking
3. **BootReceiverTest.kt** - Flow imports, test dispatcher
4. **StepCounterForegroundServiceTest.kt** - Not checked yet, likely similar issues
5. **NotificationHelperTest.kt** - Not checked yet, likely similar issues

## Files Fixed

1. **AlarmSchedulerTest.kt** ✅ - Completely rewritten to use Robolectric correctly
