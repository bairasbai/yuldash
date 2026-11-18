# Expiry cleanup: узкая защита поколения

База9e76530, ветка fix/friday-taxi-expiry-cleanup-generation-20261118. Runtime пока не меняется. Две регрессии воспроизводят postcommit restart с настоящим start_matching/offer/SADD: старый expiry затем удаляет tried нового поиска и отправляет устаревшее expired уведомление. Исходный scoped2FAIL,exit1,1.15s; не ошибки fixture.

## План до реализации

1. Сохранить scalar order_id до expiry commit. После успешного expiry CAS открыть новую read-транзакцию, fresh SELECT FOR UPDATE/populate_existing.
2. Под блокировкой сверить status=expired и search_revision=expected. При проигрыше новому состоянию не удалять tried и не отправлять старое уведомление, вернуть актуальный заказ.
3. При совпадении выполнить существующий best-effort _cleanup_tried под DB row lock; сохранить passenger_id/payload для notify, освободить read-транзакцию в finally (rollback). Expiry ранее committed и не откатывается. После release разрешено уведомление, не под сетевым push держать DB lock.
4. Сохранить notify=False, CAS loser без effects, работу worker после ORM expiration от rollback. Redis exception не отменяет expiry; lock обязательно освобождается.
5. Регрессии исходной гонки, unchanged expiry cleanup, notifyFalse, Redis failure/lockrelease и следующий заказ worker. Отдельный настоящий PG test: restart действительно ждёт cleanup row lock, после release получает новую revision/tried. Независимая приёмка, scoped и новыйFULL перед отдельной публикацией.

## Границы

Это не Redis/DB distributed transaction. У Redis connect/read timeout1с, но retries/DNS означают, что общий срок удержания lock не гарантируется. При timeout уже отправленный DELETE может исполниться после release; данная правка не защищает неопределённый сетевой исход. Delayed postcommit push после контрольной точки также остаётся отдельной проблемой. Прежние cleanup accept/cancel не меняются и не объявляются безопасными. Mixed old/new processes не покрываются. Не менять namespace tried, общий Redis client, архитектуру или приостановленные пакеты; main/production/реальные БД не трогать. PostgreSQL проверки только на одноразовом локальном кластере.

## Реализация и текущие доказательства

План34b91cc принят независимым read-only reviewer до кода. Runtime d38cece: fresh locked status/revision guard, missing/changed generation skip, finally rollback только новой read-транзакции; scalar notification payload фиксируется до rollback. Другие cleanup не изменены. 8 новых SQLite cases включая исходные2RED, notifyFalse, Redis exception, missing row, CAS loser и настоящий retry_waiting_orders с двумя заказами. Scoped115 PASS/2PGskip,exit0,3.06s; ruff/diffcheckPASS.

Настоящий одноразовый PostgreSQL runner:12 PASS,exit0,2.23s (предыдущие10revision+2новыхcleanup). В новых случаях pg_blocking_pids доказывает, что restart ждёт cleanup-lock; normal DELETE завершён до нового SADD, unexpected cleanup exception тоже освобождает lock и новый поиск продолжается. Cleanup временного PG-кластера подтверждён. Независимая приёмка реализации и FULL ещё ожидаются.

## Финальный результат локальных проверок

Независимая приёмка runtime d38cece:130 scoped PASS/2SKIP,exit0,3.24s; новый настоящий PostgreSQL12 PASS,exit0,2.29s, cleanup подтверждён, P0/P1/P2 не найдено. FULL на533908f:7047 passed,131 skipped,804 warnings,exit0,626.19s. В SQLite FULL двенадцать PG-сценариев честно skipped, отдельно они исполнены на настоящем PostgreSQL выше. Команда pytest tests -q --disable-socket --allow-unix-socket, TMPDIR=/tmp/taxi-expiry-cleanup-full, DATABASE_URL/proxy unset,TZ=UTC. Это локальный полный gate без coverage threshold, не remote CI и не production rollout.

Runtime после FULL не менялся. Diff credential-pattern scan0; ruff app/newtests F, bash syntax и diffcheckPASS. Готовится отдельная ветка GitHub; main, production, реальные существующие БД и приостановленные пакеты не изменены. Перечисленные ограничения Redis timeout/latency/push/mixed writers остаются в силе.
