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
│   │   ├── MainActivity.kt          # Entry point with NavigationSuiteScaffold
│   │   └── ui/theme/
│   │       ├── Theme.kt             # Material3 theme with dynamic colors
│   │       ├── Color.kt
│   │       └── Type.kt
│   ├── res/                         # Resources (strings, colors, drawable, mipmap)
│   └── AndroidManifest.xml
├── src/test/                        # Unit tests (JUnit 4)
├── src/androidTest/                 # Instrumented tests (Espresso + Compose)
└── build.gradle.kts                 # App module configuration
```

**Application Package:** `com.example.myhourlystepcounterv2`

## Architecture

The app uses a single-activity architecture with Jetpack Compose:

1. **MainActivity.kt:** The entry point that sets up edge-to-edge rendering and applies the theme.

2. **MyHourlyStepCounterV2App():** The root composable that manages navigation state using `rememberSaveable` for state persistence across recompositions and configuration changes.

3. **NavigationSuiteScaffold:** An adaptive navigation component from Material3 that automatically selects the best navigation UI (rail, bar, or drawer) based on screen size.

4. **AppDestinations Enum:** Defines three navigation destinations:
   - HOME (with Home icon)
   - FAVORITES (with Favorite icon)
   - PROFILE (with AccountBox icon)

The UI currently displays a simple `Greeting` composable in the scaffold content area. New screens should be added as composable functions and conditionally displayed based on the `currentDestination` state.

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
- `androidx.lifecycle:lifecycle-runtime-ktx` - Lifecycle management
- Testing: JUnit 4, Espresso, Compose UI test framework

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

1. **Unit Tests** (`app/src/test/`): Test business logic using JUnit 4
2. **Instrumented Tests** (`app/src/androidTest/`): Test UI and Android-specific functionality using Compose test framework

Example commands:
```bash
./gradlew test -Dorg.gradle.debug=true  # Run with debugging
./gradlew testDebug                      # Run tests for debug variant only
```

## Navigation Pattern

When adding new destination screens:

1. Add a new entry to `AppDestinations` enum with label and icon
2. Create a new `@Composable` function for the screen
3. Update `MyHourlyStepCounterV2App()` to conditionally render screens based on `currentDestination`

Example:
```kotlin
// In AppDestinations enum
NEWSFEEDS("News Feed", Icons.Default.NotificationsActive),

// In MyHourlyStepCounterV2App composable
when (currentDestination) {
    AppDestinations.HOME -> HomeScreen()
    AppDestinations.FAVORITES -> FavoritesScreen()
    AppDestinations.PROFILE -> ProfileScreen()
    AppDestinations.NEWSFEEDS -> NewsFeedScreen()
}
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
