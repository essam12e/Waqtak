# وَقْتَك | Waqtak

Native Android app (Kotlin, Jetpack Compose, Material 3) that organises meal and drink times with
reliable alarms, a gentle meal timer and original flat-art illustrations. Arabic (RTL, default) and English.

**Fully local.** No INTERNET permission, no account, no server, no Firebase, ads, analytics or AI.
All fonts, tones and images are bundled. Room stores records; DataStore stores settings.

- Install and enable alarms (Arabic): [`docs/INSTALL_AR.md`](docs/INSTALL_AR.md)
- Test report: [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md)

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35). The Gradle wrapper pins Gradle 8.14.3.

```bash
./gradlew assembleRelease testDebugUnitTest   # app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug
```

Signing: without a release key, release builds are signed with the committed **development** key
`app/debug-dev.keystore` (password `android`, not a secret) so updates install over each other.
For a real release key provide `WAQTAK_KEYSTORE`, `WAQTAK_KEYSTORE_PASSWORD`, `WAQTAK_KEY_ALIAS`,
`WAQTAK_KEY_PASSWORD` as environment variables (or an untracked `keystore.properties`). In CI, set the
repository secrets `WAQTAK_KEYSTORE_B64` (base64 keystore) and the three passwords.

CI (`.github/workflows/android.yml`) builds, runs unit tests, verifies the APK (apksigner, zipalign,
aapt2 badging, *no INTERNET permission*), then runs instrumented tests on an API 34 emulator in
airplane mode (`scripts/device-tests.sh`).

## Architecture

```
domain/   pure Kotlin, unit-tested: ScheduleEngine (suggestions), OccurrencePlanner (plans -> instants,
          midnight crossing, DST), HydrationPlanner, MealTimer (monotonic + wall clock), AlertQueue
data/     Room entities/DAOs, WaqtakRepository (all state transitions are conditional = idempotent),
          SettingsRepository (DataStore), backup (validated JSON export/import)
alarm/    AlarmScheduler (one PendingIntent per event type), AlarmReceiver, SystemEventReceiver
          (boot, update, time/zone, exact-alarm permission), AlarmService (foreground only while ringing),
          AlarmPlayer (USAGE_ALARM, vibration, audio focus, wake lock), AlarmActivity (over lock screen),
          AlarmController (stop sound first, then record), Reconciler (recovery policy), Notifications
ui/       Compose screens, theme (Cairo font, OFL), Canvas flat art
```

Key behaviours:
- Meal start/end use `AlarmManager.setAlarmClock` (exact, Doze-proof); drinks use
  `setExactAndAllowWhileIdle`, falling back to inexact windows if exact alarms are denied.
- The ringing foreground service uses type `systemExempted` (exact-alarm apps) or `mediaPlayback`;
  if it cannot start, an insistent `CATEGORY_ALARM` notification on the alarm stream is used.
- Simultaneous events never overlap: one sound at a time, meal end > meal start > test;
  drink reminders during a meal are merged/postponed.
- The meal timer uses `elapsedRealtime` while the device is up and the saved wall-clock start after a
  reboot. After reboot or a long absence old alarms are *not* replayed: missed meals appear in a summary,
  a timer that ended within 15 min rings once, older ones close silently.
- Snooze moves only that meal; "Redistribute the day" is an explicit, reviewable action that never
  overwrites times the user set without their tick.

## Licenses
Cairo font: SIL Open Font License 1.1 (`licenses/Cairo-OFL.txt`). Illustrations (Compose Canvas),
alarm tones (synthesised) and logo usage are original to the project.
