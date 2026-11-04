# GO Ultra Transfer

A small open-source Android app for the **Insta360 GO Ultra** camera — an alternative to the official app when you
just want your files and control of the camera: **no account, no ads, no cloud**.

It wakes the camera over Bluetooth (even a switched-off one), joins the camera's Wi-Fi on its own, lists the files
with previews, plays or opens them straight from the camera, downloads them to the phone's gallery (in the
background, with resume), deletes them from the card, shows a live preview (full screen with the phone turned
sideways), records and takes photos, keeps control of a recording while the phone's screen is off (Stop in the
notification), switches the TimeShift speed while recording, has a battery-saving mode for long recordings, shows the
space left on the card and exposes **all** of the camera's shooting settings for its 12 modes. It shows the camera's
highlight marks, lets you edit those pieces on a timeline or trim a video, and saves just those pieces as one clip
**without re-encoding** — reading only the needed bytes from the camera. Without the camera it lists what is already
on the phone. The interface is in **10 languages**, light or dark.

The protocol was reverse-engineered; see [docs/PROTOCOL.md](docs/PROTOCOL.md). Not affiliated with Insta360.

**Interface languages:** Русский · English · Français · Italiano · Español · Deutsch · Português · Türkçe ·
中文（简体）· 日本語 — short summaries at the [bottom of this page](#short-description-in-other-languages).

## What it does

**Connection**
- One button: wakes the camera over Bluetooth (even switched off) and connects to its Wi-Fi by itself.
- If Bluetooth or Wi-Fi is off on the phone, the app offers to turn it on and carries on. While the camera is not
  connected it shows a hint about a VPN the app can see (not shown for a connected camera, where it does no harm).
  The hint can be hidden; it comes back on the next VPN switch-on or connection request.
- After you leave the app it keeps the link for another 3 minutes, then lets the camera go — it falls asleep by
  itself. While downloading, exporting or recording, the automatic disconnect does not fire.
- It remembers its camera: if several Insta360 cameras are nearby, setup offers a choice, and afterwards the app
  connects to that one only.

**Files**
- Previews, grouping by day, the «photo / video / ♥ favourites» filters, and a mark on files already downloaded.
- The «Camera» filter shows only the files on the camera's card while connected, including those already downloaded
  to the phone; files left only on the phone are hidden. After disconnecting, the phone's own list comes back.
- **Tap** — the file opens: a downloaded video or photo in the app's viewer from the phone, one still on the camera
  straight from the camera (video as a light low-quality copy, instantly; photos in full). In the viewer: pinch to
  zoom, swipe up/down to the next file, ♥ favourite (and the «♥» filter in the list), «i» — file details (EXIF,
  resolution, bit rate…), «Share». **Long-press** — select (on the phone, to share several at once).
- With the camera connected the list also shows what exists only on the phone (`…_HL.mp4` clips, files deleted from
  the camera) — marked with a phone icon. A clip whose name is taken: «Replace» or «Save separately» (`…_HL2.mp4`).
- Downloading into the gallery (DCIM/Camera) in the background, with progress and resume after a drop, without
  duplicates. When replacing, the old file is kept until the new one is complete. If the app is interrupted during
  the replacement itself, a backup copy with `.backup-…` in its name may stay in the gallery — check and delete it
  by hand.
- Live photos (LIV) — one «LIVE» tile; they are downloaded as a single file in the open
  [Android Motion Photo](https://developer.android.com/media/platform/motion-photo-format) format. When an `.lrv` is
  present the video holds two tracks: a light one for viewing and the full-size one for picking a cover. The camera's
  original MP4 is kept whole inside the file, service data included; the file is therefore bigger. If the `.lrv` is
  missing, the original video is saved. On Huawei phones a gallery trailer is written as well — MediaStore then flags
  the file as a live photo. The Poco gallery (MIUI 14) does not support live photos made by third-party apps (per
  Xiaomi's documentation); there the file stays in the plain Google format. New downloads end in `_MP.jpg`.
  Opening a live photo plays it once in the app, then shows the cover; the clip can be replayed. A downloaded photo
  plays without the camera too, and any frame of the original clip can be made the cover. On replacement the original
  photo is kept inside the file and can be restored without the camera. If the cover was replaced by an older version
  and the original was not kept, the camera is needed to restore it. If the downloaded copy was deleted or moved to
  the bin, the cover-restore button is hidden: bring the copy back from the phone's bin or download it again. In
  «Details» the resolution belongs to the current cover; the saved EXIF shooting parameters belong to the original
  photo. Older downloads stay usable: to add the light track, download the photo again with replacement. If no
  suitable hardware decoder is available the app tries a software one, and on failure the preview available from the
  camera. Other apps may drop the embedded originals while processing the file, so keep a copy before editing it in
  third-party editors. The track sync is checked at build time; if it is broken, the download reports an error
  instead of replacing the previous file. Per
  [Xiaomi's documentation](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2003), MIUI 14 does not recognise
  live photos assembled by third-party apps; on HyperOS support also depends on the gallery version and the way the
  file was made.

- Marks (flags) set while recording or in the camera's own viewer are yellow ticks on the video's timeline. The flag
  in the viewer turns on the «film strip», like on the camera: at the bottom, frames of the marked pieces with their
  lengths (2 s around each mark, close ones merged), only they play, the rest is skipped; tapping a frame jumps to
  that piece.
- Colour: in the video viewer — I-Log → Rec.709, exposure, saturation, sharpness and «Look» (film presets, grain,
  halation, vignette). For photos and live photos (RAW excepted) — the same colour without the LUT, the same screen
  and the same buttons (copy / paste the colour, before / after, «Done»), the picture is drawn by the same GPU
  processing. Tiles and the «My colour» filter take photos into account. «Save» → «With my colour» writes a
  `…_COLOR.jpg` copy (for a live photo `IMG_…_COLOR.jpg`, without the clip) with the original EXIF, at most 4096
  pixels on the long side.
- Without a camera the «Files» tab shows the files in `DCIM/Camera` (grouped by day, with previews; to also see what
  a previous installation saved, the app asks for photo and video access — once). A downloaded video — with or
  without the camera — opens in the same viewer from the phone, at full quality, with the marks and the film strip.
- Pieces are edited like in a video editor: a film strip of the whole video with a line in the middle (drag to seek,
  pinch to zoom), the pieces as yellow bars with handles; the start snaps to a key frame. «+ Piece», delete,
  «As on camera».
- A video shot in I-Log is shown in colour right away — with Insta360's «GO Ultra I-Log to Rec.709» table (the mode
  is read from the file itself). The «I-Log» / «Colour» button: LUT on/off and its strength (I-Log), exposure in
  stops and saturation (any video, visible while the slider moves), sharpness with its radius, clipping (red) and
  crushed-blacks (blue) stripes. The «Look» tab: presets following film colour-grade descriptions — «Private Ryan»
  (silver-retention processing at 70 %, flashed negative), «O Brother» (dry yellow-green, almost sepia), «Amélie»
  (81EF filter, gold without blue), «The Revenant» (muted and cold, faces lighter, sky and edges darker) — with a
  0–100 % strength; «Film» (print curve, dense colour, split toning), «Cold ↔ warm», «Even skin», «Grain»,
  «Halation» (a glow around bright areas). The colour can be copied and pasted into another video. The video sits on
  top and loops while editing, the sliders scroll, the unneeded buttons are hidden. The preview is computed at no
  more than 1080 lines, and if the phone cannot keep up it goes simpler («simplified · 720p») instead of erroring.
  While paused, a change is visible once the slider is released (the video plays for a moment without sound). The
  settings are remembered per video; the file itself is not changed.
- Videos and photos from any folder on the phone can be added to the list (the «Add from the phone» button in the
  «Files» tab): they are not copied but viewed, cut, coloured and saved like the app's own — and without the camera.
  «Take off the list» merely forgets them. Filters: all, photos, videos, ♥, added, with my colour, saved.
- «✂ Trim» on a video: the whole video as one piece with handles at the start and the end — trim, then «Save». The
  edits are kept on the phone.
- One «Save» button for a video: «Whole video» or «Marked pieces only», «As shot» or «With my colour», with sound or
  without (`_MUTE`, faster). Marked pieces as shot — the `…_HL.mp4` clip without re-encoding (a piece starts at the
  key frame before it; only the index and the pieces are read from the camera). With colour — re-encoding at the
  source size, with the source codec where possible (`…_COLOR.mp4`, `…_HL_COLOR.mp4`): for the whole video the sound
  is copied as is, the pieces are cut exactly at the marks and the sound at the joins is crossfaded 0.2 s, like in a
  video editor. For a file on the camera with colour: download the original first, or go straight from the camera
  (only what is needed). If the name is taken — replace or save separately. The requested bitrate is 130 % of the
  source (within 20–200 Mbit/s); the phone's encoder may pick another bitrate or codec.
- Deleting the selected files from the camera (the button at the top, with confirmation).

**Camera**
- The live view on top, the settings under it — changes are visible in the frame at once. The window takes the
  frame's shape (16:9, 4:3, 2.35:1); in FreeFrame the future 16:9 and 9:16 crops are marked out, like on the camera.
- Phone sideways — the live view goes full screen, with a record button.
- Recording and photos from the phone, a self-timer with a countdown, «wake up and shoot at once» — a home-screen
  shortcut.
- Recording with the phone's screen off: the live view stops (it saves the camera's battery), the link stays, and
  the notification (and the lock screen) carries a «Stop recording» button.
- «Save battery» during a recording (the button next to the shutter, with a warning): the live view and the camera's
  Wi-Fi are switched off until the recording ends — for long timelapses. The timer and «Stop» work over Bluetooth,
  and after the stop the app rejoins the Wi-Fi by itself.
- TimeShift: switch «Auto / Normal» right during a recording; after the stop — the length of the resulting clip.
- While recording: pause (and changing exposure, white balance, colour, field of view and more while paused), ⚑ marks
  for important moments (the camera shows them as a «film strip»), a photo without stopping the video, ×2 zoom.
- Space left on the card: recording minutes (or the number of photos) and the free space.
- All 12 modes: video, PureVideo, slow motion, timelapse, TimeShift, loop, FreeFrame, «toddler titan» (baby view),
  photo, burst, interval, Starlapse.
- Settings in groups, like the camera's own menu: resolution and frame rate, field of view, FlowState, horizon lock,
  anti-blur, HDR, LOG, 11 filters, sharpness, portrait mode, exposure (auto / manual: ISO, shutter, EV), white
  balance, metering, ×2 zoom, RAW, live photo, burst, interval, duration and more. The allowed values come from the
  camera's own capability description, so unavailable choices are never offered.
- Camera and Pod battery with a charging indication.

**The app**
- Ten interface languages — Russian, English, French, Italian, Spanish, German, Portuguese, Turkish, Chinese
  (simplified) and Japanese. By default the phone's language; the choice is a dropdown list. Light and dark theme (or
  the phone's) live in the same place — menu ⋮ → «Settings».
- Menu ⋮ → «About»: version, build number and type, build date and time in the phone's time zone. The number changes
  with every APK build.
- Menu ⋮ → «Camera»: connection state, model, firmware, serial number, camera and Pod battery, memory card;
  disconnect / connect.

## Requirements

- Android 10 or newer. The app uses no Google services. Tested on a Huawei Mate 80 Pro and a Xiaomi Poco X3 NFC.
- Insta360 GO Ultra. Tested with firmware v1.9.29.

## Installation

Download the APK from the [Releases](../../releases) section and install it (you may need to allow installing from
unknown sources).

On the first run the app walks you through:
1. Switch the camera on with its button and put it next to the phone.
2. Confirm the **pairing** — Android shows the request as a notification in the shade.
3. Confirm the **connection on the camera's screen**.

After that there is no need to switch the camera on: the app wakes it by itself. If the camera woke up with its
screen off and asks for confirmation, press its button to switch the screen on.

## Good to know

- On the first run the app asks for **photo and video access** (the gallery): it is needed to see the files already
  downloaded, including those saved by a previous installation of the app, and to mark «downloaded» correctly.
- A **VPN** on the phone may get in the way of the camera's Wi-Fi. If the camera does not connect, turn the VPN off
  or add the app to its exceptions.
- While the live view runs on the «Camera» tab, the **camera's own menu is locked**. To change settings with the
  camera's buttons, switch to the «Files» tab.
- The photo self-timer is counted by the app: the camera applies its own timer only to its own button.
- Normally the camera's Wi-Fi is used by the app alone and the rest of the phone stays on its internet network. If
  binding individual sockets does not work, the app binds its whole process to the camera: in that mode the app
  itself has no internet.
- In «Auto» TimeShift picks the speed-up itself (from 5× to 15× and more, following what happens in the frame) and
  does not report it, so the app does not show the compressed time while recording — only after the stop.

## Building from source

You need JDK 17 and the Android SDK (compileSdk 36).

```sh
cd android
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
./gradlew testDebugUnitTest    # protocol-parsing tests
```

The finished APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
A debug build adds «Diagnostics» and «Settings self-test» to the menu (it walks every mode and value, writes and
reads them back, then restores the settings).
The release build (`assembleRelease`) is currently signed with the debug key — use your own for your releases.
The unit tests cover the protocol, the media work and the saving logic; MediaStore, Bluetooth, Wi-Fi and export
additionally need checking on a real phone and camera.

## Project layout

| Path | What is there |
|---|---|
| `android/app/src/main/java/app/goultra/transfer/camera/` | the camera protocol: BLE, Wi-Fi, TCP 6666, HTTP, settings |
| `android/app/src/main/java/app/goultra/transfer/ui/` | the screens (Jetpack Compose) and the app state |
| `docs/PROTOCOL.md` | the GO Ultra protocol as recovered during development |
| `camera_conf/` | the camera's capability description (`/CONF`, firmware v1.9.29) |
| `ble/` | Python scripts (bleak) used to explore the protocol from a PC |

## Credits

The Insta360 protocol is partly described in open projects, which helped a lot:
[RigacciOrg/insta360-wifi-api](https://github.com/RigacciOrg/insta360-wifi-api),
[xaionaro-go/insta360ctl](https://github.com/xaionaro-go/insta360ctl),
[Daiki-Iijima/insta360-go-ultra-sdk](https://github.com/Daiki-Iijima/insta360-go-ultra-sdk).
No code from those projects is used — only the protocol knowledge.

## Disclaimer

This project is not affiliated with Insta360 and is not endorsed by it. «Insta360» and «GO Ultra» are trademarks of
their owners. The protocol was recovered by observation; use at your own risk.

## License

[MIT](LICENSE)

## Short description in other languages

### Русский

**GO Ultra Transfer** — простое Android-приложение для камеры **Insta360 GO Ultra** вместо официального: без
аккаунта, рекламы и облака. Будит камеру по Bluetooth и сам подключается к её Wi-Fi, показывает файлы с превью,
проигрывает и открывает их прямо с камеры, качает в галерею телефона (в фоне, с докачкой), удаляет с карты.
Есть трансляция (на весь экран, если повернуть телефон), запись и фото с телефона (в том числе с выключенным
экраном — «Стоп» в уведомлении), режим экономии батареи для долгих съёмок, переключение скорости TimeShift на
ходу и **все** настройки съёмки для 12 режимов. Отметки камеры превращаются в клип **без перекодирования**;
видео можно обрезать, а цвет — поправить (I-Log → Rec.709, плёночные пресеты). Интерфейс на **10 языках**.
Протокол восстановлен самостоятельно; проект не связан с Insta360.

### English

**GO Ultra Transfer** — a small open-source Android app for the **Insta360 GO Ultra**, an alternative to the
official one: no account, no ads, no cloud. It wakes the camera over Bluetooth and joins its Wi-Fi by itself, lists
files with previews, plays and opens them straight from the camera, downloads them to the phone's gallery (in the
background, with resume) and deletes them from the card. It has a live view (full screen when the phone is turned
sideways), recording and photos from the phone (also with the screen off — Stop in the notification), a battery-saving
mode for long shoots, on-the-fly TimeShift speed switching and **all** the shooting settings for its 12 modes. The
camera's highlight marks become a clip **without re-encoding**; videos can be trimmed and coloured (I-Log → Rec.709,
film-look presets). The interface is in **10 languages**. The protocol was reverse-engineered; not affiliated with
Insta360.

### Français

**GO Ultra Transfer** — une petite application Android open source pour la caméra **Insta360 GO Ultra**, en
alternative à l'application officielle : sans compte, sans publicité, sans cloud. Elle réveille la caméra en
Bluetooth et rejoint son Wi-Fi toute seule, affiche les fichiers avec aperçu, les lit et les ouvre directement depuis
la caméra, les télécharge dans la galerie du téléphone (en arrière-plan, avec reprise) et les supprime de la carte.
Elle offre un aperçu en direct (plein écran en tournant le téléphone), l'enregistrement et les photos depuis le
téléphone (même écran éteint — « Arrêter » dans la notification), un mode économie de batterie pour les longues
prises, le changement de vitesse TimeShift en cours d'enregistrement et **tous** les réglages de prise de vue des
12 modes. Les marques de la caméra deviennent un clip **sans réencodage** ; on peut couper la vidéo et corriger la
couleur (I-Log → Rec.709, presets film). Interface en **10 langues**. Protocole reconstitué par rétro-ingénierie ;
projet non affilié à Insta360.

### Italiano

**GO Ultra Transfer** — una piccola app Android open source per la **Insta360 GO Ultra**, alternativa a quella
ufficiale: senza account, senza pubblicità, senza cloud. Sveglia la camera via Bluetooth e si collega da sola al suo
Wi-Fi, mostra i file con l'anteprima, li riproduce e li apre direttamente dalla camera, li scarica nella galleria del
telefono (in background, con ripresa) e li cancella dalla scheda. Offre l'anteprima dal vivo (a schermo intero girando
il telefono), registrazione e foto dal telefono (anche a schermo spento — «Interrompi» nella notifica), una modalità
risparmio batteria per le riprese lunghe, il cambio di velocità TimeShift durante la registrazione e **tutte** le
impostazioni di scatto dei suoi 12 modi. I segni della camera diventano una clip **senza ricodifica**; il video si può
tagliare e il colore correggere (I-Log → Rec.709, preset pellicola). Interfaccia in **10 lingue**. Protocollo
ricostruito a ritroso; nessun legame con Insta360.

### Español

**GO Ultra Transfer** — una pequeña app Android de código abierto para la cámara **Insta360 GO Ultra**, alternativa a
la oficial: sin cuenta, sin anuncios y sin nube. Despierta la cámara por Bluetooth y se conecta sola a su Wi-Fi,
muestra los archivos con vista previa, los reproduce y los abre directamente desde la cámara, los descarga en la
galería del teléfono (en segundo plano, con reanudación) y los borra de la tarjeta. Tiene vista en directo (a pantalla
completa girando el teléfono), grabación y fotos desde el teléfono (también con la pantalla apagada — «Detener» en la
notificación), un modo de ahorro de batería para grabaciones largas, cambio de velocidad TimeShift durante la
grabación y **todos** los ajustes de disparo de sus 12 modos. Las marcas de la cámara se convierten en un clip **sin
recodificar**; el vídeo se puede recortar y el color corregir (I-Log → Rec.709, preajustes de película). Interfaz en
**10 idiomas**. Protocolo reconstruido por ingeniería inversa; sin relación con Insta360.

### Deutsch

**GO Ultra Transfer** — eine kleine Open-Source-Android-App für die Kamera **Insta360 GO Ultra** als Alternative zur
offiziellen: ohne Konto, ohne Werbung, ohne Cloud. Sie weckt die Kamera per Bluetooth und verbindet sich selbst mit
ihrem WLAN, zeigt die Dateien mit Vorschau, spielt sie ab und öffnet sie direkt von der Kamera, lädt sie in die
Galerie des Handys (im Hintergrund, mit Fortsetzen) und löscht sie von der Karte. Dazu: Livebild (im Querformat
bildschirmfüllend), Aufnahme und Fotos vom Handy (auch bei ausgeschaltetem Bildschirm — «Stopp» in der Mitteilung),
ein Batteriesparmodus für lange Aufnahmen, das Umschalten der TimeShift-Geschwindigkeit während der Aufnahme und
**alle** Aufnahmeeinstellungen für die 12 Modi. Die Marken der Kamera werden zu einem Clip **ohne Neukodierung**;
Videos lassen sich schneiden und die Farbe anpassen (I-Log → Rec.709, Film-Presets). Die Oberfläche gibt es in
**10 Sprachen**. Das Protokoll wurde nachgebaut; keine Verbindung zu Insta360.

### Português

**GO Ultra Transfer** — um pequeno app Android de código aberto para a câmara **Insta360 GO Ultra**, alternativa à
oficial: sem conta, sem anúncios e sem nuvem. Acorda a câmara por Bluetooth e liga-se sozinha ao Wi-Fi dela, mostra os
ficheiros com pré-visualização, reproduz e abre-os diretamente da câmara, descarrega-os para a galeria do telefone (em
segundo plano, com retoma) e apaga-os do cartão. Tem vista ao vivo (ecrã inteiro ao virar o telefone), gravação e
fotos pelo telefone (também com o ecrã desligado — «Parar» na notificação), um modo de poupança de bateria para
gravações longas, mudança de velocidade TimeShift durante a gravação e **todas** as configurações de captura dos seus
12 modos. As marcas da câmara tornam-se um clipe **sem recodificação**; o vídeo pode ser cortado e a cor corrigida
(I-Log → Rec.709, predefinições de filme). Interface em **10 idiomas**. Protocolo reconstruído por engenharia inversa;
sem ligação à Insta360.

### Türkçe

**GO Ultra Transfer** — **Insta360 GO Ultra** kamerası için resmî uygulamaya alternatif, küçük ve açık kaynaklı bir
Android uygulaması: hesap yok, reklam yok, bulut yok. Kamerayı Bluetooth ile uyandırır, kendi Wi-Fi'sine bağlanır,
dosyaları önizlemeli olarak listeler, doğrudan kameradan oynatır ve açar, telefonun galerisine indirir (arka planda,
devam ettirilebilir) ve karttan siler. Canlı görüntü (telefon yan çevrilince tam ekran), telefondan kayıt ve fotoğraf
(ekran kapalıyken de — bildirimde «Durdur»), uzun çekimler için pil tasarrufu modu, kayıt sırasında TimeShift hızını
değiştirme ve 12 modun **tüm** çekim ayarları vardır. Kameranın işaretleri **yeniden kodlama olmadan** bir klip olur;
video kırpılabilir ve renk düzeltilebilir (I-Log → Rec.709, film ön ayarları). Arayüz **10 dilde**. Protokol tersine
mühendislikle çözüldü; Insta360 ile bağlantısı yoktur.

### 中文（简体）

**GO Ultra Transfer** —— 一款面向 **Insta360 GO Ultra** 相机的小型开源 Android 应用，可替代官方应用：无需账号、
没有广告、不依赖云端。它通过蓝牙唤醒相机并自动连接相机的 Wi-Fi，带预览地列出文件，可直接从相机播放和打开，
下载到手机相册（后台下载、断点续传），并能从存储卡删除文件。还有实时预览（手机横放即全屏）、手机录制与拍照
（息屏也能录制——通知栏里的「停止」）、长时间拍摄的省电模式、录制中切换 TimeShift 速度，以及 12 种模式的
**全部**拍摄设置。相机的标记点可**无需转码**直接合成片段；视频可裁剪，颜色可调整（I-Log → Rec.709、胶片预设）。
界面支持 **10 种语言**。协议为逆向还原；与 Insta360 无关联。

### 日本語

**GO Ultra Transfer** — **Insta360 GO Ultra** 向けの小さなオープンソース Android アプリで、公式アプリの代替です。
アカウント不要、広告なし、クラウドなし。Bluetooth でカメラを起こして自動でその Wi-Fi につなぎ、ファイルを
サムネイル付きで一覧表示し、カメラから直接再生・表示し、スマホのギャラリーへダウンロード（バックグラウンド、
レジューム対応）し、カードから削除します。ライブビュー（スマホを横向きにすると全画面）、スマホからの録画と
撮影（画面オフでも録画でき、通知の「停止」で操作）、長時間撮影向けのバッテリー節約モード、録画中の TimeShift
速度切り替え、12 モードの**すべて**の撮影設定を備えています。カメラのマークは**再エンコードなし**で 1 本の
クリップになります。動画のトリミングと色調整（I-Log → Rec.709、フィルム風プリセット）も可能。UI は
**10 言語**。プロトコルはリバースエンジニアリングによるもので、Insta360 とは無関係です。





