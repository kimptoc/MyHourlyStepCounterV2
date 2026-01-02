# Copilot instructions for MyHourlyStepCounterV2

Goal: Help an AI coding agent become productive quickly by pointing to the app's architecture, conventions, workflows and concrete examples.

## Quick summary
- Single-module Android app (package: `com.example.myhourlystepcounterv2`) using Kotlin + Jetpack Compose (Material3) and MVVM.
- Data persisted with Room (`app/src/main/java/.../data`), preferences via DataStore, hourly background work via WorkManager.
- Sensors: uses the device step counter (TYPE_STEP_COUNTER) via `sensor/StepSensorManager.kt` and requires `ACTIVITY_RECOGNITION` permission.

## Big picture & why
- Single-activity, Compose-based UI in `MainActivity.kt` -> `MyHourlyStepCounterV2App()` manages navigation and screens.
- Business logic in `ui/StepCounterViewModel.kt`, persistence in `data/StepRepository.kt` + Room DAOs/Entities.
- Hourly background capture: `worker/WorkManagerScheduler.kt` schedules `worker/StepCounterWorker.kt`, which reads step totals via `StepSensorManager` and saves hourly deltas to Room.

## Files to inspect first (high value)
- `app/src/main/java/com/example/myhourlystepcounterv2/ui/StepCounterViewModel.kt` — view-model logic, state flows, scheduler call
- `app/src/main/java/com/example/myhourlystepcounterv2/worker/StepCounterWorker.kt` — exact worker flow and where deltas are computed
- `app/src/main/java/com/example/myhourlystepcounterv2/sensor/StepSensorManager.kt` — sensor interfacing and update frequency
- `app/src/main/java/com/example/myhourlystepcounterv2/data/` — `StepEntity`, `StepDao`, `StepDatabase`, `StepRepository` (persistence contract)
- `app/src/main/java/com/example/myhourlystepcounterv2/ui/App.kt`, `HomeScreen.kt`, `HistoryScreen.kt` — Compose navigation and UI conventions
- `CLAUDE.md` — existing project notes and build/test commands

## Concrete developer workflows (commands to run)
- Build debug APK: `./gradlew assembleDebug`
- Install & run on device: `./gradlew installDebug && adb shell am start -n com.example.myhourlystepcounterv2/.MainActivity`
- Unit tests: `./gradlew test` (also see `app/src/test/`) 
- Instrumented tests: `./gradlew connectedAndroidTest` (requires emulator/device)
- Lint & checks: `./gradlew lint` / `./gradlew check`

## Project-specific conventions & patterns
- Version catalog: dependency versions live in `gradle/libs.versions.toml`. Add new deps there and reference via `libs.*` in `build.gradle.kts`.
- Min SDK is 33; code assumes modern Android APIs (dynamic theming, Activity recognition permission flows).
- Navigation: `AppDestinations` enum drives top-level screens — add enum entry + composable + App.kt when adding a screen.
- Background sampling: prefer WorkManager for periodic hourly tasks (see `WorkManagerScheduler.scheduleHourlyStepCounter`). Avoid adding alternative schedulers unless explicit reason.
- Data validation patterns: repository and view-model tests assert clamping/validation (no negative steps, reasonable max per-hour). Mirror those constraints when adding new logic.

## Testing notes & examples
- Unit tests demonstrate expected behavior to follow:
  - `app/src/test/.../StepRepositoryTest.kt` — validation for hourly step ranges and timestamp normalization
  - `app/src/test/.../StepCounterViewModelTest.kt` — delta calculation behavior, clamping negative values to zero
- To unit test sensor-dependent logic, mock `StepRepository` or `StepSensorManager` instead of relying on device sensors.

## Permissions & runtime behavior
- `AndroidManifest.xml` declares `ACTIVITY_RECOGNITION`. Runtime permission flow helper: `PermissionHelper.kt`.
- For UI changes that involve sensors, ensure runtime permission handling is added and unit/instrumented tests account for the permission gate.

## Failure modes & gotchas
- Steps delta calculation must be clamped to prevent negative or unreasonably large values (see tests).
- Background work depends on WorkManager constraints; tests assume the worker writes hourly snapshots and deletes old entries with `StepDao.deleteOldSteps()`.
- Ensure WorkManager scheduling is idempotent and survives app restarts (current pattern called from the ViewModel factory/initialization).

## Example small tasks (how an agent should proceed)
- Add a new screen: add `AppDestinations` entry, create `NewScreen.kt` composable, wire it in `MyHourlyStepCounterV2App()` and add a small unit test for its ViewModel/behaviour.
- Fix wrong delta logic: add unit tests reproducing the negative delta case (see `StepCounterViewModelTest`) then update the compute logic in `StepCounterViewModel` or `StepCounterWorker`.
- Add a DataStore preference: update `data/StepPreferences.kt`, add a migration note in `CLAUDE.md` if needed and add tests that read/write preferences.

---
If you'd like, I can:
- Merge content from `CLAUDE.md` into this file to preserve additional historical notes, or
- Add short examples/snippets for mocking `StepSensorManager` in unit tests.

Please review and tell me which sections are unclear or need more detail. (I can iterate quickly.)
