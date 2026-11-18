# Tried TTL: узкий план до кода

База54a93c4; fix/friday-taxi-tried-ttl-atomic-20261118. При отдельном SADD и EXPIRE ошибка/обрыв между вызовами оставляет ключ без TTL. Lifecycle namespace защищает другой цикл, но orphan old keys могут не исчезнуть. SQL rollback после precommit SADD и поздний push — отдельные границы.

## Минимальное изменение

Один helper `_remember_tried(r, order_id, driver_id, lifecycle)` для matcher и reassign: захваченный nullable lifecycle проверяется, один captured key, `with r.pipeline(transaction=True)` ставит SADD и EXPIRE3600 и вызывает execute. Redis ошибки best-effort как прежде; invalid lifecycle/None client не обращаются к Redis. Существующие SQL CAS/rowlocks/момент precommit SADD/ownership guards не менять. Не менять config/схему/API/lifecycle flagdefaultoff.

Гарантия узкая: убрать клиентское окно между двумя отдельными командами; успешный EXEC записывает члена и TTL вместе, другой клиент не вклинивается между ними. Timeout всего EXEC не означает отмену: он мог исполниться; lifecycle key по-прежнему изолирует позднюю транзакцию. Redis transaction не обеспечивает SQL+Redis атомарность, rollback команд при runtime error или доказательство crash/durability. Не обещать общего deadline. Legacy NULL остаётся прежним общим ключом.

## Доказательства и тесты

1. RED на настоящем matcher/reassign: отдельный SADD успешен, EXPIRE transport failure оставляет key TTL=-1. Сохранить исходный отказ.
2. После helper: real fakeredis transaction, captured commands/TTL3600, отсутствие split expire вызова. ДоEXEC failure не публикует SADD; timeout после реального EXEC оставляет set+TTL; delayed wholetransaction старого lifecycle не меняет новый.
3. Отдельный диагностический тест SQL commitfailure после precommit записи: настоящий rollback оставляет DB searching, но tried содержит кандидата. PASS означает наблюдение оставшегося ограничения, не исправление. Не переносить SADD послеcommit: decline может успеть раньшеистории.
4. Адаптировать старые перехваты sadd/expire к новому helper или pipeline.execute так, чтобы CAS loser assertions продолжали реально видеть Redis writer; не оставить мёртвые mocks. Особенно pre-publication тест должен наблюдать EXEC доSQLcommit, не просто вызовподготовки.
5. Независимая оценка плана до кода; scoped/ruff, реальный PG existing17 дляSQLregression (Redisтамsynthetic), независимаяприёмка, FULL. Production/реальнаяБД/приостановленныеоперации не трогать.

Настоящий Redis network server/FCM в этом пакете не проверяются. Push требует своего совместимого payload/clientrefresh решения; timeout/retry, rollbackSADD иmixedfleet не объявлять устранёнными.

## Независимая оценка до кода

Journal принял5fbf135: требуется actual EXEC с двумя корректными replies, не просто успешная сеть/helper invocation. CAS spies должны наблюдать pipeline/execute, invalid и Noneclient не создают pipeline, duplicateSADD обновляетTTL. ДоEXECerror и timeoutпослереальногоEXEC раздельные тесты. WRONGTYPE подтверждает отсутствие transaction rollback, SQLrollbackdiagnostics остаются known-boundary PASS. Timingwriters и capturedkey не менять.

Исходный RED:4FAIL (matcher/reassign ×legacy/nonNULL), TTL=-1 после actualSADD+ошибкаотдельногоEXPIRE, DBпереходcommitted. Отдельные2PASS наблюдают Redis member после настоящего SQLrollback, не защиту. Лог сохранён отдельно, 1.20с/exit1.

## Реализация и проверки кандидата

Runtime3f26382: helper отправляет actual transactional pipeline SADD+EXPIRE3600+EXEC; matcher/reassign используют его в прежних местах. SQL/config/schema/API не менялись. 21 новый testcase включает4исходныхRED→PASS,2SQLrollbackdiagnostic, success/invalid/Noneclient/beforeEXEC/afteractualEXECtimeout/duplicateTTL/WRONGTYPE. 5старыхtestfiles адаптированы: CAS helper+pipeline spies, invalid pipeline forbidden, actualEXEC beforeSQLcommit, delayedwholeEXEC oldnamespace. Старыеassertionsсохранены/усилены.

Авторский combined scoped219PASS/exit0/6.39с, RuffF/diffcheckPASS. Real disposablePG17PASS/exit0/2.68с, cleanupempty; Redisтамfakeredis, не настоящая Redis сеть. ИсходныйREDлог4FAIL2known-boundaryPASS сохранён. Независимаяприёмка/FULL ожидаются.

## Оценка следующих границ

SQLrollback: автоматический SREM после ошибки commit не является безопасной компенсацией — член мог существовать ранее или стать нужен конкурентному writer того же lifecycle; ошибка commit также может иметь неопределённый исход. Перенос всей записи послеcommit открывает decline-before-history окно. Следующее решение требует отдельно спроектировать источник истины истории/публикации и ownership для компенсации, а не добавить ещё один Redis DELETE. Здесь намеренно сохранён действующий порядок с явной диагностикой ложного исключения кандидата после rollback.

Push: прежняя детерминированная accepted-after-reassign диагностика остаётся. TTL-транзакция не меняет доставку уведомлений. Следующий независимый серверный шаг может подготовить совместимые offer_token и абсолютный expiry в offer payload; показ и действие клиентов требуют отдельного refresh/validation и собственных UI gates. Ни sequence статусов, ни клиентская защита этим пакетом не добавлены.

Независимаяприёмка3f26382: expandedscoped255PASS/exit0/8.91с, realPG17PASS/exit0/2.64с, cleanupempty; P0/P1/P2 нет. RuffF/diffcheckPASS, detect-secrets9files0. FULL ожидается.

## Финальный локальный gate

FULL наf65a2de (runtime3f26382):7157 passed,136 skipped,804 warnings,exit0,654.97с. Изbackend: `python -m pytest tests -q --disable-socket --allow-unix-socket`, TAXI_TRIED_LIFECYCLE_ENABLED=false,TZ=UTC,TMPDIR=/tmp/taxi-atomic-ttl-full,DATABASE_URL/proxyunset. 17PGcases SQLite skipped и отдельно пройдены автором/независимо. Runtime послеFULL не менялся.

Независимые255scoped+17PGPASS,ruffF/diffcheckPASS,detect-secrets9files0. Это локальный gate, не remoteCI/реальныйRedisnetwork/production. ИзвестныеSQLrollback/WRONGTYPE/pushдиагностики остаются, ихPASSне означает защиту. Готовится отдельная публикация; schema/config/активацияproductionне менялись, приостановленныеоперациине повторялись.
