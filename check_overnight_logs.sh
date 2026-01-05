#!/bin/bash

# Script to analyze overnight WorkManager execution logs
# Usage: ./check_overnight_logs.sh

echo "=================================================="
echo "  Hourly Step Counter - Overnight Log Analysis"
echo "=================================================="
echo ""

ADB="$HOME/Library/Android/sdk/platform-tools/adb"

# Check if device is connected
if ! $ADB devices | grep -q "device$"; then
    echo "❌ Error: No Android device connected"
    echo "   Please connect your device and try again"
    exit 1
fi

echo "📱 Device connected: $($ADB shell getprop ro.product.model | tr -d '\r')"
echo ""

# Get current date for filtering
CURRENT_DATE=$(date +"%m-%d")
echo "📅 Analyzing logs from: $(date +'%Y-%m-%d')"
echo ""

# Extract all StepCounterWorker and StepCounter (ViewModel) logs
echo "🔍 Extracting StepCounter logs..."
LOGFILE="/tmp/step_counter_logs_$$.txt"
$ADB logcat -d | grep -E "(StepCounterWorker|StepCounter)" > "$LOGFILE"

TOTAL_LINES=$(wc -l < "$LOGFILE")
echo "   Found $TOTAL_LINES log lines"
echo ""

if [ "$TOTAL_LINES" -eq 0 ]; then
    echo "⚠️  No WorkManager logs found. The worker may not have run yet."
    echo "   Check back after the next hour boundary."
    rm "$LOGFILE"
    exit 0
fi

echo "=================================================="
echo "  SUMMARY"
echo "=================================================="

# Count successful saves
SUCCESSFUL_SAVES=$(grep "✓ SAVED:" "$LOGFILE" | wc -l | tr -d ' ')
echo "✅ Successful hour saves: $SUCCESSFUL_SAVES"

# Count sensor successes
SENSOR_SUCCESS=$(grep "sensorSuccess=true" "$LOGFILE" | wc -l | tr -d ' ')
SENSOR_FAIL=$(grep "sensorSuccess=false" "$LOGFILE" | wc -l | tr -d ' ')
echo "📡 Sensor reads: $SENSOR_SUCCESS successful, $SENSOR_FAIL failed"

# Count specific issues
MISSING_BASELINE=$(grep "MISSING BASELINE" "$LOGFILE" | wc -l | tr -d ' ')
SENSOR_UNAVAIL=$(grep "SENSOR UNAVAILABLE" "$LOGFILE" | wc -l | tr -d ' ')
SENSOR_RESET=$(grep "SENSOR RESET" "$LOGFILE" | wc -l | tr -d ' ')
PERM_DENIED=$(grep "permission not granted" "$LOGFILE" | wc -l | tr -d ' ')

echo ""
echo "Issues detected:"
[ "$MISSING_BASELINE" -gt 0 ] && echo "  ⚠️  Missing baseline (app killed): $MISSING_BASELINE times"
[ "$SENSOR_UNAVAIL" -gt 0 ] && echo "  ⚠️  Sensor unavailable: $SENSOR_UNAVAIL times"
[ "$SENSOR_RESET" -gt 0 ] && echo "  ⚠️  Sensor reset detected: $SENSOR_RESET times"
[ "$PERM_DENIED" -gt 0 ] && echo "  ❌ Permission denied: $PERM_DENIED times"

if [ "$MISSING_BASELINE" -eq 0 ] && [ "$SENSOR_UNAVAIL" -eq 0 ] && [ "$SENSOR_RESET" -eq 0 ] && [ "$PERM_DENIED" -eq 0 ]; then
    echo "  ✅ No issues detected!"
fi

echo ""
echo "=================================================="
echo "  HOURLY BREAKDOWN"
echo "=================================================="

# Extract saved hours with timestamps from both ViewModel and WorkManager
echo ""
echo "Legend: [VM] = ViewModel save  [WM] = WorkManager save"
echo ""

# First show ViewModel saves
grep "VIEWMODEL SAVED:" "$LOGFILE" | while read -r line; do
    # Extract timestamp and steps
    if [[ $line =~ Hour\ ([0-9-]+\ [0-9:]+)\ →\ ([0-9]+)\ steps ]]; then
        HOUR="${BASH_REMATCH[1]}"
        STEPS="${BASH_REMATCH[2]}"

        if [ "$STEPS" -eq 0 ]; then
            echo "  [VM] 🕐 $HOUR → $STEPS steps ⚠️"
        else
            echo "  [VM] 🕐 $HOUR → $STEPS steps ✅"
        fi
    fi
done

# Then show WorkManager saves
grep "✓ SAVED:" "$LOGFILE" | while read -r line; do
    # Extract timestamp and steps
    if [[ $line =~ Hour\ ([0-9-]+\ [0-9:]+)\ →\ ([0-9]+)\ steps ]]; then
        HOUR="${BASH_REMATCH[1]}"
        STEPS="${BASH_REMATCH[2]}"

        if [ "$STEPS" -eq 0 ]; then
            echo "  [WM] 🕐 $HOUR → $STEPS steps ⚠️"
        else
            echo "  [WM] 🕐 $HOUR → $STEPS steps ✅"
        fi
    fi
done

# Show skipped WorkManager saves (race condition detected)
SKIPPED=$(grep "SKIPPING SAVE:" "$LOGFILE" | wc -l | tr -d ' ')
if [ "$SKIPPED" -gt 0 ]; then
    echo ""
    echo "  ℹ️  WorkManager skipped $SKIPPED hour(s) that ViewModel already saved (race avoided)"
    grep "SKIPPING SAVE:" "$LOGFILE" | while read -r line; do
        if [[ $line =~ Hour\ ([0-9-]+\ [0-9:]+)\ already\ has\ ([0-9]+)\ steps ]]; then
            HOUR="${BASH_REMATCH[1]}"
            STEPS="${BASH_REMATCH[2]}"
            echo "      → $HOUR ($STEPS steps preserved)"
        fi
    done
fi

echo ""
echo "=================================================="
echo "  HOUR BOUNDARY DETECTIONS"
echo "=================================================="
echo ""

# Show when ViewModel detected hour boundaries
VMBOUND=$(grep "HOUR BOUNDARY DETECTED:" "$LOGFILE" | wc -l | tr -d ' ')
if [ "$VMBOUND" -gt 0 ]; then
    echo "ViewModel detected $VMBOUND hour transition(s):"
    grep "HOUR BOUNDARY DETECTED:" "$LOGFILE" | while read -r line; do
        echo "  ⏰ $line" | sed 's/.*HOUR BOUNDARY DETECTED://'
    done
    echo ""
fi

echo ""
echo "=================================================="
echo "  DETAILED DIAGNOSTICS"
echo "=================================================="
echo ""

# Show diagnostic messages grouped by execution
grep "DIAGNOSTIC:" "$LOGFILE" | tail -50 | while read -r line; do
    # Color-code based on message type
    if [[ $line =~ "MISSING BASELINE" ]]; then
        echo "⚠️  $line"
    elif [[ $line =~ "SENSOR UNAVAILABLE" ]]; then
        echo "⚠️  $line"
    elif [[ $line =~ "SENSOR RESET" ]]; then
        echo "⚠️  $line"
    elif [[ $line =~ "Valid delta" ]]; then
        echo "✅ $line"
    else
        echo "   $line"
    fi
done

echo ""
echo "=================================================="
echo "  RECENT INITIALIZATIONS"
echo "=================================================="
echo ""

# Show recent hour initializations
grep "✓ INITIALIZED NEXT HOUR:" "$LOGFILE" | tail -5

echo ""
echo "=================================================="
echo "  SENSOR READING DETAILS"
echo "=================================================="
echo ""

# Show sensor retry attempts for the most recent execution
echo "Most recent sensor read attempts:"
grep "DIAGNOSTIC: Retry" "$LOGFILE" | tail -20 | while read -r line; do
    if [[ $line =~ sensorValue=0 ]]; then
        echo "  ❌ $line"
    else
        echo "  ✅ $line"
    fi
done

echo ""
echo "=================================================="
echo "  RECOMMENDATIONS"
echo "=================================================="
echo ""

if [ "$MISSING_BASELINE" -gt 0 ]; then
    echo "⚠️  MISSING BASELINE DETECTED"
    echo "   → App was killed before preferences were initialized"
    echo "   → Try keeping the app open for 1-2 minutes after launch"
    echo "   → Android may be aggressively killing the app"
    echo ""
fi

if [ "$SENSOR_UNAVAIL" -gt 0 ]; then
    echo "⚠️  SENSOR UNAVAILABLE IN BACKGROUND"
    echo "   → Step sensor cannot be accessed from background on this device"
    echo "   → This is a hardware/Android limitation"
    echo "   → Consider foreground service (battery impact)"
    echo ""
fi

if [ "$SENSOR_FAIL" -gt "$SENSOR_SUCCESS" ] && [ "$SENSOR_SUCCESS" -lt 3 ]; then
    echo "⚠️  SENSOR READ FAILURES"
    echo "   → Sensor is slow to respond in background context"
    echo "   → Current timeout: 2000ms may need to be increased"
    echo ""
fi

if [ "$SUCCESSFUL_SAVES" -gt 3 ] && [ "$MISSING_BASELINE" -eq 0 ] && [ "$SENSOR_UNAVAIL" -eq 0 ]; then
    echo "✅ WORKING WELL!"
    echo "   → WorkManager is executing reliably"
    echo "   → Sensor is readable from background"
    echo "   → Preferences are persisting correctly"
    echo ""
fi

echo "=================================================="
echo "  Full logs saved to: $LOGFILE"
echo "  To view: cat $LOGFILE"
echo "=================================================="
echo ""
