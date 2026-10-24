# RS-12 — Фильтр «сколько мест мне нужно»

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | нет — миграций, денег, тарифов нет |
| Зависит от | **А:** —. **Б:** RS-02 (`ride_seats.py`: `any_free_sql`, `free_on`, `max_free`; кеш `v3`) |
| Не параллельно с | RS-02 (`rides.py`); RS-10, RS-11 (`MapScreen.kt`, `HomeScreen.tsx`, `SessionKeys.kt`); RS-05, RS-07, RS-09 (`ApiClient.kt`, `YuldashApp.kt`) |
| Деление на PR | **[RS-12А]** фаза A, по `seats_left`: сервер → сайт → Android. **[RS-12Б]** фаза B, места по участку (после RS-02): сервер. Замок — по тегу части |
| Проверка `yuldash-security-privacy` | нет |
| Двигает сценарии приёмки | W1-E2E-01 (поиск → бронь на нужное число мест) |
| Зона файлов | сервер: `routers/rides.py` (только `search_rides`, `rides_near` — их правит и RS-02: по очереди), `tests/test_rides_seats_filter.py`. Android: `data/ApiClient.kt` (`getNearbyRidesPaged`, `NearbyPage`: ≤ 12 строк, общий), `NearbyFeedSearch.kt`, `NearbyFiltersPanel.kt`, `SecondaryScreens.kt` (`FilterPrefs`, `FiltersScreen`), `data/SessionKeys.kt`, `MapScreen.kt` (≤ 15 строк), `YuldashViewModel.kt` (поле `wantedSeats`), `YuldashApp.kt` (одна строка `:1335`, «замок»), новый `NearbySeats.kt`. Сайт: `api/discovery.ts`, `api/rides.ts`, `nearbyFeed.ts`, `filterPrefs.ts`, `components/NearbyFilters.tsx`, `components/RideSheet.tsx`, `screens/FiltersScreen.tsx`, `screens/HomeScreen.tsx` (≤ 15 строк), новый `components/NearbySeatsCard.tsx`, `tests/*`, `tests/run.mjs` |
| Источник | решения Александра 24.10: Q12 (места по участкам), Q10, Q17; `c5be55a:docs/research/apps/master/part-002.md`, «# P1» (удобства поиска) |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
Гульнара едет из Ургазы в Акъяр с дочкой и мамой — нужны три места. Сейчас лента показывает всё подряд: она открывает поездку за поездкой и только на брони узнаёт, что свободно одно место. Теперь она выбирает «Мест: 3» — остаются поездки, где на её участке свободно три и больше. Это число само встаёт в бронь. Выбор можно запомнить, как другие условия.

Если подходящего нет, приложение не молчит: «Для 3 мест поездок нет. Есть 2 поездки на 2 места — искать на 2?» и предлагает следить за маршрутом.

## 2. Что уже есть в коде
- `rides.py:491-505` `/rides` без параметра мест; `:510-513` `no_filter` — без фильтров отдаётся общий кеш `rides:active:v2` (после RS-02 `v3`): новый параметр обязан попасть в этот `any([...])`, иначе фильтр молча не сработает; `:529` и `:924` стоит только `seats_left > 0`; `:901-917` `/rides/near`; `:978-982` лёгкие колонки (`_NEAR_SHAPE_AT = 3`, `:898` — новую колонку класть в конец, индексы не сдвигать); `:1022-1026` `hidden_unverified` — образец «спрятал только этот фильтр»; `:862-891` `_matched`/`_dropoff` — какую посадку и высадку предвыбирает карточка.
- Android: `ApiClient.kt:838-891` `getNearbyRides`/`getNearbyRidesPaged`, `NearbyPage` (`:6422`); `MapScreen.kt:297-330` фильтры и запрос, `:499-507` состояния ленты, `:518-549` карточки состояний; `NearbyFiltersPanel.kt:197` `NearbyConditionsChip`; `NearbyFeedSearch.kt:59` `nearbySearch`; `SecondaryScreens.kt:1129` `FilterPrefs`, `:1161` `FiltersScreen`. Бронь: `BookingSeatCount.kt` (выбранное число урезается до доступного), `YuldashApp.kt:1335` — выбранные места всегда стартуют с `1`.
- Сайт: `api/rides.ts:80-98` `RidesQuery`/`fetchRides`; `api/discovery.ts:38-93` `NearQuery`, `nearQueryString`, `NearRidesPage`; `nearbyFeed.ts:84-137` фильтры и ключ ленты, `:154-181` `nearbyFeedState`; `filterPrefs.ts:21-98` (JSON `yuldash.filterPrefs`, чистится при выходе — `privacy.ts:21`); `HomeScreen.tsx:120,171,196`; `RideSheet.tsx:69,85,90,259-263` — `useState(1)`; `RidesScreen.tsx:66`.
- Не нашёл: параметра «нужно мест» на сервере и в клиентах; подсказки «на меньше мест».

## 3. Что сделать
**Фаза A** (PR А) — по `seats_left`. **Фаза B** (PR Б, после RS-02) — места на участке человека; параметры и ответ те же.

### Сервер (`backend/`)
1. **S1 (А).** `GET /rides/near` и `GET /rides`: `seats: Optional[int] = Query(None, ge=1, le=8)`  (вне диапазона → обычный 422 FastAPI). **`seats ≤ 1` приравнивается к «параметра нет»** — общий кеш работает; при `seats ≥ 2` параметр входит в `any([...])` у `no_filter`, условие `Ride.seats_left >= seats`.
2. **S2 (А).** `/rides/near`: в лёгкие колонки — `Ride.seats_left.label("seats_free")` в конец. В цикле (`:992`) рейс без нужных мест уходит в `short`, а не выбрасывается (проверка по рейсу, не по «виду маршрута»: места у каждого свои). К `kept` и `short` применяются одни и те же `hide_*` и `verified_only`.
3. **S3 (А).** В ответ — `hidden_by_seats` (сколько рейсов поиска спрятал только этот фильтр) и `max_free_seats` (больше всего свободных среди них), только при `seats > 1`. `count`/страницы считаются уже с фильтром мест. `hidden_unverified` — как прежде: рейсы, спрятанные только «проверенными» среди прошедших по местам.
4. **S4 (Б).** Лёгких колонок `/rides/near` (`:978-982`) для этого не хватает: дописать в конец **`Ride.seats_free_max` и `Ride.leg_free`** (если RS-02 уже дописал — не дублировать; `_NEAR_SHAPE_AT` не сдвигать). Там, где RS-02 проверяет «≥ 1», порог заменить на `seats`: участок однозначен (его предвыбирает карточка — `_matched`/`_dropoff`) → `ride_seats.free_on(…) ≥ seats`, иначе `max_free ≥ seats`; SQL-отбор `/rides` — `ride_seats.any_free_sql(seats)`. Колонки в том же SELECT — запросов не прибавляется (`test_no_query_per_item` зелёный).

### Android (`android/`)
1. **A1 (А).** `ApiClient.kt`: `getNearbyRidesPaged(…, seats: Int = 1)` (`seats > 1` → `&seats=`); `NearbyPage` + `hiddenBySeats`, `maxFreeSeats`.
2. **A2 (А).** `NearbySeats.kt`: чип «Мест» / «Мест: 3» (включён при > 1; цвет — `animateColorAsState`, `CanonMotion`) в тот же единственный ряд фильтров (`MapScreen.kt:488`) + шторка «Сколько вас едет» (кнопки −/+ ≥ 48dp, 1…8, `contentDescription` RU+BA, «Показать N поездок» по `nearbyTotal`); карточка `NearbySeatsCard`.
3. **A3 (А).** Запоминание как у условий: `FilterPrefs.loadSeats/saveSeats` (ключ `default_seats` в ящике `yuldash_filters`, `putInt`), строка «Сколько вас обычно едет» в `FiltersScreen`. Лента стартует с него; чип меняет только этот поиск. Ключ — в `SessionKeys.CLEARED_BY_FILE["yuldash_filters"]` (сторож).
4. **A4 (А).** `nearbySearch(…)` и ключи `LaunchedEffect` (`MapScreen.kt:318-319`) получают `seats` — смена места сбрасывает страницу.
5. **A5 (А).** Пусто: `seats > 1`, `hiddenBySeats > 0`, ничего не найдено → новое состояние «seats» (`MapScreen.kt:499-507`): «Для {N} мест поездок нет. Есть {M} на {K} — искать на {K}?», кнопка «Искать на {K}» (seats = `maxFreeSeats`) и «Следить за маршрутом» (RS-10). Скрытых нет — прежнее «пусто» с кнопкой RS-10. «Сбросить фильтры» сбрасывает и места (для этого поиска). При `seats > 1` под заголовком ленты: «Показаны поездки, где свободно {N} и больше».
6. **A6 (А).** В бронь: `wantedSeats` — поле `YuldashViewModel` (`:22`), а не глобальный объект; `MapScreen.kt` берёт ту же модель через `viewModel()` и пишет в неё при смене чипа; `clearUserData()` (`:398`) сбрасывает в 1 при выходе и смене сессии (`AGENTS.md` §7). `YuldashApp.kt:1335` стартует с `vm.wantedSeats` (не больше мест рейса, не меньше 1); дальше `BookingSeatCount` урежет до мест на выбранном участке (RS-02).

### Сайт (`webapp/`)
1. **W1 (А).** `api/discovery.ts`: `NearQuery.seats` → `seats=` при > 1; `NearRidesPage.hidden_by_seats`, `max_free_seats`. `api/rides.ts`: `RidesQuery.seats` (зеркало сервера). **`RidesScreen.tsx` (`/rides/feed`) места не применяет и не передаёт**: там нет чипа и строки, поездки пропадали бы молча.
2. **W2.** `filterPrefs.ts`: `seats` (1…8, по умолчанию 1) в JSON, чтение с проверкой диапазона; `write` и `saveVerifiedOnly` не теряют `seats`; строка в `FiltersScreen.tsx`.
3. **W3.** `nearbyFeed.ts`: `seats` в `NearbyFilters`, `nearbyQuery`, `nearbyContextKey`; состояние `"seats"` в `nearbyFeedState`; `seatsHiddenLabel(...)`. `NearbyFilters.tsx`: чип «Мест» + шторка; `HomeScreen.tsx`: состояние `seats` (старт — `loadFilters().seats`), карточка `NearbySeatsCard.tsx`, сброс в `resetFeedFilters`; чип меняет цвет CSS-переходом.
4. **W4.** `RideSheet.tsx`: `initialSeats` из ленты (иначе `loadFilters().seats`) — стартовое значение в `useState` и в сбросе `:85`, не больше `seats_left`; участок дальше урежет логика RS-02.

## 4. База данных
Не меняется: фильтр читает `seats_left` (А) и данные RS-02 (Б). Срок хранения и удаление аккаунта: `default_seats` и `yuldash.filterPrefs` лежат на устройстве и стираются при выходе (`SessionKeys.kt`, `privacy.ts:21`); сервер число мест не хранит.

## 5. Надписи
Русский; башкирский пишет исполнитель черновиком в `docs/tasks.md`, «Переводы на проверку». «Мест: N»; «Сколько вас едет»; «Сколько вас обычно едет»; «Показаны поездки, где свободно N и больше»; «Для N мест поездок нет»; «Есть M {поездка/поездки/поездок} на K {место/места/мест} — искать на K?»; «Искать на K».

## 6. Состояния экрана
- Загрузка/ошибка/пусто — прежние; новое «seats» вместо пустого, когда скрытые есть. Старый сервер без поля = 0 → обычное «пусто». Выбор 1 = «без фильтра».
- Башкирский и шрифт 200% не ломают чип; тёмная тема — `Canon*`; −/+ ≥ 48dp.

## 7. Тесты
- Сервер `test_rides_seats_filter.py`: `seats=3` показывает рейс с 3 свободными, прячет с 2; `/rides` после «прогрева» общего кеша с `seats=2` не возвращает кешированный список, а `seats=1` — отдаёт общий кеш; `hidden_by_seats`/`max_free_seats` верны, с `verified_only` и с заблокированным водителем (его в счёте нет); `count` и `limit/offset` согласованы; `seats=0`, `9` → 422; без параметра ответ прежний. **Б:** рейс Сибай→Акъяр, пассажир Сибай→Ургаза на 2 места из 4 — поиск Ургаза→Акъяр на 4 находит его, Сибай→Ургаза на 3 — нет; колонки `seats_free_max`/`leg_free` в лёгком SELECT, запросов не прибавилось.
- Android: `NearbySeatsTest` (чип, границы, подпись), `NearbyConditionsServerSideTest` (параметр в адресе), `BookingSeatCountTest` (старт `wantedSeats` с урезанием), тест `YuldashViewModel` (`clearUserData()` → 1), `FilterPrefs`, `ApiClientMoreTest` (разбор полей), сторожа `SessionKeysGuardTest`, языка и `Canon*`.
- Сайт: `nearby-feed-test.mjs` (адрес, ключ ленты, состояние «seats»), новый `seats-filter-test.mjs` (`filterPrefs` туда-обратно, `saveVerifiedOnly` не теряет места, старт брони) → в `ALL_SUITES`.
- Мутации: убрать `seats` из `no_filter` → красен тест кеша; не сбрасывать `wantedSeats` в `clearUserData()` → красен тест модели; считать места по виду маршрута, а не по рейсу → красен тест разных рейсов; не добавить ключ в `CLEARED_BY_FILE` → красен сторож.

## 8. Приёмка
Агент другой «семьи» (Q9). Эмулятор + сайт, RU/BA × светлая/тёмная, шрифт 200%.
- [ ] Три рейса со свободными 1, 2, 3 местами: «Мест: 3» оставляет один; «Мест: 2» — два; «1» — все три.
- [ ] Рейс из такой ленты открывается с бронью на «3», итог пересчитан; мест меньше — число урезано.
- [ ] «Мест: 4» при максимуме 3: «Для 4 мест поездок нет. Есть 1 поездка на 3 — искать на 3?»; «Искать на 3» её показывает; рядом «Следить за маршрутом» (RS-10).
- [ ] Запомнить в «Фильтрах по умолчанию» → после перезапуска «Мест: 3» уже стоит; после выхода и входа другим аккаунтом — снова 1 (число из бронирования тоже сброшено). Гость на Android: `default_seats` стирается при запуске (`ApiClient.kt:342`) — так и задумано.
- [ ] «Сбросить фильтры» из пустой ленты возвращает 1 место.
- [ ] Сайт: то же; общий кеш ленты выбор не обходит; `/rides/feed` места не прячет.
- [ ] **Б (после RS-02):** пассажир Сибай→Ургаза на 2 места из 4 → Ургаза→Акъяр на 4 находит рейс.
- [ ] `/gotovo` с выводом: `ruff check app/ --select F`, `pytest`, `:app:assembleDebug`, сторожа, `check`, `build`.

## 9. Риски и границы
- Не входит: «в двух машинах», места участка на карточке (RS-02), `/match/rides`, фильтр на `/rides/feed`.
- Запомненное «3» прячет рейсы с меньшим числом — чип горит, строка «Показаны поездки…» и подсказка это объясняют.
- **Единицы денег — решено 24.10 (D7, вариант А):** целые рубли; в RS-12 денежной части нет: «Итого» в брони считает RS-02.
- **Мозг:** `architecture.md` — параметр `seats`, поля подсказки, чип; `decisions.md` — `seats ≤ 1` = «нет параметра», `/rides/feed` без мест, `wantedSeats` во ViewModel; `tasks.md` — BA; `lessons.md` — параметр поиска обязан попасть в `no_filter` (общий кеш).

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан; RS-12А — не начато; RS-12Б — не начато (ждёт RS-02) |
