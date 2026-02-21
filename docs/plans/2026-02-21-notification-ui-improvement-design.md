# Notification UI Improvement Design

**Date:** 2026-02-21
**Status:** Approved

## Goal

Improve the persistent foreground notification so the hourly step count is bold/prominent, with the daily total shown in smaller regular-weight text below it.

## Current State

```
Title (bold):   StepNudge
Text (regular): This hour: 342 — Today: 4,891
```

Both data points are in `contentText` with equal visual weight. Neither the hourly nor daily count stands out.

## Proposed Layout

```
Title (bold):   👣 342 steps this hour
Text (regular): 📅 4,891 today
```

When syncing on startup:
```
Title (bold):   👣 syncing...
Text (regular): 📅 4,891 today
```

Android notification system renders `contentTitle` in bold, larger text, and `contentText` in regular weight — giving the hourly step count natural visual prominence without any custom views.

## Files to Change

### 1. `app/src/main/res/values/strings.xml`

- Add `notification_title_steps` — `"👣 %1$d steps this hour"`
- Add `notification_title_syncing` — `"👣 syncing..."`
- Update `notification_text_steps` — `"📅 %1$d today"` (daily total only, was `"This hour: %1$d — Today: %2$d"`)
- Update `notification_text_syncing` — `"📅 %1$d today"` (daily total only, was `"This hour: syncing... — Today: %1$d"`)

### 2. `app/src/main/java/com/example/myhourlystepcounterv2/services/StepCounterForegroundService.kt`

In `buildNotification()`:
- Change `contentTitle` to use the new `notification_title_steps` / `notification_title_syncing` strings (formatted with `currentHourSteps`)
- Change `contentText` to use the updated `notification_text_steps` / `notification_text_syncing` strings (formatted with `totalSteps` only)

## Scope

- 2 files changed
- No logic changes
- No new classes or dependencies
- Existing test suite unaffected (notification strings are not unit tested)
