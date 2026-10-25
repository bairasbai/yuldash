# CP-22 — Наблюдаемость и работа поддержки

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | платные monitoring/SMS/maps сервисы, ops staffing/SLA и публикация обещаний |
| Зависит от | CP-02/07/08/12/14/19/21A; launch отдельно CP-23 |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 03,04,05,07,08,10 |
| Зона файлов | backend/app routers/worker/outbox/health integration, existing metrics/logging helpers; новые courier ops dashboards/runbook docs в утверждённой зоне; no product secrets |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Потерянное назначение, зависший возврат или неверная оплата обнаруживаются до жалобы клиента, и у каждой проблемы есть ответственный.

## 2. Что уже есть в коде

Есть workers/cleanup/health и тесты livepos observability; inventory не подтверждает полный courier SLO/dashboard/on-call. Наличие логов не равно операционной готовности.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Метрики по state age/custody/quote/dispatch/payment/notification, alerts/runbooks, операционная модель по часовым поясам/часам работы. Никаких личных адресов в telemetry.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Counters/histograms: quote success/age, accept latency, expired lease, assignment conflict, fulfillment/custody age, proof failure, offline reconcile, return overdue, ledger mismatch, refund/payout pending, notification actual receipts.
2. Correlation command/event/order opaque IDs; SLI definitions denominator/event time/late data, no PII.
3. Alerts severity/owner/ack/escalation, dedup and incident link, suppress maintenance без скрытия денежных/custody проблем.
4. Outbox retry jitter/max dead-letter case, provider outage circuit breaker; never silently drop.
5. Runbooks lost courier, both sides unavailable, data leak, financial mismatch, disabled region, bad migration.

### B Android

Клиентская диагностика безопасных error codes/version/connection state по consent; не отправлять addresses/photos/screens автоматически.

### C PWA

Ops view role scoped, alert drilldown к case, read-only metrics; ошибки background/foreground telemetry различимы.

### Честный ETA R4

C9 обязателен: единый target-stop forecast, отдельные captured_at/received_at/generated_at, expiry и accuracy provenance, route/assignment/sequence guard; свежий GPS не омолаживает старый прогноз. Трафик/ready/route change/handoff пересчитывают либо инвалидируют ETA. Абсолютное окно, его сдвиг и customer elapsed видны отдельно от платного F5 wait. Нет вымышленного движения, вечных «5 минут» или скрытого optimistic smoothing.

Privacy фильтруется сервером, включая геометрию пассажирских остановок и return roles. Уполномоченный отправитель может видеть status/ETA возврата по scope; неудавшийся получатель не видит последующий чужой путь. Live return GPS не включается этим договором. Adapter не выдумывает timestamp/accuracy units; physical Android/PWA/background/permissions и город/село/межгород проверки обязательны.

M7 добавляет bounded TrackingSnapshot/Forecast с input hashes/seq, не второй order/receipt ledger. CP-22 панели раздельно показывают freshness, signed error/interval coverage, unavailable denominator, delay disclosure latency, split-brain/privacy. Gates численных thresholds/provider/SLO/retention остаются OPEN.

### API и события

Health readiness distinguishes DB/provider/degraded courier features. Metrics do not mutate order; alert acknowledge не fulfillment transition.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

Новые telemetry tables только если existing infrastructure не подходит, bounded retention D3. Events/outbox данные не копируются бессрочно в аналитический склад.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Доставка задерживается, поддержка уже проверяет» только после реального case/assigned owner; иначе честное «Свяжись с поддержкой».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Healthy/degraded/no supply/provider down/backlog/high custody age, alert acknowledged vs resolved, business hours closed.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

### Новые безопасные проекции

GET tracking/recipient track и tracking.updated/eta.changed/degraded/restored используют C9, общий allowlist и resync; локальный навигационный ETA не заменяет опубликованный forecast.

## 7. Тесты

Inject worker crash/provider outage/outbox retry exhaustion/clock lag/unmatched ledger; alert fired+owner+runbook recovery verified. False-positive flood and no-PII checks; dashboard completeness vs DB counts.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

SIM-37 и ETA-N01…22: old fix/new HTTP, fresh fix/old ETA, traffic5 → 20, clock skew/reboot, stale/no provider, wrong door, route/handoff race, hidden passenger geometry, return role scopes. Проверить реальными устройствами до runtime acceptance; эти проверки ещё NOT_EXECUTED.

## 8. Приёмка

Каждый P0 сигнал доходит назначенному ответственному и приводит к безопасной процедуре; mock alert screenshots не доказательство real channel delivery. SLO numeric targets утверждены владельцем на pilot baseline.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не считать uptime доказательством доставки. Не обещать 24/7 без людей. Не логировать OTP/телефон/точную гео/содержимое покупки ради diagnosis.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
