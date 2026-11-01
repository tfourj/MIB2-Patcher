# TOUCHCURSOR

Turns the **MMI touchpad** of an Audi MHI2 into a **cursor for Apple CarPlay**, packaged as an add-on for the M.I.B. (More Incredible Bash) SD card.

| You do | Before | After |
|---|---|---|
| Drag a finger on the touchpad in CarPlay | The focus jumps from item to item, like turning the knob | A cursor (small white square) follows the finger |
| Tap the touchpad | Nothing | CarPlay gets a touch where the cursor is, like tapping a touchscreen |
| Leave the touchpad alone for 5 seconds | | The cursor hides. The next touch shows it again where it was |
| Turn or press the knob | Moves the focus, selects | The same, unchanged |

Outside CarPlay the touchpad works as stock.

> [!WARNING]
> **This build is not tested yet.** The same code ran in a diagnostic jar on one car, an A6 C7 with `MHI2_ER_AU57x_K3663_1` (see [FINDINGS.md](FINDINGS.md)).
> If something is wrong, the MMI screen software may not start (black screen, no GEM).
> A [boot guard](#safeguards) turns the patch off by itself after one failed start. SSH is still the best way to undo things. Use at your own risk.

## Requirements

- Audi MHI2 with firmware **`MHI2_ER_AU57x_K3663_1`** (MU1425), e.g. A6/A7 C7, with the MMI touch pad.
  The installer checks the firmware fingerprint and refuses to run on anything else.
- [MIB2-Patcher](../../README.md#install) on the unit (needs M.I.B. once).
- Only tested with CarPlay. Android Auto isn't covered.

## What's in the `TOUCHCURSOR` folder

| File | Purpose |
|---|---|
| `TOUCHCURSOR.jar` | The patched `ATIPEvent` and `DSICarplaySafeProxy` classes and the cursor code |
| `TOUCHCURSOR.jar.sha1` | Checksum the installer uses to spot a damaged copy |
| `touchcursor.sh` | Installer: `install`, `uninstall`, `restore`, `status`, `gem-on`, `gem-off` |
| `esd/` | GEM screen with Status, Install, Uninstall and Restore buttons |

## Install

1. Copy MIB2-Patcher to the unit (see the [main README](../../README.md#install)).
2. Run the installer, using **one** of these:

   - GEM → MIB2_Patcher → TOUCHCURSOR → Install
   - Over SSH:

     ```sh
     /mnt/app/eso/mib2-patcher/patcher.sh TOUCHCURSOR install
     ```

3. Reboot the MMI.

Install also removes the TOUCHDIAG diagnostic from [FINDINGS.md](FINDINGS.md) if it is on the unit, because it replaces the same classes.

## Uninstall

- GEM → MIB2_Patcher → TOUCHCURSOR → Uninstall, or `patcher.sh TOUCHCURSOR uninstall` over SSH, then reboot the MMI.
- **Restore original lsd.sh** puts back the `lsd.sh` backup from the first install, for when the block can't be removed cleanly.

## Safeguards

- The installer checks the firmware fingerprint and the jar's checksum before it changes anything, and backs up `lsd.sh` on first install.
- **Boot guard.** Like NAVIGNORE, the `lsd.sh` block leaves a `touchcursor_pending` flag in `/mnt/persist` at every HMI start and removes it after a minute.
  If the flag is still there at the next start, the HMI restarted within that minute, so the block writes `touchcursor_disabled` and starts the HMI without the jar.
  **Install** re-arms it. **Status** shows the guard's state.
- If drawing the cursor fails, the patch stops drawing it until the next HMI start, and taps still work. Errors go to `/mnt/persist/touchcursor.log`.

## What the installer changes

| Where | What |
|---|---|
| `/mnt/app/eso/hmi/lsd/jars/TOUCHCURSOR.jar` | The jar |
| `/mnt/app/eso/hmi/lsd/lsd.sh` | A block marked `#TOUCHCURSOR` in front of the J9 start line that adds the jar to `-Xbootclasspath/p:` |
| `/mnt/app/eso/hmi/lsd/lsd.sh.touchcursor.bak` | The `lsd.sh` backup |
| `/mnt/persist/touchcursor_*` | Boot guard flags |
| `/mnt/app/eso/hmi/engdefs/z_0mib2patcher_TOUCHCURSOR.esd` | The GEM screen link |

## Build from source

```bash
./build.sh MHI2_ER_AU57x_K3663_1 TOUCHCURSOR
```

Needs a JDK 8 (see the [main README](../../README.md#build-from-source)). The source is in `source/MHI2_ER_AU57x_K3663_1/TOUCHCURSOR/src`, the compile-only firmware stubs in `stubs`.

## How it works

Stock, CarPlay is told the car has a knob and a touchpad, but the HMI never sends the iPhone the touchpad positions.
On the CarPlay screen the keypanel turns swipes into knob ticks and edge presses into left, right, up and down.

- `DSICarplaySafeProxy.startService` adds `INPUTFEATURE_HIGHFIDELITYTOUCH`, so `dio_manager` also registers a touchscreen with the iPhone.
- `ATIPEvent.dispatch()`, which every HMI event goes through, hands touchpad and key events to `TouchCursor` first.
- On the first touchpad press on the CarPlay screen, the keypanel is switched to the map's panning mode (6), which reports finger moves.
- The finger moves move the cursor, which the HMI draws in front of the CarPlay video. A tap is sent to the iPhone as a touchscreen press and release at the cursor.
- The touchpad's own keys and the joystick event of an edge press are swallowed, so the CarPlay focus doesn't move as well.

More in [FINDINGS.md](FINDINGS.md).
