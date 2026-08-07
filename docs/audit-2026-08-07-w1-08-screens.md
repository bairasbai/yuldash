# W1-08 · Аудит: ЭКРАНЫ, КНОПКИ, СОСТОЯНИЯ

Дата: 2026-08-07 · Режим: только чтение, без эмулятора и Android SDK
Область: `android/app/src/main/java/com/yuldash/app/*.kt` (кроме `MainActivity.kt`)
Метод: инструменты репозитория + 8 собственных скриптов-детекторов + проверка каждой находки глазами по коду.

---

## ВЫВОД ИНСТРУМЕНТОВ (navcheck, ktcheck) + что из этого правда

### `python3 tools/navcheck.py`
```
элементов enum Screen: 90 · веток when: 90 · else: есть
OK — навигация полная
```
**Правда.** Перепроверено: `enum class Screen` — `MainActivity.kt:394-485`, 90 элементов;
`Screen.X ->` в `YuldashApp.kt` — 90 уникальных; ссылок на несуществующий `Screen.X` нет.

⚠️ **Чего navcheck НЕ проверяет:** экраны-сироты (значение enum, на которое никто не переходит).
Написал отдельный детектор (`scratchpad/orphan.py`): считает `Screen.X` во всех `.kt`, отбрасывая
ветки `when` (в т.ч. слитые `Screen.A, Screen.B ->`).
```
всего экранов: 90; без единого перехода: 0
```
**Сирот нет.** Самые «тонкие» входы (по одной ссылке) проверены руками — все живые, напр.
`Screen.PricingInfo` открывается только из `PaymentInfoScreen` (`YuldashApp.kt:1145`), это законно.

### `python3 tools/ktcheck.py`
```
OK — ничего не нашёл
```
**Правда**, но проверка узкая (баланс скобок, иконки без импорта, порча кириллицы/латиницы
внутри слова). Дефекты уровня «мёртвая кнопка» или «нет состояния» она в принципе не ищет.

### Ловушки из `docs/handoff-2026-08-06.md` §7.1 — подтвердились на мне
Мои первые прогоны дали: 657 «одноязычных» надписей → после фильтра пар RU/BA осталось 36 →
после проверки глазами **6 реальных**. Аналогично: 12 «пустых обработчиков» → **0 реальных**;
«7 кнопок без защиты от двойного тапа» → **6 реальных** (одна оказалась защищена хелпером `act`);
«6 вечных спиннеров» → **0** (`loading = false` стоит после цепочки, а не внутри `onSuccess`).
Все числа ниже — ПОСЛЕ ручной проверки.

---

## ПОДТВЕРЖДЁННЫЕ ДЕФЕКТЫ

Сводка: **P0 — 0 · P1 — 4 · P2 — 11** (итого 15).

| Класс | P0 | P1 | P2 |
|---|---|---|---|
| Мёртвые кнопки | 0 | 0 | 0 |
| Нет состояний экрана | 0 | 0 | 3 |
| Одноязычие | 0 | 0 | 6 |
| Риск падения | 0 | 0 | 0 |
| Двойной тап | 0 | 3 | 1 |
| Навигация / сироты | 0 | 0 | 0 |
| Монетизация: реклама не доезжает до экрана | 0 | 1 | 1 |

---

### МЁРТВЫЕ КНОПКИ — 0

Проверено 12 кандидатов (пустые лямбды `on* = {}`), **все законны**:

| Место | Почему не дефект |
|---|---|
| `BookingActiveTripScreen.kt:2259` `onClick = {}` | `combinedClickable` — нужен только `onLongClick` |
| `AccessibilityScreens.kt:898,906,920,1178,1186` `onValueChange = {}` | фолбэк-слоты; боевые вызовы передают `fromField`/`toField` (`AccessibilityScreens.kt:805-806` и `:1135-1136`) |
| `CreateRideScreen.kt:574` `onValueChange = {}` | `readOnly = true`, поверх — `Box(...).clickable { onOpenDatePicker() }` |
| `BookingActiveTripScreen.kt:1208-1209` `onMethod/onAmount = {}` | `editable = false` → ветка `else` в `PayAgreementBlock` (`:674-687`) рисует только текст |
| `CourierScreen.kt:281` `onGoOnline = {}` | `poputkaOnly = true` → `feedReady = true` (`:941`), кнопка «Включить линию» не рендерится |
| `CourierLocationService.kt:93`, `InstantOrderScreen.kt:4067` `onPeer = {}` | колбэк сокета, чужая позиция здесь не нужна |

Также: `TODO/FIXME` во всём срезе — **1** (`AccessibilityScreens.kt:1109`, осознанный, помечен `TODO(backend)`);
строк-заглушек («скоро», «в разработке») — **0**; состояний, которые пишут и никогда не читают, — **1**
(см. ниже, косметика).

---

### НЕТ СОСТОЯНИЙ ЭКРАНА — 3

#### [P2] Экран «Стать водителем»: статус не загрузился — человек видит пустую анкету
- **Файл:строка** — `SosVerifyScreens.kt:735-752` (экран `VerifyDriverScreen`, `Screen.VerifyDriver`)
- **Что не так** — единственная загрузка экрана (`getDriverStatus`) при сбое сети показывает Toast и всё.
  Ни блока ошибки, ни кнопки «Повторить». В коде так и написано: «Повтор — переоткрытием экрана».
- **Что видит пользователь** — водитель, который уже отправил права и ждёт проверки, при плохой сети
  видит ЧИСТУЮ анкету «загрузите права и фото машины». Дальше он их грузит и отправляет второй раз.
- **Доказательство**
  ```kotlin
  // SosVerifyScreens.kt:733-752
  // При сетевом сбое честно предупреждаем ... Повтор — переоткрытием экрана.
  LaunchedEffect(Unit) {
      ApiClient.getDriverStatus()
          .onSuccess { s -> docsStatus = s.docsStatus; verified = s.verified; ... }
          .onFailure { e -> if ((e as? ApiException)?.status != 401) Toast.makeText(context, tStatusFail, ...).show() }
  }
  ```
- **Как чинить** — завести `statusError` + `statusTick`, ключ `LaunchedEffect(statusTick)`, при ошибке
  показывать `AppErrorState(onRetry = { statusTick++ })` вместо формы (компонент уже есть, `UiKit.kt:261`).

#### [P2] Подсказки адреса: нет сети — молча «города не найдено»
- **Файл:строка** — `GeoUi.kt:176`, `GeoUi.kt:357`, `GeoUi.kt:448`
- **Что не так** — три поиска (`searchSettlements` ×2, `searchDistricts`) при ошибке гасят список.
  Отказ сети неотличим от «такого населённого пункта нет».
- **Что видит пользователь** — набирает «Баймаҡ», подсказка не появляется, вывод — «моего села тут нет»,
  и уходит. Это вход во ВСЕ формы маршрута (заявка, поездка, заказ за близкого).
- **Доказательство** — `ApiClient.searchSettlements(query, 6).onSuccess { hits = it }.onFailure { hits = emptyList() }`
- **Как чинить** — отдельный флаг `suggestError`; при нём под полем строка «Не удалось загрузить подсказки —
  можно ввести вручную» + повтор. Ввод руками не блокировать.

#### [P2] Админ «Заявки на оплату»: загрузка — голый текст, а не скелетон, и она НИЖЕ списка долгов
- **Файл:строка** — `SecondaryScreens.kt:1638-1644`
- **Что не так** — `if (loading) item { Text("Загрузка…") }` стоит после блока долгов; ошибка и «пусто»
  ниже. Пока грузится, экран уже показал часть данных, а индикатор — внизу простым текстом.
- **Что видит пользователь** — админ. Косметика, но экран денежный и читается как «подвис».
- **Как чинить** — `SkeletonCard` вверху списка, как в остальных админ-экранах (`AdminParcelsScreen.kt:133`).

**Проверено и НЕ подтвердилось** (типовые ложные срабатывания):
`ChatContent` (`RidesRequestsChatScreens.kt:1898`) без ветки ошибки — все три обёртки
(`InstantChatScreen.kt:196`, `ParcelChatScreen.kt:206`, `SupportChatScreen.kt:410`) показывают
«Не удалось загрузить чат» + «Повторить» ДО вызова `ChatContent`; боевой чат брони
(`BookingActiveTripScreen.kt:1603-1631`) имеет собственную ветку `historyError` с повтором.

---

### ОДНОЯЗЫЧИЕ — 6

Детектор: все кириллические литералы вне `appText/appTextFor/LocalizedText` (657) → фильтр пар RU/BA
(36 «одиночек») → ручная проверка. Плюс отдельный детектор `appText(ru, ba)` с пустой или дословно
равной башкирской стороной (120 попаданий, из них законные заимствования — «Такси», «Профиль»,
«Комиссия», «ИНН», «ОСАГО», «Купон», «Премиум» и т.п.).

#### [P2] «водитель» / «пассажир» — башкирская сторона равна русской, хотя слова в проекте есть
- **Файл:строка** — `AdminWaitlistScreen.kt:168`
- **Доказательство**
  ```kotlin
  add(if (e.role == "driver") appText("водитель", "водитель") else appText("пассажир", "пассажир"))
  ```
- **Что видит пользователь** — админ в башкирском интерфейсе. В том же проекте уже есть
  «йөрөтөүсе» (`YuldashApp.kt:948`) и «юлаусы» (`FairnessScreens.kt:153`).
- **Как чинить** — `appText("водитель", "йөрөтөүсе")` / `appText("пассажир", "юлаусы")`, сверить с носителем.

#### [P2] Подписи-заглушки из слоя данных приходят только по-русски
- **Файл:строка** — `data/ApiClient.kt:1892` и `data/ApiClient.kt:4813`
- **Доказательство**
  ```kotlin
  author = r.optString("author").ifBlank { "Аноним" },            // :1892 — отзывы
  passengerName = optString("passenger_name").ifBlank { "Пассажир" }, // :4813 — карточка заказа
  ```
- **Что видит пользователь** — в башкирском интерфейсе среди башкирского текста внезапно «Аноним» /
  «Пассажир». Это единственные два места, где текст для экрана рождается в сетевом слое.
- **Как чинить** — не подставлять слово в `ApiClient`, а отдавать пустую строку и решать на экране
  через `appText("Аноним", "Аноним")` / `appText("Пассажир", "Юлаусы")`.

#### [P2] Текст сигнала SOS от курьера — только по-русски
- **Файл:строка** — `CourierScreen.kt:1376`
- **Доказательство** — `CourierSosButton(route = "Доставка #${p.id} ${p.fromCity} → ${p.toCity}")`,
  дальше `RoadsideHelp.kt:144` кладёт строку в `NavSignals.openSosWithNote`, а
  `SosVerifyScreens.kt:396` вклеивает её в заметку сигнала.
- **Что видит пользователь** — курьер на трассе, интерфейс на башкирском; дежурному уходит русская
  подпись. Не критично (адресат — дежурный), но правило «два языка» нарушено.

#### [P2] «Координаты: …» в тексте SOS — только по-русски
- **Файл:строка** — `SosVerifyScreens.kt:368` и `SosVerifyScreens.kt:398`
- **Доказательство** — `append("Координаты: $coordsText")` / `append("Координаты: $coordsText (https://yandex.ru/maps/…)")`
- **Что видит пользователь** — кнопка «Скопировать» (`:432`) даёт текст для пересылки близким;
  башкироязычный получатель читает русское слово.

#### [P2] Поиск «больниц» подстрокой ломается на башкирском вводе
- **Файл:строка** — `RidesRequestsChatScreens.kt:1316` и `RidesRequestsChatScreens.kt:1533`
- **Доказательство**
  ```kotlin
  icon = if (req.title.contains("больниц", ignoreCase = true)) Icons.Default.LocalHospital else Icons.Default.DirectionsCar
  val isHospital = ride.car.contains("больниц", ignoreCase = true)
  ```
- **Что видит пользователь** — заявка «Дауахана» (больница по-башкирски) не получает медицинскую
  иконку и не попадает в связанную логику. Молчаливый промах, не ошибка.
- **Как чинить** — сравнивать по коду категории с сервера, а не по подстроке; на переходный период
  добавить «дауахана»/«больниц».

#### [P2] Сырой ключ с сервера попадает в подпись
- **Файл:строка** — `SecondaryScreens.kt:1651`
- **Доказательство** — `else -> p.purpose` (значения `donate`/`boost`/`ad` переведены, любое новое —
  показывается латиницей как есть).
- **Что видит пользователь** — админ увидит, например, `subscription` вместо человеческого названия.

---

### РИСК ПАДЕНИЯ — 0 подтверждённых

| Что искал | Найдено | Итог после проверки |
|---|---|---|
| `runBlocking` в composable | 0 | чисто |
| `!!` на nullable | 31 | все под проверкой `!= null` в той же ветке `when`/`if`; два в `runCatching{}` (`CreateRideScreen.kt:371`, `AccessibilityScreens.kt:762`) |
| `first()`/`last()` на возможно пустом | 9 | 4 в `CourierScreen.kt:1330-1375` — внутри `if (activeParcels.isNotEmpty())`; остальные на константных списках (`demoRides`, `demoPopularRoutes`, `categories`) или после `if (raw.size >= 2)` |
| `toInt()/toDouble()` на пользовательском вводе | 0 | ввод везде через `toIntOrNull()` |
| деление на ноль | 1 кандидат | `ModeSwitchHome.kt:254` — делитель `items.size`, список из трёх литералов |
| индекс списка без границ | 0 | все `items(list.size) { i -> list[i] }` |
| `items` без `key` | 1 | `SecondaryScreens.kt:941` — статический список правил, состояния нет |

Отдельно: `IconButton` без `contentDescription` — **0** (детектор `scratchpad/cdesc.py`).

---

### ДВОЙНОЙ ТАП — 4 (3×P1 + 1×P2)

#### [P1] Админ подтверждает ДЕНЬГИ без блокировки кнопки — 4 кнопки
- **Файл:строка** — `SecondaryScreens.kt:1631`, `:1632`, `:1664`, `:1665` (экран `Screen.AdminPaymentRequests`)
- **Что не так** — «Подтвердить» / «Отклонить» для долга водителя по комиссии и для платежа
  (донат, буст, реклама) уходят на сервер без `busy`-флага, без `enabled = false`, без спиннера.
  Список обновляется только ПОСЛЕ ответа — до этого кнопка выглядит живой и нажимается снова.
- **Что видит пользователь** — админ на слабой сети жмёт «Подтвердить» дважды: уходят два
  `POST /admin/payments/{id}/confirm`. Если сервер не идемпотентен — долг закрыт дважды.
- **Доказательство**
  ```kotlin
  // SecondaryScreens.kt:1631
  Button(onClick = { val id = g.debtId; scope.launch { ApiClient.confirmDebt(id)
      .onSuccess { Toast.makeText(ctx, confirmedMsg, Toast.LENGTH_SHORT).show(); reload() }
      .onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() } } },
      ... ) { Text(appText("Подтвердить", "Раҫлау"), fontWeight = FontWeight.Bold) }
  ```
- **Как чинить** — как в соседнем `PayOnlineCard.kt:92`: `var busyId by remember { mutableIntStateOf(0) }`,
  в начале `if (busyId != 0) return`, `enabled = busyId == 0`, `loading = busyId == g.debtId` у `AppButton`.

#### [P1] Админ «Принять за пользователя» — можно нажать дважды и создать две брони
- **Файл:строка** — `SecondaryScreens.kt:1776`
- **Что не так** — `ApiClient.acceptResponse(id)` без гарда. Кнопка исчезает только когда сервер
  вернул новый статус (`r.status == "accepted"`, `:1772`), то есть после круга сети.
- **Что видит пользователя** — пассажир может получить две брони на одну заявку; водителю уходят
  два уведомления.
- **Доказательство**
  ```kotlin
  // SecondaryScreens.kt:1776
  onClick = { val id = r.id
      scope.launch { ApiClient.acceptResponse(id).onSuccess { Toast...; load() }.onFailure { Toast... } } },
  ```
- **Как чинить** — `busyId`, как выше; на время запроса — `loading` в кнопке.

#### [P1] Реклама партнёров: реальные объявления не попадают на 4 из 6 мест — см. отдельный раздел ниже.

#### [P2] «Применить» код друга — без блокировки
- **Файл:строка** — `ProfileScreen.kt:397`
- **Что не так** — `enabled = redeemCode.isNotBlank()` — это проверка поля, не защита от повторного
  нажатия. Диалог закрывается только в `.onSuccess`.
- **Что видит пользователь** — двойной тап: первый вызов проходит, второй возвращает «код уже
  активирован» — человек видит подряд «Готово» и красную ошибку и не понимает, засчиталось ли.
- **Доказательство**
  ```kotlin
  TextButton(enabled = redeemCode.isNotBlank(), onClick = {
      val c = redeemCode.trim()
      editScope.launch { ApiClient.redeemReferral(c).onSuccess { showRedeem = false; ... } ... }
  })
  ```
- **Как чинить** — `var redeeming by remember { mutableStateOf(false) }`, `enabled = redeemCode.isNotBlank() && !redeeming`.

**Проверено и чисто:** `PayOnlineCard.kt:92,123` (оплата — `if (busy) return` + `loading`),
`FairnessScreens.kt:713` (`act { }` с `if (busy) return` + `enabled = !busy`),
`InstantOrderScreen.kt:1729-1768` (заказ такси — `creating` + `enabled = … && !creating`),
`AccessibilityScreens.kt:750` и `:1103` (`if (submitting) return@…`),
`CourierScreen.kt:1424` (`if (lineBusy || busyId != 0) return@…`).

**Не доказано (нужен эмулятор):** `AdminPartnersScreen.kt:166` и `ParcelsScreen.kt:2465` — гард
формально есть (`busyId = target.id` + закрытие диалога в конце `onClick`), но между двумя тапами
в одном кадре Compose успевает вызвать обработчик дважды до рекомпозиции. Вероятность низкая,
проверяется только руками на устройстве.

---

### НАВИГАЦИЯ — 0

- Аппаратная «Назад» обработана глобально: `YuldashApp.kt:800`
  ```kotlin
  BackHandler(enabled = screen != Screen.Onboarding && screen != Screen.Login &&
      screen != Screen.Home && screen != Screen.Splash && screen != Screen.Intro) { goBack() }
  ```
  Исключённые пять — корневые/входные, для них выход из приложения корректен.
- Проверил каждую из 90 веток `when (screen)` на наличие выхода (`onBack`/`goBack`/`openHome`/
  `onDone`/`onComplete`): без выхода только `Splash`, `Intro`, `Home` — это корни.
- `goBack()` (`YuldashApp.kt:665`) снимает шаг с трейла, при пустом трейле уходит на Home —
  тупик невозможен по построению.
- Единственный намеренный блок «Назад» — `ProfileScreen.kt:3538` `BackHandler(busy) {}` в редакторе
  объявления, пока идёт сохранение; там же стрелка сверху обёрнута в `if (!busy)`. Законно и
  задокументировано в коде.

---

### МОНЕТИЗАЦИЯ: РЕКЛАМА НЕ ДОЕЗЖАЕТ ДО ЭКРАНА — 2

Класс не значился в задании, но это самый дорогой из найденных дефектов: партнёр платит, а
объявление физически не может быть показано.

#### [P1] Объявление с сервера наследует места показа, город и маршрут от ДЕМО-шаблона
- **Файл:строка** — `YuldashApp.kt:551-568`
- **Что не так** — ответ `/ads` не содержит `placements`/`routeFrom`/`routeTo`/`category`, и код
  копирует их из первого демо-объявления:
  ```kotlin
  // YuldashApp.kt:551-568
  val tmpl = demoPartnerAds.firstOrNull()
  partnerAds = if (srv.isEmpty() || tmpl == null) emptyList() else srv.map { a ->
      tmpl.copy(id = a.id, title = a.title, ... city = a.city.ifBlank { tmpl.city }, ...)
  }
  ```
  У шаблона (`Mocks.kt:317`) `placements = setOf(Nearby, Profile, Help, TripDetails)`,
  `routeFrom = "Баймаҡ"`, `routeTo = "Сибай"` (`Mocks.kt:310-311`).
- **Что видит пользователь / бизнес** — **любое** реальное объявление всегда попадает в одни и те же
  четыре места, какое бы место партнёр ни купил. Места `Route` и `RidesList` для серверных объявлений
  недостижимы вообще. А `TripDetails` фильтруется по маршруту (`BookingActiveTripScreen.kt:322`
  `matchesRoute(ride.from, ride.to)`) — и, поскольку маршрут наследован, объявление видно только на
  поездках ровно «Баймаҡ → Сибай». Итого из шести мест реально работают два: Профиль и Помощь.
- **Как чинить** — добавить `placements`, `route_from`, `route_to`, `category` в ответ `/ads` и
  разбирать их в маппинге; шаблон оставить только для иконки/оформления. До правки сервера — не
  наследовать `routeFrom/routeTo` (ставить `null`, тогда `matchesRoute` вернёт `true`, см.
  `ProfileScreen.kt:3899`).

#### [P2] Три места отбирают объявление по захардкоженному городу / демо-идентификатору
- **Файл:строка** — `MapScreen.kt:283`, `RidesRequestsChatScreens.kt:301-304`, `SecondaryScreens.kt:2098-2099`
- **Доказательство**
  ```kotlin
  // MapScreen.kt:283 — карта, блок «Партнёр рядом»
  val nearbyAd = ads.forPlacement(AdPlacement.Nearby).firstOrNull { it.city == "Баймаҡ" }

  // RidesRequestsChatScreens.kt:301-304 — лента поездок
  ads.forPlacement(AdPlacement.Route).filter { it.matchesRoute("Баймаҡ", "Сибай") }
      .firstOrNull { it.id == "ad-cafe-route" }
      ?: ads.forPlacement(AdPlacement.Route).firstOrNull { it.matchesRoute("Баймаҡ", "Сибай") }
  val sponsoredAd = remember(ads) { ads.forPlacement(AdPlacement.RidesList).firstOrNull { it.id == "ad-service-rides" } }

  // SecondaryScreens.kt:2098-2099 — экран «Помощь»
  ads.forPlacement(AdPlacement.Help).firstOrNull { it.category == "В больницу" }
      ?: ads.forPlacement(AdPlacement.Help).firstOrNull { it.city == "Баймаҡ" }
  ```
  `ad-cafe-route` и `ad-service-rides` — идентификаторы демо-объявлений (`Mocks.kt:333`, `Mocks.kt:362`).
  Серверное объявление такой id получить не может.
- **Что видит пользователь / бизнес** — партнёр из Сибая покупает место «рядом на карте» и не
  показывается никогда: город берётся дословно, а «Баймак» через русскую «к» уже не совпадёт с
  «Баймаҡ». Рекламный слот в ленте поездок мёртв для реальных объявлений полностью.
- **Как чинить** — фильтровать по городу ПОЛЬЗОВАТЕЛЯ (он уже известен: `work_city` / город поездки),
  сравнивать нормализованно (нижний регистр + `ҡ→к`, `ә→а`, `һ→х`), демо-идентификаторы из боевого
  отбора убрать.

---

## ПРОВЕРЕНО И ЧИСТО

| Проверка | Инструмент | Результат |
|---|---|---|
| Полнота `when (screen)` | `tools/navcheck.py` | 90 из 90 |
| Экраны-сироты | `scratchpad/orphan.py` | 0 из 90 |
| Пустые обработчики нажатия | `scratchpad/deadcb.py` + глаза | 12 кандидатов → 0 дефектов |
| `TODO`/`FIXME`/заглушки «скоро» | grep | 1 осознанный, 0 заглушек |
| Состояние, которое пишут и не читают | `scratchpad/deadstate2.py` | 1 (`SosVerifyScreens.kt:726` `uploadError` — Toast всё равно показывается, вреда нет; мёртвая переменная) |
| Вечный спиннер (`loading=false` только в успехе) | `scratchpad/spinner.py` | 6 кандидатов → 0 |
| `when` с загрузкой, но без ошибки | `scratchpad/whenstates.py` | 6 кандидатов → 0 |
| Ошибка без «Повторить» | grep по `ListedError`/`AppErrorState` | все вызовы передают `onRetry`/`reload()` |
| Кнопка «Повторить», не связанная с загрузкой | `scratchpad/retrydead.py` | 0 |
| `IconButton` без `contentDescription` | `scratchpad/cdesc.py` | 0 |
| `items` без `key` в Lazy-списках | `scratchpad/keys.py` | 1 (статический список) |
| `runBlocking` в UI | grep | 0 |
| Ветки `when(screen)` без выхода | `scratchpad/backs.py` | 3 (Splash/Intro/Home — корни) |
| Двуязычие: пустая башкирская сторона `appText` | `scratchpad/samesides.py` | 0 |
| Оптимистичное «успешно» до ответа сервера | grep `fire*` / Toast до `launch` | 0 (все `fire*` — телеметрия и язык) |

Состояния экранов, проверенные поимённо и полные (загрузка + пусто + ошибка + повтор):
`CourierScreen`, `ParcelsScreen`, `DriverResponsesScreen`, `ScheduledOrdersScreen`, `SavedPlacesScreen`,
`CouponsScreen`, `PartnerCabinetScreen`, `PromoCodeScreen`, `AdminPartners/Promo/Parcels/Courier/Ratings/Sos/TaxiPulse/Ads/Reviews`,
`MapScreen` (лента «рядом» — 5 состояний, включая «фильтры всё срезали»), `InstantOrderScreen`,
`WalletScreen`, `TripReceiptScreen`, `TaxiReceiptScreen`, `SupportChatScreen`, `InstantChatScreen`,
`ParcelChatScreen`, `SupportBoostScreen`, `MyStatsScreen`, `DriverTaxiRidesScreen`, `MyTaxiTripsScreen`.

---

## НЕ ДОКАЗАНО

1. **Гонка «два тапа в одном кадре»** в диалогах `AdminPartnersScreen.kt:166` и `ParcelsScreen.kt:2465`.
   Гард есть, но он срабатывает через рекомпозицию. Проверяется только на устройстве
   (быстрый двойной тап), в статике не отличить.
2. **`pretrip!!` в лямбдах `items`** — `AdminTaxiScreen.kt:399-402`. Ветка `when` гарантирует
   `pretrip != null` в момент композиции, но `key`/контент-лямбды `LazyColumn` вычисляются позже.
   Если `reloadPretrip()` обнулит `pretrip` во время прокрутки — теоретический NPE. Нужен прогон
   с обновлением на лету.
3. **Идемпотентность сервера** для `confirmDebt` / `confirmPayment` / `acceptResponse`.
   Тяжесть дефектов «двойной тап» зависит от неё, а бэкенд в этот срез не входил. Даже если сервер
   идемпотентен, UI-часть чинить надо: сейчас админ не видит, что запрос ушёл.
4. **Реальный вид тёмной темы и длинного башкирского текста** — без эмулятора не проверял.
5. **`MyStatsScreen` без состояния «пусто»** — у нового пользователя все цифры нули. Считаю это
   допустимым (экран статистики с нулями осмыслен), но продуктовое решение за Александром.
6. **`docs/architecture.md` устарел** — в шапке прямо написано «числа строк ниже устарели».
   Карта по строкам для аудита непригодна; работал grep-ом.
