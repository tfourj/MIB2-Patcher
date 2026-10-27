# Joker button skip in CarPlay: findings

Unit: `MU1425` / `MHI2_ER_AU57x_K3663_1` (Audi MHI2, 2nd gen MMI)
Source analysed: the unit's `lsd.jxe` (from an M.I.B. backup)

## TL;DR

The steering wheel joker ("star") button can be set to **skip forward** in the MMI, but it doesn't skip in Apple CarPlay or Android Auto.

- Steering wheel buttons reach the apps as shared *virtual buttons*. Each app connects its own button model, and every connected model gets the press.
- Terminal mode (CarPlay / Android Auto) connects to prev/next but **not to the joker** (virtual button 15).
- Media and the tuner do connect to the joker, but each only skips its own source.

**Fix:** a replacement `de.audi.app.terminalmode.AbstractTerminalModeHMIApplication` that also connects to the joker.
A small new listener presses terminal mode's own `TERMINAL_HK_NEXT_BUTTON` when the joker is set to skip forward.

All the analysis below was done with the tools in [`tools/jxe/`](../tools/jxe), documented in [TOOLS.md](TOOLS.md).

## Joker button setup

| What | Where | Value |
|---|---|---|
| Joker key from the key panel | `org.dsi.ifc.keypanel.Constants.KEY_MFW_JOKER1` / `KEY_MFW_JOKER2` | `58` / `59` |
| HMI key event | `de.audi.atip.hmi.event.KeyEvent.KEY_JOKER1` | `50` |
| Shared virtual button | `de.audi.atip.hmi.HMIApplication.VIRTUAL_BUTTON_JOKER1` | `15` |
| Joker function setting (system model) | `JokerKeyProcessor.joker1MeaningMdl` = system ChoiceModel | `267` |
| "Skip forward" function | `de.audi.atip.hmi.intercommunication.JokerKeyMeaning.SKIP_FORWARD` | `5` |

Other functions in `JokerKeyMeaning`: nav announcement, map colour, traffic announcement, switch tuner/media (4), drive select (6), navigate home (7), aux heating (8), POI call (9).
`de.audi.tghu.car.comfort.JokerKeyProcessor.keyPressed()` only executes 6, 7 and 8 itself. The apps that connect to the joker handle the rest.

## Who receives the joker

`de.audi.tghu.fwhmi.SharedHardkeysGUI` creates a `VirtualButtonMultiModelImpl` for virtual buttons 1, 2, 4, 6–22, including 15.
`HMIRegistry.connectServiceWithVirtualGUINEW` asks every `HMIApplication` for `getVirtualButton(id)` and **adds** each returned model to the multi-model button.
So a press goes to every app that returned a model, and adding another app doesn't take the button away from the others.

`getVirtualButton()` per app:

| Virtual button | Media (`MediaHMIApplication`) | Tuner (`TunerHMIApplication`) | Terminal mode (`AbstractTerminalModeHMIApplication`) |
|---|---|---|---|
| 1 (prev) | `MEDIA_HK_PREV_BUTTON` 20463 | 10081 | `TERMINAL_HK_PREV_BUTTON` 240003 |
| 2 (next) | `MEDIA_HK_NEXT_BUTTON` 20462 | 10080 | `TERMINAL_HK_NEXT_BUTTON` 240002 |
| **15 (joker)** | `HK_JOKER_BUTTON` 20209 | 10221 | **none** |
| 16 / 22 | | | 240020 / 240019 |

## Why skip doesn't reach CarPlay

`de.audi.tghu.media.app.HardKeyListener.keyTyped()` handles the joker (model 20209):

```java
int function = sysApp.getChoiceModelApp(267).getValue();
if (function == 5) {                      // SKIP_FORWARD
    ButtonListener l = getHKListener();   // null unless media has the main audio focus
    if (l == null) return;
    l.keyPressed(20462, a, b);            // press media's own "next"
    l.keyTyped(20462, a, b);
    l.keyReleased(20462, a, b);
}
```

`getHKListener()` returns null when a TMC readout is active, when audio is standby-muted, or when media doesn't have the main audio focus.
While CarPlay plays, media doesn't have the focus, so the press goes nowhere. The tuner's `JokerKeyHandler` behaves the same for radio.
Terminal mode never sees the joker at all.

## How terminal mode skips

`de.audi.app.terminalmode.HardKeyListener` is the listener on 240002/240003 (and 240026/240027), and passes them to `smartphone.HardKeyDSIListener`:

- `keyPressed(240002)`: if terminal mode has the audio focus, (re)starts the seek-forward (long press) timer.
- `keyReleased(240002)`: if the timer was still pending (a short press), `increaeSkipCount()` restarts a skip timer.
  When that fires, `IPlayerModificationListener.skip(...)` tells the phone to skip.

So a press followed straight away by a release of model 240002 is a normal "next track", and it only does anything while CarPlay or Android Auto has the audio focus.
`ButtonModel.keyPressed(int, int)` and `keyReleased(int, int)` are public and call the model's listener, so other code can press it.

## The patch

`src/de/audi/app/terminalmode/AbstractTerminalModeHMIApplication.java` is the original class rebuilt 1:1, plus:

- `getVirtualButton(15)` returns a new `ButtonModel` (id 240098, unused in the firmware) whose listener is `JokerBtnSkipListener`.
  Built once and cached. If anything fails it returns null, which is the stock behaviour.
- The constructor confirms the boot guard (see the README).

`src/de/audi/app/terminalmode/JokerBtnSkipListener.java` (new class):

- On `keyTyped`, reads system model 267. If it's `SKIP_FORWARD` (5), it presses and releases `TERMINAL_HK_NEXT_BUTTON` (240002).
- Everything is in a try/catch, so it can't break key handling.

Each case:

| Playing | Joker set to skip forward |
|---|---|
| CarPlay / Android Auto | Terminal mode skips (new) |
| Media (USB, BT, SD...) | Media skips as before; terminal mode's press does nothing (no audio focus) |
| Radio | Tuner handles it as before; terminal mode does nothing |

`AbstractTerminalModeHMIApplication` is the superclass of `TerminalModeHMIApplication` in `lsd.jxe`.
The rebuilt class keeps every field and method with the same descriptor and flags. `checkrefs.py` checks this, and every firmware call the two classes make, against the jxe.

MU1425's `lsd.sh` has no `$J9` line. It starts the VM with
`on -p 12 /ifs/jre/bin/j9 $VMOPTIONS -Xbootclasspath:$BOOTCLASSPATH -jxe:$LSD_DIR/lsd.jxe &`, and `jokerbtn.sh` adds its block before that line.

## Risks and things not verified

- **Not tested on the car.** Everything above comes from static analysis of this unit's `lsd.jxe`.
- Terminal mode connects to virtual buttons when it registers. The analysis says the joker view is a multi-model button, so media, tuner and car settings keep receiving it. This hasn't been seen on a running unit.
- Only "skip forward" exists as a joker function, so there's no skip back.
- The boot guard confirms from the terminal mode app's constructor, which runs later in startup than TEL2ACP's.
  Switching the car off very soon after it starts can make the guard disable the patch (re-arm with `install`).
- Only valid for this firmware (`K3663`).

# Button settings: joker, INFO and voice

The two steering wheel buttons above the joker are **INFO** (nav announcement) and **voice** (PTT).
JOKERBTN can give all three buttons another function, chosen at runtime from a settings file.

## Key path

| Button | DSI key (`org.dsi.ifc.keypanel.Constants`) | HMI key (`KeyEvent`) | Stock handling |
|---|---|---|---|
| Joker | `KEY_MFW_JOKER1` 58 | `KEY_JOKER1` 50 | `SharedHardkeysGUI` → virtual button 15 (see above) |
| INFO | `KEY_MFW_INFO` 52 | `KEY_INFO` 49 | `SharedHardkeysGUI` → virtual button 14 → nav announcement |
| Voice, press / release | `KEY_MFW_PTT_ON` 50 | `KEY_PTT` 18 | `SDSManagerGUI` → button 140001 |
| Voice, long press | 50 (key state 3) | `KEY_PTT_LONGPRESSED` 23 | `SDSManagerGUI` → button 140002 |
| Voice, double press | 50 (key state 2) | `KEY_PTT_DOUBLEPRESSED` 28 | `SDSManagerGUI` → button 140003 |

`de.audi.tghu.fwkbd.KbdListener.updateKey` gets every key from the key panel.
Presses and releases are looked up in `KbdListener.table`, which `KbdListener.<clinit>` sets to `KeyMap.tableDSI`: the **same public array**.
The lookup is a linear search on every press, so changing a row at runtime works from the next press on.
Long and double presses are only reported for DSI 50 (voice), and they skip the table.

`KeyEventDistributor.keyPressed` gives every key for which `KeyEvent.isHardkey(code)` is true to all four hard-key listeners
(`ApplicationManagerGUI`, `AudioAmplifierGUI`, `SharedHardkeysGUI`, `SDSManagerGUI`). All keys below are hard keys.

| Function | HMI key | Who handles it |
|---|---|---|
| Previous / next track | 13 / 14 | `SharedHardkeysGUI` → virtual buttons 1 / 2 (media, tuner, terminal mode). Same codes as the steering wheel arrows (DSI 46/47). |
| Mute | `INC_ROLLER_RIGHT` 21 | `AudioAmplifierGUI` → virtual button 3. Same code as pressing the right thumbwheel (DSI 44). |
| Radio, media, phone, nav | 1, 2, 4, 5 | `ApplicationManagerGUI` → hard-key models → `HardKeyManager` (TEL2ACP patches 4) |
| MENU | `KEY_RSE_MENU` 59 | `ApplicationManagerGUI`. The MENU key itself is DSI 78 → 59. |
| Nav announcement | `KEY_INFO` 49 | `SharedHardkeysGUI` → virtual button 14 |

## Voice button

`SDSHMIListener` in the SDS app handles the three voice buttons:

- 140001 released without a long press: `handleShortPTT` starts the Audi voice control.
- 140002 (long press): sets `pttLongpushActive` and `handleLongPTT` starts the phone's voice assistant
  (terminal mode `PTT_LONG_DETECTED` in CarPlay, otherwise `PhoneService.startExternMobileSpeechRecognition()`).
- 140001 released after a long press: tells terminal mode `PTT_RELEASED_AFTER_LONG_PRESS` and clears the flag.

The long press needs the PTT press before it and the release after it, so the voice button can't be remapped in the table like the other two.

## The patch

`src/de/audi/tghu/fwhmi/SDSManagerGUI.java` is the original class rebuilt 1:1 (package-private, final, same fields and methods), plus:

- The constructor starts `JokerBtnConfig` and creates a `JokerBtnVoiceKey`.
- `keyPressed(18/23/28)` and `keyReleased(18)` ask `JokerBtnVoiceKey` first.

`JokerBtnConfig` (new) reads `/mnt/persist/jokerbtn.conf` at startup and every 2 seconds in a low-priority daemon thread.
It sets the `tableDSI` rows for DSI 58 and 52 to the chosen HMI key, or back to 50 and 49 for stock.

`JokerBtnVoiceKey` (new), only while the voice button has a function:

| Sequence from the key panel | What happens |
|---|---|
| press, release | The press is held back. On release, a press and a release of the chosen key are posted. |
| press, long press, release | The held press goes to voice control, then the long press, then the release. Stock order, a little later. |
| press, release, double press, release | Chosen key on the first release and again on the double press. The last release is dropped. |

The chosen key is posted like `KbdListener.postEvent` does it, with the voice button's terminal and key source:
`HMIService.getEventDispatcher().postEvent(new KeyEvent(rootWindow, KEY_PRESSED, time, key, terminalId, keySource))`.

With the joker set to stock, it still goes to virtual button 15 and the CarPlay skip above works as before.
With any other function the joker produces that key instead, and the MMI's own joker setting is not used.

The boot guard is still confirmed by `AbstractTerminalModeHMIApplication`. `SDSManagerGUI` is created earlier, in `HMIRegistry.initVirtualGUIs`, so the guard covers both classes.

## Risks and things not verified

- **Not tested on the car.**
- The joker and INFO remaps write into a firmware array from a second thread. The writes are single `int` stores.
- A remapped voice button can't start the Audi voice control from the steering wheel. Long press still starts the phone's voice assistant.
- The long-press threshold is the key panel's. A slow short press may count as a long press.
- Holding a remapped joker or INFO holds the chosen key, like the real button: held "next" seeks forward in media.
- The settings screens (`screen <name> <parent>`, one screen per `.esd` file) haven't been seen on a unit.
