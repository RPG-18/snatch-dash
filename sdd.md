# Spec-Driven Development и snatch-dash: нужен ли фреймворк

**Статус:** разбор, ничего не внедрено. Отвечает на два вопроса: имеет ли
смысл переводить проект на SDD и, если да, какой фреймворк подойдёт.

**Дата сбора данных:** 24.09.2026, ветка `session-structured-concurrency`,
коммит `3660af0` плюс незакоммиченные правки. Факты о репозитории получены
прогоном по рабочей копии; факты о фреймворках — по их репозиториям и
документации на ту же дату, звёзды по GitHub API. Всё, что не удалось
подтвердить первоисточником, помечено **[неточно]**.

## Вердикт

1. **Переводить проект на SDD не нужно, потому что он уже на нём.** Живые
   спеки поведения, планы изменений для исполнителя-модели, критерии
   «готово» на каждом этапе и гейты перед коммитом здесь есть с лета 2026.
   Фреймворки продают ровно эту дисциплину тем, у кого её нет.
2. **Слабость процесса структурная, а не содержательная:** планы и спеки
   лежат вперемешку, момент «план закрыт, дельта влита в спеку» нигде не
   фиксируется. Это лечится соглашением о каталогах и одним скиллом, а не
   инструментом.
3. **Если инструмент всё же хочется, единственный кандидат — OpenSpec.**
   Только он моделирует нашу ситуацию: живые спеки текущего поведения
   отдельно, дельты изменений отдельно, шаг слияния между ними. Брать
   пробно, на одной фиче, с заранее записанным критерием успеха (§6).

## 1. Что уже есть в репозитории

Чтобы разбор не предлагал заново то, что сделано.

### 1.1 Живые спеки поведения — `spec/`

21 файл, ~4.2k строк. По экрану (`home_screen.md`, `dash_screen.md`, …) и по
подсистеме (`frame_pipeline.md`, `wifi_retry_policy.md`,
`drawing_from_local_tiles.md`, `video.md`, `fsm.md`). Содержат то, чего нет в
шаблонах ни одного фреймворка: провайдеры, которые экран читает и пишет;
инварианты с объяснением, почему именно так («RTP-метка — счётчик кадров, а
не время»); даты полевых подтверждений; ссылки на строки кода; отвергнутые
гипотезы с причиной. Спеки правятся вместе с кодом: из последних 60 коммитов
22 трогают `spec/` или `docs/`.

### 1.2 Планы изменений для исполнителя

[`network-refactoring.md`](plans/done/2026-09-30-network-refactoring.md) (2758 строк) написан
«для исполнителя (модель или человек), который начинает с нуля». В нём есть
всё, что фреймворки называют своими артефактами:

| Элемент плана | Аналог во фреймворках |
|---|---|
| §0 «Как читать», §4 «Правила работы для исполнителя» | constitution (Spec Kit), steering (Kiro) |
| §2 «Инварианты, которые нельзя нарушить» + golden-тесты как механическая проверка | constitution + `verify` |
| Этапы с критерием «готово», остановкой и отдельным коммитом | tasks.md с approval gates |
| Метки `[auto]` / `[ride]` | **аналога нет** |
| §0.1 «Статус» с ✅ / ❌ / ↩️ и датами, журнал отклонений от порядка | tasks status (Kiro «Sync files») |
| «Что опровергнет посылку» у каждой задачи ([`plans/active/2026-09-24-code-quality.md`](plans/active/2026-09-24-code-quality.md)) | **аналога нет** |

Тот же формат у [`plans/done/2026-09-16-frame-pipeline.md`](plans/done/2026-09-16-frame-pipeline.md), [`plans/done/2026-09-09-improvement-dash-protocol.md`](plans/done/2026-09-09-improvement-dash-protocol.md)
и [`plans/active/2026-09-22-weather.md`](plans/active/2026-09-22-weather.md).

### 1.3 Слияние плана в спеку уже делается — руками

[`spec/frame_pipeline.md`](spec/frame_pipeline.md) объявлен «выжимкой из
`plans/done/2026-09-16-frame-pipeline.md`: там остались дневник, замеры и обоснования, здесь — то, что
должно пережить план». В `plans/active/2026-09-22-weather.md` заранее сказано, какие разделы
переживут этапы. Это и есть шаг `archive` из OpenSpec, только без
инструмента и без фиксации, что именно уже влито.

### 1.4 Гейты

- обязательное `/code-review` перед коммитом, подпёртое хуком `PreToolUse`
  ([`.claude/settings.json`](.claude/settings.json));
- чек-лист проверки на железе ([`docs/ON_HARDWARE_VERIFICATION_RU.md`](docs/ON_HARDWARE_VERIFICATION_RU.md))
  с легендой `[ ]`/`[x]`/`[!]`;
- базовые линии по телефонам в [`docs/devices/`](docs/devices/), чтобы число в
  ride-файле не считали аномалией раньше времени;
- `CLAUDE.md` с обоснованием каждого правила анализатора и каждого отказа.

Гейт «заезд на мотоцикле» — главный в проекте, и его не моделирует ни один
из рассмотренных инструментов.

## 2. Что в процессе сломано

Всё ниже — про раскладку, не про содержание.

- **Планы лежали в корне репозитория** рядом с `README.md`:
  `network-refactoring.md`, `frame-pipeline.md`, `improvement-dash-protocol.md`,
  `code-quality-plan.md`. Ещё два — `report.md`, `todo-check.md` — в
  `.gitignore`, то есть существуют только на этой машине. Раньше в корне
  жили и умерли `plan.md`, `review.md`, `review-2.md`, `bugs.md`,
  `review-dk.md`, `review-k3.md`, `review-spec.md`.
  **Исправлено 01.10.2026:** все четыре переехали в `plans/active/` и
  `plans/done/` по 6.1, корень чист.
- **План и спека неразличимы по месту:** `spec/weather.md` — план с этапами,
  лежал среди спек поведения (**исправлено 01.10.2026:**
  `plans/active/2026-09-22-weather.md`). `spec/multimedia.md` — разбор
  оригинального приложения; в git с 29.09 (bae8255), но по форме это аудит, а не спека текущего поведения.
- **Момент слияния не фиксируется.** Что из `frame-pipeline.md` уже переехало в
  `spec/frame_pipeline.md`, а что нет — узнаётся только чтением обоих. Через
  месяц это уже нельзя восстановить.
- **Формат плана живёт в голове.** Следующий план будет таким же хорошим,
  только если писать его по образцу `network-refactoring.md`; нигде не
  записано, что образец именно он.

## 3. Обзор фреймворков (на 24.09.2026)

| Инструмент | Активность | Модель спек | Brownfield | Claude Code |
|---|---|---|---|---|
| [GitHub Spec Kit](https://github.com/github/spec-kit) | 138.6k ★, v1.0.10 от 22.09.2026 | `spec/plan/tasks` на фичу + одна constitution | есть [гайд](https://github.github.io/spec-kit/guides/existing-projects.html); спека описывает изменение, не существующее поведение | `specify integration install claude` → `/speckit-*` |
| [OpenSpec](https://github.com/Fission-AI/OpenSpec) | 70.0k ★, v1.13.2 от 23.09.2026 | живые `openspec/specs/` + дельты `ADDED/MODIFIED/REMOVED` в `openspec/changes/`, слияние при `archive` | «brownfield-first»; существующие документы — [сырьё, не спеки для конвертации](https://github.com/Fission-AI/OpenSpec/blob/main/docs/existing-projects.md) | `openspec init` → `/opsx:*` |
| [Kiro](https://kiro.dev/docs/specs/) (AWS) | закрытый; дата GA противоречива **[неточно]** | `requirements/design/tasks` на фичу в EARS + steering | «Sync files» по коду | нет, свой агент |
| [cc-sdd](https://github.com/gotalab/cc-sdd) | 3.7k ★, v3.1.0 от 23.09.2026 после пяти месяцев тишины | раскладка Kiro для Claude Code, `/kiro-*` | `validate-gap` / `validate-design` | 17 скиллов, есть русская локаль |
| [BMAD Method](https://github.com/bmad-code-org/BMAD-METHOD) | 53.4k ★, v6.12.0 от 04.09.2026 | Brief → Spec → Architecture → Stories, роли-персоны | контекст пишет в `AGENTS.md`; [сам советует пропустить](https://docs.bmad-method.org/existing-codebases/start-in-an-existing-codebase/), если `CLAUDE.md` уже ведётся | плагин из маркетплейса |
| [Superpowers](https://github.com/obra/superpowers) | v6.4.1 от 19.09.2026 | не SDD: датированные дизайн-доки и планы, масштаб церемонии по размеру задачи | нет | официальный плагин |
| [Tessl](https://tessl.io/registry/tessl-labs/spec-driven-development) | закрытая бета, публичного репозитория нет | spec-as-source, `.spec.md` на файл | `@describe` для существующего кода | CLI/MCP; только JS/TS |

Общие наблюдения по обзору:

- **Только OpenSpec** формально разделяет «спека текущего поведения» и
  «предложение изменения» и имеет шаг слияния между ними. Остальные ведут
  спеки по фиче, а долгоживущее знание держат в steering/`AGENTS.md`/`CLAUDE.md`.
- **Дрейф спеки от кода не автоматизирует никто.** `/opsx:verify`,
  `/speckit-converge`, «Spec Verification» Tessl — LLM-судья, не проверка.
- **Ни один не масштабирует церемонию под размер задачи** кроме Superpowers
  (spike / bounded / architectural) и BMAD с v6.12 (Build решает после
  разведки).

## 4. Что говорит критика SDD

- Böckeler, Thoughtworks, 10.2025 ([статья](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html)):
  «я лучше буду ревьюить код, чем все эти markdown-файлы»; инструменты не
  подстраивают объём под задачу; исправление мелкого бага в Kiro
  превратилось в четыре user story с шестнадцатью критериями. Thoughtworks
  Radar держит SDD в **Assess**: «длинные спеки, которые тяжело ревьюить»,
  «неясно, кто их пользователь».
- Marmelab, 11.2025 ([«The Waterfall Strikes Back»](https://marmelab.com/blog/2025/11/12/spec-driven-development-waterfall-strikes-back.html)):
  агент всё равно не делает с первого раза то, что имелось в виду, спека
  переписывается вслед за кодом, стоимость её поддержки накапливается.
- de Smet, 07.2026 ([«The hidden costs of SDD»](https://hiddedesmet.com/the-hidden-costs-of-spec-driven-development)):
  шесть налогов SDD; сигнал переплаты — «время до первого коммита растёт, а
  число дефектов в поле не падает». Предлагает три полосы: полная / лёгкая
  (одна страница) / без спеки.
- Практический тест, Ran the Builder, 04.2026 ([пост](https://ranthebuilder.cloud/blog/i-tested-three-spec-driven-ai-tools-here-s-my-honest-take/)):
  OpenSpec 4.00, BMAD Quick 3.74, BMAD Full 3.65, Spec Kit 2.77 из 5; BMAD
  Full — шесть дней против одного на ту же фичу; «для простых фич хватает
  лёгких подходов».
- Anthropic, best practices Claude Code: «если дифф описывается одним
  предложением — пропустите план».

Вывод из критики для нас: риск не в отсутствии спек, а в их избытке в чужом
формате. Спеки в `spec/` читаются и правятся потому, что в них факты и
причины, а не потому, что шаблон потребовал заполнить раздел.

## 5. Почему не переезжать целиком

- **Формат станет беднее.** EARS Kiro/cc-sdd и `requirements + scenarios`
  OpenSpec не вмещают доказательства с датами, отвергнутые гипотезы, разбор
  по hex-дампам, ссылки на строки. Это придётся либо потерять, либо
  держать во втором документе рядом.
- **Гейт `[ride]` нигде не моделируется.** Любой инструмент будет считать
  задачу закрытой, когда код написан и тесты зелёные; здесь это середина
  этапа.
- **Один разработчик, ~32k строк продакшена, 6.5k строк документации.**
  Шаблоны spec/plan/tasks дают выигрыш команде без документации; здесь они
  добавят второй слой markdown.
- **Английские шаблоны и команды** поверх русскоязычных спек и `CLAUDE.md`,
  с которыми модель уже хорошо работает. cc-sdd с русской локалью — один
  мейнтейнер с пятимесячной паузой.
- **Два источника правды.** OpenSpec хочет `openspec/specs/`; существующие
  `spec/` он советует не конвертировать. Значит, либо два каталога спек,
  либо переписывание 4k строк под его формат.

## 6. Предложение

Ранжировано по отношению «польза / стоимость».

### 6.1 Раскладка каталогов — час работы

Завести каталог планов изменений с фиксированным жизненным циклом
(название — на вкус, например `plans/` или `changes/`):

```
plans/
  active/     2026-09-22-weather.md                  ✅ перенесён 01.10
              2026-09-24-code-quality.md             ✅ перенесён 01.10
  done/       2026-09-09-improvement-dash-protocol.md ✅ перенесён 25.09
              2026-09-16-frame-pipeline.md            ✅ перенесён 25.09
              2026-09-30-network-refactoring.md       ✅ перенесён 30.09
spec/         только спеки текущего поведения
```

Правила:

- план переезжает в `done/` только с шапкой «влито в `spec/<файл>` <дата>,
  не влито: <что и почему>»;
- `spec/weather.md` переезжает в `plans/active/`, пока не закрыт этап 5, и
  возвращается в `spec/` уже спекой, как обещано в его преамбуле
  (сделано 01.10.2026: `plans/active/2026-09-22-weather.md`);
- `report.md`, `todo-check.md` либо в git, либо удалить — знание, которого
  нет в репозитории, для следующей сессии модели не существует;
- `CLAUDE.md` получает абзац об этом жизненном цикле.

Это ровно идея OpenSpec без его формата и без инструмента.

**Что опровергнет посылку:** если через месяц планы снова появятся в корне,
значит, соглашения недостаточно и нужен механический гейт (хук на путь
файла) или инструмент.

### 6.2 Формат плана как скилл — два часа

`.claude/skills/plan-format/SKILL.md`, описывающий по
`network-refactoring.md`: §0 «как читать», §0.1 статус с датами, инварианты
с механической проверкой, этапы с критерием «готово» и остановкой, метки
`[auto]`/`[ride]`, «что опровергнет посылку», проверки перед коммитом.
Второй скилл — «закрытие плана»: что переносится в спеку, что остаётся в
плане, как оформляется шапка «влито».

Ценность: следующий план модель напишет по образцу, а не как получится.
Стоимость: два файла. Риск: скилл читают, но не следуют — тогда см. 6.1.

### 6.3 Проба OpenSpec на одной фиче — день, только если хочется инструмента

Погода ([`plans/active/2026-09-22-weather.md`](plans/active/2026-09-22-weather.md)) удобна как эксперимент: план
уже есть, этапы малы, гейт `[ride]` один.

Порядок: `openspec init` в отдельной ветке → `/opsx:propose` по
существующему плану → `/opsx:apply` этапы 1–3 → `/opsx:archive`.

**Критерий успеха один, записан до начала:** итоговая спека погоды после
`archive` должна быть не хуже той, что получилась бы руками по 6.1–6.2, и
при этом слияние дельты должно занять меньше времени, чем ручное. Если
нет — ветка удаляется, инструмент не нужен, а 6.1–6.2 остаются.

Чего ждать заранее: формат `openspec/specs/` не примет разделы «Из реверса
оригинального приложения» и «Почему не альтернативы» — их придётся оставить
в нашем документе. Это и есть главная цена, которую проба должна измерить.

### 6.4 Чего не делать

- Spec Kit, BMAD, cc-sdd, Tessl — по причинам из §3 и §5, без пробы.
- Массовая конвертация `spec/` в формат любого инструмента — сам OpenSpec
  предупреждает, что это даёт «большую устаревшую спеку, которой никто не
  доверяет».
- Superpowers как SDD — он про другое (TDD, брейншторм, субагенты), живые
  спеки не поддерживает; как набор скиллов рассматривать можно отдельно.

## 7. Источники

- Spec Kit: [репозиторий](https://github.com/github/spec-kit), [existing projects](https://github.github.io/spec-kit/guides/existing-projects.html), [evolving specs](https://github.github.io/spec-kit/guides/evolving-specs.html)
- OpenSpec: [репозиторий](https://github.com/Fission-AI/OpenSpec), [concepts](https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md), [existing projects](https://github.com/Fission-AI/OpenSpec/blob/main/docs/existing-projects.md), [Thoughtworks Radar](https://www.thoughtworks.com/en-us/radar/tools/openspec)
- Kiro: [specs](https://kiro.dev/docs/specs/), [best practices](https://kiro.dev/docs/specs/best-practices/), [steering](https://kiro.dev/docs/steering/)
- cc-sdd: [репозиторий](https://github.com/gotalab/cc-sdd), [spec-driven guide](https://github.com/gotalab/cc-sdd/blob/main/docs/guides/spec-driven.md)
- BMAD: [репозиторий](https://github.com/bmad-code-org/BMAD-METHOD), [existing codebases](https://docs.bmad-method.org/existing-codebases/start-in-an-existing-codebase/)
- Superpowers: [репозиторий](https://github.com/obra/superpowers), [brainstorming](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md), [writing-plans](https://github.com/obra/superpowers/blob/main/skills/writing-plans/SKILL.md)
- Tessl: [анонс](https://tessl.io/blog/tessl-launches-spec-driven-framework-and-registry), [обзор codemyspec](https://codemyspec.com/blog/tessl-review)
- Критика: [Böckeler](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html), [Thoughtworks Radar: SDD](https://www.thoughtworks.com/en-us/radar/techniques/spec-driven-development), [Marmelab](https://marmelab.com/blog/2025/11/12/spec-driven-development-waterfall-strikes-back.html), [Breunig](https://www.dbreunig.com/2026/03/04/the-spec-driven-development-triangle.html), [de Smet](https://hiddedesmet.com/the-hidden-costs-of-spec-driven-development), [Ran the Builder](https://ranthebuilder.cloud/blog/i-tested-three-spec-driven-ai-tools-here-s-my-honest-take/), [Orchid, ASE '26](https://arxiv.org/html/2604.21505)
- Сравнения: [spec-compare](https://cameronsjo.github.io/spec-compare), [specdriven.com/landscape](https://specdriven.com/landscape), [Anthropic best practices](https://code.claude.com/docs/en/best-practices)
