# CarPlay in the cluster: findings

Unit: `MU1425` / `MHI2_ER_AU57x_K3663_1` (Audi A6 C7, MHI2), cluster with the MMI navigation map
Sources analysed: `/ifs/lsd.jxe` from the unit, `displaymanager`, `dio_manager` and `libairplay.so`, and live tests over SSH

## Status

**Experimental, not part of the patches.** The native hook in [`source/MHI2_ER_AU57x_K3663_1/CARPLAY2KOMBI/native/`](../../source/MHI2_ER_AU57x_K3663_1/CARPLAY2KOMBI/native) shows live CarPlay in the cluster.
It still lags behind the main screen when CarPlay changes little (a menu move is only a few frames), and navigation switches the cluster back to its own view when a route starts.
It isn't installed by MIB2-Patcher, and is not on the test unit any more.

To try it by hand over SSH (see [Passthrough: progress](#passthrough-progress)):
1. Copy `libcpkhook.so` and `cpk-cluster.sh` to `/mnt/app/cpk/`.
2. Back up `/mnt/system/etc/eso/production/smartphone_integrator.json` and add `"LD_PRELOAD=/mnt/app/cpk/libcpkhook.so"` to `dio_manager`'s `envs`, then reboot the MMI.
3. `sh /mnt/app/cpk/cpk-cluster.sh on [delay [fill [repeats]]]`, and `off` to get the map back.

To remove it: `cpk-cluster.sh off`, put the backed-up `smartphone_integrator.json` back, delete `/mnt/app/cpk` and `/ramdisk/cpk`, then reboot the MMI.
Rebooting matters: until then `smartphone_integrator` still starts CarPlay with the old `LD_PRELOAD`.

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

**What the cluster gets.** `displaymanager` is started from the boot image, so there's no config to preload a recorder into it.
Its encoder is `/lib/libnvvsenc.so` (NVIDIA TVMR encoder, muxed by `libnvtsmux.so`).
`NvVideoStreamEncoderOpen` and `NvVideoStreamEncoderSetAttributes` both write `66` (H.264 Baseline `profile_idc`) into the TVMR configuration, next to 30 fps and the width and height from the attributes.
So the cluster normally decodes **Baseline**, and CarPlay sends **High** profile. Passing it through only works if the cluster's decoder also handles High profile. That has to be tested on the cluster, or the stream has to be re-encoded as Baseline on the MMX.

## Passthrough: injection tests

Test streams were written to `/dev/mlb/isoTX2` while the cluster was on context 77, which stops the map encoder (it fails on the CarPlay displayable).
The writer is [`cpkinject.c`](../../source/MHI2_ER_AU57x_K3663_1/CARPLAY2KOMBI/native/cpkinject.c). It runs as a library constructor inside any host program, because there are no QNX startup files to link a program.

- **The driver only takes whole blocks.** `displaymanager` asks for `MLB_DEVCTL_GET_IF_PACKET_SIZE` and `..._BLOCK_CNT` and writes 64 × 188 = 12032 bytes at a time.
  Writes of other sizes (`cat`) succeed but are dropped. `/dev/mlb/debug/isoTX2` shows the driver's block counter, and a test with an empty file shows that `displaymanager`'s retry loop adds about 15 blocks on its own.
- **Whole blocks are counted as sent.** With `dd bs=12032`, or with `cpkinject` pacing each block by the stream's PCR, every block of the test streams shows up in the counter.

| Stream | Delivery | Cluster |
|---|---|---|
| CarPlay recording, High 780×468, video PID `0x1011` or `0x11`, PCR PID `0x100` | burst | grid background |
| Baseline 800×480 test pattern, same layout | burst, then real time | grid background |
| Baseline 800×480 colours: video PID `0x1011`, `0x11`, `0x21`, and `0x1011` with PMT `0x100`, PCR on the video PID | real time | grid background with the "Offroad" title |

So neither the profile, the speed nor these PID layouts explain it.

**Our blocks do reach the cluster.** Written while the map was streaming, the 800×480 pattern made the cluster flicker between a grey picture and the map: it followed our PAT/PMT, but couldn't decode the picture.

**The size has to be 800×298.** That's the cluster map size in `navi/app/map/Context` (`KOMBI_SCREEN_WIDTH` 800, `KOMBI_SCREEN_HEIGHT` 298).
A Baseline 800×298 test pattern (video PID `0x1011`, PCR PID `0x100`, PMT `0x1000`, keyframe every 15 frames):

| Delivery | Cluster |
|---|---|
| Mixed into the live map stream | the test pattern, glitching with the map |
| Alone, cluster on context 77, paced by PCR | **the test pattern, smooth** |

So the path works: stop the map encoder with `dmdt sc 4 77`, write whole 12032-byte blocks to `/dev/mlb/isoTX2` in real time, and switch back with `dmdt sc 4 73`.
CarPlay's own stream is 780×468, so it can't be passed through unchanged. It has to be scaled to 800×298 and encoded again.

**Layout.** A recorded CarPlay clip, re-encoded on a Mac and sent this way, plays on the cluster.
The map window sits between the two round dials. The whole CarPlay screen scaled to **496×298**, centred with black sides, fits it best. A full-width crop is too zoomed in, and 85% or 70% leave unused space.

**Context 76 on the cluster.** `dmdt sc 4 76` ([16 HMI, 59 CarPlay]) makes `displaymanager`'s own encoder run without the `nvmap` errors of context 77.
With only a stream displayable it seems to hand the decoder's buffer straight to the encoder ("Optimized case 1: non stream displayable not found in actual context!" in the binary); with a drawn layer it composites first.
The cluster showed a green, glitchy picture, not CarPlay: the composite doesn't contain the decoded CarPlay frame properly.
Contexts [33, 59] and [59, 33] (CarPlay with the cluster map layer) fail like 77, with the `nvmap` error.
So `displaymanager` can't encode CarPlay for the cluster; it needs an encoder of our own.

## Hardware transcoding in the hook

[`cpkenc.c`](../../source/MHI2_ER_AU57x_K3663_1/CARPLAY2KOMBI/native/cpkenc.c) does the scaling and encoding on the Tegra, inside `dio_manager`.

- **Getting the frames.** `libnvss_video` shows each decoded frame with `NvMediaVideoMixerRender(mixer, 1, NULL, &primary, NULL, NULL, NULL, releaseList, timestamp)` (9 arguments).
  `primary` is 0x1c bytes, with the decoded `NvMediaVideoSurface` at `+8`. The surfaces are YUV 4:2:0 (type 0), 784×480 for CarPlay's 780×468.
  The call goes through the PLT, so the hook intercepts it.
- **Scaling.** A second mixer, `NvMediaVideoMixerCreate(dev, 800, 298, aspect, srcW, srcH, 0×6, 0x21000, NULL)`, with surface rendering (`0x20000`) and DVD mode (`0x1000`), renders with `NvMediaVideoMixerRenderSurface` into an RGBA surface (type 4, 800×298).
  The source rect is `{0,0,780,468}` and the destination `{152,0,648,298}`. The surface starts zeroed, so the sides stay black.
  NVIDIA's libraries pass floats in integer registers (softfp), so the aspect ratio goes in as its bits.
- **Encoding.** `NvMediaVideoEncoderCreate(dev, 0, &init, 4 /*RGBA*/, 4, 4, dev)` is the same encoder `libnvvsenc` uses for the map.
  `init` has 800, 298 at `+0/+4`, 30/1 at `+0x14/+0x18`, 1 at `+0x20` and the config at `+0x30`. The config has the IDR period at `+0`, the bitrate at `+0x20`, and 1, 66 at `+0x80/+0x84`.
  `NvMediaVideoEncoderFeedFrame(enc, rgba, NULL, NULL, &params)` takes the frame. The params must be zeroed up to at least `+0x48`: `+0x44` is a count and `+0x48` an array pointer, and junk there crashed it.
  `BitsAvailable` / `GetBits` return the output.

The output is Baseline level 3.0, 800×298, 30 fps, with SPS, PPS and IDR every 15 frames (at 2.4 Mbit/s, about 1.8 Mbit/s in practice), and CarPlay keeps running.
A 15-second recording of it, packed as TS and sent with `cpkinject`, **plays cleanly on the cluster**.

## Live streaming

[`cpkstream.c`](../../source/MHI2_ER_AU57x_K3663_1/CARPLAY2KOMBI/native/cpkstream.c) streams the encoder output to the cluster live, and **live CarPlay shows in the cluster**.

- Each frame becomes PAT, PMT, a PCR packet and the video PES (PTS 60 ms after the PCR, from the monotonic clock), padded with null packets to whole 12032-byte blocks.
- A lock-free ring (32 blocks) feeds a writer thread that writes the blocks to `/dev/mlb/isoTX2`, so CarPlay's own display never waits on the MOST bus.
  If the ring is full, the frame is dropped and the next one is forced to be an IDR. With no new frames, the thread sends a PCR every 100 ms.
- The encoder is drained right after each frame (up to 30 ms). Without that, a frame only came out with the next CarPlay frame, which made the cluster lag on a still screen.
- The hook must be built with `-mno-unaligned-access`: the MMX traps unaligned word stores, and clang merged byte writes of the PES header into one.

[`cpk-cluster.sh`](../../source/MHI2_ER_AU57x_K3663_1/CARPLAY2KOMBI/native/cpk-cluster.sh) (installed in `/mnt/app/cpk/`) switches it:
`on` puts the cluster on context 77 and creates `/ramdisk/cpk/stream`; `off` removes it and puts the map back with context 73.
Streaming writes nothing to flash: the video goes from memory to the MOST device, and the trigger and the log (capped at 200 lines) are in `/ramdisk`.

Still open: navigation switches the cluster back (to the compass) when a route starts, see Open questions.

## How to repeat the checks

Over SSH on the MMX (see the README for setting up SSH):

- Non-interactive SSH doesn't load root's `.profile`, so set `LD_LIBRARY_PATH` first (`. /mnt/app/root/.profile`).
- `dmdt` needs `./config/framework.json`, so run it from a folder with `config -> /etc/eso/production`.
  `/tmp` can't hold folders on this unit; use `/ramdisk`. Without the config, `dmdt` aborts and leaves a core dump in `/mnt/ota/system/core`.
- `dmdt gc` lists the contexts and their displayables. `dmdt dc` declares a context and `dmdt sc` switches a display to it.
- The encoder's messages are in `sloginfo`.

## Open questions

- Whether the cluster decodes High profile at 800×298, or only Baseline (not needed: the Tegra encoder makes Baseline).
- Keeping the cluster on context 77: `GfxStreamController` switches display 4 back to its own context when route guidance starts (seen as the compass when a CarPlay route starts). Needs a Java patch.
- Whether CarPlay keeps drawing frames (and the hook keeps encoding) while the main screen shows another MMI screen.
- Whether the cluster would show a stream that is not map-like, or needs a particular foreground picture type (`setFGLayer`, never called by the HMI).
- `getDisplayableInfo(59, 4)` exists in the DSI but has no caller; its answer wasn't checked.
