# MIB2-A6C7-MENUSCREEN

**Prototype.** Adds a tile to the **MENU** screen of an Audi MHI2 MMI that opens a screen with three buttons, packaged as an add-on for the M.I.B. (More Incredible Bash) SD card.

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

To see roughly how it will look before installing, open [docs/preview/menuscreen-preview.html](docs/preview/menuscreen-preview.html) in a browser. It also builds the settings file.

> [!WARNING]
> **Not tested on a car yet.** The patch was built by reverse engineering one unit's firmware (see [docs/FINDINGS.md](docs/FINDINGS.md)).
> It replaces the class that draws the tiles of the MENU and CAR menus. If something is wrong, those menus may not open.
> GEM still opens and can uninstall it. A [boot guard](#safeguards) turns the patch off if the MMI restarts within a minute of starting. Have SSH ready. Use at your own risk.

## Requirements

- Audi MHI2 with firmware **`MHI2_ER_AU57x_K3663_1`** (MU1425), e.g. A6/A7 C7.
  The installer checks the firmware fingerprint and refuses to run on anything else.
- [M.I.B.](https://wiki.mibsolution.one/en/MHI2) on an SD card in the **SD1 slot** (`sda0`), with GEM enabled.
- SSH access to the unit (recommended, see the TEL2ACP or JOKERBTN README).

## What's in the `MENUSCREEN` folder

| File | Purpose |
|---|---|
| `MENUSCREEN.jar` | The patched `ButtonWidget3D` tile class and the new screen classes |
| `MENUSCREEN.jar.sha1` | Checksum the installer uses to spot a damaged copy |
| `menuscreen.sh` | Installer: `install`, `uninstall`, `restore`, `status`, `slot`, `gem-on`, `gem-off` |
| `command.sh` | Hook that runs every patch in `custom-patches/` from M.I.B. (no SSH needed) |
| `esd/` | GEM screen: Status, Install, Uninstall, Restore and the slot choice |

## Install

1. Copy the `MENUSCREEN` folder into a `custom-patches` folder in the root of the M.I.B. SD card (`custom-patches/MENUSCREEN`).
2. Put the card in SD1 and run the installer, using **one** of these:

   **Over SSH**

   ```sh
   on -f rcc /net/mmx/fs/sda0/custom-patches/MENUSCREEN/menuscreen.sh install
   ```

   **Without SSH**: copy `MENUSCREEN/command.sh` to `mod/command.sh` on the card and run it, the same way as for TEL2ACP and JOKERBTN.

3. Reboot the MMI (GEM → M.I.B. → Reboot).
4. Open MENU. A tile called **Custom** should be there. Select it.

The installer writes everything it does to `custom-patches/MENUSCREEN/menuscreen.log` on the SD card.

## Settings

**Slot**: in the GEM screen, or over SSH:

```sh
on -f rcc /net/mmx/fs/sda0/custom-patches/MENUSCREEN/menuscreen.sh slot off
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
   and copies it to `custom-patches/MENUSCREEN/menuscreen-unit.log` on the SD card.
   The patch writes a line when it loads its settings, when the tile is pressed, at each step of opening the screen, when a button runs, and when something fails.

Please send `custom-patches/MENUSCREEN/menuscreen.log` and a photo of the screen, whatever happens.

## Uninstall

```sh
on -f rcc /net/mmx/fs/sda0/custom-patches/MENUSCREEN/menuscreen.sh uninstall
```

Or use **Uninstall** in the GEM screen, or set `CUSTOM_PATCHES="MENUSCREEN uninstall"` in `mod/command.sh`. Reboot afterwards.
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
JAVA_HOME="/path/to/jdk8" ./build.sh
```

Set `JXE` to your `lsd.jxe` to also check the jar against the firmware (see [docs/TOOLS.md](docs/TOOLS.md#checkrefspy)).

## How it works

`ButtonWidget3D` is the firmware's class for the 3D menu tiles. The patch replaces it with a copy that behaves the same, plus a hook for one tile.
That tile stays visible, shows its own text and, when pressed, opens the screen instead of firing its usual state machine event.

The screen comes from a new HMI bundle, registered at runtime through the firmware's own OSGi registry, the same way the firmware's apps register theirs.
It takes a free bundle ID from 26 to 31 (screen 260000 to 310000), because the registry only looks bundles up in an array sized at startup for IDs up to 31.
It is built like the firmware's simple message screen, with the system fonts, colours and border.
It is shown with the same call the state machine uses. The state machine stays in the MENU state, which is why MENU brings the main menu back.

The screen handles the knob itself. BACK never reaches a screen: the firmware's `ApplicationManagerGUI` turns it into the state machine event `HK_RETURN`, which the MENU state ignores.
So the patch adds a filter to the HMI event queue, as the firmware's own filters do, that swaps `HK_RETURN` for `HK_MENU` while its screen is showing.

The buttons do what the firmware already does:

- **Open GEM** starts the `AppDevelopment` bundle and sends the `KEY_SET_CAR` key, as the firmware's `ComboKeyDetector` does for SETUP + CAR.
- **Open REM** sends the `KEY_SET_BACK` key, as it does for SETUP + BACK.
- **Restart CarPlay** runs the firmware's `/etc/scripts/disable_usb_pwr_0.sh`, waits 3 seconds and runs `enable_usb_pwr_0.sh`, on a background thread.
  `smartphone_integrator` and the media process use the same scripts for USB power resets. Presses within 15 seconds of a restart are ignored.

Full analysis: [docs/FINDINGS.md](docs/FINDINGS.md).

## Credits

- M.I.B. by MIBonk and MIB-Wiki, for the SD card toolkit and the `-Xbootclasspath/p:` approach.

> [!NOTE]
> AI was used to explore the firmware, find the MENU and screen handling, and write the patch, installer and analysis tools.
> Treat the results like any other untested community patch.
