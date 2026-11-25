<div align="center">

<img src="assets/icon.png" width="128" alt="HJM Keypad icon">

# HJM Keypad

[简体中文](README.md) · **English**

**A draggable virtual keyboard and mouse overlay for Android games — coordinate-based multi-touch hot zones let you drag the camera and press new keys at the same time. No root, no game modification.**

[![Release](https://img.shields.io/github/v/release/114514hhha233/shizuku-keypad?label=release)](https://github.com/114514hhha233/shizuku-keypad/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)]()
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Shizuku](https://img.shields.io/badge/Shizuku-13.x-6a5acd)](https://shizuku.rikka.app/)

**[⬇ Download APK](https://github.com/114514hhha233/shizuku-keypad/releases/latest)** ·
[Source](https://github.com/114514hhha233/shizuku-keypad) ·
[Issues](https://github.com/114514hhha233/shizuku-keypad/issues)

</div>

HJM Keypad (formerly "Hajimi Virtual Keypad") adds a freely placeable virtual keyboard and mouse to Android games: each key is its own floating window, and **Shizuku** injects key / touch / mouse events into the system input layer, so cloud gaming, game streaming, emulators and native mobile games all work — no root and no game modification required.

- App name: HJM Keypad (package name unchanged; upgrades over previous installs)
- Package: `com.example.shizukukeypad`
- Version: 1.7.2 (versionCode 32)
- Min Android: 8.0 (API 26), targetSdk / compileSdk 34
- Dependencies: Shizuku 13.x (`dev.rikka.shizuku:api/aidl/provider:13.1.5`), Material Components 1.12
- Language: follows the system; Simplified Chinese and English included

> Experimental project — not yet validated on a wide range of devices and games. Feedback is welcome.

---

## Multi-touch (core, off by default)

This is the most important part of the project: **coordinate-based hot zones make "drag the camera and press a new key at the same time" actually work.**

### The problem it solves

Android touch streams have ownership: once the first finger lands on the game window, the whole gesture belongs to the game and every later finger goes to the game — the overlay never receives them. An injected "fake finger" belongs to a separate input stream and cannot take a slot on the touchscreen (verified on Android 15), so stealing touches through injection is a dead end.

### How it works

In multi-touch mode every key window is set to `FLAG_NOT_TOUCHABLE`, so keys no longer take touches. Triggering happens on the listening side:

1. Through **Shizuku** (shell identity) the app runs `getevent -lp` to list touchscreen devices and read the `min/max` range of `ABS_MT_POSITION_X/Y`, then `getevent -lt` to stream raw events from `/dev/input/event*`. It is **read-only and non-consuming** — the system still delivers every event to the game; we just see each finger's coordinates as well.
2. Raw multi-finger data is parsed: `ABS_MT_SLOT` (finger slot), `ABS_MT_TRACKING_ID` (finger identity), `ABS_MT_POSITION_X/Y` (raw coordinates), settled once per `SYN_REPORT` frame.
3. **Coordinate math**: normalize the absolute value `(raw - min) / (max - min)`, then map it to the current screen orientation using `Surface.rotation`, producing screen pixels:

   ```
   ROTATION_0   : (nx * W, ny * H)
   ROTATION_90  : (ny * W, (1 - nx) * H)
   ROTATION_180 : ((1 - nx) * W, (1 - ny) * H)
   ROTATION_270 : ((1 - ny) * W, nx * H)
   ```

4. **Hot-zone hit test**: when a finger goes down, its screen coordinate is tested against each key's real screen rectangle (window position + size). A hit injects that key; lifting releases it by finger identity. Fingers that land on empty space drive the full-screen mouse cursor.

### What you get

- **Press new keys while dragging the camera**: the first finger can be on the game, later fingers still trigger keys;
- several fingers can hold different keys at once without interference;
- keys no longer block touches — the game keeps its native touch input;
- mouse left/right/middle buttons and the wheel work in multi-touch mode; tap a mouse key for a single click. empty space acts as a left click.

### Multi-touch vs window mode

| | Multi-touch | Window mode |
| --- | --- | --- |
| Key windows | all non-touchable | separate windows + `FLAG_SPLIT_TOUCH` |
| First finger | can land anywhere | must land on a key window |
| Press keys while dragging camera | ✅ yes | ❌ later fingers are lost |
| Native touch | fully preserved | fully preserved |
| Joystick / trackpad gestures | ❌ not supported | ✅ supported |
| Requires | Shizuku + `getevent` listening | Shizuku injection |

The toolbar "◈ Multi-touch" button is on by default; tap it to switch to "Windows" mode (for devices that cannot listen to raw touch).

---

## Features

### Keys
- Each key is its own `TYPE_APPLICATION_OVERLAY` window, freely draggable, resizable (40–120dp, with one-tap presets 40/50/60/70/80/90/100/120), round or rounded-square.
- Two injection modes:
  - **Keyboard events**: inject `KeyEvent` (down/up) via Shizuku — cloud gaming, streaming and remote desktop, can be held down.
  - **Touch tap**: inject a finger at the key position — native mobile games, stays touched while held.
- **Lock key**: tap once to hold, tap again to release.
- **Macros**: steps separated by comma or space, `+` means pressed together, e.g. `Ctrl+Shift+K, A`.
- Custom keyCode: any Android `KeyEvent` keyCode (1–400).
- Toggle: when the toolbar master switch is off, checked keys are hidden entirely.
- All off / all on: hide every key on screen at once, tap again to restore.

### Virtual joystick
- **Key-value mode**: 8-direction output with a 22% center dead zone, default W/S/A/D (51/47/29/32), each direction remappable.
- **Touch mode**: injects a finger at an output point to push the game's own joystick; the output point can be dragged with a crosshair in edit mode.
- The joystick is not supported in multi-touch mode yet; use window mode.

### Mouse
- **Touchpad**: drag on the small panel to move the cursor; tap for left click, two-finger tap for right click, two-finger swipe for the wheel, long press to hold the left button.
- **Mouse buttons**: a press completes a single 50ms click immediately (left button), no need to wait for release; right/middle stay at 120ms.
- The cursor is its own window; mouse buttons are injected at the cursor position; sensitivity 50%–250%.

### Full keyboard
- One QWERTY keyboard window; keys are routed by pointerId, so simultaneous presses don't steal each other's keys.
- Drag the top handle, pinch to zoom, letter/symbol layers, Caps / Shift.

### Editing & configuration
- Toolbar: edit, add, macro, all-off, close, save / load, full keyboard, one-key, mouse, multi-touch.
- In edit mode, drag keys, tap to edit, long-press to delete.
- The key dialog uses grouped cards: key, display, behavior, joystick and injection method are separated; size has common presets.
- Layout / opacity / sensitivity / toggles persist via `SharedPreferences + JSON`; positions are stored as 0–1 ratios, so rotation and resolution changes never misplace them.
- Preset management: save / load named presets.
- **Layout import/export as files**: "Export to file / Import from file" on the main screen, using the system file picker (SAF, no storage permission). The exported `hjm-keypad-layout-*.json` carries the key layout, toolbar position, key opacity, mouse sensitivity, multi-touch / full-screen mouse / one-key / full keyboard / keyboard width toggles and all presets, so moving to another device or sharing with a friend is one complete package. Import shows a summary first, then reloads the overlay. Old plain key-array JSON files still import.
- Optional "block system IME": while keys are shown, a blank IME replaces the system keyboard to avoid occlusion or letter-to-CJK conversion.
- Main screen uses Material 3 with Android 12+ dynamic color; includes a dedicated help / FAQ page.

---

## How it works

### 1. Why Shizuku
A normal app has only three ways to inject keys: accessibility (high latency, detectable by games), IME (text only), or root. Shizuku starts a privileged process via `adb shell` or root and hands the app the `IInputManager` binder, so it can call directly:

```
IInputManager.injectInputEvent(event, MODE_WAIT_FOR_FINISH)
```

The injected events are system-level input, indistinguishable from real ones.

### 2. Why each key is a separate window
If all keys were drawn in one large window, that window would cover the whole screen, swallow injected taps, and the system would attribute all touch in that region to it. Separate small windows only hit-test the key area, use the window's real screen coordinates for injection, and `FLAG_SPLIT_TOUCH` lets multiple fingers land on different keys.

### 3. Why multi-touch listens to evdev
See the "Multi-touch" section above: touch streams have ownership, an injected fake finger can't occupy a slot, so the app listens to raw touchscreen events instead — read-only, the game still gets native touch, and those coordinates drive the key hot zones. The transform is `(raw - min) / (max - min)` followed by the screen-orientation rotation.

### 4. Why the virtual mouse only uses a small touchpad
The virtual mouse only places a small touchpad window to receive drags. A full-screen touch layer would allow dragging anywhere, but it blocks native touch so camera swipes stop working in games — that approach has been removed. Note that a game may switch to "mouse look" after receiving injected mouse movement; that is the game's own input-mode switch, not the overlay stealing touches.

### 5. Move out of the way before injecting
Injected events hit the topmost touchable window. If our overlay is in the way, the injection bounces back to us, so blocking windows are temporarily set to `FLAG_NOT_TOUCHABLE` and restored afterwards.

### 6. Coordinates and persistence
Screen size comes from `currentWindowMetrics` (real pixels for the current rotation); key positions are stored as ratios, so rotation and device changes follow automatically.

---

## Known limitations

- Multi-touch mode depends on Shizuku and `getevent`; some devices use different touch device nodes or coordinate systems and may not work.
- Multi-touch does not capture native touch, so key presses are also delivered to the game underneath.
- The virtual mouse only drags on the small touchpad (the full-screen drag approach was removed) to keep native touch intact.
- The joystick and trackpad gestures (two-finger tap, etc.) are not supported in multi-touch mode.
- Injected touches are a separate input stream; a few games may ignore them.
- No iOS / no-Shizuku mode.

---

## Install & usage

1. Download the latest `HJM-Keypad-*.apk` from [Releases](https://github.com/114514hhha233/shizuku-keypad/releases) and install it.
2. Install [Shizuku](https://shizuku.rikka.app/) and start its service via `adb shell` or root as described in its documentation.
3. Open the app and grant "Display over other apps" and notification permissions.
4. Tap "Authorize Shizuku"; the status should become connected.
5. Tap "Show virtual keys" to launch the overlay; go back to the home screen or a game — the toolbar and keys appear, with multi-touch already on.
6. Tap "Edit" on the toolbar to enter edit mode, tap "+" to add keys and drag them into place; exit edit mode to use.

---

## Build

Requires JDK 17 and Android SDK 34. The repo does not include a Gradle wrapper; use a local Gradle 8.x:

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
gradle :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

The release APK is debug-signed and installs as an upgrade over previous builds (same package name).

---

## Project structure

| File | Responsibility |
| --- | --- |
| `KeypadOverlay.kt` | Overlay core: key windows, touch dispatch, injection, joystick, trackpad, full keyboard, multi-touch hot zones, toolbar |
| `MouseEngine.kt` | Hover injection ticker: fixed-rate, async, always sends the latest cursor position so low-end devices stay smooth |
| `EvdevTouch.kt` | Shizuku listener for raw touchscreen events; outputs screen coordinates |
| `KeyModel.kt` | Key model, key catalog, macro parser, persistence |
| `JoystickPad.kt` | Joystick widget |
| `ShizukuInjector.kt` | Shizuku binder and key / touch / mouse injection |
| `OverlayService.kt` | Foreground service hosting the overlay |
| `MainActivity.kt` | Authorization, toggles, appearance, presets, import/export |
| `HelpActivity.kt` | Help and FAQ page |
| `AboutActivity.kt` | About page |
| `ImeGuard.kt` / `NoopImeService.kt` | Blank IME used to block the system keyboard |
| `res/layout/` | M3 main screen, help page, about page, key dialog |
| `res/values/themes.xml` | `Theme.Material3.DynamicColors.DayNight.NoActionBar` |

---

## Permissions

| Permission | Purpose |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | Draw floating keys and toolbar |
| `moe.shizuku.manager.permission.API_V23` | Call Shizuku to inject input and listen to touch |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the overlay alive |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `BIND_INPUT_METHOD` (service declaration) | Blank IME, only when "block system IME" is enabled |

The app requests no network permission and collects no data; configuration stays on the device.

---

## Changelog

- 1.7.2: **layout import/export moved to files** (SAF picker, a `.json` profile carrying keys + toolbar position + appearance/mouse settings + presets, still accepting the old plain key-array format, with a summary before import); **left-click timing** is now "warm up hover → hold 160ms → release", which fixes a real finger press only blinking the icon and needing a second press (human press-release is usually 120–200ms, the previous 80ms got treated as an accidental touch on some ROMs); three "stuck until restart" latches are gone:
  (1) before injecting we temporarily set the overlay windows under the pointer (including the touchpad) to not-touchable but restored them only after the inject returned — on old versions "wait for dispatch" can block for hundreds of ms or never return, leaving the touchpad permanently dead; restoration now runs on a hard 280ms alarm regardless of the inject;
  (2) once an evdev finger was unbound (200ms idle, or a two-finger gesture ending) the old code could only rebind on a fresh touch-down, so one pause pushed the rest of the drag onto the ~5/s window channel; it is now re-claimed by position immediately;
  (3) the `getevent` self-heal never actually worked (`stop()` did not clear the thread reference, so `start()` saw the old thread alive and returned); also, if three consecutive hover injections take longer than 40ms the pacer degrades to async instead of being dragged by the system.
- 1.7.1: **touchpad motion fully off the UI thread**. In 1.7 the whole pipeline (evdev callback → `ui.post` → gesture dispatch → pacer) lived on the UI thread, so any other app stealing CPU collapsed the injection cadence — exactly "it stalls sooner once you open another app". The evdev thread now computes the motion and feeds the injector directly; only the cursor redraw hops to the UI thread, coalesced to at most one per 8ms. The pad telemetry's fourth cell is inject cost plus send/reject counters.
- 1.7.2: **full-screen cursor dragging removed** (that full-screen touch layer blocked native touch, so camera swipes stopped working); the virtual mouse now only drags on the small touchpad. **Left-button click shortened to 50ms**: a press completes a full click instantly, no more 160ms long-press tail. Also fixed the full-screen injection that could leave the touch layer shrunk to 1×1 forever and kill all gestures; multi-touch (hot-zone) mode now defaults to off.
- 1.7.0: touchpad motion driven by **raw touchscreen samples listened through Shizuku**, picking the denser channel automatically (the window channel yields only a few events per second on 12/13, the real cause of the choppy cursor); re-claim by position after a two-finger gesture or a listen gap; two-finger right click deferred until all fingers are up; mouse buttons sent as down/up pairs (up always sent, false negatives accepted, right-click falling back to a long press); pressed-state visuals held for at least 180ms.
- 1.7: renamed to **HJM Keypad**, new cat-keyboard adaptive icon, multi-touch mode on by default (evdev listening + coordinate hot zones), Material 3 main screen with dynamic color, dedicated help / FAQ page, redesigned key dialog, full-screen mouse multi-finger drag and tap-to-left-click.
- 1.6: full keyboard, hot-zone experiment, i18n, key size presets.
- 1.5: preset keys, common-size picker, all-off switch.

---

## Disclaimer

This project is for learning and personal use only. Follow the terms of service of the games/platforms you use, do not use it in competitive online multiplayer games, and you are responsible for any consequences.
