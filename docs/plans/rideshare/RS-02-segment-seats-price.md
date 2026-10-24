# RS-02 — Места и цена по участкам

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — тарифы (новый расчёт цены брони) и миграция рабочей базы (3 столбца `ride`) |
| Зависит от | — (от него зависят RS-03, RS-05, RS-09, RS-12, RS-15) |
| Двигает сценарии приёмки | W1-E2E-01, W1-E2E-02, W1-E2E-03 (`docs/audit-traceability/rideshare-scope.md`) |
| Зона файлов | `backend/app/`: `ride_seats.py` (новый), `models.py`, `schemas.py`, `services.py`, `errors.py`, `request_segments.py`, `safety_logic.py`, `routers/{bookings,rides,requests,medical}.py`; `backend/alembic/versions/cc_ride_segment_seats_<дата>.py` (новый); тесты — п. 7. `android/app/src/main/java/com/yuldash/app/`: `Domain.kt`, `MainActivity.kt` (только `toUiRide`), `YuldashApp.kt` (только вызов брони), `data/{ApiClient,DriverBookings,RequestRouteDtos}.kt`, `PickupStopRules.kt`, `BookingSeatCount.kt`, `BookingActiveTripScreen.kt`, `CreateRideStops.kt`, `CreateRideScreen.kt`, `ProfileScreen.kt`, `RidesRequestsChatScreens.kt`, `RequestRouteUi.kt`, `RideshareLegsLoad.kt` (новый). `webapp/src/`: `api/{rides,driver,bookings,requests}.ts`, `utils/pickupStops.ts`, `components/{RideSheet,RideCard,RideEditActions,DriverPassengers}.tsx`, `screens/{CreateRideScreen,DriverCabinetScreen,RequestsFeedScreen}.tsx`. `docs/{decisions,tasks,architecture}.md` |
| Источник | исследование `master/part-001.md` п. 6, 9, 10, 12, 13, 29; решение Q12 б (24.10) |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Было.** Водитель едет Сибай → Ургаза → Акъяр, 3 места. Трое соседей берут Сибай → Ургаза — поездка «полная», и Булат из Ургазы в Акъяр её не находит, хотя после Ургазы машина пустая. И все платят до Акъяра.

**Стало.**
- Водитель пишет цену до каждой остановки: «до Ургазы — 300 ₽», весь путь — 500 ₽ (или одну цену на всё, как сейчас). Булат платит 200 ₽ (500 − 300); итог = цена участка × места.
- Место занято только на своём участке: Булат видит «на твоём участке свободно 3 места». Отмена и «не пришёл» освобождают только свой участок.
- Водитель видит, кто где садится и выходит, за сколько, и загрузку: «Сибай → Ургаза: занято 3 из 3». С бронями цену можно только снижать.
- Старые поездки и брони — как раньше. Переднее место — одно на весь рейс.

## 2. Что уже есть в коде
- `backend/app/models.py:368-370` — один счётчик `seats_left`; `price` — **целые рубли** (`routers/rides.py:246`: 0..100 000 ₽).
- `models.py:409` `waypoints` через « | » (`ride_stops.py:31` максимум 4, `:141` `parse_stops`); `:585-587` `Booking.pickup_stop/dropoff_stop` (`""` — начало/конец, `NULL` — не указано); `:512-532` индекс «одно живое переднее на рейс».
- `routers/bookings.py:478` `FOR UPDATE`; `:539` проверка мест; `:574` `ride.price * body.seats`; `:578-585` `pay_amount`; `:610-618` условный UPDATE `seats_left >= N`; `:1535-1538`, `:1756-1759` возврат мест; `:302` `_pickup_refusal` с кодом; `:868-879` квитанция; `:1858-1880` `/driver/bookings` без мест и цены.
- `routers/rides.py:175-194` `_recent_twin_ride`; `:321-322` `Ride(**dump)`; `:340` серия; `:399-414` цена с бронями только вниз; `:447-466` `seat_cap`; отсев `seats_left > 0` — `:529`, `:924`, `:1266`, `routers/medical.py:64`; `route_fit` `:589-602`; кэш `:515`, `:623`; `price_hint` `:642-677`.
- `request_segments.py:25-28` отсев `seats_left < req.seats`; `routers/requests.py:1318-1327` списание при принятии своего рейса, `:1465` подбор, `:579-596` `_on_route_scope`, `:702-707` `matched_*`.
- `services.py:1569-1605` `ride_out_with`; `safety_logic.py:491-502` — пауза пассажира отменяет брони, места **не возвращает** (дыра, чиним здесь); `errors.py:11` `herr` без кода.
- Android: `BookingSeatCount.kt:25-26` сумма по умолчанию `ride.price * seats`, `:69-70` `kopToRub(price * selected * 100)`; `YuldashApp.kt:1412` шлёт её как `payAmount`; `ProfileScreen.kt:2937`, `:2946` «всего − свободно». Сайт: `RideSheet.tsx:113-116`, `:261-268` от `seats_left`/`price`; `DriverCabinetScreen.tsx:234`, `:464`.
- Цены до остановок и места по участкам — **не нашёл**.

## 3. Что сделать
**Термины.** Точки: P0 = `from_city`, P1…Pn = `parse_stops`, Pn+1 = `to_city`; участок i = Pi → Pi+1. Бронь занимает `[a, b)`: посадка `""`/`NULL`/незнакомая → 0, высадка → n+1 (старая бронь = весь рейс).

**Почему так.** Правда — живые брони; в строке `ride` — снимок свободных мест по участкам: лента не ходит в базу на каждую поездку (`test_no_query_per_item`). Запись — условный UPDATE «снимок не изменился» (CAS), как нынешний `seats_left >= N`: держит PostgreSQL (плюс `FOR UPDATE`) и SQLite. Таблица участков — лишние join'ы.

### Сервер (`backend/`)
1. **`Ride`:** `stop_prices_json: str = ""` (`{"Ургаза": 300}`, ключ как в `parse_stops`, цена от начала в рублях); `leg_free: str = ""` («3,0»; пусто или длина ≠ n+1 — «не заведён»); `seats_free_max: int = 0`. `seats_left` = min (весь путь — осторожно для старых клиентов).
2. **`app/ride_seats.py`** — единственная дверь для мест:
   - `span`, `legs_free` (не заведён → `[seats_left]*(n+1)`), `free_on` = min, `max_free`, `occupancy` (из броней), `recompute`;
   - `take(session, ride, span, seats, extra_where)`: ≤ 3 попыток — перечитать (`populate_existing`), не заведён → снимок из `occupancy`, не хватает → `False`; `UPDATE … SET leg_free, seats_left=min, seats_free_max=max WHERE id AND leg_free=:old AND extra_where`; 0 строк при прежнем `leg_free` → `False`, иначе повтор;
   - `release` — тот же CAS, `+seats` на `[a, b)` до `seats_total`; не заведён → `recompute`;
   - `any_free_sql(n)` = `or_(seats_left >= n, seats_free_max >= n)` — заполнять базу не нужно;
   - `segment_price`: цен нет → `price`, иначе `cum(высадка) − cum(посадка)` (`cum(начало)=0`, `cum(конец)=price`);
   - `validate_stop_prices`: пусто → `{}`; 400 `stop_prices_need_price` (`price == 0`), `stop_prices_count` (число ≠ частям `waypoints`), `stop_prices_order` (нужно `1 ≤ p < price` и строгий рост).
3. `errors.py`: `herr(status, ru, ba, *, code=None, **extra)`; старые вызовы не меняются.
4. **Публикация:** `RideIn.stop_prices: Optional[list[int]]` (по частям `waypoints`) → проверка → JSON; не в `dump`, в серию — копия; `_recent_twin_ride` сверяет JSON отдельно (иначе дубль).
5. **Бронь** (`BookIn.expected_price` — цена за место на экране): `free_on(span) < seats` → 400 `seats_not_enough` (+`free`); `seg ≠ expected_price` → 409 `price_changed` (+`price`); `total = seg * seats`; цены остановок есть, `expected_price` нет (старое приложение шлёт полную сумму) → `pay_amount = total`; UPDATE `:610-618` → `take(..., extra_where=[~exists(живая бронь пассажира)])`, `False` → `rollback` → `_retry_after_booking_race` → 409 `seats_taken_now`. Повтор, откат переднего — прежние.
6. **Отмена, неявка, пауза** (`:1535`, `:1756`, `safety_logic.py:491-502`) → `release(span(брони))` после смены статуса; замки Ride → Booking прежние.
7. **Заявки:** `segment` — дешёво `max_free`, после участка — `free_on`; `_on_route_scope` — `max(seats_left, seats_free_max)`; принятие `:1318-1327` → `take(..., extra_where=[прежние условия])`, цена брони — `price_on_table × seats` (торг важнее); `:1465` → `any_free_sql(req.seats)`; в ленту заявок — `matched_segment_price`.
8. **Выдачи:** `:529`, `:924`, `:1266`, `medical.py:64` → `any_free_sql()`; цикл `:589-602` читает ещё `seats_left, seats_free_max, leg_free`: участок однозначен → `free_on ≥ 1`, иначе `max_free ≥ 1`. Кэш → `rides:active:v3`.
9. **`RideOut`** (без запросов): `stop_prices: [{stop, price}]`, `seats_by_leg`, `seats_on_segment`/`segment_price` рядом с `matched_*` (`rides.py:616-620`, `/rides/near`); `seats_booked` — только `/driver/rides`; `/driver/bookings` + `seats`, `price`. Сырые `leg_free`/`stop_prices_json` не отдавать.
10. **Правка:** `RideEditIn.stop_prices` (`None` — не трогать, `[]` — одна цена). С живыми бронями `price` не растёт **и** у каждой брони новая `segment_price` ≤ старой, иначе 409 `price_up_with_bookings`; `b.price = новая × seats`, потолок `pay_amount` как `:412-413`. Места: `booked_seats > cap` → max занятости участка; `seats_left = …` → `recompute`.
11. **`price_hint`:** `stops` (≤ 4) → `stops: [{stop, distance_km, avg, count}]`; расстояние — по своему справочнику сёл, не Яндекс (платно).
12. **Переднее** — как сейчас (барьер — индекс базы); «сзади двое» держится: на участке мест ≤ `seat_cap`.

### Android (`android/`)
1. `Ride` (`Domain.kt`) + `stopPrices`, `seatsByLeg`, `seatsOnSegment`, `segmentPrice`, `seatsBooked`; разбор в `ApiClient.kt`, `toUiRide` (`MainActivity.kt:750`).
2. `PickupStopRules.kt`: `segmentPriceRub`, `seatsOnSegment` по формуле сервера.
3. Шторка: участок до числа мест (поменять `:653` и `:700`); `BookingSeatCount` и сумма по умолчанию — от участка; «Итого» — `AnimatedContent` (`CanonMotion`), `kopToRub(rub * 100)`; `expectedPrice` при ценах остановок; `price_changed` → перечитать поездку.
4. Публикация: переключатель «Цена до каждой остановки» (включён при остановке и цене > 0), поле у каждой (`CanonFieldShape`, `AnimatedVisibility`), «Поделить по расстоянию» (≥ 48dp, до 10 ₽); ошибки — до отправки.
5. Правка (`ProfileScreen.kt:1748`) — цены остановок; кабинет: «1 место · 300 ₽» у брони, `RideLegsLoad(ride)` на активном рейсе, `:2937`/`:2946` — `seatsBooked`.
6. Лента (`RideCard`, `RidesRequestsChatScreens.kt:880`): цена и места участка или «цена зависит от участка»; отклик (`RequestRouteUi.kt`) — `matched_segment_price`. Нового экрана нет.

### Сайт (`webapp/`)
Те же шаги: типы `api/*.ts`; формула в `utils/pickupStops.ts`; `RideSheet.tsx` (места, «Итого», `expected_price`); `CreateRideScreen.tsx` (поля, черновик); `RideEditActions.tsx`; `DriverCabinetScreen.tsx` (`seats_booked`, загрузка); `DriverPassengers.tsx`, `RideCard.tsx`, `RequestsFeedScreen.tsx`.

## 4. База данных
`cc_ride_segment_seats_<дата>`, `down_revision` — текущая голова (на 24.10 `cb_sos_retry_intent_20261021`; у RS-03/05/06/14 тоже миграции — не ветвить); образец `ca_booking_segments_20261018.py`.
- **upgrade:** `ride.stop_prices_json`, `ride.leg_free` — `VARCHAR NOT NULL DEFAULT ''`, `ride.seats_free_max INTEGER NOT NULL DEFAULT 0`; данные не трогаем (снимок — при первой записи мест). SQLite-демо добавит сам (`db.py:64`).
- **downgrade:** активным рейсам `seats_left = max(0, seats_total − Σ мест живых броней)` (`CASE` для обеих баз), затем снять столбцы; старый код — только после downgrade.

## 5. Надписи
BA пишет исполнитель черновиком в `docs/tasks.md` («Переводы на проверку»); всё через `appText(ru, ba)`.
- «Цена до каждой остановки» · «до {остановка}, ₽» · «Поделить по расстоянию» · «Цена зависит от участка» · «{N} ₽ за место» · «Загрузка по участкам» · «занято {x} из {y}» · «Выбери, где садишься и выходишь — от этого зависят цена и места».
- `stop_prices_order` «Цена до каждой следующей остановки должна быть больше, а до конца маршрута — больше всех»; `stop_prices_need_price` «Сначала укажи цену до конца маршрута»; `stop_prices_count` «Цен больше или меньше, чем остановок — проверь».
- `seats_not_enough` «На твоём участке свободно мест: {N}»; `price_changed` «Водитель изменил цену: теперь {N} ₽ за место. Проверь и забронируй ещё раз»; `price_up_with_bookings` «С бронями цену можно только снижать — у кого-то из пассажиров она бы выросла».

## 6. Состояния экрана
Публикация: подсказка грузится — спиннер, нет сети — «Поделить» скрыта, ошибка — под полем. Шторка: участок не выбран — подсказка вместо «Итого»; мест нет — «На твоём участке мест нет» + «Посмотреть другие»; нет сети — «Повторить». Длинный BA — поле цены под названием; 320dp; `Canon*`; ≥ 48dp.

## 7. Тесты
**Сервер** (`ruff check app/ --select F`, `pytest`):
- `test_segment_price.py` (новый): таблица (без цен → `price`; 300/200/500; NULL/«»), отказы с кодами, повтор публикации, серия.
- `test_segment_seats.py` (новый): трое до Ургазы → Ургаза→Акъяр бронируется, весь путь — `seats_not_enough`; отмена/неявка/пауза — свой участок; старые бронь и рейс — по-старому; лента, «Ближайшие», подбор; `price_changed`; полная `pay_amount` старого клиента; правка; `back_max_two`; заявка; миграция туда-обратно (`test_pickup_stop_booking.py:509`).
- `test_seats_invariant.py`: `leg_free[i] = total − Σ живых на участке i`. `test_last_seat_race.py` (`_book_together`) и `test_segment_seats_race_pg.py` (новый, только PostgreSQL): пересекающиеся участки — одна 200, непересекающиеся — обе. `test_request_bound_accept_seats.py`: «чужая бронь» — настоящая, не `seats_left=0`.
- **Мутации** (каждая краснит тест): без `leg_free = :old` в CAS; `span` = весь рейс; `release` на все участки; `segment_price` = `price`; без сверки `expected_price` и цен броней при правке.

**Android** (`:app:testDebugUnitTest`, `:app:assembleDebug`): `SegmentPriceTest.kt` (новый, та же таблица), `BookingSeatCountTest.kt`, `CreateRideStopsTest.kt`; сторожа `Money/Canon/Color/SpacingRatchet…GuardTest` зелёные. **Сайт:** `npm run check` (`webapp/tests/pickup-stop-test.mjs` — та же таблица), `npm run build`.

## 8. Приёмка
Агент другой «семьи» (Q9), по умолчанию «не принято».
1. Вывод `ruff`, `pytest`, `npm run check`, `npm run build`, `:app:testDebugUnitTest`, `:app:assembleDebug` — зелёные, приложены; мутации из п. 7 — красные.
2. Код: места пишет только `ride_seats.py`; в выдачах нет `seats_left > 0`; старый клиент не получает `pay_amount` выше цены участка.
3. Эмулятор (вторая сторона — сайт, Q11), RU и BA × светлая и тёмная:
   - публикация Сибай → Ургаза → Акъяр, 3 места, 300/500 ₽; «Поделить» и ошибка цен понятны;
   - трое (сайт) берут до Ургазы по 300 ₽; поиск Ургаза → Акъяр: «200 ₽ за место», «на твоём участке 3»; 2 места → «Итого 400 ₽», квитанция 400 ₽;
   - водитель видит загрузку и брони; отмена одного до Ургазы освобождает только первый участок;
   - цена вверх при бронях — отказ, вниз — пуш; рейс без цен остановок — как раньше; длинный BA не ломает узкий экран.
4. Двигает W1-E2E-01 (участок → цена → квитанция), -02 (свой рейс по заявке), -03 (последнее место, отмена, неявка); остаются RS-16, W1-E2E-07 (Q11), BA носителем. Статус — только `docs/audit-blocks.md`.

## 9. Риски и границы
- **Деньги:** новый расчёт цены брони — до «да» только в ветке; онлайн-оплаты нет (Q5); единица — рубли, как `Ride.price`.
- **Старые приложения:** рейс с местом на части пути покажут «0 мест»; бронь без участка — весь рейс, полная цена.
- **Приватность:** `seats_by_leg` — загрузка без имён, как «осталось N мест»; пуш «Новая бронь» — без села.
- **Не входит:** переднее по участкам, фильтр мест (RS-12), правка остановок (RS-15 переспрашивает цены и зовёт `recompute`), точки встречи (RS-03). Общие файлы (`models.py`, `ApiClient.kt`, `MainActivity.kt`, `YuldashApp.kt`, `ProfileScreen.kt`) — не параллельно с RS-03/05/09/12/15.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 2026-10-24 | не начато | — | — | план написан |
