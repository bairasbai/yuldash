# CP-23 — Поэтапный запуск по возможностям и регионам

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | каждый production enable/тариф/регион/провайдер/договор/БД/deploy отдельно |
| Зависит от | CP-01…22 применимый capability slice; CP-24 stage gate before enable; LG-01…11 relevant gates |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A launch checklist/flags → B закрытый стенд → C pilot decision → D расширение отдельными owner решениями |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 01–10 без сокращения; capability additions mandatory |
| Зона файлов | backend/app/config.py/owner switches when implemented; docs/plans/courier launch matrix/runbooks; Android/PWA capabilities consumers; no unsolicited production changes |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Юлдаш расширяется туда, где действительно готов доставить, вернуть и помочь. Недоступное направление не продаётся с обещанием «как-нибудь найдём».

## 2. Что уже есть в коде

Flags courier/parcels defaultfalse, scopes auditing отдельно. Target geography не подтверждена current zones/max_trip500. Existing rollout не исследован как production evidence.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

P0…P4 scope staged by service×geo×transport×supply/time. Launch gate отдельно от code complete; rollback не бросает активный груз и не теряет деньги.

Владелец выбирает пилотный город/коридор, часы/канал поддержки, approved тариф/лимиты, supply minimum и SLO thresholds по фактическому baseline. В плане нет обещанной даты запуска/выручки/охвата.

### A Readiness и серверные switches

1. Matrix каждой ячейки: owner, code SHA, tests, legal/provider agreements, tariffs version, supply/training, support/returns, retention, rollback evidence.
2. effective enable fail-closed из DB owner switch audit, not env-only client hide. Stale cache не включает деньги/категорию.
3. Kill switch levels: new quotes/orders/offers/assignments/payments; active fulfillment/refund/reconcile safe continuation по K3. Не блокировать history, допустимый offline snapshot и восстановление уже принятого заказа общим early return на экране/route. Offline не разрешает новые effects: после reconnect сервер заново проверяет текущие gates/auth; новые списания не маскируются под recovery.
4. Canary cohort/limits, rollback triggers, phase approvals immutable; никаких auto-expansion по количеству downloads.
5. Mixed Android/server/PWA version compatibility; minimal upgrade before unsupported action.

### B Закрытый стенд Android

Не показывать незапущенную capability как доступную; старые активные заказы остаются видимыми после остановки/upgrade. Пользователь понимает реальные границы.

### B Закрытый стенд PWA

Та же матрица и offline snapshot staleness. Landing/help/legal обещания синхронны фактическому availability, не охватывают всю стратегическую карту.

### C Решение о пилоте

Только после CP-24B/C/D доказательств и применимых LG, владелец отдельно разрешает ограниченный pilot, бюджет/гео/окна/supply/support. Этот текст не enable.

### D Расширение

Каждая новая ячейка capability×geo×transport повторяет readiness+scope tests; нет автоматического охвата соседнего региона после одного города.

### Межмодульный договор R2

K3 command×gate matrix обязательна для rollout. Stop admissions не запрещает verified active return/storage/refund reconcile. Но eligibility exception никогда не обходит revoked auth: recovery через reverified actor/support. Before-pilot K-CUTOVER и storage/SOP gate выполнены; after-toggle legacy workers не обходят state fence.

### Решение владельца OD-2026-10-25-01

On-route platform fee=0 до проверки minimum/ladder, при допустимой согласованной оплате водителю; dedicated commission применяется и к scheduled работе. Zero fee не paid услуги/товара и не бесплатная перевозка. Не удерживать скрытый service fee. DRIVER_SET_PRICE с ADVISORY_ONLY советом платформы утверждён OD-04; dedicated ставки отдельно OWNER_PENDING, применение в production требует своих gates. Бесплатные режимы имеют acquisition/subsidy costs, нет гарантии самоокупаемости.

### Уточнение R4

Ride bridge launch дополнительно требует M6R и общего guard во всех RS booking/edit writers; no old-writer bypass. Economic gate F14A отдельно по city/rural/intercity, observed costs vs assumptions; positive contribution/approved acquisition budget и pilot, не автоматическая окупаемость. Zero on-route fee не меняется.

### Операционный gate R4

До пилота зоны/категории доказаны конкретный возвратный адрес/принимающий/часы, связь, плательщик согласованных recovery расходов и реально доступный безопасный запасной transfer/storage путь. Само обещание «пишите в поддержку» не закрывает gate. Партнёры хранения только после договоров, проверенной доступности и SOP; собственного склада не требуется автоматически. Еда/уникальные документы требуют отдельных допустимых условий. Текущий статус NOT_OPERATIONALLY_READY, подтверждения договоров нет.

### API и события

capabilities version and rollout_reason; current order allowed_actions independent new-business disable. Admin enable requires readiness decision ref+actor session per accepted policy.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

Изменения флагов через existing owner switches journal, no second registry. Migration only approved D2 plan with backup/restore evidence; rollback compatible code, snapshots retained.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Работаем на этих направлениях…». «Новые заказы временно закрыты, текущая доставка продолжается». «Этот вид доставки подключим позже» без обещанной даты.

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Not launched/open no supply/pilot/degraded/paused/draining/disabled, closed hours/route risk, upgrade required.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

C9 задаёт связанный ETA-договор: правильная target stop/leg, отдельные promised/current/actual и elapsed/paid wait; route/ready/handoff изменения инвалидируют старый прогноз, offline/timezone не омолаживают данные. Приёмка SIM-37/ETA-N01…22 применима к затронутому сценарию, без раскрытия чужих остановок.

## 7. Тесты

SIM-30/33: mixed writer запрещает bridge enable, rollback хранит reservations; отрицательная экономика → явное no-launch/scheduled option, не hidden fee.

SIM-28/F1A: on-route paid driver + platform fee 0 при любом minimum/tier, scheduled dedicated не zero branch, no hidden fee, quote/statement/net parity; mode change только consent.

R2-GATES: new accept denied при pause, active return разрешён fresh auth, украденный token denied; provider outage допускает verified webhook/reconcile, но не новые неизвестные charges.

**REV-STOP-01 → K3, SIM-15/T-OFF-01/T-OPS-01:** toggle OFF в searching/assigned/collected/returning/payment-unknown; проверить server+Android+PWA. New create/claim/assign/payment запрещены даже со stale флагом и offline очередью. История и существующий recovery остаются доступны законному actor; terminal не симулируется. Logout/revoked auth не обходятся ради recovery. Reconnect старого payment intent сначала reconcile, не новый charge; verified callbacks/refund только в разрешённой модели K3/F12. Не смешивать новое assignment с подтверждённым recovery handoff по уже согласованным K2 основаниям.

SIM-15; turn off during searching/assigned/collected/returning/payment pending; stale client flags, owner switch auth, deploy rollback provider callback, no new orders while draining; production dry-run safe no real transactions.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Для pilot все обязательные base courier E2E пройдены, relevant capability profile пройден; financial/custody/security P0 unresolved=0, остальные риски явно утверждены, не silently waived. Для полного продукта все заявленные capability и географии закрыты своими evidence.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Нельзя считать поэтапный пилот полным агрегатором. Code green не legal/provider/supply ready. Возврат к старому auto-returned worker запрещён как rollback.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
