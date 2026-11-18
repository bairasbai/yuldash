# Неактивный bounded cleanup SQL history: план до кода

База b5462fc. Предыдущая schema-only не включена, writer отсутствует. Этот срез не добавляет scheduler, endpoint, flag, миграцию, discovery scan или вызов из matcher. Только отдельно вызываемый лабораторный helper и изолированные PG тесты после review. Main/production/paused операции не трогать.

## Минимальный контракт (предложение)

Новый отдельный модуль: функция принимает SQLAlchemy Engine, order_id, batch_limit (1..500), создаёт собственные connection/transaction; не получает caller Session и не commit/rollback чужой работы. Только PostgreSQL, обычный Engine, не Connection и не shared SQLite pool. Другой dialect — явный отказ до SQL. Не использовать global app engine/config внутри функции, не вызывать автоматически. Engine обязан быть выбран авторизованным caller; тесты только guarded disposable cluster.

1. SELECT только scalar id/version/lifecycle/shared expiry FROM instantorder WHERE id=:id FOR UPDATE. Одна owner строка на вызов; lock до любого DELETE. Missing owner → no-op (нормальные orphan невозможны из-за FK).
2. version точно1, lifecycle ровно32 lowercasehex, expiry nonNULL naiveUTC. NULL/unknown/nonpositive version, invalid/NULL lifecycle, missing expiry → fail closed, zero delete. Schema sentinel не становится activation. Функция не изменяет owner/version/expiry.
3. Время берётся через PostgreSQL clock_timestamp ПОСЛЕ получения lock, приводится к UTC naive. Не transaction_timestamp/now(), не timestamp до ожидания. Не принимать внешний stale cutoff.
4. Под lock eligible rows: тот же order_id и lifecycle != current lifecycle; плюс current lifecycle только если shared expiry <= captured time. При future expiry current members сохраняются независимо от статуса заказа (даже terminal: консервативно). Старые lifecycle уже неактивны только при соблюдении будущего all-writer owner-lock protocol. Mixed old writers не поддерживаются.
5. DELETE через bounded subselect composite PK, стабильный ORDER BY lifecycle,driver_id LIMIT batch_limit; максимум500 удалений на вызов, один commit. Это ограничение работы, НЕ cap истории: eligible остаток остаётся для следующего вызова, active set не pruned. Не обходить все orders/не циклить до пустоты. Return count без идентификаторов/PII logs.
6. Error → rollback собственной транзакции и освобождение lock, исключение наружу. Без автоматического retry после unknown commit outcome; DELETE идемпотентен по состоянию, но результат ответа при lost ACK не обещать. Parent deletion конкурирует на том же row lock; FK cascade прежний.

## Доказательства до принятия

- PG future current сохранён, old lifecycle удалён; expired/equal boundary current удалён; other order untouched; bounded repeated batches не трогают active.
- NULL/unknown/invalid/missing expiry/missing owner no-op, owner fields не меняются. Invalid batch/type/dialect reject до SQL.
- Real lock wait: writer держит owner lock и renews общий expiry в future; cleaner начинает transaction раньше, ждёт, затем fresh reread/clock сохраняет current members. Отдельно writer rotates lifecycle пока cleaner ждёт: удаление по новому owner, не captured stale state.
- Reverse race: cleaner держит lock перед DELETE, writer waits, после commit writer добавляет/renewal под lock; новый member не потерян. Наблюдать pg_blocking_pids/отдельные PID, не только thread events.
- Fault после DELETE до commit → rollback rows и release; subsequent writer проходит. Вызов не влияет на unrelated caller transaction/connection. Timeout/сетевая exactly-once доставка не заявляются.
- Минимальные pure validation tests + настоящий PG helper (не SQLite lock imitation); независимое review/scoped/PG, FULL нового app module. Сначала согласовать слот Amio.

## Оставшиеся блокеры активации

Этот helper сам не делает retention bounded по времени: нет scheduler/discovery/index/операционного бюджета/monitoring, parent может жить бесконечно. Нет SQL writer/reader/history reset+renewal, нет rollout/mixed fleet/drain протокола. Clock после lock не делает cleanup атомарным с Redis/FCM. GC не исправляет precommit Redis rollback. Не подключать helper до отдельного all-writer протокола и операционного retention review. Если review считает app helper преждевременным, сузить до test-only prototype, не подключать скрытно.

Research независимо подтверждает single-owner направление. Дополнительный activation blocker: после частичной очистки expired current будущий writer обязан под тем же lock атомарно reset **всех** оставшихся expired members перед add+renewal; простое продление expiry оживит старые исключения. Старый lifecycle нельзя переиспользовать. Ограничение500 относится к числу удалений, не bounded latency/scan cost/lockwait: большой order может требовать дорогого поиска eligible rows, а ожидание lock без отдельной operational policy не имеет гарантированного срока. Предварительный scan не даёт права DELETE. Own Engine transaction выбран вместо caller Session, чтобы ошибка/commit helper не затрагивали чужие pending writes.

## Pre-code independent review

Journal условный PASS8f25685 для uncalled lab-only app helper, подтверждён родителем. Обязательное уточнение: Engine type не гарантирует отдельный DBAPI connection, отвергать StaticPool/SingletonThreadPool и Connection до SQL; PG тест distinct backend PID плюс unrelated caller pending/flushed writes. Reject bool batch_limit (bool является int в Python). DELETE subselect и outer composite match включают order_id. Добавить owner-delete contention и partial expired batches. Rowcount не exactly-once ответ при lost ACK. Называть bounded deletion count, не bounded work/latency. Все будущие writers держат owner lock, включая insert/reset/rotation/renewal; старый lifecycle никогда не переиспользуется и не пишет после ownership recheck. Это условия активации, текущий lab их не включает.

## Первый кандидат и приёмка тестов

Helper095fe28 не имеет runtime callers (поиск app references только definition). Tests7f3d9d4: author17 unit PASS/20 PG skips0.43с; independent51 PASS/20 skips3.07с, Ruff/bash/diff PASS. Статический P2: clockwait case мог не различить transaction_timestamp на медленной машине. Исправлен4c806b8: expiry устанавливается holder после подтверждённого pg_blocking_pids, фактический GC xact_start < expiry, real clock >= expiry до release. Reverse release timeout20с. Author повтор17 PASS/20 skipped. PG/FULL ещё ожидаются.

Author realPG47 PASS3.99с, independent47 PASS4.35с, actualexit0, собственные кластеры очищены. Логи /tmp/taxi-history-gc-pg.log и /tmp/taxi-history-gc-independent-pg.log. Исправленный clockwait различающий контроль прошёл, независимая приёмка без открытых P0/P1/P2. Detect-secrets всех6 changedfiles0. FULL следующий gate. All-writer/reset/rollout/scheduler/latency budget не реализованы и не разрешены этой приёмкой.

## Финальная локальная приёмка

FULL64f7c1a: **7373 PASS, 166 skipped, 815 warnings**, actual exit 0, 678.63с. /tmp/taxi-history-gc-full.log и .exit. Ресурс передан Amio через followup после actual exit. Независимый PG47 PASS применим к helper095fe28/tests4c806b8, SQLite/scoped51 PASS; исправленный PG clock-control принят отдельно. Код не подключён, новых scheduler/endpoint/flag/callers нет. Это лабораторное доказательство cleanup primitive, не обещание активного retention, bounded latency, исправления Redis rollback или разрешения production.
