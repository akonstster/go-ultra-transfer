# Handoff — highlight marks (27.10.2026)

For the next agent picking up this work. Branch `highlights`, on top of v0.8 (`818caba`).
Local, untracked notes with device serials, adb ids and every past finding: `CLAUDE.md` in the repo root (gitignored)
— read it first. Public protocol notes: [PROTOCOL.md](PROTOCOL.md) (section "Highlight marks in a file").

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
- Whether type 2 really is (start, end) — only one sample. Ask the user to add a second playback mark and read again.

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
