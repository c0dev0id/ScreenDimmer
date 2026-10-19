<p align="center">
  <img src="docs/banner.svg" alt="Screen Dimmer" width="100%"/>
</p>

Automatic screen brightness and power control for Android tablets used as motorcycle navigation displays. It runs the screen bright while the bike powers it, then dims and optionally shuts off on battery so the tablet doesn't drain itself in the sun.

## Features

- **AC vs. battery modes** — full brightness while the bike is running, capped brightness once it's off.
- **Ambient adaption** — brightness tracks the light sensor through a median filter, smooth and spike-resistant.
- **Auto-off timer** — turns the screen off after a set idle period on battery.
- **Runs unattended** — background service that restarts after reboot.

## Setup

Requires Android 8.0 or newer.

1. Install the APK from Releases.
2. Open the app and grant the three permissions it requests:
   - **Notifications** — shows the persistent service notification (Android 13+).
   - **Modify system settings** — needed to control brightness.
   - **Unrestricted battery usage** — stops Android from throttling the service.
3. Tap **Start**.

The live lux reading and service state (Running / Stopped) show at the top of the screen.

## Settings

| Setting | What it does | Default |
|---|---|---|
| Min brightness below | Lux level below which the screen stays at minimum brightness | 10 lx |
| Max brightness above | Lux level above which the screen reaches maximum brightness | 50 000 lx |
| Battery brightness cap | Maximum brightness allowed on battery | 80% |
| Smoothing | How fast brightness tracks light changes (Fast / Medium / Slow) | Medium |
| Auto-off | Minutes on battery before the screen turns off (0 = disabled) | 5 min |
