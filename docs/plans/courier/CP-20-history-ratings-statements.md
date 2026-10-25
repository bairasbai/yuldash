# CP-20 — История оценки и понятный доход

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | политика рейтинга/санкций, финансовые названия/выписки и retention |
| Зависит от | CP-02/07/10/14/19; RS receipts/rating shared functions только после проверки |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 03,05,06,07,09 |
| Зона файлов | backend/app/routers/{parcels, courier}.py и rating_service.py; Android CourierEarningsScreen.kt/ParcelReceiptDialog.kt/ParcelsScreen.kt; PWA CourierHistoryScreen.tsx/CourierEarningsScreen.tsx/CompletedParcelCard.tsx/ParcelRate.tsx |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Человек находит старую доставку и свою оценку, а курьер понимает разницу между начисленным, удержанным, уплаченным и реально полученным.

## 2. Что уже есть в коде

parcels.py1712–1740 recent limit10; receipt1982–2063 только delivered/returned, canceled409. Existing completed cards/rating/dispute, earnings; courier recent/history tests уже есть.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Пагинируемая полная история, filter/status parity, immutable statement snapshots, один голос на actor/order, recovery after unknown, прозрачные review/appeal policies.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Cursor stable order(created_at, id), role-scoped filters и bounded page; новые записи не пропускают страницы.
2. Statement separates service/goods/commission/payment proof/refund/payout; canceled statement если нужен — новый type, не изменение старого receipt409 молча.
3. Rating unique order+actor+role, только после применимого outcome, no preset5; edit/window по принятой policy.
4. Reconcile existing rating before retry; malicious reviews moderation with appeal, no hidden permanent punishment.
5. Earnings totals reconcile ledger, date timezone/currency explicit; export minimal.

### B Android

History empty/search/filter/loading, own rating восстановление, statement доступен offline только в минимальном виде. «Последние 10» не «вся история».

### C PWA

Server pagination вместо max300 локально; earnings breakdown и reconciliation state. Keyboard/BA long texts.

### API и события

GET orders cursor+filters; statement/earnings from C4; rate idempotency, unique constraint/conflict response returns own rating only.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M10 indexes/unique rating if absent verified first. Legacy ratings preserved, duplicate repair отдельный approved reversible plan. Money snapshots not recomputed by current tariff.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Последние доставки». «Показать ещё». «Начислено до налогов и расходов». «Возврат денег ещё обрабатывается». «Есть замечания» без предвыбранной оценки.

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Delivered/returned/canceled/closed exception per allowed actions, own rating unknown/saved, no statements vs no payment, partial refund.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

SIM-12; duplicate rating/crossaccount, pagination insertion/deletion cursor, canceled receipt409 regression, return not delivered, earnings totals vs ledger, language/theme/accessibility and offline recovery.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

History genuinely paginated, state labels match server, одна оценка/начисление. Фискальный чек не подменён app receipt.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не повышать рейтинг предвыбранной 5/автонажатием из push. Не связывать courier rating с taxi trips без согласованной моделью. Не скрывать commission debt как «меньше заработал».

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
