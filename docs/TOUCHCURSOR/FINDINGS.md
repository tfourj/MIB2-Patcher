# Touchpad cursor in CarPlay: findings

Unit: `MU1425` / `MHI2_ER_AU57x_K3663_1` (Audi A6 C7, MHI2, MMI touch)
Sources analysed: `/ifs/lsd.jxe` from the unit (sha1 `2be4758b`), `dio_manager`, `libairplay.so` and `/etc/eso/production/dio_manager.json`

## Status

**Experimental, not one of the patches.** A diagnostic jar (TOUCHDIAG) in [`source/MHI2_ER_AU57x_K3663_1/TOUCHCURSOR/diag/`](../../source/MHI2_ER_AU57x_K3663_1/TOUCHCURSOR/diag) logs what the touchpad sends and can send it to CarPlay as touches.
It isn't installed by MIB2-Patcher. See [Diagnostic](#diagnostic) for how to run it.

## TL;DR

**Possible with Java only. Two of the three parts work in the car, the cursor drawing is untested.** The iPhone is told the car has a knob and a touchpad, but the HMI never sends it the touchpad positions.
The CarPlay stack on the unit can also register a touchscreen, and the HMI can send each tap as a touch (Test 3). In keypanel mode 6 the touchpad reports finger moves (Test 2).
CarPlay has no pointer of its own, so the car must draw the cursor.

- `startService` sends `inputFeatures` 9 (knob 1 + touchpad 8). The touchscreen bits (2 low, 4 high fidelity) are off because `TerminalModeConfigurationImpl.hasTouchscreen()` is false.
- So the HMI uses `TMVirtualButtonListener`, which drops touchpad positions: `touchPadPositionMoved` only logs and `touchPadPressed` is empty.
- On the CarPlay screen, `de/audi/tghu/hmi/view/VirtualButton` turns a touchpad tap into a select key. The focus jumps on a swipe come from keys, not touch events (key source `KBD_TOUCHPAD` 9, `KEY_TP_ENCODER_X/Y` 64/65 in `KeyEvent`).
- `dio_manager` creates `Eso_Knob`, `Eso_TouchScreen` and `Eso_TrackPad` HID devices from the `inputFeatures` bits and has `sendTouchScreenHIDReport`, so no native or config change is needed.

## How touchpad input reaches the HMI

`fwkbd/KbdListener.updateGesture` gets the touchpad from the keypanel DSI and posts `atip/hmi/event/TouchEvent`s with x and y:

| ID | Event |
|---|---|
| 10904 / 10905 | `TOUCH_PAD_PRESSED` / `RELEASED` |
| 10906 / 10907 | `TOUCH_PAD_TAPPED` / `DOUBLETAPPED` |
| 10908 | `TOUCH_PAD_CHARS_RECOGNIZED` (handwriting) |
| 10911 / 10912 | `TOUCH_PAD_MOVED_ABS` / `MOVED_REL` |
| 10913 | `TOUCH_PAD_SCROLL` |

`HMIEventDispatcher` runs `ATIPEvent.dispatch()` on the HMI thread. `fwhmi/AbstractWindow.processEvent` passes touch events to `fwhmi/KeyEventDistributor`, which sends them to the current screen.
The touchpad resolution is 256 or 1024, depending on the keyboard type (`AbstractTerminalModeConfiguration.getSquareTouchPadResolution`).

## How CarPlay input reaches the iPhone

`org/dsi/ifc/carplay/DSICarplay` has `postButtonEvent`, `postRotaryEvent`, `postCharacterEvent` and `postTouchEvent(source, fingers, TouchEvent[] {x, y})`, with `TOUCHSOURCE_TOUCHPAD` 0 and `TOUCHSOURCE_TOUCHSCREEN` 1.
`CarplayDSILifecycleController$CarplayDSIController.startService` builds the `ServiceConfiguration`:

| Field | MU1425 value |
|---|---|
| `inputFeatures` | 9 (`hasKnob` and `hasTouchpad` true) |
| `screenResolution` | 3 (custom), window 780 x 468 |
| Offset | 20, 5 |
| Touchpad resolution | 256 or 1024 |

`TerminalModeApplication$1.configure` binds `TMTouchscreenVBListener` only when `isTouchScreenInputWidget()` (= `hasTouchscreen()`) is true. That class sends touchpad positions as `TOUCHSOURCE_TOUCHPAD` and touchscreen positions minus the offset as `TOUCHSOURCE_TOUCHSCREEN`.
MU1425 gets `TMVirtualButtonListener` instead, and nothing in the firmware calls `VirtualButtonModel.touchPadPositionMoved`.

`dio_manager` logs "Supported HID Devices: knob | highFidelityTouchScreen | lowFidelityTouchScreen | touchPad" and drops touches while CarPlay isn't on screen.
`dio_manager.json` only sets the HID vendor and product (0x1A96 / 0x2018) and the consumer keys.
VW's MIB2 High touchscreen units use the same stack, so the touchscreen path is production code.

## Proposed patch

1. Replace `dsi/carplay/DSICarplaySafeProxy`: add `INPUTFEATURE_HIGHFIDELITYTOUCH` (4) in `startService`, and send taps as `TOUCHSOURCE_TOUCHSCREEN` press and release at the cursor.
2. Draw a cursor over CarPlay and move it with the touchpad. Widgets draw bitmaps with `fi/hybrid/hwg` (`Bitmap.setPosition`, `HWGManager.addDrawable`, see `RotateImageWidget.render`). CarPlay context 76 is [16 HMI, 59 CarPlay], and HMI popups already show over CarPlay.
3. Keep the knob as it is.

Open questions, which the diagnostic answers:

1. Which touchpad events reach the HMI while CarPlay is on screen, and does a swipe also send keys? See [Test 1](#test-1-what-the-touchpad-sends-in-carplay).
2. Does the iPhone accept touches once `inputFeatures` is 13? Very likely, see [Test 3](#test-3-touches-to-the-iphone).
3. Does an HMI bitmap show above the CarPlay video? (Not covered by the diagnostic yet.)
4. Does another keypanel mode (map panning, 6) send finger moves on the CarPlay screen? Yes, see [Test 2](#test-2-map-panning-mode-on-the-carplay-screen).

## Test 1: what the touchpad sends in CarPlay

Logged with TOUCHDIAG 0.1.0 on 2026-11-01, CarPlay on screen (screen ID `240000`):

- `startService`: `inputFeatures=9`, window 780 x 468, offset 20, 5, touchpad 256 x 256, physical 174 x 105 mm.
- Finger down: `TOUCH_PAD_PRESSED` with the position (x and y 0 to 255). Release and tap come with x and y 0.
- No `TOUCH_PAD_MOVED_ABS` or `MOVED_REL` at all, so the HMI never sees the finger move.
- Moves come as wheel keys from the touchpad (key source 9): `KEY_TP_ENCODER_X` 64 and `KEY_TP_ENCODER_Y` 65, one tick per step. The terminal mode app sends each tick to the iPhone as `postRotaryEvent(±1)`.
- Touching near an edge also sends a joystick event (`KEY_JOYSTICK` 22 with a direction, zone keys 69 to 76). The app turns it into CarPlay buttons 5 to 8 (left, right, up, down).
- The knob turns are wheel key 17 (source 1) and also become `postRotaryEvent`. A knob press is button 1.

The keypanel runs in a mode the terminal mode app sets itself: `TMDSIKeyPanelControllerImpl.setTextInputActive` calls `DSIKeyPanel.setRecognizerMode` with 25, or 26 while CarPlay text input is on.
`KbdHandler` doesn't know those modes. Map panning (`RECOGNIZERMODE_MAP_PANNING` 6) is set by the map screen with `ScreenWidget.setDesiredRecognizerMode(6, null)`, which ends in `KbdService.setRecognizerMode(6, null)` with the mode's default settings.

So a cursor needs either another keypanel mode with finger moves, or the encoder ticks (coarse, a few per swipe). The press position is enough for an absolute tap without a cursor.

## Test 2: map panning mode on the CarPlay screen

Logged with TOUCHDIAG 0.2.0 and `/ramdisk/touchdiag.mode` = 6. `KbdService.setRecognizerMode(6, null)` returned true on the first press (`KbdHandler` thought it was in mode 17).

- Drags now send `TOUCH_PAD_MOVED_REL` (10912) about every 50 ms. x and y are signed 8-bit steps (255 is -1), seen from -27 to +35 per event. That is a mouse-like delta, enough to move a cursor smoothly.
- The touchpad encoder keys (64, 65) are gone, so the pad no longer turns into knob ticks.
- Presses still carry the position. Pressing near an edge still sends a joystick event with key source 0 (not 9), which the app turns into CarPlay buttons 5 to 8, and the zone keys 69 to 76 (source 9) on release.
- Three of the five drags sent no moves. Each of them started near an edge, and the diagnostic re-applied the mode on each press, so it is unclear whether the edge zones or the re-apply cut them off.

So in mode 6 a cursor can follow the finger. A patch must also drop the joystick event that comes with a touchpad press (a JoystickEvent right after `TOUCH_PAD_PRESSED`), so the focus doesn't jump.

## Test 3: touches to the iPhone

Logged with TOUCHDIAG 0.2.0, `/ramdisk/touchdiag.inject` and mode 6, after restarting only CarPlay (an MMI reboot clears `/ramdisk`).

- `startService` sent `inputFeatures=13`, and `dio_manager` registered `Eso_TouchScreen` next to `Eso_Knob` and `Eso_TrackPad` (`sloginfo`).
- Each pad press went out as a touchscreen press and release at the press position mapped onto 780 x 468. There were no "Touch event ... not forwarded" warnings in `sloginfo`.
- Apps opened where the pad was pressed (seen by the user, not yet checked icon by icon). With the flag set, the HMI got no touchpad events, and a stock tap never sends `button 1`, so the touches are what opened them.
- CarPlay kept working with the touchscreen added. Presses near the left edge still moved the focus too (joystick, button 5).

## Diagnostic

TOUCHDIAG replaces two firmware classes, with all their original members:

| Class | Change |
|---|---|
| `atip/hmi/event/ATIPEvent` | `dispatch()` first calls `TouchDiag`, which logs every touch and key event with the current screen ID and can swallow it |
| `app/terminalmode/dsi/carplay/DSICarplaySafeProxy` | Logs `startService` and the button, rotary and touch calls to the iPhone. Adds the touchscreen bit and sends `TouchDiag`'s touches |

The HMI writes `/ramdisk/touchdiag.log` (up to 1 MB). Lines start with the time in ms:

| Line | Meaning |
|---|---|
| `T <id> code= x= y= f= scr=` | Touchpad event |
| `K <id> key= src= [dir= clicks=] scr=` | Key event (10401 press, 10402 release, 10403 wheel, 10404 joystick) |
| `C ...` | Call to the CarPlay DSI service |
| `I fingers= x= y=` | Touch sent by the diagnostic |
| `M mode= set= before=` | Keypanel mode switched by the diagnostic |

While `/ramdisk/touchdiag.mode` holds a `KbdService` recognizer mode (e.g. `6`), a touchpad press on the CarPlay screen switches the keypanel to it, again at most every 2 seconds, since the terminal mode app may set its own mode back.

While `/ramdisk/touchdiag.inject` exists, the touchpad is not passed to the HMI.
Its position is mapped onto the 780 x 468 CarPlay window and sent as a touch: press and move follow the finger, release lifts it where it last was, and a tap with a position but no press becomes a press and a release.
Keys from the touchpad are swallowed too. Handwriting still goes to the HMI.
The touchscreen bit is only added when CarPlay starts, so restart CarPlay after creating the flag.

Build (JDK 8, also runs `checkrefs.py` when `build/fw/lsd.jxe` exists):

```bash
JAVA_HOME=/path/to/jdk8 source/MHI2_ER_AU57x_K3663_1/TOUCHCURSOR/diag/build.sh
```

Install over SSH: copy `build/TOUCHDIAG.jar`, `build/TOUCHDIAG.jar.sha1` and `touchdiag.sh` to `/mnt/persist/touchdiag/` on the unit, then:

```sh
sh /mnt/persist/touchdiag/touchdiag.sh install
```

Reboot the MMI. The lsd.sh block has the same boot guard as the patches.

Test:
1. With CarPlay on screen, tap, swipe and drag on the touchpad, and turn the knob. Copy `/ramdisk/touchdiag.log`.
2. `echo 6 > /ramdisk/touchdiag.mode`, touch the pad once on the CarPlay screen, then drag slowly. The log shows whether `MOVED` events (10911, 10912) come now.
3. `touch /ramdisk/touchdiag.inject`, restart CarPlay (MENUSCREEN or reconnect the phone), check that the log shows `inputFeatures=13`, then tap and drag on the touchpad.
4. `rm /ramdisk/touchdiag.inject /ramdisk/touchdiag.mode` to get the stock touchpad back. A reboot clears them too.

Remove it with `sh /mnt/persist/touchdiag/touchdiag.sh uninstall`, reboot the MMI, then delete `/mnt/persist/touchdiag`.
MIB2-Patcher's Uninstall everything doesn't remove it.
