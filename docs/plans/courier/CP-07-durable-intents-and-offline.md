# CP-07 — Повторы и восстановление без дублей

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | миграция и retention intent; права на хранение локальных данных |
| Зависит от | CP-02/05; фактически реализованный RS-05 общий receipt/tombstone либо отдельный согласованный predecessor |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 01–08 |
| Зона файлов | backend/app/routers/{courier, parcels}.py, models.py, account.py, cleanup.py; Android data/ApiClient.kt и новый data/CourierIntentStore.kt; PWA utils/outbox.ts/outboxStore.ts, api/{courier, parcels}.ts |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Нестабильная связь не создаёт два заказа, два принятия, два возврата или два платежа. После перезапуска видно что действительно произошло.

## 2. Что уже есть в коде

courier.py:1338–1371 semantic dedup60 сек; parcels.py claim CAS уже есть. PWA outbox courier commands не содержит; wrappers не передают поддерживаемый client Idempotency-Key. Android one-shot текущие helpers ограничены rideshare.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Распространить общий durable command contract C3/C8 на create/accept/transitions/proof/return/dispute/payment intent. Сохранить смысл команды через process death без слепого повторения.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Receipt+domain change+outbox atomic; canonical fingerprint всех effect-relevant полей по RS-05, без сохранения raw secrets в receipt/log.
2. GET result actor-bound: applied/rejected/closed_no_effect/unseen; pending/unknown только локальный transport UI, не пятый серверный исход.
3. Duplicate same request restores outcome; key reuse different order/body409.
4. Unknown external payment идёт reconciliation, не запускается повторно.
5. Replay заново применяет role/phase DTO; удаление аккаунта/retention не восстанавливает старые права.

### B Android

Persist minimal intent с account/order/generation; process-death/reboot recovery. Offline proof pending, не green delivered. Logout изолирует очередь, поздний callback не переносится другому пользователю.

### C PWA

IndexedDB bounded durable queue, cross-tab ownership lock, corruption recovery и unknown intent banner. Не удалять offer/card при любой ошибке accept. Сохранить ввод при arrived/goods/return error.

### Межмодульный договор R2

Нормативный RS-05 C3: GET `/command-intents/{key}` с applied|rejected|closed_no_effect|unseen; POST `/command-intents/{key}/close` с scope/target_id/payload_hash, atomic close под тем же intent lock, что apply. Если после restart нет точного body, unseen нельзя replay: atomic close даёт безопасный no-effect либо applied результат гонки. Logout удаляет secrets/PII, но сохраняет bounded non-secret unresolved intent metadata account-bound; не auto-close неизвестный расход и не передаёт очередь другому аккаунту. K6 tombstone сохраняет запрет повторного эффекта после account deletion по утверждённому сроку; права чтения/мутации отозваны.

### Уточнение R3

Service workers используют зарегистрированный service principal/anchor namespace K6 и общий C3 receipt, не user impersonation/второй ledger. Public GET/close не принимает чужой service actor; machine reconcile авторизуется внутренним grant. External unknown не закрывается одной close команды.

### API и события

C3/C8; общий GET /command-intents/{key} и POST /command-intents/{key}/close. idempotency_key_reused409, expired key только GET/close; transport timeout не business reject. Никакого courier-only pending202 receipt protocol.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M5 reuse RS CommandReceipt + CommandActorAnchor K6, unique actor_anchor+key; старый user ON DELETE CASCADE заменяется согласованной shared migration на SET NULL association/RESTRICT receipt. Retention tombstone отдельно от PII payload; outbox event+consumer unique. Legacy twins не получают выдуманных исходных request keys.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Проверяем, прошла ли команда». «Не отправляй заново: результат уточняется». «Не удалось сохранить действие на устройстве» с безопасным выходом.

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Queued/sending/unknown (local), applied/rejected/closed_no_effect/unseen (server), conflict, storage unavailable/corrupt, login changed, expired lease with known nonaccept.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

C9 задаёт связанный ETA-договор: правильная target stop/leg, отдельные promised/current/actual и elapsed/paid wait; route/ready/handoff изменения инвалидируют старый прогноз, offline/timezone не омолаживают данные. Приёмка SIM-37/ETA-N01…22 применима к затронутому сценарию, без раскрытия чужих остановок.

R4.1 A-R4-N04: R4B в 11 регистрирует request/proposal scope/target/ref_kind/safe current; order_id nullable, actor/key/hash обязательны. Lost response обоих pre-order действий → restart → GET/atomic close; raw private context не пишется в receipt, чужой actor не читает его, applied+result_gone не становится unseen.

## 7. Тесты

R3-WORKER: restart одного job key один effect; revoked grant denied; external user не spoof principal; credential rotation не создаёт новую payment identity.

R2-INTENT: unseen → late original против close в PG, один applied или closed_no_effect; lost body → close not blind new key; logout/account delete/recreate не стирает effect tombstone и не даёт old actor доступ.

SIM-01/03/12/15; управляемый сбой до commit и после proven commit, parallel tabs, replay after TTL/restart/logout, stale cleanup generation, disk full. Mutation: new key after timeout — red.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Для каждой write command есть owner/key/state/version policy и тест двух границ commit. Кнопка repeat никогда не изменяет payload без нового согласия.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Нельзя заменить durable idempotency busy=true/60sec dedup/одним executor. Не хранить OTP/секрет приглашения в обычном outbox и не считать transport completion фактом передачи.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
