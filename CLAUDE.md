# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android foreground service app for motorcycle navigation tablets. Manages screen brightness and power based on AC state:

- **On AC**: brightness cap lifted to 100%, screen woken up
- **On battery**: brightness capped at a configurable % (default 80%), optional auto-off timer after configurable minutes

Use case: tablets left in sunlight after the motorcycle is turned off drain the battery quickly at full brightness. This app prevents that.

## Build Constraints

**Do not build locally.** All builds run in CI/CD (GitHub Actions). The Gradle wrapper jar is intentionally not committed — it is generated during CI via `gradle wrapper --gradle-version=8.8`, so `./gradlew` does not exist in a fresh clone. There are no tests or linters configured; verification happens by pushing and watching CI.

- **Gradle**: 8.8 · **AGP**: 8.5.2 · **Kotlin**: 1.9.25 (versions in `gradle/libs.versions.toml`)
- **JDK**: 17 (Temurin)
- **Build target**: `./gradlew assembleRelease`
- **SDK**: minSdk = compileSdk = targetSdk = 34
- **Language**: Kotlin, sources under `app/src/main/kotlin/de/codevoid/screensaver/`
- Dependencies are deliberately minimal (`core-ktx`, `appcompat`); the UI is hand-written `SeekBar`/`TextView` in one layout, no view binding, no Compose.

## CI/CD

Every push to `main` triggers `.github/workflows/nightly.yml`, which:
1. Generates the Gradle wrapper
2. Decodes the signing keystore from `SIGNING_KEYSTORE_BASE64` secret into `$RUNNER_TEMP/release.keystore`
3. Builds a signed release APK (`assembleRelease`, minified with ProGuard)
4. Publishes a rolling pre-release tagged `nightly` on GitHub Releases, deleting the previous one first so exactly one nightly exists

Required GitHub secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. The workflow maps these into the env vars `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, which `app/build.gradle.kts` reads for the release signing config (falling back to a placeholder so configuration doesn't fail without them).

## Architecture

Six files, one package (`de.codevoid.screensaver`):

Note that the notification is derived from the *computed target*, so a healthy-looking notification is not evidence that brightness is actually being applied — check `hasControl` and the value in `Settings.System.SCREEN_BRIGHTNESS` instead.

- **`BrightnessService`** — the core foreground service (`foregroundServiceType="specialUse"`). Owns all runtime state: registers `BroadcastReceiver`s for `ACTION_POWER_CONNECTED`/`DISCONNECTED` (exported — system broadcast) and `ACTION_SCREEN_ON`/`OFF` (not exported). The 500 ms `Handler` tick loop runs only while the screen is on — `ACTION_SCREEN_OFF` removes callbacks; `ACTION_SCREEN_ON` calls `controller.resetBuffer()` and restarts the loop. Each tick re-reads prefs (`applyPrefs()`), ticks the controller, and refreshes the notification. The notification shows live state (median lux → brightness target) and is reposted only when its text actually changes. Started sticky; a start intent with action `ACTION_STOP` stops it.
- **`BrightnessController`** — brightness math, deliberately free of Android service plumbing (only touches `ContentResolver`/`Settings.System`). See "Brightness curve" below.
- **`MainActivity`** — settings UI: live light-sensor readout, service state indicator, five sliders, Start/Stop. Sliders write straight to `Prefs` on every change (no Apply button); the running service picks them up on its next tick. See "Permission flow" below.
- **`Prefs`** — `SharedPreferences` singleton; call `Prefs.init(context)` before any access (service and activity each do this in their own `onCreate`). Keys: `brightness_cap` (Float 0.5–1.0, default 0.8), `reaction_window` (Int, clamped 10–50, default 25), `dark_lux` (Float, default 10), `bright_lux` (Float, default 50 000), `auto_off_minutes` (Int, default 5).
- **`BootReceiver`** — starts the service on `BOOT_COMPLETED`.
- **`LuxFormat.kt`** — top-level `formatLux(Float)`; formats lux across six orders of magnitude (`"5.0 lx"`, `"250 lx"`, `"1.2k lx"`, `"50k lx"`). Used in the notification and the activity's live readout.

### Brightness curve (`BrightnessController`)

**The app does not use system auto-brightness** — the service forces `SCREEN_BRIGHTNESS_MODE_MANUAL` and implements its own curve. Per tick:

1. Lux readings land in a `MAX_WINDOW`=50 element ring buffer via `onLuxReading()`. The buffer holds the *latest* reading repeated as needed rather than one-per-sensor-event: light sensors go quiet in static conditions, so the tick loop — not the sensor — drives sampling.
2. The median of the last `windowSize` samples (user-configurable 10–50, default 25) is taken. Median, not average: spike-resistant against shadows and headlights.
3. `luxToBrightness()` maps the median log10-scale between `darkLux` (min brightness below it) and `brightLux` (max above it), with a `MIN_LOG_SPAN`=0.3 guard so the endpoints can't collapse. The log fraction is raised to **gamma 2.2** because the Android brightness value is linear backlight power while perception is ~power^(1/2.2); without it, dim indoor light already looked ~65% bright.
4. The result is clamped to `maxBrightness() = 255 · capFraction^2.2` — the cap is also gamma-corrected, so "80%" means 80% *perceived*, not 80% of raw units. `capFraction` is 1.0 on AC, `Prefs.brightnessCap` on battery.
5. **Step limiting** (`stepToward`) caps movement to 1/`windowSize` of the range per tick, computed in gamma (perceived) space — a raw step of 25 near the bottom is a ~27% perceived jump, so clamping raw units would not be perceptually uniform. It also forces a minimum move of one raw unit: gamma compresses the bottom of the range so hard that a full step there measures under one unit, and truncating it to zero stalls the ramp at the floor permanently.
6. `Settings.System.SCREEN_BRIGHTNESS` is written only when it differs from the value **read back from the system**, not from the value we last wrote — see "keeping control" below.

Brightness floor is **1**/255, not 5 — the system slider is gamma-corrected, so 5 already sits at ~20% slider position and the screen never went truly dim. `resetBuffer()` clears the buffer and sets `lastWritten` to the `-1` "no baseline" sentinel, so the next tick re-fills the buffer with the current reading and jumps straight to the target instead of ramping.

### Keeping control of the brightness setting

The app is not the only writer of `Settings.System.SCREEN_BRIGHTNESS`, and every one of these failure modes is silent — the notification is computed from the median lux and the *target*, so it looks perfectly healthy while nothing reaches the screen. Three defences, all in `BrightnessController`:

- **Ramp from reality, not from memory.** Each tick re-reads the current system brightness and ramps from that. Comparing against our own `lastWritten` was the bug: when anything else moved the brightness (quick-settings slider, system restore after boot, OEM power saver) the computed target still equalled `lastWritten`, so no write happened, and with stable ambient light the app never wrote again.
- **Re-assert manual mode every tick.** `ensureManualMode()` reads `SCREEN_BRIGHTNESS_MODE` and only writes when it has drifted. Adaptive brightness can be switched back on at any time, and while it is on the platform writes `SCREEN_BRIGHTNESS` continuously and outruns us.
- **Never drop a write result.** `Settings.System.putInt` returns `false` (some OEM builds throw `SecurityException`) when the `WRITE_SETTINGS` app-op isn't held. `putSetting()` records this in `hasControl`, which the service turns into an actionable notification instead of fake healthy numbers.

### Permission flow (`MainActivity.onResume`)

Three grants are needed, and **only one flow is launched per resume** — each subsequent `onResume` picks up the next:

1. `POST_NOTIFICATIONS` (Android 13+, normal runtime request). Without it the foreground-service notification is silently suppressed when the service starts on boot.
2. `WRITE_SETTINGS` — cannot be requested via the runtime-permission flow; the user is sent to `ACTION_MANAGE_WRITE_SETTINGS`. The Start button re-checks `Settings.System.canWrite()` and re-launches this rather than starting the service.
3. Battery-optimization exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) so Doze doesn't throttle the tick loop.

### Non-obvious mechanics

- **Auto-off** does not force the screen off directly. After the configured minutes on battery, the service temporarily sets `SCREEN_OFF_TIMEOUT` to 1 s, lets the screen time out naturally, then restores the previous timeout 5 s later (also restored on AC reconnect and in `onDestroy`). `turnScreenOff()` no-ops while a restore is pending — otherwise a second firing would capture 1 s as the "previous" timeout and lock it in permanently.
- **Screen wake on AC** uses a deprecated-but-functional `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` wake lock held for 3 s.
- **Initial AC state** is detected via a null-receiver `ACTION_BATTERY_CHANGED` query, since the connect/disconnect broadcasts only fire on transitions.
- **Service state in the UI** is push-based: `BrightnessService.isRunning` is a companion-object flag whose setter notifies a static `stateListeners` set. `MainActivity` adds its listener in `onResume` and *must* remove it in `onPause` — the set is static, so a leaked listener holds the activity.
- **Light-point slider ceiling**: a `brightLux` above what the sensor can report would make max brightness unreachable, so the slider is capped at `Sensor.maximumRange` and a stored value above it is clamped on activity start.

## Docs to keep in sync

Behaviour changes should also update `CHANGELOG.md` (Keep a Changelog format, everything under `[Unreleased]`) and, for user-visible settings, the settings table in `README.md`. `.github/development-journal.md` records build/CI decisions and predates most of the app code — treat it as history, not current state.
