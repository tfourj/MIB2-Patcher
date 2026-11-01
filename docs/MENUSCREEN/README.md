# MENUSCREEN

Adds a tile to the **MENU** screen of an Audi MHI2 MMI that opens a screen with three buttons, packaged as an add-on for the M.I.B. (More Incredible Bash) SD card.

The MMI has no free slot for a new tile, so the patch reuses one that is hidden on European cars:

| Slot | Tile it reuses | Use it when |
|---|---|---|
| `online` (default) | Audi connect | always; the ring keeps its 8 tiles |
| `breakdown` | Breakdown call (China only) | not recommended: a 9th tile breaks the 3D ring layout on an A6 C7 |
| `off` | none | you want the stock MENU back without uninstalling |

| Button | Does |
|---|---|
| Open GEM | Opens the green engineering menu, like holding SETUP + CAR |
| Open REM | Opens the red engineering menu, like holding SETUP + BACK |
| Restart CarPlay | Switches the USB power of the media connector off for 3 seconds and back on, the same as unplugging the phone. For when CarPlay lags |

Turn the knob to choose a button and press it to start; the selection wraps around from the last button to the first. A line under the buttons says what happened.
**BACK** or **MENU** goes back to the main menu. The tile keeps the icon of the tile it reuses.

To see roughly how it will look before installing, open [preview/menuscreen-preview.html](preview/menuscreen-preview.html) in a browser. It also builds the settings file.

> [!WARNING]
> **Tested on one car only**, an A6 C7 with `MHI2_ER_AU57x_K3663_1`. The patch was built by reverse engineering that unit's firmware (see [FINDINGS.md](FINDINGS.md)).
> It replaces the class that draws the tiles of the MENU and CAR menus. If something is wrong, those menus may not open.
> GEM still opens and can uninstall it. A [boot guard](#safeguards) turns the patch off if the MMI restarts within a minute of starting. Have SSH ready. Use at your own risk.

## Requirements

- Audi MHI2 with firmware **`MHI2_ER_AU57x_K3663_1`** (MU1425), e.g. A6/A7 C7.
  The installer checks the firmware fingerprint and refuses to run on anything else.
- [MIB2-Patcher](../../README.md#install) on the unit (needs M.I.B. once).
- SSH access to the unit (recommended, see [Setting up SSH](../../README.md#setting-up-ssh)).

## What's in the `MENUSCREEN` folder

| File | Purpose |
|---|---|
| `MENUSCREEN.jar` | The patched `ButtonWidget3D` tile class and the new screen classes |
| `MENUSCREEN.jar.sha1` | Checksum the installer uses to spot a damaged copy |
| `menuscreen.sh` | Installer: `install`, `uninstall`, `restore`, `status`, `slot`, `gem-on`, `gem-off` |
| `esd/` | GEM screen: Status, Install, Uninstall, Restore and the slot choice |

## Install

1. Copy MIB2-Patcher to the unit (see the [main README](../../README.md#install)).
2. Run the installer, using **one** of these:

   - GEM → MIB2_Patcher → MENUSCREEN → Install
   - Over SSH:

     ```sh
     on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh MENUSCREEN install
     ```

   - Without SSH: add a line `MENUSCREEN install` after `update` in `PATCHER_RUN` in `mod/command.sh` (see the [main README](../../README.md#install)).

3. Reboot the MMI (GEM → M.I.B. → Reboot).
4. Open MENU. A tile called **Custom** should be there. Select it.

The installer writes everything it does to `mib2-patcher/logs/MENUSCREEN/menuscreen.log` on the SD card,
or to `/mnt/persist/mib2-patcher/MENUSCREEN/menuscreen.log` on the unit when no card is in.

## Settings

**Slot**: in the GEM screen, or over SSH:

```sh
on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh MENUSCREEN slot off
```

It works the next time MENU opens, no reboot needed.

**Texts**: edit `/mnt/persist/menuscreen.conf` over SSH to rename the tile or the screen title.
The tile text changes the next time MENU opens. The title changes the next time the screen is built, which is probably only after a reboot because the HMI caches built screens.

```text
slot=online
tile=Custom
title=MENUSCREEN
```

## Testing it

1. Install, reboot, open MENU. Check that the **Custom** tile is there and the other tiles look as before.
2. Select it. The screen should show the title and the three buttons, with **Restart CarPlay** selected.
3. Turn the knob. The `>` mark should move between the buttons and wrap around at the ends.
4. Press **Open GEM** and **Open REM**. Each should open its menu, the same as its key combination does on your car.
   If a menu doesn't open with its key combination either, it won't open from here.
5. With CarPlay running, press **Restart CarPlay**. CarPlay should go away and come back within about 20 seconds.
6. Press BACK, then MENU, then a hard key like NAV. Each should leave the screen.
7. Open the CAR menu and check it still works (it uses the same tile class).
8. Run **Status** in the GEM or over SSH. It shows the last lines of the patch log, `/mnt/persist/menuscreen.log`,
   and copies it to `MENUSCREEN/menuscreen-unit.log` next to the installer log.
   The patch writes a line when it loads its settings, when the tile is pressed, at each step of opening the screen, when a button runs, and when something fails.

Please send the logs from **Copy logs to SD card** and a photo of the screen, whatever happens.

## Uninstall

```sh
on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh MENUSCREEN uninstall
```

Or use **Uninstall** in the GEM screen, or add a line `MENUSCREEN uninstall` to `PATCHER_RUN` in `mod/command.sh`. Reboot afterwards.
`restore` puts back the original `lsd.sh` saved on first install, in case `uninstall` can't clean it.

## Safeguards

**Before changing anything**, the installer checks the firmware fingerprint, the jar checksum, the `lsd.sh` layout, that `/mnt/persist` is writable and that M.I.B. isn't busy.

**Boot guard.** The patched classes only load when MENU or CAR opens, so they can't report a good start themselves.
Every time the MMI screen software starts, the `lsd.sh` block leaves a flag in `/mnt/persist` and removes it after a minute.
If the flag is still there at the next start, the screen software or the unit restarted within that minute, so the block starts the stock software without the patch.
`status` then shows `DISABLED the patch after a failed start`. Running `install` again re-arms it.
Turning the car off within a minute of starting also counts as a failed start.

**When the tile is pressed**, the patch's code catches its own errors. The press then does nothing and the error goes to the patch log.
It never falls back to the reused tile's own action, so the Breakdown call slot can't start a breakdown call.

**If MENU or CAR stop working**, open the GEM (it doesn't need those menus) and run **Uninstall**, or uninstall over SSH.

## Using it together with TEL2ACP and JOKERBTN

All three can be installed together, in any order. Each adds its own marked block to `lsd.sh`, and the jars share one `-Xbootclasspath/p:` list. They replace different classes.
Use `uninstall` rather than `restore` when more than one is installed.

## Build from source

Needs a JDK 8, because newer JDKs can't produce the old class format the MMI's J9 VM uses.

```bash
JAVA_HOME="/path/to/jdk8" ./build.sh MHI2_ER_AU57x_K3663_1 MENUSCREEN
```

This rebuilds `mib2-patcher/patches/MHI2_ER_AU57x_K3663_1/MENUSCREEN/MENUSCREEN.jar` from `source/MHI2_ER_AU57x_K3663_1/MENUSCREEN/src/`. The classes in `stubs/` next to it only stand in for firmware types at compile time and are not included in the jar.
Set `JXE` to your `lsd.jxe` to also check the jar against the firmware (see [TOOLS.md](../TOOLS.md#checkrefspy)).

## How it works

`ButtonWidget3D` is the firmware's class for the 3D menu tiles. The patch replaces it with a copy that behaves the same, plus a hook for one tile.
That tile stays visible, shows its own text and, when pressed, opens the screen instead of firing its usual state machine event.

The screen comes from a new HMI bundle, registered at runtime through the firmware's own OSGi registry, the same way the firmware's apps register theirs.
It takes a free bundle ID from 26 to 31 (screen 260000 to 310000), because the registry only looks bundles up in an array sized at startup for IDs up to 31.
It is built like the firmware's simple message screen, with the system fonts, colours and border.
It is shown with the same call the state machine uses. The state machine stays in the MENU state while it shows.

The screen handles the knob itself. BACK and MENU need help: the MENU state has no BACK transition, and its MENU transition goes back into the same state, which the state machine doesn't redraw.
So BACK on the screen, and a filter in the HMI event queue for MENU (as the firmware's own filters do), ask the state machine to show its current screen again, the way the firmware does when a popup closes. That brings the main menu back.

The buttons do what the firmware already does:

- **Open GEM** starts the `AppDevelopment` bundle and sends the `KEY_SET_CAR` key, as the firmware's `ComboKeyDetector` does for SETUP + CAR.
- **Open REM** sends the `KEY_SET_BACK` key, as it does for SETUP + BACK.
- **Restart CarPlay** runs the firmware's `/etc/scripts/disable_usb_pwr_0.sh`, waits 3 seconds and runs `enable_usb_pwr_0.sh`, on a background thread.
  `smartphone_integrator` and the media process use the same scripts for USB power resets. Presses within 15 seconds of a restart are ignored.

Full analysis: [FINDINGS.md](FINDINGS.md).

## Credits

- M.I.B. by MIBonk and MIB-Wiki, for the SD card toolkit and the `-Xbootclasspath/p:` approach.

> [!NOTE]
> AI was used to explore the firmware, find the MENU and screen handling, and write the patch, installer and analysis tools.
> Treat the results like any other community patch that has only been tested on one car.
