# TEL button → CarPlay remap: findings

Unit: `MU1425` / `MHI2_ER_AU57x_K3663_1` (Audi MHI2, 2nd gen MMI)
Source analysed: `MIB BACKUP/WHOLE MIB/backup/MU1425-.../MU1425-lsd.jxe`

## TL;DR

**Yes, it looks possible, and it only needs one small class replaced.**

- Every hard key ends up in one method, `de.audi.tghu.fwhmi.appmgr.HardKeyManager.triggerKeyEvent(int terminalId, int key)`.
  For TEL (`key == 4`) it fires the System state-machine event `HK_TEL = 103`.
- The HMI already has an event that opens CarPlay from any normal app screen:
  `SMEventSystem.SWITCH_TO_TERMINAL_MODE = 1903`. Its transition has no guard.
- So a replacement `HardKeyManager` that fires `1903` instead of `103` for key 4 should make TEL open CarPlay.
  The jar would be loaded with `-Xbootclasspath/p:`, the same way the NavActiveIgnore mod works.

The thread is right that there is no APP/smartphone *key* in the Audi key tables (see below).
You don't need one, though. You can redirect an existing key one layer higher, where key presses become state-machine events.

## How the analysis was done

`lsd.jxe` is a zip with a single `rom.classes` (IBM J9 ROM image, `J99J` header, 16 558 classes).
I wrote a small Python parser for this J9 ROM layout (TOC, ROM class header, constant pool, fields, methods).
I added a disassembler for J9 bytecode and a tiny interpreter to evaluate the generated static tables
(key maps, state-machine trigger and transition lists).

J9 quirks found on this firmware, in case someone wants to repeat it:

- Bytecode size in the ROM method header is in 4-byte words, and operands are little-endian.
- `0xD7` (`aload0getfield`) is a 1-byte prefix followed by a normal `getfield`.
- `0xE7` is a 5-byte `invokeinterface2`: `E7 00 B9 <idx lo> <idx hi>`.
- Return opcodes are J9-specific (`AC` void, `AD` 1-slot, `AE` 2-slot, ...).
- `static final` constants are inlined, so event IDs show up as literal `bipush`/`sipush`, not field refs.

The tools are in [`tools/jxe/`](../tools/jxe), documented in [TOOLS.md](TOOLS.md).

## Key path: physical TEL button → phone screen

| Layer | Class / table | TEL value |
|---|---|---|
| DSI key code (from key panel) | `org.dsi.ifc.keypanel.Constants.KEY_TEL` | `3` |
| DSI → HMI key mapping | `de.audi.tghu.fwkbd.KeyMap.tableDSI` row `{3, 4}` | `4` |
| HMI key code | `de.audi.atip.hmi.event.KeyEvent.KEY_TEL` | `4` |
| Hard-key button model | `HardKeyManager.hardkeys[]` (16 `ButtonModel`s, IDs 1..16) | model `4` |
| Listener | `ListenerButtonModel.keyTyped(..)` → `HardKeyManager.triggerKeyEvent(terminalId, 4)` | |
| State-machine event | `HardKeyManager.fireSMEvent(terminalId, 103)` | `HK_TEL = 103` |
| System SM | state 165, event 103 → state 29 (Phone), action `PhoneActionProxy.hkTELPressed()` | |

`HardKeyManager.triggerKeyEvent` decompiled (original):

```java
switch (key) {
    case 1:  fireSMEvent(t, 101);  break; // HK_TUNER    (RADIO)
    case 2:  fireSMEvent(t, 102);  break; // HK_MEDIA
    case 3:  fireSMEvent(t, 107);  break; // HK_NET
    case 4:  if (getHmiService().getCurrentPopup() != 250002)
                 fireSMEvent(t, 103);      // HK_TEL      <-- the one to change
             break;
    case 5:  fireSMEvent(t, 104);  break; // HK_NAV
    case 6:  fireSMEvent(t, 105);  break; // HK_INFO
    case 7:  fireSMEvent(t, 106);  break; // HK_CAR
    case 8:  fireSMEvent(t, 111);  break; // HK_SETTINGS
    case 9:  fireSMEvent(t, 1374); break; // MFL_MENU
    case 10: fireSMEvent(t, 151);  break; // HK_SETUP_RETURN
    case 11: fireSMEvent(t, 152);  break; // HK_SETUP_CAR
    case 12: fireSMEvent(t, 153);  break; // HK_SETUP_INFO
    case 13: fireSMEvent(t, 154);  break; // HK_CAR_INFO
    case 14: fireSMEvent(t, 107);  break; // HK_NET
    case 15: getLogChannel().log(1000000, 1264); break;
    case 16: fireSMEvent(t, 1400); break; // HK_MENU
    default: getLogChannel().log(1000000, 1265, (long) key);
}
```

The numbering lines up with `KeyEvent` (`KEY_TUN=1`, `KEY_MED=2`, `KEY_TEL=4`, `KEY_NAV=5`, `KEY_CAR=7`, `KEY_SET=8`).
That is why model 4 is taken to be the TEL button.

### Why remapping in `KeyMap` doesn't work

- `org.dsi.ifc.keypanel.Constants` defines `KEY_SMARTPHONE = 114` and `KEY_HOME = 116`.
  Neither appears in any `KeyMap` table (`tableDSI`, `hkMUTableDSI`, `softkeyTableDSI`, `keySourceTable`, `tableDSIChinaRC`).
- `KeyEvent` has no smartphone or CarPlay key, and `HardKeyManager` has no case for one.
- So in `KeyMap` there is nothing to remap TEL *to*. This matches mrfixpl's comment in
  [jilleb/mib2-toolbox#283](https://github.com/jilleb/mib2-toolbox/issues/283).

## How CarPlay is entered (System state machine)

The System SM is generated code (`de.audi.tghu.system.sm.SystemSMMInit*`). I evaluated its tables.
Every transition into the terminal-mode (CarPlay/AA) state `246`:

| From state | Event | Meaning |
|---|---|---|
| 26 (main MENU screen) | `1909 MAIN_APPLICATION_WIZARD_ASI` | MENU tiles `smartphone` / `carPlay` (`SystemScreenBag1.mainGroupsWizard`) |
| 166 | `20421` | audio-focus model `1123 ACTIVE_SMARTPHONE_ENTERTAINMENT_CHOICE` changed |
| **179** | **`1903 SWITCH_TO_TERMINAL_MODE`** | **no guard** (transition 3544, flags 0) |
| 179 | `1904 NEW_DEVICE_DETECTED` | new-device disclaimer flow |

State 179 is the parent of the normal app states, including Phone (29), the Menu group (166 → 26) and terminal mode (246).
So `1903` is accepted from basically any normal app screen.

How the firmware itself fires `1903` today:

- `de.audi.app.terminalmode.statemachine.commands.ScreenChange.execute()` toggles ChoiceModel `1126 SWITCH_TO_TERMINAL_MODE_CHOICE`.
  This happens when the phone requests the screen ("Screen: MU -> Device").
- `SystemSMMInitMediators` has `ChangeMediator(1903, [1126])`, so any change of model 1126 fires event 1903.

The MENU → CarPlay tile goes to the same state `246`. So entering CarPlay from the MU side (not phone-initiated) is a normal, supported path.

## The patch

Replace `de/audi/tghu/fwhmi/appmgr/HardKeyManager.class` with a copy that changes only case 4.
The class must keep the same fields and methods, because it fully replaces the one in `lsd.jxe`:

- class `public`, no interfaces, super `java.lang.Object`
- fields `private final IFrameworkAccess framework`, `private final LogChannel logChannel`, `private final ButtonModel[] hardkeys`
- methods `<init>(IFrameworkAccess, LogChannel)`, `triggerKeyEvent(II)V`, `getModels()`, `getModel(I)`, `getModel(II)`,
  plus private `fireSMEvent(II)V`, `getHmiService()`, `getLogChannel()`

Case 4 opens CarPlay, and if CarPlay is already on screen, it falls back to the normal phone menu (TEL pressed twice → native phone):

```java
case 4:
    if (getHmiService().getCurrentPopup() == 250002) break;
    if (getHmiService().getCurrentScreenID() / 10000 == 24) { // terminal mode app id 24, screen base 240000
        fireSMEvent(terminalId, 103);  // HK_TEL: native phone
    } else {
        fireSMEvent(terminalId, 1903); // SWITCH_TO_TERMINAL_MODE: CarPlay
    }
    break;
```

The rest of the class is the original logic (constructor builds 16 `ButtonModel(i + 1)`, getters, logging IDs 1264/1265/1266).

### TEL key light

Each app state lights its own hard key on entry with `KbdService.setHKIlluminationExclusive(terminalId, key, true)`.
Phone uses key 4 (TEL), Media 2, Navi 5. The terminal mode state `246` only calls `activeApplication(.., 24)` and never sets a light,
so on CarPlay the key of the previous app stays lit.

`KbdHandler.setHKIlluminationExclusive` ignores the boolean. It always lights the given key and turns the previous one off.
`setHKIllumination` is empty and only `setHKIlluminationOff` switches a key off. Phone's `exitTelApp` calls it with `false`, which still lights TEL.

So after firing `1903`, the patch calls `framework.getKbdService().setHKIlluminationExclusive(terminalId, 4, true)` inside a catch-all.
Nothing on the way into state `246` changes the light again, so TEL stays lit until another app lights its own key.
CarPlay opened from the MENU tile or by the phone does not go through `HardKeyManager`, so the light doesn't change there.
`FocusManager.setActiveApplication(.., 24)` sees every entry, but would mean rebuilding that class too.

This is implemented in [`src/de/audi/tghu/fwhmi/appmgr/HardKeyManager.java`](../src/de/audi/tghu/fwhmi/appmgr/HardKeyManager.java).

### TEL modes and CarPlay Custom

What TEL does while a terminal mode screen is shown comes from `/mnt/persist/tel2acp_mode`, read at every TEL press:
no file or `tel` fires `HK_TEL` (phone menu), `carplay` does nothing, `custom` (or the 1.1.0 `phone`) asks the iPhone
for the CarPlay Custom screen in `/mnt/persist/tel2acp_custom`: `phone` (default), `home`, `maps`, `music` or `nowplaying`,
sent as `UI_PHONE`, `UI_HOME`, `UI_NAVIGATION`, `UI_MEDIA` or `UI_MEDIA_NOWPLAYING`.

The CarPlay DSI can do that:

- `org.dsi.ifc.carplay.DSICarplay.requestUI(int)` with `UI_HOME = 1`, `UI_PHONE = 2`, `UI_NAVIGATION = 3`, `UI_MEDIA = 4`, `UI_MEDIA_NOWPLAYING = 5`.
  This is the head unit side of CarPlay's "request UI".
- The HMI never calls it in normal use. The only caller is the diagnosis command `requestUI` in
  `CarplayDSILifecycleController.executeDiagCommand`, which always sends `0`.
- The service is `de.esolutions.fw.dsi.carplay.DSICarplayProvider`, registered in OSGi under `org.dsi.ifc.carplay.DSICarplay`.
  The terminal mode app finds it the same way (`createServiceTracker(DSICarplay.class)`).
  `IFrameworkAccess.getBundleCxt()` gives `HardKeyManager` the BundleContext to look it up.
  The provider is a plain service object, so `getService`/`ungetService` around one call is safe.

CarPlay and Android Auto share screen 240000, so the screen id can't tell them apart.
`ICoreSystemModelBank.ACTIVE_SMARTPHONE_DEVICE_CHOICE (1122)` can: `TMDeviceControl` sets it to 1 for an active CarPlay device,
2 for Android Auto, 3 for others and 0 when none is active. The patch only sends `requestUI` when it is 1,
and fires `HK_TEL` when it isn't or when anything throws.

Build notes:

- It is compiled against stub classes for `IFrameworkAccess`, `HMIService`, `KbdService`, `ButtonModel` and `LogChannel` (only the signatures used).
  The existing Audi mod `g24fullscreen.jar` uses class version 46 (Java 1.2). `build.sh` uses JDK 8 `-source 1.3 -target 1.2`, which gives the same version.
- Only `HardKeyManager.class` goes into the jar.
- Every firmware method the compiled class calls was checked against `lsd.jxe` (name, descriptor, interface vs class).

## Deployment

- M.I.B.'s `apps/installjava` already patches `lsd.sh` on `*AU*` trains. It adds
  `BOOTCLASSPATH="$BOOTCLASSPATH -Xbootclasspath/p:$BASE_DIR/lsd/jars/NavActiveIgnore.jar"` before the J9 start line.
  `-Xbootclasspath/p:` puts the jar ahead of `lsd.jxe`, so its classes win.
- On AU57x, `installjava -navignoreon` refuses to install NavIgnore ("old internal java structure").
  That refers to the NavIgnore classes, not to the bootclasspath mechanism.
- MU1425's `lsd.sh` (read from the M.I.B. `app.img` backup) has no `$J9` line. It starts the VM with
  `on -p 12 /ifs/jre/bin/j9 $VMOPTIONS -Xbootclasspath:$BOOTCLASSPATH -jxe:$LSD_DIR/lsd.jxe &`, so `installjava`'s `^$J9` sed matches nothing here.
- `TEL2ACP/tel2acp.sh` adds its own marked block for `lsd/jars/TEL2ACP.jar` the same way, and keeps `lsd.sh.tel2acp.bak` as the restore point.
- The unit's own system dump (`01_Export_.../MMXcore/info`) shows the real HMI start line:
  `/ifs/jre/bin/j9 ... -Xbootclasspath::/ifs/lsd.jxe:/eso/hmi/lsd/jars/lang_data.zip:...:/eso/hmi/lsd/jars/AppDevelopment.jar:... -jxe:/ifs/lsd.jxe`.
  So `lsd.jxe` lives in the boot image at `/ifs/lsd.jxe` (not in `/eso/hmi/lsd/`), and jars are referenced as `/eso/hmi/lsd/jars/...`.
- The same dump shows `/mnt/persist` (`/dev/fs0p2`, 8 MB) mounted on the MMX. The HMI already writes `/mnt/persist/savedpreset.json` there,
  so the boot guard flags go there too.

## Risks and things not verified

- **Tested on this car only.** TEL opens CarPlay after installing from GEM → M.I.B. → individual script and rebooting.
  Everything above comes from static analysis of this unit's `lsd.jxe`. The TEL key light has not been tested on the car yet.
- If the replacement class is wrong (missing method, wrong signature, bad class version), the HMI (`lsd`) can fail to start.
  That means a black screen and no GEM. **Have SSH or another way to restore `lsd.sh` / delete the jar before testing.**
- Behaviour with no CarPlay phone connected is unknown. State 246 picks substate 248 or 249 based on `STATE_TERMINAL_MODE_CHOICE (1105)`.
  It's probably the same screen as tapping the MENU smartphone tile without a phone.
- The `getCurrentScreenID() / 10000 == 24` check comes from `TerminalModeHMIApplication.getId() = 24`
  and `TerminalModeScreenFactory.createScreen()`, which builds screens 240000 (CarPlay/AA view) to 240009 (240002 is "no device").
  It has not been seen on a running unit yet.
- CarPlay Custom with Phone works on the car. Home, Maps, Music and Now Playing use the same call with other `UI_*` values
  and have not been tested yet.
- The Set_CarPlay_Custom GEM submenu (`screen Set_CarPlay_Custom TEL2ACP` in its own esd file) has not been seen on the car yet.
  If it doesn't show up, the `custom-*` commands still work over SSH or `mod/command.sh`.
  GEM decides where the submenu sits in the TEL2ACP screen; the esd file has no way to place it.
- GEM can only show live values from persistence (`keyValue`/`BIOSCtrl` with `per <partition> <key>`), not from files.
  So the selected mode is shown by rewriting the button labels with `[x]` in the esd files on the SD card.
  GEM is a native process (not in `lsd.jxe`), so it is unknown whether it rereads the files before the next MMI reboot.
- Long-press TEL, steering-wheel phone buttons (`MFL_*`) and voice (`SDS`) use other paths and are not affected.
- Only valid for this firmware (`K3663`). Other trains may number things differently.

## Testing on the car

1. Enable SSH on the unit (M.I.B. `mod/sshd`) so the change can be undone without the HMI.
2. Install with `TEL2ACP/tel2acp.sh install` and reboot (see the README).
3. Test TEL with and without a CarPlay phone connected, and check the HMI log for log ID `1266` (`fireSMEvent`) with event `1903`.
