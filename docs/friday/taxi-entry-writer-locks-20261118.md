# Start/advance/reassign и отдельная SQLite route boundary: план до кода

База local9823098 / remote2c9855b. Два отдельных пакета, общий файл instant_service.py пишет только один автор. Никакой production миграции, нового account ограничения, модели exact-counter или pending activation. Цель подготовки остаётся durable продолжение по выбору Байраса; эти срезы сами его не реализуют.

## Пакет A: actual entry writers, прежние BIGINT правила

Область: start_matching (3576–3610), advance_after_no_accept (3613–3646), _reassign_after_driver_cancel (2986–3039). `_bump` не использовать как готовый increment: этим writers нужны generation+status/lifecycle одним UPDATE и обычный no-op/commit вместо его conflict exception.

Предложение маленького private lock/read helper, немедленно вызываемого тремя writers:
- До implicit loads/SQL проверить dirty search_revision target; для reassign(id) проверить присутствующий identity-map object без загрузки. Этот неподдерживаемый внутренний вход должен отвергаться без commit, иначе commit сохранит отвергнутое изменение; proposed прежний bilingual conflict error с caller rollback. Account не вызывает эти writers внутри удаления и не меняется. Нужна независимая оценка такого precondition.
- Сохранить прежнюю ORM flush семантику: после dirty-revision check явный flush до owner lock/read, затем no_autoflush для read/CAS/capture. Unrelated pending fields не discard. Dirty transition/criteria могут изменить свежий owner до lookup, как прежний autoflush; это проверяется, не считается stale adoption из старого snapshot.
- PG SELECT scalar owner FOR UPDATE. SQLite guarded no-op UPDATE RETURNING scalar owner, полностью consume/close; lock ДО fresh owner capture. Никакого BEGIN IMMEDIATE посреди caller transaction/User lock.
- Guard для start: _MATCHABLE и прежний numeric range, lifecycle валиден согласно существующему контракту; reassign: cancelled/range/valid lifecycle. SQL predicate для SQLite NULL либо ровно32 lowercase hex должен соответствовать Python validator, включая длину/пустое/nonhex.
- **Advance не получает нового lifecycle validation guard:** нынешний advance может перевести invalid lifecycle offered в searching, а matcher потом отказывается от lookup. Это не исправляется незаметно данной конверсией. Optional driver/token/times остаются только когда provided; None не превращать в обязательное IS NULL.
- Свежая revision None→1, 0..MAX-1→n+1; missing/MAX/negative/ordinary loser дают прежний no-op commit/return, без внешних effects. Не требовать expectedrevision из stale ORM: нынешний start намеренно работает с текущей DB generation.
- Финальный UPDATE: прежние присваивания и optional predicates плюс exact old revision/status/lifecycle CAS, literal next. Source owner/capture через DB scalar, не identitymap. Rowcount1 только winner; под тем же lock снять реальную assigned revision/lifecycle, прежний commit и ownership проверки перед Redis/notify/matcher.
- Start searching сохраняет lifecycle, новый цикл вращает по существующему flag/nonNULL. Advance сохраняет lifecycle/round/searching_at. Reassign сбрасывает assignment, вращает lifecycle и увеличивает reassigns по прежнему SQL правилу; не менять цену/маршрут/promo.

SQLite valid path содержит дополнительный UPDATE. Invalid lifecycle no-write обещается только там, где existing writer уже его отвергает (start/reassign), не advance. Guarded no-op trigger observability и CAS failure/rollback проверяются; deployed triggers не обследованы. Numeric range/coalesce остаётся BIGINT-only, не готово к TEXT cutover. Весь lock graph и tokenless replay не решены.

### Проверки A

Для всех трёх NULL/0/MAX-1, MAX/negative/missing, stale identity map; start все _MATCHABLE/terminal, lifecycle flag on/off/NULL/nonNULL/invalid. Advance mismatch каждого optional token/driver/time и legacy None; invalid lifecycle outcome точно как baseline. Настоящий cancel→reassign и прежние route/price/promo/reset. Dirty revision no SQL/no commit, unrelated/transition dirty flush сохранён. SQLite два physical connections BUSY/explicit rollback и no lost increments; PG distinctPID/blocking и commit/rollback. Trigger validextraUPDATE/invalid0/CAS0 no external effects. Postcommit supersession до Redis/notify/matcher. Existing revision/lifecycle/route/known-gap suites и FULL после independentPG; seeded pending gaps остаются, transfer не добавлен.

## Пакет B отдельно: stale route boundary до refresh

Подтверждено двумя actual SQLite interleaves и старым helper control: cancel может commit после незащищённого route refresh; subsequent route/revision всё ещё изменяются при cancelled status. Цель узкого исправления: destination/waypoints через `_lock_route_mutation`, не весь route/fallback/quote graph.

Предложение: сохранить snapshot ожидаемого route-owner tuple, затем SQLite conditional no-op UPDATE по id + всему исходному tuple + допустимому status под no_autoflush ДО refresh. Mismatch→существующий route conflict и caller rollback; matched→writer lock удерживается до callercommit. Refresh и сравнение snapshot остаются. PG прежний row lock, никакого User lock. `_bump` не использовать для lock: accepted-route изменение не должно increment search revision.

Это новый реальный UPDATE/trigger event, а не pure read. Схема только disposable/source, не deployed. Точная формулировка dirty snapshot/implicit load и синхронизации ORM до кода требует review. Fallback имеет отдельный refresh и не объявляется защищённым этим narrow пакетом.

### Проверки B

Два физических SQLite connections: cancel/accept уже выиграл→route conflict без новых route/price/revision/token изменений; route lock выиграл→реальный BUSY конкуренту до записи, commit/rollback release; прежняя read transaction→honest BUSY_SNAPSHOT/rollback без retry. После защищённого refresh другая отмена не должна commit до release. TEMP trigger дополнительный no-op без revision arithmetic, rollback events. PG actualPID/blocking оба порядка, no added Userlock. Existingactual known-boundary tests меняются только после различающего RED/GREEN, исходное доказательство сохраняется.

До реализации A и B нужен независимый plan review. Пакеты не объединять в один необозримый diff, не активировать durable producer/consumer. Старые paused операции остаются на паузе.

## Review A: условия до реализации, B остаётся на паузе

Journal: A отдельным пакетом условно разрешён; B runtime GO НЕ получил. Для A SQLite no-op включает все existing eligibility predicates writer, включая optional advance driver/token/times; mismatch не должен вызывать лишний UPDATE. SQL lifecycle guard только start/reassign: NULL либо length(text)=32, length(CAST AS BLOB)=32 и NOT GLOB '*[^0-9a-f]*'. Byte-length дополнительно исключает embedded NUL suffix, который SQLite string functions могут не видеть. После capture остаётся Python validator; equivalence проверяется uppercase/nonASCII/empty/NUL (включая 32hex+NULsuffix). Advance invalid lifecycle baseline не ужесточается.

Dirty revision rejection — явно новый INTERNAL precondition, старый bilingual conflict без SQL/commit, не account restriction. Передавать id через inspect(order).identity, не читать expired order.id до guard; reassign lookup по identity-map безload. Для persistent существующих callers identity известна; transient/detached contract проверить и не добавлять implicit admission.

Flush policy уточнена: после dirty check выполнять flush ТОЛЬКО если session.autoflush сейчас True. Session(autoflush=False) и no_autoflush context не обходить unconditional flush; commit по-прежнему может flush как ранее. Далее закрытая no_autoflush read/CAS/capture section. Финальный UPDATE/ORM synchronization должен сохранять прежнюю семантику последующего commit, не оставлять stale pending owner writeback; проверить pending new/unrelated objects, dirty status/lifecycle, expired identity и postcommit expiration в обоих режимах. Production source search нашёл no_autoflush только вне этих entry callers; это не замена тестам дополнительных поддерживаемых Session modes.

B требует отдельной policy полностью loaded clean snapshot, expired/unloaded/dirty fields ДО любого implicitSQL/autoflush. До её принятия route runtime не менять. Guard для B не распространяется на fallback, PG rowlock/User order не меняются. Общая cutover/activation всё ещё запрещена.

## A: первый freeze и независимые замечания

Runtime ec94dc7 меняет только private entry helpers и start/advance/reassign; existing scoped 158 PASS, exit0 (`/tmp/taxi-entry-A-existing-GkvHfZ.log`). Новые tests пока не frozen: первый run 72 PASS / 2 FAIL / 6 PG SKIP (`/tmp/taxi-entry-lock1.log`, `.exit`). Оба FAIL касаются ожидания missing return: при expire_on_commit=False session.get может вернуть прежний identity-map object, хотя SQL row отсутствует; фиксируется точный контракт modes/noeffects, не меняется runtime ради фиктивного None.

Independent review выявил P2 SQL lifecycle equivalence: raw SQLite BLOB32 проходит length/GLOB guard, но Python validator отвергает bytes. Исправление должно добавить typeof(lifecycle)='text' в nonNULL ветку и доказать invalid0 для hexbytes и NULbytes; NULL отдельно. Это реальный guard недочёт candidate, не production дефект/активация. Отдельная гипотеза NULL CAS loser снята: _owns_search требует revision is not None, tuple(None,None) не разрешает matcher.

Runtime P2 исправлен 2e21e8b: typeof='text' только SQLite start/reassign nonNULL guard. Focused author123 PASS exit0 (`/tmp/taxi-entry-typeof-YcLOX6.log`); independent static/in-memory SQL control подтвердил NULL/text32 pass, BLOB/NULsuffix/uppercase/nonASCII/empty reject. Advance контракт не изменён.

Авторский tests freeze1d07902: 103 PASS / 6 PG SKIP, exit0, 2.45с (`/tmp/taxi-entry-lock3.log`, `.exit`), включая actual rawBLOB negative controls. До PG независимый review нашёл ещё fixture isolation/portability условия: Redis close должен выполняться даже при DB cleanup failure; SQLite audit-only test обязан skip до seed, а PG не может хранить NUL/overlength в VARCHAR32. Для advance на PG сохраняются реальные представимые malformed controls ('', uppercase32, nonASCII32, len31); лишь NUL/overlength получают честный dialect skip, schema/guard не обходятся. ac5b8df fixed runner mode entry-writer-locks, default255 и guard unchanged. Финальная приёмка/PG впереди.

Независимый expanded scoped: 296 PASS / 8 PG SKIP, 7.65 с, exit0 (`/tmp/taxi-entry-independent-scoped2.log`, `.exit`). Первый collection exit4 был filename error reviewer, сохранён отдельно, не app failure. Test-only corrections db86f96: Redis finally даже при DB cleanup exception; preseed SQLite-only/NUL/overlength skip на PG. Independent focused: 103 PASS / 6 PG SKIP, 2.08 с, exit0 (`/tmp/taxi-entry-independent-focused.log`, `.exit`), static PASS и открытых P0/P1/P2 нет до PG.

Author actual disposable PG entry-writer-locks: **71 PASS / 38 SKIP**, 2.65 с, actual exit0 (`/tmp/taxi-entry-writers-author-pg1.log`, `.exit`). SKIP обозначают диалектные SQLite-only/непредставимые PG values, не PG PASS. Runtime2e21e8b/tests db86f96/runnerac5b8df. Secrets0. Independent PG/default и FULL выполняются отдельно, публикации A пока нет.

Independent actual PG: **71 PASS / 38 dialect SKIP**, 3.13 с, exit0 (`/tmp/taxi-entry-writers-independent-pg.log`, `.exit`). Все 6 parametrized blocking cases (3 writers × commit/rollback) реально PASS с distinctPID/pg_blocking_pids/fresh revision. Unchanged default regression: **255 PASS**, 11.78 с, exit0 (`/tmp/taxi-entry-writers-independent-regression.log`, `.exit`). После каждого fresh cluster cleanup пуст. Final narrow A review PASS, открытых P0/P1/P2 нет. FULL запускается отдельно; B/TEXT cutover/durable recovery не приняты этим результатом.

## FULL A и проверенный итог

FULL на 07d4bf0 (runtime2e21e8b/tests db86f96/runnerac5b8df) завершён с actual exit0: **7847 PASS / 485 SKIP / 815 warnings**, 748.00 с (12:27). Логи `/tmp/taxi-entry-writers-full1.log` и `.exit`. PG skips полного SQLite набора не объявляются PG PASS; отдельные actual PG71 и regression255 результаты выше. После фактического exit ресурс передан Amio.

Проверен только пакет A: реально вызываемые start/advance/reassign получают свежий owner под lock, вычисляют Python next и меняют status/revision/lifecycle атомарно с exact CAS. Обычные BIGINT/NULL/MAX/negative/no-op commit и invalid advance lifecycle правила сохранены; dirty revision явно новый internal precondition, не ограничение удаления аккаунта. SQL numeric guard остаётся BIGINT-only. Account/model/migrations/pending activation не менялись; durable поиск ещё не реализован. B route boundary не исправлена этим пакетом и остаётся без runtime GO до отдельного capture контракта. Custom deployed triggers, полный lock graph и cutover не проверены. Main/production и paused операции не затронуты.
