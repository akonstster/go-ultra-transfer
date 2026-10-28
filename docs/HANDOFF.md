# Handoff — highlight marks (27.10.2026)

For the next agent picking up this work. Branch `highlights`, on top of v0.8 (`818caba`).
Local, untracked notes with device serials, adb ids and every past finding: `CLAUDE.md` in the repo root (gitignored)
— read it first. Public protocol notes: [PROTOCOL.md](PROTOCOL.md) (section "Highlight marks in a file").

## Status (end of 27.10)

**Done and tested on the Poco:** marks are read and shown in the viewer (`ui/FileViewer.kt` → `VideoPreview`).
- `camera/Highlights.kt`: `parse()` (record → marks, ms from start) and `pieces()` (the camera's film-strip rule:
  a recording mark → 1 s either side, clipped; playback segments as they are; overlapping pieces merged). Tests:
  `app/src/test/.../HighlightsTest.kt` (real records). A value below first_frame counts as **0** — verified: video 119
  then gives pieces 0–1 s and 1.52–6.88 s, exactly what the camera shows (1 s and 5.3 s). (The notes below that call
  it "relative 1.32 s" are superseded.)
- `CameraTcp.fileExtra(uri, type)`, `CameraSession.highlights(file)`, `CameraController.highlights(file)`;
  `CameraFile.firstFrameUs` (metadata field 24).
- Viewer: whole video plays by default; yellow ticks (media3 `setExtraAdGroupMarkers`) at piece starts; a flag chip
  with the piece count toggles the **film strip**: only pieces play (gaps skipped, 300 ms slack for key frames), after
  the last one it returns to the first and pauses; the current piece's chip is yellow; tapping a chip jumps there.

- Film strip at the bottom (after v0.9, tested on the Huawei): a frame per piece (its middle, nearest key frame)
  with its length and start; current piece framed in yellow, auto-scrolled; tap → jump. Frames come from the
  LRV on the camera via `camera/RemoteFrames.kt` — MediaMetadataRetriever over HTTP Range (256 KB blocks), so only
  the index and a few key frames are read; loaded once, when the strip is first opened.

**Step 1 of 3 — done, works on the phone (27.10):** the Files tab without the camera lists our gallery items
(`Downloader.phoneFiles`, `CameraFile.localUri`, `CameraController.phoneFiles` / `showsPhone`) under a small connect
bar; a video opens in our viewer from the phone, marks from its own trailer (`camera/LocalTrailer.kt`: the id-0 table
of `[u16 id][u32 size][u32 offset]`, or a walk back through `data + [u16 id][u32 size]` footers, as ExifTool does;
unit test with synthetic trailers), film-strip frames via `RemoteFrames.local`. Photos still open in the phone's
gallery.

**Step 2 — done:** with the camera connected, a downloaded video also opens from the phone
(`CameraController.withPhoneCopy`: the camera item plus `localUri`); its marks still come from the camera first
(they may have been edited there after the download), the phone's trailer is the fallback.

**Step 3 — done, released in v0.10 (27.10, tested on the phone by the user):**
- Editing (`ui/FileViewer.kt` → `TimelineEditor`, replaced the RangeSlider editor): a video-editor timeline under
  the picture — frames along the whole video (key frames of the phone's copy or the camera's LRV, `FrameReader` kept
  open, tiles at multiples of 2 s, cached in `CameraController.timelineFrame`), a fixed white playhead in the middle;
  drag the strip to scrub (the player follows), pinch to zoom (whole video … 1.5 s per screen). Pieces are yellow
  bands with bold edges and a triangle inside each edge at the top (pieces that meet stay readable); frames are
  separated by a dark gap. Tap selects, the selected one gets handles (start snaps to the nearest key frame — or onto
  the end of the piece before, `KeyFrames.nearestStart`; ticks on top; end free unless B-frames; neither crosses a
  neighbour). Pieces that meet are joined before cutting (`Highlights.joinTouching`): one piece, no seam. «+ Кусок» at the playhead, «Удалить», «Как на камере», «Готово».
  The film strip of piece cards stays for playback mode. Edits: SharedPreferences `pieces`, key = file name,
  `start-end,…` ms, "" = all removed (`Highlights.encode/decode/normalize`; edited pieces that only touch stay apart,
  the camera's marks keep the camera's rule — touching ones merge); the camera file and its phone copy share them.
- «✂ Обрезать» chip next to the flag (the most wanted use): one piece over the whole video, selected in the timeline,
  so its handles trim the start and end; with pieces already there it asks — replace them, or keep and edit them.
  The trimmed clip is «Только отмеченное» / «Клип» (one piece: the sound is copied as is). A sound crossfade at seams
  was considered and dropped (it would need re-encoding the sound) — the user decided against re-encoding.
- «Скачать» on a video with pieces → «Целиком» / «Только отмеченное». A downloaded one gets «Клип» instead.
- `camera/ClipMaker.kt`: MediaExtractor → MediaMuxer, no re-encoding; each piece from the key frame at/before its
  start (sound from there too) to the first frame at/after its end — a key frame only if B-frames are seen (first
  version ended on key frames too: three short pieces of a 10 s video came out 8 s long); a piece whose key frame lies
  in what was already written carries on from the previous end. Video + first audio track only. The log line
  `клип …: отмечено …; вырезано …` shows how far the starts moved — i.e. the camera's key-frame spacing. `cut()` is pure (a `Samples` interface) and unit-tested on a made-up 30 fps file
  (`ClipMakerTest`). From the camera: `CameraSession.clipSource` → `CameraRangeSource` (2 MB Range blocks, strict:
  a failed block throws instead of ending the clip). Saved by `Downloader.saveClip` as `<stem>_HL.mp4` in
  DCIM/Camera (pending while written, deleted on failure/«Стоп»); runs as a download job (bottom progress, «Стоп»).
- Key frames in the editor (`camera/Mp4Index.kt`, `KeyFrames`): read from the mp4 index alone (stss/stts/ctts/elst)
  of the full-quality file — the phone's copy, or the camera's original over Range (not the LRV, its key frames may
  differ). `MediaExtractor.seekTo` would read a whole key frame per seek — hundreds of MB for a long video from the
  camera. Times are rounded up to ms so a start set on a key frame never falls before it. The start handle snaps to
  the nearest key frame (user's choice: nearest — we can't tell how far a mark missed one), «+ Кусок» too; the end
  is free unless ctts shows B-frames. Ticks over the scale show the key frames. The scale is the piece ± 5 s, so a
  long video is edited at the same precision as a short one. `saveClip` snaps every piece's start the same way before
  cutting (camera marks never edited too), so the clip matches the editor's rule; ClipMaker counts a frame within the
  last ms as reaching the end (key frame times are rounded up to ms).
- Assumed, not verified: the camera's LRV (played while editing a camera file) and the original share a timeline; the
  platform extractor applies the elst shift the same way as `Mp4Index` (both 0 on most camera files).
- Unknowns to check on the phone: audio at the piece joins, B-frames (if the GO Ultra uses them), rotation.

**Live marks during a recording — not available (checked 28.10, fw 1.9.29):** the capture status reply
(GetCurrentCaptureStatus over BLE) carries only fields 1 state, 2 seconds, 10 phase — no keyTimePointDetail (field 4
in the proto) even with marks set from the app; the marks appear only in the finished file. Over Wi-Fi the same
request's reply didn't show the recording at all — the status stays on BLE. Each mark from the app is logged
(«отметка на N с: поставлена»). JPG + DNG stay separate tiles (user's choice).

**Colour for I-Log (viewing only, 28.10):** I-Log is told by the file itself: ExtraMetadata field 22, a string —
«standard» / «I_Log» (compared on two real files 28.10; the proto's 212.8 color_mode is not sent by the GO Ultra, kept
as a fallback) —
from GetFileInfoList for camera files (`CameraFile.iLog`), from the trailer's 0x0101 record for phone copies
(`LocalTrailer.Info.colorMode`, logged «цвет …: режим N»). I-Log videos get Insta360's «GO Ultra I-Log to Rec.709»
(app assets, `luts/`, 33³, from Resolve; `CubeLut`) automatically; other videos no LUT. The «I-Log» / «Цвет» chip →
`ColorPanel`: LUT on/off + strength (I-Log), brightness, saturation (any video), per file in prefs (`look_<name>`).
media3 `setVideoEffects` (media3-effect): the effect pipeline exists only if set before the player is prepared, so
every video player gets it. SDR effects see gamma-encoded BT.709 (Builder default WORKING_COLOR_SPACE_DEFAULT — the
javadoc's «linear» is stale), so the LUT gets I-Log as encoded. Exposure (stops, ×2^(ev/2.2) encoded) and saturation
(factor around Rec.709 luma) are one `LiveColorMatrix` (`ColorMath`, unit-tested) read per frame — sliders apply live;
LUT strength and the clipping stripes (`ClippingStripes`, own GLSL: max channel ≥ 0.98 red / ≤ 0.02 blue, diagonal
stripes) rebuild the effect list. Settings in prefs `look2_<name>` (the old `look_` held offsets). No frame after 5 s
of playing with effects → shown without them. Controls only on a tap; the video loops while the colour panel is open.
Exporting with colour would need re-encoding (Transformer) — not done; 10-bit precision would be lost there.

**Next ideas** (not started): marks in downloaded files (parse the local trailer); export /
download only the highlights; check `keyTimePointDetail` in the capture status for live marks.

## Goal of this step

The camera lets the user put highlight marks on a video (flag button while recording — in our app, the official app
or on the camera — and later in the camera's playback). We want to **show those marks in our viewer** ("film strip")
and let the user jump to them. Until today we didn't know where they were stored. That is now solved.

## What was found (verified on a GO Ultra, fw 1.9.29, 12 videos)

- Marks live **inside the mp4**, in the Insta360 trailer, record **0x000a** (`EXTRA_TYPE_HIGHLIGHT`). Trailer record
  ids are the `ExtraType` enum; the full official proto is in `iliakonnov/insta360-nas`
  (`insta360-server/insta360_messages.proto`, raw.githubusercontent.com works with curl).
- The camera returns the record on request: **GetFileExtra, code 11, body `{1: uri (string), 2: 10}`**, reply code 200,
  body = the raw record (not protobuf). **Wi-Fi (TCP 6666) only** — over BLE the camera replies with a different
  message (24579) and the request times out. This is the same command we already use for thumbnails (type 2).
- Record layout: blocks `[type u8][count u32 LE][values u64 LE]`, values in **µs on the camera clock**.
  - No marks → 8 empty blocks: types 1, 2, 3, 4, 0x10, 0x11, 0x12, 0x15, each with count 0 (40 bytes).
  - **Type 1** = marks made while recording; one u64 each.
  - **Type 2** = marks added in the camera's playback; **two u64 each (start, end)**. The only sample: 1.76 → 3.76 s.
  - After an edit in playback the record contains only types 1 and 2 (26 bytes in the sample) and the file's
    metadata (trailer record 0x0101) gains field 87 `bHighLightEidted` = 1 and 90 `edit_version` = 1.
- Converting to "seconds from the start of the video": `(value − first_frame_timestamp) / 1e6`, where
  `first_frame_timestamp` = **metadata field 24** (int64, µs). We already read the metadata for every file via
  GetFileInfoList (38) — `ExtraMetadata`, field 24 is there (field 10 = `total_time`, s).
  - A value **smaller than `first_frame_timestamp`** is already relative µs. Seen 3 times, always exactly `1320000`
    (1.32 s), always the first mark in videos marked from our app. The camera's own timeline confirms it is a real
    mark at ~1.3 s. Why our first mark lands there exactly is **not known** — see "Open questions".
  - The camera groups nearby marks into one piece on its timeline (e.g. 1.32 + 2.52 shown as one, 4.36 + 5.88 as
    another).
- Sample results (seconds from start): 102 (54 s long) → 3.64, 10.92, 15.48, 20.28, 37.84, 44.04; 066 (12 s) → 1.80,
  4.52; 119 (9 s) → 1.32, 2.52, 4.36, 5.88; 120 (4 s) → type 2: 1.76–3.76.
- Dead ends (don't repeat): GET_EDITINFO_LIST 201 = favourites / AI-analysis state per file, not marks;
  PLAYBACK_GET_HIGHLIGHT_INFO 203 with `{1: uri}` → 400; GetFileExtra types 0, 16, 18, 21 → empty; the SD card
  as a USB drive shows no change after adding marks (the trailer is rewritten in place). The official app keeps marks
  edited on the phone in its own database (Realm) and never writes them to the camera, so phone and camera can disagree.
- Live, during a recording: `CaptureStatus.keyTimePointDetail` (string, field 3/4 depending on the message) should
  carry the marks — **not checked**.

## What is on this branch (all small; nothing user-visible except the snackbar)

- `ui/GalleryScreen.kt` — snackbar moved out of the Scaffold: top-centre under the tabs in portrait (150 dp), centre
  and rotated with the picture when the phone is sideways (it used to cover the recording controls). Tested.
- `camera/CameraSession.kt` — debug only:
  - `rawCommand(code, body)` now goes **over TCP only** (returns "нет Wi-Fi" otherwise), logs the full reply
    (`hex(100_000)`), allowlist `{15, 166, 171, 242, 201, 203, 11}` — **read-only commands only, keep it that way**.
  - `debugNewestVideoInfo(nameContains)` — metadata of one video, field by field.
- `ui/CameraController.kt` — `debugVideoInfo(nameContains)`.
- `MainActivity.kt` — intent `--ez videoinfo true [--es video 135146]`.
- `docs/PROTOCOL.md` — new section; this file.

Debug intents (debug build only; `adb shell am start -n app.goultra.transfer/.MainActivity …`), results go to
`/sdcard/Android/data/app.goultra.transfer/files/log.txt` (lines start with `DEBUG`):
- `--es cmd 11:<hex>` — e.g. highlight record: hex = `0a` `<len>` `<uri ASCII hex>` `100a`.
- `--ez videoinfo true --es video <part of name>`; `--es fetch /DCIM/Camera01/<file>` (copies a file to
  `Android/data/app.goultra.transfer/files`, whole file in memory — keep it to small files).

## Next task: marks in the viewer

Suggested design (agree with the user first — they review UI decisions; see their preferences below):
1. `CameraSession.highlights(uri): List<Highlight>` — GetFileExtra(uri, 10) over TCP (`tcpLock` like
   `fileThumbnail`), parse the blocks, convert with the file's field 24 (already in the file-info metadata we fetch
   for the list; check `GetFileInfoList` parsing in `CameraSession`/`Protocol.kt` — field 24 may need adding).
   `Highlight(startMs, endMs?)`: type 1 → point, type 2 → range. Values < first_frame → relative. Drop values past
   the duration. Put the parser in `Protocol.kt` and **add a unit test** in `app/src/test/.../ProtocolTest.kt` with
   the real bytes above (empty 40-byte record, the 26-byte type-2 record, a 4-mark type-1 record:
   `01 04 00 00 00 40 24 14 00 00 00 00 00 e7 e6 a5 11 00 00 00 00 67 fa c1 11 00 00 00 00 e7 2b d9 11 00 00 00 00`
   followed by the 7 empty blocks, with first_frame 293565223 → 1.32, 2.52, 4.36, 5.88 s).
2. Viewer (`ui/FileViewer.kt`, media3 player for camera videos via LRV/HTTP): ticks on the seek bar (ranges as a
   segment), tap a tick / "next mark" button → seek. Request the record when a video opens, only when connected over
   Wi-Fi; no marks → nothing shown. Optional: a small flag badge on grid tiles (would need one request per video —
   probably lazily, cached with the thumbnails).
3. Downloaded files keep the trailer, so the same parser can read marks from a local mp4 (last ~2 MB; parse the
   trailer index — **don't stop at the first empty index slot**).

## Open questions

- **Answered (27.10, test video 121, 13 s):** the camera stamps a mark with **its own clock when SET_KEY_TIME_POINT
  arrives**; the value we send (`{1: whole seconds since start × 1000}`) is not stored. Taps at ~0:05 and ~0:10 on our
  timer → 4.36 s and 10.12 s in the file (our timer starts ~0.6 s before the first frame). No `1320000` this time —
  that value appeared only in three earlier recordings (098/099/119), origin unknown (possibly marks sent over BLE in
  an older build); just treat values < first_frame as relative.
- **Answered (27.10, test video 122, 10 s):** type 2 is (start, end). Two marks added in the camera's playback at
  ~3 s and ~7 s → `02 02 00 00 00` + 4 × u64 → 2.68–4.68 s and 6.93–8.93 s: each playback mark is a **2-second
  segment** starting slightly before the chosen point. Metadata got 87 = 1, 90 = 1 again.

## How to build / test

See `CLAUDE.md` → "Сборка Android" (JDK 17 path, `gradlew.bat assembleDebug`, adb install). The debug phone is a
Poco X3 (Android 12); the target is a Huawei Mate 80 Pro (logcat doesn't show app logs there — use log.txt).
The camera must be connected **from our app over Wi-Fi** (live view on the "Камера" tab, or the file list) for TCP
commands; the camera closes TCP when the user opens playback on the camera itself, and our app disconnects after
3 min in the background.

## Rules from the user (keep them)

- Never send dangerous commands: FORMAT_STORAGE 232, USBCARD_FORMAT 134, EXEC_CMD 81, ERASE_SD_CARD 24,
  RESTORE_FACTORY_SETTINGS 57, REBOOT 32, CLOSE_CAMERA_WIFI 34. No blind probing of unknown command codes or
  settings. DELETE_FILES only with exact uris from the list, never an empty list.
- Camera over USB adb (RT-Thread shell) is **read-only** — see the forbidden list in `CLAUDE.md`.
- Don't commit personal data (serials, MACs, adb ids, Wi-Fi password): `git grep` before committing.
- Don't confirm security prompts on the phones yourself; look at the phone screen before tapping.
- Diagnostics UI/logs: Russian only, debug build only. User UI: RU/EN via `tr()`.
- Settings layout stays stable: fixed groups/tiles, grey out instead of hiding.
- Discuss before changing behaviour; the user tests on the phone and reports back.
