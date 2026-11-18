# Phase 2: actual PostgreSQL NUMERIC domain и guarded downgrade

База local4642859 / remote8df3d6479ee2fa8a2525469fe88c60da552b7f6f. Отдельный TEST-ONLY пакет после принятой phase 1, НЕ app migration/cutover. Только новые disposable PG clones обоих actual bootstrap sources; runtime/model/adapter implementation/HTTP/SQLite не менять. Full fleet, persisted replay, finite-budget deletion policy и durable chain ещё не доказаны.

## Изоляция и предмет

Переиспользовать exact guarded source/bootstrap/per-case clone контракт phase 1: закрытые fixed source DB, nonce registry и quoted identifiers, guard admin/target, CREATE/DROP вне caller transaction, no FORCE, observed cleanup. Helpers импортируются как module, без unintended pytest fixture/test collection. Никакого подключения к прежнему завершённому кластеру.

Каждый case начинает с собственной BIGINT baseline, затем использует проверенный locked phase 1 upgrade обеих actual revision колонок в unconstrained NUMERIC. Global InstantOrder mapping не заменять: actual ExactGeneration lab TypeDecorator подключается только через test-local Core typed expressions для этих двух физических колонок. Это доказательство adapter/prototype storage, не production ORM compatibility.

## Domain controls обеих колонок

Exact int-only binds и raw SQL диагностируются отдельно. Проверить NULL, 0, 2**53+1, BIGINT_MAX-1/MAX/MAX+1, 10**128-1, negative representation sentinel. Adapter допускает signed bounded representation, но это НЕ разрешение отрицательной runtime generation. Для обеих колонок: typed bind/result, Python int exact type, RETURNING/independent raw Decimal readback, equality CAS winner/zero-row loser, commit/rollback. No floats, no patched guards/rowcount.

Через adapter 10**128 и exact_next(10**128-1) отвергаются явно; это конечная техническая граница lab, не новая account error policy. Через raw SQL тот же NUMERIC может сохраниться: отдельный counterexample обхода adapter. Аналогично отдельно raw fractional, NaN, Infinity/-Infinity и integral huge-scale/exponent samples. Inputs ограничены заранее небольшим числом символов/scale (например <=256), никакой безлимитной аллокации. Сохранять фактические raw textual value и Decimal.as_tuple/finite/integral facts; не предполагать, что PostgreSQL/DBAPI сохраняет исходный exponent spelling. Для NaN не использовать обычное Decimal equality как доказательство roundtrip.

Huge-scale integral `1.` с большим числом нулей может численно укладываться в range, но не decoder budget. Это исследуемая несовместимость storage/adapter domain, не исправлять normalization/constraint молча. Unconstrained NUMERIC не гарантирует finite/integral/digit policy; NUMERIC(128,0) не заменяет validation. Любой unexpected observed outcome сначала сохранить и разобрать, не принимать произвольный exception как PASS.

## Test-local downgrade gates и attribution

Обязательная caller transaction: actual autocommit false/READ COMMITTED, LOCAL bounded timeouts. ACCESS EXCLUSIVE до authoritative catalog/data read, fresh reread после wait. Проверить ожидаемые NUMERIC types/nullable/default shape и отсутствие unsupported dependencies. Затем all-three-resume-NULL gate; затем raw search generation finite/integral/nonnegative/BIGINT range. Отрицательная generation отвергается как protocol scope, не silently reset.

Важно: при nonNULL matching_resume_revision pending gate срабатывает РАНЬШЕ её range validation. Поэтому такие cases обозначаются именно pending rejection, не range proof. Прямой отдельный test-local numeric validator на ОБОИХ выбранных физических values доказывает finite/integral/range decision и его причину, но не считается production guard. Positive downgrade использует отдельный fixture с resume NULL, а не очищает ранее записанный sentinel ради PASS.

После preflight обе ALTER NUMERIC→BIGINT в одной transaction; injected failure после первой/второй восстанавливает исходные NUMERIC schema/data/children independent readback. Reject malformed/nonfinite/fraction/out-of-range/negative и pending оставляет schema/data неизменными, не rely на PostgreSQL cast (он способен округлить fraction). Positive commit сохраняет exact source-specific manifest, кроме двух type changes, seeded NULL/0/MAX, FK/index definitions и выбранного enforcement. Chain-only ledger indexes сохраняются, init_db отсутствие не исправляется.

Конкурентные controls: writer-first nonNULL pending или out-of-range generation commit → после реального lock wait свежий reject; rollback writer → eligible. Downgrade-first блокирует конкурирующий writer до release. Distinct PID/pg_blocking_pids, join/rollback/finally, никаких слепых retry. Locking каталогов может блокировать independent read, это не old-value readback.

## Критерий завершения

Независимый pre-code review, frozen static, author и independent real PG + unchanged regression, фактический exit/cleanup, отдельная проверенная ветка. FULL прежнего пакета не выдаётся за новый. Phase 2 PASS означает границы prototype и транзакционную репетицию, НЕ разрешение downgrade рабочей БД, изменение моделей, активацию scanner/producer или завершение выбранного пользователем durable продолжения.

## Независимый pre-code GO

Journal принял только заявленный TEST-ONLY scope. Typed Core обязан реально применять ExactGeneration к bind и result/RETURNING; различающий invalid bind до исполнения подтверждает processor, raw observer использует отдельное physical connection и Decimal/as_tuple, не тот же adapter. Обе колонки и NULL/zero проверяются отдельно. Точная ошибка и причина различают SQL/driver rejection, wrapped StatementError(ValueError) на bind и decoder rejection. После read rejection ранее committed raw value обязан остаться, typed read не является rollback этой записи.

Direct raw validator второго поля явно маркируется отдельно от pending gate. Signed representation tests не разрешают отрицательный downgrade protocol. Positive waiting writer после downgrade использует representable value; отдельный MAX+1 writer может после release получить BIGINT overflow/type error и не обязан commit. Это compatibility boundary, а не нарушение lock proof. Все preflight/catalog/data/ALTER в одной locked caller transaction; unsupported dependencies и shape drift reject до DDL. Runtime/adapter implementation/global mapping не меняются.

## Первый actual PG: сохранённый отказ fixture isolation

Phase2 test freeze6f1ddf8, runner/shared-helper a9b5b0e. Independent static PASS до исполнения, pure leaf adapter import проверен: нет settings/models/engine/IO, guard перед actual DB bootstrap сохраняется. Первый author actual PG: **174 PASS / 1 setup ERROR**, 128.07 с, actual exit **1** (`/tmp/taxi-actual-pg-domain-author1.log`, `.exit`). Это НЕ общий PASS phase2.

Ошибка в shared phase1 clone fixture: после закрытия template connection/engine.dispose проверка `pg_stat_activity` source DB увидела count1 вместо0 и остановила CREATE. Тип/PID остаточного backend в первом логе не записан, поэтому asynchronous disconnect пока гипотеза, не доказанный диагноз; возможные иные backend причины исследуются. Пройденные adapter/downgrade cases не компенсируют неисполненный setup case. Guard не удаляется, FORCE/terminate/blind CREATE retry запрещены. Кластер остановлен/удалён, cleanup пуст; ресурс освобождён. Следующий шаг — отдельная ограниченная диагностика с фактическим backend identity/state и независимым review перед correction/повтором.

## Различающий close probe: причина пока не воспроизведена

66ce22f добавляет отдельный fixed diagnostic mode, не повтор phase2: 20 последовательных actual source fingerprint/physical-close/observe циклов для каждого source. Independent static PASS. Author probe **2 PASS**, 10.60 с, exit0 (`/tmp/taxi-actual-close-probe1.log`, `.exit`). JSONL: `/tmp/yuldash-actual-schema.79wz9u_7/close-observation-chain.jsonl` и `/tmp/yuldash-actual-schema.dduifqu5/close-observation-init_db.jsonl`. Все 40 наблюдений сразу zero; original count1 причина не воспроизведена и не объявляется доказанным async disconnect. Cluster cleanup пуст.

Согласованный узкий следующий подход — bounded synchronization на полном zero всех backends exact source/target DB до CREATE/DROP с PID/state/time evidence. Это не фильтрация backend types, не terminate/FORCE, не повтор CREATE и не доказательство старой причины. На timeout по-прежнему FAIL. Требуются реальные controls удержанного backend: timeout без DDL и наблюдаемый nonzero→physical close→zero; initial zero отдельно. После correction — independent review и новые реальные прогоны phase2/phase1/default, без перезаписи первого ERROR.

## Guard correction и новый author run

765cc2c сохраняет полный zero-backend guard с bounded observation/JSONL перед CREATE и DROP. Independent static PASS: registry/guards/uncertain-CREATE handling сохранены, timeout fail, no FORCE/terminate/DDL retry. Причина старого count1 остаётся неизвестной.

Новый author diagnostic: **4 PASS**, 13.67 с, exit0 (`/tmp/taxi-actual-close-controls2.log`, `.exit`): два source-параметра 20-cycle observation и два source-параметра combined immediate-zero/held-timeout/observed-release controls. Затем новый author phase2: **175 PASS**, 117.84 с, actual exit0 (`/tmp/taxi-actual-pg-domain-author2.log`, `.exit`). Состав phase2: 174 source-specific actual PG cases и один pure exact_next boundary test, не 175 PG cases. Cluster cleanup пуст; исходный 174 PASS/1 ERROR не перезаписан. Независимые полные повторы этого scope и прежних phase1/default suites ещё требуются.

## Итог независимой phase 2 приёмки

Все независимые fresh runs завершились actual exit0:
- Close observation/guard controls: **4 PASS**, 14.05 с (`/tmp/taxi-actual-close-independent.log`, `.exit`).
- Phase 2: **175 PASS**, 143.13 с (`/tmp/taxi-actual-domain-independent.log`, `.exit`), из них 174 actual PG и 1 pure boundary.
- Phase 1 regression после изменения shared helper: **42 PASS**, 40.98 с (`/tmp/taxi-actual-domain-independent-phase1.log`, `.exit`).
- Unchanged default: **255 PASS**, 12.52 с (`/tmp/taxi-actual-domain-independent-regression.log`, `.exit`).

Final narrow independent PASS, открытых P0/P1/P2 нет; clone DROP/absence и cleanup всех кластеров подтверждены. Secrets scan **3 findings**, независимо признаны synthetic JWT fixture и двумя SQL literals, не реальными credentials; результат не называется scan0. FULL не запускался: только test/helper/runner/docs, runtime/model/adapter implementation неизменны.

Independent phase2 JSONL roots `/tmp/yuldash-actual-schema.ahzjpn3v` и `/tmp/yuldash-actual-schema.m_2lrj3h`: все 348 before-create/drop guards завершились zero. Один реальный nonzero вне искусственных controls наблюдался перед DROP: autovacuum worker PID3595, затем zero через 13.9 мс. Все before-CREATE наблюдения zero. Это доказывает полезность ожидания всех backend types, НЕ устанавливает причину прежнего source-before-CREATE count1. Phase1 84 guard logs все immediate zero. Исходный 174 PASS/1 setup ERROR сохранён как часть истории.

Проверены 44 raw-domain records: NaN/Infinity действительно nonfinite; integral fractional scale129 отвергается decoder budget; 1E128 фактически представлен Decimal exponent0; 1.000 принимается adapter. Raw storage domain шире adapter domain. Typed read rejection не откатил committed raw value. Pending rejection отделён от прямого validator второго поля; waiting writer MAX+1 после downgrade получает exact SQLSTATE22003, не обещанный commit.

Этот результат НЕ утверждает готовность production cutover, полноту старых writers, поддержание всех HTTP/ORM контрактов, SQLite rebuild или durable автоматического продолжения. Следующий узкий шаг требует отдельного принятого storage-domain/cutover контракта; менять decoder/нормализовать записи молча нельзя. Main/production/paused операции не затрагивались.
