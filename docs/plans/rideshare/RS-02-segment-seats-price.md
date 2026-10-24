# RS-02 — Места и цена по участкам

| | |
|---|---|
| Статус | не начато |
| Единицы цен | D7 (решено 24.10, вариант А): целые рубли, в копейки — только при показе |
| Ждёт «да» Александра | А — миграция рабочей базы; Б — деньги (расчёт цены участка) и миграция: делается в ветке, вливается после «да» |
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
- Цены по остановкам — целые рубли (D7); автоматического перевода сумм базы нет. Места от этого решения не зависят.

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

**Единицы (D7, решено 24.10, вариант А).** `Ride.price`, `Booking.price`, `pay_amount`, `stop_prices`, `expected_price`, `segment_price` — целые рубли; это исключение из `AGENTS.md` §8 (строку вносит Александр, `docs/plans/README.md`, «Исключения»). Часть Б делается в ветке, с diff и риском, вливается после «да». Старые суммы в базе на 100 не умножать. Единственное преобразование — на клиентской границе показа: общий `rideshareRubToKop` → `kopToRub`; такси/курьер не меняются. Ниже один договор в рублях; варианта в копейках нет.

### Часть А — сервер: места по участкам (цены не трогает)
1. **`Ride`:** `terms_version: int = 0` (BIGINT; версия полного договора рейса), `leg_free: str = ""` («3,0»; пусто или длина ≠ n+1 — «не заведён»); `seats_free_max: int = 0`. `seats_left` = min (весь путь — осторожно для старых клиентов).
2. **`app/ride_seats.py`** — единственная дверь для мест (её же зовут RS-10, RS-12 Б, RS-15):
   - `span(ride, pickup, dropoff, entry=None) -> (a, b)`; `legs_free(ride) -> list[int]` (не заведён → `[seats_left]*(n+1)`); `free_on(ride, span) -> int` (min по `[a, b)`); `max_free(ride) -> int`; `occupancy_snapshot(session, ride, proposed_stops=None, proposed_seats_total=None)` — чистый расчёт из живых броней БЕЗ UPDATE/commit/увеличения версии (RS-15 использует именно его перед своим CAS); `occupancy(session, ride)` — совместимый alias чистого расчёта; `recompute(session, ride)` вызывает snapshot и пишет остатки одной условной записью по `leg_free=:old`, версию **не** меняет (остатки — не условия), без внутреннего commit;
   - `take(session, ride, span, seats, expected_terms_version, extra_where=()) -> bool`: условная запись остатков по версии, которую уже проверил внешний алгоритм А3; не заведён → снимок occupancy под теми же замками; не хватает → False. `UPDATE … SET leg_free, seats_left=min, seats_free_max=max WHERE id AND terms_version=:checked_version AND leg_free=:old AND extra_where` — **версию не увеличивает**: бронь меняет остаток, а не условия. 0 строк → перечитать рейс: версия та же (место заняла чужая бронь) — пересчитать по свежему `leg_free`, до 3 повторов, мест не хватает → False; версия другая (изменились условия) → 409 `ride_terms_changed` без резерва. Клиентский `expected_terms_version` не меняется;
   - `release(session, ride_id, span, seats)` — тот же CAS, `+seats` на `[a, b)` до `seats_total`; не заведён → `recompute`;
   - `any_free_sql(n=1)` = `or_(seats_left >= n, seats_free_max >= n)` — заполнять базу не нужно.
3. **Бронь** (`book`): новый `BookIn.expected_terms_version` — версия, показанная перед подтверждением. До записи под замком Ride → Booking перечитать актуальный рейс/брони, после любой модерации заново взять замки; маршрут, машину/её снимок, цену, stop_prices и места проверять вместе. Несовпадение версии → 409 `ride_terms_changed` без резерва; старому клиенту без версии — те же проверки по свежему объекту под замком (это не доказательство согласия с незнакомыми новыми условиями). `free_on(span) < seats` → 400 `seats_not_enough` (+`free`; `herr(..., code=)` из RS-01); UPDATE `:610-618` → `take(..., extra_where=[~exists(живая бронь пассажира)])`, `False` → `rollback` → `_retry_after_booking_race` → 409 `seats_taken_now`. Цена, повтор, откат переднего — прежние.
   **Общий договор записи:** `terms_version` растёт **только при изменении условий** — маршрут/остановки, время, price/stop_prices, `seats_total` и опции, назначенная машина. Бронь, отмена, неявка, пауза и пересчёт остатков (`take`/`release`/`recompute`) меняют только `leg_free`/`seats_left`/`seats_free_max` условной записью по `leg_free=:old` и версию не трогают: чужая бронь между показом и подтверждением — не «условия изменились», а обычная проверка мест (400 `seats_not_enough` / 409 `seats_taken_now`), и отклик водителя на заявку от неё не устаревает. PostgreSQL — замок + повторное чтение, SQLite — CAS по версии с ограниченным повтором и полной повторной проверкой. Цена рейса и цены затронутых броней — в одной транзакции.
4. **Отмена, неявка, пауза** (`:1535`, `:1756`, `safety_logic.py:491-502`) → `release(span(брони))` после смены статуса; замки Ride → Booking прежние.
5. **Удаление аккаунта** (`account.py`): до шага 3.9 собрать `ride_id` живых броней пассажира на **чужих** активных рейсах; после `delete(Booking)` (`:456`) — их `recompute` в той же транзакции.
6. **Заявки:** `segment` — сперва `max_free`, после участка — `free_on`; `_on_route_scope` — `max(seats_left, seats_free_max)`; принятие `:1320-1328` → `take(..., extra_where=[прежние условия])`; `:1465` → `any_free_sql(req.seats)`.
7. **Выдачи:** `:529`, `:924`, `:1266`, `medical.py:64` → `any_free_sql()`; лёгкие столбцы `/rides` (`:589-602`) **и** `/rides/near` (`:978-982`) + `terms_version, seats_left, seats_free_max, leg_free`: участок однозначен → `free_on ≥ 1`, иначе `max_free ≥ 1`. Кэш → `rides:active:v3`.
8. **`RideOut`** (без запросов): `terms_version`, `seats_by_leg`, `seats_on_segment` рядом с `matched_*` (`rides.py:616-620`, `/rides/near`); `seats_booked` — только `/driver/rides`; `/driver/bookings` + `seats`, `price`. Сырой `leg_free` — нет.
9. **Правка мест** (`:447-466`): прямую смену `seats_total` при живых бронях по-прежнему запрещать; присланное прежнее число — no-op. Сохранить отдельную прежнюю ветвь автоснижения `cap` при допустимой смене опций (`rides.py:458-465`), но условие `booked_seats > cap` заменить на max занятости участка > cap. Обещанные удобства не отнимать. Остатки считать pure `occupancy_snapshot` для нового cap и записать вместе с опциями/вместимостью одним CAS/ростом terms_version; самостоятельный recompute допустим только когда он единственная запись версии.
10. **Переднее** — как сейчас (индекс базы); «сзади двое» держится: на участке мест ≤ `seat_cap`.

### Часть Б — сервер: цены остановок (в рублях, D7)
1. **`Ride.stop_prices_json: str = ""`** — JSON-**список** цен от начала по порядку частей `waypoints` (`split_raw`), не по имени: RS-15 переименовывает и переставляет остановки и присылает новый список. Вход `RideIn.stop_prices: list[int]` (по умолчанию `[]`), `RideEditIn.stop_prices: list[int] | None` (`None` — оставить), целые рубли `1..99 999`. Хранение — JSON `list[int]` в порядке остановок; выход строго один: `RideOut.stop_prices: list[StopPriceOut]`, где `StopPriceOut={stop: str, price: int}`. Выходные объекты обратно во вход не посылаются.
2. **`ride_seats.segment_price(ride, pickup, dropoff)`**: цен нет → `price`; иначе `cum(высадка) − cum(посадка)` (`cum(начало)=0`, `cum(конец)=price`, остановка — по индексу её части). **`validate_stop_prices`**: пусто → `[]`; 400 `stop_prices_need_price` (`price == 0`), `stop_prices_count` (число ≠ частям или часть — повтор/начало/конец), `stop_prices_order` (`1 ≤ p < price`, строгий рост).
3. **Публикация:** проверка → JSON; не в `dump`, в серию — копия; `_recent_twin_ride` сверяет JSON отдельно (иначе дубль).
4. **Бронь:** `BookIn.expected_price` (за место, тип как у цен) ≠ `seg` → 409 `price_changed` (+`price`); `total = seg * seats`; цены остановок есть, `expected_price` нет (старое приложение шлёт полную сумму) → `pay_amount = total`.
5. **Правка (общая транзакция RS-15):** `expected_terms_version` обязателен для нового клиента; модерация до замка, затем Ride → живые Booking, `populate_existing`, сверка версии и повторная проверка всех актуальных условий. При CAS-конфликте ничего не писать, 409 `ride_terms_changed`. `RideEditIn.stop_prices` (`None` — не трогать, `[]` — одна цена). С живыми бронями `price` не растёт **и** у каждой брони новая `segment_price` ≤ старой, иначе 409 `price_up_with_bookings`; `b.price = новая × seats`, потолок `pay_amount` как `:412-413`. Изменить Ride и все суммы Booking, поднять `terms_version` одним commit; пуши только после commit победителя.
6. **Выдача:** `RideOut.stop_prices: [{stop, price}]`, `segment_price` в рублях; пример `POST /rides` с `{"price":500,"waypoints":"Ургаза","stop_prices":[300]}` → `GET`/publish/edit-ответ `{"price":500,"stop_prices":[{"stop":"Ургаза","price":300}],"segment_price":500,"terms_version":1}` (версия здесь пример). Выбранный участок Ургаза→Акъяр → `segment_price=200`; бронь 2 мест с `expected_price=200` даёт `Booking.price=400`. Форма `stop_prices` одинакова во всех ответах; в ленту заявок — `matched_segment_price` (бронь по заявке — `price_on_table × seats`, торг).
7. **`price_hint`:** `stops` (≤ 4) → `stops: [{stop, distance_km, avg, count}]`; расстояние — свой справочник сёл, не Яндекс (платно).

### Часть В — Android (`android/`)
1. `Ride` (`Domain.kt`) + `termsVersion`, `seatsByLeg`, `seatsOnSegment`, `seatsBooked` (после А), `stopPrices`, `segmentPrice` (после Б); разбор в `ApiClient.kt`, `toUiRide` (`MainActivity.kt:750`); `PickupStopRules.kt` — `seatsOnSegment`, `segmentPriceRub` по формулам сервера.
2. Шторка: участок до числа мест (поменять блоки `BookingActiveTripScreen.kt:653` и `:700`); `BookingSeatCount` и сумма по умолчанию — от участка; «Итого» — `AnimatedContent` (`CanonMotion`), деньги — через общий `rideshareRubToKop` и `kopToRub` (D7); `expectedPrice`, `expectedTermsVersion`; ветвление по `detailCode` (`price_changed`/`ride_terms_changed` → перечитать, показать новые условия и отдельное подтверждение). Намерение группы N сохраняется при уменьшении вместимости/смене участка: не clamp до K автоматически, заблокировать отправку, предложить «Уменьшить до K» с новым итогом (RS-12); то же PWA.
3. Публикация (после Б): переключатель «Цена до каждой остановки», поле у каждой (`CanonFieldShape`, `AnimatedVisibility`), под ними — цены участков. «Поделить по расстоянию» (≥ 48dp): до 10 ₽; нарушен рост — до 1 ₽, затем «предыдущая + 1 ₽»; не помещается — поля пустые и подсказка.
4. Правка (`ProfileScreen.kt:1748`) — цены остановок; кабинет: «1 место · 300 ₽» у брони, `RideLegsLoad(ride)` (`RideshareLegsLoad.kt`) на активном рейсе, `:2937`/`:2946` — из `seatsBooked`.
5. Лента (`RideCard`, `RidesRequestsChatScreens.kt:880`): места и цена участка или «цена зависит от участка»; отклик своим рейсом (`RequestsFeedScreen.kt:111`) — `matched_segment_price`. Новых экранов нет.

### Часть Г — сайт (`webapp/`)
Те же шаги: типы `api/*.ts`; формулы в `utils/pickupStops.ts`; `RideSheet.tsx` (места, «Итого», `expected_price`, ветвление по `ApiError.code`); `CreateRideScreen.tsx` (поля, цены участков, черновик); `RideEditActions.tsx`; `DriverCabinetScreen.tsx` (`seats_booked`, загрузка); `DriverPassengers.tsx`, `RideCard.tsx`, `RequestsFeedScreen.tsx`.

## 4. База данных
Имена `<префикс>_ride_segment_seats_<дата>` (А), `<префикс>_ride_stop_prices_<дата>` (Б), префикс — по индексу; перед слиянием свежий `origin/main`, `alembic heads` → `down_revision`, одна голова; up→down→up на SQLite и PostgreSQL; сторожа `test_migrations_reach_production.py`, `test_migrations_match_the_models.py`; образец `ca_booking_segments_20261018.py`.
- **А:** `ride.terms_version BIGINT NOT NULL DEFAULT 0`, `ride.leg_free VARCHAR NOT NULL DEFAULT ''`, `ride.seats_free_max INTEGER NOT NULL DEFAULT 0`; данные не трогаем (снимок — при первой записи мест). downgrade: активным рейсам `seats_left = max(0, seats_total − Σ мест живых броней)` (`CASE`), затем снять; старый код — только после downgrade.
- **Проверка обеих миграций:** previous-main schema + синтетические старые Ride/Booking → target на SQLite и PostgreSQL; цены/бронь сохранены без пересчёта, defaults верны; оговорённый downgrade и повторный upgrade проверяются на этих данных (правила индекса N7).
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
Все сценарии ниже — **NOT_EXECUTED**, пока исполнитель не приложит реальный вывод.
**Сервер А** (`ruff check app/ --select F`, `pytest`):
- `test_segment_seats.py` (новый): трое до Ургазы → Ургаза→Акъяр бронируется, весь путь — `seats_not_enough`; отмена/неявка/пауза — свой участок; **удаление аккаунта пассажира с живой бронью возвращает место**; старые бронь и рейс — по-старому; лента, «Ближайшие», подбор; `back_max_two`; заявка своим рейсом; миграция туда-обратно (`test_pickup_stop_booking.py:509`).
- `test_seats_invariant.py`: `leg_free[i] = total − Σ живых на участке i` после любой цепочки. `test_last_seat_race.py` (`_book_together`), `test_segment_seats_race_pg.py` (новый, PostgreSQL): пересекающиеся участки — одна 200, непересекающиеся — обе 200 **с одной и той же `expected_terms_version`**; после чужой брони `terms_version` прежняя, отклик водителя на заявку принимается (`routers/requests.py:1281`). `test_request_bound_accept_seats.py`: «чужая бронь» — настоящая, не `seats_left=0`.
- Регрессии вместимости: ручной новый seats_total при живой брони →409; прислан прежний →no-op; допустимая опция снижает cap, занятые на каждом участке помещаются →один CAS/корректные остатки, max occupancy выше cap →409 без частичной записи.
- **Мутации А:** без `leg_free = :old` в CAS; `take`/`release` поднимает `terms_version` (вторая бронь получит ложное 409 `ride_terms_changed`); `span` = весь рейс; `release` на все участки; без `recompute` в `account.py` — каждая краснит тест.

**Сервер Б:** `test_segment_price.py` (новый): без цен → `price`, остановка 300/конец 500 → второй участок 200; NULL/«»; отказы с кодами, цены по порядку частей, повтор публикации, серия, `price_changed`, старая полная `pay_amount`. Контракт publish → GET → edit → booking: request `[300]`, storage `[300]`, во всех ответах `[{'stop':'Ургаза','price':300}]`; `Booking.price==200` за одно место или 400 за два, квитанция в тех же рублях, никакого 30000. Оба клиента разбирают одинаковый fixture. PostgreSQL-барьеры: 500→400 одновременно с ранее подготовленным 500→450 (и stop_prices), бронь между precheck и commit: выигравшая версия сохраняется, старый запрос 409, сумма/места согласованы. Мутации: убрать version CAS, проверку свежих цен/машины/маршрута или `expected_price` — красный тест.

**Android** (`:app:testDebugUnitTest`, `:app:assembleDebug`): `SegmentPriceTest.kt` (новый: таблица, «Поделить» — строгий рост, шаг 1 ₽), `BookingSeatCountTest.kt`, `CreateRideStopsTest.kt`; сторожа `*GuardTest` зелёные. **Сайт:** `npm run check` (`webapp/tests/pickup-stop-test.mjs` — та же таблица), `npm run build`.

## 8. Приёмка
Агент другой «семьи» (Q9), по умолчанию «не принято»; каждая часть — своим PR.
1. Проверки раздела 7 для части зелёные, вывод приложен; мутации — красные.
2. Код: места пишет только `ride_seats.py`; в выдачах нет `seats_left > 0`; Б — единый договор целых рублей (D7), влит после «да»; publish/GET/edit/booking fixture совпадает на Android/PWA; route/price/car/seats_total фиксируются атомарно с ростом terms_version; бронь и отмена версию не меняют.
3. Эмулятор (вторая сторона — сайт, Q11), RU и BA × светлая и тёмная:
   - места: трое (сайт) берут Сибай → Ургаза; Ургаза → Акъяр (Android) — «на твоём участке 3», бронь 2 места; водитель видит загрузку; отмена одного до Ургазы освобождает только первый участок;
   - цены: 300/500 ₽, видны цены участков, «Поделить» и ошибки понятны; Ургаза → Акъяр — «200 ₽ за место», 2 места → «Итого 400 ₽», квитанция 400 ₽; цена вверх при бронях — отказ, вниз — пуш;
   - рейс без цен остановок — как раньше; длинный BA не ломает узкий экран.
4. Двигает W1-E2E-01 (участок → цена → квитанция), -02 (свой рейс по заявке), -03 (последнее место, отмена, неявка, удаление аккаунта); остаются RS-16, W1-E2E-07 (Q11), BA носителем. Статус — только `docs/audit-blocks.md`.

**Мозг:** `architecture.md` — `ride_seats.py` и новые поля; `decisions.md` — «снимок + CAS», решение о единицах; `tasks.md` — BA-черновики; `lessons.md` — «пауза и удаление аккаунта не возвращали места».

## 9. Риски и границы
- **Деньги:** целые рубли (D7, решено 24.10); Б — в ветке, «да» на слияние и выкладку денежной части; онлайн-оплаты нет (Q5).
- **Старые приложения:** рейс с местом на части пути — «0 мест»; бронь без участка — весь рейс, полная цена.
- **Приватность:** `seats_by_leg` — загрузка без имён, как «осталось N мест»; пуш «Новая бронь» — без села.
- **Не входит:** переднее по участкам, фильтр мест (RS-12), правка остановок (RS-15: новый список цен + `recompute`), точки встречи (RS-03).

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 2026-10-24 | не начато | — | — | план написан; правки приёмки: единицы — вопрос, деление А–Г, удаление аккаунта |
