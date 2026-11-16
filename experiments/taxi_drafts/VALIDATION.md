# Проверка первого пакета

Пятница · 16.11.2026. База `ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8`.
Область: только `experiments/taxi_drafts/**` и добавленные ссылки в
`docs/tasks.md`, `docs/decisions.md`.

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
