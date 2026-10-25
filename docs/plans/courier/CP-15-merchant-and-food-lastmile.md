# CP-15 — Продавец еда и товарные возвраты

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | договор продавца/food SOP/температура/санитарные требования/платежи/ПД; LG-01/05/06/09 |
| Зависит от | CP-03/04/05/06/08/09/10/11/12; CP-14 по фактической платёжной модели |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 02,03,04,05,07,08,09; дополнительный food профиль |
| Зона файлов | backend/app/models.py/routers/courier.py; новые backend/app/routers/courier_merchants.py, courier_food.py; новые Android/PWA merchant-ready/food instruction screens по nav queue; CourierScreen existing integration |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Курьер не ждёт неизвестно сколько у кухни, получатель знает статус готовности, а неподходящая упаковка или нарушенный режим не скрываются зелёной галочкой.

## 2. Что уже есть в коде

models.py1910–1921 cargo_type food существует как содержимое посылки. Отдельных Merchant/Restaurant/Menu/Inventory/Cart/FoodOrder в проверенном models нет. Это не готовый ресторанный маркетплейс.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Сначала B2B/merchant last-mile already sold order: intake external ID, подготовка, ready, упаковка, передача, exception/return. Каталог ресторанов, меню/остатки/checkout — отдельный явно оценённый подпроект, не незаметная часть last-mile.

OWNER_PENDING: набор merchant capability, ответственность за качество/готовность, продуктовые температуры/сроки, food equipment/обучение, документы по фактической роли. Нет универсальных градусов/минут для всей еды.

### A Сервер

1. Merchant identity/role agreement snapshot, dedup external_order_id, ready window и contact scopes.
2. Курьер dispatch с учётом ready_at; merchant delay отдельно от courier lateness.
3. Product-specific transport/packaging/equipment validation, no cargo contamination.
4. Damaged seal/temperature exception → reject pickup/case; не forced accept.
5. Food-safe return/disposal только утверждённый SOP, не обычная посылочная автоматическая обратная поездка.
6. Отдельная capability merchant_return после состоявшейся продажи: продавец выдаёт RMA (разрешение на возврат) со сроком/адресом/составом/требованием упаковки. Заказчик выбирает возврат, quote показывает услугу отдельно от ожидаемого возврата товарных денег. Приёмка продавцом фиксирует items/condition/refusal reason; courier returned не означает merchant accepted/refunded. Отказ склада, истёкший RMA, лишняя вещь, частичный приём и закрытый магазин идут в CP-12 case с сохранившейся custody. Плата за обратный товарный маршрут и ответственность определены заранее.

### B Android

Курьер видит место выдачи, номер заказа, packing checklist и deadlines без раскрытия покупок лишним людям. Merchant portal может быть PWA first с честной capability.

### C PWA

Минимальный merchant кабинет: создать/import order, ready/not ready, discrepancy, pickup confirm, statement. Receiver status включает preparation без фиктивного courier on-route.

### Уточнение R4

OD-2026-10-25-03 подтверждает product scope ready pickup+buy_bring и payer sender/recipient/merchant. Это не разрешает исполнителю покупать до F11 start, не подтверждает долг третьей стороны и не закрывает legal/НПД/фискальные/provider gates. Consent конкретного payer до acceptance; goods/service/commission отдельно.

### API и события

MerchantOrder external id/version/ready_at/packing_policy_id; ready events отдельные от pickup.confirmed. POST /courier/v2/merchant/orders и /merchant/orders/{id}/ready; POST /courier/v2/merchant/return-authorizations, GET /merchant/return-authorizations/{id}; создание courier order с kind=merchant_return и return_authorization_id. Эти новые endpoints scoped merchant, не admin полномочия. RMA status authorised/expired/revoked/received/rejected отдельно от fulfillment и refund.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M10 MerchantOrder metadata и food policy snapshots; existing food parcels не превращаются в B2B orders. MerchantReturnAuthorization содержит merchant/order/item refs, authorisation/version/expiry, receiving conditions и receipt decision, без автоматического refund. Menu/stock/cart не создаются этим CP без отдельного scope.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Заказ ещё готовят». «Упаковка повреждена, нужен ответ продавца». «Для этого товара нужен другой режим перевозки».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Not ready/ready/stale ready/packaging rejected/courier delayed/temperature exception/customer no answer/food cannot safely return.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

C9 задаёт связанный ETA-договор: правильная target stop/leg, отдельные promised/current/actual и elapsed/paid wait; route/ready/handoff изменения инвалидируют старый прогноз, offline/timezone не омолаживают данные. Приёмка SIM-37/ETA-N01…22 применима к затронутому сценарию, без раскрытия чужих остановок.

## 7. Тесты

SIM-35: готовый заказ vs список, все три payer роли с/без consent; recipient link не платёжное согласие; бюджет/замена/чек/остаток не склеены с delivery fee.

SIM-14; duplicate merchant webhook, false ready, no equipment courier rejected, receipt identity, temperature exception, mixed cargo conflict, merchant unavailable. Отдельные сценарии merchant_return: approved RMA → pickup → seller receipt; expired/wrong merchant/wrong item/partial refusal/warehouse closed; повторное событие не удваивает возврат денег. Product runtime с безопасными тестовыми товарами отдельно.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Food-specific profile и SOP approved перед enable; last-mile не выдаётся за полный marketplace. Юридическая применимость норм отмечена по роли, не blanket.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не копировать старый СанПиН как текущий и не переносить обязанности кухни на любого курьера. Не вводить own food storage/repacking автоматически. API сценарий не доказывает безопасность реальной еды.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
