# MIB2-A6C7-TEL2ACP

Makes the **TEL** button on an Audi MHI2 MMI open **Apple CarPlay** (or Android Auto), packaged as an add-on for the M.I.B. (More Incredible Bash) SD card.

| You press | Before | After |
|---|---|---|
| TEL | Phone menu | CarPlay, with the TEL key lit |
| TEL while CarPlay is on screen | Phone menu | Depends on the [TEL mode](#tel-modes), phone menu by default |

All other buttons work as before. The TEL light only follows CarPlay when TEL opened it, not when CarPlay is opened from MENU or by the phone.

> [!WARNING]
> **Tested on one car only**, an A6 C7 with `MHI2_ER_AU57x_K3663_1`. The patch was built by reverse engineering that unit's firmware (see [docs/FINDINGS.md](docs/FINDINGS.md)).
> If something is wrong, the MMI screen software may not start (black screen, no GEM).
> A [boot guard](#safeguards) turns the patch off by itself after one failed start. SSH is still the best way to undo things. Use at your own risk.

## Requirements

- Audi MHI2 with firmware **`MHI2_ER_AU57x_K3663_1`** (MU1425), e.g. A6/A7 C7.
  The installer checks the firmware fingerprint and refuses to run on anything else.
- [M.I.B.](https://wiki.mibsolution.one/en/MHI2) on an SD card in the **SD1 slot** (`sda0`), with GEM enabled.
- SSH access to the unit (recommended, see [Setting up SSH](#setting-up-ssh)).

## What's in the `TEL2ACP` folder

| File | Purpose |
|---|---|
| `TEL2ACP.jar` | The patched `HardKeyManager` class |
| `TEL2ACP.jar.sha1` | Checksum the installer uses to spot a damaged copy |
| `tel2acp.sh` | Installer: `install`, `uninstall`, `restore`, `status`, `gem-on`, `gem-off`, `mode-*`, `custom-*` |
| `command.sh` | Hook that runs every patch in `custom-patches/` from M.I.B. (no SSH needed) |
| `esd/` | Optional GEM screen with Status, TEL mode, Install, Uninstall and Restore buttons, and a Set CarPlay Custom submenu |

## Install

1. Copy the `TEL2ACP` folder into a `custom-patches` folder in the root of the M.I.B. SD card (`custom-patches/TEL2ACP`).
2. Put the card in SD1 and run the installer, using **one** of these:

   **Over SSH**

   ```sh
   on -f rcc /net/mmx/fs/sda0/custom-patches/TEL2ACP/tel2acp.sh install
   ```

   **Without SSH**: copy `TEL2ACP/command.sh` to `mod/command.sh` on the card.
   Then run it with GEM → M.I.B. → individual script, or run the M.I.B. installation again
   (software update from the SD card), which runs `mod/command.sh` at the end. Delete `mod/command.sh` afterwards.
   It runs `install gem-on` for every patch in `custom-patches/`. To choose patches and actions, fill in `CUSTOM_PATCHES` at the top of the file.

3. Reboot the MMI (GEM → M.I.B. → Reboot).
4. Press TEL. CarPlay should open.

The installer writes everything it does to `custom-patches/TEL2ACP/tel2acp.log` on the SD card.

### Optional GEM screen

```sh
on -f rcc /net/mmx/fs/sda0/custom-patches/TEL2ACP/tel2acp.sh gem-on
```

After a reboot, GEM has a **TEL2ACP** screen with Status, the three TEL modes, Install, Uninstall and Restore,
and a **Set_CarPlay_Custom** submenu (see [TEL modes](#tel-modes)).
It only works while the SD card is in SD1. `command.sh` turns it on by default.

## TEL modes

TEL always opens CarPlay. The mode sets what TEL does while CarPlay is already on screen:

| Mode | GEM button | Command | TEL while CarPlay is on screen |
|---|---|---|---|
| TEL / CarPlay (default) | Mode: TEL / CarPlay | `mode-tel` | Phone menu |
| CarPlay only | Mode: CarPlay only | `mode-carplay` | Nothing |
| CarPlay / CarPlay Custom | Mode: CarPlay / CarPlay Custom | `mode-custom` | The CarPlay Custom screen on the iPhone |

CarPlay Custom is set in GEM → TEL2ACP → Set_CarPlay_Custom:

| GEM button | Command | Opens in CarPlay |
|---|---|---|
| Phone (default) | `custom-phone` | Phone app |
| Home | `custom-home` | Home screen |
| Maps | `custom-maps` | Maps app |
| Music | `custom-music` | Music app |
| Now Playing | `custom-nowplaying` | Now Playing screen |

Switch with the GEM buttons, or over SSH:

```sh
on -f rcc /net/mmx/fs/sda0/custom-patches/TEL2ACP/tel2acp.sh mode-custom
on -f rcc /net/mmx/fs/sda0/custom-patches/TEL2ACP/tel2acp.sh custom-music
```

`mode-*` and `custom-*` also work as actions in `mod/command.sh`, e.g. `CUSTOM_PATCHES="TEL2ACP mode-custom custom-music"`.
Changes apply from the next TEL press, no reboot needed. `status` shows the mode and CarPlay Custom.
The GEM buttons also get an `[x]` in front of the selected mode and CarPlay Custom screen.
GEM may only show a new `[x]` after an MMI reboot; Status is always up to date.
The 1.1.0 `mode-phone` setting carries over as CarPlay / CarPlay Custom with Phone.

In the CarPlay Custom mode, TEL opens the phone menu instead when Android Auto is on screen or no CarPlay phone is connected.

## Using it together with JOKERBTN

TEL2ACP and JOKERBTN (the steering wheel star button skip fix) can be installed together, in any order. Each adds its own marked block to `lsd.sh`,
and both jars go into one shared `-Xbootclasspath/p:` list. Each has its own boot guard.

`uninstall` removes only that patch's block. Use `uninstall` rather than `restore` when both are installed:
`restore` puts back `lsd.sh` as it was before that patch's first install, which can also remove the other patch's block.

## Uninstall

```sh
on -f rcc /net/mmx/fs/sda0/custom-patches/TEL2ACP/tel2acp.sh uninstall
```

Or use **Uninstall** in the GEM screen, or set `CUSTOM_PATCHES="TEL2ACP uninstall"` in `mod/command.sh`. Reboot afterwards.

`restore` puts back the original `lsd.sh` saved on first install, in case `uninstall` can't clean it.

## Safeguards

**Before changing anything**, the installer checks that:

- the firmware is exactly `MHI2_ER_AU57x_K3663_1` (checksum of `/ifs/lsd.jxe`)
- `TEL2ACP.jar` on the card isn't damaged, and the copy on the unit matches it
- `lsd.sh` has the expected layout
- `/mnt/persist` is writable (the boot guard needs it)
- M.I.B. isn't busy flashing, backing up or rebooting

If any check fails, it stops and leaves the unit as it was.

**Boot guard.** Every time the MMI screen software starts, the `lsd.sh` block first leaves a flag in `/mnt/persist`, then loads the patch.
The patched class deletes the flag once it has loaded.
If the flag is still there at the next start, that start failed, so the block starts the stock software without the patch and remembers that.

**If the screen stays black after installing:**

1. Lock the car and leave it until the MMI shuts down, or restart the MMI. The next start runs without the patch.
2. Check `status` (GEM screen, SSH or `mod/command.sh`). It shows `DISABLED the patch after a failed start`.
3. Run `uninstall`. Keep `custom-patches/TEL2ACP/tel2acp.log` from the card, it shows what happened.

The boot guard only covers starting up. If the screen software ever crashed when you *press* TEL, it would come back with the patch still on.
Then stop using TEL and run `uninstall` from the GEM screen.
Running `install` again re-arms a disabled patch.

## Setting up SSH

No cable is needed if the car has a Wi-Fi hotspot (the A6 C7 this was built on does). SSH works over it.

1. Turn on the car's Wi-Fi hotspot in the MMI settings and connect your laptop to it.
2. Turn on SSH with M.I.B. (its `sshd` mod). The [M.I.B. wiki](https://wiki.mibsolution.one/en/MHI2) has the menu entry and the login details.
3. Connect from the laptop. In the system dump from this unit, the hotspot address of the MMI is `10.173.189.1`:

   ```sh
   ssh root@10.173.189.1
   ```

   If the laptop refuses the MMI's old key type, add `-o HostKeyAlgorithms=+ssh-rsa`.

If the car has no Wi-Fi hotspot, a USB-to-Ethernet adapter in the car's USB port works too. Check the M.I.B. wiki for supported adapters.

## What the installer changes

- Copies `TEL2ACP.jar` to `/mnt/app/eso/hmi/lsd/jars/`.
- Adds the boot guard block before the J9 start line in `/mnt/app/eso/hmi/lsd/lsd.sh`. Every line ends in `#TEL2ACP`.
  When the guard allows it, the block adds the jar to J9's `-Xbootclasspath/p:` list, which loads the patched class ahead of the firmware's own copy in `lsd.jxe`.
  M.I.B.'s NavActiveIgnore patch works the same way.
  If another mod (like the JOKERBTN patch) already uses `-Xbootclasspath/p:`, TEL2ACP joins that list instead of adding a second option, so both work.
- Uses three small flag files in `/mnt/persist` (`tel2acp_pending`, `tel2acp_loaded`, `tel2acp_disabled`).
  The TEL mode is stored in `/mnt/persist/tel2acp_mode` (no file means TEL / CarPlay),
  CarPlay Custom in `/mnt/persist/tel2acp_custom` (no file means Phone).
- Saves the original `lsd.sh` as `lsd.sh.tel2acp.bak` on the unit and `custom-patches/TEL2ACP/backup/lsd.sh` on the card.

`-force` installs anyway when the firmware doesn't match or no `sha1sum` tool is found. A damaged jar is never installed.

## Build from source

Needs a JDK 8, because newer JDKs can't produce the old class format the MMI's J9 VM uses.

```bash
JAVA_HOME="/path/to/jdk8" ./build.sh
```

This rebuilds `TEL2ACP/TEL2ACP.jar` from `src/`. The classes in `stubs/` only stand in for firmware types at compile time and are not included in the jar.
Set `JXE` to your `lsd.jxe` to also check the jar against the firmware (see [docs/TOOLS.md](docs/TOOLS.md#checkrefspy)).

## How it works

Every hard key goes through `de.audi.tghu.fwhmi.appmgr.HardKeyManager.triggerKeyEvent()`. TEL normally fires the state-machine event `HK_TEL` (103).
The patched class fires `SWITCH_TO_TERMINAL_MODE` (1903) instead. The firmware already uses that event when a connected phone takes over the screen.
If a CarPlay screen (240000–240009) is already showing, the [TEL mode](#tel-modes) decides:
`HK_TEL` as before, nothing, or `DSICarplay.requestUI(...)`, which asks the iPhone to show the CarPlay Custom screen.
After opening CarPlay it lights the TEL key through `KbdService.setHKIlluminationExclusive`, the same call the phone screen uses.

Full analysis: [docs/FINDINGS.md](docs/FINDINGS.md).

## Analysis tools

[`tools/jxe/`](tools/jxe) has the Python tools used to read the firmware: a class browser and disassembler for `lsd.jxe`, a state machine explorer, and a checker that tests a patch jar against the firmware.
Usage is in [docs/TOOLS.md](docs/TOOLS.md).

To check whether TEL2ACP can support another firmware, run [`compat.py`](docs/TOOLS.md#compatpy) on that unit's `lsd.jxe` (from an M.I.B. backup, `backup/<MU>-<train>/<MU>-lsd.jxe`).

## Credits

- M.I.B. by MIBonk and MIB-Wiki, for the SD card toolkit and the `-Xbootclasspath/p:` approach.
- zrnec and mrfixpl in [jilleb/mib2-toolbox#283](https://github.com/jilleb/mib2-toolbox/issues/283), for the first findings on Audi button mapping.

> [!NOTE]
> AI was used to explore the firmware, find the key handling and CarPlay entry points, and write the patch, installer and analysis tools.
> Treat the results like any other community patch that has only been tested on one car.
