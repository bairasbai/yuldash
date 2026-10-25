# CP-12 — Возврат и помощь с зависшей вещью

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | правила возврата/хранения/компенсаций/ответственности, миграция, ops availability |
| Зависит от | CP-02/05/06/07/09/10/11; LG-07/08/09 по категории |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 03,04,05,07,08,10 |
| Зона файлов | backend/app/routers/parcels.py, cleanup.py, taxi_worker.py, incident_escalate.py, models.py; Android CourierTroubleDialog.kt/AdminParcelsScreen.kt; PWA ParcelProblemActions.tsx/AdminParcelsScreen.tsx |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Если получатель не вышел, курьер не остаётся один с чужой вещью. Отправитель видит где она, кто отвечает и что делать дальше.

## 2. Что уже есть в коде

attempt-failed1468, redeliver1521, return-start1586, return-done1624 уже есть. Таймер returning → returned и in_transit → canceled описаны CP02. Incident(parcel_id) уже реальный workflow.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Довести неуспешную попытку до подтверждённого возврата/явного exception, включая sender unavailable, breakdown, damage, unsafe goods и purchase liabilities. Сохранить денежные обязательства и историю.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Reason taxonomy/attempt evidence, допустимые repeat limits по policy и consent.
2. Return leg/quote из accepted terms; при отклонении маршрута amendment, не silent surcharge.
3. Safe-storage case с responsible owner/next_action_at, ограниченное согласованное место; никакого выдуманного склада.
4. Support escalation SLA по риску/часам; при отсутствии безопасного ops режима не принимать такие заказы.
5. Proof return закрывает только перечисленные item obligations через K1; lost/damaged/unsafe требуют решения/компенсации отдельными state axes. Worker/cleanup только alert.

### B Android

Trouble flow сохраняет активную посылку при SOS/навигации/offline; показывает за кем вещь, следующий шаг, контакт поддержки и условия возврата.

### C PWA

Возврат в активной админской очереди; customer view не выдаёт returning за delivered. Empty/error/case ownership видимы.

### Межмодульный договор R2

K2 делает redelivery и storage исполнимыми: новая attempt ordinal и accepted quote/terms; storage только известный authorized custodian, address/consent/price/owner/next_action и отдельный proof. C=storage_custodian, W=stored, O=stored_pending_release, не returned/resolved. Release from storage требует receiving party proof/assignment и новой leg authority. Без approved storage/recovery SOP пилот этой категории/коридора закрыт, включая базовую parcel услугу при невозможности обеих сторон принять.

### Уточнение R4

Trip cancel после pickup не parcel cancel:11 сохраняет current holder/carrying reservation до доказанного return/storage/handoff. Новый dedicated rescue только отдельный quote/consent, отказ платить не разрешает бросить вещь.

### Операционный gate R4

До пилота зоны/категории доказаны конкретный возвратный адрес/принимающий/часы, связь, плательщик согласованных recovery расходов и реально доступный безопасный запасной transfer/storage путь. Само обещание «пишите в поддержку» не закрывает gate. Партнёры хранения только после договоров, проверенной доступности и SOP; собственного склада не требуется автоматически. Еда/уникальные документы требуют отдельных допустимых условий. Текущий статус NOT_OPERATIONALLY_READY, подтверждения договоров нет.

### API и события

K2 report_attempt_failed/propose_redelivery/decide_redelivery/start_redelivery/request_return/confirm_return/propose_storage/confirm_storage_handover/authorize_storage_release/confirm_storage_release/resolve_exception; state_version и return_quote_ref, approved custodian ref. case events c privacy-safe reason.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M8 support/custody fields и return leg; preserve legacy amounts/goods/courier_id. Удаление аккаунта не стирает unresolved obligations. Historical auto-returned remains marked legacy.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Не удалось вручить. Вещь пока у курьера». «Согласуем безопасный возврат». «Поддержка уточняет, кто примет вещь».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

recipient unavailable/sender unavailable/unsafe route/damaged/lost/partial/return pending confirmation/closed exception/refund separately pending.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

SIM-31: canceled Ride с carrying parcel, частичный handoff old/new ride, unknown transfer, storage/return resource release только по proof.

R2-STORAGE: sender+recipient noanswer → storage proposal → handover → stored → later release → return proof. Нет proof/неизвестный holder → delivery_exception, не stored. Timeout/cap expiry/admin close не выдумывает handback.

SIM-05/06/07/08/13/14; sender+recipient unavailable, returning beyond retention, crash worker, courier injury, account delete, admin forced close, returned proof replay. Mutation: auto-returned by age — red.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Каждый exception имеет accountable actor, next action и достижимый безопасный выход; ни один physical proof не заменён таймером. Поддержка проходит сценарий до результата сторонам.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Нельзя приказывать оставить/утилизировать вещь без правового основания/разрешения; опасный груз не автоматически return по обычному маршруту. Не обнулять товар/компенсацию из-за commission 0.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
