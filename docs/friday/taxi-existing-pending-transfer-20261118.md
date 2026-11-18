# Existing pending: атомарный перенос при смене поколения

Статус: план для независимого pre-code review, НЕ разрешение runtime implementation.
База `3bed451` (published guard remote `14676fd5`), отдельная docs-ветка.
Пользовательская цель — durable продолжение после удаления предложенного водителя. Этот пакет даёт конкретный runtime transfer, но НЕ producer, worker activation или готовность всей функции.

## 1. Границы и источник правила

Предлагаемые будущие изменения только в `backend/app/instant_service.py`: fresh scalar capture pending под существующим owner lock, eligibility чистого существующего intent, перенос в том же owner UPDATE и безопасная синхронизация identity map. Schema/model/adapter/account/worker не менять. `taxi-all-writer-cutover-20261118.md:41` уже задаёт proposal: новый revision в обоих полях, attempts сохранить, next_at=min(old_next_at, post-lock clock), mismatch не adopt. Новые SQL арифметика, User locks, commit/rollback, budgets или account409 не нужны.

All-NULL baseline остаётся без pending. Весь сегодняшний MAX/negative/NULL search_revision контракт, lifecycle rotation, optional predicates, no-op commit, notifications/Redis timing сохраняется. Guarded SQLite no-op и его trigger effects не устраняются.

## 2. Eligibility и атомарное изменение

Свежий DB owner revision должен быть exact Python int (bool не int), 0..BIGINT_MAX, и уже пройти прежнюю increment validation: фактически для transfer old < BIGINT_MAX. BIGINT_MAX=2**63-1, НЕ 32-bit INT_MAX. Старый NULL owner не eligible, даже если pending revision заполнен. NULL initializer сохраняется без transfer.

Pending состоит из трёх scalar значений:
- revision: exact int, 0..BIGINT_MAX, равен старому DB owner revision;
- next_at: exact datetime, tzinfo is None, существующий naive UTC storage contract;
- attempts: exact int, 0..INT_MAX (2**31-1).

All-NULL, partial, mismatch, negative, неверные типы/aware datetime не исправлять и не очищать. Пропустить transfer, продолжив прежнюю owner operation без нового отказа. Сохранение таких данных не есть признание их безопасными для будущего consumer.

После успешного fresh lock/read один раз снять clock через существующий utcnow(), проверить предпосылку naive datetime. Предлагается application UTC clock, не обещание database wall-clock ordering. Переносит next_at=min(old, clock), attempts неизменны. Один и тот же owner UPDATE пишет literal new search_revision, matching_resume_revision=new, matching_resume_next_at=calculated и сохраняет captured attempts. Не добавлять отдельный последующий UPDATE pending. Полный transfer tuple атомарен относительно commit/rollback. Clock не снимать до ожидания lock; никакого cooldown reset attempts.

Fresh snapshot всех pending полей получается из SQL scalar row, не identity map. Final transfer UPDATE сохраняет старые owner predicates и exact captured pending tuple predicates (NULL-safe при необходимости); CAS loser не делает отдельный перенос/repair/effects. При реальном concurrent actor row lock упорядочивает capture. Не расширять non-transfer predicate новыми pending ограничениями: malformed/dirty pending не должен блокировать сегодняшний owner UPDATE.

## 3. Все actual increment writers на базе

| Место | Путь | Работа пакета |
|---|---|---|
| instant_service.py:1901–1947 `_bump_search_revision` | `_invalidate_route_search` :2073 → destination/waypoints; fallback :2492 | Расширить scalar capture, добавить pending values к существующему literal UPDATE; selective ORM sync |
| :1968–1999 `_locked_search_entry` | общая capture точка трёх entry writers | Добавить pending scalars после прежних status/revision/lifecycle, не менять optional/lifecycle predicates |
| :3128–3129 reassign UPDATE | `_reassign_after_driver_cancel` после отдельного cancel commit | Переносить только существующий eligible pending; не производить новый intent |
| :3716–3717 start UPDATE | created/scheduled/searching/expired restart | Тот же transfer; search_round/lifecycle правила без изменений |
| :3761–3762 advance UPDATE | decline, timeout, lazy reconcile | Тот же transfer, driver/token/time CAS неизменны |
| :3525 matcher NULL initializer | legacy searching NULL→1 | Не eligible по определению; не менять этот writer |

Остальные SET search_revision=search_revision — lock no-ops, не новые ownership episodes. Offer publication не увеличивает revision: сохраняет pending. Offered fallback без price drop увеличивает revision, но сохраняет offer: перенос нужен, recipient/token/expiry/price не менять. Duplicate fallback не увеличивает revision и pending не трогает. Прежние helper labs не подключать и не менять.

## 4. Dirty pending и stale identity map: обсуждаемый контракт

Это существенное ограничение, требующее явного review, а не молчаливого discard или нового409.

Предложение: если в текущем Session есть unflushed semantic history.has_changes хотя бы одного pending поля целевого order, transfer пропускается целиком. Все три caller pending значения и unrelated dirty поля сохраняются; owner writer работает по прежним правилам. Проверка passive inspect, без getattr/implicit SQL; для id-only reassign искать объект по identity key без загрузки. Same-value assignment без semantic history не блокирует transfer. Объект отсутствует в identity map — caller pending dirty отсутствует.

Порядок существенен:
- `_bump` уже внутри no_autoflush, explicit flush не добавлять. Dirty pending остаётся dirty, transfer skip.
- Entry `_prepare_search_entry` уже вызывает session.flush(), только если session.autoflush=True. Сохранить этот порядок. После реального flush данные теперь DB-owned и clean: capture/eligibility могут перенести их. При autoflush=False либо внешнем no_autoflush flush не происходит; повторно проверить history после prepare, skip если осталось dirty.
- Не обещать, что caller-written partial tuple становится eligible: проверяется полный fresh DB tuple.
- Нынешние production callers pending вообще не пишут; это не permission будущему producer обходить общий owner protocol.

Clean stale ORM pending не является источником решения. После Core UPDATE нельзя оставить старый clean loaded tuple, который пользователь может наблюдать как текущий. Для выигравшего transfer под ещё удерживаемым lock и no_autoflush selective refresh только search_revision и трёх pending атрибутов целевого persistent объекта. Повторно не refresh unrelated attrs; они должны сохранить semantic histories и значения. Отсутствующий ORM объект не загружать ради sync. При skip dirty pending selective refresh касается только того revision, который уже синхронизировал прежний writer; pending не expire/refresh. Entry synchronize_session='fetch' само по себе не считать достаточным: тест доказывает exact values и dirty preservation до commit, после expiration и independent readback.

Не применять set_committed_value к пользовательским pending writes. Не делать full refresh/expire_all. Existing entry commit по-прежнему flush-ит оставшиеся caller writes: skip означает сохранение сегодняшней семантики, НЕ гарантированный перенос такого intent. Это отдельный блокер будущего producer/allwriter activation.

## 5. Тестовая матрица и наблюдаемый прогресс

Существующий `tests/test_taxi_pending_writer_known_gaps.py` содержит synthetic полный pending41/next_at/attempts3. Реальные start/advance/destination/searching fallback сейчас завершаются expired с revision42 и pending41; offered fallback сохраняет offer, но оставляет pending41; duplicate full no-op; accept/cancel/timeout сохраняют pending. Исторические доказательства остаются в published ветке. Новые transfer assertions не переписывать как будто исходного gap не было.

Новый пакет проверяет actual service/API, без mocks SQL/CAS/commit/generation. Fake Redis и transport boundary разрешены, fixture pending явно synthetic, не producer.

- Start, advance через decline/expiry, destination и waypoints, fallback searching/offered, reassign: fresh DB tuple revision42/pending42; next_at old past сохраняется, future сокращается до post-lock clock, attempts сохраняются. При downstream no-drivers итог expired и pending42: это transfer PASS, но terminal-clear gap остаётся.
- Offered fallback без price drop: все offer поля и цена прежние, оба поколения новые; duplicate весь snapshot unchanged.
- All-NULL baseline на каждом writer; old NULL с заполненным intent не adopt; partial/mismatch/type-invalid и negative pending skip без нового отказа. MAX-1→MAX переносится, MAX/negative owner не меняет pending.
- Dirty pending каждого поля и сочетаний; autoflush=True/False/no_autoflush; explicit flush; stale clean identity map; unrelated dirty поля; expire_on_commit True/False; refresh и independent physical readback. Assert не только конечная DB, но histories перед commit.
- Real API representative destination/waypoints/fallback/decline/cancel-driver маршруты с настоящей авторизацией; реальный сервис для прямого start и clock/lock чувствительных операций. Нельзя подменить недоступный API тест helper mock-ом и объявить HTTP coverage.
- PG два физических backend PID: transfer vs competing restart/terminal, оба winner orders; реальные lock wait, точный CAS loser без effects; rollback до commit, observer не видит частичный tuple. SQLite отдельные физические соединения, честный BUSY/ожидание без ложного FOR UPDATE claim.
- Existing entry/bump/route/dialect regressions и FULL после freeze/independent review. Проверять самый свежий code и не перекрывать Amio heavy слот.

## 6. Обязательные последующие blockers

Terminal clear остаётся отдельным runtime пакетом: transition accepted, cancel_order, _expire_no_drivers, finish_early, worker close_stuck_orders/finish_expired_waits. Cancel clear → отдельный reassign не создаёт intent автоматически; это ранее записанная граница, не новая политика отмены. Физическое удаление owner удаляет его поля вместе со строкой. No-driver expiry/wait admission нельзя менять ради бесконечного поиска.

Не активировать producer/consumer до complete allwriter transfer/retain/clear, dirty producer contract, migrated model/storage/budget и stale persisted sources review. Search ownership episode не тождественен driver assignment: offered-retained transfer не меняет offer identity. Account foreign-recipient revoke и его транзакция остаются нетронутыми. Ни MAX409 для удаления, ни tokenless replay/ABA решение, ни 128-digit progress этот пакет не вводит.

План требует независимого journal review до кода, затем frozen static/author/independent реальные проверки. Никакого main/production/реальной БД или повторения paused действий. В этом документальном пакете app/tests не изменены, тесты/PG не запускались.

### Уточнение границ перед journal

Eligibility определяется ПОСЛЕ существующего entry flush; дополнительный flush запрещён. Внешний no_autoflush делает session.autoflush=False на время контекста, поэтому оставшаяся semantic dirty pending history означает skip. `_bump` flush не делает.

Owner-matching полный pending на cancelled/accepted — только структурно подходящий fixture tuple, не доказательство валидной живой ownership episode. В частности, seeded cancelled→reassign transfer в этом промежуточном пакете проверяет механику, а не разрешает сохранять обязательство после настоящей отмены. До terminal-clear и полного протокола activation запрещена.

Exact captured pending tuple CAS обязателен в transfer UPDATE даже при owner lock. Fixture trigger-control меняет pending между capture и финальным UPDATE без внешней конкуренции; ожидается CAS loser, никакого transfer retry/adoption и никаких winner effects. Если SQLite AFTER trigger меняет строку после формирования RETURNING capture, final pending CAS должен обнаружить mismatch. Trigger, меняющий строку уже после успешного финального UPDATE, не считается предотвращённым этим CAS; arbitrary deployment trigger compatibility не обещается. Тесты отдельно показывают snapshot/rowcount/rollback/commit outcome существующего caller, а не заявляют отсутствия side effects любого trigger.

Malformed skip относится только к tuple, представимому после DBAPI/SQLAlchemy scalar decoding. Физически недекодируемый timestamp или тип может вызвать DB/decoder error; этот пакет не обещает безошибочного чтения любой коррупции и не подавляет такие ошибки.

### Journal implementation GO: обязательные условия

Passive inspect учитывает state.persistent, state.session и state.deleted, а также session.deleted. Отсутствующий identity-map объект не загружается ради синхронизации. Same-value semantic history считается clean. Clock вызывается только после lock для действительно eligible transfer; all-NULL/invalid/dirty skip не вызывает clock и не зависит от его результата. Clock должен быть exact naive datetime; ошибка clock не превращается в repair tuple. Pending3 и owner generation обновляются одним UPDATE; exact pending CAS добавляется только для transfer. `_bump` CAS loser сохраняет прежний409, entry loser — прежний no-op. Никаких новых winner effects/retry. Чистая синхронизация только нужных полей под lock/no_autoflush, dirty pending не refresh/expire. Terminal/producer/worker/schema/account остаются вне scope.

### Mismatch collision: отдельный activation blocker

Контрпример: owner revision=41, полный pending revision=42. При increment owner→42 pending mismatch пропускается и весь pending tuple остаётся неизменным. После commit равенство поколений возникает случайно, хотя transfer validation его не принимала. Это НЕ доказательство валидного adoption; будущий consumer, проверяющий только равенство, способен ошибочно принять старый intent. Runtime этого пакета намеренно сохраняет approved skip semantics, без repair/clear/нового отказа. Обязательный regression фиксирует exact unchanged pending3 при old+1 mismatch и owner increment. Producer/consumer activation блокирована до отдельного решения provenance/episode validity для этого случая; одного equality guard недостаточно.

## Pre-acceptance findings preserved

Runtime freeze 5f361c2 ещё не принят. Первые неизменённые SQLite entry/bump/dialect regressions: 177 PASS / 8 PG SKIP, 3.78 с, exit 0 (`/tmp/taxi-pending-transfer-initial-regression.log/.exit`). Это не новые transfer acceptance tests.

Независимый bounded SQLite/SQLAlchemy probe подтвердил P2: canonical raw timestamp `2026-11-18 12:00:00.000000` и raw `2026-11-18 12:00:00` оба decode exact naive datetime; повторный DateTime bind всегда содержит `.000000`. Реальный typed CAS даёт соответственно rowcount 1 и 0 без конкурента. Probe exit 0 означает воспроизведение дефекта, не runtime PASS; log `/tmp/taxi-pending-timestamp-independent-probe.log/.exit`, rollback и tempfile cleanup проверены. Это representable value, не undecodable corruption.

Journal одобрил узкую correction: на SQLite в одной locked SQL row/RETURNING захватить typed datetime, raw CAST AS TEXT и storage typeof. Eligibility только доказанному TEXT storage; CAS raw TEXT COLLATE BINARY с явным String bind, без DateTime повторного кодирования. PG typed path неизменён. Успешный typed SET может канонизировать timestamp как часть transfer, но нет standalone repair. Changed raw with same decoded datetime должен проигрывать exact CAS. Новый freeze и независимые tests обязательны.

Отдельный P2 в PG tests e99842f найден статически до запуска: committed SESSION SET timeout мог загрязнить pool. Исправление defcd63 применяет SET LOCAL внутри actual Session transaction; SELECT 1/0 требует exact SQLSTATE 22012. Никакого suppression cleanup/error; PG ещё не запускался.

Actual runtime RED на 5f361c2 сохранён: timestamp_spelling 4 FAIL / 4 PASS, exit 1, `/tmp/taxi-pending-transfer-raw-red1.log/.exit`. Noncanonical timestamps дают bump409/entry owner41 без конкурента; canonical проходят. Correction 2f743f3 добавила same-row raw TEXT/typeof capture и exact BINARY/String CAS только для SQLite. Первый correction run дал 12 FAIL, exit1 (`raw-green1`): ORM RETURNING не сопоставил unlabelled CAST expression, NoSuchColumnError. Это отдельный сохранённый implementation failure, не CAS PASS. Явные labels в 8e8a5f2 исправили результат mapping; повтор `raw-green2` дал 12 PASS / 105 deselected, 1.70 с, exit0. Включены canonical/noncanonical normal transfer и AFTER-no-op raw-changed-but-same-decoded loser для всех четырёх writers. Общая приёмка пока не завершена.

## Expanded author and independent checks

Source 8e8a5f2; final tests c7f4f73 + 683b3b2. First expanded SQLite: 456 PASS / 24 SKIP, 8.78 с. Added real authenticated waypoint/decline/driver-cancel controls initially produced 2 PASS / 1 FAIL (`/tmp/taxi-pending-transfer-author-http1.log/.exit`): exact expected waypoint dict omitted existing canonical `done=False`. Source parse_waypoints confirmed that field; correction only adds the literal expected field, no outcome loosening. Revised author SQLite 459 PASS / 28 SKIP, 9.23 с (`author-sqlite2`); three further before_commit observers passed separately (`precommit1`, 3 PASS). Before-commit events observe actual transactions; commit and SQL are not replaced.

Author real disposable PG on 683b3b2: 131 PASS / 19 dialect SKIP, 7.47 с, exit0 (`/tmp/taxi-pending-transfer-author-pg1.log/.exit`). Includes 18 real concurrency cases, real backend blocking, both commit/rollback and exact database error22012, actual cancellation/advance-first orderings. Default expiration is explicit in reverse advance controls; no blanket early-guard claim with expire_on_commit=False. Cleanup verified empty.

Independent latest SQLite seven-file regression: 462 PASS / 30 PG SKIP, 8.45 с, exit0. Independent separate fresh PG modes: pending-transfer-writers 131 PASS /19 dialect SKIP, 7.58 с; entry-writer-locks 71 PASS /38 SKIP, 3.23 с; route-capture-locks 160 PASS /21 SKIP, 6.95 с; actual-orm-counter 9 PASS, 21.90 с; unchanged default 255 PASS, 14.60 с. All actual exits0 and cluster cleanup empty after each. Logs `/tmp/taxi-pending-transfer-independent-{sqlite,pg,entry,route,orm,regression}.log/.exit`. Secrets scan six changed files: 0 findings. No new model/adapter/schema/account/worker activation. FULL still pending, independent final source/report readback completes separately.

Independent final narrow PASS подтверждён для runtime8e8a5f2/final tests683b3b2: открытых P0/P1/P2 нет. Все13 ORM child reports перечитаны:39 expected controls,0 unexpected attempts,8 guarded libpq probes; child SQLite files удалены. Исходные failures сохранены выше. Producer/terminal clear/dirty producer/old+1 coincidence остаются blockers. После освобождения ресурса Amio запускается отдельный backend FULL; прежние FULL не засчитываются этому runtime.

## FULL and final scope

Backend FULL on `7196f09bb0eb907b0203cf988c4ded023e8607d1`: **8249 PASS, 861 SKIP, 815 warnings, 930.17 с (15:30), actual exit0**. Logs `/tmp/taxi-pending-transfer-full1.log`, `.exit`, `.sha`; warnings not suppressed, skipped cases not counted as proof. Own recorded FULL TMPDIR safely removed after actual exit, disposable PG cluster paths absent, source tree clean. Amio received the resource via followup only after this verification.

This verified runtime patch transfers an existing eligible clean pending tuple with successful owner revision changes; it does not create a durable account-deletion continuation. Producer/worker activation, authoritative terminal disposition, all-writer model/cutover, finite budget, dirty-producer contract, raw writer enforcement, stale tokenless clients and accidental old+1 equality remain unresolved. Schema/model/account/worker were not modified; no main merge or deployment. New terminal/wait plan remains outside this repository freeze as a read-only draft; expired with future wait is not blindly treated as final. Previously paused operations were not repeated.
