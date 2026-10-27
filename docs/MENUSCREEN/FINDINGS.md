# Custom MENU screen: findings

Unit: `MU1425` / `MHI2_ER_AU57x_K3663_1` (Audi MHI2, 2nd gen MMI)
Source analysed: `MIB BACKUP/WHOLE MIB/backup/MU1425-.../MU1425-lsd.jxe`, with the tools in [`tools/jxe/`](../../tools/jxe) ([TOOLS.md](../TOOLS.md)).

## TL;DR

**A new screen and a MENU entry for it look possible with one replaced class and three new ones.**

- MENU (`SystemScreenBag1.mainGroupsWizard`) has 13 tiles. Some are always created but hidden on EU units.
  A hidden tile can be made visible and given its own text and action by replacing the small 3D tile class `ButtonWidget3D`.
- Screens come from HMI bundles. The bundle is picked by screen ID / 10000, and IDs like 90 are unused.
  A new `HMIBundle` can be registered at runtime through the firmware's OSGi registry, and its screen shown with `HMIServiceSM.showScreen`.
- The state machine stays in the MENU state while the new screen is up. HK_MENU re-enters MENU from anywhere, so MENU (and BACK, handled by the screen) brings the main menu back.

## How the MENU tiles work

`SystemScreenBag1.mainGroupsWizard` builds 13 `ButtonWidget3D` tiles inside a `WizardWidget3D`. The 3D engine draws them; Java gives each tile:

| Tile (widget name) | Function ID | Event | Visible on this unit |
|---|---|---|---|
| `charge` | 1002 | none | e-tron only |
| `car`, `tel`, `nav`, `media`, `tuner`, `tone`, `info` | 1003, 1004, 1007-1010, 1006 | 1388, 1390, 1387, 1384, 1385, 1391, 1803 | yes |
| `online` | 1005 | 1467 `MAIN_APPLICATION_WIZARD_ONLINE` | if Audi connect is coded (SysConst 70 or 84) |
| `breakdown` | 1011 | 1867 `MAIN_APPLICATION_WIZARD_BREAKDOWN` | China only (SysConst 29 = 2, 56 = 1, 102 = 1) |
| `smartphone`, `carPlay`, `androidAuto` | 1012-1014 | 1909 | one of them, by connected phone |

- Visibility is set by the generated `SystemScreenFactory.executeConditionmainGroupsWizardScreen`, which calls `setVisible` on each tile.
- A press goes `ButtonWidget3D.keyPressed(e, true)` → 3D animation → `keyPressedCallback()` → `AbstractButtonWidget.keyPressed` → `fireSMEvent(terminal, event)`. Only the press fires the event, not the release.
- `WizardWidget3D.initialize3DWizardButtons` sends the function IDs, states and `getText()` of the tiles to the 3D engine.
  A 14th function ID would probably have no icon or slot there, so reusing a hidden tile is safer than adding one.
- Function IDs 1005 and 1011 appear only in `mainGroupsWizard`, so `function ID + widget name` identifies the tile uniquely.
- Only MENU and the CAR menus (`carMainWizard`, `charismaMain*`) create `ButtonWidget3D`. The class is loaded when one of them opens, not at startup.

## How screens are found and shown

- `ScreenManager.getScreen` → `TerminalContext.getScreenFromCache` → `HMIRegistry.getScreen(terminal, screenId)` → `getHMIBundle(screenId / 10000).getScreen(screenId, terminal)`.
- `HMIRegistry` tracks the OSGi service `de.audi.atip.hmi.HMIBundle` and files each one under its `Skin` property (`variant.skin`, `MIBHigh3D`) at index `getId()`.
- Bundle IDs used by `lsd.jxe`: 0-7, 10-12, 17-19, 21-25.
- `HMIRegistry.getHMIBundle` reads the field `hmiBundles`, which `setSkin` points at the current skin's array once at startup.
  `registerHMIBundle` grows the array with `ensureHMIBundlesCapacity` (size `(id + 32) & ~31`) and stores the new array in `allHmiBundles` only, so `hmiBundles` keeps the old one.
  The startup array has 32 entries (IDs up to 25), so a bundle with ID 32 or more is never found.
  The first version used 90 and its screen never opened in the car. The patch now takes the highest free ID from 26 to 31,
  checked against the registered `HMIBundle` services, and copies the skin name from the System bundle (ID 0).
- `IFrameworkAccess.getBundleCxt()` gives a `BundleContext` from any widget's terminal, so no activator is needed.
- `FwHMI` implements both `HMIService` and `HMIServiceSM`. The state machine shows screens with
  `HMIServiceSM.showScreen(terminal, subterminal, screenId, lock, reinit, states, flag, partialPopups)`, where `states[0]` is the screen ID.

## How a simple screen is built

`SystemScreenBag2.messageScreen` is the template: a `LabelWidget` inside a `BorderWidgetD4` (images 8010-8045) on a `ScreenWidget(28)`,
with fonts from `SystemScreenFactory.getFonts(0, terminal)` and colours from `getColorArray(9)`.
`getFonts` and `getColorArray` are package-private, so the new classes live in `de.audi.tghu.system.hmi.mibhigh3d`.
`LabelWidget.setText(String)` shows plain text without the language database.

## Leaving the screen

| Key | In the MENU state (26) | What the new screen does |
|---|---|---|
| MENU (`HK_MENU` 1400) | state 165 → 26, re-enters MENU and shows its screen | nothing, the state machine handles it |
| BACK (`KEY_BACK` 15, `HK_RETURN` 121) | not handled | fires `HK_MENU` itself |
| NAV, MEDIA, ... | normal transitions | nothing, the state machine handles it |

## The three buttons

**Knob.** A `ScreenWidget` gets the MMI knob directly. Turns arrive in `keyTurned(WheelButtonEvent)` with key code 17 (the volume knob is 16),
`getDirection()` (0 right, 1 left) and `getClickCount()`. A knob press is `keyPressed` with key code 17 (`INC_MENU_ENTER`). The screen consumes both and moves its own selection.

**GEM and REM.** `de.audi.tghu.fwkbd.ComboKeyDetector.fireCombiKeyPressedEvent` handles the hidden key combinations:

| Combination | What the firmware does |
|---|---|
| SETUP + CAR | `getStartupMgr().requestStartBundle("AppDevelopment")` (the GEM jar), then DSI key 129 |
| SETUP + BACK | DSI key 128 |

`KeyMap.tableDSI` maps DSI 129 to HMI `KEY_SET_CAR` (25) and 128 to `KEY_SET_BACK` (24).
`KbdListener.fireKeyPressedEvent` posts `new KeyEvent(rootWindow, KEY_PRESSED, time, key, terminal, keySource)` to the event dispatcher, with key source 1 for these combos and no release.
The buttons do the same, so GEM and REM open exactly when their key combinations would.
GEM is `/eso/hmi/lsd/jars/AppDevelopment.jar`; M.I.B. replaces it with its own GEM 4.12.

**Restart CarPlay.** The HMI already runs shell commands with `Runtime.getRuntime().exec(String[])`, for example `ComboKeyDetector.reMountFs` and `EngineeringApp$ProcessExecuter`.
The button runs `/bin/sh -c` with the firmware's USB power scripts (see the CPRESTART patch) on a background thread, so the HMI never waits for it.

## BACK

The BACK hard key never reaches a screen's `keyPressed`. `de.audi.tghu.fwhmi.ApplicationManagerGUI.keyPressed` gets it (key 15) and fires `HK_RETURN` (121) for the terminal, and for terminal 1 too when it came from terminal 0.
The MENU state (26) and its parents have no `HK_RETURN` transition, so BACK did nothing on the screen in the first car test, while MENU (`HK_MENU`, 1400) worked.

The patch adds a `BaseJobFilter` with `EventDispatcherAdmin.addEventFilter`, like `HMIEventQueue$HKFilter` and `DrvSchoolProcessor`.
While `HMIService.getCurrentScreenID()` is the MENUSCREEN screen, it drops `HK_RETURN` for that terminal and fires `HK_MENU` instead.
The new event is posted from a short-lived thread, because the filter runs inside the event queue's `enqueue`.

## Boot guard

The patched classes load only when MENU or CAR opens, so they can't report a good start the way TEL2ACP's `HardKeyManager` does.
The `lsd.sh` block leaves a pending flag and removes it in the background after 60 seconds. A pending flag at the next start means the HMI or the unit restarted within that minute, so the patch is disabled.

## Risks and things not verified

- **Not tested on the car.** Everything above comes from static analysis of this unit's `lsd.jxe`.
- Whether the GEM and REM keys reach their menus when posted while the custom screen is showing. The key combinations normally fire from any screen.
- Whether `LabelWidget.setHighlighted` shows a highlight on these labels. The `>` mark shows the selection either way.
- Whether the screen renders correctly in the 3D skin (fonts, colours, border, label text set before the screen connects).
- Whether the 3D engine lays out one more visible tile cleanly, and which icon it shows. The reused tile keeps its original icon.
- `ButtonWidget3D` is used by MENU and the CAR menus. If the replacement fails to load, both may stop working until `uninstall` (GEM still opens with its own key combination).
- Car test of the first version (A6 C7): with the `breakdown` slot the 3D ring got a 9th tile and its icons were drawn wrongly, so `online` is now the default.
- In the 3D menu a press reaches the tile twice: `ButtonWidget3D.keyPressed(e, true)` stores it, the 3D engine animates, and `WizardWidget3D.processEngine3DCallback` calls `keyPressedCallback()`, which fires the event.
  The patch opens its screen there, and from the release callback if the press callback never arrives.
- If the patch's code fails when the tile is pressed, the press does nothing. It never falls back to the original event, so the breakdown tile can't start a breakdown call.
- Only valid for this firmware (`K3663`).
