# Screen Dimmer — Automatic Brightness for Motorcycle Tablets

Manages screen brightness and power on Android tablets used for motorcycle navigation. The screen runs at full brightness while riding (powered by the motorcycle), then automatically dims and optionally shuts off when the engine is switched off.

## The Problem

Motorcycle navigation tablets run outside in direct sunlight at full brightness. When the motorcycle is turned off, the tablet keeps running at full power — draining its own battery rapidly. The top end of the brightness range also has sharply diminishing returns: the last 20% of brightness consumes a disproportionate share of screen power for little visible gain in daylight.

## What This App Does

**While on AC power (motorcycle running):**
- Lifts the brightness cap to 100%
- Wakes the screen if it was off

**While on battery (motorcycle off):**
- Caps brightness at a configurable maximum (default 80%)
- Optionally turns the screen off after a configurable idle period

Brightness adapts to ambient light automatically using a sliding median filter over the light sensor — smooth, spike-resistant transitions without abrupt jumps.

## Setup

Requires Android 8.0 (Oreo) or newer.

1. Install the APK (download from Releases)
2. Open the app and grant the three permissions it asks for:
   - **Notifications** — required to show the persistent service notification (Android 13+)
   - **Modify system settings** — required to control screen brightness
   - **Unrestricted battery usage** — required so Android doesn't throttle the brightness service while riding
3. Tap **Start**
4. The service runs in the background and starts automatically after reboot

## Settings

| Setting | What it does | Default |
|---|---|---|
| Min brightness below | Lux level below which the screen stays at minimum brightness | 10 lx |
| Max brightness above | Lux level above which the screen reaches maximum brightness | 50 000 lx |
| Battery brightness cap | Maximum brightness allowed while on battery | 80% |
| Smoothing | How quickly brightness tracks light changes (Fast = 5 s, Medium = 12 s, Slow = 25 s) | Medium |
| Auto-off | Minutes after AC loss before the screen turns off (0 = disabled) | 5 min |

The live lux reading at the top of the screen shows what the light sensor sees, alongside the service state (Running / Stopped).

## Build

Releases are built automatically by GitHub Actions on every push to `main` and published as a pre-release tagged `nightly`. Grab the latest APK from the [Releases](../../releases/tag/nightly) page.
