# Fix handleHourBoundary() reboot/counter-jump and baseline save bugs

**Date:** 2026-03-04
**Status:** Approved

## Problem

When the phone dies (flat battery) and restarts, Samsung Health can retroactively inflate `TYPE_STEP_COUNTER` with the whole day's accumulated steps. `handleHourBoundary()` has no reboot or counter-discontinuity detection, so it computes a bogus delta (e.g., inflated post-reboot value minus small pre-reboot baseline) and clamps it to `MAX_STEPS_PER_HOUR` (10,000). A secondary bug causes this to cascade: when `resetForNewHour()` returns `false`, the function returns without saving the new baseline to preferences, so subsequent hours also compute massive deltas from the same stale baseline.

## Root Cause

1. `handleHourBoundary()` lacks the reboot/counter-continuity checks that `checkMissedHourBoundaries()` already has.
2. The early `return` on `resetForNewHour()` failure skips the preference save, leaving `hourStartStepCount` stale.

## Fix

### Change 1: Add counter-continuity guard

After resolving `deviceTotal` and before computing `stepsInPreviousHour`, check `isDeviceRebootDetected()` and `shouldBreakCounterContinuity()`. If continuity is broken, save 0 steps for the previous hour.

### Change 2: Fix baseline save on duplicate reset

When `resetForNewHour()` returns `false`, fall through to save preferences instead of returning early.

## Files Changed

- `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`
- Unit tests for the new behavior
