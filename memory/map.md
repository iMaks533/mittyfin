# Карта Mittyfin

> Справочник: только то, что верно сейчас. История — в git log.

## Стек

Kotlin 2.3, AGP 8.13, Compose (BOM 2026.05), Media3 1.8.0 (exoplayer, ui, okhttp, hls, session),
FFmpeg-аудио `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1`, libass `io.github.peerless2012:ass-media:0.4.0`,
OkHttp 4.12 + kotlinx.serialization, DataStore, Coil 3. minSdk 29, target 36, только arm64-v8a.
Лицензия GPL-3.0 (код GPU FEL пришёл из GPL-форка NuvioTV).

## Модули и где что лежит

| Путь | Что там |
|---|---|
| `fel/` | GPU FEL: `GpuFelVideoRenderer` (Media3 BaseRenderer, два MediaCodec BL+EL в ByteBuffer/P010), `FelAccessUnitSplitter` (AU → BL / EL / RPU), `FelGlComposer` (compose-поток + present-поток по Choreographer, общий EGL, fence), `FelShaders` (reshaping poly/MMR + NLQ, PQ, tone map), `FelToneMap` (BT.2390 по L1), `FelFrameScheduler`, `GpuFelSupport` (проверка устройства), `GpuFelStatus` (HUD) |
| `fel/src/main/cpp/fel_jni.cpp` | JNI над libdovi (`fel/libdovi/android-arm64`, статическая `libdovi.a`): параметры композитора из RPU |
| `app/.../data/` | `JellyfinClient` (REST 10.9+), модели, `Prefs` (DataStore), `Settings.kt` (`AppSettings`, `TitleMemory`) |
| `app/.../player/PlayerActivity.kt` | жизненный цикл, сборка ExoPlayer, переключение серий, ошибки/автоповтор/перекодирование, PiP, MediaSession, жесты (яркость/громкость), отчёты на сервер |
| `app/.../player/PlayerScreen.kt` | UI плеера, жесты, клавиши пульта, кнопка пропуска, карточка «Далее» |
| `app/.../player/PlayerDialogs.kt` | меню, дорожки, звук, субтитры, главы, панель серий, статистика |
| `app/.../player/PlaybackSupport.kt` | `FelRenderersFactory` (GPU FEL первым, сдвиги видео/текста, `GainAudioProcessor`), `buildLoadControl`, `AudioRoute`, `RefreshPin`, `SleepTimer` |
| `app/.../player/` прочее | `TrackChooser` (языки, lossless, режимы субтитров, память), `SeriesFlow` (отрезки, «Далее»), `SubtitleProcessing` (cp1251, SDH), `SubtitleStyle`, `SeekBar` (главы, превью, D-pad), `PreviewFrames` (trickplay / кадр из файла) |
| `app/.../ui/` | Compose-экраны: главная, медиатеки (сортировка/фильтры, жанр, актёр), поиск, избранное, карточка, настройки, вход; `components/Focus.kt` (`focusHighlight`, `isTv`) |
| `art/icon-source.png` | исходник иконки; иконки и ТВ-баннер нарезаны из него |

## Контракты

- Jellyfin: заголовок `Authorization: MediaBrowser Client="Mittyfin", Device, DeviceId, Version, Token`;
  поток `/Videos/{id}/stream?static=true&mediaSourceId=` — токен в заголовке (не в URL),
  перекодирование — `POST /Items/{id}/PlaybackInfo` → HLS `TranscodingUrl`, остановка
  `DELETE /Videos/ActiveEncodings`; отрезки `/MediaSegments/{id}` (Intro/Recap/Outro/Preview);
  отчёты `/Sessions/Playing{,/Progress,/Stopped}` с `PlayMethod` DirectPlay/Transcode.
- Память по тайтлу (`TitleMemory`): ключ = id сериала для серий, id фильма иначе;
  язык звука/субтитров ("" = выкл.), forced, сдвиг субтитров, скорость.
- Задержка звука хранится по маршруту вывода (`AudioRoute`: speaker / wired / hdmi / `bt:<имя>`).
- Сдвиги: видео рендерится на `+audioDelay`, текст на `audioDelay − subtitleOffset` (`ShiftedRenderer`).

## Инварианты и грабли

- **GPU FEL возможен только при**: HEVC-декодер отдаёт P010 (цвет 54) и ≥2 сессии 4K,
  EGL-окно RGBA1010102 + `EGL_EXT_gl_colorspace_bt2020_pq`. Shield (Android 11): декодер
  только 8 бит, HDR-окна нет → недоступен (это железо, не код).
- **c2.mtk.hevc.decoder** (Dimensity): EL-кадры выходят почти в порядке декодирования →
  EL ищется по PTS (`elOut` — TreeMap). На HDR-SEI и повторных VPS/SPS/PPS перед каждым IDR
  делает `configUpdate` и теряет кадры конвейера (лог `NO_OUTPUT work returned`) → splitter
  вырезает SEI и повторы параметров. Норма: `configUpdate` ≈ 2 на запуск.
- Первый кадр после перемотки ждёт EL не дольше 500 мс (часы не идут, пока он не показан).
- Подача во входы приостанавливается, пока удержано 8 BL / 12 EL готовых кадров.
- minSdk 29: не использовать API 30+ без проверки версии (пример: `Arrays.equals` с диапазонами
  — API 33, падало на Shield).
- Платформенный извлекатель кадров на MTK отдаёт чёрное для 4K HDR → `PreviewFrames` это
  распознаёт и показывает только время/главу. Trickplay на сервере выключен в медиатеках.
- ColorOS «OTI» роняет экран на 60 Гц через ~3 с без касания — из приложения не обойти.
- Пульт: навигация на ТВ — левая панель (`NavRail`), на телефоне — нижняя пилюля. Нижняя
  панель на ТВ ломала вход фокуса в списки. Pull-to-refresh на ТВ выключен.
- Подгонка частоты: телефон — режим ≥48 Гц, кратный fps (24p → 120 Гц); ТВ — сам 23,976 Гц.

## Чек-листы

- Новый ключ настройки → поле в `AppSettings` с дефолтом (старые хранилища читаются с ним),
  строка в `SettingsScreen`.
- Новый вызов Jellyfin → метод в `JellyfinClient` через `get`/`post` (сессия восстанавливается
  сама), корутины вызывать через `attempt {}` (не `runCatching`: глотает отмену).
- Правка `GpuFelSupport`/рендерера → прогнать `:fel:testDebugUnitTest` и тестовый FEL-файл
  (`243aa250ff3ed6ea0e7da7f128f50185`) на телефоне: `noEl=0`, `dropped=0`.
- Тяжёлый диск с IDR каждую секунду для проверки потерь кадров: The Hunger Games
  (`5c6336dae0ac4ea8bd979f7a9841afc4`, старт 1051000 мс).
