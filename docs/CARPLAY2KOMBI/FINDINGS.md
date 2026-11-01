# CarPlay in the cluster: findings

Unit: `MU1425` / `MHI2_ER_AU57x_K3663_1` (Audi A6 C7, MHI2), cluster with the MMI navigation map
Sources analysed: `/ifs/lsd.jxe` from the unit, `displaymanager`, `dio_manager` and `libairplay.so`, and live tests over SSH

## TL;DR

**Not possible with a Java patch.** The cluster map is H.264 video that the MMX encodes and sends over MOST.
The display manager accepts CarPlay as the cluster's source, but the hardware encoder can't read CarPlay's video buffer.
It failed over and over (84 times in 8 seconds) until the cluster was switched back to the map.

- The cluster is display `4` of the display manager, and navigation picks what it shows with `switchContext(ctx, 4, 9876)`.
- CarPlay is displayable `59` (`DISPLAYABLE_EXTERNAL_SMARTPHONE`). Nothing in the firmware sends it to display 4, and doing that by hand fails in the encoder.
- The CarPlay stack is AirPlay `210.81` with one main screen. It has no instrument cluster stream (Apple's `altScreen`) and no route guidance data, so the iPhone never sends a cluster map or turn arrows.
- What's left is native code inside the CarPlay process (see [Other routes](#other-routes)), which is a different kind of project than the patches in this repo.

## How the cluster map gets to the cluster

**Native side.** `displaymanager` (`/eso/bin/apps/displaymanager`, config `/etc/eso/production/displaymanager.json`):

| Piece | Value |
|---|---|
| Encoder | `most_encoder` on `/dev/mlb/isoTX2`, NVIDIA MPE hardware encoder, H.264 in MPEG-TS |
| Sizing | `nv_bytes_per_frame` 20000, `nv_frames_per_gop` 5, comment "Audi ca 100kB" |
| Source | One displayable, `CMOSTEncoderInterface::setContentDisplayable(did)` |
| Cluster map displayable | `33` `DISPLAYABLE_KOMBI_MAP_VIEW` (`58` for the Google Earth map) |

The `videoovermost` process and `CVideoOverMostInterface` are for rear-seat entertainment (SDIS coding, DTCP copy protection), not the cluster.

**Java side.** In `lsd.jxe`:

| Layer | Class | Value |
|---|---|---|
| Display type | `navi/app/cluster/DSIKOMOManager` calls `setDisplayType(4, 2)` | `DISPLAYID_CLUSTER`, `DISPLAYTYPE_H264_MOST` |
| Context switch | `navi/app/cluster/GfxStreamController` → `command/komo/DisplaySwitchContextCommand` | `switchContext(ctx, 4, 9876)`, `MAGIC_SESSION_ID` 9876 |
| Update rate | `DisplaySetUpdateRateCommand` | `setUpdateRate(4, 0/1/10)` for off, reduced, full |
| Contexts | `fwhmi/DisplayManagerMIBHigh.defineContexts` | `73` = [33], `74` = [58], `75` = [20 route guidance] |

`GfxStreamController.switchViewMode` maps the cluster view to a context: map and compass use 73 (74 with Google Earth), KDK uses 75.
It runs when the view is picked in the cluster menu (`ClusterInputListener`, model 40112), when route guidance starts or stops, and when the cluster reports a new state or data rate (`DSIKOMOGfxStreamSinkListener`).

The HMI's own `fwhmi/DisplayManager` only knows displays 0 and 1, so only navigation ever touches display 4.

## How CarPlay is shown

- `dio_manager` runs the CarPlay session with `libairplay.so` (`210.81`) and decodes the video with NVIDIA `nvss`.
- In the display manager it's displayable `59`. The HMI shows it on the main screen as context `76` = [16 HMI, 59] (`TerminalModeScreenBag1`, model 240000).
- `DSICarplay` only has the main screen as a resource (`RESOURCE_MAIN_SCREEN` = 2). There's no second screen or cluster resource.

## Test

With CarPlay open on the main screen (iPhone through a wireless adapter) and the cluster on the navigation map view:

```sh
dmdt dc 77 59    # declare context 77 with only the CarPlay video
dmdt sc 4 77     # show it on the cluster
dmdt sc 4 73     # back to the map
```

| Step | Result |
|---|---|
| `dc 77 59`, `sc 4 77` | Accepted (`rc=0`) |
| Encoder | Looped 84 times in 8 seconds: MPE reset, `nvmap: nvmap devctl failure (code = c0080500 ret = -22)`, encoder closed. Nothing was streamed |
| `sc 4 73` | The map stream started again at once (`TsMux: Muxing only single stream`) |

An earlier test, before the cluster view was checked, failed the same way (24 times in 24 seconds).
Nothing is saved: the extra context only lives in the running display manager, and a reboot also puts the map back.

The encoder reads buffers the MMX renders itself, like the nav map, but not frames from the hardware video decoder.

## Other routes

- **Pass CarPlay's H.264 through** (work in progress, see [Passthrough: progress](#passthrough-progress)). Hook the video in `dio_manager` / `libairplay`, mux it to MPEG-TS and write it to `/dev/mlb/isoTX2` instead of the map.
  M.I.B.'s `carplay_maps_vc` mod has a native `altscreen-most-bridge` for Virtual Cockpit units (MU1102, MU1367, MU1440, MU1447) and patches `dio_manager` per firmware.
  On this unit the iPhone sends one keyframe per session, the stream is the whole 800×480 screen, and a mistake can crash CarPlay or the screens.
- **Copy the decoded frames** into a normal buffer before encoding. That needs NVIDIA driver internals with no documentation.

## Passthrough: progress

Work on the first route. The hook is in [`source/MHI2_ER_AU57x_K3663_1/CARPLAY2KOMBI/native/`](../../source/MHI2_ER_AU57x_K3663_1/CARPLAY2KOMBI/native), built with `build.sh` (Homebrew `llvm` + `lld` and the unit's `/proc/boot/libc.so.3`, no QNX SDK).

**Hook point.** `libairplay.so` imports `NvSSVideoDecode(handle, buffer, arg)` from `libnvss_video.so`, so a preloaded library can see every buffer.
`buffer` is `{data, len, pts (u64), flag (u8)}`, from the `memcpy(dst, [r1], [r1 + 4])` in `NvSSVideoDecode`.
`RTLD_NEXT` (`-3` in `libc.so.3`) finds nothing from a preloaded library, so the hook gets the real functions with `dlopen("libnvss_video.so", 0x100)`, the mode the firmware's `mm-ipod` uses.

**Loading it.** `smartphone_integrator` starts `dio_manager` for each CarPlay session with the `envs` in `/etc/eso/production/smartphone_integrator.json` (the same file as `/mnt/system/etc/...`).
The firmware already uses `LD_PRELOAD` there for another process. One added entry, `LD_PRELOAD=/mnt/app/cpk/libcpkhook.so`, loads the hook. `smartphone_integrator` reads the file only when it starts, so it needs an MMI reboot.
The original is kept as `/mnt/persist/cpk/smartphone_integrator.json.orig`. To undo:

```sh
mount -uw /mnt/system && cp /mnt/persist/cpk/smartphone_integrator.json.orig /mnt/system/etc/eso/production/smartphone_integrator.json; mount -ur /mnt/system
```

**What the iPhone sends** (recorded with `mkdir /ramdisk/cpk/dump` for a few seconds, then `rm -r` it):

| Item | Value |
|---|---|
| Format | Annex-B H.264, one NAL per buffer |
| SPS | High profile, level 3.1, 780×468, 4 reference frames (NAL header `0x27`) |
| Frames | P slices of about 1.5 KB, more frames when the picture moves |
| Keyframes | SPS, PPS and IDR only at the start of a session |

`AirPlayReceiverSessionForceKeyFrame(session, completion, context)` asks the iPhone for a new keyframe.
The hook keeps the session from `AirPlayReceiverSessionSetDelegate(session, delegate)`, which `dio_manager` calls at the start of a session, and drops it in `AirPlayReceiverSessionTearDown`.
Asking for a keyframe returned 0, and the next buffers held SPS, PPS and an 8.7 KB IDR. So the stream can be started for the cluster at any time.

**Not known yet:** the format the cluster decodes. `displaymanager` doesn't log its encoder settings, so the profile and resolution of the map stream, and whether the cluster accepts High profile at 780×468, are still open.

## How to repeat the checks

Over SSH on the MMX (see the README for setting up SSH):

- Non-interactive SSH doesn't load root's `.profile`, so set `LD_LIBRARY_PATH` first (`. /mnt/app/root/.profile`).
- `dmdt` needs `./config/framework.json`, so run it from a folder with `config -> /etc/eso/production`.
  `/tmp` can't hold folders on this unit; use `/ramdisk`. Without the config, `dmdt` aborts and leaves a core dump in `/mnt/ota/system/core`.
- `dmdt gc` lists the contexts and their displayables. `dmdt dc` declares a context and `dmdt sc` switches a display to it.
- The encoder's messages are in `sloginfo`.

## Open questions

- The cluster stream's profile, level and resolution, and whether the cluster decodes CarPlay's High profile 780×468.
- How to send our own MPEG-TS on `/dev/mlb/isoTX2` while `displaymanager` keeps the MOST connection to the cluster open.
- Whether the cluster would show a stream that is not map-like, or needs a particular foreground picture type (`setFGLayer`, never called by the HMI).
- `getDisplayableInfo(59, 4)` exists in the DSI but has no caller; its answer wasn't checked.
