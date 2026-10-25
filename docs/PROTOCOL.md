# Insta360 GO Ultra — camera protocol notes

Reverse-engineered while building GO Ultra Transfer (camera firmware **v1.9.29**). Everything marked *verified* was
observed on a real GO Ultra; the rest is inferred. Message numbers and schemas partly come from
[RigacciOrg/insta360-wifi-api](https://github.com/RigacciOrg/insta360-wifi-api) (`pb2/`), which describes the
X-series — GO Ultra differs in several places, noted below.

Contents: [Transports](#transports) · [BLE](#ble) · [Wi-Fi](#wi-fi) · [TCP 6666](#tcp-6666) · [HTTP](#http-port-80) ·
[Settings](#shooting-settings) · [Sleep](#sleep) · [Safety](#safety)

## Transports

| Link | Used for |
|---|---|
| BLE (service `BE80`) | wake-up, authorization, Wi-Fi credentials, recording, settings, status |
| Wi-Fi AP + TCP `192.168.42.1:6666` | file list, thumbnails, live view, the same commands as BLE |
| Wi-Fi AP + HTTP `192.168.42.1:80` | file download (Range supported), `/CONF` capability file |

Both BLE and TCP carry the same 12-byte message header and protobuf bodies.

## BLE

**Discovery.** The camera advertises service `0000be80-0000-1000-8000-00805f9b34fb`; the name is the tail of the
serial number. Manufacturer data `22 00 …` = asleep, `22 01 …` = awake (the next byte counts down before sleep).
The BLE address is random and changes — find the camera by service, not by MAC.

**GATT.** `BE81` — write (commands), `BE82` — notify (responses). Write in chunks of MTU−3, one frame at a time.

**Frame** (same as GO 3 "Go2BlePacket"):

```
FF [type] [subtype] [size u16 LE] [inner …] [CRC16/MODBUS LE over all preceding bytes]
```

Phone → camera: type `0x07`; camera → phone: type `0x06`. Subtype `0x40` = message, `0x41` = sync.
Message inner = `[u32 LE 16+len]` + 12-byte message header (below) + protobuf body.

**Sync.** Right after connecting the camera sends `FF 06 41 …` (payload `AB BA …`); answer
`FF 07 41 07 00` + 7 zero bytes + CRC before the first command.

**Message header** (BLE and TCP): `04 00 00 [code u16 LE] 02 [seq u24 LE] 80 00 00`.
Responses carry code 200 (OK) / 400 / 500 and the request's seq; notifications have codes ≥ 8000.
Over BLE only the low seq byte is meaningful.

**Authorization** — CHECK_AUTHORIZATION (39) `{1: id string}` → `{1: status (0 authorized, 1 not), …}`.
If not authorized the camera shows a prompt on its screen; after the user confirms, notification 8209 `{1: 0}`
arrives. A camera woken over BLE keeps its screen off — the user has to switch the screen on to see the prompt.
The id is any string chosen by the phone and remembered by the camera.

**Wake-up from power-off** (*verified*). Requires a BLE **bond** (Just Works pairing done once while the camera is on;
without a bond the sleeping camera refuses connections). Write to `BE81`:

```
FF 0C 02 [len u16 LE] [authorized id, ASCII] [CRC16]
```

The BLE controller answers `FF 0D 03 01 00 01 …`; ~2.5 s later the main CPU sends a sync and normal commands work.
The camera stays with its screen off, but its Wi-Fi AP comes up.

**Wi-Fi credentials** — GET_OPTIONS (8) `{1: [36]}` → `Options {36: {1 ssid, 2 password, 3 channel, …}}`.

## Wi-Fi

AP `GO Ultra XXXXXX.OSC`, 5 GHz, WPA2; the camera is `192.168.42.1`. The network has no internet, so on Android
connect with `WifiNetworkSpecifier` and bind sockets to that network. A VPN app on the phone may block traffic to it
(`bind: EPERM`).

## TCP 6666

**Framing:** `[u32 LE length including these 4 bytes][payload]`.

| Payload | Meaning |
|---|---|
| `06 00 00 "syNceNdinS"` | sync — send after connecting, the camera echoes it |
| `05 00 00` | keepalive — send every ~2 s (silence ≈10 s drops the link) |
| `04 00 00 …` | message (header as above) |
| `01 00 00 …` | live stream packet |

**Commands used** (TCP or BLE):

| Code | Name | Request → response | Notes |
|---|---|---|---|
| 1 / 2 | START / STOP_LIVE_STREAM | empty | 1280×720@30 H.265, notification 8234 with params |
| 3 | TAKE_PICTURE | empty → `{1: {1 uri, …}}` | only in a photo mode (*verified*); fires at once — the self-timer (field 26) applies only to the camera's own button |
| 4 / 5 | START / STOP_CAPTURE | **empty** | `{1: mode}` is ignored by GO Ultra |
| 7 | SET_OPTIONS | `{1: [field], 2: Options}` | |
| 8 | GET_OPTIONS | `{1: [fields packed]}` → `{2: Options}` | an unknown field makes the whole request 400 |
| 9 | SET_PHOTOGRAPHY_OPTIONS | `{1: [fields], 2: PhotographyOptions, 3: function_mode}` | |
| 10 | GET_PHOTOGRAPHY_OPTIONS | `{1: [fields], 2: function_mode}` → `{2: PhotographyOptions}` | |
| 11 | GET_FILE_EXTRA | `{1: uri, 2: 2 (thumbnail)}` | raw NV12, see below |
| 12 | DELETE_FILES | `{1: repeated uri}` → `{1: repeated fail_uri}` | *verified*; GO 3 tools send something else here |
| 13 | GET_FILE_LIST | `{1: 2 (all), 2: start, 3: limit}` → `{1: repeated uri, 2: total}` | max **100** per page, page by `total` |
| 15 | GET_CURRENT_CAPTURE_STATUS | → `{1: {1 state, 2 seconds}}` | state 0 idle, 13 TimeShift, other non-zero = recording; seconds = real time |
| 17 / 18 | GET / SET_TIMELAPSE_OPTIONS | `{1: TimelapseMode}` / `{1: {2 lapseTime ms}, 2: mode}` | interval & timelapse step |
| 38 | GET_FILEINFO_LIST | empty → per-file metadata | 9 size, 10 duration s, 19 {w,h}, 20 fps |
| 39 | CHECK_AUTHORIZATION | see BLE | |
| 105 | IFRAME_REQUEST | empty | ask for a key frame |

**Thumbnails** — GET_FILE_EXTRA response header byte 5 = `01` (raw, not protobuf):
`[u32 1][u32 total][u32 1][u32 0][u32 width][u32 height][16 zero bytes]` + NV12. Photos: use the EXIF thumbnail
from the first ~64 KB over HTTP instead; for live-photo videos and RAW `.dng` use the `.jpg` of the same shot.

**Live stream packet:** `01 00 00 [0x20 video] [u64 LE timestamp ms]` + Annex B H.265 from byte 12. Every frame is
split into **3 slices** in separate packets whose timestamps may differ by 1 ms — assemble access units by
`first_slice_segment_in_pic_flag`, not by timestamp. Configure the decoder with VPS/SPS/PPS from the first key frame
(`csd-0`). Hisilicon decoders reject `KEY_LOW_LATENCY`. While the stream runs, the camera's own menu is locked.

**Stream shape follows the recording** (*verified*): 16:9 modes → 1280×720, FreeFrame / 4:3 → 1280×960,
2.35:1 → 1280×544. The camera changes it **only when the stream is (re)started** — after switching mode or aspect
ratio, send STOP_LIVE_STREAM and then START_LIVE_STREAM strictly one after the other (sent in parallel they may
arrive swapped and the stream does not start). Reading the decoder's output format tells the real size.
FreeFrame records the whole 4:3 sensor; the camera marks a full-width 16:9 band and a full-height 9:16 column.

**Toddler Titan** (`VIDEO_BABYVIEW`, «глазами ребёнка»): video_sub_mode 16, function_mode **65** (outside 0…40,
found by scanning 41…80); field of view fixed to `SUPER_NEW` = 15 («Titan»), `SUPER_MICRO` with the macro lens.

## HTTP (port 80)

- `GET /DCIM/Camera01/<name>` — the file, no session needed; `Range` works (`206`, resumable downloads).
  `HEAD` gives the size (useful for `.dng`, which the file list reports without one).
- `GET /CONF` — the capability JSON (≈935 KB), see below.
- Directory listings are `403`; any other path answers `400` with body `1000`. There is no OSC API.

Files: `VID_YYYYMMDD_HHMMSS_NNN.mp4` (+ hidden preview `LRV_…_NNN.lrv`), `IMG_….jpg` (+ `.dng`),
`LIV_…` live photo = `.mp4` + `.lrv` + `.jpg` (+ `.dng`).

## Shooting settings

Two message types hold settings:

- **Options** (GET/SET_OPTIONS) — global: 1 video resolution of the current mode, 2 photo resolution,
  10 photos left, 11 battery `{1: external power, 2: %}`, 40 `photo_sub_mode`, 41 `video_sub_mode`,
  85 Pod `{1: attached, 2: {1: charging, 2: %}}`, 134 blur reduction.
  Normally the requested type number equals the field number in the reply, **except 133/134, which are swapped**:
  type 133 answers with field 134 (blur reduction) and type 134 with field 133 (*verified*). Request and set
  blur reduction as type 133 carrying field 134.
- **PhotographyOptions** (GET/SET_PHOTOGRAPHY_OPTIONS) — **per function mode**. A non-existent function mode
  (and `0`) silently answers with a copy of mode 7 — it is *not* "the current mode".

### Modes (*verified*)

Select a mode with SET_OPTIONS 41 (video) or 40 (photo); the other one then reads `100`.

| Mode | video_sub_mode | photo_sub_mode | function_mode |
|---|---|---|---|
| Video | 0 | 100 | 7 |
| Pure Video | 11 | 100 | 27 |
| Timelapse | 2 | 100 | 2 |
| TimeShift | 4 | 100 | 12 |
| FreeFrame | 5 | 100 | 16 |
| Loop recording | 6 | 100 | 17 |
| Slow motion | 9 | 100 | 21 |
| Photo | 100 | 0 | 6 |
| Interval | 100 | 2 | 3 |
| Burst | 100 | 3 | 5 |
| Starlapse | 100 | 7 | 18 |
| Toddler Titan (BABYVIEW) | 16 | 100 | 65 |

### PhotographyOptions fields (*verified* by changing one setting on the camera and diffing)

| CONF name | Field | Values |
|---|---|---|
| record_resolution | 31 | see resolution codes below |
| fov_type | 33 | LINEAR 1, ULTRAWIDE 2, ACTION_VIEW 4, LINEARHORIZON_45 6, LINEARHORIZON_360 11, DEWARP 13, MEGA_VIEW 14, SUPER_NEW 15 (Titan, fixed in Toddler Titan) |
| flowstate_level | 55 | off 0, slight 1, standard 2, high 3 |
| horizon_lock | 70 | bool |
| hdr_switch | 63 | bool — only at resolutions listed in CONF `hdr_support` |
| i_log_switch | 72 | bool |
| filter_mode | 18 | STANDARD 0, VIVID 2, VINTAGE 17, URBAN 18, FILM 19, NIGHT 20, GLOW 22, SELFIE 23, FROSTEDCCD 40, AMBERCCD 41, BLUECCD 42 |
| sharpness_level | 6 | LOW 0, MEDIUM 1, HIGH 2 |
| white_balance | 39 | Kelvin, 0 = auto |
| exposure_bias | 7 | sint32 zigzag, tenths of EV; ignored in full manual (ISO and shutter both fixed) |
| metering_mode | 46 | EVALUATIVE 0, FACE_PRIORITY 1 |
| exposure | 20 (photo), 21 (video) | `{1 program, 2 ISO (0 auto), 3 shutter s as double (0 auto)}`; program AUTO 0, ISO_PRIORITY 1, SHUTTER_PRIORITY 2, MANUAL 3, FULL_AUTO 5 — the camera menu has Auto (5) and Manual; manual with auto shutter is 1, with auto ISO is 2 |
| video_iso_top_limit | 24 | max ISO while ISO is auto, 0 = none |
| zoom x2 (damage_zoom / undamage_zoom) | 59 + 53 | 59 bool + 53 double 1.0 / 0.0 |
| blur reduction | 42 + **Options 134** | the camera writes both |
| portrait (self_portrait_switch_2) | 90 | `{1: brighten float, 2: smooth float}` 1.0 / 0.0; the camera adds 89 itself |
| photo_resolution | 40 | 8192×6144 17, 8192×4608 18, 4096×3072 19, 4000×2250 4 |
| raw_capture_type | 25 | PURESHOT 3, PURESHOT_RAW 4 |
| photography_self_timer | 26 | seconds |
| live_photo_switch | 85 | `{1: 0, 2: 1 off / 2 on}` |
| burst_capture_params | 44 + 51 + 45 | 44 shots, 51 seconds, 45 cached frames (camera-computed: 3/1→1, 5/1→1, 10/1→3, 15/1→5, 30/1→10, others→1; 0 with cache off) |
| cache_capture_enable | 52 | bool |
| record_duration | 29 + 54 | seconds (loop, interval, starlapse) |
| export_type (starlapse) | 56 | STARLAPSE_VIDEO 0, STAR_TRAILS_VIDEO 1, STAR_TRAILS_PHOTO 2 |
| lapse_time | TIMELAPSE_OPTIONS | ms; interval = TimelapseMode 2, timelapse = mode 1 |

### Resolution codes (field 31)

| | 24 | 25 | 30 | 48 | 50 | 60 | 100 | 120 | 200 | 240 |
|---|---|---|---|---|---|---|---|---|---|---|
| 3840×2160 | 49 | 48 | 24 | 258 | 92 | 23 | | | | |
| 2720×1530 | 51 | 50 | 39 | 259 | 100 | 38 | 25 | 217 | | |
| 1920×1080 | 53 | 52 | 29 | 260 | 81 | 40 | 150 | 28 | 26 | 27 |
| 4096×1744 (2.35:1) | 346 | 345 | 344 | 343 | 342 | 341 | | | | |
| 4000×3000 | 55 | 54 | 31 | | | | | | | |
| 2720×2040 | 57 | 56 | 41 | | | | | | | |
| 1920×1440 | 59 | 58 | 42 | | | | | | | |

### Capabilities: `/CONF`

```
{"<lens>": {"VIDEO_NORMAL": {"<param>": {"depend_on": [..], "list": {"<values joined by |>": [allowed]}},
                             "support_attrs": [params shown in this mode]}, …},
            "video_sub_mode": [...], "photo_sub_mode": [...]}
```

The list key is the current values of `depend_on` joined with `|`, else `default`. The official app takes its menus
from this file. Two extra rules the file expresses indirectly:

- HDR is allowed only at resolutions listed in the separate `hdr_support` parameter (4K60, 2.7K/1080p above 30 fps
  are missing).
- **The camera silently ignores a SET whose value is impossible together with the current settings** (e.g. 4K60
  while HDR is on). Its own menu first adjusts the dependent settings; a client should send those changes in a
  separate, earlier SET (sending them in the same SET is not enough).

### Self-test results

The debug build has a self-test that walks every mode and every value CONF allows, writes it the way the app does
and reads it back (≈1700 writes, ~25 min). On firmware v1.9.29 everything is taken except: EV in full manual
(ignored by the camera, so the app disables it there) and portrait mode, which may take more than a second to show up
in the read-back.

### Card space

| What | Where |
|---|---|
| total / free bytes | Options 158 `{1 total, 2 free, 3 reserve}` |
| recording time left (s), current mode and resolution | Options 9 (not always answered) or PhotographyOptions 41 (answered in every mode) |
| photos left | Options 10 (photo modes) |

Ask for each Options type in its own request: a type the camera doesn't answer makes the whole request fail.

### TimeShift (*verified*)

- During a recording the speed is **PhotographyOptions 37 of function mode 12**: 0 = Auto (sped up), 1 = Normal.
  Every recording starts in Auto. SET_PHOTOGRAPHY_OPTIONS `{1: [37], 2: {37: v}, 3: 12}` switches it (answer
  `{1: 37}`); switching on the camera changes the same field. CONF calls it `accelerate_frequency`.
- **Auto picks its speed itself** and doesn't report it: lying still the camera ran at 15× (60 s → 4 s), handled
  next to a changing laptop screen at 5×. Options 165 reads `{2: 5}` during any TimeShift recording either way.
  The compressed length shown on the camera is not available over the protocol (capture status, notifications and
  all options/PhotographyOptions were watched during recordings); the file list has the real length after the stop
  (the file is often 1–2 s shorter than the camera's on-screen counter).

## Sleep

After the BLE and Wi-Fi links drop, the camera counts down (second byte of its advertising data) and sleeps within
~3 minutes. **CLOSE_CAMERA_WIFI (34) prevents that** — the camera then stays awake and drains; don't send it.

**During a recording the camera turns its Wi-Fi access point off once the phone leaves it**, and OPEN_CAMERA_WIFI
(33, answers 200) doesn't bring it back until the recording stops (then the phone rejoins in ~20 s). A client that
wants to stay in control while in the background should stop the live view (the main extra drain) but keep Wi-Fi;
BLE alone is enough to stop the recording.

## Safety

Some neighbouring codes are destructive: 24 ERASE_SD_CARD, 32 REBOOT_CAMERA, 25 CALIBRATE_GYRO. Blindly probing
SET commands changes the user's settings. Send DELETE_FILES only with exact uris from the file list, never an empty
list.
