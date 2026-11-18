# SQL history reader и будущий matcher/reassign: single-candidate lab

База688061d. До кода независимый план. Reader/matcher/reassign ещё не подключены; SQL mode не включать, production/реальную БД/main не трогать.

## Почему точечный reader

У history нет малого доказанного cardinality bound. Полный SELECT всех driver IDs в Python set переносит неограниченную историю в память; нынешний candidates(... exclude=set) нельзя механически переключить без бюджета. Первый безопасный срез — **неподключённый membership probe одного candidate** по composite PK. Это не полный подбор, не reservation и не auth/eligibility.

Предложение: inspect_tried_candidate(existing PG/psycopg2 Connection, order_id, expected_revision, expected_lifecycle, driver_id) → frozen observation(id,revision,lifecycle,driver_id,expiry,observed_at,excluded). Активная caller transaction/actualautocommitFalse, input validation до SQL, typed OwnershipMismatch. Собственный savepoint, никаких outercommit/rollback/autoflush/Session/Engine. Успех удерживает owner lock до callercommit; ошибка rollback только savepoint и rethrow, callerouterrollback обязательный business contract.

Fresh SELECT scalar owner FOR UPDATE: status строго searching, version1, exact nonnegativeBIGINT revision и valid32hex lifecycle, nonNULL naiveUTC expiry. Missing/unknown/NULL legacy/stale state → exception, **не пустая история** и не Redis fallback. Clock_timestamp после lock. excluded=True только если expiry>clock и существует точная PK(order,currentL,driver). При expiry<=clock excluded=False без DELETE/renewal. Старые lifecycle/другой order не учитываются. Reader не меняет поля или rows. Frozen observation сохраняет одно captured время, не заменяет TTL на новыйlease.

PK existence query возвращает boolean, не загружает historyset. Это ограничивает размер ответа, не гарантирует lockwait/scan latency или целый matcherbudget. No PII logging. После commit observation stale; повторноеиспользование безfreshguard запрещено. Два sequential readers могут вернуть False, если междуними не было publication; это нормально, False не резервирует водителя.

## Будущая интеграция, не код этого среза

- Existing candidates перебирает Redis GEO radii, presence, eligibility и ranking. SQL-mode tentative GEO/eligibility list получают вне длительногоownerlock, без union с Redis tried. Под ownerlock historyprobe + authoritative freshness/eligibility recheck; excludedcandidate нельзя предлагать, нужен следующий tentativecandidate с ограниченным budget. Не держать rowlock на сетевыхGEO/notifications и не бесконечно сканироватьdrivers. Точный batching/ranking/fairness план до wiring отдельно.
- Same caller connection: ownerguard→reader→conditional offeredUPDATE→remember_tried_history→capture immutable offer snapshot→outercommit→dispatchguard. Любой mismatch/failure rollback всейunit; никакого catch-and-commit и no successful push beforecommit. Snapshot не изpostcommitadoptedgeneration. ReaderFalse не заменяет suspended-passenger/driverbusy/blocklists/category/geozone/price/authorization.
- Search restart/advance/route/fallback/retry/lazyGETwriters сохраняют per-cycleSQLmode и history, точныеrevisionguards. Нельзя включить толькоmatcher, оставивoldworker илисброс lifecycle в start_matching. VersionNULL остаётсяRedis path, existingv1 honored приflagoff, unknownversionfailclosed.
- Reassign: fresh cancelled assignmentguard, newuniquecycle+revision+searching+cancelleddriver exclusion SAMEtransaction. НынешнийpostcommitRedisremember не переноситькакSQLpostcommit. First-cycleinitializer толькоcreated, не использовать его черезподменуstatus. Generalrotate/cancelledguard/fee/promo/suspension/notification semantics и lifecycle strategy остаются отдельнымпакетом.
- Mixedfleet/rollback: drain/update всех writers доactivation, persistedcyclemode, noactivelegacyconversion/backfill, oldbinaryrollback запрещён приSQLcycles. GC reset-before-renew/scheduler/discovery/retention budget остаются blockers. UUID practicallyunique не formalneverreuse. Никакой networkdelivery/exactlyonce гарантий.

## Acceptance single-candidate lab

Unit input/engine/session/inactivetxn/autocommit/unknown driver rejects; frozenresult/exact expiry boundary controlledclock отдельно отrealPG.

PG futuremember→True, futuremissing→False, expiredmember→False без удаления, oldcycle/otherorderignored; все owner mismatch/NULLinvalidfailclosed безeffects. Full before/after owner+history равны (reader readonly кромеlocks). Existingexpiry не продлевается. Caller pending/flushedunrelatedwrites не commit/autoflush/rollback. Actualautocommit rejection доSQL.

Real lockwait: writer добавляет/renewal подownerlock, readerwaits и видит freshmember; rotatesrevision/L→reader mismatch, не adoption. Clock-afterwait: expiry установлен после подтверждённогоblocking, xact_start<expiry и releaseпослеexpiry, readerFalse. Reverse: readerдержитlock, writer waits доoutercommit; дваreaderFalse noreservation boundary. GCexpiredcleanup→readerFalse, сохранениеfuturecurrent; ошибки чтения rollback savepoint и releaseownerlock, propagated, неFalse.

Test-only composition с настоящими init/write helpers: probeFalse→offeredUPDATE+remember samecommit; следующийsearchroundfixture(явноноваяrevision) probeTrue, expiredreset отдельно existingwrite tests. Deferred actualcommit failure rollbackpublication+history; lostACKexistingmodel test либо новый composition только при необходимости. No realproductionmatcherclaim.

Authorunit/PG, independentreview/PG, FULL и отдельнаяpublication послеPASS. Сначала согласовать Amio heavy slot. Runtime matcher/reassign/API не менять.

## Независимый pre-code review

Journal PASSd56fcd4 для uncalled single-candidate reader. Expiry не expected CASinput: послеwait принимаетсяfreshvalidrenewal тойжеrevision/L и возвращаетсяновыйexpiry. Membership observation относится только observed_at, не будущейпубликации: долгийcaller обязан freshrecheck либо явно принять TTLточку. REPEATABLE READ/SERIALIZABLE могут дать serializationerror вместоlateststate; propagates, не переводитьошибку вFalse/missing. Savepointrollback освобождает только locks послеsavepoint, ранееудерживаемыйcallerownerlock остаётся; тестироватьоба, не обещатьreleaseвсехlocks. NoFalseauth/reservation/driverfreeclaim. Composition только реальныеCore init/write helper вsameconnection, не statusfaking productioninitializer.

## Кандидат и адресные проверки

Helperdd3add8/tests3b19e7c. Author28unit PASS/32PGskips0.57с; independent99 PASS/32skip3.15с. Static/Ruff/bash/diff/secrets PASS (0findings). AuthorrealPG153 PASS6.59с, actualexit0, /tmp/taxi-history-reader-pg.log, cleanup подтверждён. Включены настоящие22012/40001/55P03 проверки, callerlock preservation, capturedclock, scopedmembership/read-only. НезависимыйPG иFULL ожидаются; false неreservation иSQLreader не подключён.

IndependentPG153 PASS7.13с, actualexit0, /tmp/taxi-history-reader-independent-pg.log; cleanup подтверждён. Финальная независимая приёмка tests3b19e7c/readerdd3add8 PASS, открытыхP0/P1/P2 нет. FULL следующий gate. Никаких reservation/eligibility/productionmatcher гарантий.

## Финальная локальная приёмка

FULL28bc0cd: **7446 PASS, 272 skipped, 815 warnings**, actualexit0,697.82с. /tmp/taxi-history-reader-full.log и .exit. Amio получил ресурс followup послеactualexit. AuthorPG153 PASS6.59с, independent153 PASS7.13с; scoped independent99 PASS/32skip. OpenP0/P1/P2 нет, Ruff/bash/diff/secrets PASS.

Reader неподключён; наблюдение не reservation/eligibility/driveravailability. Production matcher/reassign/all-writer mode/rollout и scheduler остаются отдельными blockers. Успех этого lab не устраняет Redis rollback в действующемpath. Main/production/реальнаяБД/pausedоперации не затронуты.
