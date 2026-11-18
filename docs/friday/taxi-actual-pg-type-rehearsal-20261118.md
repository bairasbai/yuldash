# Locked PostgreSQL actual-type rehearsal: план до кода

База local82590cc / remote63e980e0963b034948faf3659db08a9091b717fa. Только TEST-ONLY PostgreSQL в новом disposable cluster. Baseline подтвердил два actual bootstrap source и drift двух partial unique ledgerentry indexes. Это не разрешает менять app models/migrations/writers или рабочую БД; SQLite rebuild вне пакета. Durable recovery и cutover остаются выключенными.

## Предмет и источники

Переиспользовать guards/provenance/full catalog diagnostics baseline без weakening. Alembic chain и actual init_db в разных fixed allowlisted databases, guard до imports и bootstrap. Ни metadata fallback, ни историческая независимость dynamic baseline не заявляются. Manifest каждого источника сравнивать с его собственным before, НЕ друг с другом: chain-only ledger indexes обязаны сохраниться, а отсутствие их у init_db не исправляется этим тестом.

Срез проверяет actual InstantOrder.search_revision и matching_resume_revision: transactional BIGINT→unconstrained NUMERIC только внутри собственных synthetic databases. Production ORM/adapter mapping не подменять. Набор foreign child fixtures и точный охват остаются явно ограниченными; full catalog inventory не равен проверке каждого API.

## Test-local preflight и transaction contract

Получить table lock, исключающий concurrent INSERT/UPDATE, ДО любого raw data preflight и удерживать через обе ALTER и manifest/readback. На fresh connection явно проверить transaction/autocommit и READ COMMITTED; остальные isolation не объявлять проверенными. Время ожидания bounded test timeout, no blind retry. Два physical PID, pg_blocking_pids и отдельное проверяемое завершение writer после release обязательны.

Initial gate: обе колонки nullable BIGINT; actual resume tuple ВСЕ три NULL; search_revision NULL либо неотрицательный BIGINT. Любой nonNULL resume (включая валидный целый), partial tuple или negative revision → typed fixture rejection до DDL, без normalization/reset. Own unsupported trigger/view, unexpected type/default/shape тоже отклоняется до DDL. Это gate тестового cutover, НЕ HTTP409 или ограничение удаления аккаунта.

Full raw data/catalog snapshot берётся до вмешательства. Каждая ошибка после first ALTER и second ALTER должна rollback обе колонки, values, child rows и transactional unrelated sentinel. Проверять отдельной healthy connection после rollback. На reject точно подтвердить отсутствие type/schema/data change, не только exception.

## Positive и negative evidence

- Positive обе actual колонки становятся NUMERIC в одной транзакции, остальные catalog differences запрещены кроме точных type-related details, перечисленных заранее. Defaults/nullability/PK/FK actions/index definitions/unique/CHECK и все seeded child values/counts сохраняются.
- До commit независимое чтение не должно видеть new values; catalog/row lock может блокировать чтение: наблюдаемый block не называть старым value readback. После commit независимый readback подтверждает types/values. Проверить отличающий rollback путь отдельно.
- Exact values через test-local Core typed columns/существующий adapter: NULL, 0, 2**53+1, MAX-1/MAX/MAX+1 и 128-digit max/max+1. Большие values вводить лишь после собственного upgrade, без float. Raw SQL допускает значения вне decoder domain: это diagnostic, а не enforcement guarantee.
- NaN/Infinity/fraction/огромный integral scale/exponent отдельно сопоставить raw NUMERIC storage и actual adapter decode. Unconstrained NUMERIC не обеспечивает finite/integral/128-digit protocol; не добавлять production CHECK/normalization молча. NUMERIC(128,0) не использовать, чтобы случайное округление выглядело validation.
- Pending sentinel для second-column roundtrip должен ожидаемо блокировать downgrade, а не очищаться ради fake PASS. Downgrade preflight под lock: all-resume-NULL, обе колонки finite/integral/BIGINT bounds и validated shape; malformed/out-of-range → reject до DDL, exact independent unchanged readback. Positive downgrade только отдельный заведомо eligible fixture.
- Реальные FK/unique/cascade/rollback controls baseline повторить после positive conversion для выбранных relationships. Полный ledger index definition и фактическая unique enforcement выбранных индексов требуют корректных synthetic inputs, не случайного PK conflict.
- Каждому scenario независимый fixture state; никакой зависимости от порядка тестов, silent reset чужих результатов или попытки repair неудачного source. Завершить/rollback всех workers перед cleanup, ошибки cleanup не скрывать; логи и reports сохранять отдельно от временных databases.

## Блокеры, которые этот срез НЕ снимает

Никакого executable production runbook, online performance/zero downtime, mixed-writer compatibility, persisted stale replay защиты, negative-history ABA repair или tokenless client fix. Старые arithmetic/range/MAX guards остаются. 128-digit finite budget не превращается в новый account failure policy. Общая durable chain требует отдельной actual writer/intent/claim/outcome/timeout/deadlock интеграции. Последующий безопасный шаг определяется наблюдёнными результатами и независимой приёмкой, не количеством PASS.

## Независимый GO: два отдельных freeze

Journal принял PG-only TEST-ONLY план с разделением: phase 1 — actual source manifests, ACCESS EXCLUSIVE/preflight, две ALTER/rollback, selected enforcement и оба lock orders; phase 2 — raw domain/adapter и guarded downgrade. Успех phase 1 НЕ считается успехом phase 2.

ACCESS EXCLUSIVE берётся ДО authoritative catalog/data validation. Любой preliminary manifest лишь diagnostic; после lock wait actual catalog и data читаются заново. Writer-first: committed bad pending/negative → reject; rollback writer → eligible. Rehearsal-first: реальный competing writer блокируется и завершает проверенный commit только после release. Actual connection autocommit=False/READ COMMITTED, SET LOCAL bounded lock_timeout/statement_timeout, distinct PID/observed blocker; finally освобождает и join всех workers.

Manifest allowlist только двух типов и точных связанных type attributes, без wildcard ignoring indexes/defaults. Chain-only ledger definitions/unique predicates сохраняются; enforcement должен ошибаться именно по named expected unique index, не случайному PK. Init_db отсутствие этих indexes не исправляется. Own unsupported trigger/view устанавливается до rehearsal, guard reject before DDL и rollback/readback доказуемы.

Phase 2 attribution: all-resume-NULL gate раньше second-column range, поэтому pending rejection не доказывает range validation. Нужен отдельный test-local validator control либо честное обозначение gate. Positive downgrade и nonNULL second-column roundtrip — разные fixtures, никакой очистки sentinel ради PASS. Huge scale/exponent controls ограничивают память/время; фиксируют фактическое DBAPI представление, не предполагают его сохранение. Adapter reject 128-digit max+1 и raw SQL accepted storage — разные наблюдения. Реальный cutover/production/model/SQLite/durable протокол остаются вне разрешения.

## Изоляция committed scenarios: собственные database clones

Независимый GO допускает per-case CREATE DATABASE с internally generated quoted nonce и TEMPLATE только одного из двух fixed source databases в уже guarded disposable cluster. Это test fixture isolation, не production backup/restore proof. Source после baseline freeze не мутируется; bootstrap child завершён, active source backends проверяются, принудительного terminate нет. Перед case catalog fingerprint и actual stamp clone должны совпасть с source.

CREATE/DROP выполняются dedicated admin connection с actual autocommit вне caller test transaction. Guard cluster/host/data directory до операции; после connect guard принимает только точный зарегистрированный nonce, не произвольный prefix. Registry добавляется после подтверждённого CREATE; при uncertain outcome pg_database проверяется в том же guarded cluster, без blind retry/new-name leak. Source/admin DB исключены из cleanup. После worker join и engine dispose DROP только exact owned registry DB без FORCE; отсутствие entry подтверждается через pg_database. Ошибки cleanup не скрываются и не вызывают broader purge; существующий guarded cluster teardown остаётся последней границей.

## Phase 1 freeze и авторская проверка

Runner 1b64500 добавляет только fixed allowlisted mode `actual-schema-pg-type-rehearsal`; прежние suites неизменны. Test freeze 966329c: 42 parametrized PG cases по двум actual source, per-case own clone, atomic commit, rollback после первой/второй ALTER, invalid tuples, unsupported dependencies/shape, writer-first и rehearsal-first наблюдаемые блокировки. Phase 2 отсутствует. Catalog inventory расширен sequence options/ownership; sequence current values нетранзакционны и не выдаются за rollback данных. Это перечисленный supported catalog, не все свойства каждого PostgreSQL объекта.

Независимый static review PASS до исполнения; открытых P0/P1/P2 не найдено. Author actual PG: **42 PASS**, 38.98 с, actual exit0 (`/tmp/taxi-actual-pg-type-author1.log`, `.exit`). Все собственные case clones удалены без FORCE, fresh cluster cleanup пуст. Этот прогон не доказывает production cutover, adapter domain или downgrade; независимый повтор/default regression впереди.

## Независимая приёмка phase 1

Independent exact PG mode на 966329c/runner1b64500: **42 PASS**, 40.27 с, actual exit0 (`/tmp/taxi-actual-pg-type-independent.log`, `.exit`). Unchanged default в отдельном fresh cluster: **255 PASS**, 12.72 с, actual exit0 (`/tmp/taxi-actual-pg-type-independent-regression.log`, `.exit`). Per-case DROP/absence assertions без teardown errors, после каждого runner временных clusters нет. Heavy ресурс освобождён после actual exit.

Final narrow acceptance PASS, открытых P0/P1/P2 нет. Secrets scan: **2 findings**, независимо классифицированы как авторская synthetic child JWT fixture и literal SQL, не реальные credentials; scan не ослаблялся и результат не называется scan0. Ruff/compile/bash/diff PASS. FULL не запускался для этого test-only изменения; старый FULL не засчитывается как новый результат.

Проверено транзакционное изменение только двух типов и rollback на собственных actual-schema fixtures с обоими bootstrap sources, сохранением source-specific ledger indexes и выбранными enforcement controls. Это НЕ применение app migration, НЕ тест deployed data/fleet, НЕ phase 2 adapter/raw-domain/downgrade и НЕ готовое durable recovery. Последующий phase 2 требует отдельного freeze/review/прогонов.
