---
name: k1g-pcap-analysis
description: Использовать при разборе K1G PCAPNG из diag, поиске пауз RX/TX, tick bursts и сопоставлении с ride-log. Не для произвольного сетевого дампа или измерения RTP.
---

# Воспроизводимый разбор K1G PCAPNG

Декодирование — только существующим `tools/k1g.lua` через
[`tools/k1g_analyze.py`](../../../tools/k1g_analyze.py). Не писать второй
декодер K1G и не склеивать плоские колонки TLV: unknown/empty/repeated TLV
сдвигают такую склейку. Это локальный анализ, не новый гейт заезда.

## 1. Происхождение и покрытие

- Установить, встроенный ли это app-level capture или внешний захват.
  Найти парный `ride-STAMP.log`, модель, сборку и доступный `app_log.txt`.
  Совпадение stamp связывает файлы, но не гарантирует полное покрытие.
- Прочитать базовую линию `docs/devices/<device>.md` и
  [`spec/k1g_protocol.md`](../../../spec/k1g_protocol.md), особенно ограничения
  seq, `0B 02` и auth result. Выбор сборки и сбор файлов принадлежат
  [`ride-verification`](../ride-verification/SKILL.md); не повторять их здесь.
- Записать фактическое временное покрытие каждого источника. Более поздний
  `app_log` не заменяет окно старой поездки. Питание, экран и настройки брать
  из сообщения райдера или достоверного измерения, не из предположений или
  служебных battery/charging TLV.

## 2. Воспроизводимый отчёт

Из корня репозитория (пути к capture и tshark подставить реальные):

```sh
python3 tools/k1g_analyze.py /absolute/ride-STAMP.pcapng --tshark /absolute/tshark --timezone Europe/Moscow --json
```

Timezone задавать явно в соответствии с ride timestamps, не с текущей зоной
машины. CLI требует Python 3.9+ stdlib и tshark с Lua; pip не нужен.
Сохранить stdout средствами инструментов рядом с исследуемыми данными, не
коммитить личные captures. Записать точную команду, capture/dissector SHA256,
версию tshark, thresholds и observed coverage из отчёта. Второй запуск с
теми же входами/settings должен дать те же данные. Exit 0 означает валидный
отчёт, не здоровую связь; exit 1 — ошибка входа/backend, exit 2 — аргументы.
Частичный stdout при ошибке не интерпретировать как успешный отчёт.

Сначала проверить:

1. `diagnostics`, observed sections/interfaces и clock segments. Пустые SHB
   отчёт не считает. `no_k1g_packets`/`no_rx_packets` — отсутствие наблюдений,
   не стабильность связи. `multiple_rx_peers` запрещает причинные выводы о
   непрерывности одной приборки: нужен отдельный capture, не выбор удобного IP.
2. `time_regression`: CLI сохраняет файловый порядок и начинает новый segment,
   не сортирует время. Отрицательная дельта не доказывает NTP: writer создаёт
   block под lock, но enqueue выполняет после него. Через section/interface/
   clock boundary интервалы и counter transitions не строятся.
3. Доступные app_log/ride на capture queue/drop/write/cap warnings. Queue
   capacity 256 — не occupancy; потолок writer 64 МиБ, возможен незаписанный
   хвост. Даже отсутствие warnings не гарантирует полноту capture.

## 3. Сопоставление с ride-log

- Рассмотреть все большие `rx_gaps`, `heartbeat_gaps`, `tick_gaps`, bursts и
  `rx_open_tails`. Closed gap строго превышает threshold; open tail — только
  нижняя граница без следующего RX, `right_censored=true`, не часть closed max.
- Для каждого инцидента сопоставить frame/time bounds с `[connect]`,
  `[session]`, `[auth]`, watchdog, `UserDisconnect` и фактическим `startStream`.
  Startup/reconnect/teardown подписать рядом, не удалять из исходного отчёта.
- При регрессии wall clock проверить длительности через monotonic `+NNNms`
  ride-log. `app_log` с HH:mm:ss не становится monotonic. По интервалу, который
  app_log не покрывает, нельзя утверждать отсутствие ошибок.
- В RX gap отдельно прочитать TX/HB datagram counts и `heartbeat_first_10s`:
  окно от последнего RX до min(next RX, +10s), counts уникальных datagrams,
  max silence включает обе границы. Это captured TX, не измерение радиоканала.
- Проверять спорные frame refs тем же Lua в tshark. Не объяснять плохой GPS
  сетевым watchdog без временного и причинного основания.

## 4. Границы доказательности

- Встроенный writer пишет TX до send: это не доказательство `sendto` или
  доставки. RX после receive не локализует задержку. RTP здесь отсутствует;
  синтетические IP/UDP headers — не wire evidence.
- Суффикс `01 01` читается BE16, но его смысл и аппаратный модуль не
  установлены. В исследованном корпусе наблюдается `00FF→0000`. Сохранять
  полный counter, старший byte, signed delta и `delta_mod65536`; последняя —
  арифметика представления, не обещание периода 65536. Не обрезать до byte.
- Unit steps и точные `00FF→0000`/`FFFF→0000` допускаются для группировки
  burst, не для оценки потерянных ticks. Burst требует минимум три разных
  frame numbers; повторные TLV одного packet не создают три arrivals.
- Delta и сжатое получение не доказывают reboot, секунды uptime, возраст
  датаграмм или kernel queue. Delayed delivery и пачечная генерация дэша
  остаются совместимыми без времени генерации. Не назначать причиной EMUI,
  driver, kernel buffer или код приложения по сравнению разных заездов.
- `0B 02` — наблюдаемый маркер возвращения, не доказательство reboot;
  отсутствие маркера тоже не исключает reboot. `07 01 == 01` — auth result,
  не подтверждение успешности всей сессии. Не реконструировать boot/stream FSM
  по одним командам и не печатать RSA blobs.
- `reconnects=0` означает только отсутствие зарегистрированных platform WiFi
  reconnect, не отсутствие пауз control RX или рестартов сессии.
- Отсутствие experts не доказывает полной валидности: Lua не диагностирует
  все short/unparsed-tail случаи. Malformed K1G в целом контейнере остаётся
  данными с diagnostics, Lua/runtime/container error — ошибка инструмента.

## 5. Результат

Краткий итог и таблица инцидентов: capture frame/time bounds, RX gap,
HB count/max silence первого 10s окна, восстановление по ride-log и известная
фаза сессии. Затем отдельно **наблюдения**, **гипотезы**, **не установлено**.
При пересмотре явно перечислить, какие прежние числа подтвердились, что
исправлено и почему. Приложить команды и пути сохранённых отчётов. Не
подменять повторный разбор полного capture synthetic fixtures.
