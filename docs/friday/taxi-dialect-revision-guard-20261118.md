# Dialect-aware revision eligibility: узкий план совместимости

База localba737c8 / remote1df94b09f6d99e869ff0f6d0018ad0e74daca95d. Expanded ORM experiment доказал: proposed SQLite TEXT mapping делает прежний coalesce/range guard неверным для NULL и большого canonical числа. Текущий production mapping остаётся BIGINT. Цель пакета — поправить общий eligibility guard без нового budget, schema/model/adapter cutover или durable activation.

## Предпочтительный исполнимый scope

Только `_can_increment_search_revision(dialect_name)` в instant_service и два production call sites (`_bump_search_revision`, `_locked_search_entry`) получают явный dialect из session. Существующие lock capture, Python exact-int/range validation, literal increment, owner CAS, optional predicates и commit/effects порядок не переписывать. Не вводить новый helper framework.

PG оставляет нынешний numeric predicate. Он не обещает отвергнуть любую дробь/raw corruption до SELECT; существующая fresh Python validation под lock остаётся обязательной. Максимум остаётся 2**63−1 для всех writers. NULL→1, MAX−1→MAX и отказ increment при MAX/negative не меняются. Это не бесконечный progression и не account deletion policy.

SQLite ветви по фактическому `typeof`:
- NULL разрешён явно, без coalesce type ambiguity.
- INTEGER и REAL: прежний numeric range `>= 0 AND < MAX` через type_coerce(column, BigInteger) с явно BigInteger-typed SQL literals (без SQL CAST). REAL сохраняется здесь намеренно: прежний predicate мог захватить row/no-op, затем Python int-only validation отказывала. Не устраняем этот наблюдаемый UPDATE/trigger задним числом. Это eligibility для lock, не разрешение mutation дроби.
- TEXT: только canonical ASCII nonnegative decimal, ровно `0` либо первая цифра1..9 и остальные digits, без leading zeros; codepoint length равен byte length и положителен. Exact limit: длина меньше19 либо длина19 и BINARY lexicographic `< '9223372036854775807'`. Использовать `type_coerce(column, String)` и явные String literals, не SQL CAST/REAL/INTEGER conversion, не SQL arithmetic.
- BLOB и некорректный TEXT false. Сравнить именно с прежними observed SQL results/trigger effects, не объявлять equivalence для произвольной повреждённой схемы.

Это меняет допустимость canonical TEXT для будущего test-local mapping, но нынешний BIGINT mapper после capture всё ещё может вернуть str и отказать на Python validation. Пакет НЕ объявляет поддержку TEXT без model/schema rollout. Текущие INTEGER/REAL/NULL stored semantics должны быть доказательно сохранены; если выяснится расхождение, сохранить FAIL и отдельно решить scope, не ужесточать скрыто.

## All-writer call graph, границы

Общий guard используется в `_bump_search_revision` (route invalidation и fallback) и `_locked_search_entry` (start_matching, advance_offer, reassign). Existing optional driver/token/time/lifecycle predicates входят в тот же guarded SQLite no-op. Advance не получает дополнительный invalid-lifecycle отказ. Dirty revision/internal clean snapshot contracts, autoflush=False/no_autoflush и postcommit expiration сохраняются. Route lock может сделать собственный более ранний no-op; пакет не обещает отсутствие всех SQL/trigger effects route graph.

Actual BIGINT valid row продолжает иметь прежний дополнительный SQLite UPDATE. Fixture trigger tests доказывают конкретные counts/rollback, не отсутствие custom triggers в deployment. Никаких User locks/global lock-order changes, no new outer commit/rollback helpers, no pending producer/consumer/transfer. Python MAX validators остальных labs и matcher не меняются.

## Доказательства до публикации

1. Сохранённый RED: published ORM experiment показывает NULL false/10**40+1 true в old TEXT guard. Новый actual guard в том же test-local mapping должен совпасть с прежним BIGINT integer-domain truth table, не расширять MAX.
2. Current BIGINT SQLite/PG: NULL/0/2/9/10/100/MAX−1/MAX/negative, dirty/unrelated/autoflush cases; compiled BigInteger/String literals и no SQL numeric casts в TEXT branch. REAL fraction/out-of-range, BLOB и malformed TEXT: direct SQL predicate plus guard no-op audit controls, rollback. Explicit physical typeof/bytes, не предполагать что affinity сохранила исходный spelling.
3. Proposed actual mapper: SQLite TEXT/PG NUMERIC, NULL и canonical limits вплоть до128digits, malformed signs/whitespace/exponent/Unicode/NUL отдельно на собственных fixtures. Общий adapter source не меняется, int input retained; eligibility не input precision proof.
4. Actual start/advance/reassign/route/fallback: обычные increment/optional loser/CAS/effects/MAX отказ, обе competing winner orders и real lock wait/SQLite honest BUSY. Existing approved regression suites переиспользовать, добавить различающие controls, не fake SQL/commit. Исторические diagnostics не переписывать как будто старого результата не было.
5. Independent pre-code review, frozen static, author + independent SQLite/real disposable PG, existing regression, затем FULL для runtime изменения. Ресурс согласовать с Amio. Только после PASS отдельная ветка, no main/prod/paused operations.

## Следующий переход, не часть пакета

Даже исправленный guard сохраняет старый MAX: future exact model/bind/validator rollout требует согласованного изменения всех validators, схемы, старых writers и persisted sources. 128-digit budget конечен, negative-history ABA и tokenless replay не решены drain-ом. Durable automatic continuation остаётся пользовательской целью; этот пакет не добавляет отказ удаления аккаунта и не объявляет цель завершённой.

## Независимый pre-code контракт

Journal conditional PASS после следующих уточнений. `type_coerce` меняет SQLAlchemy expression/bind typing, НЕ физическую SQLite affinity: parity INTEGER/REAL доказывается на actual BIGINT schema, TEXT domain — на proposed actual TEXT mapping, не на произвольном declared type. Compiled numeric bound processor сохраняет MAX exact int, TEXT bound exact str; BINARY collation ставится непосредственно на сравниваемом TEXT expression. Byte check `CAST AS BLOB` допустим, запрещены numeric CAST/REAL conversions и SQL arithmetic.

Canonical condition имеет явные скобки: length>=1, byte length=text length, NOT GLOB invalid ASCII digits, (`0` либо first1..9), и (length<19 либо length=19 AND BINARY lex<MAX). Tests: exact adjacent 9223372036854775806/7/8, 18/19/20 digits, zero/-0/00, NUL suffix, Unicode, BLOB. NULL — отдельный IS NULL. REAL samples сравнивать с фактически сохранённым value/typeof: математический MAX−1 как REAL может округлиться к MAX. Guard eligibility не permission mutate; fresh Python int-only validation неизменна.

Для dialect_name отличного от sqlite намеренно сохраняется прежний numeric fallback, без нового claim поддержки неизвестных dialects и без нового unsupported error. Полный rg call graph, включая diagnostic/private tests, проверяется после signature update. PG numeric SQL predicate остаётся прежним. BLOB/malformed TEXT расхождение baseline означало бы отдельное scope решение, не скрытую tightening. Исторический published RED остаётся в прежней ветке/документе; новые assertions явно fixed-guard controls. Финальный runtime пакет требует full regression и FULL после независимой приёмки, не только нового predicate test.

## Первый авторский отказ и независимая диагностика

Runtime79b5429 / tests ead94dd прошли independent static без P0/P1/P2. Первый actual SQLite scoped прогон: 1 FAIL, 329 PASS, 12 SKIP, 5.15 с, exit 1 (`/tmp/taxi-dialect-guard-author-sqlite1.log/.exit`). Единственный FAIL — новая fixture ожидала TEXT для raw bind `1\0` в BIGINT column, но observed typeof был INTEGER. Отказ произошёл до old/new predicate comparison данного case; настоящий synthetic TEXT/NUL negative control прошёл. PG/FULL и повтор до анализа не запускались.

Два независимых in-memory SQLite3.53.1 probes подтвердили attribution: исходный TEXT bind hex3100 сохраняется BIGINT affinity как `(1, integer, 31)`, а в TEXT column остаётся `1\0`, hex3100. Это boundary исходного spelling vs stored value, не доказанный runtime regression. Reviewer разрешил только expectedinteger+явный exact stored readback, сохраняя TEXT/NUL rejection без изменений. Исходный FAIL не удаляется и не переименовывается в PASS; после correction нужен новый scoped прогон, PG и FULL по плану.

## Исправленный адресный прогон

Test-only correction a18a6ae фиксирует исходный TEXT bind hex3100 и точный stored tuple `(1, integer, 31)`; runtime79b5429 и synthetic TEXT/NUL rejection не менялись. После независимого разрешения correction author SQLite2: 330 PASS, 12 SKIP, 6.16 с, actual exit 0 (`/tmp/taxi-dialect-guard-author-sqlite2.log/.exit`). Исходный sqlite1 exit1 сохранён отдельно.

Author real PG последовательно: locked-revision-bump 10 PASS/14 SKIP, 2.29 с; entry-writer-locks 71 PASS/38 SKIP, 3.58 с; actual-orm-counter 9 PASS, 21.10 с; default regression 255 PASS, 14.44 с. Все actual exit 0, логи `/tmp/taxi-dialect-guard-author-{locked,entry,orm,regression}1.log/.exit`. ORM 13 child reports complete/0 unexpected network; обе actual guard truth tables (SQLite proposed TEXT, PG NUMERIC) теперь 14/14 совпали с прежним BIGINT-domain expectation. Примеры `/tmp/yuldash-actual-orm.utsxysqf/report.json` и `.nh37zugx/report.json`. Own cluster INywoVcw удалён, SQLite child DB и synthetic TEXT directories отсутствуют, clone teardown guards прошли. Independent повтор и FULL ещё обязательны, не заменяются этими counts.

## Независимая scoped/runtime приёмка

Runtime79b5429 / tests a18a6ae, дальнейшие изменения только docs. Independent SQLite330 PASS/12 PG SKIP, 6.38 с; PG locked10 PASS/14 SQLite SKIP, 2.26 с; entry71 PASS/38 dialect SKIP, 2.86 с; actualORM9 PASS, 22.03 с; default255 PASS, 14.77 с. Все actual exit0, логи `/tmp/taxi-dialect-guard-independent-{sqlite,locked,entry,orm,regression}.log/.exit`, cleanup пуст после каждого. 13 child reports successful, 39 expected controls/0 unexpected, 8 guarded libpq probes; обе truth tables14/14 теперь соответствуют прежнему BIGINT-domain, public payload не изменён. Synthetic TEXT directories/SQLite DB и sidecars удалены.

Final narrow independent PASS без P0/P1/P2. Source diff не затрагивает lock/CAS/commit/lifecycle/optional guards или Python MAX. REAL trigger parity подтверждена, type_coerce не выдаётся за смену physical affinity. Secrets scan 5 changed files: 1 existing synthetic child JWT finding, без реальных credentials, runtime/new predicate без findings. Исходный fixture FAIL и correction сохранены. FULL остаётся обязательным следующим gate до публикации runtime-пакета.

## FULL backend и итог

Новый FULL backend на 63e5b07268678d19421adfa85906998d500ebf38 завершён actual exit 0: **8125 PASS, 843 SKIP, 815 warnings**, 845.22 с. Команда из backend: isolated fresh TMPDIR, DATABASE_URL/proxies/test PG mode удалены, TAXI_TRIED_LIFECYCLE_ENABLED=false, TZ=UTC, `/tmp/taxi-next-venv/bin/python -m pytest tests -q --disable-socket --allow-unix-socket`. Лог `/tmp/taxi-dialect-guard-full1.log`, exit/sha рядом. Warnings сохранены в логе, не подавлялись; skips не объявлены PASS. Рабочее дерево после прогона чистое; собственный FULL TMPDIR удалён, scoped PG cluster directories отсутствуют. Ресурс Amio передан через followup только после actual exit и cleanup.

Готов именно узкий runtime guard compatibility пакет: старый BIGINT domain/REAL capture semantics сохранены, proposed TEXT eligibility больше не путает NULL и большие строки. Source model/schema, Python MAX, account API, pending producer/consumer, rollout и production не менялись. FULL backend не означает Android/UI проверку, готовый cutover или завершённое durable автоматическое восстановление.

Будущая техническая граница: одной заменой MAX на128digits бюджет расширять нельзя. Numeric SQLite bind остаётся native integer; отдельный собственный in-memory probe `SELECT ?` с Python `10**128−1` дал `OverflowError: Python int too large to convert to SQLite INTEGER`. Текущий MAX не изменён и не вызывает этот отказ. Для будущего unified budget/model/schema rollout нужны отдельные exact bind/validator решения и исходные ограничения persisted replay/old binaries/negative-history/tokenless клиентов. Это не повод вводить новый отказ удаления аккаунта или молча обнулить поколение.
