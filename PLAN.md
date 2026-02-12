# CURRENT STATUS & NEXT STEPS (as of 2026-02-07)

## 🟢 Fix: Reboot Step-Loss Resilience (Feb 12, 2026)

### Problem
After phone reboot, some steps are lost. The app starts, but missed-hour reconciliation can incorrectly assume step-counter continuity across reboot and skip writes or reset baselines too aggressively.

### Root Causes
1. Service headless boot path did not explicitly start `TYPE_STEP_COUNTER` listening (relied on UI path).
2. Missed-hour backfill assumed `currentDeviceTotal - savedDeviceTotal` remains valid across reboot.
3. No periodic in-hour DB checkpoints to preserve partial-hour progress before unexpected reboot.
4. Initialization/backfill fallback could reuse pre-reboot cached totals when sensor had not emitted post-boot yet.

### Plan (Steps 1-4)
1. **Start sensor listener from `StepCounterForegroundService`**
   - On service boot/start, call `sensorManager.startListening()` when permission is granted.
2. **Add reboot detection and continuity guard**
   - Persist `Settings.Global.BOOT_COUNT` in preferences.
   - Treat boot-count changes (or counter rollback) as broken continuity and avoid pre/post reboot subtraction math.
3. **Add periodic current-hour checkpointing**
   - Every 5 minutes: save current-hour partial steps to DB (`saveHourlySteps` keeps max).
   - Continue periodic device-total snapshots for backfill.
4. **Adjust initialization/backfill fallback behavior**
   - If reboot detected and sensor hasn’t emitted yet, avoid stale pre-reboot fallback totals.
   - Re-baseline from first valid post-boot reading and resume normal hourly processing.

### Status
- **Implementation:** ✅ Complete (steps 1-4 merged in app code and unit-tested).
- **Device verification:** ✅ Completed on physical Samsung device via ADB reboot cycles.

### Verification
1. Reboot device with app running and no app UI open.
2. Confirm service starts sensor listener and receives events post-boot.
3. Confirm missed-hour/backfill logs show continuity-break handling when boot count changes.
4. Confirm current-hour progress is at most one checkpoint interval behind after reboot (target: <= 5 minutes).
5. Confirm no negative backfill deltas and no duplicate hourly rows.

### Verification Results (Feb 12, 2026)
1. **Boot cycles observed:** boot count increased `28 -> 29 -> 30`.
2. **Boot receiver/service start confirmed:** app process started from `BootReceiver`; boot alarms rescheduled.
3. **Service sensor start confirmed:** log contains `Sensor listener started from service`.
4. **Checkpointing confirmed:** log contains `Checkpoint saved ...` after 5-minute interval.
5. **Reboot-aware init confirmed:** log contains `boot count changed (29 -> 30). Ignoring pre-reboot cached totals.`
6. **Optional deeper scenario:** missed-hour backfill continuity-break branch can be forced in a long-gap reboot simulation if needed.

## 🟡 Fix: Overnight Sensor Event Delivery Bug (Feb 7, 2026)

### Problem
App loses step data overnight. Sensor events stop being delivered during Doze mode despite foreground service + wake lock. Steps showed in notification after midnight, then all zeros from 1am-8am.

### Root Cause
`StepSensorManager.startListening()` used 3-parameter `registerListener` without `maxReportLatencyUs`. Samsung's power management silently stops delivering TYPE_STEP_COUNTER events when screen is off.

### Fix Applied
1. **SensorState.kt**: Added `lastSensorEventTimeMs` field for staleness detection
2. **StepSensorManager.kt**: Changed to 4-parameter `registerListener` with `maxReportLatencyUs=5min` (forces batched delivery even in Doze). Added `flushSensor()`, `reRegisterListener()`, `getLastSensorEventTime()` methods.
3. **StepCounterForegroundService.kt**: Added sensor flush before `getCurrentTotalSteps()` in `handleHourBoundary()` and `checkMissedHourBoundaries()`. Modified 15-min snapshot loop to re-register dormant sensors. Re-register sensor after each hour boundary.

### Status: Deployed — awaiting overnight verification

---


## 🟢 UI: History Tab Goal Indicator + Prominent Notifications (Feb 3, 2026)

### Status
- **Implemented:** ✅ Added goal indicator circles to each Hourly Activity row (filled = goal reached, empty = not reached).
- **Goal Definition:** Uses `StepTrackerConfig.STEP_REMINDER_THRESHOLD` (currently 250 steps).
- **Visibility Tuning:** Indicator size increased to 18dp; empty indicator uses `MaterialTheme.colorScheme.outline`.
- **Notification Persistence:** Foreground service notification channel updated to high-importance `step_counter_channel_v4` with persistent label.
- **Urgent Buzzer:** 5-minute reminder now uses dedicated urgent channel with triple-buzz vibration (no sound).

### Files Touched
- `app/src/main/java/com/example/myhourlystepcounterv2/ui/HistoryScreen.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/notifications/NotificationHelper.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/Config.kt`
- `app/src/main/res/values/strings.xml`

## 🟡 INVESTIGATE: Missing Midnight Hour (Feb 4, 2026)

### Symptom
Today just after 8am:
- App shows: daily=503, current hour=437, prev hour=54, 1–2am=12, **midnight 00–01 missing**
- Samsung Health shows: daily=596, current hour=437, prev hour=60, **00–01=87**, 1–2am=12

### Evidence from logs
- History loaded lacks 00:00 hour:
  - `History loaded (past hours): 4 entries - ... 07:00: 54, 03:00: 0, 02:00: 0, 01:00: 12`
- Closure handler skipped backfill:
  - `handleUiResumeClosure: Not first open today, skipping closure detection`

### Likely Root Cause
- Midnight boundary was missed (FG service / AlarmManager not triggered or failed).
- Later app resume **skipped** closure-period backfill because it only runs on "first open today."
- Net effect: 00:00–01:00 hour never saved; daily total under-counts by ~87 steps.

### Proposed Fix (Task)
1. **Relax closure/backfill gating**
   - Run missed-hour detection when there is a **gap >= 1 hour** between `currentHourTimestamp` and `now`, even if not first open today.
   - Do not rely solely on `lastOpenDate` == today to decide.
2. **Strengthen midnight handling**
   - Ensure boundary processing explicitly handles day rollover (00:00) and writes the midnight hour record.
3. **Add targeted logs**
   - Log when backfill is **skipped** and why (include timestamps and delta hours).
   - Log when backfill **runs**, including which hours are written.

### Files to Inspect/Modify
- `app/src/main/java/com/example/myhourlystepcounterv2/ui/StepCounterViewModel.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/notifications/HourBoundaryReceiver.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/worker/HourBoundaryCheckWorker.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/data/StepPreferences.kt`

### Verification Plan
1. Simulate missed midnight boundary by stopping app before midnight and reopening after 1–2am.
2. Confirm 00:00–01:00 entry exists in History after reopen.
3. Verify daily total matches Samsung Health within 1–2 steps (expected sensor variance).
4. Confirm logs show backfill executed with correct hours.

## 🟡 ARCHITECTURE: Service‑Only DB Writes (Feb 4, 2026)

### Goal
Centralize all hourly DB writes in `StepCounterForegroundService`. UI should be read‑only and never write hourly records. AlarmManager/WorkManager should **start the service** (when allowed) instead of writing data directly.

### Rationale
- Avoid duplicate writes from multiple components (UI + service + receiver + worker).
- Ensure consistent backfill logic in one place.
- Keep UI simple: read/observe DB state only.

### Proposed Changes (Task)
1. **Move/Consolidate Backfill Logic into FG Service**
   - Implement full missed‑hour detection/backfill in `StepCounterForegroundService`.
   - Ensure dedup by persisting last processed range (start/end) in `StepPreferences`.
2. **Add Periodic “Snapshot” of Device Total**
   - Persist `totalStepsDevice` + timestamp every **15 minutes** in `StepPreferences`.
   - Retain the last **24 hours** of snapshots (rolling window).
   - Use snapshots to improve backfill accuracy when multiple hours are missed.
   - Keep snapshot writes lightweight (DataStore only, no DB writes).
   - Define snapshot structure in plan:
     - `DeviceTotalSnapshot(timestamp: Long, deviceTotal: Int)`
   - Storage options:
     - Preferred: Proto DataStore message with repeated `DeviceTotalSnapshot`
     - Acceptable: JSON‑serialized list in Preferences DataStore
3. **Make UI Read‑Only**
   - Remove hourly DB writes from `StepCounterViewModel` (startup and UI‑resume closure logic).
   - Keep it limited to reading DB + displaying sensor current hour.
4. **Receiver/Worker Start Service**
   - Update `HourBoundaryReceiver` and `HourBoundaryCheckWorker` to start the FG service for processing instead of writing directly.
   - Respect `permanentNotificationEnabled` and platform restrictions.
5. **Hard Dedup & Safety**
   - Add a single “backfill coordinator” gate in service to prevent double‑processing from multiple triggers.
   - Store last processed backfill range as two DataStore longs:
     - `lastProcessedRangeStart`
     - `lastProcessedRangeEnd`
   - Gate logic should be **atomic** (single `dataStore.edit`):
     - If new range overlaps saved range → skip.
     - Otherwise update range + `lastProcessedBoundaryTimestamp` and proceed.
   - Example (pseudocode):
     - `suspend fun tryClaimBackfillRange(start: Long, end: Long): Boolean {`
     - `  var allowed = false`
     - `  dataStore.edit { prefs ->`
     - `    val savedStart = prefs[LAST_RANGE_START] ?: 0L`
     - `    val savedEnd = prefs[LAST_RANGE_END] ?: 0L`
     - `    val overlaps = start <= savedEnd && end >= savedStart`
     - `    if (!overlaps) {`
     - `      prefs[LAST_RANGE_START] = start`
     - `      prefs[LAST_RANGE_END] = end`
     - `      prefs[LAST_PROCESSED_BOUNDARY] = end`
     - `      allowed = true`
     - `    }`
     - `  }`
     - `  return allowed`
     - `}`
   - Log explicit “skip” reasons and processed hour ranges.

### Files to Inspect/Modify
- `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/notifications/HourBoundaryReceiver.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/worker/HourBoundaryCheckWorker.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/ui/StepCounterViewModel.kt`
- `app/src/main/java/com/example/myhourlystepcounterv2/data/StepPreferences.kt`

### Verification Plan
1. Force‑stop app, ensure service writes hourly entries at boundaries.
2. Disable/enable permanent notification; verify service restart writes missed hours.
3. Simulate missed midnight boundary; verify DB entries exist without UI participation.
4. Confirm no duplicate hour rows are created when receiver/worker fires concurrently.

## 🟡 BUG: Hour Boundary Saves Wrong Hour (Feb 5, 2026)

### Symptom
At 00:00 on Feb 5, log shows FG service saved **22:00** hour instead of **23:00**:
```
Saving completed hour: timestamp=1770242400000 (Wed Feb 04 22:00:00 GMT 2026)
```
Expected to save the hour ending at 00:00 (i.e., 23:00–00:00).

### Likely Cause
`preferences.currentHourTimestamp` is stale (lags by >1 hour). The service uses it as the “previous hour” without validating gap size.

### Proposed Fix (Task)
1. **Sanity check at hour boundary** in `StepCounterForegroundService.handleHourBoundary()`:
   - Compute `currentHourTimestamp`.
   - If `currentHourTimestamp - savedHourTimestamp > 1h`, treat as missed hours.
2. **Run backfill before saving the “previous hour”**:
   - Use existing `checkMissedHourBoundaries()` / backfill logic (or inline small handler) to catch up.
3. **Clamp previous hour timestamp**:
   - If saved timestamp is stale, use `currentHourTimestamp - 1h` for the “previous hour” save.
4. **Add logs**:
   - Log when saved hour timestamp is stale and when it is corrected.
5. **Status**
   - Implemented: stale timestamp detection + clamp + explicit logs in `handleHourBoundary()`.

### Files to Modify
- `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`

### Verification Plan
1. Reproduce at next hour boundary and confirm log line `Saving completed hour: timestamp=...` matches `currentHourTimestamp - 1h`.
2. Verify DB entry exists for that hour (History list shows the hour row).
3. Confirm History list includes 23:00 after midnight.
4. Ensure no duplicate writes from receiver/worker triggers.

## 🟡 BUG: UI Resume Overwrites Correct FG Service Baseline (Feb 5, 2026)

### Symptom
At ~7:30am, notification showed ~100 steps for current hour and ~216 for daily total. Opening the app reset both UI and notification to 0 steps for current hour.

### Evidence from Device Logs (07:39)
```
Sensor fired: absolute=40871 | hourBaseline=40865 | delta=6 | initialized=true
History loaded: 06:00: 0, 05:00: 0, 04:00: 0, 03:00: 0, 02:00: 0, 01:00: 84, 00:00: 25
```
The `hourBaseline=40865` is only 6 steps behind `absolute=40871`. If ~100 steps were accumulated, baseline should have been ~40771. Something reset the baseline to a recent value when the app opened.

### Root Cause
In `StepCounterViewModel.kt` lines 721-736, `handleUiResumeClosure()` unconditionally resets the sensor baseline when it detects an hour change:

```kotlin
if (currentHourTimestamp != savedHourTimestamp) {
    // Hour changed - set new baseline
    preferences.saveHourData(
        hourStartStepCount = currentDeviceTotal,  // BUG: Overwrites correct baseline!
        ...
    )
    sensorManager.setLastHourStartStepCount(currentDeviceTotal)  // BUG: Resets sensor too!
}
```

**What happens:**
1. FG service correctly processed 7:00 boundary, set baseline to (e.g.) 40765
2. FG service tracks steps: 40765 → 40865 = ~100 steps for 7am hour
3. Preferences `currentHourTimestamp` may lag behind (still 06:00)
4. User opens app at 7:30, UI detects "hour changed" (07:00 ≠ 06:00)
5. UI overwrites baseline to 40865 (current total), wiping out ~100 steps
6. Both UI and notification now show 0 steps

### Status
✅ Implemented (UI no longer resets baselines on resume; service remains source of truth).

### Proposed Fix (Task)
**Key principle:** UI must remain read-only per the "Service-Only DB Writes" architecture. UI should NEVER reset baselines or call `saveHourData()`. The FG service is the sole owner of baselines and DB writes.

**Fix:** Remove the baseline reset logic from `handleUiResumeClosure()` entirely. If sensor state is valid (FG service running), trust it. If not, do nothing and let the FG service/worker reconcile later.

```kotlin
// REPLACE lines 721-736 in handleUiResumeClosure() with:
if (currentHourTimestamp != savedHourTimestamp) {
    val state = sensorManager.sensorState.value
    val sensorBaseline = state.lastHourStartStepCount
    val sensorCurrentSteps = state.currentHourSteps
    val sensorInitialized = state.isInitialized

    // Only trust sensor state if:
    // 1. Sensor is initialized (FG service has set it up)
    // 2. Baseline is positive (valid tracking in progress)
    // 3. Current hour steps are reasonable
    if (sensorInitialized && sensorBaseline > 0 &&
        sensorCurrentSteps in 0..StepTrackerConfig.MAX_STEPS_PER_HOUR) {
        // FG service has valid tracking - UI preserves state, does NOT reset
        android.util.Log.i("StepCounter",
            "Post-UI-closure: FG service has valid tracking (baseline=$sensorBaseline, " +
            "currentHourSteps=$sensorCurrentSteps). UI preserving state.")
        // Only sync timestamp preference (no baseline change)
        preferences.saveCurrentHourTimestamp(currentHourTimestamp)
    } else {
        // Sensor not initialized or stale - UI does NOT reset
        // Let FG service or HourBoundaryCheckWorker reconcile later
        android.util.Log.w("StepCounter",
            "Post-UI-closure: Sensor not initialized or stale (initialized=$sensorInitialized, " +
            "baseline=$sensorBaseline). UI will NOT reset baseline. " +
            "Relying on FG service/worker to reconcile.")
        // DO NOT call: preferences.saveHourData(...)
        // DO NOT call: sensorManager.setLastHourStartStepCount(...)
        // DO NOT call: sensorManager.markInitialized()
    }
}
```

### Rationale (UI Read-Only Architecture)
Per the "🟡 ARCHITECTURE: Service-Only DB Writes" decision:
- UI should be read-only and never write hourly records or reset baselines
- FG service is the sole owner of baselines and DB writes
- If FG service missed something, HourBoundaryCheckWorker will catch up

### Files to Modify
- `app/src/main/java/com/example/myhourlystepcounterv2/ui/StepCounterViewModel.kt` (lines 721-736)
  - Remove `preferences.saveHourData()` call entirely
  - Remove `sensorManager.setLastHourStartStepCount()` call entirely
  - Remove `sensorManager.markInitialized()` call entirely
  - Keep only timestamp sync (when valid) and logging

### Verification Plan
1. Walk around with app closed, notification visible showing accumulating steps
2. Cross an hour boundary (e.g., 7:00 → 7:30)
3. Note notification step count before opening app
4. Open app and verify step count preserved
5. Check logs for: `Post-UI-closure: Preserving FG service baseline` (expected after fix)
6. Verify no regression: fresh app start after reboot still initializes correctly

## 🟢 FIXED: Deduplication Blocking ALL Hour Boundaries (Feb 3, 2026)

### Status
- **Fix Implemented:** ✅ Corrected timestamp comparison logic in `StepCounterForegroundService.kt` and `HourBoundaryReceiver.kt`.
- **Tests Added:** ✅ Unit tests added to `HourBoundaryLogicTest.kt` covering the deduplication scenarios (success, skip, and stale saved timestamp).
- **Verification:** ✅ Build passed, tests passed.

### Symptoms Reported (Resolved)
- Notification shows hourly=867, daily=867 (same value - WRONG)
- ViewModel correctly calculates daily=7356, but notification ignores it
- History tab shows yesterday's (Feb 2nd) entries, not today's
- Hour boundary at 10:00 AM was SKIPPED with message "already processed"
- App thinks it's still Feb 2nd 22:00 (12 hours behind!)

### Evidence from Device Logs
```
Today: Feb 3rd at 10:00 AM

App state (WRONG):
  currentHourTimestamp = Mon Feb 02 22:00:00 GMT (12 hours stale!)
  startOfDay = Mon Feb 02 00:00:00 GMT (yesterday!)
  lastProcessedBoundaryTimestamp = 1770069600000 (Feb 02 22:00)

10:00:00 Hour boundary reached at Tue Feb 03 10:00:00 GMT 2026
10:00:00 handleHourBoundary: Hour 1770069600000 already processed, skipping
10:00:00 ✅ Hour boundary completed successfully  ← FALSE! It was skipped!

FG Service: Calculated: dbTotal=0, currentHour=867, daily=867   ← WRONG
ViewModel:  Daily total calculated: dbTotal=6489, final=7356    ← CORRECT
```

### Root Cause Analysis

The deduplication logic added to prevent double-processing is **comparing the wrong values**.

**Current (BROKEN) logic in `handleHourBoundary()`:**
```kotlin
val previousHourTimestamp = preferences.currentHourTimestamp.first()  // Feb 02 22:00
val effectiveLastProcessed = maxOf(lastProcessed, lastProcessedBoundaryTimestamp)  // Feb 02 22:00

if (previousHourTimestamp <= effectiveLastProcessed) {
    Log.d("...", "Hour $previousHourTimestamp already processed, skipping")
    return  // SKIPS EVERYTHING!
}
```

**The problem:** This checks if `previousHourTimestamp` (the SAVED hour from preferences) was already processed. But that saved value is stale (Feb 02 22:00). The check should verify if the CURRENT hour (Feb 03 10:00) was already processed.

**Why notification shows wrong daily:**
The FG service uses the stale `currentHourTimestamp` (Feb 02 22:00) in its DB query:
```kotlin
repository.getTotalStepsForDayExcludingCurrentHour(startOfDay, currentHourTimestamp)
// startOfDay = Feb 02 00:00, currentHourTimestamp = Feb 02 22:00
// This excludes ALL hours from Feb 02 22:00 onward, returning dbTotal=0
```

### Fix Required

#### Step 1: Fix deduplication logic

**File:** `StepCounterForegroundService.kt` - `handleHourBoundary()`

Change FROM:
```kotlin
val previousHourTimestamp = preferences.currentHourTimestamp.first()
if (previousHourTimestamp <= effectiveLastProcessed) {
    // Skip
}
```

Change TO:
```kotlin
// Calculate the CURRENT hour timestamp (what we're about to process)
val currentHourTimestamp = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

// Only skip if THIS hour was already processed
if (currentHourTimestamp <= effectiveLastProcessed) {
    Log.d("...", "Current hour $currentHourTimestamp already processed, skipping")
    return
}
```

**File:** `StepCounterForegroundService.kt` - `checkMissedHourBoundaries()`

Same fix - compare against the calculated current hour, not the saved timestamp.

**File:** `HourBoundaryReceiver.kt` - `processHourBoundary()` and `checkForMissedBoundaries()`

Same fix in both methods.

#### Step 2: Ensure day boundary is detected

The day boundary detection should trigger when `currentHourTimestamp` crosses midnight. Currently it may not be triggering because hour boundaries are being skipped.

After fixing the deduplication, verify that when the first hour boundary of a new day is processed, it:
1. Updates `lastStartOfDay` preference to today
2. Resets the daily total calculation

### Files to Modify

1. **`app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`**
   - `handleHourBoundary()`: Fix deduplication to compare current hour, not saved hour
   - `checkMissedHourBoundaries()`: Same fix

2. **`app/src/main/java/com/example/myhourlystepcounterv2/notifications/HourBoundaryReceiver.kt`**
   - `processHourBoundary()`: Fix deduplication to compare current hour, not saved hour
   - `checkForMissedBoundaries()`: Same fix

### Verification Plan

1. Apply fixes and rebuild: `./gradlew installDebug`
2. Force stop app and reopen
3. Check logs - `currentHourTimestamp` should update to today's current hour
4. Check notification - daily total should be higher than hourly total
5. Check History tab - should show today's date (Feb 3rd)
6. Wait for next hour boundary (e.g., 11:00)
7. Verify logs show "Hour boundary completed" WITHOUT "already processed, skipping"
8. Verify database has new entry for the completed hour

### DO NOT COMMIT current working dir changes until this is fixed!

The current uncommitted changes fix Bug #1 (goAsync crash) but introduce Bug #2 (deduplication blocking everything).

---

## 🟢 FIXED: goAsync() Double-Call Crash (Feb 2, 2026)

**Status:** Fixed in working directory (uncommitted)

The `existingPendingResult` parameter fix is correct and should be kept. The issue is the deduplication logic that was added alongside it.

---

## 🟡 PREVIOUS BUG: Double Counting & Zero Notifications (Feb 1, 2026)

### Symptoms Reported
1. **Notifications show 0 steps** even though steps have been taken
2. **Opening app fixes the 0 steps issue**
3. **Today's daily total was DOUBLE Samsung Health's count**

### Root Cause Analysis (from device logs)

#### Issue 1: Double Counting - Race Condition at Hour Boundary

**Evidence from logs at 09:00:00:**
```
09:00:00.008 Saving completed hour: timestamp=1769932800000 (08:00), steps=963
09:00:00.018 Service restart detected: missed 1 hour boundaries
09:00:00.020 Saving missed hour: timestamp=1769932800000, steps=963
```

Same 8am timestamp saved TWICE with same 963 steps = daily total doubled.

**Why it happens:** Too many overlapping mechanisms trigger at hour boundary:

| Location | What | When |
|----------|------|------|
| `StepCounterForegroundService.kt:522` | `handleHourBoundary()` | Loop reaches XX:00 |
| `StepCounterForegroundService.kt:486` | `checkMissedHourBoundaries()` | Loop start |
| `StepCounterForegroundService.kt:496` | `checkMissedHourBoundaries()` | Every loop iteration |
| `StepCounterForegroundService.kt:125` | `checkMissedHourBoundaries()` | Every `onStartCommand()` |
| `HourBoundaryReceiver.kt:35` | `processHourBoundary()` | AlarmManager XX:00 |
| `HourBoundaryReceiver.kt:39` | `checkForMissedBoundaries()` | Every 15 min |

All of these can fire within milliseconds of each other at hour boundaries, causing duplicate saves.

**Database protection insufficient:** `saveHourlyStepsAtomic()` only keeps HIGHER value. When both saves have SAME value (963), both succeed.

#### Issue 2: Notifications Showing 0 Steps

**Cause:** Timing issue with 3-second throttle + sensor reset

**Flow at hour boundary:**
```
09:00:00.000 - handleHourBoundary() → resetForNewHour() → currentStepCount = 0
09:00:00.016 - Flow emits with currentStepCount = 0 (sensor hasn't fired yet)
09:00:00.219 - Notification shows "0 steps" (throttled update)
09:00:XX.XXX - Sensor finally fires with new reading
09:00:03.XXX - Next throttled update might show correct value
```

The 3-second throttle (`sample(3.seconds)` at line 92) combined with sensor reset delay means notifications can show 0 for up to several seconds after hour boundary.

### Proposed Fixes

#### Fix 1: Prevent Duplicate Hour Boundary Processing

**Changes to `StepCounterForegroundService.kt`:**

1. **Remove redundant check from loop iteration** (line 496)
   - DELETE `checkMissedHourBoundaries()` call in the loop body
   - Keep only the initial check at loop start (line 486)

2. **Add timestamp-based deduplication:**
   ```kotlin
   // Add field at class level
   @Volatile private var lastProcessedBoundaryTimestamp: Long = 0

   // In handleHourBoundary(), after saving:
   lastProcessedBoundaryTimestamp = previousHourTimestamp

   // In checkMissedHourBoundaries(), add guard:
   if (savedHourTimestamp <= lastProcessedBoundaryTimestamp) {
       Log.d("...", "Hour $savedHourTimestamp already processed, skipping")
       return
   }
   ```

3. **Improve database protection:**
   - Change `saveHourlyStepsAtomic()` to track if it actually inserted vs skipped
   - Log when duplicate save is detected

#### Fix 2: Prevent Zero Notification After Hour Boundary

**Options:**
1. **Force immediate update after reset:** After hour boundary processing, manually update notification with known values (0 for hour, calculated daily from DB)
2. **Reduce initial throttle:** Use 1-second sample for first 5 seconds after hour boundary
3. **Simpler:** Accept brief 0 display as expected behavior (UI will correct within 3 seconds)

Recommendation: Option 1 - force immediate update is cleanest.

#### Fix 3: Simplify Defense-in-Depth Architecture

Current system has TOO MANY overlapping mechanisms causing race conditions. Simplify to:

| Mechanism | Role | When |
|-----------|------|------|
| FG Service Loop | **Primary** | Always running |
| AlarmManager XX:00 | **Backup** | Only if FG service not running |
| WorkManager 15 min | **Safety net** | Check only, don't process if recent |

Remove redundant checks from:
- Every loop iteration (line 496) - DELETE
- `onStartCommand()` (line 125) - KEEP but add cooldown

### Files to Modify

1. **`StepCounterForegroundService.kt`**
   - Line 496: Remove `checkMissedHourBoundaries()` call
   - Add `lastProcessedBoundaryTimestamp` field
   - Add timestamp guard in both boundary methods
   - Force notification update after hour boundary

2. **`HourBoundaryReceiver.kt`** (optional)
   - Add check: skip processing if foreground service handled it recently

### Verification Steps

1. **Test double counting:**
   ```bash
   adb logcat | grep -E "Saving completed|Saving missed"
   ```
   Should only see ONE save per hour, not two.

2. **Compare with Samsung Health:** Run overnight, compare daily totals

3. **Test zero notifications:** Watch notification at XX:00, should update within 3 seconds

---

## Previous Status (as of 2026-01-23)

## What's Implemented (Code Complete)

✅ **Bug #1: Hour transition flag stuck**
- ForegroundService.kt: try-finally pattern (lines 346-374)
- ⚠️ **HourBoundaryReceiver.kt: STILL HAS BUG** (line 101 early return without endHourTransition)

✅ **Bug #2: Notification rate limiting**
- Throttling with `.sample(3.seconds)` implemented (ForegroundService.kt line 92)

✅ **Bug #3: Daily total not resetting at midnight**
- Day boundary detection in ForegroundService (lines 323-342)

✅ **Bug #4: Hour boundary loop crashes**
- Multi-layer error recovery implemented (lines 387-497)
- Health check method added (lines 502-520)
- ⚠️ **NO UNIT TESTS WRITTEN** (0/5 tests completed)

✅ **Bug #5 Phase 1: AlarmManager fixes**
- Already using setExactAndAllowWhileIdle() (AlarmScheduler.kt lines 58, 139)
- Heartbeat logging added (ForegroundService.kt lines 456-459)
- ⚠️ **DIDN'T PREVENT JAN 22 FAILURE** (53 steps still missing)

✅ **Bug #6: Service behavior when killed by OS**
- When the foreground service is killed by the OS and restarts hours later, it detects missed hour boundaries and saves the accumulated steps to the last missed hour
- This preserves the total step count but attributes all missed steps to a single hour
- This is the intended behavior to maintain data integrity while simplifying the logic
- The total daily step count remains accurate, which is the primary goal

## Active Issues Requiring Immediate Action

❌ **Jan 22 Missing Steps: 53 steps from 7am hour not tracked**
- Expected: 447 steps in 8-9am hour
- Actual: 394 steps recorded
- Database: NO 7am entry found
- Root cause: Hour boundary at 8:00 AM was NOT processed

❌ **HourBoundaryReceiver.kt Line 101 Bug**
- Early return without calling endHourTransition() in finally block
- This is the SAME bug as Bug #1 that was fixed in ForegroundService
- Likely cause of Jan 22 failure

❌ **checkMissedHourBoundaries() Only Called Once**
- Currently only runs when service starts
- If service running but loop stuck, missed boundaries not caught
- Need to call on every service interaction

## Root Cause Analysis Needed

🔍 **Why did 8:00 AM hour boundary fail on Jan 22?**

Possible causes:
1. **HourBoundaryReceiver bug** (line 101) caused it to exit without processing
2. **ForegroundService loop was stuck** despite error recovery
3. **Both mechanisms failed** simultaneously (unlikely)
4. **Doze mode prevented AlarmManager** despite setExactAndAllowWhileIdle()

**Action required**: Pull device logs from Jan 22 7:55-8:05 AM to diagnose

## IMMEDIATE PRIORITY ORDER

### 🔴 Priority 1: Fix HourBoundaryReceiver.kt Bug (CRITICAL)
**Time**: 10 minutes
**Status**: ✅ ALREADY COMPLETE
**Issue**: Line 101 returns early without calling endHourTransition() in finally block

**Resolution**:
- ✅ Verified try-finally pattern is already correctly implemented (lines 113-132)
- ✅ Kotlin language guarantees finally blocks execute before labeled returns like `return@launch`
- ✅ `endHourTransition()` will always be called even with early return at line 121
- ✅ This was NOT the cause of Jan 22 failure - the code was already correct

**Note**: This was a false alarm from the initial analysis. The Kotlin semantics ensure the finally block executes before any return statement.

---

### 🟡 Priority 2: Add checkMissedHourBoundaries() Everywhere
**Time**: 20 minutes
**Status**: ✅ COMPLETE (2026-01-22 09:30)

**Actions Completed**:
1. ✅ Added `checkMissedHourBoundaries()` to `onStartCommand()` (StepCounterForegroundService.kt:118-126)
   - Checks for missed boundaries whenever service receives any command
   - Provides recovery when service restarts or receives intents
2. ✅ Added `checkMissedHourBoundaries()` to loop iterations (StepCounterForegroundService.kt:458-464)
   - Checks at start of each hour boundary loop iteration
   - Self-healing: detects gaps even if loop was running
3. ✅ Added missed boundary detection to HourBoundaryReceiver (HourBoundaryReceiver.kt:53-64)
   - Detects when AlarmManager fires but multiple hours were missed
   - Logs warning: `"⚠️ Detected $hoursDifference missed hours!"`
4. ✅ App built and installed successfully

**Benefits**: Defense in depth with 4 independent recovery points:
- Service startup (onStartCommand)
- Each loop iteration
- AlarmManager backups
- ForegroundService initial loop start

**Why important**: Multiple independent layers of protection ensure missed boundaries are caught

---

### 🟡 Priority 3: Diagnostic Investigation
**Time**: 30 minutes
**Status**: ✅ COMPLETE (2026-01-22 09:35)

**Actions Completed**:
1. ✅ Analyzed alarm history via `adb shell dumpsys alarm`
2. ✅ Queried database for Jan 22 entries
3. ✅ Identified 6-hour gap (4am-9am) with no alarms

**Critical Findings**:

**Database Status**:
- Last entry: `2026-01-18 19:00:00` (746 steps)
- **NO entries for Jan 19, 20, 21, or 22**

**Alarm History**:
```
✅ 02:00 AM - Alarm fired successfully
✅ 03:00 AM - Alarm fired successfully
❌ 04:00 AM - NO ALARM (scheduling stopped)
❌ 05:00 AM - NO ALARM
❌ 06:00 AM - NO ALARM
❌ 07:00 AM - NO ALARM ← This is why 53 steps missing
❌ 08:00 AM - NO ALARM
✅ 09:00 AM - Alarm scheduled (after app opened at 8:44 AM)
```

**Root Cause Identified**:
- Hour boundary processing at 3:00 AM succeeded
- BUT failed to reschedule next alarm for 4:00 AM
- Foreground service loop likely crashed or stopped
- No backup mechanism detected the failure
- 6-hour gap resulted in missed data

**Why Priority 2 Fixes This**:
Our defensive code adds 4 independent recovery points:
1. Service restart checks (onStartCommand)
2. Loop iteration checks
3. Multi-hour gap detection (HourBoundaryReceiver)
4. Initial startup checks

These will prevent recurrence by catching gaps from multiple angles.

---

### 🟢 Priority 4: Write Unit Tests for Bug #4
**Time**: 2-3 hours
**Status**: 0/5 tests written

**Tests needed** (from Bug #4 section below):
1. Hour boundary loop survives database exception
2. Hour boundary loop survives sensor exception
3. Hour boundary loop restarts after crash
4. Hour boundary loop stops after 10 failures
5. CancellationException stops loop immediately

**Why important**: Verify error recovery actually works

---

### 🟢 Priority 5: Overnight Verification Test
**Time**: 8+ hours (mostly waiting)
**Status**: NOT DONE

**Test**: Let app run overnight with both switches ON
- Verify all hour boundaries processed
- Verify database has entries for every hour
- Compare totals with Samsung Health
- Check for any missing steps

**Why important**: Real-world validation of all fixes

---

### ⚪ Priority 6: Phase 2 WorkManager Backup (Optional)
**Time**: 45 minutes
**Status**: NOT IMPLEMENTED

**Action**: Add periodic WorkManager job (every 15 min) to check for missed boundaries

**Why optional**: Priorities 1-3 should fix the issue; this is additional safety

---

## Decision Points

**Answered** (Priorities 1-3 complete):
1. ✅ **What caused Jan 22 failure?** Alarm scheduling stopped after 3:00 AM, causing 6-hour gap (4am-9am)
2. ✅ **HourBoundaryReceiver bug?** No - try-finally was already correct (Kotlin semantics)
3. ✅ **Did loop survive?** No - loop stopped rescheduling alarms after 3:00 AM, causing cascading failure

**Next Decisions** (After Priority 4-5):
- ⏳ Do we need WorkManager backup? (Priority 6) - **Probably YES**, for additional redundancy
- ⏳ Do we need Samsung Health integration? (Bug #5 Phase 3) - Monitor first
- ⏳ Are current defensive fixes sufficient? - **Test overnight** (Priority 5) to verify

---

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

### Step 2: Fix HourBoundaryReceiver.kt (10 min) ✅ IMPLEMENTED
**File**: `app/src/main/java/com/example/myhourlystepcounterv2/notifications/HourBoundaryReceiver.kt`
**Location**: Lines 92-122
**Problem**: Line 101 has early return without calling `endHourTransition()`

**Status**: ✅ Implemented with try-finally pattern (lines 95-122)

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

### Step 3: Fix StepCounterForegroundService.kt (10 min) ✅ IMPLEMENTED
**File**: `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`
**Location**: Lines 344-374 (current)
**Problem**: Line 350 has early return without calling `endHourTransition()`

**Solution**: Same try-finally pattern as Step 2

**Status**: ✅ Implemented with try-finally pattern (lines 347-374)

### Step 4: Add Defensive Logging (5 min) ✅ IMPLEMENTED
**File**: `app/src/main/java/com/example/myhourlystepcounterv2/sensor/StepSensorManager.kt`

**Status**: ✅ Already implemented in StepSensorManager.kt

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

## Implementation Status - Bug #1 (Hour Transition Flag)

**Status**: ✅ FULLY IMPLEMENTED

**Steps Completed**:
- ✅ Step 1: Bug verified via log analysis
- ✅ Step 2: HourBoundaryReceiver.kt fixed with try-finally pattern (lines 95-122)
- ✅ Step 3: StepCounterForegroundService.kt fixed with try-finally pattern (lines 361-388)
- ✅ Step 4: Defensive logging added to StepSensorManager.kt (lines 270, 283, 78)

## Success Criteria
- ✅ Sensor events fire continuously (no stuck 0 values)
- ✅ Hour boundaries processed without breaking sensor
- ✅ Duplicate processing handled gracefully (try-finally ensures cleanup)
- ✅ No stuck transition flags (endHourTransition() always called in finally block)
- ✅ Opening app doesn't trigger sudden step jumps
- ✅ Steps taken between 9:01-9:10 are correctly tracked
- ✅ Defensive logging in place to debug future issues

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

---

# Notification Rate Limiting Issue

## Date
2026-01-19

## Issues Identified

### 1. Critical: Notification Rate Limiting
**Severity**: Critical
**Location**: `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt:62-95`

**Problem**:
- The foreground service updates the notification on **every sensor event** without throttling
- The step sensor with `SENSOR_DELAY_UI` emits events at ~60Hz (60 times per second)
- This causes Android's NotificationService to drop notifications with error:
  ```
  E NotificationService: Package enqueue rate is 6.468354. Shedding notifications
  ```

**Evidence from logs**:
```
01-19 21:59:32.792 - 21:59:44.521 (12 seconds): 20+ notification updates
E NotificationService: Package enqueue rate is 6.468354. Shedding 0|com.example.myhourlystepcounterv2|42|null|10719
E AppOps: attributionTag not declared in manifest of com.example.myhourlystepcounterv2 (repeated)
```

**Root Cause**:
```kotlin
// Line 62-95: Combines flow without throttling
combine(
    sensorManager.currentStepCount,  // Emits on every sensor event (~60Hz)
    preferences.currentHourTimestamp,
    preferences.useWakeLock
) { currentHourSteps, currentHourTimestamp, useWake ->
    // Calculates daily total and updates notification
}.collect { (currentHourSteps, dailyTotal, useWake) ->
    // PROBLEM: Updates notification immediately on every emission
    val notification = buildNotification(currentHourSteps, dailyTotal)
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID, notification)  // Called 60+ times/second!
}
```

**Impact**:
- User sees incomplete/stale step counts due to dropped notifications
- Unnecessary battery drain from excessive notification processing
- Poor user experience
- System logs flooded with AppOps warnings

---

### 2. Minor: AppOps Warning
**Severity**: Minor (informational)
**Location**: System logs

**Problem**:
```
E AppOps: attributionTag not declared in manifest of com.example.myhourlystepcounterv2
```

**Impact**:
- Harmless warning, no functional impact
- Can be safely ignored or fixed later by adding attribution tags to manifest

---

## Proposed Solution

### Fix #1: Throttle Notification Updates (Critical)

**Strategy**: Add flow throttling to limit notification updates to **once every 3 seconds** (aligns with spec requirement for 3-second display updates).

**Implementation**:
1. Import Kotlin Flow `sample()` operator
2. Apply `sample(3.seconds)` to the combined flow before collecting
3. This ensures notifications update at most once every 3 seconds regardless of sensor frequency

**Code Changes**:

**File**: `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`

**Imports to add** (after line 19):
```kotlin
import kotlinx.coroutines.flow.sample
import kotlin.time.Duration.Companion.seconds
```

**Replace lines 62-95** with:
```kotlin
// Observe flows and update notification / wake-lock accordingly
scope.launch {
    combine(
        sensorManager.currentStepCount,
        preferences.currentHourTimestamp,
        preferences.useWakeLock
    ) { currentHourSteps, currentHourTimestamp, useWake ->
        android.util.Log.d("StepCounterFGSvc", "Live sensor: currentHourSteps=$currentHourSteps")

        // Calculate start of day
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Get daily total from database (excluding current hour)
        val dbTotal = repository.getTotalStepsForDayExcludingCurrentHour(startOfDay, currentHourTimestamp).first() ?: 0
        val dailyTotal = dbTotal + currentHourSteps

        android.util.Log.d("StepCounterFGSvc", "Calculated: dbTotal=$dbTotal, currentHour=$currentHourSteps, daily=$dailyTotal")
        Triple(currentHourSteps, dailyTotal, useWake)
    }
    .sample(3.seconds)  // THROTTLE: Only emit once every 3 seconds
    .collect { (currentHourSteps, dailyTotal, useWake) ->
        android.util.Log.d("StepCounterFGSvc", "Notification update (throttled): currentHour=$currentHourSteps, daily=$dailyTotal")

        // Update notification with correct daily total
        val notification = buildNotification(currentHourSteps, dailyTotal)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)

        // Handle wake-lock
        handleWakeLock(useWake)
    }
}
```

**Key Change**: Added `.sample(3.seconds)` operator after the `combine()` and before `.collect()`.

**Benefits**:
- Reduces notification updates from ~60/second to ~0.33/second (20 per minute)
- Eliminates Android rate limiting errors
- Maintains responsive UI updates (3 seconds is still frequent enough)
- Significantly reduces battery consumption
- Aligns with CLAUDE.md spec: "display should update every 3 seconds"

---

### Fix #2: Suppress AppOps Warning (Optional)

**Strategy**: Add attribution tags to AndroidManifest.xml (low priority).

**Implementation**: Can be addressed later if needed. Not functionally impactful.

---

## Testing Plan

### Test #1: Verify Notification Throttling
1. Build and install the app with the fix
2. Enable "Permanent Notification" in Profile tab
3. Monitor logcat for rate limiting errors:
   ```bash
   adb logcat | grep -E "NotificationService|StepCounterFGSvc"
   ```
4. **Expected**:
   - No "Shedding" errors
   - Log shows "Notification update (throttled)" every ~3 seconds
   - Notification displays current step counts correctly

### Test #2: Verify UI Still Updates Correctly
1. Open the app to Home screen
2. Walk around and watch the step count
3. **Expected**:
   - Hourly step count updates in real-time (not throttled in UI)
   - Notification updates every 3 seconds
   - Daily total remains accurate

### Test #3: Battery Impact
1. Run app for 1 hour with permanent notification enabled
2. Check battery usage in Android Settings
3. **Expected**:
   - Reduced battery consumption compared to before fix
   - App should not appear in "high battery usage" warnings

### Test #4: Hour Boundary
1. Wait for hour boundary (XX:00)
2. Verify previous hour is saved correctly
3. Verify notification updates show 0 for new hour
4. **Expected**:
   - No notification rate limiting errors during hour transition
   - Data integrity maintained

### Test #5: Background Operation
1. Open app, then switch to another app (background the step counter)
2. Monitor for 10 minutes
3. **Expected**:
   - Notification continues updating every 3 seconds
   - No rate limiting errors in background

---

## Rollback Plan

If the fix causes issues:
1. Revert the changes to `StepCounterForegroundService.kt`
2. Remove the added imports
3. Rebuild and deploy

The change is isolated to one file and one flow, making rollback straightforward.

---

## Alternative Solutions Considered

### Alternative 1: Increase sample rate to 5 seconds
- **Pros**: Even less battery usage
- **Cons**: Less responsive, deviates from 3-second spec
- **Decision**: Stick with 3 seconds per spec

### Alternative 2: Use `debounce()` instead of `sample()`
- **Pros**: Only updates when value stabilizes
- **Cons**: Could delay updates unnecessarily during continuous walking
- **Decision**: `sample()` is better for periodic updates

### Alternative 3: Manually track last notification time
- **Pros**: More control
- **Cons**: More complex, reinvents flow operators
- **Decision**: Use built-in `sample()` for simplicity

---

## Risk Assessment

**Risk Level**: Low

**Risks**:
- Notification might feel slightly less "real-time" (mitigated: 3 seconds is still very responsive)
- Edge case: If user stops walking and immediately checks notification, might see old count for up to 3 seconds (acceptable trade-off)

**Mitigation**:
- The main UI (HomeScreen) still updates in real-time via ViewModel
- Notification is for glanceable updates, not critical data
- 3-second delay is within acceptable UX range

---

## Implementation Checklist

- [x] Add `sample` and `Duration` imports to StepCounterForegroundService.kt
- [x] Add `.sample(3.seconds)` operator to the flow pipeline
- [x] Update log message to indicate "throttled" updates
- [x] Build and test on device
- [x] Monitor logcat for rate limiting errors (should be eliminated)
- [x] Verify notification updates correctly every 3 seconds
- [ ] Verify UI still updates in real-time (pending longer test)
- [ ] Test hour boundary transition (pending next hour)
- [ ] Test background operation for 30 minutes (pending)
- [ ] Check battery usage improvement (pending)
- [ ] Update CLAUDE.md if needed (document throttling behavior)
- [ ] Commit changes with descriptive message

---

## Implementation Status

**Status**: ✅ IMPLEMENTED (2026-01-20)

**Changes Made**:
- Added `kotlinx.coroutines.flow.sample` and `kotlin.time.Duration.Companion.seconds` imports to `StepCounterForegroundService.kt` (lines 20, 22)
- Added `.sample(3.seconds)` operator to notification flow (line 92)
- Updated log message to show "(throttled 3s)" indicator (line 94)
- Throttling reduces notification updates from ~60/second to ~0.33/second (once every 3 seconds)

**Verification Results**:
- ✅ Foreground service confirmed running with notification ID 42
- ✅ No "Package enqueue rate" errors in logcat
- ✅ No "Shedding" messages for this app
- ✅ Service has been stable for 2+ minutes without errors

**Next Steps**:
- Monitor battery usage over next few hours
- Verify notification updates remain accurate during normal use
- Test hour boundary transitions

---

## Success Criteria

1. ✅ No more "Package enqueue rate" or "Shedding" errors in logcat
2. ✅ Notification updates throttled to once every 3 seconds
3. ⏳ Step counts remain accurate (pending verification)
4. ⏳ UI performance unchanged (pending verification)
5. ⏳ Battery usage reduced (pending long-term test)
6. ⏳ Hour boundaries process correctly without errors (pending next hour)

---

## Notes

- The HomeScreen ViewModel is NOT affected by this change—it still gets real-time updates directly from `sensorManager.currentStepCount`
- Only the background notification service is throttled
- The 3-second throttle matches the spec requirement: "display should update every 3 seconds with the latest step total"
- This fix is independent of the hour transition flag bug documented above and can be implemented separately

---

# Day Boundary Bug - Daily Total Not Resetting at Midnight

## Date
2026-01-20

## Issue Identified

### Critical: Daily Total Shows Yesterday's Steps After Midnight

**Severity**: Critical
**Location**: `app/src/main/java/com/example/myhourlystepcounterv2/ui/StepCounterViewModel.kt:385-408`

**Problem**:
- At midnight (00:12), notification correctly shows 0 steps for current hour
- However, daily total shows 11,676 steps (from yesterday) instead of 0
- Root cause: `preferences.lastStartOfDay` is only updated when ViewModel initializes (when user opens app)
- If user doesn't open app at midnight, `lastStartOfDay` remains stuck at old date
- SQL query uses old `startOfDay` value, summing ALL historical records instead of just today's

**Evidence from logs and database**:
```
01-20 00:12:48: displayedDaily=11676 (should be 0)
Database query: SELECT SUM(stepCount) WHERE timestamp >= [OLD_DATE]
Result: Sums up all records from days ago, not just today
```

**Root Cause Code**:
```kotlin
// StepCounterViewModel.kt line 392
val effectiveStartOfDay = if (storedStartOfDay > 0) storedStartOfDay else getStartOfDay()

// Problem: storedStartOfDay comes from preferences.lastStartOfDay
// This preference is ONLY updated in ViewModel.initialize() (line 382)
// If app is not opened at midnight, it never updates!
```

**Impact**:
- User sees incorrect daily totals after midnight
- Notification shows wrong daily step count
- Data appears corrupted
- User experience severely degraded

---

## Proposed Solution

### Add Day Boundary Detection to Foreground Service

**Strategy**: Update `lastStartOfDay` preference at midnight even when app is in background.

**Implementation**:
1. Modify `StepCounterForegroundService.handleHourBoundary()` method
2. Add check for midnight (hour == 0)
3. Calculate current `startOfDay` timestamp
4. Call `preferences.saveStartOfDay(startOfDay)` to update the preference
5. Log the day boundary update for debugging

**Code Changes**:

**File**: `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`

**Add after line 339** (after "Processing hour boundary" log):
```kotlin
// Check for day boundary (midnight - hour 0)
val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
if (currentHour == 0) {
    val startOfDay = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    android.util.Log.i(
        "StepCounterFGSvc",
        "DAY BOUNDARY: Updating lastStartOfDay to ${java.util.Date(startOfDay)} (midnight detected)"
    )
    preferences.saveStartOfDay(startOfDay)
}
```

**Benefits**:
- Fixes daily total showing wrong values after midnight
- Works even when app is in background
- Minimal code change (13 lines)
- No impact on existing functionality
- Background service already runs at hour boundaries

---

## Testing Plan

### Test #1: Verify Midnight Reset (Background)
1. Build and install the app with the fix
2. Ensure "Permanent Notification" is enabled
3. Let app run in background overnight
4. At 00:01, check notification daily total
5. **Expected**:
   - Daily total shows 0 (or small number from midnight hour)
   - Logs show "DAY BOUNDARY: Updating lastStartOfDay"

### Test #2: Verify Midnight Reset (Foreground)
1. Open app at 23:58
2. Wait for midnight while watching Home screen
3. **Expected**:
   - Hourly count resets to 0 at 00:00
   - Daily total resets to 0 at 00:00
   - No old steps showing

### Test #3: Compare with Samsung Health
1. After midnight, check both apps
2. **Expected**:
   - Daily totals match (should both be near 0)
   - History shows yesterday's total correctly

### Test #4: Multi-Day Test
1. Run app for 3 consecutive days
2. Never open app between midnight and 1am
3. Check daily totals each morning
4. **Expected**:
   - Each day starts at 0
   - No accumulation from previous days

---

## Implementation Status

**Status**: ✅ IMPLEMENTED (2026-01-20)

**Changes Made**:
- ✅ **v1** (2026-01-20 00:31): Added midnight detection (hour == 0) to `StepCounterForegroundService.handleHourBoundary()`
- ✅ **v2** (2026-01-20 00:50): **IMPROVED** to robust comparison approach (matches ViewModel pattern)
  - Replaced `currentHour == 0` check with `storedStartOfDay != currentStartOfDay` comparison
  - Now handles service restarts at ANY time (not just midnight)
  - Handles timezone changes
  - Handles multi-day gaps (app killed for days)
  - Also initializes `lastStartOfDay` if never set (storedStartOfDay == 0)
- Location: `StepCounterForegroundService.kt:323-342`
- Logs day boundary updates for debugging

**Edge Cases Now Handled (v2)**:
1. ✅ Service restarting at 00:05 (misses midnight, but catches at next hour boundary)
2. ✅ Service restarting at 01:00, 02:00, etc. (catches immediately at next hour)
3. ✅ App killed all day, restarted next afternoon (catches on first hour boundary)
4. ✅ Timezone changes (recalculates startOfDay in current timezone)
5. ✅ Multi-day gaps (stored date compared to current date)
6. ✅ First-time initialization (handles storedStartOfDay == 0)

**Next Steps**:
- ✅ Build and deploy to device
- Monitor logs at next hour boundary to verify detection works
- Monitor logs at next midnight to verify normal midnight case still works

---

## Success Criteria

1. ✅ Code changes implemented
2. ⏳ Daily total shows 0 at midnight (pending midnight test)
3. ⏳ Logs show "DAY BOUNDARY" message at 00:00
4. ⏳ Daily totals match Samsung Health after midnight
5. ⏳ Multi-day test passes (no accumulation)

---

## Notes

- This fix is independent of notification rate limiting and hour transition flag bugs
- The ViewModel's day boundary detection (lines 352-382) still works when app is opened
- Foreground service now provides redundant day boundary detection for background operation
- The fix ensures `lastStartOfDay` is always current, regardless of when user opens app

---

# Bug #4: Hour Boundary Loop Crashes Silently - Missing Steps

## Date
2026-01-20 (Morning)

## Issue Identified

### Critical: Foreground Service Stops Processing Hour Boundaries After Exception

**Severity**: Critical - Data Loss
**Location**: `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt:102-128`

**Problem**:
- Foreground service has an unprotected infinite loop that processes hour boundaries
- If ANY exception occurs during `handleHourBoundary()`, the entire coroutine crashes
- Loop never restarts, causing permanent failure until app is manually reopened
- No error handling, no recovery, no logging of the crash

**User Impact (Real Case)**:
- User had both switches ON in Profile settings
- Service processed 3:00 AM ✅ and 4:00 AM ✅ successfully
- After 4:00 AM: Loop crashed silently ❌
- No hour boundaries processed from 4 AM - 8:44 AM (4.5 hours!)
- User took 460 steps between 8-9 AM
- App only recorded 422 steps (lost 38 steps from 8:00-8:44 AM)
- Database shows NO records saved since January 18 (2 days ago!)
- AlarmManager alarms stopped being rescheduled after 4 AM

**Evidence from logs and alarms**:
```
Alarm History:
- 03:00 AM ✅ ACTION_HOUR_BOUNDARY fired
- 04:00 AM ✅ ACTION_HOUR_BOUNDARY fired
- 05:00 AM ❌ NOT scheduled (loop dead)
- 06:00 AM ❌ NOT scheduled (loop dead)
- 07:00 AM ❌ NOT scheduled (loop dead)
- 08:00 AM ❌ NOT scheduled (loop dead)
- 09:00 AM ⏰ Scheduled (after app reopened at 8:44)

Database:
- Last record: January 18, 19:00 (2 days ago)
- NO records for January 19
- NO records for January 20

Foreground Service:
- createTime=-7h54m36s (running since ~1 AM)
- restartCount=1 (restarted once)
- isForeground=true (claims to be running)
- BUT: No hour boundary processing logs since 4 AM
```

**Root Cause Code**:
```kotlin
// Line 102-128: Unprotected infinite loop
scope.launch {  // ❌ NO ERROR HANDLING
    android.util.Log.i("StepCounterFGSvc", "Hour boundary detection coroutine started")

    checkMissedHourBoundaries()

    while (true) {  // ❌ NO TRY-CATCH
        val now = java.util.Calendar.getInstance()
        val nextHour = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.HOUR_OF_DAY, 1)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        val delayMs = nextHour.timeInMillis - now.timeInMillis

        delay(delayMs)  // ❌ Can throw CancellationException

        // Hour boundary reached - execute logic
        handleHourBoundary()  // ❌ If this throws, loop dies forever
    }
}
```

**Potential Exception Sources**:
1. `delay(delayMs)` - CancellationException if coroutine cancelled
2. `handleHourBoundary()` - Multiple failure points:
   - Database IO exceptions
   - Sensor timeout/access issues
   - DataStore read/write failures
   - Arithmetic exceptions (overflow, division)
   - Calendar/date calculation errors
   - AlarmManager scheduling failures
3. `checkMissedHourBoundaries()` - Same failure points as above

**Impact**:
- **Silent data loss** - no user-visible error
- Steps taken during outage period are permanently lost or misattributed
- Daily totals incorrect
- History incomplete
- App appears to work (notification visible, switches ON) but doesn't track
- Only detected when user manually checks step counts vs other apps

---

## Proposed Solution

### Add Multi-Layer Error Handling with Recovery

**Strategy**: Implement defensive error handling at multiple levels to ensure the hour boundary loop NEVER dies permanently.

**Implementation**:

#### Layer 1: Inner Loop Protection (Per-Iteration Recovery)
Catch exceptions in each iteration but keep loop alive:

```kotlin
scope.launch {
    android.util.Log.i("StepCounterFGSvc", "Hour boundary detection coroutine started")

    try {
        checkMissedHourBoundaries()
    } catch (e: Exception) {
        android.util.Log.e("StepCounterFGSvc", "Error checking missed boundaries", e)
        // Continue anyway - non-fatal
    }

    while (true) {
        try {
            val now = java.util.Calendar.getInstance()
            val nextHour = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.HOUR_OF_DAY, 1)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }

            val delayMs = nextHour.timeInMillis - now.timeInMillis
            android.util.Log.i(
                "StepCounterFGSvc",
                "Next hour boundary in ${delayMs}ms at ${nextHour.time}"
            )

            delay(delayMs)

            android.util.Log.i("StepCounterFGSvc", "Hour boundary reached")
            handleHourBoundary()
            android.util.Log.i("StepCounterFGSvc", "Hour boundary completed successfully")

        } catch (e: CancellationException) {
            // Coroutine cancelled - propagate to outer handler
            android.util.Log.w("StepCounterFGSvc", "Hour boundary loop cancelled", e)
            throw e
        } catch (e: Exception) {
            // Any other exception - log but continue loop
            android.util.Log.e(
                "StepCounterFGSvc",
                "❌ Hour boundary processing failed but loop continues",
                e
            )

            // Wait a bit before retrying (prevent tight error loop)
            delay(60000) // 1 minute backoff
        }
    }
}
```

#### Layer 2: Outer Loop Protection (Full Restart)
Restart entire loop if outer layer fails:

```kotlin
// In onCreate() or startForegroundService()
scope.launch {
    var restartCount = 0
    while (restartCount < 10) {  // Limit restarts to prevent infinite loop
        try {
            startHourBoundaryLoop()  // Extracted method with inner loop
        } catch (e: CancellationException) {
            android.util.Log.w("StepCounterFGSvc", "Hour boundary loop cancelled intentionally")
            break  // Don't restart if intentionally cancelled
        } catch (e: Exception) {
            restartCount++
            android.util.Log.e(
                "StepCounterFGSvc",
                "❌❌ Hour boundary loop crashed! Restart attempt $restartCount/10",
                e
            )
            delay(5000)  // 5 second backoff before restart
        }
    }

    if (restartCount >= 10) {
        android.util.Log.wtf(
            "StepCounterFGSvc",
            "💀 Hour boundary loop failed 10 times - GIVING UP. Service needs restart."
        )
        // TODO: Send notification to user about critical failure
    }
}
```

#### Layer 3: AlarmManager Fallback (Already Exists)
Keep existing AlarmManager scheduling as backup:
- If foreground service loop dies, alarms can still fire
- `HourBoundaryReceiver` can restart service if needed
- Already implemented, just needs to be reliable

**Code Changes**:

**File**: `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`

**Refactor lines 102-128**:
1. Extract hour boundary loop to separate method `startHourBoundaryLoop()`
2. Add try-catch around each iteration (Layer 1)
3. Add outer restart logic (Layer 2)
4. Add backoff delays to prevent tight error loops
5. Add success/failure logging
6. Distinguish CancellationException from other exceptions

**Benefits**:
- Loop survives individual failures
- Automatic recovery from transient errors
- Detailed error logging for debugging
- Prevents silent data loss
- Graceful degradation (backoff delays)
- Limits infinite restart loops (max 10 attempts)

---

## Testing Plan

### Unit Tests (Add to StepCounterForegroundServiceTest.kt)

#### Test 1: Hour Boundary Loop Survives Database Exception
```kotlin
@Test
fun hourBoundaryLoop_survivesIOException() = runTest {
    // Mock repository to throw IOException on first call, succeed on second
    val mockRepository = mock<StepRepository> {
        on { saveHourlySteps(any(), any()) } doThrow IOException("Database full") doReturn Unit
    }

    val service = createServiceWithMocks(repository = mockRepository)

    // Trigger two hour boundaries
    advanceTimeBy(3600000) // 1 hour
    advanceTimeBy(3600000) // 1 hour

    // Verify: First call failed, second succeeded, loop still running
    verify(mockRepository, times(2)).saveHourlySteps(any(), any())
    assertTrue("Loop should still be running", service.isHourBoundaryLoopActive())
}
```

#### Test 2: Hour Boundary Loop Survives Sensor Exception
```kotlin
@Test
fun hourBoundaryLoop_survivesSensorTimeout() = runTest {
    val mockSensor = mock<StepSensorManager> {
        on { getCurrentTotalSteps() } doReturn 0 doReturn 1000 // 0 = timeout, then success
    }

    val service = createServiceWithMocks(sensorManager = mockSensor)

    advanceTimeBy(3600000)
    advanceTimeBy(3600000)

    verify(mockSensor, times(2)).getCurrentTotalSteps()
    assertTrue(service.isHourBoundaryLoopActive())
}
```

#### Test 3: Hour Boundary Loop Restarts After Crash
```kotlin
@Test
fun hourBoundaryLoop_restartsAfterCrash() = runTest {
    var callCount = 0
    val mockRepository = mock<StepRepository> {
        on { saveHourlySteps(any(), any()) } doAnswer {
            callCount++
            if (callCount == 1) throw RuntimeException("Unexpected error")
            Unit
        }
    }

    val service = createServiceWithMocks(repository = mockRepository)

    advanceTimeBy(3600000) // Triggers failure
    advanceTimeBy(60000)   // Backoff period
    advanceTimeBy(3600000) // Next hour - should succeed

    assertEquals(2, callCount, "Should have retried after failure")
}
```

#### Test 4: Hour Boundary Loop Stops After Max Restarts
```kotlin
@Test
fun hourBoundaryLoop_stopsAfter10Failures() = runTest {
    val mockRepository = mock<StepRepository> {
        on { saveHourlySteps(any(), any()) } doThrow RuntimeException("Always fails")
    }

    val service = createServiceWithMocks(repository = mockRepository)

    // Trigger 11 hour boundaries (should stop after 10)
    repeat(11) {
        advanceTimeBy(3600000)
    }

    // Verify: Attempted 10 times, then gave up
    verify(mockRepository, times(10)).saveHourlySteps(any(), any())
    assertFalse("Loop should have stopped", service.isHourBoundaryLoopActive())
}
```

#### Test 5: CancellationException Stops Loop Immediately
```kotlin
@Test
fun hourBoundaryLoop_stopsOnCancellation() = runTest {
    val service = createServiceWithMocks()

    service.stopForegroundService() // Should cancel coroutines

    advanceTimeBy(3600000)

    // Verify: No hour boundary processing attempted after cancellation
    verify(mockRepository, never()).saveHourlySteps(any(), any())
}
```

### Integration Tests

#### Test 6: Real Device - Service Survives Overnight
```kotlin
@Test
@MediumTest
fun foregroundService_processesMultipleHourBoundaries() {
    // Launch app with foreground service
    val scenario = ActivityScenario.launch(MainActivity::class.java)

    // Fast-forward time using TestDispatcher
    repeat(8) { hour ->
        testScheduler.advanceTimeBy(3600000)
        Thread.sleep(100) // Let processing complete

        // Verify database has record for this hour
        val db = getDatabase()
        val recordCount = db.stepDao().getStepsForDay(getTodayStart()).blockingFirst().size
        assertTrue("Should have $hour records", recordCount >= hour)
    }

    scenario.close()
}
```

#### Test 7: Real Device - Service Recovers from Database Lock
```kotlin
@Test
@MediumTest
fun foregroundService_recoversFromDatabaseLock() {
    // Simulate database lock by writing from another thread
    val db = getDatabase()

    // Hold transaction open to block writes
    db.runInTransaction {
        // Trigger hour boundary while transaction is open
        triggerHourBoundary()
        Thread.sleep(2000) // Hour boundary should fail
    }

    // Wait for backoff period
    Thread.sleep(60000)

    // Trigger another hour boundary
    triggerHourBoundary()

    // Verify: Second attempt succeeded
    val records = db.stepDao().getStepsForDay(getTodayStart()).blockingFirst()
    assertTrue("Should have saved after recovery", records.isNotEmpty())
}
```

### Manual Testing Scenarios

#### Scenario 1: Overnight Test
1. Enable "Permanent Notification" in Profile
2. Let device run overnight (at least 8 hours)
3. Check in morning:
   - Database should have records for every hour
   - Alarm history should show hour boundary alarms firing
   - No gaps in step tracking

**Success Criteria**:
- ✅ All hours have database records
- ✅ Step counts match other tracking apps (±10%)
- ✅ No "loop crashed" errors in logcat

#### Scenario 2: Induced Failure Test
1. Fill device storage to 95% capacity
2. Enable foreground service
3. Wait for 2-3 hour boundaries
4. Check if service recovered:
   ```bash
   adb logcat | grep "Hour boundary.*failed but loop continues"
   ```

**Success Criteria**:
- ✅ Error logged but loop continues
- ✅ Later hour boundaries succeed when storage available
- ✅ No permanent failure

#### Scenario 3: Sensor Interference Test
1. Enable foreground service
2. Open Samsung Health during hour boundary
3. Verify hour boundary still processes
4. Check for sensor reset handling logs

**Success Criteria**:
- ✅ Hour boundary completes despite sensor conflict
- ✅ Step counts remain accurate

---

## Monitoring & Diagnostics

### Add Health Check Mechanism

**New Method** (add to StepCounterForegroundService):
```kotlin
private var lastSuccessfulHourBoundary: Long = 0
private var consecutiveFailures: Int = 0

fun isHourBoundaryLoopHealthy(): Boolean {
    val timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulHourBoundary
    val maxGapMs = 2 * 60 * 60 * 1000 // 2 hours

    return timeSinceLastSuccess < maxGapMs && consecutiveFailures < 3
}

// Call this in handleHourBoundary() on success:
private suspend fun handleHourBoundary() {
    try {
        // ... existing logic ...

        lastSuccessfulHourBoundary = System.currentTimeMillis()
        consecutiveFailures = 0
    } catch (e: Exception) {
        consecutiveFailures++
        throw e
    }
}
```

### Add Diagnostic Logs

Add to diagnostic logging (every 30 seconds):
```kotlin
android.util.Log.i(
    "StepCounterDiagnostic",
    "Hour boundary health: lastSuccess=${Date(lastSuccessfulHourBoundary)}, " +
    "failures=$consecutiveFailures, loopActive=${isHourBoundaryLoopActive()}"
)
```

---

## Implementation Status

**Status**: ✅ IMPLEMENTED (2026-01-20 09:07)

**Changes Made**:
- ✅ Added health check variables: `lastSuccessfulHourBoundary`, `consecutiveFailures`, `hourBoundaryLoopActive`
- ✅ Refactored hour boundary loop into two methods:
  - `startHourBoundaryLoopWithRecovery()` - Layer 2: Outer restart logic
  - `startHourBoundaryLoop()` - Layer 1: Inner loop with per-iteration error handling
- ✅ Added health check method `isHourBoundaryLoopHealthy()`
- ✅ Implemented exponential backoff (1-5 minutes after iteration failure, 5-30 seconds after restart)
- ✅ Max restart limit: 10 attempts before giving up
- ✅ Proper CancellationException handling (stops loop, doesn't restart)
- ✅ Detailed logging at all levels (success, failure, restart)
- ✅ Graceful shutdown signal in `onDestroy()`

**Verification Results**:
- ✅ App builds successfully
- ✅ Installed on device
- ✅ Hour boundary loop starting with new error handling
- ✅ Logs show: "Hour boundary detection loop starting"
- ✅ Loop calculating next hour boundary correctly
- ⏳ Awaiting next hour boundary to verify error recovery works

**Next Steps**:
- Write unit tests for error scenarios (5 tests planned)
- Write integration tests (2 tests planned)
- Monitor overnight for stability (8+ hours)
- Update CLAUDE.md with error handling details

## Implementation Checklist

- [x] Refactor hour boundary loop to `startHourBoundaryLoop()` method
- [x] Add try-catch around each loop iteration (Layer 1)
- [x] Add outer restart logic with counter (Layer 2)
- [x] Add backoff delays (1 min after iteration failure, 5 sec after restart)
- [x] Add detailed success/failure logging
- [x] Handle CancellationException separately
- [x] Add health check method `isHourBoundaryLoopHealthy()`
- [x] Add diagnostic logging to show loop status
- [x] Build and deploy to device
- [x] Verify loop starts correctly
- [ ] Write Unit Test 1: Survives database exception
- [ ] Write Unit Test 2: Survives sensor exception
- [ ] Write Unit Test 3: Restarts after crash
- [ ] Write Unit Test 4: Stops after max restarts
- [ ] Write Unit Test 5: Stops on cancellation
- [ ] Write Integration Test 6: Multiple hour boundaries
- [ ] Write Integration Test 7: Recovers from database lock
- [ ] Manual overnight test (8+ hours)
- [ ] Manual failure injection test
- [ ] Update CLAUDE.md with error handling details
- [ ] Commit changes with descriptive message

---

## Success Criteria

### Code Implementation
1. ✅ Hour boundary loop error recovery code written
2. ✅ Automatic recovery logic implemented (backoff, retries)
3. ✅ Detailed error logging added
4. ✅ Health check method implemented

### Verification & Testing
5. ❌ Unit tests written: **0/5 tests completed** (lines 1082-1177 in PLAN.md)
6. ❌ Integration tests written: **0/2 tests completed** (lines 1180-1229 in PLAN.md)
7. ⏳ Manual overnight test: **NOT DONE** (8+ hours pending)
8. ⏳ Real-world verification: **Jan 22 failure suggests issues remain**

### Outcome Goals (Once Verified)
9. ⏳ Hour boundary loop survives exceptions (needs unit tests)
10. ⏳ No silent data loss (needs overnight test)
11. ⏳ All hours have database records (needs verification)
12. ⏳ Step counts match Samsung Health ±5% (needs comparison)

---

## Risk Assessment

**Risk Level**: Medium

**Risks**:
- Backoff delays might cause slight data gaps (mitigated: AlarmManager fallback)
- Excessive logging might impact performance (mitigated: only log on errors)
- Restart counter might prevent recovery in rare cases (mitigated: 10 attempts is generous)

**Mitigation**:
- Keep AlarmManager as independent backup system
- Log only failures, not every iteration
- Health check provides early warning before complete failure
- 1-minute backoff is short enough to minimize gaps

---

## Notes

- This is the most critical bug found - causes silent, permanent data loss
- Bug was hard to detect because service appears healthy (notification visible, switches ON)
- Only discovered through detailed alarm history and database analysis
- Fix requires defense-in-depth: multiple layers of error handling
- AlarmManager backup is essential - keep both systems independent

---

# Step Count Discrepancy - Missing Steps Compared to Other Apps

## Date
2026-01-22

## Issue Identified

### Critical: App Shows 394 Steps vs 447 Steps in Moova/Samsung Health (8-9am Hour)

**Severity**: High
**Discrepancy**: 53 steps missing (447 - 394 = 53)

**Evidence from Device Logs**:
```
01-22 08:47:28.002 StepSensor: Sensor fired: absolute=106040 | hourBaseline=105646 | delta=394
01-22 08:47:28.015 StepCounter: History loaded: 2 entries - 1769047200000: 53, 1769043600000: 7
01-22 08:47:28.015 StepCounter: Daily total calculated: dbTotal=60 (excluding current hour 1769068800000=Thu Jan 22 08:00:00 GMT 2026), currentHourSteps=394, final=454
```

**Key Observations**:
1. Current hour baseline: 105646
2. Current sensor absolute: 106040
3. Delta (hourly steps): 394
4. Database history shows only 2am (53 steps) and 1am (7 steps) entries
5. **NO 7am hour entry exists in database** - the 7am hour boundary was never processed!
6. The missing 53 steps exactly matches the 7am hour's expected step count

---

## Root Cause Analysis

### Problem: Hour Boundary Not Processed at 8:00 AM

**Timeline Reconstruction**:
1. **Before 8:00 AM**: User was walking, accumulating steps
2. **At 8:00:00 AM**: Hour boundary should have been processed
   - Expected: Save 7am hour steps to database, reset baseline for 8am
   - Actual: **No hour boundary processing occurred**
3. **After 8:00 AM**: Steps continued accumulating from the OLD baseline
4. **Result**: Steps taken between 7:00-8:00 AM were "absorbed" into the 8am hour's baseline

**Why Hour Boundary Was Missed**:

The foreground service's hour boundary loop calculates delay until next hour and sleeps. However, there are several scenarios where this can fail:

1. **Service Killed by System**: Android may kill the foreground service during Doze mode or battery optimization
2. **Delay Calculation Drift**: Small timing errors can accumulate, causing the loop to miss the exact boundary
3. **Exception in Loop**: An unhandled exception could crash the loop silently
4. **AlarmManager Backup Failed**: The backup alarm may not have fired due to:
   - Doze mode restrictions
   - Battery optimization
   - Alarm not rescheduled after previous hour

**Evidence Supporting This Theory**:
- No "Hour boundary" logs found in logcat around 8:00 AM
- No "Saving completed hour" logs found
- Wake lock was held continuously (ACQ=-11h50m22s675ms at 08:09)
- Service was running (notifications were updating)
- But hour boundary loop may have been stuck or crashed

---

## Detailed Technical Analysis

### Current Hour Boundary Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Hour Boundary Processing                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Primary: StepCounterForegroundService                          │
│  ├── startHourBoundaryLoopWithRecovery()                        │
│  │   └── startHourBoundaryLoop()                                │
│  │       └── while(true) {                                      │
│  │           delay(msUntilNextHour)  ← Can drift or be killed   │
│  │           handleHourBoundary()                               │
│  │       }                                                      │
│  │                                                              │
│  Backup: HourBoundaryReceiver (AlarmManager)                    │
│  └── Scheduled for XX:00:00 each hour                           │
│      └── May not fire during Doze mode                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### The Baseline Problem

When the app opens mid-hour (e.g., 8:44 AM) after missing an hour boundary:

```kotlin
// StepCounterViewModel.kt lines 257-284
if (currentHourTimestamp != savedHourTimestamp && savedHourTimestamp > 0) {
    // Hour changed while app was closed - save previous hour data
    if (previousHourStartSteps > 0 && savedDeviceTotal > 0) {
        var stepsInPreviousHour = actualDeviceSteps - previousHourStartSteps
        // ...
        repository.saveHourlySteps(savedHourTimestamp, stepsInPreviousHour)
    }

    // Initialize for current hour with actual device step count
    preferences.saveHourData(
        hourStartStepCount = actualDeviceSteps,  // ← Sets baseline to CURRENT sensor value
        currentTimestamp = currentHourTimestamp,
        totalSteps = actualDeviceSteps
    )
}
```

**The Issue**: When the app opens at 8:44 AM:
- `savedHourTimestamp` = 7am (from preferences)
- `currentHourTimestamp` = 8am (calculated)
- `actualDeviceSteps` = 106040 (current sensor)
- `previousHourStartSteps` = baseline from 7am start

The code saves `actualDeviceSteps - previousHourStartSteps` as the 7am hour's steps. But this includes ALL steps from 7am to 8:44am, not just 7am-8am!

Then it sets the 8am baseline to `actualDeviceSteps` (106040), which means steps taken between 8:00-8:44 are LOST because the baseline is set to the current value, not the value at 8:00:00.

---

## Proposed Solutions

### Solution 1: Improve Hour Boundary Reliability (Recommended)

**Goal**: Ensure hour boundaries are ALWAYS processed, even during Doze mode.

**Implementation**:

1. **Use setExactAndAllowWhileIdle() for AlarmManager**
   - File: `app/src/main/java/com/example/myhourlystepcounterv2/notifications/AlarmScheduler.kt`
   - Change from `setExact()` to `setExactAndAllowWhileIdle()`
   - This allows alarms to fire during Doze mode

2. **Add Redundant WorkManager Backup**
   - Schedule a periodic WorkManager job every 15 minutes
   - Job checks if any hour boundaries were missed
   - If missed, retroactively calculate and save the steps

3. **Improve Foreground Service Loop Resilience**
   - Add watchdog timer to detect stuck loops
   - Log heartbeat every 5 minutes to verify loop is alive
   - Auto-restart loop if no heartbeat detected

**Code Changes**:

**File**: `app/src/main/java/com/example/myhourlystepcounterv2/notifications/AlarmScheduler.kt`

```kotlin
// Change from:
alarmManager.setExact(
    AlarmManager.RTC_WAKEUP,
    nextHourMillis,
    pendingIntent
)

// To:
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        nextHourMillis,
        pendingIntent
    )
} else {
    alarmManager.setExact(
        AlarmManager.RTC_WAKEUP,
        nextHourMillis,
        pendingIntent
    )
}
```

### Solution 2: Retroactive Step Calculation on App Open

**Goal**: When app opens mid-hour, calculate missed steps retroactively.

**Implementation**:

1. **Detect Missed Hour Boundaries**
   - Compare `savedHourTimestamp` with `currentHourTimestamp`
   - If difference > 1 hour, hour boundaries were missed

2. **Calculate Steps Per Missed Hour**
   - Total steps while closed = `actualDeviceSteps - savedDeviceTotal`
   - Distribute evenly across missed hours
   - Or use heuristics (e.g., assume sleep hours = 0 steps)

3. **Save Retroactive Entries**
   - Create database entries for each missed hour
   - Set appropriate baseline for current hour

**Code Location**: `app/src/main/java/com/example/myhourlystepcounterv2/ui/StepCounterViewModel.kt` lines 257-284

### Solution 3: Use Samsung Health API (Long-term)

**Goal**: Query Samsung Health for accurate historical step data.

**Implementation**:
1. Add Samsung Health SDK dependency
2. Request step data permission
3. Query historical step counts by hour
4. Use Samsung Health data as source of truth
5. Fall back to sensor data if Samsung Health unavailable

**Pros**:
- Most accurate data (matches what user sees in Samsung Health)
- Handles all edge cases (Doze, app killed, etc.)
- No need to track hour boundaries ourselves

**Cons**:
- Requires Samsung Health SDK integration
- Only works on Samsung devices
- User must grant additional permissions
- More complex implementation

---

## Recommended Fix Priority

### Phase 1: Immediate (Fix Hour Boundary Reliability)

1. **Change AlarmManager to setExactAndAllowWhileIdle()** - 10 min
   - Ensures backup alarm fires during Doze mode
   - Low risk, high impact

2. **Add Heartbeat Logging to Hour Boundary Loop** - 15 min
   - Log every 5 minutes: "Hour boundary loop alive, next boundary in X minutes"
   - Helps diagnose future issues

3. **Add Missed Boundary Detection on Service Start** - 20 min
   - When foreground service starts, check if any boundaries were missed
   - Process missed boundaries retroactively

### Phase 2: Short-term (Improve Retroactive Calculation)

4. **Improve ViewModel Initialization Logic** - 30 min
   - When app opens after missed boundaries, calculate steps per hour
   - Use time-based distribution (assume walking hours vs sleep hours)

5. **Add WorkManager Backup Job** - 45 min
   - Periodic job every 15 minutes
   - Checks for missed boundaries
   - Independent of foreground service

### Phase 3: Long-term (Samsung Health Integration)

6. **Integrate Samsung Health SDK** - 4 hours
   - Query historical step data
   - Use as source of truth
   - Fall back to sensor if unavailable

---

## Implementation Checklist

### Phase 1: Immediate Fixes

- [x] Update AlarmScheduler to use setExactAndAllowWhileIdle() ✅ ALREADY IN CODE (lines 58, 139)
- [x] Add heartbeat logging to hour boundary loop ✅ ALREADY IN CODE (lines 456-459)
- [ ] **Fix HourBoundaryReceiver.kt line 101 early return bug** ❌ CRITICAL - NOT DONE
- [x] Add checkMissedHourBoundaries() to onStartCommand() ✅ ALREADY IN CODE (lines 118-126)
- [x] Add checkMissedHourBoundaries() to loop iterations ✅ ALREADY IN CODE (lines 458-464)
- [x] Add missed boundary detection to HourBoundaryReceiver ✅ ALREADY IN CODE (lines 53-64)
- [ ] Pull Jan 22 device logs (7:55-8:05 AM) ❌ DIAGNOSTIC NEEDED
- [ ] Test with Doze mode enabled ❌ NOT TESTED
- [ ] Verify alarms fire during battery optimization ❌ NOT TESTED

### Phase 2: Short-term Improvements

- [ ] Improve ViewModel initialization for missed boundaries
- [ ] Add WorkManager backup job
- [ ] Test overnight with app closed
- [ ] Verify step counts match Samsung Health

### Phase 3: Long-term (Optional)

- [ ] Research Samsung Health SDK integration
- [ ] Implement Samsung Health data query
- [ ] Add fallback logic
- [ ] Test on multiple Samsung devices

---

## Testing Plan

### Test 1: Doze Mode Hour Boundary
1. Enable Doze mode: `adb shell dumpsys deviceidle force-idle`
2. Wait for hour boundary
3. Verify alarm fires and steps are saved
4. Expected: Hour boundary processed even in Doze

### Test 2: App Closed Overnight
1. Close app at 10 PM
2. Walk around (or simulate steps)
3. Open app at 8 AM next day
4. Verify all hourly entries exist in database
5. Compare with Samsung Health

### Test 3: Service Killed by System
1. Start foreground service
2. Force kill: `adb shell am force-stop com.example.myhourlystepcounterv2`
3. Wait for hour boundary
4. Verify AlarmManager backup fires
5. Verify steps are saved

### Test 4: Multiple Missed Boundaries
1. Close app
2. Disable all alarms/services
3. Wait 3+ hours
4. Re-enable and open app
5. Verify retroactive calculation works

---

## Success Criteria

1. [ ] Hour boundaries processed 100% of the time (even during Doze)
2. [ ] Step counts match Samsung Health within 5% tolerance
3. [ ] No missing hourly entries in database
4. [ ] Retroactive calculation handles missed boundaries
5. [ ] Heartbeat logs confirm loop is always alive
6. [ ] AlarmManager backup fires reliably

---

## Risk Assessment

**Risk Level**: Medium-High

**Risks**:
- setExactAndAllowWhileIdle() may still be restricted on some devices
- Retroactive calculation may not be 100% accurate
- Samsung Health integration requires significant effort

**Mitigation**:
- Multiple backup systems (FG service + AlarmManager + WorkManager)
- Conservative step distribution for missed hours
- Extensive logging for debugging
- Consider Samsung Health as ultimate fallback

---

## Specification Clarification

### Current Behavior When Service Restarted After Being Killed

When the foreground service is killed by the OS and restarts hours later, it detects missed hour boundaries and saves the accumulated steps to the last missed hour. This preserves the total step count but attributes all missed steps to a single hour. This is the intended behavior to maintain data integrity while simplifying the logic. The total daily step count remains accurate, which is the primary goal.

### Benefits of Current Approach

1. **Preserves Total Step Count**: All steps taken during downtime are preserved
2. **Simple Implementation**: Straightforward logic that's easy to maintain
3. **Accurate Daily Totals**: The daily step count remains correct
4. **Robust Recovery**: Handles service restarts reliably

### Trade-offs

1. **Hourly Granularity**: Steps from multiple missed hours are attributed to a single hour
2. **Historical Accuracy**: Individual hour records may not reflect actual hourly activity
3. **Data Distribution**: Steps are consolidated rather than distributed across missed hours

Despite these trade-offs, the current approach ensures that no steps are lost and the total daily count remains accurate, which is the primary requirement for the step counter application.

---

## Notes

- The 53-step discrepancy exactly matches what would be expected if the 7am hour was missed
- Samsung Health and Moova likely use the system step counter service directly
- Our app relies on sensor events which can be missed during Doze
- The foreground service was running but the hour boundary loop may have been stuck
- This is a systemic issue that will recur until fixed
- The additional checkMissedHourBoundaries() calls provide extra safety net for edge cases

---

# Architectural Refactor: Single Owner for Step-Tracking State (Feb 6, 2026)

## Background

On Feb 6, the ViewModel's `initialize()` clobbered the FG service's live sensor baseline when the Activity was recreated, resetting the displayed hourly step count from 84 to 0. An immediate fix added `shouldPreserveFgTracking` guards at the two code paths that reset the baseline.

However, this is the latest in a recurring pattern of bugs caused by the same root issue: **the ViewModel and FG service both write to the shared sensor state and DataStore preferences, with no clear owner**. Previous sessions addressed closure period handling, hour distribution bugs, missed boundary detection, multi-layer error recovery, and ViewModel initialization — each adding complexity to an already complex initialization path (~350 lines).

## Goal

Make the FG service the single authority for step-tracking state. Reduce the ViewModel to a thin reader. Eliminate the class of "stale preference" bugs.

## Steps

### Step 1: FG service updates DataStore at every hour boundary

**Files:** `StepCounterForegroundService.kt`, `StepPreferences.kt`

The FG service processes hour boundaries (saves to DB, resets sensor baseline) but does NOT update `currentHourTimestamp` or `hourStartStepCount` in DataStore — only the ViewModel does. This is why preferences go stale when the UI isn't open.

- [x] In the FG service's `handleHourBoundary()`, after saving to DB and resetting the sensor baseline, call `preferences.saveHourData(hourStartStepCount, currentHourTimestamp, totalSteps)` — done (line 634)
- [x] In `checkMissedHourBoundaries()`, do the same after processing each missed boundary — done (line 458)
- [x] Verify `totalStepsDevice` is also kept current by the FG service — included in `saveHourData()`
- [x] Add logging to confirm preference writes at each boundary — done ("Preferences synced at hour boundary" / "Preferences synced at missed boundary")

### Step 2: FG service updates `lastStartOfDay` on day boundary

**Files:** `StepCounterForegroundService.kt`

- [x] When the FG service detects a day boundary (midnight crossing), update `preferences.saveStartOfDay(newStartOfDay)` — done via `syncStartOfDay()` called from both `handleHourBoundary()` and `checkMissedHourBoundaries()`
- [x] Note: a basic version of this may already exist (see Day Boundary Bug section above) — verified, `syncStartOfDay()` covers this

### Step 3: Simplify ViewModel initialization

**Files:** `StepCounterViewModel.kt`

With the FG service keeping preferences current, `initialize()` can be dramatically simplified:

- [ ] **If sensor is already initialized** (FG service running): read state from the sensor singleton, sync any stale preferences, done. No reconstruction needed. *(NOT YET DONE — ViewModel is still 541 lines)*
- [ ] **If sensor is NOT initialized** (cold start, no FG service): read baseline from preferences (now kept current by FG service), set sensor state, done.
- [ ] **Remove or reduce**: the `isFirstOpenOfDay` closure distribution block, the multi-hour missed boundary backfill, and the day-boundary re-detection. These exist because the ViewModel doesn't trust the FG service — once the FG service reliably owns preferences, they become dead code or simple fallbacks for the no-FG-service edge case.
- [ ] Target: reduce `initialize()` from ~350 lines to ~100 lines or less

### Step 4: Add staleness assertion

**Files:** `StepCounterViewModel.kt` or `StepCounterForegroundService.kt`

- [x] Add diagnostic check: if `currentHourTimestamp` in DataStore is more than 2 hours behind the actual current hour while the FG service is running, log an error — done via `logTimestampStaleness()` (line 722)
- [x] This catches preference drift early rather than waiting for user-visible bugs — implemented, logs every 2 hours max
- [x] Could integrate with the existing diagnostic logging that runs every 30 seconds — integrated into the notification update flow

### Step 5: Add integration test for Activity recreation

**Files:** new test in `app/src/androidTest/`

- [ ] Write an instrumented test that:
  1. Starts the FG service
  2. Simulates sensor events to accumulate steps
  3. Destroys and recreates the Activity (via `ActivityScenario.recreate()`)
  4. Asserts the displayed hourly step count matches what the FG service was tracking (not zero)
- [ ] This is the exact failure mode of the Feb 6 bug and prevents regression

### Step 6: Audit and remove accumulated defensive code

**Files:** `StepCounterViewModel.kt`

After steps 1-3, review for code that is now unreachable or redundant:

- [ ] The `shouldPreserveFgTracking` guards (from the Feb 6 fix) — may be unnecessary if the ViewModel no longer attempts baseline resets
- [ ] The `validSavedDeviceTotal` fallback checks (for `savedDeviceTotal=0` bug) — may be obsolete if the FG service keeps the value current
- [ ] The closure period distribution logic — may reduce to a single "FG was running → trust it; FG was not running → use preferences" branch
- [ ] Goal: fewer code paths = fewer edge cases = fewer bugs

## Order of Work

1. **Steps 1-2** first (FG service becomes the writer) — can ship independently
2. **Step 3** next (simplify ViewModel) — the big payoff
3. **Steps 4-6** last (observability, testing, cleanup) — hardening

Each step is independently shippable and testable.

## Success Criteria

- [ ] Preferences are current at all times when FG service is running (no stale timestamps)
- [ ] Opening the app never resets hourly step count when FG service is tracking
- [ ] ViewModel `initialize()` is under 100 lines
- [ ] Integration test passes for Activity recreation scenario
- [ ] Overnight test: all hourly entries present, totals match Samsung Health within 5%

---

## 2026-02-12 Sensor Syncing Hotfix Plan

Issue observed on-device: user walked `50+` steps, app opened with `0`, and reminder/notification reflected stale zero before first fresh sensor callback arrived.

Planned + implemented in this patch:

- [x] Add explicit sensor sync probing on app startup/resume (`flush + wait for fresh callback`) and expose UI sync state.
- [x] Show `syncing...` in Home hourly card while waiting for fresh sensor callback.
- [x] Show `syncing...` in foreground notification during startup sensor sync window.
- [x] Suppress first/second reminder notification when sensor is still syncing and hourly steps are still zero.
- [x] Keep normal behavior unchanged once a fresh callback arrives.
