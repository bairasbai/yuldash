# Durable поиск: all-writer contract и граница cutover

Статус: проектирование, НЕ runtime approval. База local d60e5c6 / remote fcee477e, предыдущие доказательства в `taxi-resume-transaction-composition-20261118.md` и `taxi-generation-migration-rehearsal-20261118.md`. Никакой production миграции, изменения удаления аккаунта, нового 409 или обязательного client token в этом пакете.

## Обещание и разные границы

Выбор Байраса: после удаления аккаунта получателя предложения автоматически продолжать поиск с восстановлением после сбоя. Первый результат offered не завершает обязательство: provenance сохраняется через offered и переносится при следующем advance. Accepted или terminal завершают эту цепочку; новый start/reassign после терминального завершения не наследует её автоматически без отдельного решения.

Точное представление generation решает потерю точности и конечный BIGINT предел в поддержанном диапазоне, но НЕ различает исторически повторённое значение и НЕ авторизует запрос. Прототип имеет конечный 128-digit budget, его отказ нельзя молча переносить в account API. Даже после server drain старый tokenless HTTP запрос того же водителя к тому же заказу может действовать на новый offer. Это отдельная совместимость клиентов, не свойство SQL counter.

## Обязательный неделимый runtime пакет перед подключением

1. Оба stored generation поля и все reader/writer binds переводятся согласованно. SQLite TEXT только equality: никакого прежнего SQL coalesce+1, >=, <, MAX/MIN или сортировки как числа. Python exact increment только после fresh writer lock; SQLite нуждается в настоящем writer lock до чтения. Старые binaries после расширения не допускаются.
2. Удаление аккаунта: действующий HTTP User lock и fresh guard сохраняются. Все affected foreign offers проверяются и отзываются вместе с полным intent в одной account transaction. Own orders удаляются прежним FK-safe путём. Вложенный commit, Redis/GEO/push внутри этой транзакции недопустимы. Direct caller contract отдельный. Noncooperative matcher и deadlock обеих жертв требуют реального теста, не обещания delete200 при любой гонке.
3. Claim: отдельный атомарный cooldown commit с полным exact owner+pending CAS, время после lock, без network под lock. Это не lease: дублирующая подготовка допустима. Fresh downstream CAS не должен adopt новую generation из ORM. Outcome и retain/transfer/clear obligation фиксируются одной транзакцией; loser без Redis/push/clear.
4. Offered сохраняет obligation; decline, expiry и lazy GET reconcile переносят её при searching. Route/waypoints/fallback/start/reassign при существующем pending также переносят по заранее принятому правилу cooldown/attempts. Нельзя обновить revision и оставить intent прежней generation.
5. Accepted/terminal и физическое удаление owner прекращают pending атомарно. Worker timeout не продлевается claim: offered_at/searching_at не изменяются при cooldown. Concurrent terminal vs outcome проверяется exact CAS. No-driver outcome сохраняет существующие admission/round/wait правила, не обещает поиск без лимитов.
6. Scanner/index/fairness/backoff, malformed tuple/negative history, raw SQL validation, GC и all-writer coverage остаются обязательными до enable. Неподключённые SQL-history labs не активируются по умолчанию этим проектом.

## Cutover: технические этапы и стоп-условия

- До выполнения: отдельное разрешение на реальную схему и окно; аудит actual stored values и deployment entrypoints, без предположения что intent сейчас везде NULL. Здесь реальная БД не читается.
- Остановить admission ownership writes/consumer processes и доказать завершение транзакций; остановить рестарт старых image/cron/operator scripts. Флаг нового binary не блокирует старый writer.
- Отдельно классифицировать persisted queues, snapshots, retry scripts, restores. Drain процессов не отзывает сохранённые owner snapshots. Не удалять очереди/Redis наугад. Equal-value replay из rehearsal остаётся явным отрицательным контролем.
- В migration preflight lock до validation обеих колонок и до DDL; сохранить actual application FK/index/CHECK, не только mini-schema. Проверить стоимости блокировки/сканирования, backup/restore и дальнейшую совместимость pending. Mini-schema rehearsal не даёт этих доказательств.
- Атомарный переход code/schema с проверкой всех writer paths и точных readback. Двоичный rollback запрещён после несовместимых значений/pending; downgrade только после явной проверки обеих колонок и semantics, без reset/clamp/потери обязательств.
- Не заявлять глобальную stale-client защиту. Tokenless compatibility требует отдельного API/client rollout; текущий accept вообще не имеет token input, decline имеет optional expected_offer_token. Обязательность не вводится этим пакетом.

## Следующий допустимый срез

Сначала actual-code test-only диагностика replay: сохранить запрос offer A, легитимно получить offer B для того же order/driver, выполнить старый запрос через реальный auth/endpoint и независимо прочитать SQL. Отдельно token-aware decline old token vs tokenless decline; accept проверять по его фактическому контракту, не придумывать поддержку token. Никакого изменения поведения endpoint, искусственного HTTP статуса или подмены ownership CAS. Тест PASS означает наблюдение известной границы, а не исправление.

Source audit всех writer locations и независимый review плана ещё выполняются. После их результата план уточняется до реализации. Никакого подключения claim или producer в промежуточном срезе.

## Source inventory на d60e5c6

Независимый read-only аудит: production вообще не использует `matching_resume_*`, кроме объявления model. `instant_service.py`: start_matching (3542, Order lock/commit затем matcher); try_offer_next (3354, NULL-init отдельный commit и offered CAS/commit с precommit Redis); advance_after_no_accept (3579, commit до matcher); reconcile_offer (3615, fresh lock→advance); decline_offer (3628, отдельный analytics commit до advance). Эти ветви требуют retain/transfer в той же owner transaction, не после неё.

Route `_invalidate_route_search` (1920) через apply_destination/apply_waypoints и add_fallback_category (2323) должны переносить pending одновременно с реальным bump. Terminal `_expire_no_drivers` (3256), transition (2743), cancel_order (2850), finish_early (2150), worker close_stuck_orders (98) и finish_expired_waits (224) должны очищать pending в своей транзакции. Worker retry_waiting_orders (173) и advance_stale_offers (279) делегируют ownership services; отдельный retry_count commit не является transfer. Account foreign recipient UPDATE (~658) и общий commit (~801) должны быть заменены атомарным отзывом + intent только в полном runtime пакете.

Transfer proposal для существующего корректного intent: новый revision в обоих полях, attempts сохранить, next_at=min(old_next_at, post-lock clock). Неверное соответствие owner/pending не исправлять adoption. Cancel terminal clear затем reassign без intent не наследует обязательство автоматически; решение о новой цепочке отдельно. User→Order в account/accept не разрешает добавлять User lock в существующие Order-first paths.

Второй безопасный actual-code diagnostic перед wiring: seeded валидный pending на обычной app fixture, настоящий start/advance/route/fallback/terminal service, fresh SQL readback. Текущий expected known-gap: generation меняется без transfer либо terminal сохраняет pending. Подмена только разрешённых внешних boundary, не SQL/commit/generation helper. Не выдавать seed за существующий production producer. Точные ветви выбираются после независимой оценки; MAX+1 на настоящей BIGINT модели не заявляется.

### Уточнённый delayed-request diagnostic

Source-only исследование: старый непустой token в decline при B ожидается как HTTP 200/no-op, не ошибка. Accept не имеет body/token параметра; передачу JSON с token нельзя называть поддерживаемым контрактом.

Legitimate A→B: настоящий online/presence/create и matcher выдают A; запрос A сохраняется до изменений. Настоящий token-aware decline A оставляет единственного D в tried и приводит к expired через настоящие candidates/cleanup. Passenger `/wait`, контролируемые часы за retry interval, но внутри wait_until, обновление presence настоящим endpoint, затем настоящий retry_waiting_orders дают B тому же D. Fresh SQL должен подтвердить token B != A, возросший revision, актуальный deadline. Никаких прямых UPDATE статуса/токена или mocked candidates.

Отдельные cases: old-token decline 200/no-op без новой OfferDecline; tokenless decline меняет B; tokenless accept принимает B. Возможный дополнительный accept с old-token JSON фиксирует фактическое отсутствие binding, не API обещание. Это заранее подготовленный отложенный запрос, не реальная сетевая задержка. Разрешённые подмены: fakeredis, transport boundaries, явные часы; auth/guards/handlers/commit/cleanup/selection настоящие. Результат ещё не воспроизведён; independent pre-code review ожидается.

## Разрешение test-only и авторский freeze

Journal PASS обоим направлениям, не runtime или cutover. Обязательны реальный lifecycle/SQL/commit, synthetic pending обозначен, итоговый status/revision/pending без предположения промежуточного offered. Старый decline token ожидается 200/no-op. Равный stale replay, отрицательная история, конечный adapter budget и tokenless клиенты остаются отдельными блокерами.

Авторский replay freeze a379b04: 3 PASS, exit 0, 2.12 с (`/tmp/taxi-tokenless-author2.log`, `.exit`). Первый run сохранён: 3 setup ERROR (`/tmp/taxi-tokenless-author1.log`, `.exit`), transport spy не принимал пятый positional data настоящего push_bilingual. Исправлена только сигнатура spy, не приложение. A→B получен через настоящий matcher/decline/expired/wait/retry; old-token decline no-op, tokenless decline и accept действуют на B. Accept с дополнительным old-token JSON проверен отдельно как неподдерживаемое/игнорируемое тело.

Авторский pending freeze 1cb26a8: 8 PASS, exit 0, 1.73 с (`/tmp/taxi-pending-writers-GU5lKt.log`). Реальные start/advance/destination/searching fallback заканчиваются expired с revision42 и pending41; offered fallback сохраняет offer, но bump создаёт mismatch, duplicate сохраняет полный snapshot. Accepted/cancel/timeout оставляют synthetic pending. Это наблюдаемые пробелы, НЕ исправление и НЕ покрытие всех writer paths.

060d47a добавляет только allowlisted `durable-writer-known-gaps` mode для этих двух файлов с обычным conftest. Guard до imports/DDL и default255 неизменны; unknown/path/DSN/extra arguments отвергаются exit64 до bootstrap. Авторский static PASS. Независимая scoped/PG приёмка впереди, публикации этого пакета ещё нет.

Независимый expanded scoped: 45 PASS, exit 0, 6.52 с, `/tmp/taxi-all-writer-gaps-independent-scoped.log`. Static подтвердил boundaries и runner, но выявил P2 cleanup pending fixture: собственные persisted Notification rows не убирались и fakeredis не закрывался. Обычный pytest teardown после yield выполняется и при failure; отсутствие try вокруг yield само по себе не доказывает утечку order. Исправляется только cleanup own refs и закрытие Redis в finally без подавления cleanup errors. Это ещё не финальная приёмка.

Cleanup correction 038ade5: собственные Notification/OfferDecline refs удаляются до order, Redis закрывается в finally даже при SQL cleanup exception; ошибки не скрываются. Авторский повтор 8 PASS, 2.05 с, exit0 (`/tmp/taxi-pending-cleanup-ijFsJH.log`), независимый focused 8 PASS, 1.74 с, exit0 (`/tmp/taxi-pending-cleanup-independent.log`). P2 закрыт, открытых P0/P1/P2 перед PG нет.

Author actual disposable PG `durable-writer-known-gaps`: **11 PASS**, 3.46 с, actual exit0, `/tmp/taxi-all-writer-gaps-author-pg1.log` и `.exit`. Cleanup завершён; independent PG и unchanged default regression выполняются отдельно. Новый FULL не требуется/не заявляется для test-only пакета, app/model/migration не изменены.

## Финальная независимая приёмка test-only пакета

На тестовом freeze 038ade5: независимый actual PG **11 PASS**, 3.54 с, exit0 (`/tmp/taxi-all-writer-gaps-independent-pg.log`, `.exit`); unchanged default regression **255 PASS**, 13.22 с, exit0 (`/tmp/taxi-all-writer-gaps-independent-regression.log`, `.exit`). Каждый режим запускался в отдельном fresh disposable cluster; после каждого cleanup пуст. Final reviewer PASS, открытых P0/P1/P2 нет. Expanded SQLite 45 PASS и focused cleanup 8 PASS приведены выше. Secret scan: 0 findings. Ресурс передан Amio после фактического exit.

Публикуется только диагностический пакет с планом. App/model/migration/runtime не менялись, нового FULL нет. Эти PASS доказывают существующие pending/tokenless ограничения, не их исправление; durable account continuation и cutover не готовы. Заявленный all-writer inventory не равен тестовому покрытию всех ветвей. Прежние паузы и production ограничения сохраняются.
