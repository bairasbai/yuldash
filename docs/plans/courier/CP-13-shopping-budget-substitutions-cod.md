# CP-13 — Покупка замены и товарные деньги

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | правовая модель buy-bring/COD, лимиты/аванс/компенсации/чеки, миграция; LG-01…05/08 |
| Зависит от | CP-03/05/06/07/09/10/11/12; CP-14A/M9 для COD/allocation/provider effect; 13A1 shopping foundation не ждёт 14A |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A1 shopping foundation/M8 → 14A/M9 → A2 COD activation; B Android и C PWA после соответствующего API → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 02,03,04,05,07 |
| Зона файлов | backend/app/routers/courier.py, parcels.py, models.py; Android ParcelsScreen.kt/CourierScreen.kt/ParcelReceiptDialog.kt; PWA ParcelSendWizard.tsx/CourierScreen.tsx/ParcelReceiptCard.tsx; новые shopping contract tests |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Клиент получает согласованные товары, а не неожиданный счёт у двери. Курьер заранее понимает нужно ли тратить свои деньги и кто оплатит отказ/возврат.

## 2. Что уже есть в коде

delivery_type buy_bring; goods-cost courier.py1444, raise-budget1494; cap500000 коп и tolerance max15%/10000 коп в 59,71–77. Формы бюджета/товара уже есть. Это не разрешение всем НПД покупать/собирать чужую выручку.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Разделить shopping request, выкуп, COD и компенсацию расходов. Line items, replacement consent, фактический чек/сумма, partial/not available, безопасная ветка отказа. Старый tolerance не считать новым согласием.

OWNER_PENDING: кто является покупателем/продавцом/плательщиком, авансирует ли курьер, допустимый риск, сбор за покупку, tolerances, возврат товара и роль ККТ. Без согласованной схемы capability disabled, но подготовка DTO/тестов возможна.

### A Сервер

1. ShoppingRequest lines/quantity/category/cap/substitution policy; блок запрещённого в free text и изменении списка.
2. Request amendment при превышении или замене, audit consent, expiry; молчание не approval.
3. Purchase receipt привязан к merchant/lines/amount, no self-increase payable.
4. Actual goods amount не включает delivery/commission; возврат покупки и доставки раздельно.
5. После покупки release запрещён без custody/liability resolution. COD отдельный permission, merchant accounting и доказательство передачи выручки.

### B Android

Shopping list/budget/замены до заказа, courier before accept risk, чек и network retry с сохранённым вводом. Нельзя «согласен» от имени клиента нажать курьеру.

### C PWA

Паритет list/amendment/receipt, ошибки суммы/фото не теряют заполнение. Клиент видит pending proposal, не уже куплено.

### Межмодульный договор R2

F11 запрещает внешнюю оплату до applied start_purchase. Cancel-before-start отзывает разрешение, start-before-cancel оставляет bounded in_progress/unknown commitment и custody recovery; receipt не создаёт задним числом debt. Базовый offline start запрещён, потеря ответа → reconcile до платежа. F10 отдельно задаёт COD collection → held → remittance/refund/shortfall с доказательствами, не courier payout. Очередь:13A1 shopping/M8 → 14A obligations+allocation+attempts+COD/M9 → 13A2 COD feature activation без новой общей миграции. CP-14 не ждёт 13A2.

### Уточнение R3

F13 вводит один CashDisbursementIntent/reserve для remittance и cash refund. До внешней передачи нужен applied start; pending/unknown любого вида уменьшает тот же account и source-collection remainder. Refund уже remitted средств требует разрешённый иной funding source, не аванс курьера.

### Уточнение R4

OD-2026-10-25-03 подтверждает product scope ready pickup+buy_bring и payer sender/recipient/merchant. Это не разрешает исполнителю покупать до F11 start, не подтверждает долг третьей стороны и не закрывает legal/НПД/фискальные/provider gates. Consent конкретного payer до acceptance; goods/service/commission отдельно.

### API и события

Shopping/Amendment DTO F6, commands authorize_purchase/start_purchase/record_purchase_result/reconcile_purchase и общие propose_amendment/decide_amendment; permission deliver_prepaid vs collect_goods_payment vs buy_for_customer. Idempotent actual_amount с immutable evidence.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M8 ShoppingRequest/Lines/PurchaseCommitment/PurchaseEvidence, shopping extension уже существующих M3 amendments/consents; legacy scalar budget сохранить без выдуманных item prices. Purchase evidence retention/receipt data по LG05/06.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Без твоего согласия замену не купят». «Бюджет товара…; доставка отдельно…». «Курьер не обязан оплачивать покупку своими деньгами» в выбранной модели.

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Out of stock/partial/await consent/price changed/receipt uploading/unknown purchase record/cancel after purchase/recipient refuses payment/merchant return rejected.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

SIM-35: готовый заказ vs список, все три payer роли с/без consent; recipient link не платёжное согласие; бюджет/замена/чек/остаток не склеены с delivery fee.

R3-CASH: held 60000, remit60000 reserved/unknown запрещает refund 60000; concurrent40000/30000≤60000; реальные два расхода сохраняются с shortfall, не fictitious debt.

R2-BUY: start/cancel обе очередности, success+lost receipt, unknown/failed, duplicate receipt, unauthorized oldlist receipt, partial merchant refund. R2-COD: split payer/partial collection/partial remittance/refund/shortage/account takeover, conservation F10.

SIM-06; malicious increase/negative/fraction money/cap boundary, double record_purchase_result, late consent old list, request canceled while purchase, missing receipt, prohibited category in replacement. LG model permissions tested.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Каждая товарная сумма и замена имеет источник/согласие; нет скрытого увеличения счета. End-to-end до возврата/сверки без реальных покупок в автоматических тестах.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не рекламировать лекарства/алкоголь как обычный buy-bring. Не приравнивать service payment к goods collection. Не обещать платформенный credit/страхование без договора.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
