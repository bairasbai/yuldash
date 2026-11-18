# Enforceable driver slot: design and isolated SQL experiment

Base 2124f81 / published 328a347e. План до независимого review. Никаких app migration/index/lock изменений. Production и ранее приостановленные операции не затрагивать.

## Почему cooperative lock недостаточен

Предыдущие реальные PG diagnostics подтвердили: User→Order + fresh busy работает при cooperative READ COMMITTED, но обход writer и старый RR snapshot дают двойную публикацию; User lock не защищает profile online. Поэтому новый wrapper сам по себе не готов к активации. Рассматриваем отдельно DB occupancy invariant и admission: первый не заменяет второй.

## Кандидат, не выбранная production миграция

Один expression UNIQUE index над CASE на той же таблице orders:

```sql
CREATE UNIQUE INDEX driver_slot_unique ON fixture_orders ((CASE
  WHEN status = 'offered' THEN current_offer_driver_id
  WHEN status IN ('accepted','arriving','onboard') THEN driver_id
  ELSE NULL END));
```

Каждый nonNULL driver может занимать не более одного вычисленного слота. Это покрывает обычные SQL writers независимо от приложения и User lock, а не только новых callers. Срок offer не входит: истечение часов не должно неявно освобождать индексный слот, сначала явный status transition/reconcile. Terminal rows возвращают NULL, могут сохранять исторический driver. Нет cross-mode claim: rides/courier в другой таблице не покрыты.

Официальная документация PostgreSQL описывает уникальный индекс как DB-проверку конфликтующих ключей с ожиданием конкурирующей транзакции; NULL по умолчанию distinct. Expression/predicate должны быть immutable, поэтому clock-based expiry в индекс не предлагается. Это основание гипотезы, не заменяет собственные конкурентные тесты: [Index uniqueness checks](https://www.postgresql.org/docs/current/index-unique-checks.html), [Unique indexes](https://www.postgresql.org/docs/current/indexes-unique.html), [CREATE INDEX](https://www.postgresql.org/docs/current/sql-createindex.html).

Один unique index НЕ валидирует row shape/существование driver/admission. NULL occupied driver и чужие поля требуют отдельного решения. Исследовательский CHECK на fixture: offered требует current_offer_driver_id NOT NULL и driver_id NULL; active требует driver_id NOT NULL и current_offer_driver_id NULL. Это НЕ готовая совместимая миграция: account.py:658–659 сейчас очищает current_offer_driver_id без смены offered, и такой CHECK отвергнет текущий delete-account путь. Сохранить точный counterexample, не менять account deletion в этом пакете. Все исторические malformed rows и неизвестные statuses должны быть инвентаризированы до какого-либо реального ограничения; не молча чинить/удалять их.

## Безопасный первый срез

Test-only таблицы в уникальной fixture schema внутри guarded disposable PG cluster. TEMP таблица непригодна для cross-connection race; новая непубличная schema с nonce и явным cleanup только своей schema. Минимальные synthetic columns id/status/driver_id/current_offer_driver_id/offer_expires_at, без реальных пользователей/адресов и без app tables ALTER/DDL. Это SQL invariant experiment, НЕ actual matcher/helper integration. Имена schema генерировать доверенно, цитировать identifiers, чужие schemas никогда не drop. Изоляция/guard из существующего PG runner обязательны.

Никакого приложения, feature flag, scheduler, production index, reservation service или trigger. В тестах raw SQL моделирует запись canonical offered/active. Не импортировать/подменять модели, чтобы выдать fixture за production finalizer.

## Матрица

1. Offered↔offered и offered↔active same driver: concurrent real connections, winner держит INSERT/UPDATE; waiter подтверждён pg_blocking_pids. Commit winner -> exact unique violation23505, rollback winner -> second success. Уровни READ COMMITTED и REPEATABLE READ со snapshot доwinnercommit; фиксировать конкретный outcome, не catchany. Exact diagnostic constraint/index name в error и независимый SQL readback.
2. Bypass writer без User lock всё равно получает конфликт; distinct drivers и terminal historical rows не конфликтуют. Offered expired всё ещё занимает слот. Не обещать fairness/latency/absence deadlocks.
3. Offered→accepted на том же order сохраняет slot atomically; competing different order не может занять его посреди transition. Release terminal commit освобождает, rollback сохраняет. Прямые SQL INSERT и UPDATE paths покрыть.
4. Existing duplicates до CREATE UNIQUE INDEX должны дать23505; failure не считается успешной миграцией. В fixture нет автоматической дедупликации. Реальный migration readiness/CONCURRENTLY invalid-index cleanup здесь не доказан.
5. Index-only NULL loophole отдельным control: malformed occupiedNULL rows разрешены. Optional shapeCHECK fixture отвергает эти malformed rows точным23514. Реальный account-delete SQL shape на offered→current_offer_driver_id NULL получает23514; это доказанный compatibility blocker, не исправление и не повод подавлять CHECK.
6. Не допускаем silent status fallthrough claim: supported statuses fixture явно перечислены/проверены; unknown-status behavior отметить. Admission/profile/time-based restrictions не защищены индексом; прежний profile diagnostic остаётся применим.

## До eventual production решения

Нужны product acceptance semantics единственного offered/active слота, audit duplicate/malformed rows, совместимость account deletion и всех assign/release/accept paths, ошибка23505→rollback всей unit и безопасный next-candidate бюджет, обработка40001/40P01 без blind retry/двойных effects, graph User/Order/unique-wait/cross-mode/deletion. Unique index добавляет реальные waits и может участвовать в deadlock, даже без нового explicit User lock. Старый writer не обходит индекс, но может не обработать новую ошибку и дать500; поэтому constraint enforcement не означает safe mixed fleet rollout. План online migration/locking/rollback и schema-before-code отдельно, реальнуюБД не читать/менять.

Admission остаётся отдельным protocol: существующие guards preserve, authoritative recheck point/time-expiry, all mutable tables и Redis boundary. Не добавлять «eligible token» без обеспеченного revocation/writer contract. DB slot uniqueness не подтверждает личность, лицензию, геозону, рабочее время, долги, безопасность или delivery.

## Gates

Независимый pre-code review → test-only implementation → author/independent real PG + existing regression → exact-tree отдельная publication. App unchanged; новыйFULL не заменяет PG и старыйFULL не объявляется проверкой новыхcases. Ресурс согласовать с Amio. По результатам выбрать/отклонить DBcandidate до любой runtime реализации.

## Принятые условия независимого pre-code review

Journal PASS `034b748` только raw SQL fixture experiment. До CREATE проверить disposable database/data_directory/host guard; trusted nonce schema и fully qualified quoted identifiers, без изменения общего search_path. Workers завершить и rollback до DROP собственной schema в finally через отдельную healthy connection, cleanup errors не скрывать. Active↔active и reverse active/offered добавить; разные row IDs, чтобы23505 был occupancy index, не PK, assert SQLSTATE и точное index name. RR snapshot устанавливается SELECT доwait; same-row UPDATE40001 не смешивать с cross-row occupancy proof, неожиданный outcome не PASS. Status fixture NOT NULL с явным допустимым набором; index-only unknown-status и NULL-key controls отдельно, без ложной защиты SQL three-valued CHECK. Expired offered удерживает slot, same-row offered→accepted сохраняет, terminal release commit/rollback проверяются. Account-delete23514 — только SQL-shape compatibility model, не accountAPI test. Все operational/runtime/admission blockers остаются.

## Первый реальный PG: сохранённый отказ

Tests `204e685`, 46 collected/static PASS. Author PG завершился **250 PASS, 1 FAIL**, exit 1, 12.46 с (`/tmp/taxi-slot-invariant-pg.log` / `.exit`). Unknown-status INSERT в strict fixture нарушает одновременно status CHECK и shape CHECK; PostgreSQL сообщил точный `23514 strict_slot_shape`, тест ошибочно ожидал `strict_slots_status`. Это ошибка разделения тестовых предусловий, не обход ограничения и не runtime defect. Статическое независимое review этого не заметило. Исправление должно проверить status CHECK на status-only fixture с точным именем, сохранить отдельные shape и NOT NULL controls; принимать любой constraint нельзя. Исходный FAIL сохраняется, весь PG будет повторён после независимой оценки.

## Исправление и повтор

Точное test-only исправление `51d5e5b`: unknown status проверяется на status-only `slots` с `23514 slots_status`; NULL/23502, shape CHECK и valid-status matrix сохранены. Независимый diff review PASS без ослабления. Author PG2: **251 PASS**, 11.25 с, actual exit 0 (`/tmp/taxi-slot-invariant-pg2.log` / `.exit`), cleanup подтверждён. Конкурентные RC/RR INSERT/UPDATE получили ожидаемый occupancy23505/index, winner rollback позволяет второму пройти. Canonical handoff/release и NULL/account-delete shape границы проверены только на fixture. Independent PG следующий.

## Итог независимой проверки

Independent PG: **251 PASS**, 11.27 с, actual exit 0 (`/tmp/taxi-slot-invariant-independent-pg.log` / `.exit`). Каждая собственная nonce schema удалена и отсутствие подтверждено; runner удалил cluster, остаточных каталогов нет. Независимая финальная приёмка `51d5e5b` PASS без открытых P0/P1/P2. Ruff/bash/diff и secret scan PASS (0 findings). Ресурс передан Amio.

CASE UNIQUE перспективен как DB occupancy boundary: проверенные raw SQL writes не обошли конфликт на RC/RR без User lock. Это не доказательство готовности приложения: synthetic schema не application schema, CHECK несовместимость account-delete shape подтверждена, миграционный аудит/сохранность реальных назначений/ошибки старых binaries/lock graph/admission не решены. Runtime и application DDL неизменны, новый FULL не запускался. 46 новых PG cases и весь прежний PG regression прошли отдельно; исходный250PASS1FAIL остаётся в истории.
