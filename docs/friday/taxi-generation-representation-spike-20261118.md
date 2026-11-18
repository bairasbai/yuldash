# Generation representation spike: только изолированные тесты

Статус: независимый pre-code PASS, не выбор протокола. Цель — проверить exact NUMERIC PostgreSQL и канонический TEXT/Python int SQLite как альтернативы BIGINT, не менять приложение/модель/миграцию/409 и не активировать durable workflow.

## Изоляция

Новый dedicated allowlisted runner mode generation-representation, только новый файл; existing default255 и driver-slot-app-compat5 неизменны. Guard до pytest/conftest imports, только заново созданный disposable PostgreSQL; собственная nonce schema, все таблицы fully qualified либо явно локальная transaction schema. Не устанавливать DDL на application tables. Threads/connections завершаются перед собственным cleanup; cleanup error не скрывать. SQLite только временный собственный файл.

## PG матрица

Две NUMERIC колонки revision/resume: exact Decimal input/result без float, MAX−1→MAX→MAX+1, 2**53+1, 10**40+1; NULL и equality обеих колонок. Один UPDATE increment обеих, stale equalityCAS rowcount0. Два concurrent UPDATE: разные backendPID, фактически наблюдаемый pg_blocking_pids wait, два реальных commit дают N+2; bounded timeout. Отдельно rollback и невидимость uncommitted value независимому connection.

Unconstrained NUMERIC показывает actual fraction/NaN/Infinity, не объявляет их корректным counter. Отдельная CHECK fixture явно rejects nonfinite/fraction/negative exact23514+constraint. Python validator доint проверяет finite/integral/sign и bounded digits/exponent, не допускает огромной аллокации. Это prospective contract model, не действующий app guard/выбор signed semantics.

## SQLite матрица

Numeric loss фиксируется как наблюдение конкретного установленного dialect/DBAPI: typeof storage, raw SQL value и SQLAlchemy value. TEXT canonical: '0' либо positive digits без leadingzero/sign/space/exponent; positive prototype использует только Python int increment и exact TEXT equalityCAS обоих полей. SQL TEXT+1 отдельный negative control, не положительная реализация. Два connection/BEGIN IMMEDIATE с наблюдаемым busy/блокировкой и bounded timeout, reread после writer release; это database writer lock, не PostgreSQL row lock. Rollback/nonvisibility обязательны.

## Границы

Exact storage не доказывает ORM/public API/JSON compatibility, migration lock/rewrite cost, downgrade при больших значениях, mixed oldwriters, negative historicalABA или readiness нового generation. NUMERIC ограничен implementation; Python parsing ограничен отдельным spike budget. UUID adjunct остаётся альтернативой, protocol/cutover ещё не выбраны. Все результаты и отклонения фиксируются; PASS diagnostics не означает production fix.

## Реализация и авторские результаты

Frozen134440a: новый test-only файл и два allowlisted runner modes. generation-representation использует --noconftest, без app imports/DDL; matching-resume-known-gaps использует обычный conftest только в fresh guarded cluster. Default255 и appcompat5 argv неизменны. Unknown/arbitrary/extra args reject64 доbootstrap. AuthorSQLite35 PASS/17PGSKIP0.47с; independentSQLite35 PASS/17SKIP0.45с; Ruff/bash/diff PASS, staticreview безP0/P1/P2.

Author actual representation mode: **52 PASS,0.73с,actualexit0**, лог `/tmp/taxi-generation-representation-pg.log` / `.exit`. Из52 — 17PGcases плюс35unit/SQLite; не называть весь52 толькоPG. Реальные PG NUMERIC increments/CAS/rollback и observed backend lock wait прошли, двеactualcommit дают точное N+2. Exact23514 ownCHECK, valid0/largeinteger/NULLcontrols прошли. Ownschema absence и disposablecluster cleanup подтверждены. Это положительный representation prototype, не compatibility/cutover/runtime approval.

Author known-gap actualPG mode: **2 PASS,2.39с,actualexit0**, `/tmp/taxi-known-gaps-pg.log` / `.exit`; old actualHTTP/advance ограничения воспроизведены, cluster cleanup подтверждён. Independent actualPG обоихрежимов+unchanged255regression ещё впереди; новыйFULL не заявляется, приложение не менялось.

## Независимая финальная приёмка

Frozen134440a независимо проверен в трёх последовательных fresh clusters: known gaps **2 PASS1.92с**, representation **52 PASS0.72с** (17PG+35unit/SQLite), unchanged default regression **255 PASS13.76с**, все actualexit0. Логи `/tmp/taxi-known-gaps-independent-pg.log`, `/tmp/taxi-generation-independent-pg.log`, `/tmp/taxi-generation-independent-regression.log` и соответствующие `.exit`. Ownschema/cluster cleanup и отсутствие остаточных каталогов подтверждены. Final review PASS без открытыхP0/P1/P2, secrets0, ресурс переданAmio.

Проверено: PostgreSQL NUMERIC сохраняет показанные большие integral values и CAS при наблюдаемой конкуренции; SQLite canonicalTEXT/Pythonint сохраняет их в ограниченном prototype, SQLite Numeric counterexample остаётся. Не проверены production migration/cutover, adapter совместимость существующих SQLAlchemy/SQLModel writers, schema downgrade больших значений, mixed fleet и negative historicalABA. Ни один новый generation protocol не выбран/подключён. Known-gap PASS по-прежнему доказывает, что текущий runtime ещё не реализует durable continuation. App/models/migrations не менялись; новыйFULL не запускался и не заявляется.
