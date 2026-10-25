# CP-08 — Назначение без гонок и перегруза

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | алгоритм приоритета/санкций, миграция, геоданные и новые service promises |
| Зависит от | CP-03/04/05/06/07; общий availability policy modes |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A1 generic M6 → A2 Ride bridge M6R после actual RS; B1/C1 generic, B2/C2 publish/search после A2 → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 02,07,08 |
| Зона файлов | backend/app/routers/{courier, parcels}.py, geo.py, models.py, taxi_worker.py; новые backend/app/courier_dispatch.py/tests/test_courier_dispatch_v2.py; Android/PWA CourierScreen |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Заказ получает подходящий свободный курьер, а не тот, кто случайно первым обновил большую ленту. Курьер не принимает несовместимые обещания на одно время.

## 2. Что уже есть в коде

courier.py:1101–1190 id-desc feed, FEED_MAX до geo filters; parcels.py:1154–1245 rowlock/CAS; parcels.py:831–902 best-effort broadcast. Сентябрьское docs/tz/TZ-dispatch-2026-09.md:201–232 уже описывает nearest-feed gap.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Расширить существующий feed/claim до versioned offers, lease, generation, capacity reservation и shortage policy. Не удалять попутный ручной claim без совместимого пути.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Eligible set по зоне/грузу/транспорту/окнам/занятости и свежему GPS; фильтровать до pagination/limit.
2. Детерминированная rank policy: estimated approach/time, capability, capacity, fair rotation; не скрытый рейтинг-штраф.
3. Offer C5 lease expiry и revoked cleanup; accept atomic order+capacity locks fixed order.
4. No supply: bounded search window/новое окно/отмена без fake assignment; не auto-increase price.
5. Reassign до custody; после custody только CP-18 handoff/support. Общие taxi/rideshare commitments включены.

### B Android

Оффер с expiry/server clock, net и return terms; delayed click после замены offer получает безопасный conflict. Геоправо denied → честный ограниченный режим.

### C PWA

Лента server-sorted/paginated, last updated/geo quality; browser hidden не обещает background dispatch. Accept error не удаляет всё молча.

### Межмодульный договор R2

K4 offer DTO содержит offer_id, dispatch_generation, lease_token/expiry, state_version, terms_version, item_set_hash, quote/remuneration hashes. Accept передаёт именно эти issued fields; assignment_id/generation впервые выдаёт сервер после commit. Старый токен другой generation/предметов/цены не применим. Handoff использует K2 atomic old/new slots и capacity, не release+accept двумя транзакциями.

### Уточнение R3

Ручной попутный выбор сохраняет GET open requests → claim_on_route с подписанным preview; K4 внутри одной транзакции создаёт self-offer и вызывает тот же accept service. Stored mode определяет guards. Legacy без durable intent/preview требует upgrade, но manual on-route capability не удаляется.

### Отдельные продукты on-route и dedicated

Service_type в scope/C2/F14 обязателен в UI/quote/offer/eligibility/dispatch. «Передать по пути» показывает собственное направление попутчика, окно и допустимый крюк; «Заказать курьера» — отдельную работу с её условиями/ценой. Профессиональный допуск не наследуется от rideshare. Нет silent substitution при нехватке supply; смена режима требует согласия и нового договора. OD-2026-10-25-01: on-route водитель может получать оплату, комиссия Юлдаш 0; это не бесплатная перевозка. Dedicated, включая scheduled, комиссионный. OD-04 утверждает DRIVER_SET_PRICE с ADVISORY_ONLY советом платформы; конкретные dedicated rates и legal/provider implementation остаются gated. No minimum/tier/hidden service fee для on-route.

### Уточнение R4

11 — canonical Ride bridge: optional parcel toggle + capacity block в существующем CreateRide; published Ride versions, parcel-only без passenger booking, independent route search, same self-offer claim. A1/M6 generic → A2/M6R Ride integration после accepted RS; B2/C2 оба publish/search clients после A2. Joint Ride → Booking → Parcel lock/CAS защищает seats/baggage/parcel units, не второй seat ledger.

### Решение OD-04 и договор цены

DRIVER_SET_PRICE, ADVISORY_ONLY, sender/payer consent до create; request/proposal → F2 quote → single-use order по R4A в 11. Рекомендация не обязательный диапазон и не fallback сумма. Ride meeting по умолчанию, door detour только prior consent/route-time context и RS guards. Никакой скрытой комиссии; SIM-36 проверяет stale proposal, duplicate create и mode switch.

### API и события

GET offers, POST offers/id/accept(lease_token, expected_version, generation), reason codes. Offer identity≠order identity; server expiry проверяется при commit.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M6 offers/leases/capacity расширяет M1 assignment/item slot, unique active item assignment, indexed eligible search и expiry. Legacy accepted переносится без повторного notify/fees; prepared offers не означают custody.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Предложение истекло». «Заказ уже принял другой курьер». «Нет подходящей доставки по твоему направлению».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Fresh/stale offer, no supply, expired/revoked, accept unknown, capacity busy, location stale, paused eligibility.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

R4.1 A-R4-N01/N05: R5A в 11 задаёт nonreleased occupied predicate и unknown-span admission block; recovery_required не освобождает physical hold. Отсутствующая policy/поле сохраняет legacy passenger category/visibility/Booking, explicit parcel-only DTO отдельно. Проверить SIM-30 дополнительные обе очередности и already-reserved next leg; old DTO/backfill не меняет flags/живые обязательства.

## 7. Тесты

SIM-29/30/31: пассажирский багаж vs parcel accept, несколько independent parcels, parcel-only выдача при no seats, потеря publish/claim response, trip cancel после pickup. Две PG sessions, не mock.

SIM-28: on-route/dedicated lane, UI/quote/fee scope parity, запрет тихой замены и неправильного допуска/процента.

R3-CLAIM: два claims; claim/cancel обе очередности; lost response same key; stale route/net; professional order не проходит бытовой guard и self-offer не создаёт второй receipt.

R2-OFFER: GET DTO → accept без выдуманного client assignment_generation; expiry/old dispatch generation/changed net; two offers across modes не превышают capacity.

SIM-01/02/08; PG 50 concurrent accept, same courier accepts two incompatible jobs, expiry boundary, suspend during accept, release vs pickup, FEED_MAX under sparse eligible set. Mutation: remove capacity lock — red.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Одно assignment и непревышенная capacity доказаны PG races; отказ не ухудшает рейтинг/заработок без согласованного правила. Сентябрьский dispatch scope согласован, не два matcher.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не считать presence/GPS гарантией доступности. Не переназначать вещь кнопкой «снять курьера». Не использовать нейтральный choice как скрытую трудовую дисциплину без LG-10.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
