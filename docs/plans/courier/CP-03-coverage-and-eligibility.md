# CP-03 — Территории транспорт и допуски

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | новые территории/категории, документы/ПД, юридические требования, миграция |
| Зависит от | CP-01/02; RS owner switches после проверки; LG-01/02/08/11 для включения |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 02,07,08,09 |
| Зона файлов | backend/app/geo.py, config.py, models.py, routers/courier.py; Android CourierOnboardingScreen.kt/CourierScreen.kt; PWA api/courier.ts, CourierOnboardingScreen.tsx/CourierScreen.tsx; новые backend/app/courier_capabilities.py |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Клиент узнаёт до заказа, можно ли везти его груз по этому адресу, а курьер получает только работу под свой транспорт и действующий допуск.

## 2. Что уже есть в коде

geo.py:650–688 zone_allows общий taxi/courier. car|cargo в models.py:2011–2056. courier.py:1108–1109 unknown geo fail-open. PWA zone contract отстаёт от Android district/intercity/regions.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Каталог семи субъектов и capability matrix без обещания доставки всюду. Добавить пеший/вело/мото как отдельные допуски; юридический статус независим от транспорта. Unknown geography fail-closed только для новых заявок.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Версионировать покрытия/коридоры и правила товара/веса/габарита/оборудования.
2. Separate status проверки человека, транспорта, налоговой/договорной роли, equipment, goods и expiry.
3. Нельзя self-approve или автоматически расширить car на cargo/food; expired/revoked закрывает новые offers.
4. Активная custody при утрате допуска направляется support, не сбрасывается.
5. Выровнять legacy work_* zones с новым DTO через migration adapter, не менять taxi молча.

### B Android

Онбординг под выбранный транспорт и тип работы; понятные pending/rejected/expiry/action needed. Не требовать водительские документы пешему по инерции; чувствительные данные минимально.

### C PWA

Убрать старый city|intercity|region-only DTO и согласовать district/flags/versions. Изменение зоны показывает сохранено только после server outcome.

### Межмодульный договор R2

K3 различает expiry eligibility (нет новых offers, безопасные bounded resolution действующей вещи через свежую auth) и compromise/revoked session (все команды этой сессии forbidden). При account takeover recovery выполняет reverified holder/support с audit и минимальными правами, не старый token. Денежная COD custody из F10 сохраняется независимо от блокировки.

### Отдельные продукты on-route и dedicated

Service_type в scope/C2/F14 обязателен в UI/quote/offer/eligibility/dispatch. «Передать по пути» показывает собственное направление попутчика, окно и допустимый крюк; «Заказать курьера» — отдельную работу с её условиями/ценой. Профессиональный допуск не наследуется от rideshare. Нет silent substitution при нехватке supply; смена режима требует согласия и нового договора. OD-2026-10-25-01: on-route водитель может получать оплату, комиссия Юлдаш 0; это не бесплатная перевозка. Dedicated, включая scheduled, комиссионный. OD-04 утверждает DRIVER_SET_PRICE с ADVISORY_ONLY советом платформы; конкретные dedicated rates и legal/provider implementation остаются gated. No minimum/tier/hidden service fee для on-route.

### Уточнение R4

Canonical enum/allowed pairs и legacy mapping в 01; new on_route допускает parcel с Ride bridge11, а документы — item kind. Initial intersettlement restriction предложена владельцу, не окончательная blanket policy. Dedicated внутри города/между сёлами не определяется одной географией. Risk reasons/appeal не доказывают умысел и не используют protected/sensitive traits.

### API и события

capabilities/eligibility version, ошибки ELIGIBILITY_EXPIRED/TRANSPORT_UNSUPPORTED/GOODS_RESTRICTED. При accept повторно проверить версии после lock, а не только login.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M2 CoveragePolicy/CourierEligibility и transport enum expand. Existing approved сохраняется только для прежнего car/cargo scope; неизвестные документы не считать verified. Retention identity отдельно.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Этот тип доставки пока не подключён». «Нужно подтвердить допуск». «Документ истёк, новые заказы недоступны; с текущим поможет поддержка».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Нет покрытия/нет supply/paused/unknown locality/expired eligibility/partial approved capabilities; denied permissions без crash.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

SIM-29/32: allowed pairs rejected при подмене; отсутствие пассажиров/сельский адрес не fraud proof; unknown legacy сохраняет recovery, не новый допуск.

SIM-28: on-route/dedicated lane, UI/quote/fee scope parity, запрет тихой замены и неправильного допуска/процента.

R2-GATE: expired eligibility и revoked auth на одинаковом collected заказе дают разные allowed_actions; старый украденный token не может ни proof, ни cash remittance.

Boundary polygons/settlement aliases, all six neighbours, false-positive cross-border, stale approval/expiry during accept, PWA↔Android DTO parity, IDOR документ. Mutation: unknown geo returns true — negative test red.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Региональная матрица и каждый capability имеют owner/основание/expiry; Android/PWA одинаковы. Не включены medicines/alcohol через free text/other.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Целевой каталог не равен запущенной зоне. НПД не автоматическое разрешение COD/buy-for-customer; физический тип электротранспорта не равен его правовому классу.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
