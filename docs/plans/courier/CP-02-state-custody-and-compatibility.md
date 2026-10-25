# CP-02 — Состояния и ответственность за вещь

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | миграция, изменение возвратов/таймеров и договорных последствий; production отдельно |
| Зависит от | CP-01; CP-21A контракт данных/прав; общий machine error foundation |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 01,02,04,07,08 |
| Зона файлов | backend/app/models.py, routers/{courier, parcels}.py, cleanup.py, taxi_worker.py; новые backend/app/courier_state.py и tests/test_courier_state_v2.py; Android/PWA DTO adapters |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Посылка не исчезает из ответственности курьера из-за таймера, отмены или перезапуска. «Вернул» означает подтверждённый возврат, а не истечение срока.

## 2. Что уже есть в коде

models.py:1922–1924 семь legacy статусов. parcels.py:1624–1655 return-done без кода отправителя; cleanup.py:674–704 timeout returning → returned; taxi_worker.py:294–346 использует accepted_at/created_at. Это статические факты, не установленная runtime авария.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Ввести независимые workflow/custody/payment/settlement/dispute/obligation оси C1; весь legacy код направить через одну transition policy. Убрать доказательство физического исхода из timer cleanup.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Реализовать таблицу C2, state_version/terms_version/last_transition_at и атомарный event.
2. Старые routes вызывают тот же command service и role checks. Map legacy status только по C1, closed_exception не masquerade delivered.
3. Timeout присваивает case/alert/next_action, но не custody resolved. Для обоих legacy путей (возраст returning → returned и in_transit → canceled) результат — nonterminal custody/escalation по C1/K1, не терминальный статус. Назначенный исполнитель/holder и занятая capacity сохраняются до подтверждённой передачи; timeout не разрешает reassignment или повторную продажу места (CP-08, R5A в 11).
4. Account deletion, admin close, refund и dispute не обходят custody; отдельное разрешённое решение с причиной.
5. Проверить все writers поиском, включая cron, admin, tests fixtures и shared taxi helpers.

### B Android

DTO явных осей; действия приходят allowed_actions. Старая подпись canceled при вещи у курьера заменяется честным «нужен возврат/помощь»; несовместимый клиент блокируется до новых сценариев.

### C PWA

Не удалять карточку по optimistic terminal. Вкладка активных включает returning/delivery_exception и cases; независимая финансовая строка после доставки.

### Межмодульный договор R2

K1 даёт per-item физическую истину и deterministic aggregate. M1 не включает новые v2 команды: K-CUTOVER требует M1…M8, безопасные legacy adapters/worker fence и CP-21B-core. CP-02A может завершить foundation без ожидания будущих feature ACL, но release barrier проходит отдельно. Canceled-with-goods/purchase/COD никогда не redispatch по одному status.

### Уточнение R3

M1 создаёт minimal DeliveryStop identity до item/proof FK; M3 только обогащает эти stable IDs адресом/окном/Leg по D2. K1 теперь включает item canceled и aggregate all-canceled/mixed; cancel_requested/unknown commitment не terminal.

### API и события

C1/C2 commands, expected_version, events pickup.confirmed/return.required/return.confirmed. 409 CUSTODY_REQUIRES_RETURN и VERSION_CONFLICT. Proposed state service единый для v2/legacy.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M1: expand state/item/assignment/assignment-item/minimal proof/transfer/event foundations. Полная матрица K5 переносит все оси; legacy terminal без proof → delivery_exception/unknown + historical legacy status, не фальшивый proof. Синтетика всех 7 статусов, canceled with courier/goods и returning past timeout. После новых custody events destructive downgrade запрещён.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Вещь у курьера. Сначала нужно согласовать возврат». «Статус уточняет поддержка». «Возврат подтверждён» только после proof.

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Доставка/возврат/спор/денежная сверка отдельно; stale command, unknown physical outcome, historical unverified, terminal with refund pending.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

R3-STOP/CANCEL: upgrade M1 без M3 имеет валидные FK; repeat projector single canceled сохраняет sender/resolved без tracking; delivered+canceled → completed_mixed.

R2-MIG: legacy accepted/in_transit/returning/canceled/terminal без proof → K5; ни fee, ни notify от backfill. Старый worker после cutover отклонён fence. SQLite/PG naive UTC roundtrip по K4, старые taxi сравнения не получают aware datetime.

**REV-CUS-01 → T-CUS-01, SIM-05/15/30/31:** для каждого из двух timer writers проверить заказ до/после порога, повторный tick, рестарт, concurrent proof и попытку нового назначения. Без proof неизменны holder/assignment/carrying reservation, заказ доступен в recovery; повтор не создаёт второй системный case (CP-19 REV-CASE-01). С proof освобождаются только подтверждённые items по K1, ровно один раз; возраст сам по себе не доказательство. Проверка охватывает все writers, не только UI.

**REV-DEP-01:** отмена в пути и удаление аккаунта при unresolved custody — отдельная сверка с PR #212 и CP-21/K6 до будущей реализации. Наличие cancel guard в PR не доказывает корректное deletion/retention поведение. Сообщённый риск удаления старого активного parcel при account deletion — **P1 custody blocker, OPEN** до воспроизведения на согласованном SHA и независимой проверки; не считать PR #212 его решением. Проверить delete/recreate, отзыв credentials и support-mediated recovery: не стирать holder/obligations, не возвращать старый доступ новому аккаунту. Статус PR/веток перепроверить по процедуре в CHANGELOG; текущий план не объявляет merge или исправление main.

SIM-02/05/15; property: no transition from carrier custody to canceled/resolved without transfer or approved exception. PG cancel/pickup race. Mutation: timer sets returned — T-CUS-01 red. Migration unknown не превращается в verified.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Полный writer audit, ни один путь не снимает обязанность таймером; state table проверена positive/negative transitions. Cross-mode taxi regressions не ухудшены.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не удалять старые заказы/суммы. Не считать custody_state юридическим заключением о собственности. Нельзя исправить только текст UI или сделать silent status fallback.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
