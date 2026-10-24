# RS-02 — Места и цена по участкам

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — тарифы (по-новому считается цена брони) и миграция рабочей базы (3 столбца в `ride`) |
| Зависит от | — (от него зависят RS-03, RS-05, RS-09, RS-12, RS-15) |
| Двигает сценарии приёмки | W1-E2E-01, W1-E2E-02, W1-E2E-03 (`docs/audit-traceability/rideshare-scope.md`) |
| Зона файлов | см. раздел «Зона файлов» в конце п. 3 |
| Источник | исследование: `master/part-001.md` п. 6, 9, 10, 12, 13, 29; решение Александра 24.10 Q12 б |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Было.** Водитель едет Сибай → Ургаза → Акъяр, в машине 3 места. Айгуль бронирует Сибай → Ургаза, Булат — Ургаза → Акъяр. Приложение считает, что каждый занял место на всю дорогу: после Айгуль и двоих попутчиков до Ургазы поездка «полная», и Булат её не находит, хотя после Ургазы машина пустая. И все платят полную цену до Акъяра.

**Стало.**
- Публикуя поездку, водитель пишет цену до каждой остановки: «до Ургазы — 300 ₽», а до Акъяра (весь путь) — 500 ₽. Не хочет — оставляет одну цену на любой участок, как сейчас.
- Айгуль платит 300 ₽, Булат — 200 ₽ (500 − 300). Итог брони = цена участка × число мест.
- Место занято только на своём участке. Булат видит поездку и «на твоём участке свободно 3 места».
- Отмена или «не пришёл» освобождает место только на участке этого пассажира.
- Водитель видит, кто где садится и выходит, сколько мест и за сколько, и загрузку: «Сибай → Ургаза: 3 из 3 · Ургаза → Акъяр: 0 из 3».
- Когда есть брони, цену можно только снижать: ни у одного пассажира его цена не вырастет.
- Старые поездки и брони работают как раньше. Переднее место — по-прежнему одно на весь рейс.

## 2. Что уже есть в коде
- `backend/app/models.py:368-370` — `Ride.seats_total`, `seats_left` (один счётчик на весь рейс), `price` — **целые рубли** (`rides.py:246` клампит 0..100 000 ₽; `Booking.pay_amount` — «₽», `models.py:609`).
- `models.py:409` — `Ride.waypoints`, остановки текстом через « | »; `ride_stops.py:31` `MAX_STOPS = 4`; `ride_stops.py:141` `parse_stops` — допустимые остановки по порядку.
- `models.py:585-587` — `Booking.pickup_stop` / `dropoff_stop`: `""` — начало/конец, `NULL` — не указано (старый клиент).
- `models.py:512-532` — частичный уникальный индекс `uq_booking_front_seat_live`: одно живое переднее место на рейс.
- `routers/bookings.py:478` `FOR UPDATE` строки поездки; `:539` быстрая проверка `seats_left`; `:574` `total_price = ride.price * body.seats`; `:578-585` `pay_amount` не ниже цены; `:610-618` условный UPDATE `seats_left >= N` (защита и на SQLite); `:1535-1538` и `:1756-1759` возврат мест при отмене/неявке арифметикой в SQL; `:343-360` «высадка после посадки» (`dropoff_stop_order`); `:302` `_pickup_refusal(status, code, ru, ba)` — отказ с машинным кодом.
- `routers/bookings.py:868` — квитанция берёт `pay_amount` или `booking.price`; `:878-879` участок в квитанции уже есть.
- `routers/bookings.py:1858-1880` — `/driver/bookings` отдаёт водителю `pickup_stop`/`dropoff_stop`; мест и цены брони в строке нет.
- `routers/rides.py:242-246` клампы мест и цены; `:281` `_clean_publish_stops`; `:175-194` `_recent_twin_ride` сравнивает ВСЕ поля `RideIn` с `Ride`; `:321-322` `Ride(**dump)`; `:340` серия регулярных рейсов.
- `routers/rides.py:366-373` `RideEditIn`; `:399-414` цена с бронями только вниз, брони переписываются `body.price * b.seats`; `:447-466` потолок мест `seat_cap` и `seats_left = cap - booked_seats`.
- `routers/rides.py:529`, `:924`, `:1266`, `routers/medical.py:64` — выдачи отсекают `seats_left > 0`; `routers/rides.py:589-602` — точный `route_fit` в цикле по лёгким столбцам; `:515`, `:623` кэш `rides:active:v2`.
- `routers/rides.py:642-677` — `price_hint`: средняя цена + бензин (`fuel_estimate_kop`) только на весь маршрут.
- `request_segments.py:25-28` — заявка отсекает рейс по `seats_left < req.seats` целиком; `routers/requests.py:1318-1327` — принятие своего рейса списывает `seats_left` условным UPDATE; `:1334` рейс из старого отклика `seats_left=0`; `:1465` подбор `seats_left >= req.seats`; `:579-596` `_on_route_scope` берёт `max(seats_left)`; `:498-503`, `:702-707` поля `matched_*` в ленте заявок.
- `services.py:1557-1566` `front_seats_taken`; `:1569-1605` `ride_out_with` (`RideOut(**ride.model_dump())`); `:1620-1655` `public_ride_payload` чистит остановки.
- `safety_logic.py:491-502` — пауза пассажира отменяет его брони («Место снова свободно»), но `seats_left` **не возвращает** — найденная дыра, чинится здесь же.
- `errors.py:11` — `herr(status, ru, ba)` без машинного кода.
- Android: `CreateRideStops.kt:102` редактор остановок; `CreateRideScreen.kt:300`, `:682` поле цены, `:689` подсказка, `:422` `publishRide`; `BookingSeatCount.kt:25-26` сумма по умолчанию `ride.price * seats`, `:69-70` «Итого» через `kopToRub(price * selected * 100)`; `PickupStopRules.kt:24` `routePlaces`; `PickupStopPicker.kt:117` `BookingSegmentPicker`; `BookingActiveTripScreen.kt:653`, `:700` порядок блоков; `YuldashApp.kt:1412` `bookWithStatus` шлёт `payAmount`; `data/ApiClient.kt:967`, `:1060`, `:2399`, `:6448`; `MainActivity.kt:750` `toUiRide`; `ProfileScreen.kt:1748` правка рейса, `:2937`, `:2946` «занято = всего − свободно»; `RidesRequestsChatScreens.kt:880` `RideCard`.
- Сайт: `components/RideSheet.tsx:90`, `:113-116`, `:255`, `:261-268` места и «Итого» от `seats_left`/`price`; `pay_amount` сайт не шлёт (`:132-145`); `screens/CreateRideScreen.tsx:87`, `:97`, `:131-142` (черновик), `:218`, `:240`; `utils/pickupStops.ts:21` `routePlaces`; `components/RideEditActions.tsx:45`, `screens/DriverCabinetScreen.tsx:234`, `:378-381`, `:464` — «занято = всего − свободно».
- Переднее место по участкам, цены до остановок, места по участкам — **не нашёл**.

## 3. Что сделать
**Термины.** Точки маршрута: P0 = `from_city`, P1…Pn = `parse_stops(ride)`, Pn+1 = `to_city`. Участок i = Pi → Pi+1, всего n+1. Бронь занимает участки `[a, b)`: a — индекс посадки (`""`, `NULL`, незнакомая → 0), b — индекс высадки (`""`, `NULL`, незнакомая → n+1). Старая бронь без участка = весь рейс.

**Почему такая схема.** Источник правды — живые брони (`pending/confirmed/onboard`) с их участками. В строке `ride` — снимок свободных мест по участкам, чтобы лента не делала запросов на каждую поездку (сторож `test_no_query_per_item`). Запись — условный UPDATE «снимок не изменился» (CAS): тот же приём, что сейчас (`seats_left >= N` внутри UPDATE), он работает и на PostgreSQL (вдобавок к `FOR UPDATE`), и на SQLite, где `FOR UPDATE` пуст. Отдельная таблица участков отвергнута: лишние join'ы и миграция данных; счёт из броней на каждый запрос — запросы в ленте.

### Сервер (`backend/`)
1. **Модель** (`models.py`, `Ride`): `stop_prices_json: str = ""` — JSON `{"Ургаза": 300}`, ключ — остановка как в `parse_stops`, значение — цена от начала, целые рубли (как `price`; переводить попутку на копейки — вне плана); `leg_free: str = ""` — свободно по участкам «3,0»; `""` или длина ≠ n+1 → «не заведён»; `seats_free_max: int = 0` — max(`leg_free`). `seats_left` дальше = min(`leg_free`) — свободно на всём маршруте (старые клиенты и старый код видят осторожное число).
2. **Новый модуль `app/ride_seats.py`** (одна точка для всех дверей):
   - `points(ride, entry)`, `span(ride, pickup, dropoff, entry) -> (a, b)`;
   - `legs_free(ride, entry) -> list[int]`: заведённый `leg_free`, иначе `[seats_left] * (n+1)` (старый рейс — прежнее поведение);
   - `free_on(ride, span)` = min по участкам; `max_free(ride)`;
   - `occupancy(session, ride, entry)` — по живым броням (правда); `recompute(session, ride)` — пишет `leg_free/seats_left/seats_free_max` из правды;
   - `take(session, ride, span, seats, extra_where=()) -> bool`: до 3 попыток: перечитать `leg_free, seats_left, seats_total` (`populate_existing`); не заведён → начальное значение из `occupancy`; `min(free[a:b]) < seats` → `False`; иначе `UPDATE ride SET leg_free=:new, seats_left=min, seats_free_max=max WHERE id=:id AND leg_free=:old AND <extra_where>`; `rowcount == 1` → `True`; 0 и `leg_free` в базе прежний → не прошло `extra_where`, `False`; иначе снова;
   - `release(session, ride_id, span, seats)`: тот же CAS, `+seats` на `[a, b)` с потолком `seats_total`; не заведён → `recompute` (своя бронь уже не живая в этой транзакции, второй раз не прибавлять);
   - `any_free_sql(n=1)` = `or_(Ride.seats_left >= n, Ride.seats_free_max >= n)` — у незаведённых `seats_free_max = 0`, работает без заполнения базы;
   - `segment_price(ride, pickup, dropoff)`: цен нет → `ride.price` (как сейчас); иначе `cum(высадка) − cum(посадка)`, где `cum(начало)=0`, `cum(конец)=ride.price`, незнакомая посадка → 0, высадка → `ride.price`;
   - `validate_stop_prices(prices: list[int] | None, stops_raw, stops, price)`: `None`/`[]` → `{}`; `price == 0` → 400 `stop_prices_need_price`; длина ≠ числу частей `waypoints` → 400 `stop_prices_count`; оставить только остановки из `parse_stops`; каждая цена `1 ≤ p < price`, строго растёт по маршруту → иначе 400 `stop_prices_order`.
3. **Ошибки с кодом.** В `errors.py` — `herr(status, ru, ba, *, code=None, **extra)` (старые вызовы не меняются; `detail` = `{ru, ba}` + `code` + `extra`). Все новые отказы — с кодом.
4. **Публикация** (`schemas.py` `RideIn`, `rides.py` `create_ride`): `stop_prices: Optional[list[int]]` — по порядку частей `waypoints`. После `_clean_publish_stops` → `validate_stop_prices` → `stop_prices_json`. В `dump` исключить `stop_prices` (как `pickup_point_id`, `:321`), в серию (`:340`) — та же строка. `_recent_twin_ride`: исключить `stop_prices` из общего сравнения и сверить нормализованный JSON отдельно — иначе повтор при обрыве создаст дубль (RS-05).
5. **Бронь** (`bookings.py` `book`): `BookIn.expected_price: Optional[int]` — цена за место, которую видел человек.
   1. После `_resolve_dropoff_stop` → `sp = span(...)`. Быстрая проверка `:539` → `free_on(ride, sp) < seats` → 400 `seats_not_enough`, `free=N`.
   2. `seg = segment_price(...)`; `expected_price` прислан и ≠ `seg` → 409 `price_changed`, `price=seg`.
   3. `total_price = seg * seats`. Есть цены остановок, а `expected_price` нет (старое приложение подставляет полную цену, `BookingSeatCount.kt:25`) → `body.pay_amount` игнорировать, `pay_amount = total_price`. Иначе — правила `:578-585`.
   4. Условный UPDATE `:610-618` заменить на `take(..., extra_where=[~exists(живая бронь этого пассажира)])`. `False` → `rollback` → `_retry_after_booking_race` → 409 «Места только что разобрали» (`seats_taken_now`). Дальше как сейчас: вставка, `IntegrityError` переднего места откатывает и места.
   5. Повтор (`_booking_retry`) — без изменений: та же бронь, места не списываются второй раз.
6. **Отмена и неявка** (`:1535-1538`, `:1756-1759`): вместо `seats_left + seats` — `release(session, ride.id, span(брони), booking.seats)` после условного UPDATE статуса. Порядок замков Ride → Booking не меняется.
7. **Пауза пассажира** (`safety_logic.py:491-502`): после `_снять_бронь(b)` — `release(...)`: место правда освобождается.
8. **Заявки.** `request_segments.segment`: в дешёвой проверке `ride.seats_left < req.seats` → `max_free(ride) < req.seats`; после расчёта участка — `free_on(ride, span(участка)) < req.seats → None`. `_on_route_scope` (`requests.py:579-596`) — `max(seats_left, seats_free_max)`. Принятие (`:1318-1327`) — `take(session, bound_ride, span(section), req.seats, extra_where=[те же условия])`; цена брони остаётся `price_on_table × seats` (торг важнее опубликованной цены). Подбор `:1465` — `any_free_sql(req.seats)`. В ленту заявок (`:498-503`, `:702-707`) — `matched_segment_price`: клиенты подставляют её в цену отклика, водитель меняет.
9. **Выдачи.** `rides.py:529`, `:924`, `:1266`, `medical.py:64` → `any_free_sql()`. В цикле `:589-602` добавить в лёгкие столбцы `seats_left, seats_free_max, leg_free, seats_total`; участок однозначен (`matched_*`) → `free_on ≥ 1`, иначе `max_free ≥ 1`. Кэш `rides:active:v2` → `v3`.
10. **Витрина** (`schemas.py` `RideOut`, `services.py:1569`): `stop_prices: list[{stop, price}]` (по `parse_stops`, `[]` — одна цена); `seats_by_leg: list[int]` (= `legs_free`, без запросов); `seats_on_segment`, `segment_price: Optional[int]` — для участка искавшего, ставить рядом с `matched_*` (`rides.py:616-620` и в `/rides/near`); `seats_booked: Optional[int]` — только в `/driver/rides` (`rides.py:1055-1076`), одним запросом: сумма мест живых и завершённых броней. `leg_free`, `seats_free_max`, `stop_prices_json` наружу как есть не отдавать.
11. **Водитель** (`/driver/bookings`, `:1858-1880`): в строку — `seats` и `price` брони (свои данные сделки).
12. **Правка** (`rides.py` `edit_ride`): `RideEditIn.stop_prices: Optional[list[int]]` (`None` — не менять, `[]` — одна цена). Проверка — `validate_stop_prices` с новой полной ценой. С живыми бронями: `price` не растёт (как `:400-401`) **и** для каждой брони новая `segment_price` ≤ старой → иначе 409 `price_up_with_bookings`; `b.price = новая segment_price × b.seats`, потолок `pay_amount` как `:412-413`. Места (`:450-466`): `booked_seats > cap` → `max(занято по участкам) > cap`; вместо присваиваний `seats_left` — `recompute`. Пуш «Поездка обновлена» — как сейчас.
13. **Подсказка цены** (`price_hint`): параметр `stops` (до 4, через «|») → `stops: [{stop, distance_km, avg, count}]`: расстояние — только по своему справочнику сёл (`ride_stops._lookup_place`), без Яндекс-геокодера (платно); `avg/count` — тем же запросом с `to_city=stop`. Подсказка, не правило.
14. **Переднее место** — без изменений: одно живое на рейс (индекс `models.py:529-532`). По участкам — это убрать барьер базы и держать его в CAS; риск больше пользы, отдельный план по просьбе Александра. «Сзади максимум двое» держится само: на каждом участке мест ≤ `seats_total` ≤ `seat_cap`.

### Android (`android/`)
1. `Domain.kt` `Ride`: `stopPrices: List<Int>`, `seatsByLeg: List<Int>`, `seatsOnSegment: Int?`, `segmentPrice: Int?`, `seatsBooked: Int?`; разбор в `ApiClient.kt:6448`/`:6527`, перенос в `toUiRide` (`MainActivity.kt:750`, общий файл — §11).
2. `PickupStopRules.kt`: `segmentPriceRub(ride, pickup, dropoff)`, `seatsOnSegment(ride, pickup, dropoff)` — та же формула, что `segment_price`/`free_on`.
3. Шторка брони: участок выбирается **до** числа мест (поменять местами `:653` и `:700` в `BookingActiveTripScreen.kt`); `BookingSeatCount(available = seatsOnSegment, price = segmentPriceRub)`; участок не выбран → подсказка «Выбери, где садишься и выходишь — от этого зависят цена и места». «Итого» — `AnimatedContent`, длительность `CanonMotion`, деньги — `kopToRub(rub * 100)`, без `/100`. `rememberBookingPayAmount` — от цены участка.
4. `bookWithStatus`/`createBookingResponse` — `expectedPrice` (`expected_price`, только если у рейса есть цены остановок); вызов `YuldashApp.kt:1412`. 409 `price_changed` → перечитать поездку, показать текст, кнопка снова активна.
5. Публикация: в `CreateRideStopsEditor` переключатель «Цена до каждой остановки» (по умолчанию включён, если есть остановка и цена > 0) и поле «до {остановки}, ₽» у каждой (`CanonFieldShape`, `AnimatedVisibility`); кнопка «Поделить по расстоянию» (≥ 48dp) — из `price_hint.stops`, округление до 10 ₽, монотонность. Ошибки до отправки — как сервер. `publishRide(stopPrices)`.
6. Правка (`ProfileScreen.kt:1748`, `ApiClient.editRide`) — поля цен остановок, если они есть.
7. Водитель: в карточке брони — «1 место · 300 ₽» рядом с `BookingSegmentNote`; новый `RideshareLegsLoad.kt` → `RideLegsLoad(ride)` («Сибай → Ургаза: занято 3 из 3») на карточке активного рейса в кабинете; `ProfileScreen.kt:2937`, `:2946` — из `seatsBooked`, а не «всего − свободно».
8. Лента (`RideCard`, `RidesRequestsChatScreens.kt:880`): участок известен → `segmentPrice` и «на твоём участке N»; цены остановок есть, участок неизвестен → «цена зависит от участка». Отклик своим рейсом (`RequestRouteUi.kt`, `data/RequestRouteDtos.kt`) — подставить `matched_segment_price`.
Нового экрана нет — `enum Screen` не меняется.

### Сайт (`webapp/`)
1. Типы: `api/rides.ts` (`Ride` + те же поля; `editRide` — `stop_prices`), `api/driver.ts` (`RideCreateInput.stop_prices`, `PriceHint.stops`), `api/bookings.ts` (`expected_price`; строка водителя `seats`, `price`), `api/requests.ts` (`matched_segment_price`).
2. `utils/pickupStops.ts`: `segmentPrice`, `seatsOnSegment` — та же формула.
3. `RideSheet.tsx`: список мест и «Итого» — от участка (`:261-268`), проверка `:113-116` — `seatsOnSegment`, цена `:255` — цена участка; `expected_price`; 409 `price_changed` — перечитать поездку.
4. `CreateRideScreen.tsx`: тот же переключатель и поля; цены — в черновик (`:131-142`); `stop_prices` в теле (`:218-240`).
5. `RideEditActions.tsx`, `DriverCabinetScreen.tsx` (`:234`, `:378-381`, `:464` — `seats_booked`, загрузка по участкам), `DriverPassengers.tsx` (места и цена брони), `RideCard.tsx`, `RequestsFeedScreen.tsx` (подстановка цены отклика).

### Зона файлов
Сервер: `backend/app/ride_seats.py` (новый), `models.py`, `schemas.py`, `services.py`, `errors.py`, `request_segments.py`, `safety_logic.py`, `routers/bookings.py`, `routers/rides.py`, `routers/requests.py`, `routers/medical.py`, `backend/alembic/versions/cc_ride_segment_seats_<дата>.py` (новый); тесты `backend/tests/test_segment_seats.py`, `test_segment_price.py`, `test_segment_seats_race_pg.py` (новые), `test_seats_invariant.py`, `test_last_seat_race.py`, `test_request_bound_accept_seats.py`, по необходимости `test_pickup_stop_booking.py`, `test_request_bound_segments.py`, `test_request_route_feed.py`, `test_full_ride_is_not_in_the_feed.py`.
Android (`android/app/src/main/java/com/yuldash/app/`): `Domain.kt`, `MainActivity.kt` (только `toUiRide`), `YuldashApp.kt` (только вызов брони), `data/ApiClient.kt`, `data/DriverBookings.kt`, `data/RequestRouteDtos.kt`, `PickupStopRules.kt`, `BookingSeatCount.kt`, `BookingActiveTripScreen.kt`, `CreateRideStops.kt`, `CreateRideScreen.kt`, `ProfileScreen.kt`, `RidesRequestsChatScreens.kt`, `RequestRouteUi.kt`, `RideshareLegsLoad.kt` (новый); тесты `PickupStopTest.kt`, `BookingSeatCountTest.kt`, `CreateRideStopsTest.kt`, `SegmentPriceTest.kt` (новый).
Сайт: `webapp/src/api/{rides,driver,bookings,requests}.ts`, `utils/pickupStops.ts`, `components/{RideSheet,RideCard,RideEditActions,DriverPassengers}.tsx`, `screens/{CreateRideScreen,DriverCabinetScreen,RequestsFeedScreen}.tsx`, `webapp/tests/pickup-stop-test.mjs`.
Мозг: `docs/decisions.md`, `docs/tasks.md`, `docs/architecture.md`.

## 4. База данных
Миграция `cc_ride_segment_seats_<дата>`, `down_revision` — текущая голова (`alembic heads`; на 24.10 — `cb_sos_retry_intent_20261021`; RS-03/05/06/14 тоже добавляют миграции — цепочку не ветвить). Образец — `ca_booking_segments_20261018.py` (проверка через `inspect`, повторный запуск безопасен).
- **upgrade:** `ride.stop_prices_json VARCHAR NOT NULL DEFAULT ''`, `ride.leg_free VARCHAR NOT NULL DEFAULT ''`, `ride.seats_free_max INTEGER NOT NULL DEFAULT 0`. Данные не трогаем: старый рейс заводит `leg_free` из броней при первой записи мест.
- **downgrade:** для `status='active'` — `seats_left = CASE WHEN seats_total − SUM(seats живых броней) < 0 THEN 0 ELSE … END` (прежний смысл «всего − занято», одним SQL для PostgreSQL и SQLite), затем снять 3 столбца. Откат кода — только после downgrade, иначе старый код вернёт отменённые места не на те участки.
- SQLite-демо добавит столбцы сам (`db.py:64` `_migrate_sqlite_add_columns`).
- Ждёт «да»: применение на рабочей базе.

## 5. Надписи
Русский; башкирский — черновик исполнителя через `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку». Всё через `appText(ru, ba)` / `appText` сайта.
- «Цена до каждой остановки» · «до {остановка}, ₽» · «Поделить по расстоянию» · «Одна цена на любой участок».
- «Цена до каждой следующей остановки должна быть больше, а до конца маршрута — больше всех» (`stop_prices_order`).
- «Сначала укажи цену до конца маршрута» (`stop_prices_need_price`) · «Цен больше или меньше, чем остановок — проверь» (`stop_prices_count`).
- «На твоём участке свободно мест: {N}» (`seats_not_enough`) · «Водитель изменил цену: теперь {N} ₽ за место. Проверь и забронируй ещё раз» (`price_changed`).
- «С бронями цену можно только снижать — у кого-то из пассажиров она бы выросла» (`price_up_with_bookings`).
- «Выбери, где садишься и выходишь — от этого зависят цена и места» · «Цена зависит от участка» · «{A} → {B}: {N} ₽ за место» · «Загрузка по участкам» · «{A} → {B}: занято {x} из {y}» · «{N} места · {сумма}».

## 6. Состояния экрана
- Публикация: подсказка цен грузится — чип со спиннером; ошибка сети — кнопка «Поделить» скрыта, поля вводятся руками; ошибка цен — под полем, кнопка «Опубликовать» неактивна.
- Шторка брони: участок не выбран — подсказка вместо «Итого»; мест на участке 0 — «На твоём участке мест нет» + «Посмотреть другие»; `price_changed` / `seats_not_enough` — спокойный текст, новая цена, кнопка снова доступна; нет сети — прежний «Повторить».
- Кабинет: загрузка по участкам грузится вместе с рейсом; пусто (броней нет) — «Пока никто не забронировал».
- Длинный башкирский: поле цены под названием остановки, а не в строку; строки участков переносятся; узкий экран 320dp без горизонтальной прокрутки.
- Тёмная тема — только `Canon*`; тач-цели ≥ 48dp; `contentDescription` полей на двух языках.

## 7. Тесты
**Сервер** (`pytest` из `backend/`, `ruff check app/ --select F`):
- `test_segment_price.py` (таблица): без цен — `ride.price`; Сибай→Ургаза 300, Ургаза→Акъяр 200, Сибай→Акъяр 500; NULL/«» = начало/конец; валидация 400 с кодами; `price = 0` + цены → 400; повтор публикации с ценами — тот же рейс; серия копирует цены.
- `test_segment_seats.py`: 3 места, трое Сибай→Ургаза → Ургаза→Акъяр бронируется (3 свободно), Сибай→Акъяр — 400 `seats_not_enough`; отмена/неявка/пауза освобождают только свой участок; старая бронь без участка — весь рейс; старый рейс (`leg_free=""`) — прежнее поведение до первой записи; лента/«Ближайшие»/подбор заявки находят частично полный рейс и не показывают рейс без места на однозначном участке; `seats_by_leg`/`seats_on_segment`/`segment_price` в выдаче; `expected_price` → `price_changed`; старый клиент с полной `pay_amount` получает цену участка; правка: цена вверх с бронью → 409, вниз → брони и `pay_amount` пересчитаны; потолок `back_max_two` по max загрузке; принятие заявки своим рейсом списывает только участок; миграция туда-обратно (как `test_pickup_stop_booking.py:509`).
- `test_seats_invariant.py`: инвариант по участкам `leg_free[i] = total − Σ мест живых броней на участке i`, `seats_left = min`.
- `test_last_seat_race.py` (потоки + `Barrier`, `_book_together`): последнее место на пересекающихся участках — ровно одна 200; на непересекающихся — обе 200.
- `test_segment_seats_race_pg.py` (только PostgreSQL, как `test_cancel_in_transit_race_pg.py`): то же на настоящих замках + отмена и бронь в одну секунду.
- `test_request_bound_accept_seats.py`: «чужую бронь» делать настоящей бронью или устаревшим `leg_free`, а не `seats_left=0` — проверка не ослабляется.
- **Мутации:** убрать `leg_free = :old` из CAS → красный тест устаревшего чтения; `span` всегда весь рейс → красный «Ургаза→Акъяр»; `release` на все участки → красный инвариант; `segment_price` = `ride.price` → красная таблица; не сверять `expected_price` → красный `price_changed`; убрать проверку цены по броням в правке → красный.

**Android** (`:app:testDebugUnitTest`, `:app:assembleDebug`): `SegmentPriceTest.kt` — та же таблица, что сервер; `BookingSeatCountTest.kt` — максимум = места участка, «Итого» = цена участка × места; `CreateRideStopsTest.kt` — монотонность и «Поделить»; сторожа `MoneySourceGuardTest`, `CanonSourceGuardTest`, `ColorSourceGuardTest`, `SpacingRatchetGuardTest`, двуязычие — зелёные.
**Сайт**: `npm run check` (`tests/pickup-stop-test.mjs` — та же таблица), `npm run build`.

## 8. Приёмка
Принимает агент другой «семьи» (Q9), по умолчанию «не принято».
1. Вывод `ruff`, `pytest` (все, включая новые), `npm run check`, `npm run build`, `:app:testDebugUnitTest`, `:app:assembleDebug` — зелёные, приложены.
2. Мутации из п. 7 по одной — тест краснеет, приложен вывод.
3. Код: все записи мест идут через `ride_seats.take/release/recompute` (`rg "seats_left\s*[-+]?=" backend/app` — только `ride_seats.py`); в выдачах нет `seats_left > 0`; `pay_amount` старого клиента не выше цены участка.
4. Эмулятор (одно устройство; вторая сторона — сайт в браузере, Q11), RU и BA × светлая и тёмная:
   - водитель (Android) публикует Сибай → Ургаза → Акъяр, 3 места, до Ургазы 300 ₽, всего 500 ₽ — поля, «Поделить», ошибка «цена не растёт» видны и понятны;
   - три пассажира (сайт) берут Сибай → Ургаза — у каждого 300 ₽;
   - поиск Ургаза → Акъяр (Android) находит рейс: «200 ₽ за место», «на твоём участке 3»; бронь 2 места → «Итого 400 ₽»; квитанция после завершения — 400 ₽;
   - водитель видит загрузку по участкам, места и цену каждой брони; отмена одного Сибай → Ургаза освобождает только первый участок;
   - правка цены вверх при бронях — отказ; вниз — пассажиру пуш, цена в брони меньше;
   - старая поездка без цен остановок — одна цена, как раньше; длинные башкирские строки не ломают вёрстку на узком экране.
5. Двигает W1-E2E-01 (поиск по остановкам → участок → цена → квитанция), W1-E2E-02 (свой рейс по заявке списывает только участок), W1-E2E-03 (последнее место, отмена/неявка освобождают свой участок). Остаётся: полный прогон RS-16, W1-E2E-07 (Q11), BA носителем. Статус — только в `docs/audit-blocks.md`.

## 9. Риски и границы
- **Деньги/тарифы.** Меняется расчёт цены брони — до «да» Александра только в ветке. Онлайн-оплаты нет (Q5); суммы — договорённость. Единица — рубли, как у `Ride.price`.
- **Старые приложения.** Видят `seats_left` = свободно на всём маршруте (осторожно): рейс с местом лишь на части пути покажут как «0 мест». Цена — полная, бронь без участка — весь рейс. Новую `pay_amount` выше цены участка сервер им не запишет.
- **Приватность.** `seats_by_leg` публично показывает загрузку по участкам (без имён и без мест конкретных людей) — того же рода, что нынешнее «осталось N мест». Пуш «Новая бронь» по-прежнему без названия села.
- **Гонки.** CAS по `leg_free` + `FOR UPDATE`; повтор ≤ 3. Не добавлять запись мест мимо `ride_seats`.
- **Откат.** Только через downgrade (п. 4), иначе места разъедутся.
- **Не входит:** переднее место по участкам, фильтр «сколько мест нужно» (RS-12), правка остановок (RS-15 обязан сбросить/переспросить цены и вызвать `recompute`), точки встречи (RS-03).
- **Общие файлы** (`models.py`, `ApiClient.kt`, `MainActivity.kt`, `YuldashApp.kt`, `ProfileScreen.kt`) — параллельно с RS-03/05/09/12/15 нельзя.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 2026-10-24 | не начато | — | — | план написан |
