# Удаление получателя текущего taxi offer: план отдельного runtime среза

База: local 3b9e6d6, опубликованный actual-app diagnostic 79867de19090d69f863697a3fdf5249b56a4227b. Статус: первоначальный вариант ниже НЕ ПРИНЯТ; runtime ещё не изменён. Candidate constraints не устанавливаются в приложении/миграциях. Main/production и приостановленные операции не затрагиваются.

## Подтверждённая проблема и поведенческий выбор

`delete_user_account` очищает current_offer_driver_id, оставляя чужой заказ offered. Настоящий authenticated POST /me/delete проходит с index-only, но с диагностическим strict shape CHECK получает exact23514 и откатывается. Простая замена на searching не даёт durable continuation: retry_waiting_orders обслуживает searching/expired только при будущем wait_until. advance_after_no_accept самостоятельно commit-ит и запускает matching/Redis/push, поэтому непригоден внутри account transaction.

Предлагаемый минимальный вариант: атомарно offered→expired с отзывом адресата, token и deadline. Это не прежний автоматический timeout→следующий кандидат. Пассажир сохраняет явный повтор; уже действующий wait_until сохраняет штатный worker retry. Решение требует принятия поведенческой границы до реализации. Альтернатива с обязательным автоматическим продолжением требует отдельного durable recovery протокола, не best-effort postcommit вызова.

## Предлагаемый контракт

В единственной caller account transaction, на месте прежней очистки ссылки: получить fresh scalar строки current_offer_driver_id=uid под Order FOR UPDATE в стабильном id порядке. Собственные заказы удаляются прежним путём; обработка не должна препятствовать их удалению. Для чужих offered одним UPDATE установить status=expired, expired_at=captured UTC, current_offer_driver_id=NULL, offer_expires_at=NULL, offer_token=NULL. Статус и recipient меняются одним statement, без промежуточной malformed shape. NULL revision→1; допустимый неотрицательный revision ниже BIGINT MAX→+1; MAX/negative сохраняются без overflow и не блокируют удаление. Никакого reset revision или нового lifecycle. Guard требует fresh offered/recipient; если CAS используется дополнительно к lock, exact captured tuple и rowcount обязательны.

Другие статусы только отвязать прежнюю dangling ссылку, не переводить accepted/done/cancelled в поиск. Не менять driver_id, lifecycle, round, route, price/fee/promo, searching_at или wait_until. Не вызывать matcher, _expire_no_drivers, Redis/history cleanup, push, не добавлять commit/rollback всей caller transaction. Postcommit media/storage/presence остаются существующими. Terminal status и отозванный token/recipient делают прежний offer snapshot устаревшим даже при MAX revision; это не гарантия остановки уже отправленного push.

User→Order согласуется с accept, но не доказывает весь lock graph. Проверить stable multiorder ordering, foreign keys и concurrent accept/delete: account User lock + fresh Order read, не добавлять User lock внутри Order-first helpers. Другие режимы не менять.

## Доказательная матрица до публикации

1. Настоящий authenticated /me/delete, index-only и strict candidate CHECK только в dedicated fresh disposable PG: HTTP200, old token401, чужой offered стал expired, recipient/token/deadline NULL; own orders удалены. Unauthorized401/no changes.
2. NULL, normal, MAX и negative revision; exact preserved lifecycle/wait_until/route/price/fees/promo/round. Non-offered done/cancelled unchanged status; active driver/passenger deletion gate по-прежнему409.
3. Ошибка реального PostgreSQL COMMIT после transition: User и связанные rows/foreign order восстановлены после rollback, postcommit external calls отсутствуют. Не выдавать monkeypatched exception за network/real commit failure.
4. Конкурирующие accept/delete с фактическим lock wait/distinct PID и fresh readback; никакого stale offered resurrection. Отдельная проверка stale snapshot dispatch через настоящий guard/transport boundary, не подменять guard.
5. Уже действующий wait_until сохраняется; worker continuation проверять настоящим codepath отдельно, не обещать новый recovery для отсутствующего wait_until.
6. Переписать прежний strict23514 diagnostic на успешный regression только после исправления; сохранить прежнее доказательство в отчёте. Legacy exact23505 diagnostic остаётся без подавления: bounded conflict handling отдельный следующий пакет.

Независимый pre-code review → runtime/tests freeze → scoped + dedicated actual-app PG + unchanged PG regression → independent repeat → новый FULL → verified separate branch. Cleanup собственных constraint/fixtures/cluster обязательно, без global purge. Уведомления/доставка, полный deadlock graph, cross-mode admission, mixed fleet/online constraint build остаются непроверенными; constraint не активировать.

## Решение пользователя и следующий план

Байрас выбрал вариант 2: автоматическое продолжение поиска другого водителя с надёжным восстановлением после сбоя, НЕ завершение поиска. Поэтому предложенный выше expired-only вариант не реализуется. Он сохранён как исследованная отвергнутая альтернатива, а не действующий runtime контракт. Сейчас независимо проектируется durable intent/recovery: запись в одной account transaction, восстановление после её commit без необходимости клиентского GET, fresh ownership проверка перед matching, сохранение после сбоя до публикации следующего результата. Реализация ждёт конкретного независимого review этого нового контракта. Никаких runtime/DDL изменений в этом коммите.

## Durable вариант: исследование, ещё не контракт реализации

Независимое исследование рекомендует явный persisted intent вместо переопределения offer_expires_at или глобального сканирования всех searching. Кандидат: nullable matching_resume_revision, атомарно поставленный вместе с account offered→searching и отзывом recipient/deadline/token. Продолжение сохраняет текущий round/lifecycle/searching_at и лимит, не вызывает start_matching с reset. Публикация offered/expired должна атомарно погасить соответствующий intent; clear-before-matching недопустим. Все writers, изменяющие searching revision, должны переносить актуальный intent либо завершать его с персистентным outcome, иначе route/fallback создаст новый crash-window. Простое отбрасывание mismatch намерения недостаточно.

Review требует сопоставить этот order-local вариант с отдельной durable job/lease/backoff моделью. До кода нужны явные правила двух workers, duplicate work, crash до/после claim/publish/ack, retry budget, failure recovery и MAX/negative. Не обещать повтор при MAX, пока revisionguard исключает progress; silent reset запрещён. NULL→1 допустим только в атомарном переходе. Ordinary searching без intent не затрагивается. Старые writers не знают transfer/clear, поэтому mixed fleet не получает durable гарантию: schema-first и полный согласованный writer/worker rollout остаются условиями, production не выполняется.

Уточнение фактов auth: в текущем routers/auth.py delete_me вызывает lock_authenticated_user до guard_can_delete; security._authenticated_user(locked=True) делегирует lock_refresh_user. Сам SQL lock и fresh-read семантику надо сверить, но отсутствие User lock нельзя утверждать по одному account.py. Неcooperative matcher всё ещё может создать новую ссылку после account scan; требуется отдельное реальное interleaving доказательство и честная rollback граница, не гарантия всего lock graph.

Durable lookup не равен durable доставке push. Crash после DB outcome перед network delivery остаётся отдельным outbox вопросом; exactly-once и подтверждённая доставка не заявляются. Следующий артефакт — конкретная writer matrix и выбор retry/claim representation, затем повторное независимое review. До этого только документы, никакой schema/runtime активации.

## Предлагаемый минимальный протокол без эксклюзивного lease

Для следующего review: order-local `matching_resume_revision BIGINT NULL`, `matching_resume_next_at TIMESTAMP NULL`, `matching_resume_attempts INTEGER NULL` (NULL legacy/no pending; без SQL default/backfill). Account commit атомарно создаёт owner-bound intent для отозванного offered. Worker под fresh Order lock проверяет searching/exact pending revision/валидный lifecycle и due next_at, снимает captured scalar owner, сдвигает next_at на ограниченный сверху backoff и насыщенно увеличивает attempts. Commit claim НЕ снимает intent. GEO/lookup выполняются вне row lock, matcher обязан использовать captured owner без adoption новой revision. Успешный CAS offered/expired очищает все три поля в той же transaction, terminal transitions также clear. Это persisted cooldown, НЕ lease/reservation: долгий GEO допускает вторую подготовку, winner определяется существующим SQL CAS. Никаких новых effects до CAS.

Crash before claim commit оставляет due intent; after claim commit до outcome оставляет intent до next_at; after outcome commit before return не создаёт нового offer, поскольку outcome и clear атомарны. Lost ACK проверяется fresh SQL, без компенсации. Attempts не приводит к прекращению работы, а насыщается без overflow; постоянный outage не имеет гарантированного срока восстановления. Scheduler, retry cadence и существующий stuck timeout должны быть явно проверены; не превращать recovery в бессрочное обещание.

Writer matrix: `_bump_search_revision` (route/waypoints/fallback), `start_matching`, `advance_after_no_accept`, reassign обязаны переносить уже существующее pending обязательство на новый owner, когда оставляют searching; не создавать его для обычного поиска без prior intent. Матчер offered и `_expire_no_drivers` clear в outcome UPDATE; cancel/accept/arriving/onboard/done/worker terminal clear вместе со статусом. Own order delete удаляет поля с parent. NULL matcher init не должен adopt malformed pending. Uncalled SQL-history labs не подключаются. Старый pending нельзя просто удалить на mismatch, если writer потерял transfer: это нарушение протокола/mixed-fleet blocker, а не успешная обработка.

Ещё нерешённые pre-code пункты: MAX/negative revision без silent reset и без отвергнутого terminal fallback; точный backoff/first due clock; критерий stale malformed intent и operator recovery; scan/index/retention budget; actual auth/accept/account race и FK matcher bypass. Допустим первым только аддитивный schema-only срез после отдельного review: без создания intent, worker wiring и изменения account поведения. Это подготовка, НЕ выполненное автоматическое продолжение. Полная активация не входит в schema пакет.

## Schema-only acceptance proposal

Отдельная nullable база: matching_resume_revision BIGINT, matching_resume_next_at TIMESTAMP WITHOUT TIME ZONE, matching_resume_attempts INTEGER. Python defaults None, SQL defaults отсутствуют; без index, backfill, initialization, CHECK и writer/worker wiring. Единственная следующая migration head после cn_instant_tried_history. Отсутствие index не утверждает достаточный scan budget: это будущий activation blocker. Неизвестные/некорректные значения пока только хранимые sentinel, не разрешение на выполнение.

Проверки schema-only: metadata и реальная SQLite/PG schema types/nullability/default; old-row и old-style INSERT получают три NULL; double-upgrade сохраняет sentinel без обещания drift repair; downgrade удаляет только три новых поля, сохраняя прежние rows/status/token/revision/history; offline SQL compile. Обычная HTTP сериализация с nonNULL sentinel не раскрывает resume metadata и не запускает поиск. Existing matcher/account paths не пишут поля; schema-before-new-model обязателен, old-binary structural compatibility не означает durable mixed-writer guarantee. PG только собственный disposable cluster; downgrade только собственная временная схема. FULL требуется после model change. Документальная приёмка этого proposal ещё ожидается.

Факт auth перепроверен обоими reviewers: существующий HTTP delete уже имеет User FOR UPDATE + populate_existing до guard; первоначальное замечание об отсутствии lock снято. Прямые вызовы delete_user_account требуют отдельного caller precondition. Все остальные concurrency вопросы сохраняются.

## Независимый pre-code итог

Journal review d027cfa: PASS направления persisted cooldown без exclusive lease и первого inactive schema-only среза; НЕ runtime approval. Приняты все условия: tuple all-NULL либо полностью валиден; clock после lock; saturation без exponent/datetime overflow; cooldown не fence, duplicate GEO допустим, full owner CAS winner-only effects; transfer next_at/attempts требует отдельного решения; claims не продлевают stuck timeout (_last_move_at предпочитает offered_at); MAX/negative блокируют runtime, не schema. Downgrade после будущей activation недопустим без отдельного разбора pending работы. Исследователь/author runtime и tests работают в разных файлах; только model/migration/test foundation, no account/matcher/worker wiring.

## Schema implementation и первые проверки

Runtime foundation e5aebb4: только InstantOrder nullable metadata и migration co→cn. Tests 08cd45d, readability-only formatting 2d7432c. Author scoped 28 PASS / 4 PG SKIP, 2.69 с; independent scoped 71 PASS / 4 PG SKIP, 7.28 с; Ruff F/bash/diff PASS. Новый default PG добавляет только 4 schema cases, dedicated actual-app constraint mode не меняется.

Author actual PostgreSQL: 255 PASS, 13.08 с, actual exit0; `/tmp/taxi-resume-schema-pg.log` / `.exit`. Собственные schema/cluster очищены, остаточных yuldash-revision-pg каталогов нет. Проверена реальная migration старых populated rows/child history и model create_all metadata. Independent PG и новый FULL ещё впереди; schema не реализует durable continuation, account lifecycle defect пока не исправлен.

Independent PG: 255 PASS, 12.05 с, actual exit0, `/tmp/taxi-resume-schema-independent-pg.log` / `.exit`; own-schema и cluster cleanup подтверждены. Final independent acceptance PASS без открытых P0/P1/P2. Secret scan0. Следующий обязательный gate — FULL на зафиксированном schema пакете; старый FULL не используется как доказательство новых изменений.

## FULL и границы результата

FULL на ce9d148 завершён actual exit0: **7493 PASS, 379 SKIP, 815 warnings**, 694.69 с (11:34). Логи `/tmp/taxi-resume-schema-full.log` и `.exit`. Отдельные PostgreSQL проверки выше не заменены SQLite FULL; PG skip не считается PG PASS. Ресурс после фактического окончания передан Amio.

Пакет готов к отдельной публикации только как inactive resume schema foundation. Нет account/matcher/worker runtime изменения, активации, production migration или исправления previously demonstrated account deletion shape. Выбор пользователя durable automatic continuation сохранён, но ещё требует атомарного intent producer, all-writer transfer/outcome clear, recovery consumer и доказанных interleavings. MAX/negative, видимость blocked intent, retry/stuck-timeout policy, scan budget и mixed fleet остаются условиями будущего runtime. Bounded exact unique-conflict handling — следующий отдельный пакет, не добавлен здесь.
