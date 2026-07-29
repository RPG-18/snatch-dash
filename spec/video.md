# Видеопоток на dash (H.264/RTP)

**Файлы:** [`packages/opendash_dash_engine/android/.../dash/video/DashEncoder.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/video/DashEncoder.kt),
[`.../dash/video/NalProcessor.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/video/NalProcessor.kt),
[`.../dash/video/RtpPacketizer.kt`](../packages/opendash_dash_engine/android/src/main/kotlin/com/opendash/opendash_dash_engine/dash/video/RtpPacketizer.kt)

Собственный рендер (карта + приборы) кодируется в H.264 (`MediaCodec`, Surface
input) и стримится по RTP на порт 5000 (`DashSocket.RTP_PORT`). Разрешение
526×300, baseline profile, i-frame-interval 1 сек — сверено с официальным
приложением-компаньоном дэша (см. `re_app/overview.md` и `re_app/PACKET_CAPTURE.md`
в соседнем каталоге реверс-инжиниринга).

## Пробелы относительно оригинального приложения

Сверка со статическим анализом `com.royalenfield.reprime` (decompiled APK,
класс `r2g`/`q0k` — MediaCodec-энкодер, `NavigationFragment`/
`NavigationRootFragmentViewModel` — логика адаптации):

- ✅ **Склейка SPS/PPS с IDR** — не пробел. Оригинал приклеивает закэшированный
  `BUFFER_FLAG_CODEC_CONFIG` (SPS/PPS) к каждому `BUFFER_FLAG_KEY_FRAME` в один
  буфер перед отправкой. `NalProcessor.emitIdr()` делает то же самое
  (`sps + start_code + pps + start_code + idr` одним `ByteArray`, на каждый
  IDR заново). Поведение совпадает.

- ⚠️ **Нет адаптивного переключения fps/битрейта** — реальный пробел.
  Оригинал использует два пресета кодирования и переключается между ними
  на лету по **дистанции до следующего манёвра** (`NavigationRootFragmentViewModel.S2/T2`):

  | Пресет | Триггер | fps | Битрейт | i-frame-interval |
  |---|---|---|---|---|
  | High (`S2`) | до манёвра **< 1000 м** | 4 | 204 800 бит/с | 1 с |
  | Low (`T2`) | до манёвра **> 1000 м** (гистерезис 200 м) | 2 | 102 400 бит/с | 1 с |

  `DashEncoder.kt` сейчас использует только фиксированные `FPS = 4`,
  `BITRATE = 200_000` — фактически всегда «High»-пресет, без сброса до
  2 fps/100 kbps на длинных прямых участках вдали от поворота. Битрейт High
  почти совпадает (200_000 vs 204_800, ~2.4% — вероятно просто 200×1000 vs
  200×1024 у оригинала), так что несоответствие именно в отсутствии
  адаптивного снижения, а не в базовых числах.

  Если экономия канала/энергопотребления дэша на длинных перегонах не
  критична для целей проекта — можно осознанно оставить как есть; если
  важно точное соответствие поведению оригинала, стоит добавить переключение
  по `primaryDistanceInMeters` с порогом 1000 м и гистерезисом 200 м.
