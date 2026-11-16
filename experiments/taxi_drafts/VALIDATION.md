# Проверка первого пакета

Пятница · 16.11.2026. База `ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8`.
Область: только `experiments/taxi_drafts/**` и добавленные ссылки в
`docs/tasks.md`, `docs/decisions.md`.

### Уточнение secret-scan при пакете5

Прежние команды scan каталога без `--all-files` могли пропускать ещё не tracked
новые файлы. Поэтому прежнее «0 findings» не доказывает их полный охват.
В пакете5 весь `experiments/taxi_drafts` повторно проверен с `--all-files`:
одно эвристическое срабатывание на `market_signals.py:110`, публичную строковую
константу `NOT_IMPLEMENTED_pricing_authorization_and_service_safety`. Это код
ограничения результата, не секрет. Других findings нет. Сканер не отключался,
исключения/allowlist не добавлялись. Повтор покрывает и исходники пакетов1–4.

- Независимая проверка плана до кода, отдельный `gpt-6.1-sol`: **ACCEPT** для
  ограниченного offline-пакета, не для всех функций. Условия: сравнение полей,
  aware-time, свежесть/направление ETA, неизвестные статусы fail-closed,
  отсутствие полномочий и атомарности явно отмечены.
- `python -B -m unittest discover -s experiments/taxi_drafts/tests -v`:
  **19 tests PASS**, код выхода0. Синтетические subtests включают негативные
  варианты и границы. Это не19 сквозных сценариев такси.
- `ruff check experiments/taxi_drafts --select F`: **PASS**, код выхода0.
- `git diff --check`: **PASS**.
- `git grep -n taxi_drafts -- backend android webapp web`: совпадений нет.
  Diff не меняет runtime, dependencies, workflows, тарифы или БД.
- `detect-secrets scan experiments/taxi_drafts docs/tasks.md docs/decisions.md`:
  **0 findings**. Это проверка разрешённых исходников, не гарантия безопасности.

**NOT_RUN:** полный backend pytest/coverage, Android сборка/GuardTest/emulator,
PWA/web сборки, PostgreSQL-конкурентность, E2E, нагрузка, внешние карты и CI.
Изменений runtime нет. Долгие полные процессы в текущей облачной среде не имеют
надёжно восстановленного контроля; этот пакет сознательно проверяется только
быстрыми offline-командами. Повтор полного CI/интеграций обязателен до подключения
к продукту. Нет основания считать такси полностью проверенным или готовым.

Независимая кодовая приёмка, новый `gpt-6.1-sol`: **ACCEPT** для изолированного
черновика, P0/P1 нет. Рецензент лично повторил19 tests, Ruff F, diff и изоляцию.
P2 документации исправлены: секции добавлены перед старыми заголовками, а не
внутри их содержимого; создан данный файл проверки. Проверенная ревизия фиксируется коммитом этого пакета; файл не
самоссылочный. Ни merge, ни deploy не выполнялись.

## Пакет2 — восстановление после потерянного ответа

База remote8120af541f3bf24bb9e830d0ae2daa2a755afae5 (local169ada963, одинаковое
дерево). Изменения только `recovery.py`, `tests/test_recovery.py` и добавления к
README/этому отчёту. Shared docs/цены/БД/runtime не меняются.

- Независимая план-приёмка: ACCEPT для offline classifier, не runtime recovery.
- Та же команда unittest: **33 tests PASS** (19 первого пакета +14 новых), exit0.
- Ruff F: PASS. Diff whitespace: PASS.
- Новые проверки: late response другого запроса/аккаунта/сессии; unknown не absent;
  отсутствие заказа не receipt; TTL/future; конфликт revision; сохранение якоря;
  lost body/new key/other account; истёкшая replay guarantee; повтор без мутаций.
- Полные backend/Android/E2E/CI и реальный recovery/retry: **NOT_RUN**.

Независимая кодовая приёмка, свежий gpt-6.1-sol: **ACCEPT** для offline-пакета,
P0/P1/P2 нет; рецензент лично повторил33 tests, Ruff F и diff.
Secret-scan `experiments/taxi_drafts`: **0 findings**, exit0.

## Пакет3 — модель экономики на синтетических входах

База remote207e61629a9f0592c53059437b03b70e00a69464 / localfc95c284,
одинаковое дерево. Только economics_preview.py, test_economics_preview.py и
дополнения README/VALIDATION. Цены, runtime и журналы других агентов не меняются.

- Независимая план-приёмка: ACCEPT для сценарной модели, не доказанной экономики.
- Stdlib suite **45 tests PASS** (12 новых), Ruff F и diff-check PASS.
- Проверены неизвестные расходы/объёмы, exact fee на B+A, failed attempts,
  нулевой объём с расходами, отрицательная маржа, ceiling break-even, 100% A
  водителю, неизменный gross при промо платформы, четыре класса без magic multiplier.
- Реальные ставки/затраты, налоги/договоры, прибыль/cashflow, нагрузка и пилот:
  **NOT_RUN / NOT_IMPLEMENTED**. Полные backend/Android/CI не запускались.

Независимая кодовая приёмка, свежий gpt-6.1-sol: **ACCEPT**, P0/P1/P2 нет.
Рецензент повторил45 tests и diff-check; его окружение не нашло Ruff, поэтому
Ruff PASS выше только авторский. Secret-scan experiments/taxi_drafts: **0 findings**.

## Пакет4 — качество агрегатов и safety-сигналов

База remote30bfd876857014be1a77aa3afdcf88c043c765bf / local0f72ad288,
одинаковое дерево. Только market_signals.py, test_market_signals.py и README/
VALIDATION. Вводный текст README обновлён под четыре последовательных пакета.

- Независимая план-приёмка: ACCEPT только для offline-классификатора.
- Stdlib suite **57 tests PASS** (12 новых), Ruff F/diff-check PASS.
- Проверены unknown/partial/failure отдельно от0 supply, нулевой спрос, точное
  окно/контекст, TTL/future/min observations, собственная свежесть safety,
  приоритет ограничений с сохранением причин деградации, все четыре класса.
- Реальное получение/формирование сигналов, ценообразование, safety workflow,
  full backend/Android/CI: **NOT_IMPLEMENTED / NOT_RUN**.

Независимая кодовая приёмка свежим gpt-6.1-sol: **ACCEPT**, P0/P1/P2 нет.
Рецензент лично повторил57 tests, Ruff F и diff. Secret-scan
experiments/taxi_drafts: **0 findings**, exit0.

## Пакет5 — подтверждения денежных фактов

База remotef7f8197d8d2ff3a18a36c0b6fea5e45eb04d94af / local1ce3c7516,
одинаковое дерево. Только payment_facts.py, test_payment_facts.py и README/
VALIDATION. Рабочие payment/ledger endpoints не изменены.

- Независимая план-приёмка: ACCEPT с fail-closed totals, точным dedup, отдельными
  unknown/pending и явным направлением возврата.
- Stdlib suite **72 tests PASS** (15 новых), Ruff F/diff-check PASS.
- Проверены exact duplicate/status conflict/reused evidence, перестановки,
  совместный over-refund, unknown/failed originals, unknown reserve/failed release,
  scope/method/time, bool/float/zero/blank evidence, отсутствие paid/debt состояния.
- Provider/DB/фактическая оплата/возврат, full backend/Android/E2E/CI:
  **NOT_IMPLEMENTED / NOT_RUN**. Только синтетические offline fixtures.

Независимая кодовая приёмка свежим gpt-6.1-sol: **ACCEPT**, P0/P1/P2 нет.
Рецензент повторил72 tests, Ruff F и diff; дополнительно проверил лимит именно
исходного collection, границы pending/unknown и24 перестановки конфликтов.
Полный `detect-secrets scan --all-files experiments/taxi_drafts`: одно проверенное
ложное срабатывание на публичную константу пакета4 (см. уточнение в начале),
других findings нет; реальных секретов не обнаружено.

## Пакет6 — отмена и проверка no-show

База remote95fa6591aaf69b3fd953f5cc80c1e0a5ef9c76ea / local406bca9be.
Только cancellation_review.py, test_cancellation_review.py и README/VALIDATION.
Ни существующий таймер, ни формула отмены не скопированы и не изменены.

- Независимая план-приёмка: ACCEPT с отдельным текущим контекстом и fail-closed
  неизвестными доказательствами. План-reviewer не нашёл06-criteria в checkout;
  автор прочитал сохранённый отдельный документ и сверил указанные пункты.
- Stdlib suite **82 tests PASS** (10 новых), Ruff F/diff-check PASS.
- Проверены изменения всех существенных consent-полей, missing/expired/future,
  no-show граница/ранний предзаказ/повтор, unknown pickup/contact/presence,
  onboard/present, stale generation/revision и противоречивые времена.
- Runtime/плата/страйки/реальное согласие/геопроверки, backend/Android/E2E/CI:
  **NOT_IMPLEMENTED / NOT_RUN**.

Независимая кодовая приёмка свежим gpt-6.1-sol: **ACCEPT**, блокирующих находок нет.
Рецензент повторил82 tests, Ruff F и diff. Полный scan --all-files повторён:
только известное ложное срабатывание market_signals.py:110 на публичную константу,
других findings нет. Сканы/проверки не выполняли денежных операций.
