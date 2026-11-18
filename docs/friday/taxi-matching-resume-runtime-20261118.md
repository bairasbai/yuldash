# Durable continuation после удаления offer recipient: runtime draft

База: inactive schema local8b4002f / remote254eef1b74e005b38d5b7ef9ec0811dbac1e892b. Выбор Байраса: автоматически продолжать подбор с восстановлением после сбоя, не expired-only. Это черновик до независимой оценки, не разрешение подключения и не реализованная функция. Candidate slot constraint/SQL-history lab не активируются.

## Предлагаемая единица атомарности

Настоящий HTTP delete уже держит fresh User FOR UPDATE перед guard_can_delete. В account transaction перед удалением ссылки получить актуальные чужие offered rows в ID порядке под Order lock. Для каждого normal revision (<MAX, >=0; NULL→1) один conditional UPDATE: searching, recipient/deadline NULL, уникальный новый token для отзыва, revision+1, pending revision=newrevision, next_at=postlockUTC, attempts=0. Preserve lifecycle/round/searching_at/wait_until/route/prices/fees/promo. Собственные rows удалить прежним FK-safe путём, другие statuses не воскресить. Единственный account commit сохраняет User deletion и intent вместе. Никаких network/matching/push/вложенного commit в account path.

MAX/negative ещё отдельный pre-code выбор. Рассматривается явный failclosed409 recovery_required с rollback account transaction вместо вечного searching, молчаливого expired или reset. Это сохраняет User и прежний offer, но блокирует удаление при исключительном повреждённом/исчерпанном revision; требует независимой оценки и явного решения, не внедрять скрыто. Ни один вариант пока не называется реализованным.

## Recovery без lease

Новый worker step только explicit pending, ordinary searching/no intent не трогать. Scan due tuples в порядке next_at,id с конечной пачкой; отдельная transaction fresh Order lock, clock после lock, verify full valid tuple/status/revision/lifecycle. Claim сдвигает next_at на capped backoff и насыщает attempts, commit сохраняет pending. Это cooldown, не exclusive lease. Перед исполнением fresh scalar ownership проверяется без adoption другого поколения, затем actual matcher/GEO вне долгого row lock. Captured immutable search tuple передаётся так, чтобы refresh не захватил чужую revision. Ошибка оставляет intent до следующей попытки, а Session rollback не теряет account commit. При CAS loser запрещены clear/Redis/push.

Outcome offered/expired очищает три поля атомарно в том же winner UPDATE/commit. Crash после outcome до возврата не повторяет offer; lost commit ACK определяется fresh SQL, никакой компенсации. Дублирующая подготовка после истечения cooldown допустима, единственный winner определяется full existing CAS. Redis precommit-SADD/SQL rollback boundary остаётся: continuation не делает SQL+Redis атомарными. Отсутствующий Redis сейчас приводит к штатному expired в matcher; не менять эту существующую policy молча.

## Transfer и timeout

Все production revision writers: _bump_search_revision(route/waypoints/fallback), start_matching, advance_after_no_accept, reassign. Когда prior pending существует и результат searching, обязательство перенести на new revision; next_at=min(oldnext,postlocknow), attempts сохранить. New account deletion boundary задаёт attempts0/due now. Claims alone не меняют searching_at/offered_at/created_at и не обходят maxoffers.

Terminal cancel/accept/arriving/onboard/done/worker closure clear атомарно; own row deletion удаляет metadata. Outcome clear обязателен только победителю своего full CAS. Stale pending mismatch нельзя считать выполнением работы, если новый writer забыл transfer: это protocol violation. NULL init с malformed pending не adopt. Неподключённые SQL labs не участвуют и не вызываются.

Существующий stuck timeout сохраняет приоритет и может закрыть recovery по исходному last_move; claims не продлевают срок. Новый step расположен после stuck closure, ordinary timeout policy не меняется. No finite attempt exhaustion: capped cooldown, насыщенный счётчик; постоянный outage не даёт SLA. SKIP LOCKED/scan/index бюджет и fairness требуют отдельного доказательства, LIMIT не время выполнения.

## Обязательные проверки реального кода

Actual authenticated deletion200/oldtoken401 с committed intent безwait_until; unauth401; own orders/relatedrows cleanup; active-driver deletiongate409; revisionNULL/normal/MAX/negative. Actual deferredCOMMITrefusal rollback User/foreigntransition/intent/priorrows, zero postcommit media/presence. Account→accept и accept→account реальные waits/distinctPID; noncooperative matcher newFKreference race сохраняет rollback honesty, не обещать полныйdeadlockgraph.

Worker claim rollback, crash-equivalent afterclaimcommit, two consumers before/aftercooldown, duplicateGEO with winner-only SQL/effects, outcome commit then model lostACK, failure retry; route/fallback/start/reassign transfers во время GEO/claim; cancel/timeout не resurrection. Обычный searching безintent unchanged. Реальные helper/GEO boundary spies, не mock account/guards/CAS. Dedicated freshPG candidateCHECK только диагностическая проверка формы, не productionDDL. Author+independentPG, scoped и новыйFULL передпубликацией.

## Rollout границы

Schema-before-new-code, все затронутые writers/worker согласованы; mixed old writers могут потерять transfer. Не включать production и не утверждать durable guarantee до полного rollout. Worker timer availability assumption явная. Push после SQL commit может быть потерян/задержан: durable lookup не durable delivery/exactlyonce. Bounded exact23505 handling отдельно после этого пакета.

Будущая admission оценка учитывает пользовательский сценарий быстрой передачи машины другому водителю (план docs/plans/taxi/07-fleet-vehicle-handover.md на0ec5be23): нельзя считать driver↔vehicle вечной привязкой. Здесь vehicle права/assignment не меняются, сценарий ещё не проверен и дефект не утверждается.

## Review correction: обязательство через всю offer-цепочку

Journal выявил блокер исходного draft: clear при offered обеспечивает лишь первый следующий результат; после decline commit→crash до matcher снова теряется continuation. Этот вариант НЕ реализуется. Пересмотр: три resume поля сохраняются при offered как provenance текущего opt-in search episode; worker сканирует только searching. Advance/route переносит obligation на новое поколение и делает due; offered status сам предотвращает повторное выполнение. Clear только accepted либо terminal outcome. Cancel→reassign и новый start без prior intent не получают этой гарантии: terminal завершает episode. Будущая передача через новый episode требует отдельного выбора, не скрытой автоматической активации.

## Exhaustion research, без принятия 409

409 при невозможности продолжения блокирует удаление аккаунта и потому НЕ принят как молчаливое техническое решение. Исследована точная достаточность текущего круга: r=coalesce(revision,0), k=search_round, N=maxoffers, remaining=max(N-k,0); нужно r <= MAX-(1+remaining). Один increment account revoke, затем по одному advance после каждого оставшегося offer, включая последний перед штатным maxoffers expiry. MAX-1 при remaining>0 недостаточен. Это доказательство только при неизменных лимитах и без дополнительных route/start revision-changing действий; для расширения гарантии все writers должны сохранять headroom invariant, а config изменения отдельно учтены. Invalid k/N не нормализовать скрыто.

Token-only отзыв без revisionincrement отвергнут как shortcut: отдельный stale matcher защищён полным tuple, но optionaltoken callers и intent ownership позволяют новый ABA риск, весь протокол не доказан. Не вводить revision reset. Альтернативы exceptional recovery/явной блокировки удаления ещё исследуются; user выбор automatic continuation не подменяется expired или вечным searching. Runtime пока не изменён.

Реальный deadlock User→Order удаления и Order→FKUser matcher остаётся возможным; тест должен проверить обе жертвы и whole-transaction rollback/noeffects, а не требовать произвольный SQLSTATE как PASS. Не отображать исключение в409 без отдельного exact handler.

## Более широкий generation вариант: предварительное сравнение

Исследование backend refs и Android/PWA не нашло публичного потребления search_revision: сейчас это внутренний CAS/snapshot scalar, не API number. Кандидат без сброса — exact PostgreSQL NUMERIC для обеих revision колонок, Python int/Decimal без float, со сменой всех BIGINT guards. MAX→MAX+1 перестаёт быть arithmetic overflow; отрицательные значения требуют доверенной cutover границы/drain, иначе исторический ABA от повреждения не исключён. PostgreSQL NUMERIC имеет конечный implementation limit, это не математически бесконечный counter ([официальная документация](https://www.postgresql.org/docs/17/datatype-numeric.html)). DBAPI/type conversion зависит от dialect; SQLite Numeric может терять точность, model change без exact roundtrip tests недопустим ([SQLAlchemy 2.0 types](https://docs.sqlalchemy.org/en/20/core/type_basics.html)).

Альтернатива UUID adjunct аддитивна, но требует full writer/CAS/snapshot/intent cutover и даёт практическую uniqueness, не буквальное отсутствие collision; старый revision overflow gate должен быть отдельно снят для нового протокола. Replacement order меняет идентичность/FK/promo/history и несоразмерен; tried_lifecycle rotation смешивает ownership с историей исключений и теряет её, не рекомендуется. Ни NUMERIC, ни UUID пока не выбраны/реализованы. Следующий отдельный test-only representation spike должен сравнить реальную стоимость, exactness/SQLite, CAS/rollback и mixed fleet. Production ALTER и automatic backfill/drain не разрешены.

Локальный isolated in-memory SQLite probe на установленном SQLAlchemy2.0.58/pysqlite подтвердил риск: Numeric(asdecimal=True) + Decimal input 9007199254740993 возвращает9007199254740992; 9223372036854775806,5807 и5809 все возвращаются как9223372036854775808. Это actual representation failure, не только предупреждение документации. App/models/данные не менялись. Прямая универсальная замена BigInteger→Numeric поэтому НЕ безопасна: нужен отдельный exact dialect representation/арифметика/CAS либо другой protocol. PostgreSQL exactness этим SQLite probe не доказана и не опровергнута.

## Независимые normal-path diagnostics разрешены

Journal PASS двум test-only known gaps: actual authenticated delete200/oldtoken401 с fresh SQL foreign offered/recipientNULL/oldtoken+deadline/resumeNULL; actual advance20→21 с единственной подменой downstream matcher, которая independent connection подтверждает уже committed searching и бросает ModeledCrash доlookup, затем callerrollback не стирает commit. Это наблюдение текущих дыр, не desired contract/fix и не process kill/network failure. Никаких runtime/runner/DDL/auth/guard/SQLcommit подмен. Отдельность DBAPI connection обязательна, StaticPool не считается независимым доказательством. Реализация этих diagnostics начата отдельно от непринятого runtime.

Known-gap tests frozen34156ba: author2 PASS1.27с, independent expanded31 PASS2.90с, оба exit0; отдельная физическаяDBAPI connection подтверждена. Journal разрешил dedicated matching-resume-known-gaps runner mode (только этот файл, guard beforeimports/freshcluster, normalconftest), existing default255/appcompat5 unchanged. ActualPG ещё впереди; SQLite не заменяет PG.

## Проверенный контекст vehicle handover

Прочитан pinned [handover plan0ec5be23](https://github.com/bairasbai/yuldash/blob/0ec5be23/docs/plans/taxi/07-fleet-vehicle-handover.md), blobfb8d330591bb1ac2aafe8720d384c6bbb72bd4b8. Это PLAN/NOT_IMPLEMENTED, не действующая vehicle assignment модель. План запрещает обычную передачу при accepted/arriving/onboard, требует assignment-version fence для outstanding offers/late accept и отдельно отзывает право новой работы, не login/history access. Исторический order execution snapshot не должен переезжать вслед за текущим профилем машины; planned shift не равен active assignment или online/GPS consent.

Для будущего generation/admission: durable resume не может воспринимать старую driver eligibility или автомобиль как неизменные. После восстановления нужны текущие права пары driver/vehicle/assignment и нужного класса, а поисковое поколение и assignment fencing — разные сущности. Здесь эти guards не реализуются/не тестировались; plan не доказывает дефект текущего кода. Cross-mode/account deletion/global lock graph и handover со старым pending offer остаются отдельной совместной матрицей, без переноса PII/доходов/старых чатов между водителями.
