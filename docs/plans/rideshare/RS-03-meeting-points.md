# RS-03 — Точное место встречи и высадки

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — миграция рабочей базы (часть А), ночная зачистка координат (удаление данных, часть А) |
| Зависит от | RS-01 (`herr(code=)`, `ApiError.code`), RS-02 (общая зона брони и цепочка миграций) — строго после их слияния |
| Не параллельно с | по «Очереди по общим файлам» индекса: RS-01, RS-02, RS-04, RS-05, RS-06, RS-07, RS-08, RS-13, RS-14, RS-15 (`bookings.py`, `models.py`, `auth.py`, `config.py`, `cleanup.py`, `ApiClient.kt`, `TripPass.kt`, `BookingActiveTripScreen.kt`, `RideSheet.tsx`, `ActiveTripScreen.tsx`) |
| Деление на PR | `[RS-03А]` точка при брони, видимость, зачистка, «Мои данные» (вся миграция — здесь); `[RS-03Б]` предложить/сменить место (три команды); `[RS-03В]` «Подъезжаю» по точке. Внутри части — сервер → сайт → Android (D4) |
| Проверка `yuldash-security-privacy` | да — точные координаты, видимость, срок хранения, выгрузка |
| Двигает сценарии приёмки | W1-E2E-01, W1-E2E-05 (`docs/audit-traceability/rideshare-scope.md`) |
| Зона файлов | `backend/app/`: `models.py`, `config.py`, `meeting_points.py`*, `routers/meeting.py`*, `routers/__init__.py`, `routers/bookings.py`, `routers/auth.py`, `cleanup.py`; миграция* в `backend/alembic/versions/`; `backend/tests/test_meeting_points.py`*, `test_meeting_points_privacy.py`*, `test_cleanup.py`. `android/app/src/main/java/com/yuldash/app/`: `data/ApiClient.kt`, `data/BookingDtos.kt`, `data/DriverBookings.kt`, `data/TripPass.kt`, `PickupStopPicker.kt`, `BookingActiveTripScreen.kt`, `DriverPassengerRow.kt`, `MeetingPointPicker.kt`*; `android/app/src/test/.../MeetingPointTest.kt`*. `webapp/src/`: `api/bookings.ts`, `components/YandexMap.tsx`, `components/RideSheet.tsx`, `components/DriverPassengers.tsx`, `components/MeetingPointPicker.tsx`*, `screens/ActiveTripScreen.tsx`; `webapp/tests/meeting-point-test.mjs`*, `webapp/tests/run.mjs`. `docs/{tasks,decisions,architecture,lessons}.md`. (* — новый файл) |
| Источник | `master/part-001.md` п. 5, п. 13; `master/part-002.md` контракты 4–6, «Места и календарь», K01–K02; решения 24.10: Q15 а, Q14 а, Q10 а |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Было.** Айгуль бронирует «Сибай → Акъяр через Ургазу», в брони — только «Ургаза». Водитель въезжает в село и звонит:
«Ты где — у школы, у магазина?». Пять минут поиска, зимой — на морозе. Высадка — «где-нибудь в Акъяре».

**Стало.** По желанию Айгуль ставит булавку на карте или выбирает ориентир («у магазина») и пишет «синие ворота напротив школы»;
так же — где выйти. До подтверждения водитель видит только «Ургаза · место указано» — чужой не узнает, где она живёт. После —
точка, пояснение и «Маршрут» в навигатор; когда поездка закончилась — снова только «место было указано». Передумала — «Предложить другое место»; пока водитель не согласился, действует прежнее.
«Подъезжаю» сверяет машину с её точкой. Через месяц после поездки точка стирается сама, при удалении аккаунта — сразу.

## 2. Что уже есть в коде
- `backend/app/models.py:580-587` — у брони только названия `pickup_stop`/`dropoff_stop` («Координат НЕ храним»); `:373-375` — у рейса `pickup` и пин `pickup_lat/lng`; `:452-468` — `PickupPoint` (`is_seed`).
- `backend/app/ride_stops.py:13-16` — точка остановки из справочника; `stop_point` `:225`, `pickup_of` `:305`, `VILLAGE_KINDS` `:41`.
- `services.py:1860-1888` — наружу только `is_seed`; `:1811-1840` — сид для 5 городов, координаты примерные, сёл нет; `:1620-1645` — `public_ride_payload` без `pickup*`.
- `backend/app/routers/bookings.py:276-295` `BookIn`; `:306-360` разбор места; `:421-446` повтор и `*_conflict` с `booking_id`; `:610-628` списание мест; `:660-671` пуш без названия села.
- `bookings.py:675-774` `/details`: точка только при `booking_contacts_open` (`:689`, `:765-767`); `visibility.py:300-319` — окно confirmed/onboard + 48 ч.
- `bookings.py:963-995` `_verify_arrival` (#186) — только пин рейса; `:998-1014` `_nearest_m`; `:1096-1121` применение; пороги `config.py:389-399`.
- `bookings.py:1826-1894` `/driver/bookings`; `livepos.py:1-13` — точка водителя только в Redis.
- `routers/auth.py:1063-1109` `_location_summary`; `:1112-1152` `/me/data`; `:1155+` `/me/export` (`:1168-1170` «координаты не идут»).
- `account.py:281-284,456` — удаление аккаунта удаляет брони; `cleanup.py:145-281` — правила только удаляют строки, брони с оценкой/чатом/SOS живут всегда (`:183-201`).
- `errors.py:11-13` — `herr` без кода (код добавляет RS-01); сейчас код даёт `_pickup_refusal` (`bookings.py:302-303`).
- `antifraud.py:446-458` — `log_text_flag` (зовётся из `moderate_open_text`, `:492-493`) делает `session.commit()` сессии вызывающего.
- `data/TripPass.kt:13-24` — офлайн-паспорт пассажира (у водителя его нет, `BoardingCodeRules.kt:26-28`); сохраняется при confirmed/onboard (`BookingActiveTripScreen.kt:368-373`), стирается при done/cancelled (`:393-405`).
- Android: `PickupStopPicker.kt:117-151` `BookingSegmentPicker`; `BookingActiveTripScreen.kt:887-933` `BookingMeetingCard` (`geo:` `:923`); `MapScreen.kt:1961` `PickupPickerOverlay(..., hint)`; `CreateRideScreen.kt:1084` `PickupSuggestionChips`; `DriverPassengerRow.kt:54`; `data/TripPass.kt:41,65`; `data/ApiClient.kt:1078-1100`, `:5446` `detailCode`.
- Сайт: `components/RideSheet.tsx:135-144`; `screens/ActiveTripScreen.tsx:936-939`; `components/DriverPassengers.tsx:77,249`; `components/YandexMap.tsx:110` (выбора точки нет); `api/geo.ts:84`; `api/client.ts:150-158` (`ApiError` без `code`).
- Своей точки пассажира, команды смены и зачистки — не нашёл.

## 3. Что сделать
### Сервер (`backend/`)
1. **Модель** `Booking`: `meet_lat`, `meet_lng`, `alight_lat`, `alight_lng: Optional[float]`; `meet_note`, `alight_note: str = ""` (≤120);
   `meet_landmark_id`, `alight_landmark_id: Optional[int]` без внешнего ключа; `meet_version: int = 0`; `meet_proposal: str = ""`
   (JSON `kind, lat, lng, note, landmark_id, clear, at`); `meet_changes: int = 0`.
2. **`config.py`:** `meeting_point_village_km = 3.0`, `meeting_point_city_km = 15.0`, `meeting_point_keep_days = 30`,
   `meeting_point_max_changes = 5`, `arrival_verify_meeting_enabled = True`.
3. **`meeting_points.py`:** `MeetingIn` (`lat` −90…90, `lng` −180…180, `note` ≤120, `landmark_id`, `clear`); `validate()` — числа
   конечные, не `(0,0)`; ориентир только `is_seed` (иначе 400 `meeting_landmark_unknown`); пояснение — `moderate_open_text(...,
   check_contact=False, place="meeting_note")`. Опора: остановка — `stop_point`, начало — пин рейса / `from_lat/lng`, конец — `to_lat/lng`;
   радиус по виду (село/райцентр — village, город — city), опора неизвестна — `stop_corridor_km` от прямой (`_to_segment_km`); дальше —
   400 `meeting_point_far`. Место не выбрано — 409 `meeting_point_needs_stop`. Плюс `passenger_view`, `driver_view(status)`,
   `gps_target`. Координаты — никогда в лог, ошибку, пуш, аналитику. Модерация коммитит сессию (`antifraud.py:446-458`) и
   снимает `FOR UPDATE` — звать её до любых изменений объектов; запись дальше — только условным `UPDATE`.
   Все ошибки плана — `herr(status, ru, ba, code=...)` из RS-01.
4. **[А] Бронь:** `BookIn.meeting`, `BookIn.alighting`; проверка после разбора места (`:528-533`), до списания мест. `_booking_retry`:
   другая точка — 409 `meeting_point_conflict` с `booking_id`, та же — существующая бронь. В пуш — « · место встречи указано».
5. **[Б] Команды** (`routers/meeting.py`, регистрация `routers/__init__.py:7,26`; права — `booking_and_ride_for_user`, `services.py:231`):
   - `POST /bookings/{id}/meeting-point` — пассажир (иначе 403 `meeting_point_not_yours`): `kind` (`pickup`|`dropoff`), `point`,
     `base_version`. `pending` — применяем; `confirmed` — предложение, пуш водителю без места; `onboard` — только `dropoff`
     (409 `meeting_point_onboard`); `done`/`cancelled` — 409 `meeting_point_closed`; предел смен — 429 `meeting_point_too_many`;
     повтор того же — 200 без пуша. Запись — `UPDATE ... WHERE meet_version = :base AND status IN (...)`, 0 строк — 409 `meeting_point_stale`.
   - `POST /bookings/{id}/meeting-point/answer` — водитель: `proposal_at`, `accept`; `UPDATE ... WHERE meet_proposal = :прочитанное`;
     «да» — в поля и `meet_version + 1`, «нет» — снять; пуш пассажиру. Предложения нет — 200 `already: true`.
   - `POST /bookings/{id}/meeting-point/withdraw` — пассажир отзывает, повтор — 200.
6. **[А] Видимость** (`/details`, `/driver/bookings`, `/bookings/mine`): пассажир — своё всегда. Водитель — `meeting_point_set` и
   `meeting_proposal_pending` всегда, а координаты, пояснение, ориентир и предложение — **только при статусе брони `confirmed`/`onboard`**
   (D9; не по окну `booking_contacts_open`: после `done` — только «место было указано», `pending`/`cancelled` — то же). Старые
   `pickup*` в `/details` отдают точку пассажира только той стороне и только в то окно, где точка видна по правилу выше; иначе — как
   сейчас (`bookings.py:765-767`). Новые поля: `pickup_note`, `pickup_landmark_ru/ba`, `pickup_source`,
   `dropoff_lat/lng/note/landmark_ru/ba`, `meeting_version`, `meeting_proposal`. Не отдавать: публичная карточка, `routers/share.py`,
   квитанция (`:847-880`), другие пассажиры.
7. **[В] «Подъезжаю»** (`:1096-1121`): есть `gps_target` — `_nearest_m(driver_points_for_ride(..., with_ride_point=True), цель)`; отказ
   только дальше `stop_refuse_km`, «подтверждено» ближе `arrival_verify_radius_m` (булавка на глаз — ложный отказ дороже, `:978-980`).
   До части В — прежние ветки.
8. **[А] Зачистка** (`cleanup.py`): `scrub_meeting_points(now)` пачками по 2000 обнуляет `meet_*`, `alight_*`, `meet_proposal`:
   `cancelled` без `confirmed_at` — в ближайшую ночь; `done`/`cancelled` — когда `ride.depart_at < now − meeting_point_keep_days`
   (D9: от выезда, через `JOIN ride`); кроме открытых SOS, `Incident` (`models.py:757`), `Report` (`new`/`reviewing`).
   Вызов в `main()` (`:893-946`), сухой прогон — только счёт.
9. **[А] Мои данные:** свои точки — в `route_location_points`; `/me/data` — `meeting_points_days`; `/me/export` — у своих броней ориентир,
   пояснение и координаты; точки пассажиров водителю не идут; `:1168-1170` → «чужие координаты не идут».
10. **[А] Удаление аккаунта** — код не меняется (брони уходят целиком), только тест.

### Android (`android/`)
1. DTO — новые поля null-безопасно; `ApiClient.kt` — точки в бронь (А) и три новые команды (Б), отказы по `detailCode` (RS-01).
   `TripPass.kt` — **пассажирский** офлайн-паспорт: своя точка туда (в селе без сети она нужнее всего), стирается вместе с паспортом
   при done/cancelled (`BookingActiveTripScreen.kt:393-405`). Исполнитель проверяет, что `saveTripPass` (`:370-372`) не зовётся при
   `role == "driver"`; зовётся — добавить это условие: точка пассажира на телефоне водителя не хранится.
2. `MeetingPointPicker.kt`: «Уточнить место (по желанию)» → шторка: `PickupSuggestionChips`, «На карте» → `PickupPickerOverlay(hint)`,
   пояснение. Без ключа MapKit — заглушка оверлея, остальное работает.
3. `PickupStopPicker.kt` — строка под выбором посадки/высадки; `BookingMeetingCard` — своя точка, «Изменить место» / «Предложить
   другое место», ожидание, «Отозвать».
4. `DriverPassengerRow.kt`: до подтверждения — «Место указано — откроется после подтверждения»; после — ориентир, пояснение,
   «Маршрут» (`geo:lat,lng?q=lat,lng(Место встречи)` через `Intent.createChooser`, без имени пассажира, манифест не трогать);
   предложение — «На карте», «Согласиться», «Оставить прежнее».
5. `appText`, `Canon*`, цели ≥48dp, плашки — `AnimatedVisibility` с `CanonMotion`. Нового экрана нет — ⭐ п. 4 не нужен.

### Сайт (`webapp/`)
1. `api/client.ts` — `code` в `ApiError` (`:383-391`), если нет; `api/bookings.ts` — поля и три функции.
2. `YandexMap.tsx` — «пин в центре» + `onCenterChange`; `MeetingPointPicker.tsx`.
3. `RideSheet.tsx` — «Уточнить место»; `ActiveTripScreen.tsx` — точка, предложение, ожидание; `DriverPassengers.tsx` — как Android
   п. 4, «Маршрут» — `https://yandex.ru/maps/?rtext=~<lat>,<lng>&rtt=auto` (`rel="noopener"`).

## 4. База данных
`<следующий префикс>_booking_meeting_points_<дата>`, `down_revision` — голова после `cc_ride_segment_seats` (RS-02; не ветвить с
RS-05/06/14). 11 колонок из шага 1, идемпотентно (образец `ca_booking_segments_20261018.py`); старые брони — NULL, правила прежние;
`downgrade` снимает колонки. **Ждёт «да».**

## 5. Надписи
Русские; башкирский — черновик исполнителя (`yuldash-bashkir-linguist`) в `docs/tasks.md`, «Переводы на проверку».
Экран: «Уточнить место (по желанию)», «Где тебя забрать?», «Где тебя высадить?», «Выбери ориентир или поставь точку на карте»,
«Пояснение: ворота, подъезд, что рядом», «Место указано — откроется после подтверждения», «Маршрут», «Предложить другое место»,
«Ждём ответа водителя — пока действует прежнее место», «Пассажир просит встретиться в другом месте», «Согласиться», «Оставить прежнее»,
«Ориентиров пока нет — поставь точку».
Пуши: «Пассажир предлагает другое место встречи», «Водитель согласился на новое место», «Водитель оставил прежнее место — договоритесь
в чате». Ошибки: «Точка далеко от «<место>» — выбери место в самом селе или договорись в чате», «Сначала выбери, где садишься»,
«Ты уже в машине — место посадки не меняется», «Место только что изменилось — посмотри ещё раз».

## 6. Состояния экрана
Ориентиры: скелетон / «Не получилось загрузить. Повторить?» / пусто — «Ориентиров пока нет — поставь точку». Нет карты или сети —
заглушка. Отправка — кнопка в загрузке, без второго запроса; отказ — текст сервера, ввод сохранён. Длинный BA — перенос; тёмная — `Canon*`.

## 7. Тесты
**Сервер** (`ruff check app/ --select F`, `pytest`):
- `test_meeting_points.py`: точка у остановки и в начале; дальше радиуса — 400 и места целы; чужой ориентир — 400; `pending` —
  применилось; `confirmed` — предложение и один пуш, повтор — без пуша; ответ «да»/«нет»; устаревший — 409 / `already`; гонка двух
  запросов с одной версией — проходит один; лимит смен; `onboard` — только высадка; «Подъезжаю»: 4 км — без отказа и без
  «подтверждено», 300 м — «подтверждено», 7 км — отказ, рубильник выкл. — как раньше.
- `test_meeting_points_privacy.py`: водитель до подтверждения и через 48 ч после поездки — без точки, в окне — с ней; другой пассажир,
  гость, `/rides`, `/rides/{id}`, страница близкого, квитанция — без точки; caplog без координат; `/me/export`; удаление аккаунта
  пассажира и водителя.
- `test_cleanup.py`: отменённая без подтверждения — стёрта в первую ночь; завершённая — через 30 дней; с открытым SOS/разбором — нет;
  сухой прогон ничего не меняет.
- Мутации: убрать окно связи / условие версии в `UPDATE` / отказ 800 м — тесты краснеют.

**Android:** `MeetingPointTest.kt` + все `*GuardTest` (особенно `MapKitGateGuardTest`, `TouchTargetGuardTest`, `ReachableApiGuardTest`); `:app:assembleDebug`.
**Сайт:** `tests/meeting-point-test.mjs` в `ALL_SUITES`; `npm run check`, `npm run build`.

## 8. Приёмка
Агент другой «семьи» (Q9), по умолчанию «не принято».
1. Приложены зелёные выводы: `ruff`, полный `pytest`, `npm run check`, `npm run build`, `:app:assembleDebug`, тесты Android.
2. Эмулятор, Android-пассажир + сайт-водитель и наоборот, RU/BA × светлая/тёмная: бронь «Сибай → Акъяр через Ургазу» с ориентиром
   и пояснением → водитель видит только «место указано» → подтвердил → точка, пояснение, «Маршрут» открывает карту → пассажир
   предлагает другое место → «Согласиться» → новая точка у обоих; второй круг — «Оставить прежнее».
3. «Подъезжаю» с эмулятором в 4 км от точки — без отказа, в 200 м — «подтверждено по GPS»; узкий экран, крупный шрифт, без ключа
   карты — вёрстка цела; ответы `/details` и `/driver/bookings` водителю до подтверждения — без координат (вывод приложить).
4. Двигает W1-E2E-01 и W1-E2E-05. Остаётся: живые телефоны и GPS (W1-E2E-07, Q11), BA носителем, RS-16. Статус — `docs/audit-blocks.md`.

## 9. Риски и границы
- Не входит: смена точки водителем (чат), живая точка пассажира, ориентиры сёл (наполняет Александр).
- Сид примерный — карта открывается на ориентире и просит проверить булавку.
- Своя точка в выгрузке — решение плана (данные самого человека). Срок 30 дней — новое правило хранения → `docs/decisions.md`
  и `/me/data`; на проде сначала сухой прогон.
- Общая зона с RS-02, RS-05, RS-07 (`bookings.py`, `models.py`) — по очереди (Q10); с RS-05 точка входит в отпечаток тела брони.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан |
