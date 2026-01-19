# Day Boundary Bug - Hour Transition Flag Stuck True

## Root Cause

**CRITICAL BUG IDENTIFIED**: `HourBoundaryReceiver.kt` and `StepCounterForegroundService.kt` have early returns that skip calling `endHourTransition()`, leaving `hourTransitionInProgress = true` permanently. This causes all sensor events to be ignored.

**Why this happens:**
1. Both AlarmManager (HourBoundaryReceiver) and ForegroundService try to process hour boundary at 9:00 AM
2. First one succeeds, sets baseline
3. Second one detects duplicate, returns early WITHOUT calling `endHourTransition()`
4. Flag stuck at `true` → sensor events ignored forever
5. User takes steps at 9:01-9:10 → all ignored (logs show `sensorCurrentStepCount=0`)
6. User opens app at 9:11 → `refreshStepCounts()` forces state update, bypasses check

## Detailed Investigation Report

### Timeline Analysis

#### Midnight (~12:00 AM)
- **User Action**: Took ~37 steps
- **System State**: App was likely in foreground or recently used
- **Result**: Steps tracked correctly, saved to database with timestamp `1768784400000`

#### 8:00 AM (Hour Boundary)
- **What Happened**: Hour boundary alarm fired (either HourBoundaryReceiver or ForegroundService)
  - Saved 8am hour with 406 steps (timestamp: `1768809600000`)
  - Reset baseline for 9am hour
  - Called `sensorManager.beginHourTransition()` then `resetForNewHour()` then `endHourTransition()`

- **Critical Issue**: The hour boundary processing does NOT call `startListening()` on the sensor
  - Line 93-122 in HourBoundaryReceiver.kt shows no sensor re-registration
  - Line 290-379 in StepCounterForegroundService.kt shows no sensor re-registration

#### 9:00 AM (Hour Boundary)
- **Hour Boundary Alarm Fired**: Logs show "Hour boundary alarm triggered at exactly 9:00:00 AM"
- **Processing**:
  - HourBoundaryReceiver or ForegroundService saved the 8am hour
  - Called `resetForNewHour()` which reset display to 0
  - Set new baseline for 9am
- **Sensor Status**: Still not firing! The sensor listener was never re-registered

#### 9:01-9:10 AM (User Takes Steps - NOT DETECTED)
- **Diagnostic Logs Show**:
  - `displayedHourly=0` (correct - just started 9am hour)
  - `displayedDaily=443` (correct - 406 + 37 from database)
  - `sensorCurrentStepCount=0` ← **SMOKING GUN** - sensor not updating!

- **Why Sensor Not Firing**:
  - The sensor listener is registered in `StepCounterViewModel.initialize()` (line 66-67)
  - But if the app is in background, the ViewModel is NOT re-initialized after hour boundaries
  - However, **sensor events are being ignored** during `hourTransitionInProgress`

#### 9:11 AM (User Opens App)
- **What Happened**: `MainActivity.onResume()` called `viewModel.refreshStepCounts()`
  - This called `sensorManager.setLastKnownStepCount(currentTotal)` (line 502)
  - The sensor was already registered and firing, but the state was stale
  - Suddenly sensor shows: `absolute=76777 | hourBaseline=76770 | delta=7`

### Code Location Analysis

#### Issue 1: Hour Transition Flag Not Thread-Safe with Multiple Components

**Location**: `StepSensorManager.kt` lines 268-285

The hour transition mechanism assumes only ONE component processes hour boundaries. But we have:
- `HourBoundaryReceiver.kt` (AlarmManager backup)
- `StepCounterForegroundService.kt` (primary handler)

**Race Condition**: If both fire at 9:00:00 AM:
1. HourBoundaryReceiver calls `beginHourTransition()` at 9:00:00.000
2. ForegroundService calls `beginHourTransition()` at 9:00:00.050
3. HourBoundaryReceiver calls `endHourTransition()` at 9:00:00.100
4. ForegroundService calls `endHourTransition()` at 9:00:00.150
5. **Result**: Flag is cleared, but...

**The ACTUAL bug**: Look at line 97-102 in HourBoundaryReceiver:
```kotlin
val resetSuccessful = sensorManager.resetForNewHour(deviceTotal)

if (!resetSuccessful) {
    android.util.Log.w("HourBoundary", "Baseline already set, skipping duplicate reset")
    return@launch  // ← EXITS WITHOUT CALLING endHourTransition()
}
```

**CRITICAL BUG**: If `resetForNewHour()` returns false (duplicate), the receiver EXITS without calling `endHourTransition()`! This leaves `hourTransitionInProgress = true` **PERMANENTLY**, causing all future sensor events to be ignored!

#### Issue 2: Same Problem in ForegroundService

**Location**: `StepCounterForegroundService.kt` line 350

Same early return pattern without calling `endHourTransition()`.

## Fix Plan

### Step 1: Verify the Bug (5 min)
- Pull device logs and search for "Baseline already set, skipping duplicate reset" at 9am
- Check for "Hour transition in progress" messages stuck after 9am
- This will confirm the hypothesis

### Step 2: Fix HourBoundaryReceiver.kt (10 min)
**File**: `app/src/main/java/com/example/myhourlystepcounterv2/notifications/HourBoundaryReceiver.kt`
**Location**: Lines 92-122
**Problem**: Line 101 has early return without calling `endHourTransition()`

**Solution**: Use try-finally pattern:
```kotlin
sensorManager.beginHourTransition()
try {
    val resetSuccessful = sensorManager.resetForNewHour(deviceTotal)
    if (!resetSuccessful) {
        android.util.Log.w("HourBoundary", "Duplicate detected, skipping")
        return@launch
    }

    // Save previous hour to database
    val hourTimestamp = preferences.currentHourTimestamp.first()
    if (hourTimestamp > 0) {
        val hourStartOfHour = getStartOfHour(hourTimestamp)
        val previousHourSteps = deviceTotal - preferences.hourStartStepCount.first()
        val clampedSteps = previousHourSteps.coerceIn(0, Config.MAX_STEPS_PER_HOUR)

        repository.saveHourlySteps(hourStartOfHour, clampedSteps)
        android.util.Log.i("HourBoundary", "Saved hour $hourStartOfHour: $clampedSteps steps")
    }

    // Update preferences for new hour
    val currentHour = getStartOfHour(System.currentTimeMillis())
    preferences.saveHourData(
        hourStartStepCount = deviceTotal,
        currentTimestamp = currentHour,
        totalSteps = deviceTotal
    )

    android.util.Log.i("HourBoundary", "Hour boundary processed: new hour $currentHour, baseline $deviceTotal")
} finally {
    sensorManager.endHourTransition()
}
```

### Step 3: Fix StepCounterForegroundService.kt (10 min)
**File**: `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`
**Location**: Lines 341-371
**Problem**: Line 350 has early return without calling `endHourTransition()`

**Solution**: Same try-finally pattern as Step 2

### Step 4: Add Defensive Logging (5 min)
**File**: `app/src/main/java/com/example/myhourlystepcounterv2/sensor/StepSensorManager.kt`

Add logs to track transition state:
```kotlin
fun beginHourTransition() {
    val startTime = System.currentTimeMillis()
    android.util.Log.i("StepSensor", "BEGIN hour transition at ${java.util.Date(startTime)}")
    hourTransitionInProgress.set(true)
}

fun endHourTransition() {
    val endTime = System.currentTimeMillis()
    android.util.Log.i("StepSensor", "END hour transition at ${java.util.Date(endTime)}")
    hourTransitionInProgress.set(false)
}
```

Also modify `onSensorChanged()` to log when events are ignored:
```kotlin
if (hourTransitionInProgress.get()) {
    android.util.Log.d("StepSensor", "Ignoring sensor event during transition: steps=$event.values[0]")
    return
}
```

### Step 5: Test Scenarios (30 min)
1. **Duplicate Hour Boundary**: Trigger both AlarmManager and ForegroundService at same time
2. **Sensor Events During Transition**: Verify events are buffered/ignored correctly
3. **Post-Transition Sensor**: Verify sensor resumes firing after transition
4. **App Closed Overnight**: Test full day boundary with app in background

### Step 6: Consider Architectural Improvement (Optional)
Prevent duplicates at source:
- Add distributed lock using DataStore preference
- First component to acquire lock processes hour boundary
- Others skip processing entirely
- Lock released after 5 seconds max (safety timeout)

## Success Criteria
- ✅ Sensor events fire continuously (no stuck 0 values)
- ✅ Hour boundaries processed without breaking sensor
- ✅ Duplicate processing handled gracefully
- ✅ No stuck transition flags
- ✅ Opening app doesn't trigger sudden step jumps
- ✅ Steps taken between 9:01-9:10 are correctly tracked

## Why Samsung Health Shows Correct Data

Samsung Health has direct access to the system step counter service and doesn't rely on sensor events. It queries the cumulative step count directly, which is why it shows:
- Midnight-1am: 112+13 = 125 steps
- 8-9am: 37+406 = 443 steps
- 9am (current): 13 steps

## Verification Steps Needed

After implementing the fix:
1. Check device logs for "BEGIN hour transition" and "END hour transition" pairs
2. Verify no "Ignoring sensor event during transition" after END is called
3. Verify sensor events continue firing after hour boundary processing
4. Monitor `sensorCurrentStepCount` in diagnostic logs - should never be stuck at 0
