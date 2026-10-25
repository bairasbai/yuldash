# CP-11 — Ожидание опоздание и отмена

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | денежная политика ожидания/отмены/лимиты, миграция; компенсации отдельно |
| Зависит от | CP-02/04/05/06/07/09/10 |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 03,04,07,09 |
| Зона файлов | backend/app/routers/{courier, parcels}.py, instant_service.py, config.py, models.py; Android CourierTroubleDialog.kt/ParcelsScreen.kt/CourierScreen.kt; PWA ParcelProblemActions.tsx/CourierScreen.tsx |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Не нужно спорить у двери, сколько стоило ожидание и можно ли отменить бесплатно. Обе стороны заранее знают ставку, предел и причину.

## 2. Что уже есть в коде

courier.py:513–542 waiting на двух концах/возврате; parcels.py:1109–1150 arrive geocheck. Июльское «без платного ожидания» не отражает текущий код. Cancel/return formulas545–650.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Явные WaitSession/cancel preview contract, authority/time/evidence, согласованные fee caps. Исправить возможный return cap overrun новым waiting только после owner decisions.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Server time/stop/phase уникальный active wait, free interval не сбрасывается повтором arrive.
2. Valid arrival и contact attempt policy; noise GPS/чужое photo не запускают/останавливают деньги.
3. Client получает start/remaining/rate/cap; отключённые notifications не маскируются delivery receipt.
4. Cancel preview привязан к state/terms_version, stale consent не списывает больше.
5. Late courier/merchant/customer причинность отдельно, компенсация не автоматический штраф.

### B Android

Таймер server-derived, понятный cap, предупреждение о старте платы; cancel confirmation показывает реальные последствия и хранит intent при unknown.

### C PWA

Не глотать arrived/in-transit/cancel ошибки; preserve form и видимый outcome. Screen reader сообщает этапы без озвучивания каждой секунды.

### Межмодульный договор R2

K2 определяет start_wait/pause_wait/resume_wait/stop_wait, actor/phase и server-clock. F5 cap_scope/order accumulator с reserved+consumed исключает reset при новой сессии/попытке; обещанный общий return cap включает указанные wait/retry компоненты. При cap reached новый расход не начисляется без действительного amendment. cancel-preview связан с version и учитывает purchase commitment F11, COD и physical custody.

### API и события

Wait session commands, cancel preview + command; amount_kop/version; CUSTODY_REQUIRES_RETURN после pickup. Wait update events не tax receipt.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M7 wait/attempt entities; старые начисления сохраняются, новый snapshot rate для будущего. Backfill start_time unknown без придуманного GPS.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Бесплатное ожидание закончится через…». «Дальше… за… не больше…». «После передачи отмена означает возврат».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Не прибыл/прибыл/free wait/paid wait/cap reached/disputed/paused/return wait, network clock drift, cancel unknown.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

C9 задаёт связанный ETA-договор: правильная target stop/leg, отдельные promised/current/actual и elapsed/paid wait; route/ready/handoff изменения инвалидируют старый прогноз, offline/timezone не омолаживают данные. Приёмка SIM-37/ETA-N01…22 применима к затронутому сценарию, без раскрытия чужих остановок.

## 7. Тесты

R2-CAP: stop/resume/new attempt не сбрасывают order cap; return 350+newwait7 при total cap350 остаётся≤350, rejected excess либо отдельный предварительно согласованный amendment. Scope changes OWNER_PENDING.

SIM-02/05/14; double arrival, device clock skew, missing notification, concurrent stop/cancel/proof, wait after return freeze, cap0 and cap reached, minimum boundary. Mutation: client clock money — red.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Счёт воспроизводим по серверным интервалам, договору и cap; нет двойного/неограниченного ожидания. Цена возврата видна до первоначального заказа.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Нельзя наследовать конкурентные 6/10/15 мин как утверждённый тариф. Нельзя останавливать wait чужим фото, приравнять silent timeout к согласованному штрафу.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
