# CP-18 — Несколько заказов точек и передача

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | новые обязательства/маршруты/стоимость/ПД/партнёрские передачи и миграция |
| Зависит от | CP-08/09/10/12/17; CP-15/16 по товарам/транспорту |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 01–04,07,08,10; multi-stop profile |
| Зона файлов | backend/app/models.py, routers/{courier, parcels}.py/livepos.py; новые backend/app/courier_itinerary.py; Android CourierScreen.kt/CourierLocationService.kt; PWA CourierScreen.tsx/ParcelTrackMap.tsx |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Курьер может везти несколько совместимых посылок, но одна вручённая коробка не закрывает остальные и не раскрывает одному клиенту чужие адреса.

## 2. Что уже есть в коде

Несколько активных deliveries возможны; Android service имеет active IDs, PWA GPS только выбранной mounted карте. Отдельных DeliveryStop/CourierOffer entities в baseline не найдено; batching не подтверждён.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Itinerary/legs/items/capacity, precedence pickup-before-dropoff, customer isolation, partial proof и контролируемый handoff. Hubs/relay не включаются без отдельного ops/legal решения.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Расчёт batch только по совместимости груза/окон/объёма/обещанных net; opt-in policy и явные constraints.
2. Новая точка не ухудшает accepted SLA/net без amendment; route version.
3. Item custody по stop, aggregate complete iff все obligations resolved, не first dropoff.
4. Reassignment после pickup — dual-party handoff proof; unknown handoff case не две active custody.
5. GPS/signals распределяются по разрешённым order scopes; replay route sequence.

### B Android

Однозначный следующий stop с items и суммой, нельзя вручить не ту посылку; back/navigation не меняет активный order. Background per active allowed IDs bounded.

### C PWA

Multi-order tracking контракт с честным foreground-only; receiver видит свой segment/ETA, не маршрут с чужими домами.

### Межмодульный договор R2

K1 — единственная per-item/stop/order projection. C2 confirm_dropoff/return не terminal весь заказ по одному item. K2 propose_handoff/confirm_handoff lock order → sorted assignments → item slots/capacity → proof: атомарно старые slots/capacity освобождаются и новые занимают только transferred items; unaffected items остаются. Unknown handoff не разрешает две competing assignments, создаёт recovery case и блокирует новые действия на спорных items. Legacy adapter не принимает mixed outcome как delivered.

### Уточнение R3

K1 resolved item outcomes включают canceled только при доказанном no-transfer/no-purchase/no-collection. All canceled → canceled; delivered/returned/canceled смесь → completed_mixed. Уже состоявшаяся физическая передача не откатывается, неразделимый unknown commitment препятствует scoped cancel.

### Отдельные продукты on-route и dedicated

Service_type в scope/C2/F14 обязателен в UI/quote/offer/eligibility/dispatch. «Передать по пути» показывает собственное направление попутчика, окно и допустимый крюк; «Заказать курьера» — отдельную работу с её условиями/ценой. Профессиональный допуск не наследуется от rideshare. Нет silent substitution при нехватке supply; смена режима требует согласия и нового договора. OD-2026-10-25-01: on-route водитель может получать оплату, комиссия Юлдаш 0; это не бесплатная перевозка. Dedicated, включая scheduled, комиссионный. OD-04 утверждает DRIVER_SET_PRICE с ADVISORY_ONLY советом платформы; конкретные dedicated rates и legal/provider implementation остаются gated. No minimum/tier/hidden service fee для on-route.

### Уточнение R4

Ride joint capacity11 поддерживает independent single-item orders без слияния их денег/получателей; multi-item одного заказа и оптимизация batch остаются этой capability. Handoff между Ride требует old/new capacity/version locks и scoped proof, baggage/passenger commitments сохраняются.

### API и события

Itinerary version, stop IDs/sequence, leg capacity and assignments; commands include stop_id/item_ids. Handoff consent/proof tied old+new assignments.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M10 batch/itinerary links расширяют M1 item-level custody, unique stop/item transitions, reservations per leg. Unbatched legacy остаются independent, нет автоматического объединения историй.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Передай 2 места для этого получателя». «Другая посылка ещё в пути». «Передачу другому курьеру нужно подтвердить обеим сторонам».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Partial stop/failed one item/next stop delayed/batch paused/handoff proposed/unknown transfer/return of one package.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

C9 задаёт связанный ETA-договор: правильная target stop/leg, отдельные promised/current/actual и elapsed/paid wait; route/ready/handoff изменения инвалидируют старый прогноз, offline/timezone не омолаживают данные. Приёмка SIM-37/ETA-N01…22 применима к затронутому сценарию, без раскрытия чужих остановок.

## 7. Тесты

SIM-30/31: два parcels одного Ride разные senders/receivers, overlapping legs capacity; partial transfer no double slot; passenger baggage не отнято ради parcel.

SIM-28: on-route/dedicated lane, UI/quote/fee scope parity, запрет тихой замены и неправильного допуска/процента.

R3-CANCEL: delivered+canceled и returned+canceled имеют единственный projector outcome; purchase-start-first остаётся recovery; repeat GET/backfill не resurrect tracking.

R2-MULTI: two delivered+one returned → completed_mixed и resolved physical, финансы отдельно; one returned+one unknown → delivery_exception; partial RMA, wrong item hash, handoff crash before/after commit; GPS остаётся для другого активного заказа.

SIM-07/08; reorder stops, batch capacity across legs, concurrent add/remove/accept, partial delivery all-vs-one, wrong code/items, old courier callback, privacy other stop, crash handoff.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Каждое место имеет единственную custody; каждое обещание/плата/доступ привязаны к своему заказу. Базовый single parcel не регрессирует.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не объединять чужие деньги/receipts/PII в один customer DTO. Не считать handoff техническим reassign. Не снижать качество маршрута ради скрытого batching.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
