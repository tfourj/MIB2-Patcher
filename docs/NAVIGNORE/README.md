# NAVIGNORE

Keeps the **MMI navigation** running on an Audi MHI2 when **Apple CarPlay** starts a route, packaged as an add-on for the M.I.B. (More Incredible Bash) SD card.

| You do | Before | After |
|---|---|---|
| Start a route in CarPlay while the MMI navigation guides | The MMI ends its route, the cluster shows the compass | The MMI route and the cluster map keep running |
| Phone call or Siri in CarPlay | The HMI knows CarPlay has the phone or speech | Also hidden from the HMI, like M.I.B.'s NavActiveIgnore (see [How it works](#how-it-works)) |

> [!WARNING]
> **Not tested yet**, built for an A6 C7 with `MHI2_ER_AU57x_K3663_1` by reverse engineering that unit's firmware (see [FINDINGS.md](FINDINGS.md)).
> If something is wrong, the MMI screen software may not start (black screen, no GEM).
> A [boot guard](#safeguards) turns the patch off by itself after one failed start. SSH is still the best way to undo things. Use at your own risk.

## Requirements

- Audi MHI2 with firmware **`MHI2_ER_AU57x_K3663_1`** (MU1425), e.g. A6/A7 C7.
  The installer checks the firmware fingerprint and refuses to run on anything else.
- [MIB2-Patcher](../../README.md#install) on the unit (needs M.I.B. once).
- Not together with M.I.B.'s NavActiveIgnore, which replaces the same class. The installer refuses to run while that is on.

## What's in the `NAVIGNORE` folder

| File | Purpose |
|---|---|
| `NAVIGNORE.jar` | The patched `AppState` class |
| `NAVIGNORE.jar.sha1` | Checksum the installer uses to spot a damaged copy |
| `navignore.sh` | Installer: `install`, `uninstall`, `restore`, `status`, `gem-on`, `gem-off` |
| `esd/` | GEM screen with Status, Install, Uninstall and Restore buttons |

## Install

1. Copy MIB2-Patcher to the unit (see the [main README](../../README.md#install)).
2. Run the installer, using **one** of these:

   - GEM → MIB2_Patcher → NAVIGNORE → Install
   - Over SSH:

     ```sh
     /mnt/app/eso/mib2-patcher/patcher.sh NAVIGNORE install
     ```

3. Reboot the MMI.

## Uninstall

- GEM → MIB2_Patcher → NAVIGNORE → Uninstall, or `patcher.sh NAVIGNORE uninstall` over SSH, then reboot the MMI.
- **Restore original lsd.sh** puts back the `lsd.sh` backup from the first install, for when the block can't be removed cleanly.

## Safeguards

- The installer checks the firmware fingerprint and the jar's checksum before it changes anything, and backs up `lsd.sh` on first install.
- **Boot guard.** The patched class only loads when CarPlay connects, so the `lsd.sh` block can't wait for it.
  Like MENUSCREEN, it leaves a `navignore_pending` flag in `/mnt/persist` at every HMI start and removes it after a minute.
  If the flag is still there at the next start, the HMI restarted within that minute, so the block writes `navignore_disabled` and starts the HMI without the jar.
  **Install** re-arms it. **Status** shows the guard's state.

## What the installer changes

| Where | What |
|---|---|
| `/mnt/app/eso/hmi/lsd/jars/NAVIGNORE.jar` | The jar |
| `/mnt/app/eso/hmi/lsd/lsd.sh` | A block marked `#NAVIGNORE` in front of the J9 start line that adds the jar to `-Xbootclasspath/p:` |
| `/mnt/app/eso/hmi/lsd/lsd.sh.navignore.bak` | The `lsd.sh` backup |
| `/mnt/persist/navignore_*` | Boot guard flags |
| `/mnt/app/eso/hmi/engdefs/z_0mib2patcher_NAVIGNORE.esd` | The GEM screen link |

## Build from source

```bash
./build.sh MHI2_ER_AU57x_K3663_1 NAVIGNORE
```

Needs a JDK 8 (see the [main README](../../README.md#build-from-source)). The source is in `source/MHI2_ER_AU57x_K3663_1/NAVIGNORE/src`. It has no stubs, because `AppState` only uses `java.lang`.

## How it works

CarPlay tells the HMI which app states the iPhone owns (navigation, phone, speech) in `org.dsi.ifc.carplay.AppState` objects.
When the iPhone takes navigation, the MMI ends its own route guidance.
The patched `AppState` returns unknown (0) from `getAppStateID()` and `getOwner()`, so the HMI never sees the iPhone take an app state over. The fields keep the real values.

This is how M.I.B.'s NavActiveIgnore for Audi works too. Because every app state is hidden, the HMI also doesn't see CarPlay take the phone or speech; watch for side effects in calls or Siri. Android Auto isn't covered.
More in [FINDINGS.md](FINDINGS.md).

## Credits

The idea comes from M.I.B.'s NavActiveIgnore mod (`navignore_audi.jar`). This is a separate build of the same approach for this firmware, checked against its `lsd.jxe`.
