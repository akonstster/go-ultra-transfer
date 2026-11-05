# Альфа: другие камеры Insta360 (скачивание файлов)

> Ветка `universal`, сборки `v0.17-alpha1` и новее. Цель — **забрать файлы** с камер Insta360, вокруг которых
> приложение не строилось. Управление, трансляция и настройки для них **не поддерживаются** (пока).

## Что уже должно работать

Камеры, отвечающие на **OSC HTTP API** (`http://192.168.42.1`), — по официальной документации Insta360 это
**X3, X4, X5, ONE X2, ONE X3, ONE X4, ONE RS, Ace, Ace Pro** (и другие модели X/ONE-серии):

- список файлов (с размерами и датами), пары `.insv` (`_00_`/`_10_`) показываются одним снимком;
- скачивание в `DCIM/Camera` — тот же загрузчик, что и для GO Ultra (докачка, защита от дублей);
- `.insv`/`.insp` сохраняются как есть (просмотр/склейка 360 — вне альфы; откройте их в Insta360 Studio).

**GO 2 / GO 3 / GO 3S**: у них другой протокол (BLE-пакеты Go2BlePacket + TCP 6666, как у GO Ultra), OSC у них нет.
В мастере первого запуска для них есть **отдельный пункт** («GO 2 · GO 3 · GO 3S») — он не ведёт на OSC-экран, а
показывает подсказку: включите камеру, подключитесь к её Wi-Fi вручную (пароль — на экране камеры, обычно
88888888) и нажмите **«Подключиться»**. Приложение подхватит сеть, к которой подключён телефон, и дальше работает
как с GO Ultra: список файлов и скачивание. Режим **непроверенный** — BLE-часть у GO 2/GO 3 может отличаться;
нужен тестер с такой камерой.

## Как подключить

1. Включите камеру и откройте на телефоне **Настройки → Wi-Fi**. Найдите точку доступа камеры —
   её имя оканчивается на **`.OSC`** (например, `Insta360 X4 AB12CD.OSC`). Пароль по умолчанию — **88888888**
   (можно поменять на самой камере; если меняли — введите свой).
2. Подключитесь к этой сети. Телефон может предупредить, что «интернета нет» — это нормально.
3. Откройте приложение → меню **⋮ → «Другая камера (альфа)…»**.
4. Нажмите **«Найти камеру»**. Появится модель, прошивка, батарея и список файлов.
5. Отметьте нужное (или «Выбрать все») и нажмите **«Скачать»** — файлы сохранятся в `DCIM/Camera`.

Ещё один вход — мастер первого запуска (или ⋮ → «Настроить камеру заново»): внизу **«Экспериментально — поддержка
других камер» → «Выбрать серию камеры»**. Серии X/ONE/Ace открывают тот же экран «Другая камера» с подсказкой о
выборе; **GO 2 · GO 3 · GO 3S** показывает свою инструкцию: подключитесь к Wi-Fi камеры и нажмите «Подключиться» —
приложение подхватит эту сеть само (если телефон уже в сети камеры, оно использует её как есть).

Если камера «не активирована» (`unactivated`), один раз подключите её к официальному приложению Insta360 —
это требование прошивки, обойти его нельзя.

## Что НЕ делает альфа

- не меняет никакие настройки камеры и **никогда не удаляет файлы** с неё;
- не показывает превью/трансляцию и не умеет запись — только список и скачивание;
- не проигрывает `.insv` (360) внутри приложения.

## Пришлите профиль камеры — это добавляет поддержку

Кнопка **«Отправить профиль камеры»** на том же экране собирает отчёт: `/osc/info`, `/osc/state`, образец
`camera.listFiles`, `camera.getOptions`, модель телефона и версию приложения. Серийник маскируется, пароли
не собираются. Пришлите его:

- через **GitHub issue** (шаблон: «Camera profile: <модель>») или
- на почту/мессенджер автора (см. профиль GitHub `akonstster`).

## English

> Branch `universal`, builds `v0.17-alpha1` and newer. The goal is to **get the files off** Insta360 cameras this app was
> not built around. Control, live view and settings are **not supported** for them (yet).

### What should work

Cameras that answer the **OSC HTTP API** (`http://192.168.42.1`) — per Insta360's official documentation that is
**X3, X4, X5, ONE X2, ONE X3, ONE X4, ONE RS, Ace, Ace Pro** (and other X/ONE-series models):

- the file list (sizes and dates), `.insv` pairs (`_00_`/`_10_`) shown as one shot;
- downloads into `DCIM/Camera` — the same downloader the GO Ultra uses (resume, no duplicates);
- `.insv`/`.insp` are saved as they are (360 playback/stitching is out of scope; open them in Insta360 Studio).

**GO 2 / GO 3 / GO 3S** speak a different protocol (Go2BlePacket over BLE + TCP 6666, like the GO Ultra) and have no
OSC. The first-run wizard has a **separate entry** for them (“GO 2 · GO 3 · GO 3S”) — it does not open the OSC screen
but shows the steps: switch the camera on, join its Wi-Fi by hand (the password is on the camera's screen, usually
88888888) and tap **“Connect”**. The app picks up the network the phone is already on and then works like it does
with the GO Ultra: the file list and downloads. This mode is **unverified** — the BLE half may differ on GO 2/GO 3;
a tester with such a camera would help.

### How to connect

1. Switch the camera on and open **Settings → Wi-Fi** on the phone. Find the camera's access point — its name ends
   with **`.OSC`** (e.g. `Insta360 X4 AB12CD.OSC`). The default password is **88888888** (it can be changed on the
   camera; if you changed it, type yours).
2. Join that network. Android may warn that there is “no internet” — that is expected.
3. Open the app → menu **⋮ → “Another camera (alpha)…”**.
4. Tap **“Find the camera”**. The model, firmware, battery and the file list appear.
5. Tick what you need (or “Select all”) and tap **“Download”** — the files land in `DCIM/Camera`.

Another entry point is the first-run wizard (or ⋮ → “Set up the camera again”): at the bottom **“Experimental — support
for other cameras” → “Choose the camera series”**. X/ONE/Ace open the same “Another camera” screen with a “you picked”
hint; **GO 2 · GO 3 · GO 3S** shows its own instructions: join the camera's Wi-Fi and tap “Connect” — the app picks up
that network itself (when the phone is already on the camera's network, it is used as it is).

If the camera reports `unactivated`, connect it to the official Insta360 app once — the firmware requires it.

### What the alpha does not do

- never changes camera settings and **never deletes files** on the camera;
- no previews/live view, no recording — listing and downloading only;
- does not play `.insv` (360) inside the app.

### Send the camera profile — that is how support gets added

The **“Send camera profile”** button collects a report: `/osc/info`, `/osc/state`, a sample of
`camera.listFiles`, `camera.getOptions`, the phone model and the app version. The serial is masked and no passwords
are collected. Send it via a **GitHub issue** (title: “Camera profile: <model>”) or to the author (GitHub profile
`akonstster`), and tell us: the camera model, firmware version, what worked and what did not.

