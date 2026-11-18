# Actual ORM counter mapping: ограниченный test-only план

База local05a7437 / remote189d91545b86d39e90ef0fc0185ce0b6376bc2d6. Пользовательская цель — автоматическое durable продолжение после удаления предложенного водителя. Storage CHECK не завершает эту функцию. Следующий пакет проверяет недоказанную границу actual SQLModel mapping, а не повторяет прежние Core-only или miniature Counter tests.

## Один исполнимый срез, до runtime review

Изолированные child-процессы используют настоящий `app.models.InstantOrder` и существующий ExactGeneration. Только внутри child, до service imports, ORM запросов и компиляции expressions, два Column.type заменяются на ExactGeneration. Production source и глобальный mapping основного pytest процесса не меняются. Это proposed test-local mapping, НЕ проверка неизменённой нынешней модели. Сравнить исходные column identities/types и изменённые ровно два типа; compiled bind/result/RETURNING должны различающе доказывать реальное применение adapter, а не ручной decode. Новый процесс для каждого сценария исключает reuse compilation cache или утечку patch.

PG: один явно выбранный actual init_db bootstrap source, собственные registered case clones. Не заявлять новую проверку chain/init_db parity. В parent только существующий test-local NUMERIC upgrade и validated storage CHECK, затем закрыть connections перед child. Clone guard проверяется заново до app imports в child. SQLite: собственный новый tempfile, full actual metadata create_all после двух test-local type replacements; это fresh schema, НЕ rebuild существующей БД. Никакого startup/init_db поверх PG clone и никакой автоматической починки source schema.

Guard до app imports: fixed runner mode/allowlisted case, точный registered clone или собственный SQLite path, actual PG host/port/database/data_directory, отсутствие inherited DSN/PG options. Ограничить child network до разрешённого loopback PG до app/service imports, SQLite полностью без сети. DATABASE_URL в child только его собственная fixture, безопасные synthetic settings. Никаких HTTP/real Redis/FCM/storage действий. Ограниченный subprocess timeout, kill+wait собственного child при timeout; workers/engines закрыть до own clone/file cleanup, ошибки не скрывать. CREATE/DROP только прежний guarded registry protocol, без FORCE/terminate.

## Матрица ORM и хранения

Оба поля независимо: NULL, 0, MAX+1, 10**40+1, 10**128−1. ORM insert/flush/commit, expire/refresh/get и Core UPDATE RETURNING через actual mapper columns возвращают exact Python int/None. Independent physical connection raw observer фиксирует PG Decimal/as_tuple или SQLite typeof/hex/TEXT; не читать raw через тот же adapter. Физические типы/schema shape явно проверяются. SQLite CHECK в этом срезе не добавлять молча: если он нужен, использовать отдельно принятый test-local DDL и явно показать, что create_all сам CHECK не устанавливает.

Identity map: independent committed update сохраняет stale loaded scalar до explicit refresh; expire/refresh обновляет его. Dirty обоих counters + unrelated field, autoflush=True/False/no_autoflush, explicit flush/rollback, commit expiration. Никакой discard unrelated pending writes. До commit independent observer не видит значения, после видит, outer rollback восстанавливает прежний tuple. NULL не missing и не zero.

Invalid bool/float/overflow отдельно: сначала зафиксировать фактическое SQLModel constructor/assignment поведение, не предполагать strict Pydantic validation; затем persistence boundary с настоящим compiled bind. Если ORM нормализует исходный invalid input в допустимый int, это diagnostic known gap, а не заявленный adapter reject. Определённый тест не принимает любой исход как PASS: исследовательский результат фиксируется отдельно, затем точный regression expectation.

## Различающие actual guard controls

Исполнить настоящий `_can_increment_search_revision` на SQLite TEXT и сравнить с точным Python BIGINT-domain ожиданием. Это compatibility diagnostic: lexical/coalesce semantics могут дать неверный match, даже когда все сохранённые значения допустимы. Не менять guard, не выдавать CHECK за исправление. Отдельный настоящий `_bump_search_revision` с MAX+1/large mapped int должен сохранять текущий BIGINT refusal; caller rollback и independent readback доказывают отсутствие committed increment. Подмен SQL/CAS/commit не делать. No account API/новый409: наблюдается прежний internal helper.

Время/lock политика новых writers здесь НЕ реализуется, concurrency protocol и remaining validators остаются будущим review. SQLite fresh schema не доказывает migration, old binary compatibility или честность raw SQL arithmetic.

## Публичная витрина

Настоящий `order_payload` с синтетическим passenger participant, без actor_authorized bypass и без mock serializer. Сравнить публичный payload baseline с large internal counters, подтвердить отсутствие обоих generation keys и успешное JSON encoding. Не считать это покрытием всех HTTP endpoints или авторизации. Отдельный общий model_dump показывает внутренние большие int и потому не разрешён автоматически как публичный JS-safe API. Если service dependency пытается внешний вызов, блокировать и исследовать, не разрешать сеть.

## Gate и ограничения

Сначала независимый план, затем frozen static review, author/independent disposable PG+SQLite и нужные unchanged regressions. No app model, adapter source, runtime, migrations, account policy, main/prod, paused operations. Проверенный пакет публикуется отдельно.

Успех этого среза даёт evidence для следующего ЕДИНОГО model/bind/guard/validator изменения, не разрешение его включить. Старые binaries и persisted snapshots/jobs/requests/restart/backup могут записать другое допустимое значение. Tokenless replay и negative-history ABA остаются. 128-digit limit конечен, overflow не является принятым отказом удаления аккаунта; durable producer/consumer/transfer пока не подключены. Следующий runtime контракт должен сохранить NULL/rollback/ownership и пользовательское решение, а не объявить CHECK достаточным.

## Независимый pre-code review и обязательные controls

Journal дал PASS только ограниченному experiment. Import models уже создаёт instrumented attributes/comparators: child-local Column.type replacement сам по себе не доказывает обновление mapper. Перед/после снять table Column identity, mapper.column_attrs обоих полей, instrumented comparator/expression.type и реальные compiled bind/result processors. Не очищать/перестраивать caches скрыто. Stale comparator сохраняется как candidate FAIL и блокирует зависимые positive claims; отдельная корректировка требует нового review. Parent pytest не импортирует app и не патчит mapping.

Network deny и exact destination guard включены до settings/service imports; parent preparation тоже guarded. Новый child на сценарий; timeout только своего child с wait, registry cleanup без FORCE. Источники/source неизменны. SQLite metadata create_all проверяет фактический TEXT и явно отсутствие новых CHECK; PG clone с validated CHECK имеет другую storage policy. Signed -1 поэтому не одинаковый policy outcome двух fixtures, и это не ошибка неизменённого adapter.

SQLite guard truth table включает NULL, 0, 2, 9, 10, 100, MAX−1/MAX/MAX+1 и большое число; исполняется настоящий expression с его реальным bind type, без нормализации в INTEGER. _bump на MAX+1 должен дать текущий typed internal error, rollback/readback без committed mutation. Возможное SQLite no-op UPDATE не выдавать за отсутствие SQL вообще; различать mutation attempt и persisted value. Ни новый account409, ни allwriter proof.

Constructor coercion, assignment и persistence boundary фиксируются отдельно с точным observed expectation, не catch-any. Distinct connection observer может блокироваться, тогда это не proof старого committed значения. order_payload baseline/large counters сравниваются с явной минимальной policy для volatile fields; fields не прячутся broad allowlist. Это serializer integration, не HTTP/auth coverage. Private model_dump не становится публичным API. Любой неожиданный внешний вызов блокируется и докладывается, не разрешается или маскируется fallback.

## Первый исполнимый gate

44c6ab2 добавил только minimal fresh-SQLite child gate, не полную матрицу и не PG. Independent static нашёл P2 default-suite integration: отсутствие opt-in приводило бы к no-app assertion после обычного conftest. До первого исполнения исправлено 32e3a0e: controlled skip вне exact actual-orm-counter mode, строгий FAIL внутри него. Runner 2252597 allowlists один файл. Static P2 закрыт независимо, не был runtime product failure.

Авторский gate 32e3a0e: 1 PASS, actual exit 0, 1.50 с (`/tmp/taxi-actual-orm-gate-author1.log/.exit`), report `/tmp/yuldash-actual-orm.vlsq10ge/report.json`, stage complete. До patch обе table types BigInteger; после patch table/mapper/comparator/expression ExactGeneration для обеих колонок. Actual compiled и ORM flush/refresh/RETURNING/bool-bind controls прошли. Создана fresh full metadata SQLite с TEXT и явно без новых CHECK; DB/sidecars удалены, stderr пустой. Это child-local mapping proposal, не проверка неизменённой модели; на этом import path stale cache не наблюдался, не универсальное доказательство всех import orders. PG, service payload, расширенная constructor/autoflush/guard matrix остаются впереди.

Независимый stage1 на 32e3a0e: 1 PASS, 1.62 с, exit 0; без opt-in отдельный control 1 SKIP, exit 0 (`/tmp/taxi-actual-orm-gate-independent{,-skip}.log/.exit`). Report `/tmp/yuldash-actual-orm.jt85v0bk` подтвердил те же actual mapping/processors, отсутствие новых CHECK и cleanup. Это PASS минимального gate, не всей плановой матрицы. После него разрешена реализация следующих approved test-only scenarios; до их independent static никакие новые PG не запускаются.

## Expanded static: сетевой boundary

Expanded d3aa793 добавил 9 parent cases (minimal, 4 SQLite и 4 PG/init_db), с отдельными prepare_pg children. До первого expanded исполнения independent review обнаружил P2: host-only allowlist 127.0.0.1 позволял другие loopback service ports. Даже no-driver fixture path не заменяет исполнимую блокировку неожиданного Redis/другого service вызова. Author GO отложен до исправления child-only boundary, учёта denied attempts (включая проглоченный fallback) и доказанного PG preprobe до app imports. Никаких product runtime/serializer mocks или OS-wide network changes.

## Expanded author evidence

Network correction 5ac0ce0 независимо статически принята: child Python socket/DNS deny-all с тремя отдельно записанными ожидаемыми controls; любой scenario attempt вызывает FAIL, даже после fallback. C/libpq не перехватывается этим Python guard, поэтому под активным deny выполняется настоящий fresh connection preprobe точного disposable destination до imports. e96e8fb добавляет только наблюдаемые child/parent/backend PID. Никаких cache clears, private remaps, serializer или SQL guard mocks.

Author expanded SQLite на 5ac0ce0: 5 PASS / 4 deselected, 7.35 с, exit 0 (`/tmp/taxi-actual-orm-expanded-author-sqlite1.log/.exit`). Actual SQLite TEXT guard дал NULL→False при Python BIGINT eligibility True и 10**40+1→True при Python False. Это несовместимость предлагаемого mapping со старым guard, НЕ изменение текущей production BIGINT модели. Actual _bump сохраняет внутренний BIGINT отказ, caller rollback/readback пройдены; настоящий participant order_payload равен baseline и не раскрывает counters. Constructor и assignment сохраняют bool/float/overflow без coercion, actual bind отклоняет их до SQL. Эти результаты не дают разрешения использовать model_dump как public API.

Author combined actual-orm-counter e96e8fb: 9 PASS, 20.00 с, actual exit 0 (`/tmp/taxi-actual-orm-expanded-author-pg1.log/.exit`). Это 5 SQLite parent cases и 4 actual PG/init_db cases; дополнительно 4 prepare_pg child не считать отдельными pytest PASS. Все 13 child reports complete/exit0, у каждого 3 expected network controls и 0 scenario attempts. Parent PID 44 и отдельные child PIDs записаны, PG backend probes также. Пример report `/tmp/yuldash-actual-orm.5goag6v6/report.json`; libpq реально работал под Python socket deny. PG guard совпал с BIGINT truth table, обе колонки проходили actual ORM/RETURNING/refresh и raw observer, identity/autoflush/rollback/constructor/payload. Кластер yNjCSCnC, own clones и SQLite DB/sidecars очищены. Только init_db source; chain/parity/rebuild claims нет. Независимые повтор и регрессии ещё отдельный шаг.

## Независимая финальная приёмка expanded среза

Source e96e8fb, дальнейшие коммиты только этого документа. Independent actual-orm-counter: 9 PASS, 20.75 с, exit 0 (5 SQLite + 4 PG/init_db); shared phase1: 42 PASS, 50.74 с, exit 0; default unchanged regression: 255 PASS, 14.51 с, exit 0. No-opt-in collection control: 9 SKIP, 0.27 с, exit 0, не объявляется 9 PASS. Логи `/tmp/taxi-actual-orm-expanded-independent*.log/.exit`.

Проверены 13 child reports/PID, 8 реальных guarded libpq probes под активным Python socket deny, 39 expected network controls и 0 scenario attempts. Own clone teardown, cluster cleanup и SQLite DB/sidecars подтверждены. Разрешение Python socket и C/libpq — разные границы, не universal native-network interception. Final narrow PASS без открытых P0/P1/P2. Ruff F / diff check PASS. Secrets scan: 3 synthetic/literal findings, не реальные credentials, scanner не ослаблен. FULL не запускался, это test-only пакет.

Вывод: предложенный child-local actual mapper сохраняет exact ORM/bind/result поведение на проверенных fresh schema/import path; настоящие старые guards не совместимы с SQLite TEXT, а public order_payload на fixture остаётся прежним. Это не тест неизменённого production mapping, не HTTP/auth coverage, не actual SQLite rebuild, не доказательство всех import orders/caches, не all-writer/cutover readiness. Реализация unified guards/validators и durable producer/consumer/transfer остаётся отдельным решением с независимой проверкой; finite budget, persisted replay/old writers/tokenless границы не сняты. Ресурс передан Amio только после actual exit и cleanup всех PG.
