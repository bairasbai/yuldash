# RS-09 — Обратная поездка одной кнопкой и публикация на несколько дат

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | нет — миграций, денег, тарифов нет. Выкладка сервера — общим порядком (`AGENTS.md` §8) |
| Зависит от | RS-02 (`stop_prices`, `herr(..., code=)`), RS-05 (`Idempotency-Key`, шаг S6) — оба раньше, RS-09 строится на их коде |
| Двигает сценарии приёмки | W1-E2E-01 (публикация), частично E2E-04. E2E-06 закрывает RS-16 |
| Зона файлов | сервер: `routers/rides.py`, `schemas.py` (классы в конец), `tests/test_ride_series.py`. Android: `CreateRideScreen.kt`, `ProfileScreen.kt`, `YuldashApp.kt` (общий — «замок», §12), новые `RideTemplate.kt`, `RideDatesPicker.kt`, `RideSeriesResultSheet.kt`, `data/RideSeriesApi.kt`, `ApiClientEndpointContractTest.kt`. Сайт: `CreateRideScreen.tsx`, `DriverCabinetScreen.tsx`, `api/driver.ts`, `ui.css`, новые `utils/rideTemplate.ts`, `components/DatesPicker.tsx`, `tests/ride-*-test.mjs`, `tests/run.mjs` |
| Источник | `master/part-002.md`, «# P1», обратные поездки и несколько дат (PUB-06); Q10, Q12, Q17 |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
Рамиль возит Сибай → Акъяр через Ургазу. Утром отвёз, вечером едет домой. Раньше обратную поездку набирали заново: оба села, остановки задом наперёд, цены. Теперь у поездки есть кнопка «Обратно»: форма открывается с Акъяром в начале, Сибаем в конце, Ургазой посередине, цены остановок развёрнуты. Время, место встречи и комментарий не переносятся (они про ту поездку) — так и написано.

Кто ездит по пн, ср и пт, отмечает дни в календаре — всё публикуется сразу. Если у одного дня время прошло, будет «Опубликовано 2 из 3» и причина по третьей. Само ничего не бронируется; двойное нажатие и обрыв связи лишнего не создают.

## 2. Что уже есть в коде
- `rides.py:214` `create_ride`; `:325-341` `recurrence` создаёт ещё 4 рейса одним коммитом, без отчёта по каждому; `:263` «время выезда уже прошло» (400, без `code`); `:175` `_recent_twin_ride` (60 с), только для первого рейса; `:220` блок отдыха таксиста (одна попутка); `:224` потолок 30 активных проверяется один раз; `:355` оповещение подписчиков. Поля «серия» нет (`models.py:389`, `schemas.py:45`).
- Android: `CreateRideScreen.kt:284` (на входе только `prefillDate`), `:380-430` одна `ApiClient.publishRide` (`ApiClient.kt:967`, вернёт id), затем `onPublish` уводит с экрана; `:658-676` ряд «Повтор»; `RideDeparturePicker.kt:124` — ОДНА дата; `YuldashApp.kt:954` `openCreateRide`, `:415`, `:1305`, `:1579`; `ProfileScreen.kt:3143`, `:3417` — карточки поездки и архива.
- Сайт: `CreateRideScreen.tsx:111` `recurrence`, `:127-151` черновик (`formDraft.ts:119` при открытии ПЕРЕЗАПИСЫВАЕТ поля), `:202-257` отправка, `:259-299` экран «опубликовано»; `DriverCabinetScreen.tsx:483-488` кнопки без данных, `:589` архив. `RepeatTripScreen.tsx:88-117` — повтор пассажира = заявка, не трогаем.
- Не нашёл: «Обратно», несколько дат, ответ «по каждой дате».

## 3. Что сделать
### Сервер (`backend/`)
1. **S1.** Тело `create_ride` (`:215`…`return ride`, уже с блоками цен RS-02 и ключа RS-05) без изменений вынести в `_create_ride_core(body, user, session, *, notify: bool, request_key)`; `create_ride` зовёт его с `notify=True`. При `notify=False` пропустить `notify_map_changed()` и `notify_route_watchers()` (`:354-355`). Зелёными остаются `test_rides_edges.py:73`, `test_wave1_scenarios.py:243`, `:258`.
2. **S2.** Отказ `:263` — `herr(400, <тот же текст>, code="time_passed")` (`code` в `herr` вводит RS-02).
3. **S3.** `POST /rides/series`: `RideSeriesIn{ride: RideIn, departures: list[datetime]}` (`MAX_SERIES_DATES = 10`, `ride.depart_at` игнорируется). Ошибки запроса: `recurrence != "none"` → 400 `series_with_recurrence` («Выбери одно: повтор или несколько дат»); больше 10 → 400 `series_too_many` («За один раз — не больше 10 дат»). Даты сортируются, дубли склеиваются.
4. **S4.** По каждой дате — `_create_ride_core(body.model_copy(update={"depart_at": d, "recurrence": "none"}), …, notify=False)`. `HTTPException` → элемент `{ok:false, code, ru, ba}` (code из detail; иначе 429 `too_many_rides`, 403 `rest_block`, прочее `rejected`); иная ошибка → `session.rollback()`, `server_error`. Цикл не прерывается. Ответ 200: `{published, failed, items:[{depart_at, ok, ride_id, already, code, ru, ba}]}`. Создаётся только `Ride`.
5. **S5.** После цикла: `notify_map_changed()`, `notify_route_watchers(session, самый_ранний_новый)`, `session.refresh`; остальные рейсы пачки учтёт RS-10.
6. **S6.** Заголовок `Idempotency-Key` пачки (RS-05, `idempotency.parse_key`); ключ даты = `<ключ>-<ГГГГММДДччмм UTC>` (формат RS-05 допускает), каждая дата идёт через ту же квитанцию `ride.publish`, что и `POST /rides`. Повтор пачки → квитанции находят те же рейсы → `already:true`; 60-секундный `_recent_twin_ride` остаётся запасным.
7. **S7.** `GET /rides/{ride_id}/return-template` — только владелец (чужая → `herr(403, "Это не твоя поездка", "Был һинең сәфәрең түгел")`), ничего не пишет. `RideTemplateOut`: `from_city`/`to_city` переставлены, `waypoints` задом наперёд, `stop_prices` (RS-02: цена от начала, по частям `waypoints`) → `[price − p for p in reversed(stop_prices)]` (Ургаза 300 из 500 → 200 из 500), `category` hospital/parcel/cargo → `regular`; места, цена, удобства (в т.ч. `only_trusted`, `women_only`) — как были. Не берутся: `depart_at`, `pickup*`, `comment`, `receiver_name`, `parcel_size`, `partner_id`, `recurrence`. Плюс `dropped` и `suggested_date` (день исходной).

### Android (`android/`)
1. **A1.** `data/RideSeriesApi.kt`: `ApiClient.publishRideSeries(…)`, `getReturnTemplate(id)` (образец — `RouteWatchApi.kt`; ключ — `CommandIntent.prepare("ride.series", …)` из RS-05); `RideTemplate.kt`: DTO и перенос в начальные значения формы.
2. **A2.** Кнопка «Обратно» (`SwapHoriz`, ≥ 48dp, `contentDescription` RU+BA) в карточке (`ProfileScreen.kt:3143`) и на `ArchiveRideCard` (`:3417`) → `getReturnTemplate` (индикатор) → форма; ошибка — «Повторить».
3. **A3.** `YuldashApp.kt`: состояние `createRideTemplateJson` (saver как у `:415`), параметр `openCreateRide`, передача в `:1305`, колбэк `:1579`.
4. **A4.** `CreateRideScreen.kt`: параметр `template` задаёт начальные значения `rememberSaveable`; заметка «Не перенесли: …» (по `dropped`); дата пустая, пикер на `suggested_date`. Само ничего не публикуется.
5. **A5.** `RideDatesPicker.kt`: сетка месяца, до 10 дней не раньше сегодня, одно время на все. В ряд «Повтор» (`:658`) — «Несколько дат», взаимоисключает остальные. ≥ 2 дат → `publishRideSeries`, иначе прежний `publishRide`.
6. **A6.** `RideSeriesResultSheet.kt`: после ответа остаёмся на экране. «Опубликовано 2 из 3»; по датам: «в ленте» / «уже была опубликована» / причина сервера. `time_passed` → «Выбрать другую дату»; `server_error`/нет сети → «Повторить» (только неудачные). «Готово» → `onPublish`. 0 опубликовано — остаёмся в форме, даты целы. После одиночной — шторка с «Опубликовать обратно».

### Сайт (`webapp/`)
1. **W1.** `api/driver.ts`: `publishRideSeries` (ключ — `commandIntent.ts` RS-05), `fetchReturnTemplate`; `utils/rideTemplate.ts`: чистые функции «шаблон → поля формы», «итог серии → группы». «Обратно» в `DriverCabinetScreen.tsx:486` и в архиве (`:591`) → `navigate("/create-ride", {state:{template}})`.
2. **W2.** `CreateRideScreen.tsx`: брать `location.state.template`; **до первого рендера** `clearDraft(RIDE_DRAFT)` (иначе `:133-150` вернёт старый черновик поверх шаблона); после применения `navigate(path, {replace:true, state:null})` — F5 не применит шаблон повторно.
3. **W3.** `components/DatesPicker.tsx` + `ui.css`: сетка (`role="grid"`, ячейки ≥ 48px), до 10 дат, одно `<input type="time">`; «Несколько дат» в блоке `:594`. Экран `:259` → итог по датам, как A6.

## 4. База данных
Не меняется. Связь «рейсы одной серии» не хранится.

## 5. Надписи
Русский; башкирский (и `ba` в `herr`) — черновик исполнителя в `docs/tasks.md`, «Переводы на проверку». «Обратно»; «Не перенесли: время, место встречи, комментарий — они были про ту поездку»; «Несколько дат»; «Опубликовано 2 из 3»; «в ленте»; «уже была опубликована»; «Выбрать другую дату»; «Опубликовать обратно»; тексты S3.

## 6. Состояния экрана
- Загрузка: «Обратно» и «Опубликовать» с индикатором, повтор нажатия заблокирован. Ошибка: строка + «Повторить». Нет прошлых поездок — кнопки нет. Итог серии: всё / частично / ничего.
- Длинный башкирский переносится; тёмная тема — `Canon*`; сетка дат на 360dp; цели ≥ 48dp.

## 7. Тесты
- Сервер `test_ride_series.py`: 3 даты, одна в прошлом → `published=2, failed=1, time_passed`, броней нет; та же пачка снова → те же `ride_id`, `already`; `recurrence`+даты и 11 дат → 400; потолок 30 → `too_many_rides`; блок отдыха → `rest_block`; `notify_route_watchers` 1 раз; чужой на шаблон → 403; шаблон: остановки и `stop_prices` развёрнуты, `hospital`→`regular`, `only_trusted` цел, `comment`/`pickup` нет, `Ride` не прибавилось; 10 дат ≤ 2 с.
- Android: `RideTemplateTest`, `RideSeriesResultTest`, `RideDatesPickerTest`, `CreateRideFormContentTest`, `ApiClientEndpointContractTest`, сторожа языка и `Canon*`. Сайт: `ride-template-test.mjs`, `ride-series-ui-test.mjs` (в `ALL_SUITES`).
- Мутации: убрать разворот остановок/цен → красен тест шаблона; убрать `clearDraft` (W2) → красен тест сайта; `notify=True` в серии → красен тест вызова.

## 8. Приёмка
Агент другой «семьи» (Q9). Эмулятор + сайт, RU/BA × светлая/тёмная, шрифт 200%; телефоны не нужны (Q11).
- [ ] «Обратно» у поездки Сибай → Акъяр через Ургазу: Акъяр → Сибай, Ургаза посередине, цена и места те же, цены остановок развёрнуты, время пусто, видна заметка.
- [ ] Пока не нажато «Опубликовать» — нового в ленте нет, броней у пассажиров прошлой поездки нет.
- [ ] 3 даты, у одной время прошло: «Опубликовано 2 из 3», причина у третьей; в ленте ровно две.
- [ ] Двойной тап и обрыв сети посреди отправки → после «Повторить» рейсов ровно по числу дат.
- [ ] «Повтор» и «Несколько дат» вместе не выбрать; «Раз в неделю» даёт прежние 5.
- [ ] Шаблон переживает выгрузку приложения; на сайте F5 не применяет его второй раз.
- [ ] `/gotovo` с выводом: `ruff check app/ --select F`, `pytest`, `:app:assembleDebug`, сторожа, `check`, `build`.

## 9. Риски и границы
- Не входит: отмена всей серии, разное время по датам, «Обратно» для пассажира. Модерация и геокод идут на каждую дату — держим замер. Общие файлы (`rides.py`, `YuldashApp.kt`, `CreateRideScreen.*`, `ProfileScreen.kt`, `DriverCabinetScreen.tsx`, `tests/run.mjs`) — по очереди после RS-02/RS-05 и вместе с RS-14, RS-15.
- Приватность: шаблон — только владельцу; `comment` и место встречи не переносим (бывают адреса и телефоны).

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан |
