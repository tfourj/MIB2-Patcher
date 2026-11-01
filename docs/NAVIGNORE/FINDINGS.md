# MMI navigation during a CarPlay route: findings

Unit: `MU1425` / `MHI2_ER_AU57x_K3663_1` (Audi MHI2)
Source analysed: `/ifs/lsd.jxe` from the unit, M.I.B.'s `mod/java/navignore_audi.jar`

## TL;DR

**One small class decides it.** CarPlay reports app states to the HMI as `org.dsi.ifc.carplay.AppState`. When the iPhone owns navigation, the MMI hands it over and ends its route.
A replacement `AppState` that reports every state and owner as unknown keeps the MMI navigation running. That's what M.I.B.'s NavActiveIgnore does, and NAVIGNORE builds the same class for this firmware.

## The app state

`org/dsi/ifc/carplay/AppState` in `lsd.jxe`:

| Member | Notes |
|---|---|
| `int appStateID`, `int owner`, `int speechMode` | public fields |
| `<init>()`, `<init>(int, int, int)` | |
| `getAppStateID()`, `getOwner()`, `getSpeechMode()` | |
| `toString()` | `AppState(appStateID=…,owner=…,…)` with a `StringBuffer(150)` |

Constants in `org/dsi/ifc/carplay/DSICarplay`:

| Constant | Value |
|---|---|
| `APPLICATIONID_UNKNOWN` / `_PHONE` / `_NAVIGATION` / `_SPEECH` | 0 / 1 / 2 / 3 |
| `APPLICATIONOWNER_UNKNOWN` / `_MAINUNIT` / `_DEVICE` | 0 / 1 / 2 |

Only two places read the getters:
- `de/audi/app/terminalmode/dsi/carplay/CarplayDSILifecycleController$DSICarplayListenerImpl.convertAppState`, which turns them into `CarPlayAppState` objects for the rest of the HMI.
- `de/esolutions/fw/comm/dsi/carplay/impl/AppStateSerializer.putOptionalAppState`.

So the getters are the one place that covers every consumer.

## M.I.B.'s NavActiveIgnore

`navignore_audi.jar` (installed with `apps/installjava -navignoreon`) holds two classes:

- `org/dsi/ifc/carplay/AppState`: `getAppStateID()` and `getOwner()` return 0, the rest as in the firmware.
- `de/audi/app/terminalmode/smartphone/androidauto2/nav/AndroidAuto2NavHandler`: the same for Android Auto.

NAVIGNORE only builds `AppState` from source. The Android Auto class depends on the exact firmware build, and it isn't needed for CarPlay.

## Open questions

- Not tested on the car yet: the cluster during a CarPlay route, and calls and Siri with every app state hidden.
- A narrower variant could hide only the navigation state (2) and pass phone and speech through.
