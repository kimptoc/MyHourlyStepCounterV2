# Day Boundary Bug Investigation Plan

## Issue Summary

**Reported Symptoms:**
1. When new day started: History showed previous day totals ❌
2. When new day started: Daily total on home screen was for previous day ❌
3. Current hour total HAD reset correctly and showed 40 steps ✅ (matched other apps)
4. After closing/opening app: Totals were fixed ✅ BUT hourly total reset to zero ❌

**Critical Problem:** App loses current hour progress when reopened on a new day.

---

## Root Cause Hypothesis

Based on code analysis of `StepCounterViewModel.kt`:

### Timeline of Events

**Initial App Open (New Day):**
1. `initialize()` runs (lines 50-320)
2. Detects day boundary crossing at line 137: `crossedDayBoundary = true`
3. Saves yesterday's incomplete hour as 0 (line 147)
4. Detects first open of day at line 152: `isFirstOpenOfDay = true`
5. Runs closure period distribution logic (lines 158-247)
6. **Sets `lastOpenDate` to current day** at line 246
7. User sees 40 steps for current hour (sensor is working correctly)
8. **BUT**: Daily total query likely still shows yesterday's data

**App Close/Reopen (Same Day):**
1. `initialize()` does NOT run (ViewModel still exists)
2. `refreshStepCounts()` runs from `MainActivity.onResume()` (line 457)
3. Calls `handleUiResumeClosure()` at line 491
4. **CRITICAL BUG**: Line 518 checks `isFirstOpenOfDay = lastOpenDate != currentStartOfDay`
5. This is now **FALSE** because we already set `lastOpenDate` in initialize()
6. **WAIT** - Actually line 520 returns early if NOT first open!
7. So the bug must be elsewhere...

### Re-analyzing the Bug

Let me trace through what happens when app reopens:

**Second Open Scenario:**
- User opens app at 00:05 on new day → sees 40 steps (correct)
- User closes app
- User reopens app at 00:10 (still same hour, same day)
- `refreshStepCounts()` runs
- `handleUiResumeClosure()` is called with `currentDeviceTotal` = current sensor value
- Line 518: `isFirstOpenOfDay = (lastOpenDate != currentStartOfDay)` = FALSE (we already set it)
- Line 520: Returns early - **GOOD, no baseline reset should happen**

**So why does the hourly count reset to zero?**

### Likely Culprit: Database Query Timing

The issue is probably:
1. History screen queries: `getStepsForDay(currentStartOfDay)`
2. Daily total queries: `getTotalStepsForDay(currentStartOfDay)`
3. These queries execute BEFORE the current hour data is saved to the database
4. Current hour shows 40 because it's calculated from sensor (device total - hour baseline)
5. But daily total is from database which doesn't have today's data yet

When user reopens app, something must be triggering a baseline reset. Let me check if there's another code path...

---

## Investigation Tasks

### Task 1: Add Comprehensive Logging
**File:** `StepCounterViewModel.kt`

Add detailed logging to track:
- `initialize()` - when it runs, what values it reads, what it sets
- `refreshStepCounts()` - when it runs, current vs previous device total
- `handleUiResumeClosure()` - whether it runs, what conditions it evaluates
- Hour baseline changes - log EVERY time `setLastHourStartStepCount()` is called

**Specific Log Points Needed:**
```kotlin
// In initialize() after line 246
Log.i("DAY_BOUNDARY", """
    POST-INITIALIZE STATE:
    - lastOpenDate: ${java.util.Date(currentStartOfDay)}
    - hourStartStepCount: $actualDeviceSteps
    - currentHourTimestamp: ${java.util.Date(currentHourTimestamp)}
    - dailySteps from DB: ${getTotalStepsForDay()}
""")

// In refreshStepCounts() before line 491
Log.i("REOPEN", """
    REFRESH TRIGGERED:
    - currentDeviceTotal: $currentTotal
    - previousDeviceTotal: $previousTotal
    - lastOpenDate: ${java.util.Date(preferences.lastOpenDate.first())}
    - currentStartOfDay: ${java.util.Date(getStartOfDay())}
    - Will call handleUiResumeClosure: true
""")

// In handleUiResumeClosure() at line 520
Log.i("REOPEN", """
    handleUiResumeClosure() - Early Return:
    - isFirstOpenOfDay: $isFirstOpenOfDay
    - lastOpenDate: ${if (lastOpenDate > 0) java.util.Date(lastOpenDate) else "never"}
    - currentStartOfDay: ${java.util.Date(currentStartOfDay)}
    - Skipping closure detection: returning early
""")

// In handleUiResumeClosure() if NOT returning early
Log.i("REOPEN", """
    handleUiResumeClosure() - Running Distribution:
    - stepsWhileClosed: $stepsWhileClosed
    - currentHour: $currentHour
    - Will reset baseline to: $currentDeviceTotal
""")
```

### Task 2: Reproduce the Bug
**Steps:**
1. Build and install app with enhanced logging
2. Open app on new day (simulate by clearing app data if needed)
3. Wait for app to show current hour count (should be > 0)
4. Note: history, daily total, hourly total values
5. Close app completely (swipe away from recents)
6. Reopen app
7. Capture logcat output
8. Compare: hourly total before/after reopen

### Task 3: Check Database Query Timing
**Files to examine:**
- `StepRepository.kt` - getTotalStepsForDay, getStepsForDay
- `StepDao.kt` - SQL queries for day filtering
- `StepCounterViewModel.kt` - where daily total is calculated

**Questions:**
- When is daily total calculated? From sensor or from DB?
- Is there a timing issue where DB queries run before current hour is saved?
- Does the flow for updating `_dailySteps` and `_dayHistory` run correctly on day boundary?

### Task 4: Check Sensor Baseline Logic
**File:** `StepSensorManager.kt`

Review:
- `setLastHourStartStepCount()` - when is this called?
- `getCurrentHourSteps()` - how is hourly delta calculated?
- Are there multiple code paths that reset the hour baseline?

**Grep for:** All calls to `setLastHourStartStepCount`
```bash
grep -r "setLastHourStartStepCount" app/src/main/
```

### Task 5: Trace Data Flow
Create a sequence diagram showing:
1. App opens on new day (Day 2, 00:05)
2. What values are in preferences from Day 1?
3. What happens in initialize()?
4. What values are written to preferences?
5. What is displayed on screen?
6. App closes
7. App reopens (Day 2, 00:10)
8. What values are read from preferences?
9. What happens in refreshStepCounts()?
10. What is displayed on screen?

---

## Suspected Bug Locations

### Hypothesis A: Double Baseline Reset
**Location:** `handleUiResumeClosure()` lines 595-604

**Problem:** After distributing steps from yesterday, the function unconditionally resets the hour baseline to `currentDeviceTotal` (line 599). This happens even if we're already tracking the current hour.

**Expected Behavior:**
- If current hour already has a baseline (same hour as saved), DON'T reset it
- Only reset baseline if hour changed or this is truly first open

**Fix:**
```kotlin
// Check if we're in the same hour as before
val savedHourTimestamp = preferences.currentHourTimestamp.first()
if (currentHourTimestamp != savedHourTimestamp) {
    // Hour changed - set new baseline
    preferences.saveHourData(
        hourStartStepCount = currentDeviceTotal,
        currentTimestamp = currentHourTimestamp,
        totalSteps = currentDeviceTotal
    )
    sensorManager.setLastHourStartStepCount(currentDeviceTotal)
} else {
    // Same hour - keep existing baseline
    Log.i("StepCounter", "Same hour as before - NOT resetting baseline")
}
```

### Hypothesis B: Database Timing Race
**Location:** Lines where `_dailySteps` and `_dayHistory` are updated

**Problem:** The Flow that updates daily steps may execute before the database has today's data, showing yesterday's total instead.

**Investigation Needed:**
- Check where `_dailySteps.value` is set
- Verify it's using `getStartOfDay()` for current day, not cached value
- Check if there's a race between DB insert and query

### Hypothesis C: lastOpenDate Logic
**Location:** Line 246 and line 611

**Problem:** `lastOpenDate` is updated in TWO places:
- `initialize()` line 246
- `handleUiResumeClosure()` line 611

**Potential Race:**
1. initialize() sets lastOpenDate = today
2. Later, refreshStepCounts() runs
3. handleUiResumeClosure() checks if first open (line 518)
4. It sees lastOpenDate == today, so returns early
5. But then line 611 runs anyway? NO - it returns at 522

This seems fine actually.

---

## Test Scenarios

After fix is implemented, test these scenarios:

### Scenario 1: Clean Day Boundary
1. Open app at 23:55 on Day 1
2. Let hour boundary pass to 00:00 Day 2
3. Verify hourly count resets to 0 at midnight
4. Walk 50 steps
5. Verify hourly count shows ~50
6. Close app
7. Reopen app
8. **EXPECT:** Hourly count still shows ~50 (NOT reset to 0)
9. **EXPECT:** Daily total shows ~50
10. **EXPECT:** History shows 00:00 entry with ~50 steps

### Scenario 2: Overnight Closure
1. Open app at 22:00 on Day 1, walk 100 steps
2. Close app
3. Sleep overnight
4. Open app at 08:00 on Day 2
5. **EXPECT:** Hourly count shows current hour steps
6. **EXPECT:** Daily total shows distributed morning steps
7. **EXPECT:** History shows Day 2 entries from 00:00 onwards
8. Close app immediately
9. Reopen app
10. **EXPECT:** Hourly count NOT reset to 0
11. **EXPECT:** Daily total unchanged
12. **EXPECT:** History unchanged

### Scenario 3: Multiple Reopens Same Hour
1. Open app at 10:00, walk to 100 steps
2. Close app at 10:15 (100 steps shown)
3. Reopen at 10:20 (should show ~100+ steps, not reset)
4. Close app at 10:25
5. Reopen at 10:30 (should show ~100+ steps, not reset)
6. **EXPECT:** Each reopen maintains hourly count

---

## Success Criteria

Fix is successful when:
1. ✅ New day boundary detected correctly
2. ✅ Hourly count resets to 0 at midnight
3. ✅ Hourly count accumulates during hour
4. ✅ Daily total shows current day total (not previous day)
5. ✅ History shows current day entries (not previous day)
6. ✅ **Reopening app does NOT reset hourly count to 0**
7. ✅ Closure period distribution works correctly
8. ✅ All values persist across app restarts

---

## Execution Plan

### Phase 0: Examine Existing Logs (10 min) **← START HERE**
- [ ] Pull existing logcat from device
- [ ] Search for day boundary logs from midnight (00:00)
- [ ] Look for initialize(), refreshStepCounts(), handleUiResumeClosure() calls
- [ ] Check if logs show the baseline reset that causes the bug
- [ ] If logs are insufficient, proceed to Phase 1

**Why this is important:** The app has extensive logging already. The bug may have already occurred and been logged. Examining existing logs is much faster than rebuilding the app.

### Phase 1: Add Enhanced Logging (30 min)
**Only needed if Phase 0 logs are insufficient**
- [ ] Add comprehensive logging to initialize()
- [ ] Add comprehensive logging to refreshStepCounts()
- [ ] Add comprehensive logging to handleUiResumeClosure()
- [ ] Add logging to ALL setLastHourStartStepCount() calls
- [ ] Build and install app

### Phase 2: Reproduce (15 min)
- [ ] Clear app data to simulate new day
- [ ] Open app, verify current hour count > 0
- [ ] Note all displayed values
- [ ] Close and reopen app
- [ ] Capture logcat -d > day_boundary_bug.log
- [ ] Analyze log to identify where baseline resets

### Phase 3: Fix (1-2 hours)
- [ ] Implement fix based on log analysis (likely Hypothesis A)
- [ ] Test Scenario 1
- [ ] Test Scenario 2
- [ ] Test Scenario 3
- [ ] Verify all success criteria met

### Phase 4: Cleanup
- [ ] Remove excessive debug logging (keep key checkpoints)
- [ ] Update documentation if needed
- [ ] Commit fix with clear description

---

## Notes

**Why this bug is critical:**
- Users expect hourly count to persist when reopening app
- Losing progress is frustrating and makes app unreliable
- This breaks the core functionality of the app

**Why it's tricky:**
- Two initialization paths: initialize() vs refreshStepCounts()
- Day boundary logic is complex with multiple edge cases
- Closure period handling adds additional complexity

**Key insight:**
The hourly baseline should ONLY be reset when:
1. Hour actually changes (new hour boundary)
2. Day changes (midnight)
3. App first launches (cold start)

It should NOT be reset when:
- Reopening app in same hour
- Resuming from background in same hour
- Even if it's first UI open of the day but we're still in the same hour!
