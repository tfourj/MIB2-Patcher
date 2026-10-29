# JOKERBTN

Makes the steering wheel **joker ("star") button** skip tracks in **Apple CarPlay** (and Android Auto) on an Audi MHI2 MMI,
and lets you choose what the joker and the two buttons above it (**INFO** and **voice**) do. Packaged as an add-on for the M.I.B. (More Incredible Bash) SD card,
with GEM screens to pick the functions in the car.

| Star button set to "skip forward", playing | Before | After |
|---|---|---|
| CarPlay / Android Auto | Nothing | Next track |
| Media (USB, Bluetooth, SD...) | Next track | Next track |
| Radio | As before | As before |

| Button | Stock | Default after install |
|---|---|---|
| Joker (star) | Function from the MMI car settings | Same, with the CarPlay skip fix above |
| INFO | Nav announcement | Previous track |
| Voice, short press | Audi voice control | Next track |
| Voice, long press | Siri / phone voice assistant | Siri / phone voice assistant |

Each of the three buttons can be set to one of these functions at any time, without a reboot:

| Function | Does |
|---|---|
| `stock` | What the button does without the patch (for the joker: the MMI setting, with the CarPlay skip fix) |
| `prev`, `next` | Previous / next track (or station), in CarPlay, Android Auto, media and radio |
| `mute` | Same as pressing the volume thumbwheel |
| `radio`, `media`, `phone`, `nav`, `menu` | Same as the RADIO, MEDIA, TEL, NAV and MENU keys |
| `navinfo` | Nav announcement (joker and voice button) |

All other buttons work as before.

> [!WARNING]
> **Tested on one car only**, an A6 C7 with `MHI2_ER_AU57x_K3663_1`. The patch was built by reverse engineering that unit's firmware (see [FINDINGS.md](FINDINGS.md)).
> If something is wrong, the MMI screen software may not start (black screen, no GEM).
> A [boot guard](#safeguards) turns the patch off by itself after one failed start. SSH is still the best way to undo things. Use at your own risk.

## Requirements

- Audi MHI2 with firmware **`MHI2_ER_AU57x_K3663_1`** (MU1425), e.g. A6/A7 C7.
  The installer checks the firmware fingerprint and refuses to run on anything else.
- [MIB2-Patcher](../../README.md#install) on the unit (needs M.I.B. once).
- For the CarPlay skip: the star button set to **skip forward** in the MMI car settings, and the joker set to `stock` here.
- SSH access to the unit (recommended, see [Setting up SSH](../../README.md#setting-up-ssh)).

## What's in the `JOKERBTN` folder

| File | Purpose |
|---|---|
| `JOKERBTN.jar` | The patched terminal mode app and voice button classes, the joker listener and the button settings classes |
| `JOKERBTN.jar.sha1` | Checksum the installer uses to spot a damaged copy |
| `jokerbtn.sh` | Installer: `install`, `uninstall`, `restore`, `status`, `set`, `gem-on`, `gem-off` |
| `esd/` | GEM screens: Status / Install / Uninstall / Restore, plus one settings screen per button |

## Install

1. Copy MIB2-Patcher to the unit (see the [main README](../../README.md#install)).
2. Run the installer, using **one** of these:

   - GEM → MIB2_Patcher → JOKERBTN → Install
   - Over SSH:

     ```sh
     on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh JOKERBTN install
     ```

   - Without SSH: add a line `JOKERBTN install` after `update` in `PATCHER_RUN` in `mod/command.sh` (see the [main README](../../README.md#install)).

3. Reboot the MMI (GEM → M.I.B. → Reboot).
4. Play something in CarPlay and press the star button. It should skip to the next track. INFO and a short press on the voice button should skip back and forward.

The installer writes everything it does to `mib2-patcher/logs/JOKERBTN/jokerbtn.log` on the SD card,
or to `/mnt/persist/mib2-patcher/JOKERBTN/jokerbtn.log` on the unit when no card is in.

## Choosing the functions

**In the GEM**: the **MIB2_Patcher → JOKERBTN** screen has three settings screens,
**Joker_button**, **INFO_button** and **Voice_button**. Tap a function to use it. It works within a few seconds, no reboot needed.

**Over SSH**:

```sh
on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh JOKERBTN set info next
```

The button is `joker`, `info` or `voice`, the function one from the table above. `status` shows the current settings.
The settings are stored in `/mnt/persist/jokerbtn.conf` and kept by MIB2-Patcher updates.

**The joker** uses the MMI's own joker setting only when it is set to `stock`. Any other function here replaces it.

**The voice button** only changes on a short press. Hold it to start Siri or the phone's voice assistant, as before.
While it has a function, the Audi voice control can't be started from the steering wheel. Set it back to `stock` to use it.

## Using it together with TEL2ACP

JOKERBTN and TEL2ACP can be installed together, in any order. Each adds its own marked block to `lsd.sh`,
and both jars go into one shared `-Xbootclasspath/p:` list. Each has its own boot guard.
With TEL2ACP, `phone` opens CarPlay like the TEL key does.

`uninstall` removes only that patch's block. Use `uninstall` rather than `restore` when both are installed:
`restore` puts back `lsd.sh` as it was before that patch's first install, which can also remove the other patch's block.

JOKERBTN now includes everything MFLBTN did. If MFLBTN is installed, the installer stops: run `mflbtn.sh uninstall` first and remove its folder from the card.

## Uninstall

```sh
on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh JOKERBTN uninstall
```

Or use **Uninstall** in the GEM screen, or add a line `JOKERBTN uninstall` to `PATCHER_RUN` in `mod/command.sh`. Reboot afterwards.

`restore` puts back the original `lsd.sh` saved on first install, in case `uninstall` can't clean it.

## Safeguards

**Before changing anything**, the installer checks that:

- the firmware is exactly `MHI2_ER_AU57x_K3663_1` (checksum of `/ifs/lsd.jxe`)
- `JOKERBTN.jar` in MIB2-Patcher isn't damaged, and the copy the HMI loads matches it
- `lsd.sh` has the expected layout, and MFLBTN isn't installed
- `/mnt/persist` is writable (the boot guard needs it)
- M.I.B. isn't busy flashing, backing up or rebooting

If any check fails, it stops and leaves the unit as it was.

**Boot guard.** Every time the MMI screen software starts, the `lsd.sh` block first leaves a flag in `/mnt/persist`, then loads the patch.
The patched class deletes the flag once the CarPlay app has started.
If the flag is still there at the next start, that start failed, so the block starts the stock software without the patch and remembers that.

**If the screen stays black after installing:**

1. Lock the car and leave it until the MMI shuts down, or restart the MMI. The next start runs without the patch.
2. Check `status` (GEM screen, SSH or `mod/command.sh`). It shows `DISABLED the patch after a failed start`.
3. Run `uninstall`. Copy the logs to the SD card and keep them, they show what happened.

The boot guard only covers starting up. If the screen software ever crashed when you press one of the three buttons, it would come back with the patch still on.
Then set the buttons to `stock` in the GEM screen and run `uninstall`.
Running `install` again re-arms a disabled patch.

## Setting up SSH

See [Setting up SSH](../../README.md#setting-up-ssh) in the main README.

## What the installer changes

- Copies `JOKERBTN.jar` to `/mnt/app/eso/hmi/lsd/jars/`.
- Adds the boot guard block before the J9 start line in `/mnt/app/eso/hmi/lsd/lsd.sh`. Every line ends in `#JOKERBTN`.
  When the guard allows it, the block adds the jar to J9's `-Xbootclasspath/p:` list, which loads the patched classes ahead of the firmware's own copies in `lsd.jxe`.
  M.I.B.'s NavActiveIgnore patch works the same way.
  If another mod (like TEL2ACP) already uses `-Xbootclasspath/p:`, JOKERBTN joins that list instead of adding a second option, so both work.
- Writes the button settings to `/mnt/persist/jokerbtn.conf` (only if there are none yet).
- Uses three small flag files in `/mnt/persist` (`jokerbtn_pending`, `jokerbtn_loaded`, `jokerbtn_disabled`).
- Saves the original `lsd.sh` as `lsd.sh.jokerbtn.bak` on the unit and `/mnt/persist/mib2-patcher/JOKERBTN/backup/lsd.sh`.

`uninstall` and `restore` also delete the settings file. `-force` installs anyway when the firmware doesn't match or no `sha1sum` tool is found. A damaged jar is never installed.

## Build from source

Needs a JDK 8, because newer JDKs can't produce the old class format the MMI's J9 VM uses.

```bash
JAVA_HOME="/path/to/jdk8" ./build.sh MHI2_ER_AU57x_K3663_1 JOKERBTN
```

This rebuilds `mib2-patcher/patches/MHI2_ER_AU57x_K3663_1/JOKERBTN/JOKERBTN.jar` from `source/MHI2_ER_AU57x_K3663_1/JOKERBTN/src/`. The classes in `stubs/` next to it only stand in for firmware types at compile time and are not included in the jar.
Set `JXE` to your `lsd.jxe` to also check the jar against the firmware (see [TOOLS.md](../TOOLS.md#checkrefspy)).

## How it works

**CarPlay skip.** Steering wheel buttons reach the apps as shared virtual buttons, and every app that connects to one gets the press.
CarPlay connects to prev/next but not to the joker (virtual button 15), and media and the tuner only skip their own sources.
The patched `AbstractTerminalModeHMIApplication` also connects to the joker. When the joker is set to skip forward, `JokerBtnSkipListener` presses CarPlay's own "next" button (`TERMINAL_HK_NEXT_BUTTON`, 240002).
That button only skips while CarPlay or Android Auto is playing, so media and radio behave as before.

**Joker and INFO functions.** The key panel's joker and INFO keys are turned into HMI keys by the table `KeyMap.tableDSI`, which the keyboard code reads on every press.
The patch changes those table entries to the chosen key, for example `KEY_PREV` (13). Every app then sees a real "previous" press.

**Voice.** Voice presses go to `de.audi.tghu.fwhmi.SDSManagerGUI`, which the patch replaces.
It holds a press back until the button is released. A short press becomes a press of the chosen key.
If the key panel reports a long press first, the held press goes to voice control as usual, so Siri still starts.

The settings file is read again every 2 seconds, which is why changes work without a reboot.

Full analysis: [FINDINGS.md](FINDINGS.md).

## Analysis tools

[`tools/jxe/`](../../tools/jxe) has the Python tools used to read the firmware: a class browser and disassembler for `lsd.jxe`, a state machine explorer, and a checker that tests a patch jar against the firmware.
Usage is in [TOOLS.md](../TOOLS.md).

## Credits

- M.I.B. by MIBonk and MIB-Wiki, for the SD card toolkit and the `-Xbootclasspath/p:` approach.
- zrnec and mrfixpl in [jilleb/mib2-toolbox#283](https://github.com/jilleb/mib2-toolbox/issues/283), for the first findings on Audi button mapping.

> [!NOTE]
> AI was used to explore the firmware, find the joker and steering wheel button handling, and write the patch, installer and analysis tools.
> Treat the results like any other community patch that has only been tested on one car.
