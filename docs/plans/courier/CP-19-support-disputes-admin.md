# CP-19 — Поддержка спор и действия администратора

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | права поддержки/денежные решения/компенсации/договоры/retention, SLA часы |
| Зависит от | CP-02/09/10/11/12; CP-14 для денежных решений; RS admin assurance после проверки |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 04,05,07,08,09 |
| Зона файлов | backend/app/routers/{incidents, parcels, courier}.py, incident_escalate.py, models.py; Android AdminParcelsScreen.kt/AdminIncidentsScreen.kt/CourierTroubleDialog.kt; PWA AdminParcelsScreen.tsx/AdminIncidentsScreen.tsx/IncidentDetailScreen.tsx |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Жалоба не пропадает после отправки: обе стороны видят разбор и решение, а опасная или зависшая доставка сразу попадает ответственному человеку.

## 2. Что уже есть в коде

parcels.py:1753–1789 Incident(parcel_id) и admin1810–1979. PWA AdminParcels max300 local filter и Active исключает returning; Android active sort не исключает returned. Получатель без аккаунта спорит через отправителя.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Операционная очередь по custody/деньгам/сроку, full dispute lifecycle до решения и appeal, минимальные RBAC полномочия и audit. No-account recipient обращение с проверенными ограниченными правами.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Case taxonomy loss/damage/nonpayment/late/unsafe/harassment/wrong item; severity и next_action_at.
2. Reporter/other side evidence request/response/resolution/appeal, сроки по accepted policy; закрытый order не лишает права законного спора.
3. Support read role, dispatcher mutation, financial resolver и superadmin разделены; чувствительные просмотры audited.
4. Admin actions expected_version+reason+evidence, никакого прямого status/paid toggle. Custody/security gates остаются.
5. Queue pagination/server filters, returning и unknown outcome включены. SLA alert не закрывает case. Массовые системные сигналы не скрывают обычные обращения за общим limit: server-side фильтры по source/type/status/parcel и устойчивая пагинация доступны до усечения, не только local filter первых 300 строк.
6. Системный custody-case обязательно связан с `parcel_id` (parcelID в клиентском DTO); тип/причина и causal episode/generation различимы. Повторный worker tick/retry/restart того же события идемпотентно возвращает существующий case, новый реальный эпизод не подавляется. Использовать общий service-principal/receipt договор C3/K6 и существующий Incident, не второй case/receipt ledger. Связь case/alert и доменного результата — атомарная запись либо восстанавливаемый outbox с тем же causal key.

### B Android

Короткий incident flow из активного/завершённого заказа, сохранённый текст, помощь при SOS отдельно. Админ видит полномочие/последствия до действия.

### C PWA

Полноценная case timeline, ссылки на ограниченные evidence, resolution кому/когда отправлено. Поиск без массового экспорта ПД; role-specific views.

### API и события

Existing Incident расширить parcel context; resolution endpoint C4, expected_case_version, allowed_resolutions, money decision refs. Recipient grant scopes complaint only.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M8/M10 case fields/indexes, evidence/audit immutable. Retention/appeal/legal hold по LG06, private reason не push text. Historical incidents не становятся resolved.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Обращение принято». «Нужны подробности от второй стороны». «Решение…; можно обжаловать…». «Это экстренная ситуация? Позвони…» только корректный локальный процесс.

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Draft/sending/unknown/open/await evidence/under review/resolved/appealed/escalated, admin permission expired, custody unresolved.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

**REV-CASE-01 → SIM-05/09/13, T-CUS-01/T-UX-01/T-OPS-01:** два workers и рестарт на одном parcel/episode дают один системный case; разные parcel/новый episode не схлопываются. Crash между escalation и notification восстанавливает тот же case, без потери parcel context. На наборе больше лимита страницы (включая >300 системных сигналов) обычное обращение находится серверным фильтром и открывается из пагинированной очереди; обход стабильных страниц без пропуска/дубля в фиксированном срезе. Проверить ACL, returning/unknown и последующий case resolution независимо от физического proof.

SIM-05/09/13; owner-role IDOR, revoked admin session, race two resolutions, evidence foreign order, terminal dispute access, recipient token misuse, money action without financial role, no silent closure.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Отправка → разбор → решение сторонам реально пройдены. Очередь не прячет returning. Каждый admin change traceable и не обходит физическое доказательство.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не считать открытие формы решённым спором, не объявлять «проверен» только по роли/admin кнопке. Автоматическая санкция за жалобу без процедуры и правового review запрещена.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
