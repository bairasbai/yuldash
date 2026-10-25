# CP-17 — Межгород и запланированные доставки

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | региональный запуск/ЭПД/договоры/провайдеры/сроки/тарифы; LG-07/11 |
| Зависит от | CP-03/04/05/06/07/08/09/10/12; CP-16 только для cargo вариантов; CP-14/F12 для scheduled authorization |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 01,02,03,04,07,08,10; corridor additions |
| Зона файлов | backend/app/geo.py/config.py/routers/courier.py/models.py; Android/PWA delivery forms/courier work zones; proposed corridor scheduling module |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Из села можно заранее отправить вещь в райцентр или соседний регион, видя реальные окна и обратный путь, а не обещание экспресса там, где нет курьера.

## 2. Что уже есть в коде

zone_allows city/district/intercity/regions/direction; courier default межгород 40 км, max_trip_km500, delivery deadline день. Это не доказательство покрытия всей республики и соседей.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Corridor windows с supply reservations, местное время каждой точки, route constraints и обратная логистика. On-route остаётся отдельной моделью, не забор dedicated курьером без согласия.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Published corridor/service window/capacity и cutoffs; schedule reservation не assigned без подтверждения.
2. Дедуп occurrence IDs, перенос окна по amendment, late/no-show policy.
3. Quote trip distance и eligibility во всём коридоре; 500 км limit пересматривает владелец по расчётам, не удалить guard молча.
4. Weather/road closure case, безопасная остановка и return route; не автоматическое платное repricing.
5. ЭПД/транспортные документы по legal matrix; internal OTP не заменяет обязательные документы.

### B Android

Показывать дату/время+местный часовой пояс, cutoffs и ожидаемую доставку диапазоном. Offline passport включает только минимальный активный маршрут.

### C PWA

Schedule/calendar parity, пустая выдача future days честно, подписка на появление corridor только по отдельному consent и outbox; не auto-book.

### Региональная готовность и деньги

Городской on-demand включается только с измеренной доступной supply, не из наличия polygon. Село/райцентр: явные дни и окна, точный pin/дверь+ориентир, cutoff и подтверждённая capacity; нет подходящего рейса → unavailable, не ложное «курьер едет». Quote учитывает только заранее согласованные road/weather/long-pickup/backhaul components. Перед pilot реальный маршрут и обратный путь, связь/offline, доступная поддержка/хранитель и unit economics трёх сторон проверяются отдельно; сейчас NOT_EXECUTED. F12 auth expiry/reauthorization не даёт обещать оплату после истечения резерва.

### Отдельные продукты on-route и dedicated

Service_type в scope/C2/F14 обязателен в UI/quote/offer/eligibility/dispatch. «Передать по пути» показывает собственное направление попутчика, окно и допустимый крюк; «Заказать курьера» — отдельную работу с её условиями/ценой. Профессиональный допуск не наследуется от rideshare. Нет silent substitution при нехватке supply; смена режима требует согласия и нового договора. OD-2026-10-25-01: on-route водитель может получать оплату, комиссия Юлдаш 0; это не бесплатная перевозка. Dedicated, включая scheduled, комиссионный. OD-04 утверждает DRIVER_SET_PRICE с ADVISORY_ONLY советом платформы; конкретные dedicated rates и legal/provider implementation остаются gated. No minimum/tier/hidden service fee для on-route.

### Уточнение R4

On-route опубликованная собственная поездка 11 и dedicated inter-village — разные продукты даже на одном коридоре. Intersettlement on-route initial policy ещё owner gate; numeric detour/frequency thresholds не установлены. Scheduled dedicated остаётся комиссионным.

### Решение OD-04 и договор цены

DRIVER_SET_PRICE, ADVISORY_ONLY, sender/payer consent до create; request/proposal → F2 quote → single-use order по R4A в 11. Рекомендация не обязательный диапазон и не fallback сумма. Ride meeting по умолчанию, door detour только prior consent/route-time context и RS guards. Никакой скрытой комиссии; SIM-36 проверяет stale proposal, duplicate create и mode switch.

### API и события

Scheduled order window UTC+IANA, corridor_id/version, occurrence key, supply confirmation state. Route change creates new terms_version/quote.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

Corridor policy/capacity via M2/M6; schedule fields M3, reservation/occurrence M6; дополнительная revision только после обновления D2 с legacy deadline preserved as date-only unknown window. No invented exact pickup time.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«В ближайший рейс, когда найдётся перевозчик». «Время по месту забора». «На этом направлении пока нет подтверждённого курьера».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Future/unconfirmed/confirmed/missed cutoff/route paused/weather delay/return unavailable/cross-timezone.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

C9 задаёт связанный ETA-договор: правильная target stop/leg, отдельные promised/current/actual и elapsed/paid wait; route/ready/handoff изменения инвалидируют старый прогноз, offline/timezone не омолаживают данные. Приёмка SIM-37/ETA-N01…22 применима к затронутому сценарию, без раскрытия чужих остановок.

## 7. Тесты

SIM-29/32: published corridor/window matches, parcel-only без пассажиров допустим, intracity/geography не единственный classifier, same route dedicated не hidden free lane.

SIM-28: on-route/dedicated lane, UI/quote/fee scope parity, запрет тихой замены и неправильного допуска/процента.

SIM-10/13; midnight/timezone, leapdate, stale schedule, courier capacity overlap, interrupted road/region border, return cost shown before order, no exact route. EPD offline contingency reviewed.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Каждый activated corridor имеет исполнителя/поддержку/return SOP/времена/допуски. Geo strategy не выдаётся за coverage now.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не включать все 7 субъектов одним flag; не считать «по пути» гарантированной экспресс доставкой. Резерв passenger seats RS02 не parcel capacity.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
