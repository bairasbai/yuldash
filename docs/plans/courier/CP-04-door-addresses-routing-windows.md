# CP-04 — Точные адреса маршрут и время

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | провайдер геокодинга/маршрута, стоимость сервиса, ПД/миграция |
| Зависит от | CP-02/03; LG-06 |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 01,02,03,08,09 |
| Зона файлов | backend/app/geo.py, routers/{courier, parcels}.py, models.py; Android ParcelsScreen.kt/data/ApiClient.kt; PWA ParcelSendWizard.tsx, parcelForm.tsx; новые backend/app/courier_routes.py и route tests |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Цена и время считаются до нужного дома, а не до центра Сибая. Подъезд, этаж и неудобный доступ не выясняются впервые у двери.

## 2. Что уже есть в коде

ParcelsScreen.kt:1760–1787 settlement geocode,1807–1819 street text; PWA ParcelSendWizard:213–237,253–275 аналогично resolveCity. courier.py:462–507 pickup от work_city, не live GPS; route_metrics используется:671–791.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Подтверждённые door coordinates, confidence, entrance/access options, route quality и местные окна каждой точки. Missing road route не превращается в точную haversine цену.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Geocode candidate → user confirmation → private point; сохранить provider/data version и precision.
2. Route все stops с transport restrictions; distance/time quality, unreachable/rural/ferry/toll/seasonal flags.
3. IANA timezone per stop + UTC windows; validation overnight/DST/provider unavailable.
4. Пересчёт при смене точки, транспорта/опции; stale cache keyed by params/version.
5. GPS подачи отдельно от work_city; undisclosed fallback запрещён.

### B Android

Адресный мастер с подтверждением точки, подъезд/этаж/лифт/контакт/доступ; альтернативный ввод при denied geo. Текст адреса не подменяет подтверждённую точку.

### C PWA

Тот же мастер и confidence state, доступен keyboard/screen reader; отмена map picker сохраняет прежнюю точку только как прежнюю, а не новую.

### Межмодульный договор R2

K4 задаёт границу времени: DB naive UTC как timeutil.py, API требует offset/Z, IANA zone отдельно у каждого stop. Общие amendment/consent и obligation identity появляются M3; изменение адреса/окна требует новый terms+quote, не отдельный будущий M8 API. Предлагаемый новый backend/app/courier_time.py является adapter, не замена общего utcnow().

### Уточнение R3

M1 Stop уже существует: M3 добавляет address/geo/quality/window/zone columns и Leg FK, не создаёт заново stop identity. Legacy logical point не утверждает фактическую дверь, маршрут или время.

### Уточнение R4

Ride route/span/time/vehicle snapshot и частные pickup/dropoff parcel points различаются по 11. Published corridor + подтверждённый handover window, не совпадение строк городов и не centroid proof. Parcel detour не меняет согласованный пассажирский маршрут/окно молча.

### API и события

Quote stops contain address_ref, lat/lon, precision, confirmed_by, timezone, window_start/end; route result route_hash/distance_m/duration_s/quality. Ошибки UNKNOWN_ADDRESS/ROUTE_UNAVAILABLE.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M3 расширение M1 DeliveryStop + новая Leg, private address data. Legacy centroid пометить settlement_precision, не door_verified. Обновлённый адрес → новая terms version, прошлый snapshot immutable.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Проверь точку у нужного входа». «Маршрут пока не удалось рассчитать». «Время указано по месту получения».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Несколько совпадений, точка вне зоны/дороги, нет сети, большой сельский адрес, подъезд недоступен, маршрут fallback с явным предупреждением.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

C9 задаёт связанный ETA-договор: правильная target stop/leg, отдельные promised/current/actual и elapsed/paid wait; route/ready/handoff изменения инвалидируют старый прогноз, offline/timezone не омолаживают данные. Приёмка SIM-37/ETA-N01…22 применима к затронутому сценарию, без раскрытия чужих остановок.

## 7. Тесты

SIM-29/31: участок Уфа → Сибай, private doorpoints, reverse direction/window mismatch; trip edit при активных obligations не сохраняется частично.

R3-STOP: M1 → M3 backfill сохраняет stop/item IDs, неизвестная дверь NULL, proof FK не ссылается на отсутствующую таблицу.

R2-TIME: прежний naive accepted_at сравнивается без TypeError; +05/+03 → один UTC instant; offsetless local отвергнут; SQLite и PG roundtrip старых и новых строк, mock aware input не попадает в legacy worker.

SIM-10; одинаковые город/улица в разных районах; 0 km/очень длинный маршрут; river/закрытая дорога; две timezones; поздний geocode A после B; quote invalidation. Mutation: centroid marked exact — red.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

На синтетических адресах расстояние и время воспроизводимы по route snapshot. Реальный map/door GPS подтверждается поздним runtime gate, не наличием JSON.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не логировать точные координаты/домофон/телефон. Не обещать ETA гарантию без SLA. Не сохранять пользовательские доступ-коды в общих аналитических данных.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
