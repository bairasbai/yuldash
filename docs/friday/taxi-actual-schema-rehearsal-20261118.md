# Actual-schema generation rehearsal: план до реализации

База: local fc63d78 / remote 45efafc58f89918a9a08dd4fafe16c792ebf0eea. Это отдельный TEST-ONLY эксперимент, НЕ production cutover, не новая migration/model и не готовое durable восстановление. Реализация только после независимого review. Предыдущая mini-schema репетиция не доказывает совместимость полной схемы.

## Подтверждённая исходная граница

Обе actual колонки InstantOrder.search_revision и matching_resume_revision — nullable BIGINT. matching_resume_next_at — naive timestamp, attempts — nullable INTEGER. Фактический source Alembic head co_instant_matching_resume; проверка файлов не доказывает stamp рабочей БД. create_all/SQLite add-columns не меняет тип существующей колонки. Production pending producer/consumer ещё не подключены.

После пакетов _bump/A арифметика части writers стала Python literal, но SQL coalesce/range guards, MAX validators matcher и неподключённых SQL-history helpers остаются BIGINT-specific. Equality guards не дают права объявить совместимыми ordering/arithmetic/COALESCE над SQLite TEXT. Старые binaries и raw SQL обходят adapter.

## Ограничение первого среза

Только заново созданные disposable PG clusters и собственные SQLite tempfiles. Две независимые исходные схемы: фактическая Alembic chain до co и init_db/create_all. Их parity не предполагать. Если bootstrap chain не работает, сохранить точный отказ; не менять чужие migrations и не выдавать metadata fallback за успешную chain.

Первый preflight допускает лишь inactive pending: все три resume поля NULL. Любой nonNULL, partial или malformed tuple блокирует тестовый cutover ДО DDL. Это ограничение репетиции, НЕ новое ограничение account API и НЕ разрешение потерять pending работу. Normal generation NULL/неотрицательная BIGINT; corrupt/negative отклоняется без reset/clamp. Собственные synthetic rows содержат разные statuses и реальные FK children, а не реальные данные пользователя.

## Защиты и доказательства

- Dedicated allowlisted runner mode; guard свежего disposable database/data_directory/host ДО pytest/conftest/application imports. Никаких inherited DSN, произвольного pytest path или app DDL вне этого кластера. Default suite неизменён.
- До DDL снять полный catalog manifest: columns/types/nullability/defaults/PK, CHECK, indexes с definitions/uniqueness, outgoing/incoming FK с actions, triggers/views. Проверять enforcement, не только имена.
- Owner table lock получить ДО raw validation обеих колонок и удерживать до завершения DDL/verify. Validation без adapter masking. Два actual connections/PID и наблюдаемое ожидание concurrent writer; preflight failure сохраняет schema/data independent readback.
- PG: обе BIGINT колонки преобразовать в unconstrained NUMERIC одной транзакцией. NUMERIC(128,0) не использовать как доказательство integer validation: cast может округлять fraction.
- SQLite: собственная полная actual table перестраивается с canonical TEXT обоих counters. FK OFF только dedicated fixture connection вне TX; проверить pragma, FK check ДО commit, integrity_check после, FK ON в finally и реальный bad-child rejection. Incoming children/их target, actions, unique/index/CHECK/defaults сохраняются. Unsupported custom trigger/view явно блокирует rehearsal, не silently drop. Никакого global purge.
- Injected failures после первого ALTER, copy и rename проверяют rollback обеих колонок и всех synthetic rows/children. После ошибки fresh healthy connection проверяет исходную схему/значения. Все workers завершены до cleanup; cleanup ошибки не скрывать.
- Exact roundtrip обеих колонок: NULL, 0, 2**53+1, MAX-1/MAX/MAX+1 и 128-digit boundary; actual SQLAlchemy/DBAPI bind, flush/expire/refresh/RETURNING, NULL-safe equality CAS и independent commit/rollback. Большие значения seed только после upgrade; никакой float конверсии.
- Old model/new schema, new adapter/old SQLite INTEGER, старые MAX/range/arithmetic writers и stale equal-value replay — отдельные negative controls. Их отказ/небезопасность не маскировать как успешную совместимость.
- Actual HTTP serialization проверяет отсутствие внутренних generation/pending полей. Старый ORM с новым физическим типом не называть новым production model. Не monkeypatch глобальную модель и не выдавать это за готовый rollout.
- PG downgrade только под lock после finite/integral/BIGINT range проверки ОБОИХ колонок и pending compatibility. Любая несовместимость — fail до DDL; schema/data unchanged. SQLite downgrade не заявлять без отдельной реализации/проверки.

## Зависимости полной таблицы

Source audit обнаружил incoming links Message, TripShare, SosEvent, Report, Incident, Rating, InstantTriedExclusion, Payment, LedgerEntry, DriverCancel, CommissionDebt, OfferDecline, PromoRedemption.used_order_id, PriceComplaint; outgoing User/Tariff. Это старт inventory, не замена actual catalog. History имеет composite PK и order CASCADE; CommissionDebt — unique order. Родительский rebuild не должен каскадно уничтожить историю/платежные строки. Никаких настоящих финансовых операций.

## Нерешённые блокеры cutover

128-digit budget остаётся конечным техническим пределом, не разрешением блокировать удаление аккаунта. Adapter Decimal budget также ограничивает scale/exponent: integral NUMERIC с огромным scale может пройти обычный range/integral CHECK, но не decoder. До решения нужны отдельные raw SQL negative controls и согласованный storage/decoder domain; не добавлять молча normalization или production CHECK. NaN/Infinity/fraction/raw SQL bypass не решаются типом NUMERIC автоматически.

Drain активных процессов недостаточен: persisted queues, saved HTTP requests, scheduled workers, backups/restore, старые binaries и operator scripts способны вернуть stale inputs. Counter equality не защищает equal-value historical replay. Tokenless accept/decline остаются отдельной клиентской границей. Никакой online migration/zero downtime/latency гарантии из лаборатории.

Durable цель Байраса сохраняется: удаление offer recipient атомарно оставляет обязательство автоматического поиска, offered сохраняет его через последующий advance, terminal/accepted снимают по принятому контракту. Но actual producer/claim/outcome/all-writer transfer, timeout/deadlock/mixed fleet и negative-history trust boundary здесь не реализуются. Preflight fail оставляет cutover выключенным; не вводит новый HTTP 409. Main/production/paused операции не затрагивать.

## Независимый review и первый freeze scope

Journal принял только TEST-ONLY направление. Первый исполнимый пакет ограничивается двумя независимыми bootstrap sources, actual catalog/data baseline, locked inactive preflight/reject controls и PG transactional ALTER/rollback. SQLite destructive rebuild отложен до отдельного freeze после полного manifest. Source provenance — actual alembic_version и catalog; запрещён startup, достраивающий chain до metadata. Bootstrap fail сохраняется как отдельный наблюдённый блокер, не product failure и не успешный source.

Manifest допускает лишь две намеренные type-замены и неизбежные связанные catalog details; defaults/nullability/PK/FK actions/index expressions/childcounts/values неизменны. Unsupported собственный trigger/view создаётся как настоящий negative control и блокирует ДО DDL. Lock предшествует raw validation; injected fault после первой и второй ALTER проверяется независимым healthy connection после rollback. All3-resume-NULL gate не нормализует даже валидную nonNULL пару. Adapter tests не подменяют global InstantOrder; old ORM/new schema — отдельный контроль, отсутствие HTTP response не считается доказательством no leak. Любой skipped/failed source явно исключается из итогового PASS; production cutover по-прежнему выключен.

## Feasibility: ещё более узкий исполнимый baseline

Read-only audit обнаружил: `0001_baseline_schema.py` вызывает текущую SQLModel metadata.create_all. Поэтому два bootstrap процесса независимы по данным/соединениям, но Alembic fullchain с нуля НЕ исторически независимое воспроизведение production schema. `alembic/env.py` самостоятельно создаёт engine из DATABASE_URL; переданный test connection не обеспечивает изоляции. Нужны guarded subprocess с DSN до imports, без глобальной env mutation параллельного pytest.

Первый executable freeze поэтому ограничен actual bootstrap/catalog/data/FK diagnostics БЕЗ ALTER/rebuild. Он получает manifests и точные отказы для последующего отдельного решения о PG ALTER/rollback. Actual init_db отличается от metadata-only из-за SQLite add-columns; provenance маркируется прямо. Chain failure не заменяется metadata fallback и не выдаётся за успешный source. Это сужение объёма, не снятие будущих требований preflight/rollback/cutover.

## Первый static freeze: ещё не runtime PASS

Runner cd12ed5 создаёт две отдельные fixed disposable БД: yuldash_schema_chain_fixture и yuldash_schema_init_fixture. Testfile a0f5ff0 использует закрытый bootstrap entrypoint и проверяет destination до app imports, actual manifest/completeness и selected child enforcement (history, Message, CommissionDebt). Эти три связи не означают проверку всех incoming FK. Syntax/Ruff/diff PASS; DB/pytest на этом freeze не запускались.

Независимое static review выявило три P2 до запуска: parent guard ошибочно отвергает нейтральные PGPASSFILE/PGSERVICEFILE runner; нет фактического stamp readback из alembic_version; SQLite файлы остаются после fixture без отдельного cleanup. Исправления должны сохранить запрет произвольных PG overrides, добавить exact source provenance и cleanup только собственных DB файлов после завершения child, сохранив текстовые доказательства. Это test-only corrections, не изменение приложения и не обход product failure.

## Исправленный baseline и SQLite evidence

00fbaf1 закрывает все три static P2; независимый recheck подтверждён до запуска. Author SQLite: **4 PASS / 3 PG SKIP**, exit0, 4.28 с (`/tmp/taxi-actual-baseline-author.IA3qzf/run.log`, `run.exit`; reports `/tmp/yuldash-actual-schema.cpka_qq3`). Independent SQLite: **4 PASS / 3 PG SKIP**, exit0, 3.95 с (`/tmp/taxi-actual-schema-independent-sqlite.log`, `.exit`; reports `/tmp/yuldash-actual-schema.q09_kmbn`). Оба source successful: chain 72 tables/stamp exact co, init_db 71 tables/stamp absent; metadata columns completeness и шесть selected enforcement controls/independent rollback readback PASS. Собственные SQLite files и sidecars удалены, текстовые evidence сохранены.

Реальный drift подтверждён обоими прогонами: кроме alembic_version, chain содержит два partial unique индекса ledgerentry `uq_ledgerentry_promo_comp` и `uq_ledgerentry_refund`, которых нет после init_db. Это наблюдение структуры двух synthetic bootstrap, НЕ parity и не утверждение о deployed schema/финансовом инциденте. Эти индексы или bootstrap runtime не изменяются. PG пока не запускался; новый FULL не требуется для test-only пакета без изменения runtime, старый FULL B не выдаётся за проверку новых diagnostics.

## Проверенный итог baseline (без изменения типов)

Author disposable runner actual-schema-baseline: **7 PASS**, 9.83 с, actual exit0 (`/tmp/taxi-actual-schema-author-pg1.log`, `.exit`). Independent: **7 PASS**, 11.00 с, actual exit0 (`/tmp/taxi-actual-schema-independent-pg.log`, `.exit`). Состав каждого: **3 PostgreSQL-specific + 3 SQLite + 1 static**, не семь PG cases. Independent unchanged default PG regression: **255 PASS**, 13.02 с, exit0 (`/tmp/taxi-actual-schema-independent-regression.log`, `.exit`). Проверенный test freeze 00fbaf1, runner cd12ed5; последующие изменения только этого отчёта.

Independent reports `/tmp/yuldash-actual-schema.oksk750f` (PG), `/tmp/yuldash-actual-schema.16gwrscf` (SQLite). Оба dialects: chain/init_db child exit0, actual head co/отсутствие stamp соответственно; шесть selected controls и независимое чтение полного выбранного synthetic набора после rollback подтверждены. В обоих dialects drift тот же: alembic_version и два chain-only partial unique ledgerentry indexes. Все собственные PG clusters остановлены/удалены; SQLite files/sidecars удалены, текстовые evidence сохранены. Heavy-ресурс освобождён Amio после actual exit.

Final independent narrow acceptance PASS, открытых P0/P1/P2 нет. Secrets scan: **1 synthetic fixture finding**, независимо подтверждённая авторская тестовая JWT-константа child ENV=dev, не реальный credential; scan не ослаблялся. Ruff/py_compile/bash/diff PASS. FULL нового пакета не запускался: изменены только тесты, runner и docs, runtime/models/migrations не менялись. Предыдущий FULL B не засчитывается как новый baseline FULL.

Первый результат — доказанный baseline и структурное отличие источников, НЕ миграция или исправление drift. Никакого ALTER/rebuild здесь нет. Следующий отдельный review: locked inactive preflight и transactional PG type rehearsal с manifest-preservation/rollback, включая сохранение найденных indexes. SQLite rebuild только после отдельного полного dependency acceptance. Остальные cutover/128-digit/raw SQL/persisted replay/durable blockers сохраняются.
