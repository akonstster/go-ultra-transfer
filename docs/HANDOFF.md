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
`ColorPanel` (tabs «Свет и цвет» / «Плёнка»): LUT on/off + strength (I-Log), exposure, saturation, sharpness, film (any video).
media3 `setVideoEffects` (media3-effect): the effect pipeline exists only if set before the player is prepared, so
every video player gets it. SDR effects see gamma-encoded BT.709 (Builder default WORKING_COLOR_SPACE_DEFAULT — the
javadoc's «linear» is stale), so the LUT gets I-Log as encoded. Exposure (stops, ×2^(ev/2.2) encoded) and saturation
(factor around Rec.709 luma) are one `LiveColorMatrix` (`ColorMath`, unit-tested) read per frame — sliders apply live;
LUT strength and the clipping stripes (`ClippingStripes`, own GLSL: max channel ≥ 0.98 red / ≤ 0.02 blue, diagonal
stripes) rebuild the effect list (the stripes are a check, not a setting: not saved, off whenever the colour panel
closes). Settings in prefs `look2_<name>` (the old `look_` held offsets). No frame after 5 s
of playing with effects → shown without them. Controls only on a tap; the video loops while the colour panel is open.
Sharpness: `Sharpen` (luma unsharp mask, 2 rings × 8 taps, threshold, capped). «Плёнка» tab: `FilmLook` (one GLSL
pass after sharpening, all uniforms live) = `FilmMath` (unit-tested; the shader takes its constants by template, so they
can't drift): warmth as a WB gain in light → per-channel print curve (log2 contrast ×(1+0.25·film) around 0.18, soft
exponential shoulder from 0.7, print black 0.0008) → density (light × (1 − 0.3·film·sat²)) → split toning in YCbCr
(teal in the low-mids, warm in the high-mids; black and white stay neutral) → skin: within ±25° of the 123° skin line
the shot's hue is kept (warmth and toning don't reach it) and pulled up to 30 % towards the line. Halation: 3 rings × 8
taps to 2.5 % of the height, randomly turned per pixel, red-orange, added in light before the curve. Grain: value noise
on a 1080-line grid, two sizes, new per frame (`presentationTimeUs`), weighted to the mid-tones. Checked by compiling
the shaders with glslangValidator and rendering them in headless Chromium WebGL on a test chart. `ColorLook` now holds
11 values (`decode` still reads the 4- and 6-value forms). While the panel is open the video keeps the top 45 %, the
sliders scroll, the top bar / ♥ column / chips hide and the pager is locked.
Preview weight (phone 4K lagged, then failed): while playing, `Presentation.createForHeight` first in the chain brings
the picture to 1080 lines (never upscales; the camera's LRV stays as is). `FilmLook.frames` counts frames drawn; 2-s
windows below 80 % of the format's frame rate (or a player error while playing) step down 1080 → 720 → 540 + light
halation / sharpening (`light` uniform: one ring of taps), logged «превью проще», label «упрощённо · 720p»; the colour
is dropped only after the last step. The size is known before the player starts (`videoShortSide`, phone copies),
so the effects aren't rebuilt as playback starts (a rebuild then left a swiped-to video without frames → «нет кадров за
5 с»); pause / play only flips the `light` uniform. Paused, media3 1.4.1 with effects draws no new frame, not even after
a seek to the same spot (and many 4K seeks hung the viewer): after a change (slider let go, panel opened / closed) the
player plays muted until `FilmLook.frames` moves (≤ 0.4 s) and pauses again. Zoom resets when the panel opens / closes
and when the page is swiped away.
«Стиль» tab (was «Плёнка»): `FilmMath.Grade` holds every step (balance, flash, face lift / sky / vignette, print
curve, density, silver-retention overlay, saturation per hue at 103° / 167° / 241° / 347° with cos⁴ weights, hue turns,
shadow / mid / highlight toning, skin); `Looks.kt` builds it from the sliders and a preset × strength. Presets are
models of published facts (ASC / AC articles, Codex, Technicolor, Apollo — sources in the KDoc), tuned by eye to one
still per film sent by the user; not calibrated against frame statistics yet. Skin protection is always on: the mask
is hue (full within ±15° of 123°, fading to ±45°) and colour relative to brightness (soft 0.04…0.12 up, 0.4…0.75
down), so dark and light skins alike — wide soft edges, narrow ones showed as stripes on faces under Amélie; «Ровная кожа»
pulls skin hues up to 60 % to the line. A preset can lower the guard (`skinGuard`, mixed by strength): Amélie has 0 —
faces go golden with the rest, as in the film, and guarding against so strong a grade showed its edges. `ColorLook` has 13 values (11-value saves drop the old skin protection value).
Shaders checked with glslangValidator and rendered in headless Chromium WebGL with the uniforms from `Looks.grade`.
Saving is one «Сохранить» (top bar for videos only) → `SaveDialog`: whole /
marked pieces × as shot / with my colour × sound on / off (+ for a camera file with colour: download the original first, or straight
from the camera). `CameraController.save` picks the way: whole as shot = download; pieces as shot = `saveClip`
(no re-encoding, starts on key frames, `…_HL`); with colour = `exportWithColor` (media3-transformer 1.4.1, `…_COLOR` /
`…_HL_COLOR`). Names via `saveName`; a taken name → replace / save separately. Export: `exportEffects` (the viewer's
effects in full, no preview size / stripes, sharpening radius × lines / 1080), the original's size, frame rate, codec
(H.265 if the original is, else H.264; the encoder factory falls back), bitrate (track, or file size / length; 20…200
Mbit/s). Whole: one item, sound copied. Pieces: one clipped item each in a sequence, cut exactly (re-encoding anyway);
the transformer makes the picture only (`setRemoveAudio`); the sound is joined by `ClipAudio` like a video
editor's crossfade: at each join the first piece's sound runs 0.1 s past its end fading out while the next one's
starts 0.1 s before its start fading in (constant power, window centred on the cut, narrowed to half a short piece;
`AudioJoin`, unit-tested: as long as the picture, exact inside pieces), 10 ms fades at the clip's ends; decoded with
MediaCodec from the original (phone copy or `session.clipSource`), encoded AAC-LC 256 kbit/s in memory, muxed with
the picture (`MediaMuxer`, interleaved by time, rotation kept) straight into the gallery item. Without sound (`_MUTE`): clips
leave the track out (`ClipMaker.join(withSound = false)`; the whole video as shot becomes such a copy instead of a
download), the transformer removes it, pieces skip `ClipAudio`.
with `DefaultMediaSourceFactory` over `CameraHttpDataSource` on the full file's URL (not `ProgressiveMediaSource`: it
ignores the items' clipping — the whole picture came out) (Range reads, only what is needed);
the codec info from `session.indexSource`. Via `cacheDir/export.mp4`, then `Downloader.saveClip`. Progress in percent
(own bar at the gallery's bottom and over the video); «Стоп» cancels (the download stage too). 8-bit
output: an I-Log file's 10 bits are not kept. The Insta360 trailer (marks) isn't carried over. Not on the phone yet.
Colour copy / paste: icons over the video while the panel is open; `copiedLook` in prefs (`look_copied`), pasted as
that video's look; in the gallery a selection gets a paste icon (`pasteLookToSelected`, videos only) and tiles with a
look of their own show a palette mark (`hasLook`, redrawn via `lookVersion`). A save replacing a file forgets its
thumbnail (`forgetThumbnail`; tiles also key on `localUri`). Saving a selection as a batch: to do.
The viewer's effect list no longer changes while playing (turning the I-Log switch rebuilt it and stopped playback):
`LiveLut` (the .cube as a 2D atlas texture, `CubeLut.atlas`, blue between two slices, red / green by the texture's
filtering — matches a CPU trilinear lookup of the real table within 0.9 / 255), strength live (0 = off); the stripes
(`ClippingStripes.on`) and before / after (`bypass` on each effect) are flags read per frame. Only a preview-size step
rebuilds the list.
Paused colour: `StillFrame` (GLSurfaceView over the player, `setZOrderMediaOverlay`) draws the frame at the paused
position (`FrameReader.exact`, OPTION_CLOSEST, ≤ 1920 wide, upright; phone copy or the camera's LRV) through the
player's own effect objects as `ColorStep`s (shader + `bind`; media3 runs them via `StepProgram`), ping-ponging two
FBOs, redrawn when `LiveEdits.version` moves. The player draws nothing new while paused, and a frame caught half-way
through its chain mixed old and new settings (before / after looked random: «то только стиль, то весь LUT»). The muted
nudge stays only for a new player view (panel opened / closed) and an effect rebuild.
Files added from the phone: the «Добавить с телефона» icon (Files tab) opens the system file picker
(OpenMultipleDocuments, video/* and image/*); the app keeps the URIs (prefs `added_files`) with a persistable read
permission — nothing copied. `CameraFile.ADDED_PREFIX` uris (`isAdded`), names kept apart («name (2).ext»), listed with
the app's folder (`localFiles`), a folder badge; everything else (viewer, pieces, colour, save) as for phone copies.
Video kinds by extension (mp4, mov, m4v, 3gp, mkv, webm). A file whose permission is gone drops off; «Убрать из списка»
on a selection of added files only forgets them. Filters: all / photos / videos, then ♥, added, own colour, saved
(…_HL / _COLOR / _MUTE) — shown when not empty. Thumbnails of picked files fall back to a frame / a small decode.
Batch save: a selection's bottom bar has a save icon → `BatchSaveDialog` (whole / each one's pieces, as shot / each
one's own colour, sound) → `saveBatch` runs `save` per video in turn (joins its job), skips videos with nothing to
save, takes the next free name (never overwrites); «пачка i из n» on the bars, «Стоп» stops the batch.
Gallery tiles of videos with a colour of their own show it: `tileImage` colours the thumbnail on the CPU (CubeLut.apply
trilinear, ColorMath, FilmMath — no sharpening / halation / grain at that size), cached per look; tiles redraw on
`lookVersion`.
Performance review (phone laggy): tiles redraw only when their own video's colour changes (`lookKeys`, an in-memory
name → look map, instead of a global counter that made every tile reload on each slider release); the colour is baked
into a 17³ table once per look (`bake`) and thumbnails looked up in it without allocation, on at most two cores,
cancelled with the tile; coloured tiles cached within 24 MB, one per file; filter counts and the selection are derived
state. The paused still keeps one GL view for the whole pause (a new frame is swapped in), redraws on `LiveEdits` (a
StateFlow, not a 16 ms poll), decodes one frame at a time after seeks settle (300 ms), and its frames no longer count as
the player's. The still is no longer turned twice (the retriever turns frames by the video's rotation already).
Added files: name, size, length fixed once when added (prefs file `added`), so the list comes up without reading the
files; a file drops off only if it is gone or its permission was taken away; changes run one at a time. A batch
counts only saves that got into the gallery.
Poco reboot (logcat -b crash): system_server died in `WifiNetworkFactory$ConnectionTimeoutAlarmListener.onAlarm`
(NPE on `mUserSelectedNetwork.BSSID`) — an Android 12 bug when an app takes back a `WifiNetworkSpecifier` request while
the system is still connecting. `CameraWifi` now takes a request back only after its answer (onAvailable /
onUnavailable); one dropped before that is marked abandoned and taken back when the answer comes; a new request waits
for the previous one's answer.
Review fixes (after a Poco reboot while testing): a page's player prepares only when the page is on screen; the
paused-frame nudge leaves a released player alone; the joined sound goes to `cacheDir/export.aac`, not the heap;
codecs / extractors are released when setup fails; temp files are cleared at start; `lut()` parses once (Mutex) and
fast; a LUT that fails to load plays the video as shot; pieces are clamped to the video's real length; «i» counts
frames only for phone files (from the camera the extractor would read the frames' data over Wi-Fi); the
notification's «Стоп» also stops a save with colour. Still: every video player carries the effect chain even with a
plain look (the effects must exist before prepare for live sliders).
«i» for videos: `streamInfo` (MediaExtractor on the phone copy, or the camera's index over Wi-Fi) — video codec, 8 / 10
bit (codec profile), its bitrate; frame rate counted from frame times over the first 2 s; sound codec, kHz, channels,
bitrate or «нет»; the whole file's bitrate from the retriever.

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

See `CLAUDE.md` → "Сборка Android" (JDK 17 path, `gradlew.bat assembleDebug`, adb install). `CLAUDE.md` lives only on
the user's PC (gitignored), so cloud sessions don't see it: the steps to give the user (PowerShell, repo root
`C:\Users\nonroot\myprojects\go-ultra-transfer`; adb is **not** on PATH):
```
git pull
cd android
.\gradlew.bat assembleDebug
& C:\Users\nonroot\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```
The debug phone is a
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
