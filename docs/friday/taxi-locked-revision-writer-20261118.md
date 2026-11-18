# Preparatory actual revision writer: план до кода

База bd40841 / remote 7bc1577. Следующий срез НЕ durable continuation: удалить SQL arithmetic из реально вызываемого `_bump_search_revision`, сохраняя нынешние BIGINT/NULL/MAX/negative semantics. InstantOrder types, adapter, resume producer/consumer, account/API не меняются. No new deletion restriction/409. Остальные start/advance/reassign SQL arithmetic остаются явно отдельной работой; этот срез не называется all-writer migration-ready.

## Узкая область

`instant_service._bump_search_revision` вызывается route/waypoints `_invalidate_route_search` и `add_fallback_category`. Публичные service paths уже делают fresh `session.refresh(..., with_for_update=True)` до criteria mutation. PostgreSQL держит row lock; SQLite игнорирует FOR UPDATE, поэтому простой Python read→update недопустим.

Предлагаемый алгоритм после выбора lock strategy: получить свежий DB scalar под настоящим writer lock; None→1, int 0..MAX-1→n+1. MAX/negative/missing возвращают прежний bilingual herr409 на том же route/fallback boundary. Записать literal next exact NULL-safe CAS, require rowcount1, refresh только search_revision, вернуть scalar. Никакого commit/rollback внешней транзакции. Caller rollback сохраняет route/price/promo/revision; никаких новых Redis/push эффектов внутри helper. Dirty ORM same-field недопустим без явного flush contract, не adopt stale identitymap.

## Нерешённый до реализации выбор SQLite lock

A. SQLite no-op UPDATE перед fresh read (аналог существующего security.lock_refresh_user), PG SELECT FOR UPDATE. No-op нужен лишь для lock, но это реальный UPDATE: может вызвать trigger даже при subsequent invalid generation. Нельзя молча назвать это полной observational equivalence. Проверить фактическую schema/trigger contract и rollback; не вводить такой эффект без независимого решения.

B. Guarded no-op UPDATE с нынешним `_can_increment_search_revision`, RETURNING старого scalar, затем Python next и exact CAS. Invalid MAX/negative/missing не затрагиваются, NULL возвращается как одна строка, не путать с missing. Дополнительный UPDATE для валидного writer остаётся; guard numeric coalesce/range остаётся BIGINT-only и должен быть заменён при будущем type cutover. Этот вариант удаляет только арифметику, не всю SQLite TEXT несовместимость.

Ни один вариант не выбран как approved runtime. Не вызывать BEGIN IMMEDIATE посреди неизвестной активной caller transaction и не коммитить её ради получения lock. Не добавлять User lock под Order lock.

## Матрица обязательной проверки

- Actual route/waypoints/fallback normal NULL/0/MAX-1, exact revision+1, все route/price/fee/promo/token/lifecycle прежние правила.
- MAX/negative/missing прежняя ошибка, independent rollback readback; никаких новых account действий.
- Offered fallback без снижения цены сохраняет offer, но bump; duplicate no-op без bump.
- Два physical SQLite connections с наблюдаемым writer BUSY/blocking ДО fresh scalar; rollback и итог без lost increment. PG distinctPID/observed blocking, outercommit release и stale expected controls.
- Conditional UPDATE rowcount0 не success, stale identity map не источник next. Валидный SQL write не использует column+1; остальные writers не объявлять converted.
- Ошибка после helper и caller rollback сохраняет связанные criteria и unrelated transaction semantics. Никакого fake SQL commit вместо actual PG rollback.
- Старые revision/route/fallback tests + actual-code known gaps остаются применимыми (pending всё ещё не обслуживается). Full backend после независимой scoped/PG приёмки, если runtime меняется.

После independent выбора алгоритма подготовить один минимальный actual helper+регрессии пакет. Реальная migration/drain, finite128budget, malformed/negative historical trust, tokenless replay, scheduler и все pending writers остаются блокерами включения durable функции.

## Независимый выбор и уточнённый контракт до кода

Journal выбирает B, условный PASS narrow `_bump`. SQLite guarded no-op RETURNING выполняется только при прежнем numeric range guard; result row полностью consume, `(None,)` означает существующий legacy owner, `None` означает missing/invalid. SQLite rowcount RETURNING не используется. PostgreSQL fresh scalar SELECT FOR UPDATE внутри caller transaction. Далее Python None→1/valid int→+1 и обычный literal UPDATE с exact NULL-safe old revision CAS, require rowcount1. Trigger-induced смена после RETURNING приводит к прежнему herr409 и caller rollback, не adoption/retry.

Весь lock/read/update/refresh выполняется под `session.no_autoflush`; перед SQL явный check SQLAlchemy history изменения search_revision у переданного ORM object. Dirty same-field отвергается прежним helper conflict error, не flush/discard; actual route/fallback callers должны быть проверены как clean. Другие dirty поля не discard, refresh только revision; no_autoflush не объявляется запретом любых последующих внешних stale writes. Helper не commit/rollback caller, внешняя ошибка требует caller rollback как прежде.

Схема source review без custom triggers: поиск app/alembic не нашёл CREATE TRIGGER; deployed schema не проверялась. SQLite valid rows теперь дают два UPDATE против прежнего одного. Fixture audit trigger должен честно показать 2 vs1 events, invalid0 и исчезновение событий после rollback. Никакой observational equivalence для произвольных triggers. BIGINT numeric guard остаётся несовместимым с будущим TEXT numeric comparisons, поэтому readiness cutover не заявляется.

SQLite pre-existing reader snapshot может дать SQLITE_BUSY_SNAPSHOT/lock upgrade failure: propagate/rollback, не blind retry и не гарантия ожидания. Тестировать два physical connections, commit/rollback no-lostincrement и отдельный honest busy control. PG real lock wait и fresh scalar. Lock в `_bump` не делает ранее прочитанный route/status/quote свежим: actual concurrent state-change control должен показать границу, а не заявлять починку всего route graph. Данный пакет не меняет эту политику.

## Pre-implementation доказательства и GO

2e50d44 содержит только test-local SQL probe. Author 6 PASS, exit0, 1.14 с (`/tmp/taxi-bump-probe1.log`, `.exit`); independent 6 PASS, exit0, 1.40 с (`/tmp/taxi-bump-probe-independent.log`, `.exit`), Ruff/diff PASS. Фактическая conftest SQLite schema проверена через sqlite_master: persistent InstantOrder triggers отсутствуют. Собственные connection-local TEMP triggers показывают 2 vs1 valid UPDATE, invalid/missing0, NULL distinction и rollback audit events. Это не проверка deployed schema и не runtime acceptance. Reviewer дал GO узкой реализации B после probe.

Source audit: два callsites helper: `_invalidate_route_search` и `add_fallback_category`; destination/waypoints/fallback вызывают bump после своего refresh и до присваивания criteria/price. Штатный revision clean. В app/alembic update triggers/hooks не найдены; найденный before_commit hook idempotency не равен UPDATE trigger. Внешняя refresh/autoflush семантика caller не исправляется автоматически. Runtime реализация и actual helper acceptance только начинаются.

## Runtime freeze и предварительное review

905e848 меняет только import inspect и `_bump_search_revision`. Existing revision/route scoped: 78 PASS, exit0 (`/tmp/taxi-locked-bump-scoped-EpaMk6.log`). Независимый read-only diff review не нашёл P0/P1/P2: history-before-load, no_autoflush, consume/close RETURNING, NULL distinction, PG fresh scalar, literal NULL-safe CAS, selective refresh и отсутствие outer commit/rollback подтверждены. Actual новые тесты/PG/FULL ещё впереди; это не финальная приёмка.

Первый actual helper test run сохранён: 18 PASS / 2 FAIL / 2 SKIP (`/tmp/taxi-bump-actual1.log`, `.exit`). Оба FAIL: диагностический route-interleave тест предполагал cancelled→expired, но наблюдал cancelled; при исходном searching ORM присваивание того же searching не является dirty status. Это неверное предположение fixture, не подтверждённая runtime regression. Финальная диагностика должна закрепить точный independent status/route/revision readback, не принимать любой исход и не заявлять resurrection без доказательства. Файл и причины отказов сохраняются.

Actual tests freeze 0c8b02a: 22 PASS / 2 PG SKIP, exit0, 1.38 с (`/tmp/taxi-bump-actual3.log`, `.exit`). NULL/MAX/negative/missing, dirty revision до SQL, unrelated dirty fields/selective refresh, real trigger CAS0 с caller rollback, SQLite two-connection BUSY и explicit rollback/commit проверены. Route boundary закреплён точно: cancelled остаётся cancelled, но отложенная destination/waypoints mutation меняет route и revision21. Это наблюдение текущего candidate, не baseline equivalence или доказательство resurrection.

2866e07 добавляет только allowlisted locked-revision-bump mode, fixed new file с обычным conftest; default и guard unchanged. Независимый review нашёл P2 fixture isolation: busy_timeout=1 возвращался к обычному значению только на success path, что при failure могло загрязнить pooled connection. Требуется восстановление исходного PRAGMA через finally с rollback/commit до возврата connection. PG blocking observation deadline3с остаётся меньше lock_timeout4с; startup wait допустимо увеличить отдельно. Runtime issue этим замечанием не установлен; final acceptance ещё нет.

Независимый expanded scoped: 180 PASS / 2 PG SKIP, 5.97 с, exit0 (`/tmp/taxi-locked-bump-independent-scoped2.log`, `.exit`). Первый collection exit4 был ошибочным именем файла команды reviewer, не отказом приложения; исходный log сохранён. Дополнительный reviewer control загрузил только старый helper из 905e848^ в памяти: оба route-after-cancel diagnostics также PASS, 1.34 с (`/tmp/taxi-locked-bump-old-helper-boundary.log`). Для этих двух interleaves helper не вносит новый дефект; это не полная baseline equivalence и не исправление route graph.

P2 исправлен d0e9b7a: original busy_timeout восстанавливается в finally после rollback до pool, без swallow; startup Event wait20с, observation3с/runnerlock4с не изменены. Independent focused: 22 PASS / 2 PG SKIP, 1.81 с, exit0 (`/tmp/taxi-locked-bump-independent-focused.log`, `.exit`); static PASS, P2 закрыт. Author actual disposable PG: **10 PASS / 14 SQLite SKIP**, 1.98 с, exit0 (`/tmp/taxi-locked-bump-author-pg1.log`, `.exit`), включая два actual blocking tests. Независимые PG/default и FULL ещё впереди.

Independent actual PG: **10 PASS / 14 SQLite-only SKIP**, 2.02 с, exit0 (`/tmp/taxi-locked-bump-independent-pg.log`, `.exit`), включая оба distinct PID/observed blocking/fresh revision cases commit и rollback. Unchanged regression: **255 PASS**, 12.03 с, exit0 (`/tmp/taxi-locked-bump-independent-regression.log`, `.exit`). Каждый fresh cluster удалён, cleanup пуст. Final narrow review PASS для runtime905e848/tests d0e9b7a/runner2866e07, открытых P0/P1/P2 нет. Secrets0. FULL запускается отдельно; это не all-writer cutover или durable recovery.

## FULL и проверенный итог

FULL на fbb70ec (runtime905e848, tests d0e9b7a) завершён с actual exit0: **7744 PASS / 479 SKIP / 815 warnings**, 701.24 с (11:41), `/tmp/taxi-locked-bump-full1.log` и `.exit`. Это полный SQLite backend suite; его PG SKIP не считаются PG PASS, отдельные реальные PG результаты выше. После фактического завершения ресурс передан Amio через followup.

Проверено узкое изменение реально вызываемого route/fallback revision helper: свежий scalar под writer lock, Python increment и exact CAS при прежних BIGINT/NULL/MAX правилах. Account, модели/миграции, другие writers и pending producer/consumer не менялись. SQLite valid path содержит дополнительный UPDATE; custom deployed triggers не проверены. Отложенная route mutation после cancellation в двух SQLite interleaves остаётся известной границей и наблюдалась также со старым helper. Tokenless client replay, all-writer conversion, finite budget и полный durable поиск не исправлены. Main/production/приостановленные операции не затрагивались.
