# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**My Hourly Step Counter V2** is a native Android application built with Kotlin and Jetpack Compose. 
The app targets modern Android devices (API 33+) with a focus on Material3 design and adaptive UI patterns. 
The project uses Gradle as the build system with centralized dependency management via a version catalog.

It should use the step sensor directly to get current step total for the current hour.  To keep a history, it should persist the steps and timestamps. 
This means it should be able to work after a reboot.
Basic architecture:
* WorkManager job runs every hour
* Reads current step count from TYPE_STEP_COUNTER
* Calculates delta from last reading
* Stores hourly record in a local Room database
* UI reads from the database

On the home tab, it should also show the total steps for the current day, lower down on the screen in smaller font than the hourly count.
This application should show the date time including minutes and seconds.
At the start of each hour, it should set the displayed hourly count to zero
As the hour progress, it should show the number of steps taken in that hour. The display should update every 3 seconds with the latest step total for that hour.
The Favorites tab should be renamed History and it should show the history of steps taken that day for each hour, for example at midday it would have totals for 11am, 10am , 9am etc listed on the screen.




**Key Technologies:**
- **Language:** Kotlin 2.0.21
- **UI Framework:** Jetpack Compose with Material3
- **Android Gradle Plugin:** 8.13.2
- **Target SDK:** Android 13+ (API 33-36)
- **Build System:** Gradle with version catalog (gradle/libs.versions.toml)

## Project Structure

```
app/
├── src/main/
│   ├── java/com/example/myhourlystepcounterv2/
│   │   ├── MainActivity.kt                  # Entry point with edge-to-edge, permissions
│   │   ├── PermissionHelper.kt              # ACTIVITY_RECOGNITION permission management
│   │   ├── Config.kt                        # App-wide constants (thresholds, max values) - formerly StepTrackerConfig.kt
│   │   ├── data/
│   │   │   ├── StepEntity.kt                # Room entity for hourly step records
│   │   │   ├── StepDao.kt                   # Room DAO with query methods
│   │   │   ├── StepDatabase.kt              # Room database singleton
│   │   │   ├── StepRepository.kt            # Data access layer (abstracts DB/preferences)
│   │   │   └── StepPreferences.kt           # DataStore for caching sensor values
│   │   ├── notifications/                   # Notification management components
│   │   │   ├── AlarmScheduler.kt            # AlarmManager scheduling for reminders and hour boundaries
│   │   │   ├── HourBoundaryReceiver.kt      # BroadcastReceiver for hour boundary processing
│   │   │   ├── NotificationHelper.kt        # Notification creation and management
│   │   │   └── StepReminderReceiver.kt      # BroadcastReceiver for step reminder notifications
│   │   ├── receivers/                       # Broadcast receivers
│   │   │   └── BootReceiver.kt              # BroadcastReceiver for restarting services after boot
│   │   ├── sensor/
│   │   │   └── StepSensorManager.kt         # TYPE_STEP_COUNTER sensor listener
│   │   ├── services/                        # Background services
│   │   │   └── StepCounterForegroundService.kt # Foreground service for persistent step tracking
│   │   ├── worker/
│   │   │   ├── StepCounterWorker.kt         # WorkManager background job (currently for cleanup)
│   │   │   └── WorkManagerScheduler.kt      # WorkManager configuration
│   │   ├── ui/
│   │   │   ├── App.kt                       # Navigation scaffold with AppDestinations
│   │   │   ├── HomeScreen.kt                # Current hour + daily total display
│   │   │   ├── HistoryScreen.kt             # Hourly breakdown list for the day
│   │   │   ├── ProfileScreen.kt             # Configuration display with settings toggles
│   │   │   ├── StepCounterViewModel.kt      # MVVM ViewModel with business logic
│   │   │   ├── StepCounterViewModelFactory.kt # Factory for DI
│   │   │   └── theme/
│   │   │       ├── Theme.kt                 # Material3 dynamic theming
│   │   │       ├── Color.kt                 # Color palette
│   │   │       └── Type.kt                  # Typography
│   │   ├── res/                             # Resources (strings, colors, drawable, mipmap)
│   │   └── AndroidManifest.xml
│   ├── src/test/                            # Unit tests (74 tests across 9 test files)
│   ├── src/androidTest/                     # Instrumented tests (Espresso + Compose)
│   └── build.gradle.kts                     # App module configuration
```

**Application Package:** `com.example.myhourlystepcounterv2`

## Architecture

The app uses **MVVM architecture with Repository pattern** in a single-activity Jetpack Compose app with sophisticated background processing:

### Presentation Layer (UI)
1. **MainActivity.kt:** Entry point that handles:
   - Edge-to-edge rendering setup
   - Runtime permission requests (ACTIVITY_RECOGNITION)
   - ViewModel initialization via factory
   - onResume() hook to refresh step counts when returning from other apps
   - Manages foreground service based on user preferences

2. **MyHourlyStepCounterV2App() (App.kt):** Root composable that:
   - Manages navigation state using `rememberSaveable`
   - Initializes ViewModel in LaunchedEffect with application context
   - Uses NavigationSuiteScaffold for adaptive navigation (rail/bar/drawer based on screen size)

3. **AppDestinations Enum:** Three navigation destinations:
   - **HOME:** Real-time current hour step count (large display) + daily total (smaller)
   - **HISTORY:** LazyColumn showing hourly breakdown for the day with timestamps
   - **PROFILE:** Configuration display with settings toggles (permanent notification, wake-lock)

4. **StepCounterViewModel:** MVVM ViewModel that:
   - Manages UI state via StateFlows (hourlySteps, dailySteps, dayHistory, currentTime)
   - Coordinates sensor, preferences, repository, and background services
   - Handles closure period detection with smart step distribution
   - Detects day boundaries and resets baselines
   - Updates display every second, listens to sensor events
   - Manages interaction with background services

### Data Layer
5. **StepRepository:** Data access abstraction over Room database
   - Provides Flow-based queries for reactive UI updates
   - Saves hourly step records with timestamps

6. **Room Database:**
   - **StepEntity:** Data class with id, timestamp, stepCount
   - **StepDao:** DAO with queries (getStepsForDay, getTotalStepsForDay, saveHourlySteps)
   - **StepDatabase:** Singleton database instance

7. **StepPreferences (DataStore):** Caches critical values across reboots:
   - `hourStartStepCount`: Baseline at hour start
   - `totalStepsDevice`: Last known device total from sensor
   - `currentHourTimestamp`: Current hour's start timestamp
   - `lastStartOfDay`: Midnight timestamp for day boundary detection
   - `lastOpenDate`: Timestamp for closure period detection
   - `permanentNotificationEnabled`: Toggle for persistent notification
   - `useWakeLock`: Toggle for keeping processor awake
   - `reminderNotificationEnabled`: Toggle for step reminder notifications

### Sensor Layer
8. **StepSensorManager:** Manages TYPE_STEP_COUNTER sensor:
   - SensorEventListener that tracks step count changes
   - Detects sensor resets (e.g., when Samsung Health accesses sensor)
   - Maintains hour baseline and calculates delta for current hour
   - Provides StateFlow for reactive UI updates
   - Thread-safe with Mutex and StateFlow for concurrent access

### Background Processing
9. **Foreground Service:**
   - **StepCounterForegroundService:** Maintains persistent notification and continuous step tracking
   - Updates notification in real-time with current hour and daily totals
   - Handles hour boundary processing when app is in background
   - Manages wake-lock based on user preferences

10. **AlarmManager:**
   - **AlarmScheduler:** Schedules precise alarms for step reminders (XX:50) and hour boundaries (XX:00)
   - **StepReminderReceiver:** BroadcastReceiver that sends step reminder notifications
   - **HourBoundaryReceiver:** BroadcastReceiver that processes hour boundaries and saves data
   - Provides backup scheduling when foreground service isn't running

11. **WorkManager:**
   - **StepCounterWorker:** Background job for periodic cleanup tasks (database maintenance)
   - **WorkManagerScheduler:** Configures periodic work requests
   - Handles edge cases: permission denied, sensor reset, stale timestamps

12. **Broadcast Receivers:**
   - **BootReceiver:** Restarts services and schedules alarms after device reboot
   - Registered in AndroidManifest.xml to handle system events

### Key Patterns
- **Closure Period Handling:** When app reopens after being backgrounded, distributes missed steps intelligently (early morning vs. later in day)
- **Fallback Priority:** Sensor > DataStore Cache > Safe Default (0)
- **Data Validation:** Clamps negative deltas to 0, caps unreasonable values at 10,000 steps/hour
- **Extensive Logging:** Diagnostic logs at every critical decision point for production debugging
- **Multi-Component Coordination:** Foreground service, AlarmManager, and WorkManager work together for reliable background operation
- **User Control:** Configurable settings for notification behavior and power management

## Common Development Tasks

### Build the App
```bash
./gradlew assembleDebug      # Build debug APK
./gradlew assembleRelease    # Build release APK (requires signing config)
./gradlew build              # Full build with all checks
```

### Run the App
```bash
./gradlew installDebug       # Install debug APK on connected device
adb shell am start -n com.example.myhourlystepcounterv2/.MainActivity  # Launch app
```

### Run Tests
```bash
./gradlew test               # Run unit tests only
./gradlew connectedAndroidTest  # Run instrumented tests (requires device/emulator)
./gradlew connectedCheck     # Run all tests on connected device
```

### Linting and Code Quality
```bash
./gradlew lint               # Run Android lint checks
./gradlew check              # Run all checks including lint and tests
```

### Development Build and Deploy
```bash
./gradlew installDebug && adb shell am start -n com.example.myhourlystepcounterv2/.MainActivity
```

### Clean Build
```bash
./gradlew clean build        # Clean and rebuild everything
```

## Dependency Management

Dependencies are centralized in `gradle/libs.versions.toml`. When adding new dependencies:

1. Add the version to the `[versions]` section
2. Add the library definition to the `[libraries]` section
3. Reference it in `app/build.gradle.kts` using `libs.groupname.libraryname`

**Current Key Dependencies:**
- `androidx.core:core-ktx` - Core Kotlin extensions
- `androidx.activity:activity-compose` - Compose integration with activities
- `androidx.compose.*` - Compose UI framework (material3, adaptive-navigation-suite)
- `androidx.lifecycle:lifecycle-runtime-ktx` / `lifecycle-viewmodel-compose` - Lifecycle management and ViewModel
- `androidx.room:room-runtime` / `room-ktx` - Room database for persistence
- `androidx.datastore:datastore-preferences` - DataStore for caching sensor values
- `androidx.work:work-runtime-ktx` - WorkManager for hourly background jobs
- Testing: JUnit 4, Espresso, Compose UI test framework, Room testing

## Theme and Styling

The app uses **Material3 dynamic theming** (`Theme.kt`):
- On Android 12+: Dynamic colors are extracted from the system wallpaper
- Below Android 12: Falls back to predefined color schemes (Light/Dark)
- Colors defined in `Color.kt` (Purple40, Purple80, PurpleGrey40, PurpleGrey80, Pink40, Pink80)
- Typography defined in `Type.kt`

When adding new UI components, always wrap them with `MyHourlyStepCounterV2Theme` for theming consistency in previews.

## Key Configurations

**Android SDK Versions:**
- Compile SDK: 36
- Min SDK: 33 (Android 13)
- Target SDK: 36 (Android 15)
- Java Target: JVM 11

**Build Features:**
- Compose is enabled (`buildFeatures { compose = true }`)
- Edge-to-edge rendering is enabled in MainActivity

## Testing Strategy

The app has **74 comprehensive unit tests** across 9 test files covering edge cases and production reliability scenarios.

### Test Coverage
1. **Unit Tests** (`app/src/test/`): Test business logic using JUnit 4
   - **WorkerEdgeCasesTest:** DataStore cached device total = 0, negative deltas, unreasonably large deltas, stale timestamps, permission denied, multiple edge cases combined
   - **DataStoreFallbackTest:** Sensor timeout fallback, stale cache handling, cache corruption, race conditions, concurrent writes
   - **SensorRolloverAndResetTest:** Sensor decreases (device reboot), sensor jumps unreasonably large (health app sync), boundary conditions, consecutive resets
   - **ClosurePeriodHandlingTest:** Day boundary crossing, early morning reopening, step distribution across multiple hours, clamping to max
   - **StepCounterViewModelTest, StepCounterEdgeCasesTest, StepCounterInitializationTest, InitializationOrderingTest, SensorInitializationTimeoutTest:** ViewModel initialization, sensor timing, closure period logic
   - **StepRepositoryTest, SensorResetDetectionTest:** Data layer and sensor behavior

2. **Instrumented Tests** (`app/src/androidTest/`): Test UI and Android-specific functionality using Compose test framework

### Running Tests
```bash
./gradlew test                           # Run all unit tests (74 tests)
./gradlew testDebug                      # Run tests for debug variant only
./gradlew test -Dorg.gradle.debug=true   # Run with debugging
./gradlew connectedAndroidTest           # Run instrumented tests (requires device)
./gradlew testDebug -k                   # Continue after failures
./gradlew testDebug --tests "ClosurePeriodHandlingTest"  # Run specific test file
```

### Test Philosophy
- **Edge Case Focus:** Tests cover scenarios that occur in production (device reboots, corrupted preferences, permission changes, sensor resets)
- **Conservative Validation:** Tests verify that invalid data is rejected and safe defaults are used
- **Data Integrity:** Tests ensure step counts are never negative, never exceed reasonable maximums, and are preserved across edge cases

## Navigation Pattern

**Current Implementation (App.kt):**
```kotlin
enum class AppDestinations(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    HISTORY("History", Icons.AutoMirrored.Filled.List),  // List icon for historical records
    PROFILE("Profile", Icons.Filled.AccountBox),
}

// In MyHourlyStepCounterV2App composable
when (currentDestination) {
    AppDestinations.HOME -> HomeScreen(viewModel, modifier)
    AppDestinations.HISTORY -> HistoryScreen(viewModel, modifier)
    AppDestinations.PROFILE -> ProfileScreen(modifier)
}
```

**Adding new destination screens:**
1. Add entry to `AppDestinations` enum with label and icon
2. Create `@Composable` screen function (pass ViewModel if needed)
3. Add `when` branch in `MyHourlyStepCounterV2App()` to render the screen

Example (adding Settings):
```kotlin
// In AppDestinations enum
SETTINGS("Settings", Icons.Default.Settings),

// In MyHourlyStepCounterV2App when block
AppDestinations.SETTINGS -> SettingsScreen(viewModel, modifier)
```

## Resources

- App resources are in `app/src/main/res/`
- String resources: `res/values/strings.xml`
- App name is defined as `@string/app_name` in manifest and can be changed in strings.xml
- App icons are in `res/mipmap-*` directories for different densities

## Common Issues

1. **Build fails with "Unable to strip native libraries"**: This is a warning for the graphics path library and doesn't affect functionality.
2. **Theme not applying**: Ensure all Compose functions are wrapped with `MyHourlyStepCounterV2Theme` in previews and the root composable.
3. **Navigation state lost on rotation**: State is preserved with `rememberSaveable` which handles configuration changes automatically.
4. **Step count shows 0 after long closure**: Check logcat for "Closure period detected" logs. Verify smart distribution logic is distributing to correct hours.
5. **Daily total doesn't match Samsung Health**: Check sensor reset logs. May indicate another app is accessing the sensor mid-read.

## Key Configuration Constants

**StepTrackerConfig.kt** - Read-only app-wide constants:
```kotlin
object StepTrackerConfig {
    const val MORNING_THRESHOLD_HOUR = 10          // Before 10am = early morning
    const val MAX_STEPS_PER_HOUR = 10000           // Cap for unreasonable values
    const val MORNING_THRESHOLD_DISPLAY = "10:00 AM"
    const val MAX_STEPS_DISPLAY = "10,000"
}
```

**PermissionHelper.kt** - ACTIVITY_RECOGNITION permission management:
- `hasActivityRecognitionPermission(context)`: Check if permission granted
- `getRequiredPermissions()`: Returns array of permissions to request (API-level aware)
- MainActivity requests permissions via `registerForActivityResult()` launcher

**Important:** Do not allow user editing of StepTrackerConfig values. These are production-validated constants that ensure data integrity. ProfileScreen displays these values read-only for transparency.

---

## Session Summary: Closure Period Handling & Comprehensive Testing

### Key Decisions Made

1. **Smart Step Distribution:** When app reopens after closure, distribute steps intelligently based on time of day—early morning (before 10am) puts all steps in current hour; later (10am+) distributes evenly across waking hours assuming normal activity.
2. **Day Boundary Handling:** Save yesterday's incomplete hour as 0 steps, assuming sleep period, preventing data corruption from incomplete hours.
3. **Conservative Fallback:** When uncertain (sensor unavailable, corrupted data, permission denied), use 0 or cached values rather than risking invalid data.
4. **Maximum Clamping:** Enforce 10,000 steps/hour maximum to prevent health app sync anomalies and sensor glitches from corrupting data.

### Code Patterns Established

- **Config.kt:** Centralized constants (MORNING_THRESHOLD_HOUR, MAX_STEPS_PER_HOUR) with read-only Profile display—no user edits allowed.
- **Graceful Degradation:** Fallback priority: Sensor > DataStore Cache > Safe Default (0). Handles timeouts, permissions, and corruption independently.
- **Preference Tracking:** Added `lastOpenDate` preference to detect day boundaries; tracks hour baselines across reboots.
- **Extensive Logging:** Diagnostic logs at every decision point (day boundary, distribution logic, sensor reads) for production debugging.

### Next Steps Identified

1. **Integration Testing:** Add WorkManager TestDriver instrumentation tests to verify hourly boundary triggering end-to-end.
2. **Permission Smoke Tests:** Test runtime permission grant/deny scenarios at app level.
3. **Production Monitoring:** Watch for edge case logs in real usage; adjust thresholds if needed.
4. **Optional Low-Priority:** Consider UI improvements to show "estimated" label for distributed hours.

---

## Session Summary: Hour Distribution Bug Fix

### Key Decisions Made

1. **Calendar Iteration Bug Root Cause:** Identified critical bug where `calendar.apply { add(Calendar.HOUR_OF_DAY, 1) }` inside a loop **accumulates**—each iteration compounds the previous addition, skipping the first hour and adding an extra hour at the end.
2. **Fix Strategy:** Replace accumulative `add()` with direct hour assignment using `set(Calendar.HOUR_OF_DAY, threshold + hour)`, creating a fresh calendar for each iteration to avoid side effects.
3. **Impact Quantified:** Bug caused distribution of 1000 sensor-detected steps to only ~400 recorded steps (missing 600 steps from missed hours).

### Code Patterns Established

- **Fresh Calendar Per Iteration:** When calculating multiple hour timestamps in a loop, use `Calendar.getInstance()` each iteration and set absolute hour values instead of relative adjustments. Avoids mutation bugs.
- **Direct Assignment vs Incremental:** Replace `calendar.apply { add(field, delta) }` patterns with `calendar.set(field, absoluteValue)` when iterating—safer and more readable.
- **Diagnostic Logging in Loops:** Added per-iteration logs showing which hour receives which step count, enabling production debugging of distribution logic.

### Next Steps Identified

1. **Real-World Testing:** Deploy fix and verify daily totals match Samsung Health across closure scenarios.
2. **Edge Case Regression Testing:** Run existing ClosurePeriodHandlingTest suite to ensure fix didn't break other closure logic.
3. **Monitor Distributed Hour Logs:** Watch device logs for hour distribution patterns to confirm all hours are captured correctly.
4. **Optional: Add Integration Test:** Create instrumentation test specifically for multi-hour closure scenarios to prevent regression.

---

## Session Summary: Documentation Update & UI Polish

### Key Decisions Made

1. **Code Review Against Spec:** Conducted comprehensive review of implementation against CLAUDE.md requirements. Identified documentation drift—architecture section described simple placeholder UI, but production has full MVVM + Repository pattern with 74 tests.
2. **Documentation Overhaul:** Completely rewrote CLAUDE.md to reflect actual architecture (data/sensor/worker/ui packages), all dependencies (Room, DataStore, WorkManager), comprehensive test coverage, and key patterns (closure handling, fallback priority, validation).
3. **UI Consistency Fix:** Fixed History tab icon duplication (was using Home icon). Selected `Icons.AutoMirrored.Filled.List` as semantically appropriate for historical records display.

### Code Patterns Established

- **Documentation as Code:** CLAUDE.md must stay synchronized with implementation. Document actual architecture, not aspirational or outdated descriptions.
- **Semantic Icon Selection:** Choose Material icons that represent function (List for records history, not generic placeholders).
- **Complete Architecture Documentation:** Document all layers (presentation, data, sensor, background), key patterns, and production-validated constants in project guidance files.

### Next Steps Identified

1. **Display Update Frequency:** Spec requires 3-second step count updates, but implementation uses event-driven sensor + 1-second clock. Monitor if current behavior is acceptable or needs throttling adjustment.
2. **Documentation Maintenance:** Keep CLAUDE.md updated as architecture evolves to prevent future drift.
3. **Optional: Deprecation Warnings:** Consider updating ProfileScreen to use `HorizontalDivider` instead of deprecated `Divider`.
