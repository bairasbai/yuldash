# Transactional resume composition: test-only план

База: bda0099 / remote 88fe3fda93e1130f62e1699794e8eae35ff460ea. Цель — соединить настоящие ExactGeneration и evaluate_resume_claim в изолированной транзакционной модели. Новые production handlers не создаются. Actual account→worker→matcher recovery остаётся невыполненным.

## Контракт

Собственная таблица содержит полный OwnerSnapshot и три pending-поля; обе revision-колонки используют настоящий adapter. Никаких InstantOrder или app FK. Test-local orchestrator принимает активный caller Connection и captured immutable snapshot. PostgreSQL: fresh SELECT FOR UPDATE. SQLite: caller BEGIN IMMEDIATE до первого чтения. Clock снимается после lock: PG clock_timestamp преобразуется в naive UTC, SQLite clock читается после BEGIN. Настоящая pure policy получает captured/fresh/clock, не присваивая себе другого owner.

Только due выполняет UPDATE next_at/attempts с точным NULL-safe WHERE полного owner и pending. Rowcount обязан быть 1; ноль — отдельный CAS-lost, не success или ack. Helper не коммитит и не откатывает внешнюю транзакцию; caller явно делает commit либо rollback, ошибки не подавляются. Результат APPLIED до commit не является durable claim. Нет network/Redis/push/lookup. Row lock сохраняется до завершения caller transaction.

Test-local transfer под lock и full-tuple CAS проверяет согласованность pending; exact_next(old) записывается в обе generation-колонки. Attempts сохраняется, next_at=min(old_next, post-lock clock). Offered outcome сохраняет provenance; pure policy не исполняет offered. Model advance offered→searching переносит intent на новое поколение. Terminal transition очищает pending тем же CAS; not_due/obsolete не дают разрешения на clear. Это модель будущих writers, не настоящий advance/account.

## Матрица

- Committed readback больших точных generations; rollback до claim commit; modeled interruption после commit сохраняет cooldown. Это не process kill или network proof.
- Два PG worker с разными PID и наблюдаемым pg_blocking_pids: один claim; stale consumer после ожидания получает obsolete. Fresh snapshot до cooldown даёт not_due, позже — новую proposal.
- Transfer или terminal во время ожидания делают captured устаревшим и не допускают resurrection. Offered-retain→model advance сохраняет обязательство.
- Rollback claim/transfer/terminal, unrelated caller writes и явный commit.
- Каждый owner/pending field имеет stale control. Actual SQL full-tuple CAS rowcount 0 не объявляется success. Нужен реальный zero-row control: при необходимости own-fixture trigger или direct CAS после фактического изменения, не monkeypatch rowcount. Policy validation и SQL CAS проверяются отдельно.
- SQLite: два физических connection, file DB, BEGIN IMMEDIATE, busy/rollback. Это не PG row lock. Exact clock equality проверяется pure test; PG lock-wait case доказывает clock после ожидания, не искусственное равенство wall clock.

Negative generation invalid для текущей policy; signed adapter проверяется отдельно. Переполнение 128-digit lab budget отвергается до записи, без reset/clamp. Retry fairness, outage, cutover, persisted stale queues и tokenless HTTP replay не решены. Новый allowlisted runner mode запускает только этот файл в fresh cluster без app conftest. Собственная schema очищается после join всех workers. До реализации требуется независимый review.

## Независимое разрешение реализации

Journal дал PASS test-only на 57a09dc; редакционная версия 3da65ec сохраняет scope. Обязательны actual caller transaction/autocommit false, READ COMMITTED в PG, отдельный SQL zero-row control, independent readback до/после commit и rollback, clock после реального lock wait. Все nullable owner/pending поля участвуют в CAS. Trigger допускается только как явная fixture injection, не обычная гонка. Реализация начата в одном новом тестовом файле; production functions не добавляются.

## Freeze тестовой композиции

99ce004 и уточнение 076c757: SQLite 28 PASS / 34 PG SKIP, exit 0, 1.61 с, `/tmp/taxi-resume-composition-author3.log`. Независимый static принял full CAS/zero-row и ограничения модели. Проверка raw.in_transaction не объявляется enforcement BEGIN IMMEDIATE: это caller precondition, а используемый путь отдельно доказывает BUSY второго writer до fresh read. Offered-модель проверяет только provenance, не matching budget; сохранность round утверждается явно. Real PG ещё впереди.

Author actual resume-composition mode: **58 PASS / 4 SKIP**, 3.48 с, exit 0; `/tmp/taxi-resume-composition-pg1.log` и `.exit`. Режим включает SQLite и PostgreSQL cases, поэтому весь count не называется числом PG tests. Фактические PG blocking/CAS/commit/rollback проверки прошли; собственная schema и cluster очищены. Independent PG ещё впереди.

## Финальная независимая приёмка

На финальном test-only дереве: **58 PASS / 4 SKIP**, 3.67 с, actual exit 0 (`/tmp/taxi-resume-composition-independent-pg.log`, `.exit`). Из PASS: 30 PostgreSQL и 28 SQLite; SKIP: три PG-only concurrency case на SQLite и один SQLite-only BUSY case на PG. Отдельный fresh default regression: **255 PASS**, 14.99 с, exit 0 (`/tmp/taxi-composition-migration-independent-regression.log`, `.exit`). Cleanup подтверждён, временных кластеров нет. Независимый reviewer: PASS, открытых P0/P1/P2 нет. Новый FULL не запускался и не заявляется для test-only пакета. Это не actual account recovery, не активация и не доказательство fleet drain.
