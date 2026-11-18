# Search revision: проверка настоящих PostgreSQL-блокировок

Test-only пакет поверх `6791f9c` (опубликованный runtime `e8c7470`). Кандидат тестов `7d6de98`. Runtime, схема и зависимости приложения неизменны; действующие БД и production не использовались.

## Что доказано

10 сценариев вызывают настоящие helpers `start_matching`, `try_offer_next`, `apply_destination`, `apply_waypoints`, `add_fallback_category`:
- Два одновременно прочитавших старую revision start caller увеличивают счётчик дважды, без lost update.
- Два NULL initializer используют разные соединения; только победитель переходит к lookup, проигравший не присваивает чужую generation.
- Старый candidate/empty lookup после настоящего restart с теми же часами, round и token не записывает offered/expired поверх новой revision и не производит Redis/push effects.
- Destination/waypoints/fallback writer удерживает реальный row lock до commit. Старый matcher действительно блокируется этим backend PID (`pg_blocking_pids`), затем проигрывает CAS. Проверены candidate и empty пути для каждого writer.

Соединения явно закреплены за разными backend PID. Barrier/Events и server timeout ограничены; освобождение удержанного writer выполняется в finally. Redis/уведомления синтетические. Это доказательство транзакционной границы PostgreSQL, не end-to-end доставки push.

## Изоляция и запуск

`backend/scripts/run-taxi-revision-postgres.sh` принимает каталог уже установленных доверенных PostgreSQL binaries и Python окружение с зависимостями backend:

```bash
YULDASH_TEST_PG_BIN=/path/to/trusted/postgresql/bin \
YULDASH_TEST_PYTHON=/path/to/backend/venv/bin/python \
bash backend/scripts/run-taxi-revision-postgres.sh
```

Runner не принимает существующий DATABASE_URL: удаляет унаследованные DB/PG/proxy-настройки, создаёт свой mktemp-каталог с umask077, новый случайный SCRAM-пароль и случайный loopback-порт. Unix sockets отключены. До создания fixture-БД проверяются собственный data_directory и адрес127.0.0.1. Тестовый guard до client/lifespan дополнительно проверяет каталог, host/port/user/database и отсутствие URL overrides. TCP pytest разрешён только127.0.0.1. Cleanup останавливает только свой кластер и удаляет только свой временный каталог; при ошибке остановки сохраняет каталог и сообщает проблему.

Проверенный runtime: установленный npm-пакет `@embedded-postgres/linux-x64` версии `18.6.0-beta.19`, бинарник сообщает PostgreSQL18.6. Это локальный тестовый PostgreSQL, не доказательство версии/миграции production. SQLModel создаёт таблицы только новой fixture-БД; настоящая Alembic upgrade реальной БД не выполнялась.

## Результаты

- Первый предпуск безопасно NOT RUN77: в пакете отсутствуют createdb/psql. Bootstrap заменён установленным psycopg2, guard проверяет кластер до CREATE DATABASE.
- Unix socket вариант дважды не смог стартовать из-за OS `Operation not permitted`, включая одобренную escalation; тесты не выполнялись, кластеры удалены. Отдельный TCP-loopback preflight PASS; новая альтернатива независимо проверена до запуска.
- Авторский настоящий PostgreSQL:10 PASS,exit0,2.18s.
- Независимая приёмка кода/изоляции и новый PostgreSQL:10 PASS,exit0,2.20s; P0/P1/P2 не найдено. В обоих случаях cleanup подтверждён отсутствием временных cluster directories.
- Обычный SQLite запуск:56 PASS /10 skipped,exit0,2.15s. PG tests честно пропускаются на SQLite, а не выдаются за доказательство блокировок.
- Ruff app/new test F, bash syntax и diffcheck PASS.

Новый FULL в test-only пакете не выполнялся: `backend/app`, Alembic и зависимости побайтно неизменны относительно принятого runtime. Последний полный runtime gate на7f1a1b2:7039 PASS,119 skipped,exit0; он не называется новым прогоном этого пакета.

## Что не доказано

Mixed old/new writers по-прежнему не дают гарантии revision; требуется отдельный разрешённый rollout всех процессов и завершение старых in-flight работ. Production миграция не выполнялась. Redis cleanup/distributed transaction, delayed postcommit push, другие PostgreSQL версии и весь PostgreSQL backend suite здесь не проверены. Это10 конкретных concurrency regressions, не готовность всего режима такси.
