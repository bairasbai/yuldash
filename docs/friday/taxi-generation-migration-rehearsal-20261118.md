# Disposable generation migration rehearsal: план

Только test-local мини-схема и allowlisted runner после review. Это не production migration, не InstantOrder и не Alembic. PostgreSQL: собственная nonce schema, guard до DDL, --noconftest. SQLite: собственный временный файл. Таблицы: owner с двумя BIGINT-колонками, child FK, index/CHECK и synthetic queue.

## PostgreSQL

Upgrade выполняет ALTER обеих колонок в NUMERIC в одной транзакции. Данные: NULL, −1, 0, MAX и смешанные пары. Проверяются точный Decimal readback, реальные FK/CHECK отказы и сохранность индекса. После commit настоящий adapter записывает MAX+1 и 10**40+1; отдельное соединение читает точные значения. Rollback после первого и обоих ALTER восстанавливает BIGINT и данные.

DDL lock: reader transaction блокирует ALTER; подтверждаются разные PID и pg_blocking_pids, ограниченные timeout, release и фактический commit. Это демонстрация блокировки, не оценка production latency.

Downgrade preflight под table lock в той же транзакции проверяет обе колонки: NULL либо finite integral NUMERIC в signed BIGINT диапазоне. Отдельно проверяется synthetic pending-work gate. Дроби, NaN, ±Infinity, MAX+1 и MIN−1 в каждой колонке отклоняются без ALTER и изменения данных; положительный контроль — NULL/MIN/MAX. Никакого округления, обрезания, clamp или reset. Concurrent writer не может попасть между validation и DDL. Pending gate — модель policy, не проверка реальных очередей.

## SQLite

Подтвердить исходный INTEGER storage, FK, index и CHECK. На выделенном connection отключить FK до транзакции, затем BEGIN IMMEDIATE до чтения. Создать replacement TEXT table, перенести данные через строгий Python int→canonical text, удалить старую таблицу, переименовать копию и восстановить индекс. Не переименовывать старую таблицу во временное имя, чтобы child FK не переписался на него.

Выполнить foreign_key_check внутри транзакции до commit. В finally восстановить FK ON и проверить enforcement. Ошибки после copy/drop/rename должны откатывать исходную schema и данные. Второй physical connection получает BUSY. Только собственный файл: это не универсальная миграция SQLite и не разрешение отключать production FK.

## Старые writers и replay

Negative controls: старый MAX gate блокирует progress после расширения; SQLite raw SQL +1 над TEXT нарушает точность. Synthetic persisted queue со старой generation после нового commit получает exact CAS 0. Но stale input с совпадающим expected value неотличим одними числами: это не доказательство drain или revocation. Old-restart модель фиксирует несовместимость, а не проверяет реальное развёртывание. Tokenless HTTP delayed requests остаются отдельной неисправленной границей.

Все threads/connections завершаются до DROP собственной schema; cleanup проверяется. Реальные fleet drain, backup restore, очереди, negative historical ABA, все writers, production downtime и account deletion здесь не проверяются. До реализации требуется независимый review; DDL разрешён только на disposable fixture.

## Независимое разрешение реализации

Journal дал PASS test-only на 57a09dc. Lock preflight берётся до validation и блокирует INSERT/UPDATE до обеих ALTER; отказ проверяется независимым catalog/data read после rollback. PG cast может округлить дробь и не заменяет preflight. FK/index/CHECK проверяются реальным enforcement и definition, а не именами. SQLite PRAGMA проверяется вне транзакции; после каждого отказа FK ON, bad-child rejection и integrity_check обязательны. Этот первый срез включает SQLite upgrade/rebuild и PostgreSQL downgrade; SQLite downgrade не реализуется и не заявляется. Равное stale generation значение в queue должно остаться положительным counterexample защиты от replay.

## Реализация и замечание независимой приёмки

Первый freeze 2609233: SQLite 6 PASS / 19 PG SKIP, статические проверки PASS. Независимый scoped двух файлов на 076c757 дал 34 PASS / 53 SKIP, exit 0, 1.95 с, но review выявил P2: отсутствовал обязательный PRAGMA integrity_check после success и rollback. Это пропуск acceptance, не успешная финальная приёмка.

Исправление 12d79fe добавляет в общий helper точную проверку `[('ok',)]` после восстановления FK, во всех copy/drop/rename rollback cases и success. Авторский повтор: 6 PASS / 19 SKIP; лог `/tmp/taxi-migration-integrity-vARVbO.log`. Дополнительно увеличивается только fixture Event deadline с 5 до 20 секунд; SQL lock/statement timeout не меняется. Реальный PG пока не запускался.

Final fixture deadline fix d592698: Event waits 20 с, SQL timeout 6/8 с прежние. Независимый повтор после закрытия P2: SQLite 6 PASS / 19 SKIP, exit 0, 0.34 с. Author actual generation-migration-rehearsal mode: **25 PASS**, 1.00 с, exit 0 (19 PG + 6 SQLite), `/tmp/taxi-generation-migration-pg1.log` и `.exit`. Собственная schema и cluster очищены; фактические ALTER lock/rollback, preflight и сохранность мини-схемы проверены. Independent PG ещё впереди, никакой production-ready migration не заявляется.

## Финальная независимая приёмка

После исправления P2: **25 PASS**, 1.38 с, actual exit 0 (19 PostgreSQL + 6 SQLite), `/tmp/taxi-generation-migration-independent-pg.log` и `.exit`. Отдельный fresh default regression: **255 PASS**, 14.99 с, exit 0, `/tmp/taxi-composition-migration-independent-regression.log` и `.exit`. Cleanup подтверждён, временных кластеров нет. Независимый reviewer: PASS, открытых P0/P1/P2 нет. Новый FULL не запускался для test-only пакета. Miniature rehearsal не является готовой production migration, проверкой actual account recovery или реальным fleet-drain proof; равный stale replay остаётся контрпримером.
