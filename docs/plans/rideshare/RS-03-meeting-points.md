# RS-03 — Точное место встречи и высадки

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — миграция рабочей базы (новые поля брони), ночная зачистка координат (удаление данных), выкладка |
| Зависит от | RS-02 (общая зона брони: `Booking`, `BookIn`, `book()`, цепочка миграций) — делать строго после слияния RS-02 |
| Двигает сценарии приёмки | W1-E2E-01 (место посадки и высадки), W1-E2E-05 (точка закрыта до подтверждения) (`docs/audit-traceability/rideshare-scope.md`) |
| Зона файлов | сервер: `backend/app/models.py` (Booking), `backend/app/config.py`, новый `backend/app/meeting_points.py`, новый `backend/app/routers/meeting.py`, `backend/app/routers/__init__.py`, `backend/app/routers/bookings.py`, `backend/app/routers/auth.py` (`/me/data`, `/me/export`), `backend/app/cleanup.py`, новая миграция в `backend/alembic/versions/`, новые `backend/tests/test_meeting_points.py`, `backend/tests/test_meeting_points_privacy.py`; Android: `data/ApiClient.kt`, `data/BookingDtos.kt`, `data/DriverBookings.kt`, `data/TripPass.kt`, `PickupStopPicker.kt`, `BookingActiveTripScreen.kt`, `DriverPassengerRow.kt`, новый `MeetingPointPicker.kt`, новый тест `MeetingPointTest.kt`; сайт: `webapp/src/api/bookings.ts`, `webapp/src/api/client.ts` (поле `code`), `webapp/src/components/YandexMap.tsx`, `webapp/src/components/RideSheet.tsx`, `webapp/src/screens/ActiveTripScreen.tsx`, `webapp/src/components/DriverPassengers.tsx`, новый `webapp/src/components/MeetingPointPicker.tsx`, новый `webapp/tests/meeting-point-test.mjs`, `webapp/tests/run.mjs`; мозг: `docs/tasks.md`, `docs/decisions.md` |
| Источник | исследование: `master/part-001.md` п. 5 «Точные места встречи и высадки P0», п. 13 (условия брони не меняются молча); `master/part-002.md` контракты 4–6, «Места и календарь» (устаревшие координаты, отказ GPS), K01–K02; решения Александра 24.10: Q15 а, Q14 а, Q10 а |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Было.** Айгуль бронирует поездку «Сибай → Акъяр через Ургазу». В брони записано только «Ургаза». Водитель
въезжает в село и звонит: «Ты где — у школы, у магазина, на трассе у поворота?». Пять минут поиска, а зимой —
пять минут на морозе. Высадка так же: «где-нибудь в Акъяре».

**Стало.**
- При брони Айгуль может уточнить место: поставить булавку на карте или выбрать ориентир («у магазина»,
  «у мечети» — где справочник их знает) и дописать пояснение: «синие ворота напротив школы». Так же — где выйти:
  «у больницы в Акъяре». Это по желанию: без точки всё работает, как сейчас.
- Пока водитель не подтвердил бронь, он видит только «Ургаза · место указано». Где живёт Айгуль, чужой
  человек не узнает. После подтверждения — точка на карте, пояснение и кнопка «Маршрут» (откроет навигатор).
- Передумала, где встречаться, после подтверждения — нажимает «Предложить другое место». Водитель соглашается
  одной кнопкой или оставляет прежнее. Пока не согласился, действует старая точка — никто не едет «в никуда».
- Кнопка водителя «Подъезжаю» сверяет машину с точкой Айгуль, а не с центром села.
- Точка — её личные данные: через месяц после поездки стирается сама, при удалении аккаунта — сразу,
  а в «Скачать мои данные» видна ей самой.

## 2. Что уже есть в коде
- `backend/app/models.py:580-587` — у брони только названия `pickup_stop`/`dropoff_stop`; комментарий: «Координат НЕ храним».
- `backend/app/ride_stops.py:13-16` — точка остановки берётся из справочника сёл; `stop_point` — `:225-243`; `start_point` — `:246-254`; `pickup_of` — `:305-323`; `VILLAGE_KINDS` — `:41`.
- `backend/app/models.py:373-375` — у рейса `pickup` (текст) и пин `pickup_lat`/`pickup_lng` водителя.
- `backend/app/models.py:452-468` — справочник ориентиров `PickupPoint` (RU+BA, координаты, `is_seed`).
- `backend/app/routers/pickup.py:28-38` — `GET /pickup-points` без входа; `backend/app/services.py:1860-1888` — наружу только `is_seed`.
- `backend/app/services.py:1811-1840` — сид ориентиров только для Уфы, Сибая, Баймака, Белорецка, Учалов, координаты = центр города + смещение (то есть примерные). Ориентиров сёл нет.
- `backend/app/routers/bookings.py:276-295` — `BookIn` (`pickup_stop`, `dropoff_stop`); разбор места — `:306-360`; повтор брони и конфликты `pickup_stop_conflict` — `:421-446`; атомарное списание мест — `:610-628`; пуш «Новая бронь» без названия села — `:660-671`.
- `backend/app/routers/bookings.py:675-774` — `/bookings/{id}/details`: `unlocked = booking_contacts_open(...)` (`:689`), точка `pickup`/`pickup_lat` только при `unlocked` (`:765-767`), на остановке пин рейса не отдаётся (`:717-719`).
- `backend/app/visibility.py:300-319` — `booking_contacts_open`: confirmed/onboard и 48 ч после поездки (`chat_after_trip_hours`, `config.py:980`).
- `backend/app/routers/bookings.py:963-995` — `_pickup_point`/`_verify_arrival` (#186): сверка «Подъезжаю» только с пином рейса; `:998-1014` — `_nearest_m`; `:1017-1031` — сверка с остановкой; применение — `:1096-1121`. Пороги: `config.py:389-399` (800 м, 5 км, 2 км).
- `backend/app/routers/bookings.py:1826-1894` — `/driver/bookings`: место посадки видно водителю и до подтверждения (название села).
- `backend/app/livepos.py:1-13` — координаты водителя только в Redis с коротким сроком, в базу не пишутся; `backend/app/routers/location.py:194` — `driver_point_for_booking`.
- `backend/app/services.py:1620-1645` — `public_ride_payload` вырезает `pickup`/`pickup_lat`/`pickup_lng` из публичной карточки.
- `backend/app/routers/auth.py:1063-1109` — `_location_summary` считает только пин рейса; `/me/data` — `:1112-1150`; `/me/export` — `:1155+`, в описании `:1168-1170` «координаты … в выгрузку не идут», место посадки названием — `:1222-1233`.
- `backend/app/account.py:281-284`, `:456` — удаление аккаунта удаляет брони пассажира и брони на рейсах водителя.
- `backend/app/cleanup.py:183-201` — брони с оценкой/сообщениями/SOS не удаляются никогда; правил «стереть поле» для броней нет (`_rules` — только DELETE, `:145-281`).
- `backend/tests/test_geo_logs_without_coordinates.py` — сторож «координаты не в логе».
- `backend/app/errors.py:11-13` — `herr(status, ru, ba)` без машинного кода; код отдаёт `_pickup_refusal(status, code, ru, ba, **extra)` (`bookings.py:302-303`).
- Android: `PickupStopPicker.kt:117-151` — `BookingSegmentPicker` (выбор посадки/высадки по названиям); `BookingActiveTripScreen.kt:887-933` — `BookingMeetingCard` с `geo:`-ссылкой «Открыть точку на карте»; `MapScreen.kt:1961-1969` — `PickupPickerOverlay(initial, onConfirm, onDismiss, hint)` с проверкой ключа MapKit; `CreateRideScreen.kt:1084-1101` — `PickupSuggestionChips(city, selectedId, onSelect)`; `DriverPassengerRow.kt:54` — участок пассажира у водителя; `data/TripPass.kt:41,65` — офлайн-пропуск хранит `pickup_lat`.
- Сайт: `webapp/src/components/RideSheet.tsx:70-71,135-144` — бронь с `pickup_stop`/`dropoff_stop`; `webapp/src/screens/ActiveTripScreen.tsx:936-939` — только текст `pickup`; `webapp/src/components/DriverPassengers.tsx:77,249`; `webapp/src/components/YandexMap.tsx:110` — карта без режима выбора точки; `webapp/src/api/geo.ts:84` — ориентиры; `webapp/src/api/client.ts:150-158` — `ApiError` без машинного кода.
- Выбора точной точки пассажиром, точки высадки, команды смены точки и зачистки координат брони — не нашёл.

## 3. Что сделать
### Сервер (`backend/`)
1. **Модель.** В `Booking` (`models.py`, рядом с `:585-587`): `meet_lat`, `meet_lng`, `alight_lat`, `alight_lng` (`Optional[float]`);
   `meet_note`, `alight_note` (`str = ""`, до 120 знаков); `meet_landmark_id`, `alight_landmark_id` (`Optional[int]`, **без** внешнего
   ключа: ориентир — справочник; нет строки — показываем только пояснение); `meet_version: int = 0`; `meet_proposal: str = ""`
   (JSON предложения: `kind`, `lat`, `lng`, `note`, `landmark_id`, `clear`, `at`); `meet_changes: int = 0`. Комментарий: ПДн, наружу — по п. 6.
2. **Настройки** (`config.py`, рядом с `:389-407`): `meeting_point_village_km = 3.0`, `meeting_point_city_km = 15.0`,
   `meeting_point_keep_days = 30`, `meeting_point_max_changes = 5`, `arrival_verify_meeting_enabled = True`.
3. **Модуль `meeting_points.py`** — вся логика в одном месте:
   - `MeetingIn` (pydantic): `lat` (`ge=-90, le=90`), `lng` (`ge=-180, le=180`), `note` (`max_length=120`), `landmark_id`, `clear: bool = False`.
   - `validate(session, ride, pickup_stop, dropoff_stop, kind, body, entry)`: числа конечные, не `(0, 0)`; `landmark_id` — только
     `PickupPoint.is_seed` (иначе 400 `meeting_landmark_unknown`), без координат — берём координаты ориентира; пояснение обрезаем и
     проверяем `moderate_open_text(..., check_contact=False, place="meeting_note")` (не режет, только помечает).
   - Опорная точка: посадка на остановке — `ride_stops.stop_point`; в начале — `ride.pickup_lat/lng`, иначе `from_lat/lng`;
     высадка на остановке — `stop_point`, в конце — `to_lat/lng`. Радиус: вид из `VILLAGE_KINDS` — `meeting_point_village_km`, город —
     `meeting_point_city_km`; опора неизвестна — не дальше `stop_corridor_km` от прямой «откуда → куда» (`ride_stops._to_segment_km`).
     Дальше — 400 `meeting_point_far` («Точка далеко от «Ургаза». Выбери место в самом селе или договорись в чате»).
   - Место посадки не выбрано (`pickup_stop is None`) — 409 `meeting_point_needs_stop`; высадка `None` — так же для `alighting`.
   - `driver_view(booking, ride, open)` / `passenger_view(booking)` — словари для ответов (п. 6); `gps_target(booking)` — `(lat, lng)` или `None`.
   - Ни одна функция не пишет координаты в лог, в текст ошибки, в пуш и в аналитику.
4. **Бронь с точкой.** `BookIn` (`bookings.py:276-295`): `meeting: Optional[MeetingIn]`, `alighting: Optional[MeetingIn]`. Проверка — сразу
   после `_resolve_pickup_stop`/`_resolve_dropoff_stop` (`:528-533`), **до** списания мест: отказ 400 не трогает места. Повтор
   (`_booking_retry`, `:421-446`): есть живая бронь с другой точкой — 409 `meeting_point_conflict` с `booking_id` (как
   `pickup_stop_conflict`), та же точка — вернуть существующую. В пуш «Новая бронь» (`:663-669`) — « · место встречи указано» без названия.
5. **Команды смены точки** — новый роутер `routers/meeting.py` (регистрация в `routers/__init__.py:7,26`), права через
   `booking_and_ride_for_user` (`services.py:231`), ошибки — локальный помощник по образцу `_pickup_refusal` (машинный `code` + `ru` + `ba`):
   - `POST /bookings/{id}/meeting-point` — только пассажир брони (иначе 403 `meeting_point_not_yours`). Тело: `kind` (`pickup`|`dropoff`),
     `point: MeetingIn`, `base_version`. `base_version != meet_version` — 409 `meeting_point_stale` с текущим состоянием.
     Статус `pending` — применяем сразу (водитель точку ещё не видел). `confirmed` — пишем предложение, точка не меняется, пуш водителю
     «Пассажир предлагает другое место встречи» (без места, `ref_kind="booking"`). `onboard` — только `dropoff` (иначе 409
     `meeting_point_onboard`). `done`/`cancelled` — 409 `meeting_point_closed`. Счётчик `meet_changes >= meeting_point_max_changes` —
     429 `meeting_point_too_many` («Место меняли уже много раз — договоритесь в чате»). Тот же самый запрос второй раз — 200 без пуша.
     Применение и запись предложения — условным `UPDATE ... WHERE id = :id AND meet_version = :base AND status IN (...)`;
     0 строк — перечитать и 409 `meeting_point_stale`.
   - `POST /bookings/{id}/meeting-point/answer` — только водитель рейса. Тело: `proposal_at` (метка из предложения), `accept`.
     Условный `UPDATE ... WHERE meet_proposal = :прочитанное`: согласие — значения в поля, `meet_version + 1`, предложение очищено,
     пуш пассажиру «Водитель согласился на новое место»; отказ — предложение очищено, пуш «Водитель оставил прежнее место — договоритесь
     в чате». Предложения уже нет (ответил со второго телефона) — 200 `{"already": true, ...}` с текущим состоянием.
   - `POST /bookings/{id}/meeting-point/withdraw` — пассажир отзывает своё предложение; повтор — 200.
   - Гонка «предложение пассажира» vs «ответ водителя» разводится условием на `meet_proposal`/`meet_version` в самом `UPDATE` (работает и
     на SQLite, где `FOR UPDATE` пуст).
6. **Кто что видит** (`booking_details`, `:675-774`; `/driver/bookings`, `:1826-1894`; `/bookings/mine`, `:1774`):
   - Пассажир — свою точку всегда, пока она не стёрта; плюс своё предложение.
   - Водитель — `meeting_point_set` и `meeting_proposal_pending` всегда; координаты, пояснение, ориентир и содержимое предложения —
     только при `booking_contacts_open(booking, ride)`.
   - Старые поля `pickup`/`pickup_lat`/`pickup_lng` (`:765-767`): есть своя точка — отдаём её (текст = ориентир, иначе пояснение,
     иначе название остановки), иначе прежняя логика. Старые клиенты сразу ведут к правильной точке.
   - Новые поля: `pickup_note`, `pickup_landmark_ru`, `pickup_landmark_ba`, `pickup_source` (`passenger`|`ride_pin`|`stop`|`""`),
     `dropoff_lat`, `dropoff_lng`, `dropoff_note`, `dropoff_landmark_ru`, `dropoff_landmark_ba`, `meeting_version`, `meeting_proposal`.
   - Не отдавать: публичная карточка рейса (`public_ride_payload`), страница близкого (`routers/share.py`), квитанция
     (`trip_receipt`, `:847-880` — ей делятся), другие пассажиры рейса.
7. **«Подъезжаю»** (`driver_status`, `:1096-1121`): `gps_target(booking)` есть и `arrival_verify_meeting_enabled` — расстояние
   `_nearest_m(livepos.driver_points_for_ride(..., with_ride_point=True), цель)`; отказ (существующий текст «Ты ещё далеко…») только
   если дальше `stop_refuse_km`, «подтверждено по GPS» — ближе `arrival_verify_radius_m`. Порог отказа нарочно широкий: булавку ставят
   на глаз, а ориентиры из сида примерные — ложный отказ дороже пропущенного обмана (`:978-980`). Нет своей точки — прежние ветки без изменений.
8. **Зачистка** — `cleanup.py`: функция `scrub_meeting_points(now)` + константа `MEETING_DAYS`. Обнуляет `meet_*`, `alight_*`,
   `meet_proposal` пачками (`id IN (SELECT ... LIMIT 2000)`, как `_delete_batched`) у броней: `cancelled` и `confirmed_at IS NULL`
   (точку видел только сам пассажир) — в ближайшую ночь; `done`/`cancelled` — через `meeting_point_keep_days` после
   `COALESCE(done_at, cancelled_at, created_at)`. Не трогаем, пока открыт SOS (`status='open'`), разбор `Incident`
   (`open`/`awaiting_response`/`under_review`/`appealed`, `models.py:757`) или жалоба `Report` (`new`/`reviewing`). Вызов в `main()`
   (`:893-946`): в сухом прогоне — только счёт, иначе — обнуление; ошибка не роняет чистку.
9. **Мои данные.** `_location_summary` — добавить свои точки броней пассажира в `route_location_points`; `/me/data` — ключ
   `meeting_points_days`; `/me/export` — у своих броней строка «место встречи: <ориентир/пояснение>, точка <широта, долгота>»
   и так же высадка; водителю точки пассажиров в выгрузку не идут. Уточнить описание `:1168-1170`: «чужие координаты не идут».
10. **Удаление аккаунта** — код не меняется (брони удаляются целиком, `account.py:281-284,456`); добавить тест.

### Android (`android/`)
1. `data/BookingDtos.kt`, `data/DriverBookings.kt` — разбор новых полей (null-безопасно; старый сервер их не шлёт).
   `data/ApiClient.kt`: в `createBookingResponse` (`:1078-1100`) — `meeting`/`alighting`; новые `setMeetingPoint`, `answerMeetingPoint`,
   `withdrawMeetingPoint`; отказ — через `ApiException.detailCode` (`:5446`). `data/TripPass.kt` — новые поля в офлайн-пропуск
   (в селе без сети точка нужнее всего); стирание при выходе уже есть (`data/TripPassDeletion.kt`).
2. Новый `MeetingPointPicker.kt`: строка «Уточнить место (по желанию)» → шторка: чипы ориентиров `PickupSuggestionChips(город места)`,
   кнопка «На карте» → `PickupPickerOverlay(hint = …)`, поле пояснения (до 120), «Готово». Без ключа MapKit оверлей сам показывает
   заглушку — остаются ориентиры и пояснение. Отказ `meeting_point_far` — текст сервера под полем.
3. `PickupStopPicker.kt` — под выбором посадки и высадки по строке из п. 2 (до брони); `BookingActiveTripScreen.kt` — передать выбор
   в бронь; `BookingMeetingCard` (`:887-933`) — своя точка, ожидание ответа («Ждём ответа водителя — пока действует прежнее место»),
   кнопки «Изменить место» (`pending`) / «Предложить другое место» (`confirmed`, высадка — и `onboard`), «Отозвать».
4. `DriverPassengerRow.kt`: до подтверждения — «Место указано — откроется после подтверждения»; после — ориентир, пояснение,
   «Маршрут» (`Intent.ACTION_VIEW`, `geo:lat,lng?q=lat,lng(Место встречи)` через `Intent.createChooser`; без имени пассажира в
   подписи; без правки `AndroidManifest.xml`); предложение — «Пассажир просит встретиться в другом месте» + «На карте»,
   «Согласиться», «Оставить прежнее».
5. Всё — `appText(ru, ba)`, `Canon*`, тач-цели ≥ 48dp, у иконок-кнопок `contentDescription` на двух языках; появление
   карточки предложения и смена состояния — `AnimatedVisibility`/`AnimatedContent` с `CanonMotion`. Нового экрана нет
   (шторка и оверлей) — ⭐ п. 4 не нужен; если исполнитель всё же сделает экран — `enum Screen` + ветка навигации + `docs/architecture.md`.

### Сайт (`webapp/`)
1. `api/client.ts` — необязательное `code` в `ApiError` из `detail.code` (`:383-391`); проверь, не добавил ли его уже RS-01/RS-05.
2. `api/bookings.ts` — поля `meeting`/`alighting` в `CreateBookingInput` (`:20-24`), новые поля в `BookingDetails` (`:65-105`),
   `DriverBookingRow` (`:327-331`), функции `setMeetingPoint`, `answerMeetingPoint`, `withdrawMeetingPoint`.
3. `components/YandexMap.tsx` — режим выбора: пин в центре и `onCenterChange`; без ключа — существующая заглушка.
   Новый `components/MeetingPointPicker.tsx`: ориентиры (`api/geo.ts:84`), карта, пояснение.
4. `components/RideSheet.tsx` — «Уточнить место» в брони; `screens/ActiveTripScreen.tsx` (`:936-939`) — своя точка, предложение,
   ожидание; `components/DriverPassengers.tsx` — как Android п. 4; «Маршрут» — ссылка `https://yandex.ru/maps/?rtext=~<lat>,<lng>&rtt=auto`
   в новой вкладке (`rel="noopener"`).

## 4. База данных
Миграция `cc_booking_meeting_points_<дата>`: `down_revision` — голова цепочки на момент работы (после миграции RS-02; сейчас голова
`cb_sos_retry_intent_20261021`), `alembic heads` — одна голова. Добавляет 11 колонок `booking` из шага 1 сервера; идемпотентно, по
образцу `ca_booking_segments_20261018.py` (проверка существующих колонок). Старые брони — NULL/пусто, правила прежние, ничего не
угадываем. `downgrade` — снять колонки. **Ждёт «да»** (рабочая база).

## 5. Надписи
Русские (башкирский — черновик исполнителя через `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку»):
«Уточнить место (по желанию)», «Где тебя забрать?», «Где тебя высадить?», «Выбери ориентир или поставь точку на карте»,
«Пояснение: ворота, подъезд, что рядом», «Место указано — откроется после подтверждения», «Маршрут», «Изменить место»,
«Предложить другое место», «Ждём ответа водителя — пока действует прежнее место», «Отозвать», «Пассажир просит встретиться
в другом месте», «Согласиться», «Оставить прежнее», пуши «Пассажир предлагает другое место встречи», «Водитель согласился на новое
место», «Водитель оставил прежнее место — договоритесь в чате»; ошибки: `meeting_point_far`, `meeting_point_needs_stop` («Сначала
выбери, где садишься»), `meeting_point_onboard` («Ты уже в машине — место посадки не меняется»), `meeting_point_closed` («Поездка
закрыта — место уже не поменять»), `meeting_point_stale` («Место только что изменилось — посмотри ещё раз»), `meeting_point_too_many`,
`meeting_landmark_unknown`, `meeting_point_conflict` («У тебя уже есть бронь с другим местом встречи…»).

## 6. Состояния экрана
- Загрузка ориентиров — скелетон чипов; ошибка — «Не получилось загрузить ориентиры. Повторить?»; пусто (в селе нет ориентиров) —
  «Ориентиров тут пока нет — поставь точку на карте и напиши пояснение».
- Нет ключа карты / нет сети на карте — заглушка, ориентиры и пояснение работают.
- Отправка — кнопка в состоянии загрузки, повторное нажатие не шлёт второй запрос; отказ — текст сервера, ввод не теряется.
- Ожидание ответа водителя — отдельная плашка; ответ пришёл — плавная смена (`AnimatedContent`).
- Длинный башкирский текст и пояснение в 120 знаков — перенос, не обрезка; тёмная тема — только `Canon*`.

## 7. Тесты
**Сервер** (`pytest` из `backend/`, плюс `ruff check app/ --select F`):
- `test_meeting_points.py`: бронь с точкой у остановки и в начале; точка дальше радиуса — 400 и места не списаны; чужой/личный
  ориентир — 400; `pending` — применилось; `confirmed` — предложение, точка прежняя, один пуш; повтор того же — без второго пуша;
  ответ «да»/«нет»; ответ на устаревшее — 409/`already`; гонка «предложение vs ответ» (два запроса подряд с одной версией — проходит
  один); лимит смен; `onboard` — только высадка; «Подъезжаю»: 4 км от точки — без отказа и без «подтверждено», 300 м — «подтверждено»,
  7 км — отказ, рубильник выключен — прежнее поведение.
- `test_meeting_points_privacy.py`: водитель до подтверждения не получает координат/пояснения ни в `/details`, ни в `/driver/bookings`;
  после — получает; через 48 ч после поездки — снова нет; другой пассажир, гость, `/rides/{id}`, `/rides`, страница близкого,
  квитанция — без точки; лог (caplog) без `52.345`/`58.612` при брони и смене точки (как `test_geo_logs_without_coordinates.py`);
  `/me/export` пассажира — своя точка есть, у водителя — нет; удаление аккаунта пассажира и водителя — точки ушли с бронями.
- `test_cleanup.py` (дополнить): отменённая без подтверждения — стёрта в первую ночь; завершённая — через 30 дней, раньше — нет;
  с открытым SOS/разбором — не стёрта; сухой прогон ничего не меняет.
- Мутации: убрать проверку `unlocked` у водителя — краснеет приватность; убрать условие версии в `UPDATE` — краснеет гонка;
  поставить порог отказа 800 м — краснеет «4 км без отказа».

**Android:** `MeetingPointTest.kt` (выбор/сброс точки, видимость водителю по статусу, текст подписи без имени) + сторожа
`BilingualSourceGuardTest`, `CanonSourceGuardTest`, `ColorSourceGuardTest`, `SpacingRatchetGuardTest`, `TouchTargetGuardTest`,
`MapKitGateGuardTest`, `ReachableApiGuardTest`; `:app:assembleDebug`.
**Сайт:** `tests/meeting-point-test.mjs` (в `ALL_SUITES` `tests/run.mjs`): тело брони с точкой, предложение и ожидание, водитель
без координат до подтверждения; `npm run check`, `npm run build`.

## 8. Приёмка
Принимает агент другой «семьи» (Q9), по умолчанию «не принято».
1. Вывод `ruff`, `pytest` (весь набор), `npm run check`, `npm run build`, `:app:assembleDebug` и тестов Android — зелёный, приложен.
2. Эмулятор, Android-пассажир + сайт-водитель и наоборот, RU/BA × светлая/тёмная: бронь «Сибай → Акъяр через Ургазу» с точкой у
   ориентира и пояснением → водитель до подтверждения видит только «место указано» → подтвердил → точка, пояснение, «Маршрут» открывает
   карту → пассажир предлагает другое место → у водителя плашка → «Согласиться» → у пассажира новая точка; второй круг — «Оставить
   прежнее».
3. «Подъезжаю» с эмулятором, поставленным в 4 км от точки — без отказа; в 200 м — «подтверждено по GPS».
4. Узкий экран и крупный шрифт — пояснение и кнопки не ломают вёрстку; без ключа карты — заглушка, бронь проходит.
5. Запрос `/details` и `/driver/bookings` от водителя до подтверждения — без координат (вывод приложить).
6. Двигает: W1-E2E-01 (точное место посадки и высадки в основной поездке), W1-E2E-05 (точка закрыта до подтверждения).
   Остаётся: два живых телефона и настоящий GPS (W1-E2E-07, Q11), проверка BA носителем, полный прогон RS-16. Статус — только `docs/audit-blocks.md`.

## 9. Риски и границы
- Не входит: предложение смены точки от водителя (пока — чат), живая точка пассажира, ориентиры сёл (наполнение справочника —
  отдельная работа с Александром), пешая навигация.
- Сид ориентиров примерный (`services.py:1812`) — поэтому после выбора ориентира карта открывается на нём и просит проверить булавку,
  а порог отказа «Подъезжаю» широкий.
- Своя точка в выгрузке — решение этого плана (это данные самого человека); если Александр решит иначе — убрать одну строку.
- Срок 30 дней — новое правило хранения: записать в `docs/decisions.md` и в ответ `/me/data`, чтобы приложение не обещало другое.
- Миграция и зачистка — только после «да»; на проде — сначала сухой прогон чистки.
- Общая зона с RS-02 и RS-05 (`bookings.py`, `models.py`) — по очереди, не параллельно (Q10).

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан |
