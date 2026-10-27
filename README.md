# MIB2-Patcher

Patches for the Audi MHI2 MMI, packaged as one add-on for the M.I.B. (More Incredible Bash) SD card.
All patches live in one `mib2-patcher` folder on the card and show up under a single **MIB2_Patcher** entry in the GEM.
Patches are sorted by firmware train, and the patcher only offers the ones built for the unit's firmware.

| Patch | Does | Status | Docs |
|---|---|---|---|
| JOKERBTN | The steering wheel joker (star) button skips tracks in CarPlay, and the joker, INFO and voice buttons can be set to other functions | Not tested on a car | [README](docs/JOKERBTN/README.md) |
| TEL2ACP | The TEL key opens Apple CarPlay | Tested on one car | [README](docs/TEL2ACP/README.md) |
| MENUSCREEN | A MENU tile that opens a screen with Open GEM, Open REM and Restart CarPlay | Prototype, not tested on a car | [README](docs/MENUSCREEN/README.md) |

| Train | Unit | Patches |
|---|---|---|
| `MHI2_ER_AU57x_K3663_1` | MU1425, e.g. A6/A7 C7 | JOKERBTN, TEL2ACP, MENUSCREEN |

> [!WARNING]
> The patches were built by reverse engineering one unit's firmware. If something is wrong, the MMI screen software may not start (black screen, no GEM).
> Each patch has a boot guard that turns it off by itself after a failed start. SSH is still the best way to undo things. Use at your own risk.

## Requirements

- Audi MHI2 with a firmware train from the table above. Each installer checks the firmware fingerprint and refuses to run on anything else.
- [M.I.B.](https://wiki.mibsolution.one/en/MHI2) on an SD card in the **SD1 slot** (`sda0`), with GEM enabled.
- SSH access to the unit (recommended, see [Setting up SSH](#setting-up-ssh)).

## Install

1. Copy the `mib2-patcher` folder to the root of the M.I.B. SD card.
2. Put the card in SD1 and add the GEM screen, using **one** of these:

   **Over SSH**

   ```sh
   on -f rcc /net/mmx/fs/sda0/mib2-patcher/patcher.sh gem-on
   ```

   **Without SSH**: copy `mib2-patcher/command.sh` to `mod/command.sh` on the card.
   Then run it with GEM → M.I.B. → individual script, or run the M.I.B. installation again
   (software update from the SD card), which runs `mod/command.sh` at the end. Delete `mod/command.sh` afterwards.
   It runs `gem-on` by default. To install patches or run other commands, fill in `PATCHER_RUN` at the top of the file.

3. Reboot the MMI (GEM → M.I.B. → Reboot).
4. Open GEM → **MIB2_Patcher**. Install the patches you want from their screens, then reboot again.

The patcher writes to `mib2-patcher/patcher.log` on the SD card, and each patch to its own log in its folder.
The GEM screens only work while the SD card is in SD1. Installed patches keep working without the card.

## The GEM screen

```text
MIB2_Patcher           Status of all patches, Install all patches, Uninstall all patches
├── JOKERBTN           Status, Install, Uninstall, Restore
│   ├── Joker_button   one entry per function
│   ├── INFO_button
│   └── Voice_button
├── MENUSCREEN         Status, Install, Uninstall, Restore, tile slot
└── TEL2ACP            Status, TEL modes, Install, Uninstall, Restore
    └── Set_CarPlay_Custom
```

## patcher.sh

```sh
on -f rcc /net/mmx/fs/sda0/mib2-patcher/patcher.sh <command>
```

| Command | Does |
|---|---|
| `train` | Show the train the patcher picked |
| `status` | Show the train and the status of every patch |
| `install` | Install every patch of the train |
| `uninstall` | Uninstall every patch and remove the MIB2_Patcher GEM screen |
| `gem-on`, `gem-off` | Add or remove the MIB2_Patcher screen and the screens of every patch |
| `<PATCH> <command>` | Run one patch's installer, e.g. `patcher.sh JOKERBTN set info next` |

Several commands can follow each other, e.g. `patcher.sh install gem-on`. `-force` before the command passes `-force` to the patch installers,
and uses the only train folder when the firmware matches none.

The patcher picks the train by comparing the checksum of `/ifs/lsd.jxe` with `lsd.jxe.sha1` in each train folder.
That takes about a minute, so for quick changes over SSH you can also run a patch installer directly, as shown in its README.

## Uninstall

Use **Uninstall all patches** in the GEM, the Uninstall entry of one patch, or:

```sh
on -f rcc /net/mmx/fs/sda0/mib2-patcher/patcher.sh uninstall
```

Reboot afterwards. Use `uninstall` rather than `restore` when more than one patch is installed:
`restore` puts back `lsd.sh` as it was before that patch's first install, which can also remove the other patches' blocks.

## Moving from the separate patch repos

The patches are the same as in the `MIB2-A6C7-JOKERBTN`, `MIB2-A6C7-TEL2ACP` and `MIB2-A6C7-MENUSCREEN` repos.
Installed patches keep working: the `lsd.sh` blocks, jars and settings on the unit don't change.

1. Copy `mib2-patcher` to the card and run `gem-on` as above. It removes the old JOKERBTN, TEL2ACP and MENUSCREEN GEM screens.
2. Delete the `custom-patches` folder and the old `mod/command.sh` from the card.

## Setting up SSH

No cable is needed if the car has a Wi-Fi hotspot (the A6 C7 these were built on does). SSH works over it.

1. Turn on the car's Wi-Fi hotspot in the MMI settings and connect your laptop to it.
2. Turn on SSH with M.I.B. (its `sshd` mod). The [M.I.B. wiki](https://wiki.mibsolution.one/en/MHI2) has the menu entry and the login details.
3. Connect from the laptop. In the system dump from the MU1425 unit, the hotspot address of the MMI is `10.173.189.1`:

   ```sh
   ssh root@10.173.189.1
   ```

   If the laptop refuses the MMI's old key type, add `-o HostKeyAlgorithms=+ssh-rsa`.

If the car has no Wi-Fi hotspot, a USB-to-Ethernet adapter in the car's USB port works too. Check the M.I.B. wiki for supported adapters.

## Repository layout

| Path | Contents |
|---|---|
| `mib2-patcher/` | The folder that goes on the SD card: `patcher.sh`, the `command.sh` hook and the MIB2_Patcher GEM screen |
| `mib2-patcher/patches/<train>/` | `lsd.jxe.sha1` of the train, and one folder per patch with its jar, installer and GEM screens |
| `source/<train>/<patch>/` | Java source of the patch (`src/`) and compile-only firmware stubs (`stubs/`) |
| `docs/<patch>/` | README and firmware findings of each patch |
| `tools/jxe/` | Python tools for reading the firmware, see [docs/TOOLS.md](docs/TOOLS.md) |
| `build.sh` | Builds a patch jar |

## Build from source

Needs a JDK 8, because newer JDKs can't produce the old class format the MMI's J9 VM uses.

```bash
JAVA_HOME="/path/to/jdk8" ./build.sh MHI2_ER_AU57x_K3663_1 TEL2ACP
```

This rebuilds `mib2-patcher/patches/<train>/<patch>/<patch>.jar` and its `.sha1` from `source/<train>/<patch>/`.
The classes in `stubs/` only stand in for firmware types at compile time and are not included in the jar.
Set `JXE` to your `lsd.jxe` to also check the jar against the firmware (see [docs/TOOLS.md](docs/TOOLS.md#checkrefspy)).

## Adding a train

1. Make a folder `mib2-patcher/patches/<train>` with the train name M.I.B. shows, e.g. `MHI2_ER_AU57x_K3663_1`.
2. Put the sha1 of that firmware's `lsd.jxe` in `lsd.jxe.sha1` in it.
3. For each patch, check it against the firmware with [`checkrefs.py`](docs/TOOLS.md#checkrefspy) (and [`compat.py`](docs/TOOLS.md#compatpy) for TEL2ACP).
   Copy the patch folder, or put adapted source in `source/<train>/<patch>` and build it.
4. In the copied folder, set `SUPPORTED_JXE_SHA1` in the installer and change the train name in the paths in `esd/`.

## Adding a patch

A patch is a folder in `mib2-patcher/patches/<train>/` with one installer `.sh` file. The patcher calls it with
`install`, `uninstall`, `status`, `gem-on` and `gem-off`, and passes anything else on from `patcher.sh <PATCH> ...`.
Its main GEM screen uses `MIB2_Patcher` as parent and is linked into `/eso/hmi/engdefs` as `z_mib2patcher_<PATCH>.esd`,
so it sorts after the MIB2_Patcher screen.

## Credits

- M.I.B. by MIBonk and MIB-Wiki, for the SD card toolkit and the `-Xbootclasspath/p:` approach.
- mrfixpl in [jilleb/mib2-toolbox#283](https://github.com/jilleb/mib2-toolbox/issues/283), for the first findings on Audi button mapping.

> [!NOTE]
> AI was used to explore the firmware and write the patches, installers and analysis tools.
> Treat the results like any other community patch.
