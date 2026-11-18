# SQL history initialization и будущая интеграция: узкий первый цикл

База330f07e. План до независимого review. Нынешние matcher/reassign/GC/write helpers не подключать. Никаких flag/scheduler/production migrations. Цель: определить границы интеграции и реализовать только допустимый uncalled first-cycle initialization lab.

## Почему не общий rotate/reset helper сейчас

Активный legacy searching/offered нельзя eager-init: Redis содержит историю, которая не восстанавливает committed outcome. Tokenized Redis lifecycle не означает SQL mode. После GC старые membership rows исчезают, поэтому их отсутствие не доказывает, что lifecycle никогда не использовался. UUID даёт практическую уникальность, не абсолютное доказательство never-reuse. General restart/reassign init требует отдельного протокола свежести lifecycle и всех writers; не прятать его за allow_reset.

## Предложенный безопасный первый срез

Отдельный uncalled helper begin_first_sql_search(existing PG/psycopg2 Connection, order_id, expected_revision). Та же active transaction/actualautocommitFalse/ownSAVEPOINT discipline, что write primitive. Не создавать outer transaction/commit/rollback. Input IDs/revision validate до SQL; expected_revision допускает None или0..MAXBIGINT с точным None comparison (не coalesce/adopt).

Fresh owner SELECT FOR UPDATE. Допуск **только status=created, version IS NULL, lifecycle IS NULL, expiry IS NULL**, exact expected revision, отсутствие любых membership rows этого order. Иначе typed OwnershipMismatch без изменений. Даже пустой searching/expired/scheduled/cancelled owner отвергается; этот lab не upgrade старых заказов. No Redis read/import/delete.

Под lock сгенерировать UUID4 hex внутри функции (caller не передаёт token), получить один clock_timestamp после lock. Один conditional UPDATE атомарно ставит status=searching, revision=None→1 либо n→n+1, searching_at=clock, search_round=0, version1/lifecycle/expiry=clock+3600. MAX/negative revision failclosed до изменений; остальные price/route/fee/driver поля не менять. Return frozen id/revision/lifecycle/expiry/searching_at; APPLIED не означает COMMITTED. Генерация уникальна вероятностно, а отсутствие prior history не доказывает прошлое удалённого state: поддержанный caller создаёт новый order, не реанимирует/переписывает старый. Не назвать этот guard универсальным доказательством первого цикла.

Caller новойorder transaction может вызвать initializer с атомарным переходом searching, затем write primitive/offer publication на том же Connection; один commit. Не вызывать нынешний start_matching внутри этого lab: он сам commit и выбирает lifecycle без учета historyversion. Пример composition будет только в tests, не integration.

## План будущего matcher/reassign integration (НЕ реализация)

1. Полный audit всех writers: start_matching/neworder/scheduledactivation/retryworker/lazyreconcile, advance/decline/timeout, route/waypoints/fallback, accept/cancel/expiry, drivercancel/reassign, accountdelete и GC. Owner-first lock до child mutations, scalar expected snapshot и no stale ORM writeback.
2. Per-cycle mode выбирается только на разрешённой новойгранице после обновления fleet; NULL legacy работает прежнимRedisпутём, active legacy не конвертируется. Existing SQLversion honored приflagoff, unknownversionfailclosed; binaryrollback запрещён при SQLcycles. Нужен общий admission gate/drain oldworkers, не одинHTTPfeatureflag.
3. Matcher SQLmode читает SQL exclusions/общийexpiry (безRedisunion), rechecks послеownerlock; offeredUPDATE + write primitive в одном outertransaction, fresh frozenpushsnapshot доcommit, dispatch послеcommit с existingguard. Никакого RedisSADD какauthoritativeexclusion. ExternalGEO/eligibility вне долгогоlock, но кандидат можетустареть и требуетrecheck.
4. Reassign cancelled→searching: новый уникальныйcycle/version/expiry + revision increment + exclusion бросившего в SAMEtransaction. Нынешний postcommit RedisSADD нельзя механически заменить отдельным helpercommit. Fresh cycle cannotreuse historicalnamespace; стратегия ещё требует решения. Suspension/fee/notification/promo правила сохраняются. Failure → rollback wholebusinessunit, no partialreassign.
5. searching→searching retry сохраняетcycle/mode/history; expired restart новаяcycle появномуcontract. Advance/route/fallback не сбрасываютисторию. SQLexpiry resetALL передrenew evenafterpartialGC. Duplicate renewssharedset. Old-cyclecleanup не удаляетnewcycle.
6. Scheduler/GCdiscovery/budget/monitoring и долгоживущийparent retention остаютсяactivation blockers; primitive tests не разрешают rollout. Commit-success неdelivery; crashbeforepush/latepush/clientreconciliation остаются.

## Матрица первого lab

Unit invalidConnection/autocommit/noactive/IDs/revision reject, unknownstates/membership guard. PGcreated чистыйinitializer атомарно изменяет только status/revision/searching_at/round/history fields, сохраняетвсеotherfields, nohistoryinsert, repeatedcallreject no rotation; every legacy/SQL/NULLpartial field case failclosed; twoinitializers realownerlock one success one mismatch; initializedstate invisiblebeforecallercommit и rollback восстанавливаетNULL.

Integration model tests SAMEConnection: createorder→atomicinit/searchingrevision→offeredUPDATE→remember_tried_history, actualcallercommit/rollback и actualdeferred23503 (ownTEMPparent/child) исходы; modeledlostACKafteractualcommit отдельно. Failure aftermetadataUPDATE savepointrestores, unrelatedcallerwrites остаются pending/notcommitted; caller обязан outerrollbackunit. RealGC competition afterinit: futurecurrent не удаляется, GC не видитuncommittedinit доlockrelease. No productionauth/eligibility/routing assurance от этой модели.

Независимое review до кода, authorunit/PG и independentPG после согласования Amio, FULL и отдельнаяветка толькоприPASS. Общийrotate/reassigninit и production wiring НЕ входят в этот пакет.

## Зафиксированный контракт после review

Journal выбрал атомарный created→searching lab, metadata-only вариант отвергнут; родитель подтвердил. Pristine created требует driver_id/current_offer_driver_id/offer_token/offer_expires_at всеNULL, search_round=0; противоречивое состояние failclosed, не repair. version/L/expiryNULL, nohistory и exact revision сохраняются. Known nonnegative revision доMAX-1; MAX/negative owner безeffects. Input expected_revision None или неотрицательныйBIGINT, MAX может быть передан для явного owner overflow refusal. Failure typed OwnershipMismatch, собственный savepointrollback, callerouterrollbackmandatory. UUID4 practicallyunique, не доказательство neverreuse; новыйorder — callerprecondition. Не обходить production suspension/eligibility/admission/discount/notification rules: helper не подключён и не заменяет start_matching. Scheduled/expired/reassign/generalrotate остаются вне scope.

## Кандидат и адресные проверки

Helper98039b4 и tests6d0a076: author14 unit PASS/43 PG skips0.57с; independent71 PASS/43 skips2.63с. Ruff/bash/diff/secrets PASS, 0 findings; app references только definition, интеграции нет. Author realPG121 PASS5.78с, actualexit0, /tmp/taxi-history-init-pg.log; собственный кластер очищен. Независимый PG и FULL ожидаются. Это Core-model composition, не production matcher/admission proof.

Independent realPG121 PASS5.85с, actualexit0, /tmp/taxi-history-init-independent-pg.log; cleanup подтверждён. Финальная независимая приёмка tests6d0a076/helper98039b4 PASS, P0/P1/P2 нет. Общий restart/reassign, production admission, strict never-reuse и rollout не доказаны; FULL следующий gate.

## Финальная локальная приёмка

FULL92e586e: **7418 PASS, 240 skipped, 815 warnings**, actual exit0,705.72с. /tmp/taxi-history-init-full.log и .exit; ресурс передан Amio через followup. AuthorPG121 PASS5.78с, independent121 PASS5.85с, scoped independent71 PASS/43skip. Open P0/P1/P2 нет, Ruff/bash/diff/secrets PASS.

Это неподключённый pristine first-cycle lab. Core composition тесты не доказывают production admission/matcher/reassign; общий restart/rotation и strict never-reuse/rollout остаются отдельными blockers. Ни реальная БД, ни main/production/paused операции не затронуты. Applied snapshot не committed результат; caller transaction ownership сохраняется.
