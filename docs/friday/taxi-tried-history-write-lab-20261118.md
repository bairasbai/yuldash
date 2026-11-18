# Atomic reset/add/renew: неподключённый transaction primitive

База466ef0b. Только план до независимого review. SQL history не активирована. Никаких matcher/worker/router/writer wiring, scheduler/flags/миграций. GC lab остаётся неподключённым. Предыдущие paused operations не трогать.

## Почему другая граница транзакции

GC может иметь собственную транзакцию. Запись exclusion должна разделять commit/rollback с eventual offered UPDATE либо reassign: отдельный Engine.begin был бы ошибкой. Предлагается отдельный uncalled helper, принимающий **уже активную PostgreSQL Connection transaction**, не Session/Engine. Не commit/rollback caller. Не читать ORM identity map, не autoflush чужих pending writes. Caller обязан rollback всей бизнес-транзакции при любой ошибке/mismatch, не catch-and-commit частичный offer.

Минимальный первый срез работает только с уже инициализированным version1 owner и nonNULL expiry. NULL version/lifecycle/expiry не инициализируются. New-cycle init/mode selection/reassign wiring отдельно; это сохраняет fail-closed неизвестного состояния, не молчаливое adoption.

## Предлагаемый контракт

Input connection, order_id, expected_revision, expected_lifecycle, expected_status, driver_id. Проверить конкретные int (не bool), диапазон revision0..MAXBIGINT, positiveIDs, valid32lowerhex, known status searching/offered; PostgreSQL только, active transaction обязательна, autocommit reject по фактическому driver state (не только SQLAlchemy logical transaction). Поддержанный driver явно ограничить проверяемым psycopg2, если общей надёжной проверки нет. Не создавать transaction/connection самостоятельно.

1. Fresh scalar owner SELECT FOR UPDATE; exact id/status/revision/lifecycle/version1 + nonNULL naiveUTC expiry. Missing/mismatch/invalid → явный отказ/False без history/expiry mutations. Не adopt новое состояние; caller после отказа не публикует offer. Status searching нужен доoffer/reassign, offered возможен после своего успешногоUPDATE; никакие другие targets не разрешать.
2. clock_timestamp после lock, UTCnaive. Если expiry<=clock, удалить **все current lifecycle members**, не batch500; иначе сохранить весь current set. Старые lifecycle не трогать (их GC отдельный), otherorder не трогать. Это atomic full reset-before-renew, а не cap/prune.
3. Idempotent INSERT current driver ON CONFLICT compositePK DO NOTHING. Duplicate всё равно renew общий срок набора. UPDATE shared expiry = max(previous future expiry, clock+3600s), чтобы поздняя операция/clockback не сокращала более свежий срок. Если previous expiry expired, новый срок clock+3600. Возврат applied без commit: это не утверждение публикации/delivery.
4. Для исключения partial primitive writes при Python/DB exception можно локальный savepoint внутри существующей caller transaction, но он не заменяет обязательный rollback бизнес-транзакции. Принять точный failure API до кода; не проглатывать errors. Caller controls commit/rollback; no retries при uncertain commit.

## Все конкуренты и будущий rollout (не реализуются)

- matcher successful offered UPDATE и exclusion должны быть одной transaction под ownerlock; lookup может быть stale, после lock нужны fresh ownership/history recheck. CAS loser не пишет history/push.
- start_matching searching→searching resets round, сохраняет lifecycle; новаяcycle выбираетmode/L/expiry атомарно, не по глобальному флагу midcycle.
- advance/retry/lazy reconcile, route/waypoints/fallback сохраняют историю/общийexpiry, ownerlock и revision guards; не read-modify-write ORM stale scalar.
- driver cancel→reassign должен создать новый unique lifecycle и exclusion отменившего в одной transaction, а не postcommit side-effect. Это committed exclusion, не обязательно новый offer.
- terminal accept/cancel/expire cleanup не удаляет свежуюcycle; GC берёт тотже owner-first lock. Истёкшаяcycle перед renewal очищается целиком даже после partialGC. Lifecycle neverreuse.
- old binaries не знают SQL mode/history и несовместимы с активнымprotocol; defaultoff не достаточен при mixedfleet. Нужны drain/update всех workers и lazyHTTP writers, per-cycle version mode, rollback policy. Никакого Redisunion: он возвращает false rollback exclusions.
- unknownversion/NULLlegacy failclosed этим primitive, прежнийRedis runtime unchanged. Нет schema migration existingDB.

## Матрица проверки

Unit rejects Engine/Session/inactive Connection/autocommit/bool/out-of-range/invalidtoken beforewrite. PG validfuture add preservesmembers, duplicate renewswholeexpiry, expired resetALL+add послеpartialGC, othercycle/owner unaffected, mismatchzeroeffects и owner fields кромеexpiry неизменны. Exactexpiry<= отдельно controlledclock test явно отделить отactualclock.

Настоящая единая PGtransaction с orderUPDATE+primitive: rollback откатываетоба; deferredCOMMIT23503 откатываетоба; actualcommit→syntheticexception сохраняетоба, callerrollback не стирает (не networktimeoutclaim). Fault afterreset/insert/expiryupdate: нет partial primitive changes послеsavepoint rollback, callertransaction не коммититсяhelper.

Concurrency: twoadd/duplicaterenew ownerlock serialized, actualblocking_pids/distinctPID, staleexpectedrevision/L не пишет послеwait; clockcapturedafterwait; GC→writer и writer→GC preservingfreshset, partialexpiredGC→writerresetALL. Parentdelete contention/orphanguardexisting. Caller unrelated pending/flushed work не коммитится/helperне rollback его. No boundedlatency promise: full reset может быть большим.

Scope this slice не включает safe initialization, productionwriter atomicity, request retry/protocol, discovery/GC budget/scheduler, auth/UI/delivery. Эти blockers остаются даже при PG/FULL PASS. Независимый review затем согласованный authorPG/independentPG/FULL и отдельнаяветка.

## Зафиксированный failure API после независимого review

Journal условный PASS69cb91e. Принимаем typed OwnershipMismatch для missing/mismatch/invalid owner, **не False**; input validation errors до SQL. begin_nested SAVEPOINT перед owner SELECT и мутациями. Success RELEASE savepoint, без outer commit: owner lock остаётся до caller завершения. Error rollback только own savepoint и re-raise DB/Python exception; caller обязан outer rollback business unit. После mismatch savepoint lock мог освободиться, не использовать старую ownership. Это дисциплина будущего caller, не гарантия против catch-and-commit misuse.

Scope driver только PostgreSQL psycopg2; actual raw DBAPI .autocommit должен быть False, active caller Connection transaction обязательна. Проверено локально SQLAlchemy2.0.58/psycopg2.9.13: dialect.detect_autocommit_setting читает actual .autocommit. Unknown driver reject. Для expected_status=offered дополнительно fresh current_offer_driver_id == driver_id, иначе OwnershipMismatch. Searching допускает exclusion отменившего при будущем reassign, recipient check неприменим. Это не auth/eligibility и не новый offer generation authority.

Future ORM orchestrator обязан flush до capture/составления Core unit и не оставлять pending writes тех же history/owner полей после primitive; stale identity map не исправляется скрытым expire/refresh/rollback caller Session. Unrelated writes остаются под caller ownership. max(previousExpiry,clock+3600) не сокращает срок при clock rollback, но jumps могут продлитьTTL: не заявлять точную Redis wall-clock эквивалентность. No wiring/init/activation.

## Кандидат и первый реальный PG отказ

Helper b833b0a; tests ea9685d. Author31 unit PASS/31 PG skips; independent71 PASS/31 skips2.66с, static/Ruff/bash/diff PASS. Первый authorPG78: **77 PASS, 1 FAIL**, exit1,5.44с, /tmp/taxi-history-write-pg.log. Причина точно fixture setup: PostgreSQL запрещает FK из TEMP table в постоянную instantorder, actual COMMIT проверка ещё не выполнялась. Это не runtime rollback failure и не PASS. Test-only d19fcc9 заменяет ссылку на две собственные TEMP parent/child с PRESERVE ROWS и setupcommit; missing-parent INSERT/count, actual23503 и owncleanup сохраняются. Исходный отказ не перезаписывается. Schema/runtime unchanged, повтор после независимого review.

Correction d19fcc9 independently static PASS; повтор author PG78 PASS4.88с (/tmp/taxi-history-write-pg2.log), independent78 PASS5.07с (/tmp/taxi-history-write-independent-pg.log), actualexit0 и cleanup. Deferred case теперь действительно достигает COMMIT23503, не setup error; modeled ACK остаётся отдельным. Независимая приёмка aa22f41 без P0/P1/P2. FULL следующий gate; helper success не outercommit/publication/delivery.

## Финальная локальная приёмка

FULL на e2e3111: **7404 PASS, 197 skipped, 815 warnings**, actual exit0, 696.20с. /tmp/taxi-history-write-full.log и .exit. Amio получил ресурс followup после actual exit. Author PG2 78 PASS4.88с, independent78 PASS5.07с; первый fixtureFAIL сохранён отдельно. Scoped independent71 PASS/31skip, Ruff/bash/diff/secrets PASS (0 findings). No open P0/P1/P2.

Это неподключённый primitive: caller владеет outer commit/rollback. Без initialization/SQL readers/matcher/reassign wiring/flags/scheduler/rollout, без production и main. Реальная сеть/uncertain commit не тестировались, modeled ACK прямо обозначен. Следующий этап только отдельный план cycle initialization и caller orchestration; не объявлять Redis rollback исправленным в рабочем пути.
