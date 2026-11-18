# B: guarded route capture до SQLite refresh

База local96d8b97 / remote2cdce742. Отдельный от A пакет; до независимого review runtime не меняется. Цель — подтверждённая узкая SQLite гонка destination/waypoints: cancellation commit после route refresh, но до writer lock позволяет старой операции изменить маршрут/revision отменённого заказа. Два actual interleave diagnostics и old-helper control сохранены в предыдущем пакете. Это не весь route graph и не durable account recovery.

## Actual callers и новое внутреннее предусловие

Source audit: routers/instant.py change_destination (1017–1051), accept_destination (1097–1111), set_waypoints (1163–1181) загружают полный order с populate_existing/FOR UPDATE, затем вычисляют quote и вызывают apply. Ни одна из трёх обычных apply ветвей до вызова не меняет target order и не commit/expire. destination_quote не мутирует order; tariff SELECT может autoflush уже существующие dirty session objects, поэтому это не обещание защиты более ранних действий caller. Preview/driver-approval ветви, которые не вызывают apply, сюда не относятся; ack_destination идёт после apply.

Поддерживаемый direct input: persistent object в той же Session с identity, не deleted; поля существующего source tuple fully loaded; target order целиком clean по histories всех column attributes. Dirty/expired/partially loaded/detached input должен получить прежний bilingual route conflict ДО SQL/flush, без commit/rollback helper. Caller обязан rollback/reload/requote перед явным повтором. Это явно новое internal precondition, не новая account restriction или обязательный client token. Полный refresh иначе мог бы молча отбросить dirty поля вне tuple, поэтому dirty target целиком отвергается, не только dirty criteria. Unrelated dirty objects других rows не запрещены и не flush внутри helper; обычный последующий caller commit остаётся.

## Точный capture/lock алгоритм

1. Войти session.no_autoflush до любого ORM getattr. inspect(order), проверить session/persistent/identity/deleted и histories без attribute load. Проверить source fields присутствуют в state.dict и не expired/unloaded. ID взять state.identity, expected tuple только state.dict.
2. Сохранить нынешний набор source: status, search_revision, driver_id, destination_changed_at, to_lat/to_lng, waypoints_json, category, pricing_k, ride_price, price_estimate, pending_to_lat/lng/text/price. Не добавлять непроверенные policy fields молча.
3. SQLite conditional no-op UPDATE по id + allowed status + каждому exact NULL-safe expected field. SET search_revision=search_revision, без арифметики/диапазона/rotation. RETURNING id полностью consume/close, matched row обязателен; missing/mismatch — прежний conflict. Existing NULL revision и accepted route допустимы, `_bump` не использовать для lock.
4. После matching SQLite write lock выполнить прежний full refresh FOR UPDATE и сравнение tuple/status под no_autoflush. PG выполняет прежний refresh row lock после общего capture precondition, без no-op UPDATE/User lock.
5. Helper не commit/rollback. Route/price/promo/revision mutation и commit остаются прежним apply; held lock переживает helper return. Ошибки/BUSY/serialization распространяются, caller rollback whole unit, без автоматического retry или adoption чужого snapshot.

SQLite valid route получает дополнительный UPDATE, с реальным trigger event. Source/disposable schema проверяется, deployed schema нет. При custom trigger изменение captured field приводит к conflict/rollback; arbitrary-trigger equivalence не обещается. Изменение identity/route-owner до capture через auto-load нельзя считать исходным snapshot, поэтому такие inputs отвергаются, не перезагружаются внутри guard.

## Обязательный RED/GREEN и конкурентные проверки

- Сохранённый baseline reproducer: два вида route, actual cancel commit между old refresh и bump изменяет route/revision при cancelled status. В новом guard второй writer после защищённого refresh не должен commit до release. Не просто изменить ожидаемый статус на произвольный исход.
- Cancel/accept first: реальный transition/commit другим physical connection, route stale snapshot → conflict; independent full SQL tuple подтверждает отсутствие новых route/price/revision/token effects.
- Route first: второй physical SQLite connection получает actual BUSY до записи; при route commit/rollback release, subsequent cancel/accept выполняется только после явного rollback/fresh read. PRAGMA timeout восстанавливается в finally до pool; failures не маскировать.
- PG distinct PID + pg_blocking_pids, оба winner orders, bounded timeouts; no new User lock. Предварительная читательская SQLite TX может дать BUSY_SNAPSHOT/upgrade failure; честный rollback, не гарантия ожидания.
- No-SQL inputs: expired/unloaded/dirty tuple, dirty non-tuple order field, detached/deleted/transient/wrong Session. Guard проверять до implicit id load/autoflush. Unrelated new/dirty других rows не flush внутри helper, сохраняются при последующем commit и откатываются caller rollback.
- Valid destination/waypoints searching/offered/accepted по настоящим HTTP auth/quote/apply; принятый маршрут не увеличивает search revision. None fields/CAS, every tuple component change, trigger audit extra no-op/rollback и trigger-induced mismatch. Capture контракт не поддерживает подмену SQL/guards/commit.
- Existing route/revision/entry/lifecycle suites, actual PG и FULL после независимой приёмки. Старые known-boundary tests обновляются только соответствующим narrow RED/GREEN доказательством; прежние логи/история не удаляются.

## Что НЕ исправляется

Fallback имеет собственный refresh и не получает эту гарантию автоматически. Изменения tariff/live position/quote после snapshot, поля вне нынешнего tuple, historical equal-value replay и tokenless requests не решены. Не меняются account deletion, schema/counter types, all-writer pending transfer, scanner/claim или activation. Durable продолжение ещё не завершено. Main/production/paused операции не трогать.

## Независимый GO до реализации

Journal и отдельный reviewer приняли narrow план после уточнения: кроме inspect.deleted обязательно проверять `order in session.deleted` ДО flush, поскольку pending delete ещё persistent/deleted=False. Отдельные no-SQL controls для scheduled delete и flushed deleted обязательны. Histories читаются passive через history.has_changes, не load_history/getattr. Присвоение того же значения при отсутствии semantic history change допускается; membership в session.dirty сам по себе не запрет.

Loaded/nonexpired требование относится к source tuple и ID из identity; unrelated unloaded/expired column не создаёт новый отказ сам по себе. Target dirty history любого column запрещён из-за full refresh. Для SQLite no-op применять synchronize_session=False, RETURNING полностью закрывать до refresh. No_autoflush охватывает весь capture→conditionalwrite→refresh, включая проверки Session membership. Actual HTTP всех трёх apply callers, accepted без revision increment, pending-delete/no-SQL и наблюдаемая невозможность concurrent commit до release обязательны. После фиксации этих условий разрешена отдельная реализация B; это не runtime acceptance и не полный route/durable guarantee.

## Runtime freeze и предварительная приёмка

2e2347e меняет только route capture helper и список source fields. Existing route/revision scoped: 78 PASS, exit0 (`/tmp/taxi-route-B-existing-dD9ZdS.log`). Independent read-only runtime review не нашёл P0/P1/P2: passive capture/session.deleted, clean target, loaded source, NULL-safe SQL guard, consume/close и no_autoflush до refresh подтверждены. PG сохраняет row lock, новых User lock/commit/rollback/increment внутри guard нет. Actual tests/PG/FULL ещё впереди.

Уточнение ID: expired только ORM id при известной inspect.identity и загруженном source допускается без implicit load; full/source expiry отвергается. Первый новый probe run `/tmp/taxi-route-capture-red1.log` фактически дал 1 PASS на новом runtime, поэтому НЕ называется RED несмотря на имя файла. Отличающий baseline RED готовится изолированным in-memory старым helper из96d8b97, без отката checkout.

Первый expanded author GREEN на runtime2e2347e: 153 PASS / 4 SKIP, exit0, 3.57 с (`/tmp/taxi-route-capture-green1.log`). Все три actual HTTP apply callers прошли, accepted revision не увеличивается. Два прежних interleave теперь требуют наблюдаемого BUSY второго writer после protected refresh. Отличающий RED: только старый helper96d8b97 загружен изолированным temporary plugin, новый expected-BUSY test дал ровно1 FAIL, поскольку cancellation действительно commit-илась (`/tmp/taxi-route-old96-red.log`, `.exit`). Это явная in-memory baseline подмена helper для сравнения, не откат checkout и не production запуск. Первый red1=1PASS нового runtime по-прежнему не называется RED.

Эти tests ещё до freeze: добавляются every-tuple CAS, PG reverse-wait и preexisting SQLite reader controls; финальные count/review/PG будут записаны отдельно.

Author tests freeze7180a8b: **175 PASS / 6 PG SKIP**, exit0, 3.77 с (`/tmp/taxi-route-capture-green3.log`, `.exit`), новый файл и прежний locked-bump suite. Покрыты no-SQL invalid capture, source/allcolumn histories, every-tuple mismatch, actual HTTP три accepted apply пути и searching/offered route, SQLite cancel/accept winners/BUSY/read-snapshot/PRAGMA finally, TEMP trigger rollback. Четыре новых PG cases подготовлены, ещё не запускались. 37fbb3d добавляет fixed route-capture-locks mode для этих двух файлов; default/guard unchanged, early unknown/extra reject64 и static PASS. Final independent scoped/PG/FULL впереди.

Independent expanded scoped: 405 PASS / 12 PG SKIP, 9.25 с, exit0 (`/tmp/taxi-route-capture-independent-scoped.log`, `.exit`), Ruff/diff PASS. Runtime новых P0/P1 не найдено, но test-only P2: route_order fixture оставляет собственные Notification refs. Actual services.notify сохраняет Notification до send_push spy; ref_id не FK cascade. Исправляется только own-ref cleanup до order, без global purge; transport spy не означает отсутствие notification persistence. После correction нужен focused repeat до PG.

P2 cleanup исправлен a17e7c7: own Notification/OfferDecline/history удаляются до order, Redis finally сохраняется. Independent focused двух файлов: **175 PASS / 6 PG SKIP**, 4.38 с, exit0 (`/tmp/taxi-route-capture-independent-focused.log`, `.exit`), Ruff/diff PASS; открытых P0/P1/P2 нет до PG.

Author actual disposable PG route-capture-locks: **160 PASS / 21 SKIP**, 5.46 с, actual exit0 (`/tmp/taxi-route-capture-author-pg1.log`, `.exit`). Runtime2e2347e/tests a17e7c7/runner37fbb3d. Secrets0. Независимые PG/default и FULL ещё впереди, публикации B нет.

Independent actual PG: **160 PASS / 21 dialect SKIP**, 5.57 с, exit0 (`/tmp/taxi-route-capture-independent-pg.log`, `.exit`). Все четыре новых both-winner PG blocking cases и два прежних bump lockwait реально PASS. Unchanged default: **255 PASS**, 12.51 с, exit0 (`/tmp/taxi-route-capture-independent-regression.log`, `.exit`). После каждого fresh cluster cleanup пуст. Final narrow review PASS, открытых P0/P1/P2 нет. FULL запускается отдельно; гарантия не распространяется на fallback, всю quote freshness или durable recovery.

## Итог полного прогона

FULL на `4f619288adf1adb7dc25f904060954ec5ff5aa1e`: **8000 PASS / 489 SKIP / 815 warnings**, 710.77 с, actual exit **0**. Команда: `env -u DATABASE_URL -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u http_proxy -u https_proxy -u all_proxy TAXI_TRIED_LIFECYCLE_ENABLED=false TMPDIR=/tmp/taxi-route-capture-full-tmp TZ=UTC /tmp/taxi-next-venv/bin/python -m pytest tests -q --disable-socket --allow-unix-socket` из backend. Полные доказательства: `/tmp/taxi-route-capture-full1.log` и `.exit`. SKIP не является PASS; реальные PG проверки зафиксированы отдельно выше. После actual exit ресурс освобождён Amio.

Пакет принят только для описанного narrow route capture. Это не активация SQL history/counter cutover, не исправление tokenless replay, не завершённое durable продолжение удаления аккаунта. Новых моделей/миграций/production изменений нет. Дополнительный SQLite UPDATE и строгие internal clean/loaded preconditions остаются явно описанными ограничениями. Последующая правка этого отчёта не меняет проверенный runtime/tests.
