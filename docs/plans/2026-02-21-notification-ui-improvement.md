# Notification UI Improvement Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the persistent notification display the hourly step count in bold/large title text, with the daily total in smaller regular-weight sub-text below it.

**Architecture:** Android's `NotificationCompat.Builder` renders `contentTitle` in bold, larger system font and `contentText` in regular weight. We move the hourly count into `contentTitle` and daily total into `contentText`, updating the string resources to match. No logic changes — just rearranging which data goes into which field.

**Tech Stack:** Kotlin, NotificationCompat (AndroidX), Android string resources

---

### Task 1: Update string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

This task has no unit-testable logic — it is a resource-only change. Verify by reading the file after editing.

**Step 1: Update `notification_text_steps`**

Currently:
```xml
<string name="notification_text_steps">This hour: %1$d — Today: %2$d</string>
```

Change to (daily total only, with emoji):
```xml
<string name="notification_text_steps">📅 %1$d today</string>
```

Note: the argument index changes from `%2$d` to `%1$d` because the hourly count is removed from this string — only the daily total remains.

**Step 2: Update `notification_text_syncing`**

Currently:
```xml
<string name="notification_text_syncing">This hour: syncing... — Today: %1$d</string>
```

Change to:
```xml
<string name="notification_text_syncing">📅 %1$d today</string>
```

**Step 3: Add new title strings**

After the existing `notification_text_syncing` line, add:
```xml
<string name="notification_title_steps">👣 %1$d steps this hour</string>
<string name="notification_title_syncing">👣 syncing…</string>
```

**Step 4: Verify the file looks correct**

Read `app/src/main/res/values/strings.xml` and confirm the 4 notification strings look like:
```xml
<string name="notification_text_steps">📅 %1$d today</string>
<string name="notification_text_syncing">📅 %1$d today</string>
<string name="notification_title_steps">👣 %1$d steps this hour</string>
<string name="notification_title_syncing">👣 syncing…</string>
```

**Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: update notification string resources for emoji layout"
```

---

### Task 2: Update `buildNotification()` in the foreground service

**Files:**
- Modify: `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt:323-354`

**Step 1: Read the current `buildNotification()` method**

Read lines 323–354 of `StepCounterForegroundService.kt` to confirm the current implementation before editing.

Expected current state:
```kotlin
private fun buildNotification(currentHourSteps: Int, totalSteps: Int, isSyncing: Boolean = false): Notification {
    val title = getString(R.string.app_name)
    val text = if (isSyncing) {
        getString(R.string.notification_text_syncing, totalSteps)
    } else {
        getString(R.string.notification_text_steps, currentHourSteps, totalSteps)
    }
    ...
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(text)
        ...
}
```

**Step 2: Update the title and text variables**

Replace:
```kotlin
val title = getString(R.string.app_name)
val text = if (isSyncing) {
    getString(R.string.notification_text_syncing, totalSteps)
} else {
    getString(R.string.notification_text_steps, currentHourSteps, totalSteps)
}
```

With:
```kotlin
val title = if (isSyncing) {
    getString(R.string.notification_title_syncing)
} else {
    getString(R.string.notification_title_steps, currentHourSteps)
}
val text = getString(R.string.notification_text_steps, totalSteps)
```

Note: both syncing and non-syncing states use `notification_text_steps` for the sub-text (daily total format is the same either way).

**Step 3: Build the app to verify no compile errors**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL` with no errors. If there are string format argument errors, check that `notification_text_steps` uses `%1$d` (single argument) and `notification_title_steps` uses `%1$d` (single argument).

**Step 4: Run unit tests to verify no regressions**

```bash
./gradlew testDebug
```

Expected: all tests pass (notification strings are not covered by unit tests, but the surrounding logic should be unaffected).

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt
git commit -m "feat: move hourly steps to bold notification title with emoji layout"
```

---

## Manual Verification

After deploying to a device:

1. Install the debug build: `./gradlew installDebug`
2. Open the app — the foreground service starts and the persistent notification appears
3. Pull down the notification shade
4. Confirm the notification shows:
   - **Bold top line:** `👣 NNN steps this hour`
   - **Regular sub-text:** `📅 NNNN today`
5. Force-stop the app, reopen it — confirm the notification returns to the same layout
6. Wait for or simulate a sensor sync startup — confirm the syncing state shows:
   - **Bold top line:** `👣 syncing…`
   - **Regular sub-text:** `📅 NNNN today`
