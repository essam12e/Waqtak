#!/usr/bin/env bash
# Runs on the CI emulator (see .github/workflows/android.yml). Every step logs to device-out/.
set -u
OUT=device-out
mkdir -p "$OUT"
FAIL=0
PKG=com.waqtak.app
RUNNER=com.waqtak.app.test/androidx.test.runner.AndroidJUnitRunner

adb root >/dev/null 2>&1 || true
adb wait-for-device
sleep 3

./gradlew --no-daemon -q assembleDebug assembleDebugAndroidTest || exit 1
mkdir -p "$OUT/screens"

# Everything below runs with networking off.
adb shell cmd connectivity airplane-mode enable
sleep 2
adb shell settings get global airplane_mode_on | tee "$OUT/airplane.txt"

# --- Upgrade from the released 1.0.0: install it, finish its setup, log a drink, then update over it.
adb install scripts/fixtures/waqtak-1.0.0.apk | tee "$OUT/install-v1.txt"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 6
for i in 1 2 3 4 5 6; do python3 scripts/ui_tap.py "التالي" >> "$OUT/v1-setup.txt" 2>&1; done
python3 scripts/ui_tap.py "تفعيل الجدول" >> "$OUT/v1-setup.txt" 2>&1
sleep 4
python3 scripts/ui_tap.py "شربت" >> "$OUT/v1-setup.txt" 2>&1
sleep 2
adb exec-out screencap -p > "$OUT/screens/00_v1_before_upgrade.png"
cat "$OUT/v1-setup.txt"

adb install -r -g app/build/outputs/apk/debug/app-debug.apk | tee "$OUT/install-debug.txt"
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | tee "$OUT/install-test.txt"


run() { # $1 = class or class#method, $2 = log name
  adb shell am instrument -w -r -e class "$1" "$RUNNER" > "$OUT/$2.txt" 2>&1
  if grep -q "^OK (" "$OUT/$2.txt"; then echo "PASS $1" | tee -a "$OUT/summary.txt"
  else echo "FAIL $1" | tee -a "$OUT/summary.txt"; FAIL=1; tail -40 "$OUT/$2.txt"; fi
}

adb logcat -c
run com.waqtak.app.UpgradeDeviceTest upgrade
run com.waqtak.app.NoNetworkTest no-network
run com.waqtak.app.AlarmDeviceTest alarm-device
run com.waqtak.app.ScheduleDeviceTest schedule-device

# Process death: phase 1 starts a 40 s meal timer, instrumentation ends and the process dies.
run com.waqtak.app.LifecycleDeviceTest#processDeath_phase1_startShortSessionThenDie death-1
adb shell am kill "$PKG"
sleep 2
adb shell pidof "$PKG" > "$OUT/death-pid-before-alarm.txt" || echo "no process (as expected)" >> "$OUT/death-pid-before-alarm.txt"
sleep 50
# The alarm must have started a fresh process and the ringing foreground service.
adb shell dumpsys activity services "$PKG" > "$OUT/death-services.txt"
if grep -q "AlarmService" "$OUT/death-services.txt" && grep -q "isForeground=true" "$OUT/death-services.txt"; then
  echo "PASS end-of-meal alarm rang in a fresh process after process death" | tee -a "$OUT/summary.txt"
else
  echo "FAIL ringing service not found after process death" | tee -a "$OUT/summary.txt"; FAIL=1
fi
adb shell dumpsys notification --noredact | grep -A3 "pkg=$PKG" > "$OUT/death-notifications.txt" || true
run com.waqtak.app.LifecycleDeviceTest#processDeath_phase2_sessionSurvivedAndEndRangOnce death-2

# Boot: phase 1 removes our alarms like a reboot does, then BOOT_COMPLETED must restore them.
run com.waqtak.app.LifecycleDeviceTest#boot_phase1_scheduleThenLoseAlarms boot-1
adb shell dumpsys alarm | grep -B1 -A4 "$PKG" > "$OUT/boot-alarms-before.txt" || true
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p "$PKG" | tee "$OUT/boot-broadcast.txt"
sleep 8
adb shell dumpsys alarm | grep -B1 -A4 "$PKG" > "$OUT/boot-alarms-after.txt" || true
if grep -q "MEAL_START" "$OUT/boot-alarms-after.txt"; then
  echo "PASS BOOT_COMPLETED rescheduled the meal alarm" | tee -a "$OUT/summary.txt"
else
  echo "FAIL meal alarm missing after BOOT_COMPLETED" | tee -a "$OUT/summary.txt"; FAIL=1
fi

# Denied permissions: revoking a runtime permission kills the app (Android behaviour), so check a fresh launch.
mkdir -p "$OUT/screens"
adb shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS
adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM deny
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" > "$OUT/denied-start.txt"
sleep 6
adb exec-out screencap -p > "$OUT/screens/18_permissions_denied_home.png"
adb logcat -d > "$OUT/logcat-denied.txt"
if adb shell pidof "$PKG" > /dev/null && ! grep -q "FATAL EXCEPTION" "$OUT/logcat-denied.txt"; then
  echo "PASS app runs with notifications and exact alarms denied" | tee -a "$OUT/summary.txt"
else
  echo "FAIL crash with denied permissions" | tee -a "$OUT/summary.txt"; FAIL=1; grep -A20 "FATAL EXCEPTION" "$OUT/logcat-denied.txt" | head -40
fi
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow

run com.waqtak.app.ScreenshotsTest screenshots

adb logcat -d > "$OUT/logcat-debug.txt"
grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG" "$OUT/logcat-debug.txt" > "$OUT/crashes-debug.txt" || true

mkdir -p "$OUT/screens" "$OUT/reports"
adb pull "/sdcard/Android/data/$PKG/files/screens/." "$OUT/screens/" || true
adb pull "/sdcard/Android/data/$PKG/files/reports/." "$OUT/reports/" || true

# Release APK (the delivered Waqtak.apk): install over the debug build (same signing key), launch, check no crash.
# (The upgrade path from 1.0.0 was verified above with the same key; versionCode 1 -> 2.)
adb logcat -c
if adb install -r out/Waqtak.apk | tee "$OUT/install-release.txt" | grep -q Success; then
  adb shell pm clear "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/release-start.txt"
  sleep 6
  adb shell pidof "$PKG" > "$OUT/release-pid.txt" && echo "PASS release APK installs and launches offline" | tee -a "$OUT/summary.txt" \
    || { echo "FAIL release APK process not running" | tee -a "$OUT/summary.txt"; FAIL=1; }
  adb exec-out screencap -p > "$OUT/screens/00_release_first_launch.png"
  adb logcat -d > "$OUT/logcat-release.txt"
  if grep -q "FATAL EXCEPTION" "$OUT/logcat-release.txt"; then echo "FAIL release crash" | tee -a "$OUT/summary.txt"; FAIL=1; fi
else
  echo "FAIL release APK install" | tee -a "$OUT/summary.txt"; FAIL=1
fi

adb shell cmd connectivity airplane-mode disable || true
cat "$OUT/summary.txt"
exit $FAIL
