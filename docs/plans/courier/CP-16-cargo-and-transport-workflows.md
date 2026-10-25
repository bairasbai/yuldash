# CP-16 — Пеший вело мото авто и грузовой

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | допуски/страхование/труд/груз/тарифы/документы; LG-02/07/08/10/11 |
| Зависит от | CP-03/04/05/06/08/09/12 |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 02,03,04,07,09; отдельные transport profiles |
| Зона файлов | backend/app/routers/courier.py, models.py, geo.py; Android CourierOnboardingScreen.kt/CourierScreen.kt; PWA CourierOnboardingScreen.tsx/ParcelSendWizard.tsx; proposed equipment/cargo validation module |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Пеший не получает тяжёлый шкаф, грузовая машина не приезжает без нужной вместимости, а подъём на этаж не оказывается бесплатным сюрпризом.

## 2. Что уже есть в коде

Только car/cargo в application/profile; размеры small/medium/large и вес/хрупкость уже в ParcelDelivery. Конкретного piece dimensions/vehicle equipment proof как полного контракта не доказано.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Transport capability и cargo intake: масса/габариты/число мест/крепления/подъезд/погрузка/помощники. Отдельно договорная роль и допуск конкретного человека/машины.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Limits by transport/equipment immutable policy; known dimensions required для cargo, vague large не достаточно.
2. Access/parking/loading/этаж/лифт и временные ограничения включаются quote lines.
3. Смена машины/курьера инвалидирует capacity eligibility, requires reaccept при существенных условиях.
4. Multiple loaders/такелаж только отдельная capability/договор, не обещание обычного водителя.
5. Mismatch на заборе: фото по consent, amendment или refuse с понятной cancel policy.

### B Android

Онбординг по транспорту без лишних документов, vehicle identity snapshot после подтверждения; чек-лист погрузки/безопасности не мешает accessibility.

### C PWA

Ввод mm/g/kg с локальными понятными единицами, пределы и inline errors, одинаковая server validation. Контракт клиентской машины не меняет тариф такси.

### API и события

Transport requirements payload: class, weight_g, dimensions_mm, pieces, equipment, helpers, access options; errors CAPACITY_EXCEEDED/EQUIPMENT_REQUIRED, no client override.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M2 eligibility; M3 stops/cargo snapshot; новые поля при необходимости через зарегистрированную D2 revision, не вне очереди. Vehicle identity reuse RS14 только после проверки.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Проверь вес и размеры каждого места». «Погрузка не включена». «Этот груз не поместится в выбранный транспорт».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Unknown dimensions/insufficient equipment/vehicle changed/arrival mismatch/loading refused/safety stop/fee amendment pending.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

Boundary weight/dimensions, units1000x, awkward shape/total vs perpiece, different transport duplicates, same vehicle concurrent capacity, stale approval, helper count and accessibility. False declaration negative.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Каждый transport имеет limits/допуск/сценарии/роль; car/cargo legacy не сломан, новые типы закрыты до readiness. Нет unpriced mandatory options.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не считать «Газель» универсальным разрешением грузоперевозок/грузчиков. Не расширять в опасные грузы/животных по категории other. Не обещать полную страховку.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
