# RS-02 — Места и цена по участкам

| | |
|---|---|
| Статус | не начато |
| Единицы цен | решено 24.10 (D7): вариант А — целые рубли, исключение из §8 («Единицы», раздел 3) |
| Ждёт «да» Александра | А — миграция рабочей базы; Б — тарифы (расчёт цены брони) и миграция |
| Зависит от | RS-01 (`herr(..., code=)`, `ApiError.code`, `detailCode`); Б — после А |
| Не параллельно с | RS-03, RS-05, RS-07, RS-08, RS-09, RS-12, RS-14, RS-15 — по общим файлам, очередь в индексе (RS-02 — сразу после RS-01) |
| Деление на PR | `[RS-02А]` сервер, места без цен → `[RS-02Б]` сервер, цены остановок → `[RS-02В]` Android, `[RS-02Г]` сайт (места — после А, цены — после Б) |
| Проверка `yuldash-security-privacy` | да — деньги (`pay_amount`, `expected_price`), публичная загрузка участков |
| Двигает сценарии приёмки | W1-E2E-01, -02, -03 (`docs/audit-traceability/rideshare-scope.md`) |
| Зона файлов | А: `backend/app/ride_seats.py` (новый), `models.py`, `schemas.py`, `services.py`, `request_segments.py`, `safety_logic.py`, `account.py`, `routers/{bookings,rides,requests,medical}.py`, `backend/alembic/versions/<префикс>_ride_segment_seats_<дата>.py`. Б: те же файлы без `safety_logic.py`/`account.py`/`medical.py` + `<префикс>_ride_stop_prices_<дата>.py`. Тесты — раздел 7. В (`android/app/src/main/java/com/yuldash/app/`): `Domain.kt`, `MainActivity.kt` (только `toUiRide`), `YuldashApp.kt` (только вызов брони), `data/{ApiClient,DriverBookings,RequestRouteDtos}.kt`, `PickupStopRules.kt`, `BookingSeatCount.kt`, `BookingActiveTripScreen.kt`, `CreateRideStops.kt`, `CreateRideScreen.kt`, `ProfileScreen.kt`, `RidesRequestsChatScreens.kt`, `RequestsFeedScreen.kt`, `RideshareLegsLoad.kt` (новый). Г (`webapp/src/`): `api/{rides,driver,bookings,requests}.ts`, `utils/pickupStops.ts`, `components/{RideSheet,RideCard,RideEditActions,DriverPassengers}.tsx`, `screens/{CreateRideScreen,DriverCabinetScreen,RequestsFeedScreen}.tsx`. Мозг: `docs/{decisions,tasks,architecture,lessons}.md` |
| Источник | исследование `c5be55a:docs/research/apps/master/part-001.md` п. 6, 9, 10, 12, 13, 29; решение Q12 б (24.10) |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Было.** Водитель едет Сибай → Ургаза → Акъяр, 3 места. Трое берут Сибай → Ургаза — поездка «полная»: Булат из Ургазы в Акъяр её не находит, хотя дальше машина пустая. И все платят до Акъяра.

**Стало.**
- Место занято только на своём участке: Булат видит «на твоём участке свободно 3 места». Отмена и «не пришёл» освобождают только свой участок.
- Водитель пишет цену до каждой остановки: «до Ургазы — 300 ₽», весь путь — 500 ₽ (или одну на всё) и видит цены участков: «Ургаза → Акъяр: 200 ₽». Булат платит 200 ₽ × места. С бронями цену — только снижать.
- Водитель видит, кто где садится и выходит, за сколько, и «Сибай → Ургаза: занято 3 из 3». Старое — как раньше; переднее место — одно на рейс.
- Цены по остановкам — после решения Александра, рубли или копейки; места от этого не зависят.

## 2. Что уже есть в коде
- `backend/app/models.py:368-370` — один счётчик `seats_left`; `price` — **целые рубли** (`routers/rides.py:246`), `Booking.price`/`pay_amount` — тоже (`models.py:538`, `:609`); `AGENTS.md` §8 требует копейки — см. «Единицы».
- `models.py:409` `waypoints` (`ride_stops.py:31` максимум 4, `:141` `parse_stops`); `:585-587` `pickup_stop/dropoff_stop` (`""` — начало/конец, `NULL` — не указано); `:512-532` одно переднее на рейс.
- `routers/bookings.py:478` `FOR UPDATE`; `:539` проверка мест; `:574` `ride.price * body.seats`; `:578-585` `pay_amount`; `:610-618` UPDATE `seats_left >= N`; `:1535-1538`, `:1756-1759` возврат мест; `:868-879` квитанция; `:1858-1880` `/driver/bookings` без мест и цены.
- `routers/rides.py:175-194` `_recent_twin_ride`; `:321-322` `Ride(**dump)`; `:340` серия; `:399-414` цена с бронями вниз; `:447-466` `seat_cap`; отсев `seats_left > 0` — `:529`, `:924`, `:1266`, `routers/medical.py:64`; лёгкие столбцы `:589-602` (`/rides`), `:978-982` (`/rides/near`); кэш `:515`, `:623`; `price_hint` `:642-677`.
- `request_segments.py:25-28` отсев `seats_left < req.seats`; `routers/requests.py:1320-1328` списание при принятии своего рейса, `:1465` подбор, `:572` `_on_route_scope`, `:702-707` `matched_*`.
- Места **не возвращают** пауза пассажира (`safety_logic.py:491-502`) и удаление его аккаунта: живые брони на чужих рейсах попадают в `booking_ids` (`account.py:281-284`) и стираются (`:456`). Обе дыры ломают «свободно = всего − занято» — чиним.
- Android: `BookingSeatCount.kt:25-26` сумма `ride.price * seats` уходит как `payAmount` (`YuldashApp.kt:1412`), `:69-70` `kopToRub(price * selected * 100)`; `ProfileScreen.kt:2937`, `:2946` «всего − свободно». Сайт: `RideSheet.tsx:113-116`, `:261-268`; `DriverCabinetScreen.tsx:234`, `:464`.
- Цены до остановок и места по участкам — **не нашёл**.

## 3. Что сделать
**Термины.** Точки: P0 = `from_city`, P1…Pn = `parse_stops`, Pn+1 = `to_city`; участок i = Pi → Pi+1. Бронь занимает `[a, b)`: посадка `""`/`NULL`/незнакомая → 0, высадка → n+1 (старая бронь = весь рейс).

**Почему так.** Правда — живые брони; в `ride` — снимок свободных мест по участкам: лента не ходит в базу на каждую поездку (сторож `tests/test_feeds_do_not_ask_the_db_per_row.py::test_лента_поездок_не_растёт_по_запросам`). Запись — условный UPDATE «снимок не изменился» (CAS), как нынешний `seats_left >= N`: держит PostgreSQL (с `FOR UPDATE`) и SQLite.

**Единицы (решено 24.10, D7 — вариант А).** Осознанное исключение из §8 «цены попутки — целые рубли», в копейки только на границе показа (`kopToRub(rub * 100)`). Описание полей `_kop` ниже относится к отклонённому варианту Б и не реализуется. Единицы в одной поездке не смешивать, серверный тест на единицу (раздел 7).

### Часть А — сервер: места по участкам (цены не трогает)
1. **`Ride`:** `leg_free: str = ""` («3,0»; пусто или длина ≠ n+1 — «не заведён»); `seats_free_max: int = 0`. `seats_left` = min (весь путь — осторожно для старых клиентов).
2. **`app/ride_seats.py`** — единственная дверь для мест (её же зовут RS-10, RS-12 Б, RS-15):
   - `span(ride, pickup, dropoff, entry=None) -> (a, b)`; `legs_free(ride) -> list[int]` (не заведён → `[seats_left]*(n+1)`); `free_on(ride, span) -> int` (min по `[a, b)`); `max_free(ride) -> int`; `occupancy(session, ride)` из живых броней; `recompute(session, ride)`;
   - `take(session, ride, span, seats, extra_where=()) -> bool`: ≤ 3 попыток — перечитать (`populate_existing`), не заведён → снимок из `occupancy`, не хватает → `False`; `UPDATE … SET leg_free, seats_left=min, seats_free_max=max WHERE id AND leg_free=:old AND extra_where`; 0 строк при прежнем `leg_free` → `False`, иначе повтор;
   - `release(session, ride_id, span, seats)` — тот же CAS, `+seats` на `[a, b)` до `seats_total`; не заведён → `recompute`;
   - `any_free_sql(n=1)` = `or_(seats_left >= n, seats_free_max >= n)` — заполнять базу не нужно.
3. **Бронь** (`book`): `free_on(span) < seats` → 400 `seats_not_enough` (+`free`; `herr(..., code=)` из RS-01); UPDATE `:610-618` → `take(..., extra_where=[~exists(живая бронь пассажира)])`, `False` → `rollback` → `_retry_after_booking_race` → 409 `seats_taken_now`. Цена, повтор, откат переднего — прежние.
4. **Отмена, неявка, пауза** (`:1535`, `:1756`, `safety_logic.py:491-502`) → `release(span(брони))` после смены статуса; замки Ride → Booking прежние.
5. **Удаление аккаунта** (`account.py`): до шага 3.9 собрать `ride_id` живых броней пассажира на **чужих** активных рейсах; после `delete(Booking)` (`:456`) — их `recompute` в той же транзакции.
6. **Заявки:** `segment` — сперва `max_free`, после участка — `free_on`; `_on_route_scope` — `max(seats_left, seats_free_max)`; принятие `:1320-1328` → `take(..., extra_where=[прежние условия])`; `:1465` → `any_free_sql(req.seats)`.
7. **Выдачи:** `:529`, `:924`, `:1266`, `medical.py:64` → `any_free_sql()`; лёгкие столбцы `/rides` (`:589-602`) **и** `/rides/near` (`:978-982`) + `seats_left, seats_free_max, leg_free`: участок однозначен → `free_on ≥ 1`, иначе `max_free ≥ 1`. Кэш → `rides:active:v3`.
8. **`RideOut`** (без запросов): `seats_by_leg`, `seats_on_segment` рядом с `matched_*` (`rides.py:616-620`, `/rides/near`); `seats_booked` — только `/driver/rides`; `/driver/bookings` + `seats`, `price`. Сырой `leg_free` — нет.
9. **Правка мест** (`:447-466`): `booked_seats > cap` → max занятости участка; `seats_left = …` → `recompute`.
10. **Переднее** — как сейчас (индекс базы); «сзади двое» держится: на участке мест ≤ `seat_cap`.

### Часть Б — сервер: цены остановок (в рублях, D7)
1. **`Ride.stop_prices_json: str = ""`** — JSON-**список** цен от начала по порядку частей `waypoints` (`split_raw`), не по имени: RS-15 переименовывает и переставляет остановки и присылает новый список. Тип в `RideIn`/`RideEditIn`/`RideOut`: А — `list[int]`, ₽, `1..99 999`; Б — `stop_prices_kop: list[int]`.
2. **`ride_seats.segment_price(ride, pickup, dropoff)`**: цен нет → `price`; иначе `cum(высадка) − cum(посадка)` (`cum(начало)=0`, `cum(конец)=price`, остановка — по индексу её части). **`validate_stop_prices`**: пусто → `[]`; 400 `stop_prices_need_price` (`price == 0`), `stop_prices_count` (число ≠ частям или часть — повтор/начало/конец), `stop_prices_order` (`1 ≤ p < price`, строгий рост).
3. **Публикация:** проверка → JSON; не в `dump`, в серию — копия; `_recent_twin_ride` сверяет JSON отдельно (иначе дубль).
4. **Бронь:** `BookIn.expected_price` (за место, тип как у цен) ≠ `seg` → 409 `price_changed` (+`price`); `total = seg * seats`; цены остановок есть, `expected_price` нет (старое приложение шлёт полную сумму) → `pay_amount = total`.
5. **Правка:** `RideEditIn.stop_prices` (`None` — не трогать, `[]` — одна цена). С живыми бронями `price` не растёт **и** у каждой брони новая `segment_price` ≤ старой, иначе 409 `price_up_with_bookings`; `b.price = новая × seats`, потолок `pay_amount` как `:412-413`.
6. **Выдача:** `RideOut.stop_prices: [{stop, price}]`, `segment_price`; в ленту заявок — `matched_segment_price` (бронь по заявке — `price_on_table × seats`, торг).
7. **`price_hint`:** `stops` (≤ 4) → `stops: [{stop, distance_km, avg, count}]`; расстояние — свой справочник сёл, не Яндекс (платно).

### Часть В — Android (`android/`)
1. `Ride` (`Domain.kt`) + `seatsByLeg`, `seatsOnSegment`, `seatsBooked` (после А), `stopPrices`, `segmentPrice` (после Б); разбор в `ApiClient.kt`, `toUiRide` (`MainActivity.kt:750`); `PickupStopRules.kt` — `seatsOnSegment`, `segmentPriceRub` по формулам сервера.
2. Шторка: участок до числа мест (поменять блоки `BookingActiveTripScreen.kt:653` и `:700`); `BookingSeatCount` и сумма по умолчанию — от участка; «Итого» — `AnimatedContent` (`CanonMotion`), деньги — по решению о единицах (А: `kopToRub(rub * 100)`); `expectedPrice`; ветвление по `detailCode` (`price_changed` → перечитать).
3. Публикация (после Б): переключатель «Цена до каждой остановки», поле у каждой (`CanonFieldShape`, `AnimatedVisibility`), под ними — цены участков. «Поделить по расстоянию» (≥ 48dp): до 10 ₽; нарушен рост — до 1 ₽, затем «предыдущая + 1 ₽»; не помещается — поля пустые и подсказка.
4. Правка (`ProfileScreen.kt:1748`) — цены остановок; кабинет: «1 место · 300 ₽» у брони, `RideLegsLoad(ride)` (`RideshareLegsLoad.kt`) на активном рейсе, `:2937`/`:2946` — из `seatsBooked`.
5. Лента (`RideCard`, `RidesRequestsChatScreens.kt:880`): места и цена участка или «цена зависит от участка»; отклик своим рейсом (`RequestsFeedScreen.kt:111`) — `matched_segment_price`. Новых экранов нет.

### Часть Г — сайт (`webapp/`)
Те же шаги: типы `api/*.ts`; формулы в `utils/pickupStops.ts`; `RideSheet.tsx` (места, «Итого», `expected_price`, ветвление по `ApiError.code`); `CreateRideScreen.tsx` (поля, цены участков, черновик); `RideEditActions.tsx`; `DriverCabinetScreen.tsx` (`seats_booked`, загрузка); `DriverPassengers.tsx`, `RideCard.tsx`, `RequestsFeedScreen.tsx`.

## 4. База данных
Имена `<префикс>_ride_segment_seats_<дата>` (А), `<префикс>_ride_stop_prices_<дата>` (Б), префикс — по индексу; перед слиянием свежий `origin/main`, `alembic heads` → `down_revision`, одна голова; up→down→up на SQLite и PostgreSQL; сторожа `test_migrations_reach_production.py`, `test_migrations_match_the_models.py`; образец `ca_booking_segments_20261018.py`.
- **А:** `ride.leg_free VARCHAR NOT NULL DEFAULT ''`, `ride.seats_free_max INTEGER NOT NULL DEFAULT 0`; данные не трогаем (снимок — при первой записи мест). downgrade: активным рейсам `seats_left = max(0, seats_total − Σ мест живых броней)` (`CASE`), затем снять; старый код — только после downgrade.
- **Б:** `ride.stop_prices_json VARCHAR NOT NULL DEFAULT ''`; downgrade — снять (брони хранят свою цену). SQLite-демо добавит столбцы сам (`db.py:64`).
- Срок хранения и удаление аккаунта: личных данных нет; поля живут и удаляются с `ride` (`account.py`); удаление пассажира — `recompute` (А5).

## 5. Надписи
BA — черновик исполнителя в `docs/tasks.md` («Переводы на проверку»); всё через `appText(ru, ba)`.
- «Цена до каждой остановки» · «до {остановка}, ₽» · «{A} → {B}: {N} ₽» · «Поделить по расстоянию» · «Не получилось поделить — впиши цены сам» · «Цена зависит от участка» · «{N} ₽ за место» · «Загрузка по участкам» · «занято {x} из {y}» · «Выбери, где садишься и выходишь — от этого зависят цена и места».
- `stop_prices_order` «Цена до каждой следующей остановки должна быть больше, а до конца маршрута — больше всех»; `stop_prices_need_price` «Сначала укажи цену до конца маршрута»; `stop_prices_count` «Цен больше или меньше, чем остановок — проверь».
- `seats_not_enough` «На твоём участке свободно мест: {N}»; `price_changed` «Водитель изменил цену: теперь {N} ₽ за место. Проверь и забронируй ещё раз»; `price_up_with_bookings` «С бронями цену можно только снижать — у кого-то из пассажиров она бы выросла».

## 6. Состояния экрана
Публикация: подсказка грузится — спиннер, нет сети — «Поделить» скрыта, ошибка — под полем. Шторка: участок не выбран — подсказка вместо «Итого»; мест нет — «На твоём участке мест нет» + «Посмотреть другие»; нет сети — «Повторить». BA — поле под названием; 320dp; `Canon*`; ≥ 48dp.

## 7. Тесты
**Сервер А** (`ruff check app/ --select F`, `pytest`):
- `test_segment_seats.py` (новый): трое до Ургазы → Ургаза→Акъяр бронируется, весь путь — `seats_not_enough`; отмена/неявка/пауза — свой участок; **удаление аккаунта пассажира с живой бронью возвращает место**; старые бронь и рейс — по-старому; лента, «Ближайшие», подбор; `back_max_two`; заявка своим рейсом; миграция туда-обратно (`test_pickup_stop_booking.py:509`).
- `test_seats_invariant.py`: `leg_free[i] = total − Σ живых на участке i` после любой цепочки. `test_last_seat_race.py` (`_book_together`), `test_segment_seats_race_pg.py` (новый, PostgreSQL): пересекающиеся участки — одна 200, непересекающиеся — обе. `test_request_bound_accept_seats.py`: «чужая бронь» — настоящая, не `seats_left=0`.
- **Мутации А:** без `leg_free = :old` в CAS; `span` = весь рейс; `release` на все участки; без `recompute` в `account.py` — каждая краснит тест.

**Сервер Б:** `test_segment_price.py` (новый): таблица (без цен → `price`; 300/200/500; NULL/«»), отказы с кодами, цены по порядку частей, повтор публикации, серия, `price_changed`, полная `pay_amount` старого клиента, правка; **тест на единицу**: вариант А — 300 ₽ дают `booking.price == 300`, квитанция `amount == 300`, `RideOut.stop_prices[0].price == 300` (не 30 000); вариант Б — то же в копейках. Мутации: `segment_price` = `price`; без `expected_price`; без проверки цен броней при правке.

**Android** (`:app:testDebugUnitTest`, `:app:assembleDebug`): `SegmentPriceTest.kt` (новый: таблица, «Поделить» — строгий рост, шаг 1 ₽), `BookingSeatCountTest.kt`, `CreateRideStopsTest.kt`; сторожа `*GuardTest` зелёные. **Сайт:** `npm run check` (`webapp/tests/pickup-stop-test.mjs` — та же таблица), `npm run build`.

## 8. Приёмка
Агент другой «семьи» (Q9), по умолчанию «не принято»; каждая часть — своим PR.
1. Проверки раздела 7 для части зелёные, вывод приложен; мутации — красные.
2. Код: места пишет только `ride_seats.py`; в выдачах нет `seats_left > 0`; Б — в целых рублях (D7).
3. Эмулятор (вторая сторона — сайт, Q11), RU и BA × светлая и тёмная:
   - места: трое (сайт) берут Сибай → Ургаза; Ургаза → Акъяр (Android) — «на твоём участке 3», бронь 2 места; водитель видит загрузку; отмена одного до Ургазы освобождает только первый участок;
   - цены: 300/500 ₽, видны цены участков, «Поделить» и ошибки понятны; Ургаза → Акъяр — «200 ₽ за место», 2 места → «Итого 400 ₽», квитанция 400 ₽; цена вверх при бронях — отказ, вниз — пуш;
   - рейс без цен остановок — как раньше; длинный BA не ломает узкий экран.
4. Двигает W1-E2E-01 (участок → цена → квитанция), -02 (свой рейс по заявке), -03 (последнее место, отмена, неявка, удаление аккаунта); остаются RS-16, W1-E2E-07 (Q11), BA носителем. Статус — только `docs/audit-blocks.md`.

**Мозг:** `architecture.md` — `ride_seats.py` и новые поля; `decisions.md` — «снимок + CAS», решение о единицах; `tasks.md` — BA-черновики; `lessons.md` — «пауза и удаление аккаунта не возвращали места».

## 9. Риски и границы
- **Деньги:** единицы — рубли (D7); Б до «да» на тарифы — в ветке; онлайн-оплаты нет (Q5).
- **Старые приложения:** рейс с местом на части пути — «0 мест»; бронь без участка — весь рейс, полная цена.
- **Приватность:** `seats_by_leg` — загрузка без имён, как «осталось N мест»; пуш «Новая бронь» — без села.
- **Не входит:** переднее по участкам, фильтр мест (RS-12), правка остановок (RS-15: новый список цен + `recompute`), точки встречи (RS-03).

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 2026-10-24 | не начато | — | — | план написан; правки приёмки: единицы — вопрос, деление А–Г, удаление аккаунта |
