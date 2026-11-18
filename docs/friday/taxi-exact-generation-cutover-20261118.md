# Конкретный путь exact generation: план до реализации

База b1e5437 / remote2f9d65699cc9865034164aac7baf263fab7d042d. Representation spike доказал точность PostgreSQL NUMERIC и SQLite TEXT+Python int в отдельных таблицах, но не совместимость приложения. Предлагаемый путь для независимой оценки: сохранить монотонную integer generation, использовать exact NUMERIC в PG и canonical TEXT в SQLite через единый строгий adapter. Не выбирать UUID adjunct первым: он заменяет идентичность поколения и все ownership guards, тогда как exact counter сохраняет equality/monotonic смысл. Это условный инженерный выбор, не production approval.

## Контракт представления

Python domain: None либо настоящий int, bool/float запрещены. SQL PG: unscaled NUMERIC с exact Decimal binds/results; SQLite: canonical signed decimal TEXT. Никакого Numeric SQLite и SQL TEXT+1. Result conversion проверяет конечность/целочисленность до int, без float. Negative не нормализуется/не reset: signed monotonic chain допустим только после доверенной cutover границы, drain старых owners и аудита исходных значений. До этого отрицательное значение остаётся неизвестной историей, а не безопасно исправленным counter. NULL legacy→1 остаётся отдельной атомарной инициализацией.

Размеры ограничены технической ёмкостью PG/Python/приложения. Не обещать бесконечность или обход любой повреждённой строки. На этапе adapter тестируются BIGINT boundaries и значительно большие integers, fractional/NaN/Infinity/неканоническийTEXT reject; поведение за выбранным operational size budget ещё требует решения. Ограничение не должно молча превращаться в новый отказ удаления аккаунта.

## Increment без скрытой SQLite арифметики

Production design candidate: fresh scalar SELECT under existing Order lock, вычислить Python next=current+1, conditional UPDATE с exact old generation и всеми прежними guards. Оба поколения (search_revision и matching_resume_revision) используют один adapter. Не выполнять coalesce(column,0)+1 над SQLite TEXT; все места SQL increment нужно заменить согласованно. Lock+CAS сохраняют атомарность, но новый lock placement/flush/identity map должен проверяться отдельно, особенно advance callers без прежнего lock. Никакого User lock из Order-first helper. PG isolation errors propagate; no read-then-unlocked-write.

Инвентарь: _bump_search_revision(route/waypoints/fallback); start_matching; advance_after_no_accept; reassign; matcher NULL init; frozen snapshot/dispatch и accepted assignment guards; expiry/cleanup guards; все SQL-history labs validators/RETURNING. Неподключённые labs не активируются, но compatibility tests должны либо сохранять старый явно scoped контракт, либо согласованно обновить representation без новых callers. Все публичные serializers сохраняют отсутствие revision; private int не становится JS number.

## Безопасный cutover — только план, не действие

1. Аудит actualschema/type/data и всех readers/writers, включая workers и старые binaries; real DB здесь не читается.
2. Подготовить dual-dialect adapter и tested migration на disposable populated schema. PG type alteration может блокировать/переписывать таблицу; измерить, не обещать zero-downtime. SQLite batch/table rebuild сохраняет всеFK/index/constraints и данные, не копирует настройки в production.
3. Остановить новые ownership операции и drain in-flight matchers/workers; одного флага в новом binary недостаточно для старого. Доказать отсутствие старых owners перед переходом trust boundary. Не считать сам факт migration доказательством drain.
4. В согласованное окно изменить обе колонки и весь writer/read fleet, validate exact readback; старые BIGINT/MAX-gated binaries не возвращать после больших значений. Enable durable producer/consumer только после полного protocol review и новой проверки.
5. Downgrade допустим только если оба столбца integral и в BIGINT диапазоне, нет incompatible pending/in-flight работы и старые semantics восстановимы. Иначе fail beforeDDL; никакого clamp/reset/truncate. Backup не даёт права молча потерять новые заказы.

Этот cutover требует отдельного разрешения Байраса для main/production/реальнойБД и здесь не выполняется. Signed negative historicalABA считается снятым только относительно подтверждённой новой trust boundary, не задним числом.

## Следующий изолированный срез

Один unconnected adapter + pure exact-next/validation module либо test-local prototype, без изменения InstantOrder/migration/runtime. Настоящие SQLModel/SQLAlchemy roundtrip, bind/comparison/CAS, nullable values, ORM dirtyflush/refresh/RETURNING на PG и SQLite, concurrent lockedincrement и rollback. PG native +1 допустим только диагностический контроль, production-style prototype всегда Pythonlockednext. Набор value cases: None,-2,-1,0,2**53±1,MAX−1/MAX/MAX+1,10**40±1. Negativecases являются signed representation, не historicalABA proof. Не использовать adapter через model до independent acceptance.

Параллельная допустимая durable часть: immutable full-owner snapshot/claim policy (due clock/backoff/attempt saturation) может быть тестируемой чистой функцией на synthetic data, без account/writer wiring. Она не снимает cutover и не называется реализованным продолжением. End-to-end остаётся account transaction→offered-retained obligation→worker→allwriter transfer→accepted/terminal clear; current known gaps остаются.

## Принятый независимый prototype контракт

Journal PASS только unconnected adapter prototype, не cutover/runtime. Для этого среза выбирается явный **128 decimal-digit/exponent budget** до int/str conversion; validation error остаётся lab-only и НЕ переносится в account API. Python type(v) is int (bool reject); PG finite integral Decimal; SQLite canonical signedTEXT (0 либо -?[1-9][0-9]*), '-0','+0',leadingzeros,whitespace,scientificnotation отвергаются. sNaN/NaN/±Infinity и огромные exponent/digit cases проверяются до выделения больших строк/чисел.

SQLite TEXT обеспечивает equality, но НЕ numeric sorting/inequality/max/min/COALESCE arithmetic: отдельные negative controls и полный inventory выражений обязательны. SQLite writer lock через BEGIN IMMEDIATE ДОfreshread, FOR UPDATE недостаточно. Для обеих columns SQLModel dirtyflush/expire/refresh/RETURNING/NULL/comparison bind/staleidentitymap + rawphysical storage и independentconnection read. PG rowlock/blocking + exact staleCAS, два actualcommit, no timingmock.

Adapter не защищает rawSQL/oldwriter от fractional/nonfinite values: отдельно сохранить bypassdiagnostic и future DBvalidation blocker. Drain только activeprocesses недостаточен: persisted queues/snapshots/retry inputs, delayed messages, restart/rollback oldbinary могут replay послеcutover. Их inventory/revocation/trustboundary ещё не приняты. No InstantOrder/migration/wiring/409. Purepolicy отдельно synthetic/noIO, неrecovery.

## Проверенный текущий expression inventory (base b1e5437)

В production app найдено 43 упоминания search_revision. Опасные для SQLiteTEXT SQL expressions сосредоточены в instant_service: `_can_increment_search_revision` использует coalesce+числовые >=0/<MAX; `_bump_search_revision`, reassign, start_matching, advance_after_no_accept используют coalesce(column,0)+1. Эти выражения нельзя оставить после смены SQLite storage. Matcher NULL init — literal1+IS NULL, остальные snapshot/dispatch/expiry/accept ownership guards — scalar/equality, но bind coercion всё равно тестируется. Route/waypoints/fallback проходят через _bump. matching_resume_revision в app пока только model declaration, ни одного producer/consumer. Поиск source не нашёл production ORDER BY/MAX/MIN search_revision; это ограниченный static inventory данного checkout, не доказательство внешних SQL/старых binaries/планировщиков.

Lab modules init/write/reader/publication отдельно имеют BIGINT/nonnegative validators и scalar predicates; их старые границы нельзя молча объявить совместимыми с signed/exact generation. Никаких обновлений этих modules/InstantOrder на prototype этапе. Представление и equality adapter отдельно от полного writer arithmetic migration.

## Persisted/replay inventory и точная trust boundary

Read-only source audit base871498b: SQL current search_revision и nullable matching_resume_revision сохраняются, но actual DB values не читались, NULL всехintent не предполагается. SQL history хранит lifecycle, неrevision. Matcher/accepted guard/_OfferSnapshot/worker ORM batches держат owner in-process; durable serialized generation jobs в просмотренных paths не найдены. Tried Redis содержит driver IDs под order/lifecycle, неrevision; presence/GEO тоже неgenerationpayload. Это scoped source finding, не доказательство отсутствия внешнихdeploymentqueues/retry scripts.

Конкретный delayed input остаётся: WebPush/FCM и offline clients сохраняют offer_token/expiry, неsearch_revision. Decline expected_offer_token optional; старый tokenless decline при повторном предложении того же order тому жеdriver читает текущийoffer и может его отклонить. Accept тоже не требуетtoken. Server drain не устраняет этот replay. Exact counter не чинит token binding и не заявляется глобальной защитой любого старого действия.

Cutover checklist дополняется: классификацияactualintentrows поотдельномуразрешению; actualdeploymententrypoints/jobs/operatorretries; запретrestart oldimage/cron/systemd иunsafe rollback; запрет восстановленияserializedlabexpectedowners безновойprotocolvalidation. Не очищать Redis/pushqueues наугад. Доказательство будущейграницы формулируется как монотонность новых server-generated revisions послеустановленногодоверенногоперехода, не отсутствие всехстарыхкоманд. Legacy tokenlessHTTPreplay остаётся отдельно и требуетclient/APIcompatibilitywork; приостановленныеclientпакеты не повторяются.

## Adapter prototype freeze и проверки

Frozen b580c17: unconnected ExactGeneration/strict decoders/exact_next + SQLModel tests + dedicated generation-adapter runner --noconftest. Existing modes unchanged; no InstantOrder/migration/runtime callers. AuthorSQLite60 PASS28PGSKIP0.60с, independentSQLite60 PASS28SKIP0.64с; Ruff/compile/bash/diff PASS. Author actual mode88 PASS1.47с (28PG+60unit/SQLite), actualexit0, ownschema/cluster cleanup; `/tmp/taxi-generation-adapter-pg.log` / `.exit`. Independent actualPG+unchanged255regression впереди.

Сохранён факт первого отказа, не скрытый PASS: WIP доb580c17 дал **1 FAIL,59 PASS,28 SKIP**,0.83с,exit1. decode_decimal(Decimal('0E128')) ошибочно отклонялся value.adjusted()>=128. Исправлено исключение нуля, abs(exponent)<=128 сохранено и0E129reject. Исходный logfile по ошибке автора был перезаписан финальнымPASS; полного исходного файла сейчас нет, count/traceback подтверждены отчётом автора о tool output. ОтдельногоWIPSHAнебыло. Правило дальше — уникальный logfilename накаждыйrun, не выдавать пересказ за сохранённый rawlog.

Independent actual mode88 PASS1.33с (28PG+60unit/SQLite), unchanged default255 PASS12.44с, обаactualexit0, ownschema/clustercleanup и отсутствиеостатков подтверждены. Логи `/tmp/taxi-generation-adapter-independent-pg.log` и `/tmp/taxi-generation-adapter-independent-regression.log` с `.exit`. Final acceptance обоихlabs PASS безP0/P1/P2; zeroedge0E128/0E129 проверен, прежнийFAIL остаётся. Secrets0. FULLпакета запускается отдельно, никакогоcutoverapproval.

## Полный прогон и итог пакета

FULL на 9e817d3 завершён с фактическим exit 0: **7677 PASS, 424 SKIP, 815 warnings**, 735.38 с (12:15). Логи `/tmp/taxi-exact-generation-full.log` и `.exit`. Отдельные реальные PG доказательства приведены выше; SKIP в полном SQLite наборе не считаются PG PASS. После actual exit ресурс передан Amio.

Готов к публикации только изолированный lab-пакет: exact adapter и pure claim proposal. InstantOrder, production migrations, account/matcher/worker handlers не изменены; production callers отсутствуют. Cutover, API token binding, all-writer migration и durable end-to-end ещё не выполнены. Signed representation не доказывает историческую ABA-безопасность повреждённых данных; raw SQL и старые binaries обходят adapter. Первый WIP отказ и утрата его raw log не скрыты. Никакого нового ограничения удаления/409, main или production действий.
