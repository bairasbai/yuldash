# Atomic matcher finalizer: план неподключённого SQL-среза

База: reader 5d646ba, remote 5059ae5080cb27a0b2d27b2ca58f2af2498f381b. Новая отдельная ветка. Это план, не готовое исправление production matcher. До реализации нужен независимый review.

## Почему нельзя просто подключить reader

`instant_service.py:try_offer_next` проверяет passenger suspension, Redis presence/GEO, max offers, затем `candidates`/`eligible`/`rank`. `eligible` учитывает online/verified, busy offered/active, блокировки пассажира, самого пассажира, suspension/quality/rest/debt, taxi admission, зону, классы и явно разрешённый fallback, опции, women-only verification и pretrip. Эти правила нельзя заменить boolean `approved=True`, историей SQL или валидным driver ID. `busy_driver_ids` делает SELECT, а не межзаказную reservation: owner lock одного заказа не сериализует двух разных orders для одного driver. Никакой новый helper не должен обещать устранение этой гонки без общего протокола всех writers.

Существующий CAS публикации связывает status/searching_at/search_round/offer_token/revision/lifecycle. SQL reader пока связывает только status/revision/lifecycle/version. Поэтому будущий finalizer обязан отдельно связывать весь captured search tuple; нельзя принять новый round/route после stale подготовки только потому, что кто-то не изменил revision. Подготовка GEO/ranking вне долгого SQL lock, финальные authoritative проверки под согласованными locks; сетевые запросы и push не должны выполняться внутри них.

## Предлагаемый следующий допустимый срез

**Неподключённый transaction primitive**, а не production matcher или admission service. Название `publish_sql_offer_lab`; явная документация, что вызывается только в тестовой композиции. Не добавлять feature flag, endpoint, worker import, runtime dispatch. Не переносить `eligible` в Core и не дублировать изменчивую политику в этом пакете. Отсутствие production admission orchestration остаётся блокером активации, а не успешным пропуском гейта.

API: существующий active PostgreSQL psycopg2 Connection; order_id, expected_revision, expected_lifecycle, expected_searching_at, expected_search_round, expected_offer_token; driver_id; offer TTL и max offers как явные проверенные целые policy values. Положительные INTEGER IDs, nonnegative BIGINT revision, valid 32 lowercase hex lifecycle; bool не int. Naive UTC searching_at (NULL допускается только если явно выбран и протестирован контракт), previous offer token None либо valid hex. Никаких произвольных полей UPDATE от caller.

Один внешний SAVEPOINT helper вокруг всей композиции; actual DBAPI autocommit False. Fresh owner scalar FOR UPDATE, exact searching/version1/revision/lifecycle/full search tuple, valid expiry; driver_id assignment должен быть NULL, current_offer_driver_id/offer_expires_at должны быть NULL. Previous offer_token может сохраняться после decline. Неверный owner — typed OwnershipMismatch, не False/no-op. Round >= max offers — отдельная typed rejection без expired transition. Отдельная typed rejection для excluded candidate. Ошибки SQL/Python propagate, собственный savepoint rollback, caller обязан rollback всей business unit. Успех APPLIED не означает committed.

Под owner lock вызвать настоящий `inspect_tried_candidate`; True -> rejection. False не разрешает водителя, это лишь membership observation. Без сетевых вызовов между probe и write. Clock после получения lock; timestamp начала оффера выбирается один раз, expiry = timestamp + TTL, token UUID4 practical uniqueness. Conditional offered UPDATE связывает тот же owner tuple, ставит только status/offered_at/token/current_offer_driver_id/offer_expires_at/round+1. Revision не увеличивать (действующая семантика publication). Затем настоящий `remember_tried_history` для offered в той же Connection. Это обеспечивает полный reset просроченного набора перед add и shared sliding renewal; ошибка write откатывает и offered UPDATE.

Возвращать frozen **publication record**, не готовый push payload: id/driver/revision/lifecycle/token/offered_at/offer_expires_at/new round/history expiry. Record не является `_OfferSnapshot` и не принимается `_push_offer`; не создавать новый обход dispatch guard. Будущая интеграция обязана снять полноценный snapshot из SQL scalar в этой транзакции, commit, затем существующий guarded dispatch. No outer commit/rollback, no ORM autoflush/expire, no Redis, no notifications. Pending same-field ORM writes не поддерживаются: future orchestrator flush before capture и не допускает stale writeback.

## Допуск и конкурентность: отдельные блокеры

Нельзя доказать eligibility из finalizer result. Будущий orchestration сначала сохраняет весь текущий набор policy checks, затем определяет общую lock/constraint дисциплину driver reservation для matcher, accept, manual assignment, cancellation/reassign и online/admission writers. Lock одного order недостаточен; новая эксклюзивная блокировка только здесь не защищает от старых writers. Deadlock порядок owner/driver и всех связанных таблиц должен быть оценён отдельно до подключения. Финальный admission может измениться после проверки: нужна определённая точка сериализации либо честная граница, не обещание universal freshness.

SQL mode persistent per cycle, NULL legacy path не переводится автоматически; unknown version failclosed. Без Redis union/backfill/fallback. Drain/update всех writers перед activation, old-binary rollback с SQL cycles запрещён. Init только pristine created; reassign/general rotation отдельно. Scheduler/discovery/retention budget/never-reuse и all-writer lock остаются activation blockers. Crash after DB commit before dispatch оставляет committed exclusion без доставки. Lost ACK не exactly-once и не безопасный blind retry; UUID не formal never-reuse.

## Предлагаемые проверки

1. Unit validation до SQL, immutable record, fail-closed inputs, отсутствуют imports/calls из production paths.
2. Real PG composition: настоящий init -> finalizer -> write, owner/history становятся видны только вместе после outer commit; outer rollback восстанавливает searching и history. Успех не коммитит unrelated pending/flushed caller writes.
3. Excluded candidate rejected без owner/history изменений; expired history целиком reset даже после partial GC; duplicate/old lifecycle/other order не принимаются ошибочно. Expiry equality и clock-after-lock wait.
4. Same order два finalizer: один winner; stale revision/lifecycle/full search tuple, cancel/rotation during wait -> rejection, без adoption. Read committed и propagated serialization error. Round boundary/overflow и недопустимые policy ints.
5. Реальные SQL ошибки после offered UPDATE и history insert/renew вызывают savepoint rollback обоих; actual deferred constraint failure at outer COMMIT возвращает исходное состояние. Synthetic lost-ACK-after-real-commit отдельно помечена моделью, не network test.
6. Diagnostic two different orders same driver может дать две publication при отсутствии reservation protocol: это известная граница, не тест «исправленного двойного назначения». Никаких искусственных approved booleans, чтобы выдать это за production eligibility.
7. No Redis/push/commit вызовов, frozen scalar result, реальная PG изоляция/cleanup; независимый scoped и PG, затем FULL, separate branch publication после PASS.

Ресурс PG/FULL согласовывается с Amio. Реальные БД, main, production и ранее приостановленные операции не затрагиваются.

## Исследование текущего допуска

Независимое read-only исследование подтвердило storage-only границу: `routers/instant.py:752–777` на accept берёт User FOR NO KEY UPDATE перед Order transition. Поэтому owner-first lab не добавляет driver lock: общий User→Order порядок для eventual matcher должен быть отдельным решением. Synthetic fixture допуска в тесте не означает production admission. Полнота money/address push snapshot здесь не проверяется; record только SQL publication identity. Предлагаемое имя `publish_sql_offer_lab` явно отличает primitive от готового matcher.

## Принятый pre-code контракт независимого review

Journal: условный PASS storage-only, не admission. Фиксируем до кода:
- `expected_searching_at` только nonNULL naive UTC; SQLv1 init уже создаёт его. Никакой legacy NULL поддержки здесь.
- TTL и max_offers — actual int > 0, не bool; expected round actual int 0..2147483646, max_offers 1..2147483647. TTL не произвольно урезаем: datetime/timedelta overflow явно ValueError с rollback savepoint. Round+1 всегда SQL INTEGER.
- Exact fresh full tuple/version/чистые assignment поля; conditional UPDATE rowcount ровно 1, иначе OwnershipMismatch.
- `CandidateExcluded` и `OfferLimitReached` отдельные typed exceptions; они не отменяют обязательный outer rollback business unit. Общий helper SAVEPOINT включает nested reader/write, UPDATE и capture record.
- Один offer timestamp после owner lock; reader/write имеют собственные более поздние observation clocks. History expiry в record читать из реально записанного DB scalar, не вычислять от offer timestamp. Консервативный reject от probe на границе TTL допустим; нет обещания единого clock всех операций.
- Record не `_OfferSnapshot`; нет Redis/push/ORM/driver lock/fullset. Diagnostic двух orders одного driver доказывает отсутствие reservation, а не исправляет его. Serialization errors propagate без retry/False.

## Реализация в работе

Runtime `9f0380c`: только `backend/app/taxi_offer_publication_lab.py`, без production callers (поиск imports/calls в app дал только definition). Author compile/Ruff F/diff PASS; независимая оценка кода и новые тесты ещё выполняются. Ни PG, ни FULL этого пакета пока не пройдены, публикация не выполнялась. Старый FULL reader не заменяет новый gate.

## Адресные проверки

Runtime `9f0380c`, tests `9c34aad`: author scoped 95 PASS / 39 PG SKIP, 0.92 с; independent scoped 126 PASS / 39 SKIP, 1.22 с. Ruff F, bash syntax, diff-check PASS, runtime secret scan 0 findings. Независимый code/test review не выявил P0/P1/P2; это ещё не финальный FULL gate.

Author actual PG: **192 PASS**, 8.39 с, exit 0 (`/tmp/taxi-publication-pg.log` и `.exit`), временный кластер удалён. Новый suite 39 случаев проверяет настоящие lock waits, 22012 после offer/insert/renew, deferred COMMIT 23503, RR 40001, partial GC reset, caller isolation и модель lost ACK после реального commit. Два orders одному driver действительно публикуются: known-boundary diagnostic, НЕ reservation guarantee. Independent PG и FULL следующие.

Independent actual PG: **192 PASS**, 8.31 с, exit 0 (`/tmp/taxi-publication-independent-pg.log` / `.exit`), cleanup подтверждён. Финальная независимая приёмка runtime `9f0380c` / tests `9c34aad`: PASS без открытых P0/P1/P2 в заявленном scope. FULL остаётся обязательным следующим gate.

## Финальная локальная проверка

FULL на `8ac9d513d908ef1dd7adbcd4888b6454324ac6bb`: **7482 PASS, 311 SKIP, 815 warnings**, actual exit 0, 709.53 с. Логи `/tmp/taxi-publication-full.log` и `.exit`. Ресурс передан Amio после фактического exit. Авторский и независимый PG по 192 PASS, независимая приёмка без открытых P0/P1/P2. Skips не считаются PASS; новые 39 PG случаев отдельно прошли на настоящем изолированном PostgreSQL.

Итог строго storage-only: атомарность offer/history в caller transaction проверена, helper не подключён. Допуск/driver reservation/full payload/доставка не входят в доказательство. Диагностические два orders одному driver остаются допустимым для lab исходом и блокером production integration. Mixed fleet, reassign, общий порядок locks, GC activation и rollout не разрешены. Main/production/реальные БД/приостановленные операции не затронуты.
