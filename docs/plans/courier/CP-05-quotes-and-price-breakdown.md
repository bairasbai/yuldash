# CP-05 — Цена до заказа без сюрпризов

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | деньги/тарифы/миграция; изменения численных ставок только отдельным решением |
| Зависит от | CP-02/03/04; денежный контракт F1/F2; LG-03 |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 03,07,09 |
| Зона файлов | backend/app/routers/courier.py, config.py, pricing.py, price_freeze.py; models.py; Android ParcelsScreen.kt; PWA ParcelSendWizard.tsx; новые backend/app/courier_quotes.py/tests/test_courier_quotes_v2.py |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Отправитель до подтверждения понимает итог, состав, срок цены, товар отдельно, ожидание и максимальные последствия возврата.

## 2. Что уже есть в коде

courier.py:1196–1227 estimate и Redis freeze2 мин,1269–1434 create; formula671–791 и current defaults config 244–300. Это база, которую расширяем durable quote, не заменяем конкурентным тарифом.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Immutable quote snapshot вместо только cache freeze; явные единицы/rounding/expiry/coverage и route version, согласованные return/cancel terms. Выявить границу 40 км и long pickup в economics, не исправлять цену без owner.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Зафиксировать текущую арифметику golden fixtures для каждого компонента, включая ночь/погоду/размер/срочность.
2. Quote line schema F2, integer сумма; актуальный tariff policy/version, delta shadow comparison.
3. Create атомарно проверяет hash/expiry/version/coverage, не trusts client total.
4. Address/cargo/window edit инвалидирует quote; accept не может получить иной net скрыто.
5. Fallback/unknown pickup показаны как оценка/предложение по принятой модели, не fixed.

### B Android

Breakdown с итогом услуги, товаром/объявленной ценностью отдельно, условия возврата до CTA; stale цена требует нового осознанного подтверждения.

### C PWA

Те же суммы/округление и retry semantics; переключение вкладок/типа доставки не применяет старый quote. Loading не оставляет старую цену кликабельной.

### Межмодульный договор R2

M3 уже содержит OrderAmendment/Consent и PaymentObligation identity, чтобы quote lines задавали planned payer/payee/consent requirements, а create materialized component mapping F2/F10. Summary payer только производный; recipient-link не consent на оплату. Cap scope/accumulator F5 фиксируется в quote; нет автоматического нового cap на каждой остановке.

### Уточнение R3

F2 single-use quote: line хранит quote_component_id и planned obligation, не obligation_id. Create атомарно materializes QuoteUse + OrderQuoteComponent + реальные obligations; same key повторяет тот же mapping, другой intent с использованным quote получает QUOTE_ALREADY_USED. Новая доставка требует новый quote.

### Решение OD-04 и договор цены

DRIVER_SET_PRICE, ADVISORY_ONLY, sender/payer consent до create; request/proposal → F2 quote → single-use order по R4A в 11. Рекомендация не обязательный диапазон и не fallback сумма. Ride meeting по умолчанию, door detour только prior consent/route-time context и RS guards. Никакой скрытой комиссии; SIM-36 проверяет stale proposal, duplicate create и mode switch.

### API и события

POST quotes и POST orders(quote_id, accepted_terms_version). Quote immutable; QUOTE_EXPIRED/QUOTE_STALE/VERSION_CONFLICT. В response currency=RUB, все amount_kop integer.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M3 Quote/QuoteLine: immutability, expiry/query indexes, referenced accepted quote не удаляется обычным cache TTL. Legacy order price не переоценивается.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Доставка…; товар оплачивается отдельно». «Цена устарела, пересчитаем перед заказом». «Возврат может стоить… по этим условиям».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Оценка/фиксированное предложение/истекло/маршрут неизвестен/сбой пересчёта/изменились условия/unsupported option.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

R3-QUOTE: quote без order/debt; expired/unaccepted debt absent; concurrent create и lost response; два разных key на Q дают один order; rollback не оставляет orphan obligation.

R2-MONEY: split payer quote (service sender/goods recipient/commission courier) не сливает плательщиков; recipient grant без consent не создаёт obligation.

T-PAY-02/T-GEO-01, HALF_UP boundaries, integer overflow/negative/nonfinite rejected, quote replay across accounts, concurrent tariff switch/create,40 км discontinuity golden vs proposed shadow. Отдельно E-01/E-03 из 08: city delivery/intercity pickup cap300 vs720 и reverse zones; прежний freeze не доказывает непрерывность 40 км. Mutation: client price accepted — red.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Каждая копейка итоговой цены объясняется строкой и policy version. Прежние defaults не изменены документом; owner decisions отличимы от shadow proposals.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не копировать московские тарифы Достависты/Яндекса в RB. Не смешивать рубли RS/D7 с копейками courier. Не называть Redis запись вечным договором цены.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
