# W1-07 — Аудит контракта «приложение ↔ сервер» + слой данных

Режим: только чтение. `MainActivity.kt` не трогался.
Дата среза: рабочее дерево `/home/user/yuldash`, HEAD `9278dc6`.

---

## СВЕРКА ПУТЕЙ (метод + вывод команд)

### Метод

Сверка сделана **программно**, не глазами. Скрипты в
`/tmp/claude-0/-home-user-yuldash/40a3ca55-0451-5d5f-a009-b640c2e0a6b2/scratchpad/`.

**Сторона приложения** (`extract_app.py`): по всем `*.kt` в `com/yuldash/app` регулярка
с `re.S` (многострочная — иначе теряются вызовы вида `call(\n "POST", "/rides",`) ловит:

* `call("METHOD", "/path"` и `callMultipart("METHOD", "/path"` — литералы;
* `call("METHOD", varName,` — путь в переменной; переменная резолвится обратным сканом
  до 45 строк вверх до её `val/var … = "…"`.

Результат: **306 уникальных пар (метод, путь), 0 нерезолвленных**.

Нормализация перед сравнением:
`$var` и `${expr}` → `{}`; хвост `?query` отрезается; отдельно отрезается хвостовая
переменная-конструктор запроса (`$q`, `$qs`, `$query`, `$params`) — именно на ней первый прогон
дал два ложных расхождения (`/driver/rides$q`, `/admin/taxi/pretrip$q`); префикс `/api/v1`
снимается (в `main.py:130-133` каждый роутер монтируется дважды — на корень и под `/api/v1`,
так что разница в префиксе расхождением не является).

**Сторона сервера** (`extract_srv.py`): `@router.<method>("path")` по всем 42 файлам
`backend/app/routers/` + `@app.<method>` в `main.py`. Все роутеры создаются как
`APIRouter(tags=[...])` **без `prefix=`** (проверено: 41 из 41), поэтому путь в декораторе =
финальный путь. Итого **353 маршрута**.

**Ручные дополнения к списку приложения** (эти вызовы идут мимо `call()` и регуляркой не ловятся):

| Что | Где | Путь |
|---|---|---|
| `postWithToken()` | `data/ApiClient.kt:333-334` | `POST /push/unregister`, `POST /auth/logout` |
| `GeocoderClient` | `data/GeocoderClient.kt:40` | `GET /geocode` |
| 7 WS-клиентов | `data/ChatSocket.kt:61,76,107`, `LocationSocket.kt:59`, `InstantLocationSocket.kt:28,44`, `MapFeedSocket.kt:50` | `/ws/*` |

### Приложение зовёт, сервера нет

```
APP -> NO SERVER: 0
METHOD MISMATCH (путь есть, метод другой): 0
```

**Ни одного расхождения.** Все 306 путей, которые дёргает приложение, существуют на сервере
тем же HTTP-методом. Оба «расхождения» первого прогона (`/driver/rides$q` →
`rides.py:553`, `/admin/taxi/pretrip$q` → `taxi.py:327`) — ложные: `$q` там это
`""` или `"?status=…"` (`ApiClient.kt:1959`, `ApiClient.kt:2482`), т.е. хвост query-строки.

Причина такой чистоты понятна и её стоит сохранить: в проекте есть
`android/app/src/test/java/com/yuldash/app/data/ApiClientEndpointContractTest.kt` — таблица
путей, сгенерированная из самого `ApiClient.kt`, гоняется через MockWebServer. Она ловит
опечатку в адресе. **Чего она не ловит: существует ли адрес на реальном сервере и какие
заголовки уходят** — см. дыру P0-1 ниже, MockWebServer принял бы запрос без `Authorization`.

### Сервер отдаёт, никто не зовёт

Из 353 маршрутов вычтены: вызываемые приложением, вызываемые `webapp/` и `web/`
(извлечение — `extract_web.py`, 353 пути, включая шаблонные литералы `${API_BASE}/events`),
7 WS-каналов, 4 внешних вебхука, 4 ops-ручки, 8 ссылок, которые открывает браузер/загрузчик
картинок (`/media/{}`, `/t/{}`, `/r/{}`, `/secure/docs/{}`, `/secure/evidence/{}`).

Остаётся **18 маршрутов без единого клиента**:

| Метод | Путь | Файл:строка |
|---|---|---|
| GET | `/admin/support/tickets` | support.py:247 |
| GET | `/admin/support/tickets/{id}` | support.py:289 |
| POST | `/admin/support/tickets/{id}/reply` | support.py:308 |
| POST | `/admin/support/tickets/{id}/close` | support.py:336 |
| GET | `/admin/bans` | antifraud.py:92 |
| POST | `/admin/bans/device` | antifraud.py:50 |
| DELETE | `/admin/bans/device/{id}` | antifraud.py:81 |
| GET | `/admin/sybil/suspects` | sybil.py:15 |
| GET | `/admin/payouts` | wallet.py:291 |
| GET | `/admin/events/summary` | events.py:123 |
| POST | `/admin/promo/{id}` | promo.py:359 |
| GET | `/ads/{id}/stats` | ads.py:237 |
| GET | `/medical-partners/{id}` | medical.py:42 |
| GET | `/reviews/mine` | reviews.py:69 |
| GET | `/rides/{id}/tips` | rides.py:429 |
| GET | `/users/{id}/trust` | incidents.py:527 |
| PATCH | `/requests/{id}` | requests.py:291 |
| POST | `/me/tips-sbp` | family.py:440 |

Смысловые группы:

* **Админ-поддержка целиком без клиента** (4 ручки, `support.py`). Пользователь тикет создаёт
  (`SupportChatScreen.kt`, `POST /support/tickets` — есть), а **ответить ему некому**: ни в
  Android, ни в `webapp/` нет ни одного вызова `/admin/support/*`, и в `ApiClient.kt` нет ни
  одного метода со строкой `admin/support`. Это не мусор — это оборванный сценарий.
* **Админ-антифрод (3) и Sybil (1)** — та же история: сигналы собираются, разбирать их нечем.
* `PATCH /requests/{id}` и `PATCH /rides/{id}` — двойники POST-алиасов `/edit`, которыми
  пользуется приложение (`HttpURLConnection` не умеет PATCH, см. комментарий
  `ApiClient.kt:1060`). `PATCH /rides/{id}` зовёт `webapp/`, `PATCH /requests/{id}` — никто.

Это не баги, но это площадь атаки: 4 из 18 — админские мутации (`POST /admin/bans/device`,
`DELETE /admin/bans/device/{id}`, `POST /admin/promo/{id}`, `POST /admin/support/…`), которые
живут в проде и не проверяются ни одним клиентским тестом.

---

## ПОДТВЕРЖДЁННЫЕ ДЫРЫ

### [P0] Подсказки адресов не работают вообще: `/geocode` требует токен, приложение его не шлёт

* **Файл:строка**
  * `android/app/src/main/java/com/yuldash/app/data/GeocoderClient.kt:40-46` — запрос строится
    без заголовка `Authorization`.
  * `backend/app/routers/discovery.py:132-133` — `def geocode(q: str = "", user: User = Depends(current_user))`.
  * `backend/app/security.py:18` — `bearer = HTTPBearer(auto_error=True)`.

* **Что не так**
  Клиент открывает соединение так:

  ```kotlin
  val url = URL("${ApiClient.apiBase()}/geocode?q=$enc")
  conn = (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = 8000
      readTimeout = 8000
      requestMethod = "GET"
  }
  ```

  Ни `Authorization`, ни `X-Device-Id`. Сервер же с 2026-08-03 закрыл ручку авторизацией —
  ровно чтобы аноним не жёг бесплатную квоту Яндекса (комментарий в `discovery.py:135-137`).
  `HTTPBearer(auto_error=True)` при отсутствующем заголовке отдаёт **403 «Not authenticated»**
  ещё до тела обработчика. `GeocoderClient` видит `code !in 200..299` и возвращает
  `Result.failure(IOException("geocode HTTP 403"))` — **всегда, для всех пользователей**.

* **Что видит пользователь**
  * Заказ такси, поле «Куда» (`InstantOrderScreen.kt:1338`, ветка `.onFailure` ставит
    `searchFailed = true`) → на каждый ввод адреса «не удалось найти, повторить?». Кнопка
    «Вызвать» не загорается — без точки Б нет `/instant/estimate`. **Такси заказать нельзя.**
  * «Мои места» (`SavedPlacesScreen.kt:263`, через `suggest()` → `getOrDefault(emptyList())`)
    → «ничего не нашли» на любой адрес.
  * Посылка (`ParcelsScreen.kt:1676-1677`) — координаты «откуда/куда» не определяются.
  * Карта (`MapScreen.kt:1411`, `:1464`) — разбор адреса в точку возвращает `null`.
  * Голосовой ввод адреса (`AccessibilityScreens.kt:411`) — пусто.

* **Доказательство**
  1. `web`-версия того же продукта шлёт токен и работает:
     `webapp/src/api/discovery.ts:75-78` зовёт `apiGet('/geocode?q=…')`, а `apiGet` →
     `request()` с `auth = true` по умолчанию (`webapp/src/api/client.ts:76,83`) →
     `Authorization: Bearer …`. То есть контракт ручки — «с токеном», и Android от него
     единственный отстал.
  2. Единственный тест ручки —
     `android/app/src/test/java/com/yuldash/app/data/GeocoderAndNetworkTest.kt:69-70` —
     проверяет только `rec.method == "GET"` и `rec.path.startsWith("/geocode?q=")`.
     Заголовки не проверяются, MockWebServer отвечает 200 кому угодно. Поэтому регрессия
     зелёная в тестах и мёртвая на устройстве.
  3. `GeocoderClient` — единственный сетевой клиент приложения, который **не** ходит через
     `ApiClient.call()`; все 306 остальных путей заголовок получают
     (`ApiClient.kt:2723-2725`).

* **Как чинить**
  Не подпирать `GeocoderClient` своим заголовком, а убрать шов: перевести `/geocode` на общий
  `ApiClient.call("GET", "/geocode?q=${enc(q)}", null, auth = true)` — тогда ручка автоматически
  получает `Authorization`, `X-Device-Id`, авто-refresh на 401, ретрай и общий разбор ошибок,
  а `GeocoderClient` остаётся только парсером `items[]` и LRU-кешем.
  В `ApiClientEndpointContractTest` добавить проверку присутствия заголовка `Authorization`
  для всех `auth = true` путей — тогда следующий такой обрыв поймает CI.

---

### [P1] Повтор при таймауте дублирует неидемпотентные POST (посылка, Boost-платёж, сообщение, поездка, заявка)

* **Файл:строка**
  `android/app/src/main/java/com/yuldash/app/data/ApiClient.kt:2713` (включение backoff),
  `:2731` (`conn.responseCode`), `:2735` (чтение тела), `:2758-2765` (повтор).

* **Что не так**
  Комментарий на `ApiClient.kt:2707-2712` утверждает:

  > «Идемпотентность: даже POST безопасен — повтор идёт лишь когда ответ не получен вовсе,
  > значит сервер запрос не обработал → дубля на бэкенде не будет.»

  Посылка «нет ответа ⇒ сервер не обработал» неверна. Тело запроса отправляется в `apply{}`
  на `:2728`; после этого:

  ```
  2731: val code = conn.responseCode          // ждёт readTimeout = 15 000 мс
  2735: val text = stream?.bufferedReader(...).use { it.readText() }
  ```

  `SocketTimeoutException` — наследник `IOException` — прилетает из `:2731`, когда сервер
  **уже принял и, возможно, закоммитил транзакцию**, но не уложился в 15 с (медленный запрос,
  тормозящая БД, деплой). Обрыв TCP на `:2735` — то же самое: 200 уже отдан. Оба случая
  попадают в `catch (e: IOException)` на `:2758` и повторяют **тот же POST** дважды
  (400 мс, 900 мс). `retryOnNetwork = false` выставлен ровно в одном месте на весь файл —
  `healthOk()`, `ApiClient.kt:225`.

* **Что видит пользователь**
  Одно нажатие «Отправить посылку» превращается в две заявки на доставку; одно нажатие
  «Поднять поездку» — в два платёжных намерения; одно сообщение в чате — в два.

* **Доказательство**
  Сервер защищён **выборочно**. Дедупликация есть:
  * `backend/app/routers/bookings.py:104-114` — «Защита от дубля: один пассажир не бронирует
    одну поездку повторно», возвращает `existing`;
  * `backend/app/routers/instant.py:306-315` — то же под row-lock для такси-заказа.

  Дедупликации нет (проверено сканом `existing|дубл|already|idempot` по телу обработчика):

  | Ручка | Обработчик | Чем платит пользователь |
  |---|---|---|
  | `POST /parcels` | parcels.py:407 `parcel_create` | вторая заявка на доставку |
  | `POST /boost/create` | payments.py:195 `boost_create` | второй платёж за поднятие |
  | `POST /support/donate` | payments.py:~284 `support_donate` | второй донат |
  | `POST /rides` | rides.py:105 `create_ride` | вторая опубликованная поездка |
  | `POST /requests` | requests.py:106 `create_request` | вторая заявка пассажира |
  | `POST /bookings/{id}/messages` | chat.py:585 | дубль сообщения |

  Ключ идемпотентности во всём приложении **один**:
  `ApiClient.kt:3695-3698` → `wallet.py:265`, `ledger.py:103-119` (вывод денег). Остальные
  деньги-операции не прикрыты.

* **Как чинить**
  Развести две ситуации, которые сейчас слиты в одну:
  1. исключение **до** `conn.responseCode` (соединение не установилось) — повторять можно;
  2. `SocketTimeoutException` из `:2731` и любой `IOException` из `:2735` — ответ мог
     существовать, повторять нельзя.

  Практично: держать `var requestSent = false`, ставить его после записи тела, и в
  `catch (e: IOException)` повторять только при `!requestSent || method == "GET"`.
  Параллельно — сквозной `Idempotency-Key` (UUID на попытку) для всех create-ручек, по образцу
  уже работающего `ledger.py:109` (`scoped_key`), и `retryOnNetwork = false` точечно для
  `/boost/create`, `/support/donate`, `/parcels`.

---

### [P1] Ошибка сервера (4xx/5xx) на чтении превращается в «пусто»: 58 мест

* **Файл:строка** — полный список получен брейс-точным сканом
  (`onsucc.py`: находит `.onSuccess { … }`, матчит скобки и смотрит, есть ли дальше по цепочке
  `.onFailure`/`.fold`). Самое чувствительное:

  | Файл:строка | Вызов | Что молча пустеет |
  |---|---|---|
  | `BookingActiveTripScreen.kt:1030` | `getBoardingCode` | посадочный код брони — главный артефакт доверия |
  | `BookingActiveTripScreen.kt:1096` | `getTripState` | роль/фаза поездки, «водитель выехал» |
  | `BookingActiveTripScreen.kt:1081,1095` | `getMessages` | история чата |
  | `BookingActiveTripScreen.kt:1788` | `getBookingShares` | активные ссылки «поделился с близким» |
  | `MapScreen.kt:334` | `getNearbyRequests` | заявки рядом на карте |
  | `MapScreen.kt:719` | `getFeed` | лента |
  | `YuldashApp.kt:602` | `getMyBookingsDetailed` | «мои поездки» |
  | `YuldashApp.kt:709` | `getRides` | список поездок |
  | `YuldashApp.kt:793` | `getContacts` | доверенные контакты |
  | `ProfileScreen.kt:1266` | `getDriverDebt` | долг водителя |
  | `ProfileScreen.kt:1315,1318` | `getTaxiWorkday` | смена таксиста |
  | `InstantOrderScreen.kt:2805` | `getInstantShares` | ссылки на заказ |
  | `InstantChatScreen.kt:86,128` | `getInstantOrder`, `getOrderMessages` | чат такси |
  | `SupportChatScreen.kt:431` | `closeSupportTicket` | **мутация** без обработки отказа |

* **Что не так**
  В проекте уже осознали проблему и починили её **наполовину**. `ApiClient.kt:204-214` заводит
  глобальный `serverUnreachable`, и комментарий там прямо говорит про «84 места без
  `.onFailure`». Но флаг ставится **единственно** на `ApiClient.kt:2766` — в ветке
  «повторы исчерпаны, `IOException`». Ответ **с** HTTP-кодом (500 от упавшего воркера, 502 от
  nginx при деплое, 403, 404) до этой строки не доходит: он уходит в
  `Result.failure(ApiException(...))` на `:2755`, а вызывающий код, где нет `.onFailure`,
  просто ничего не делает.

* **Что видит пользователь**
  Сервер лежит на 502 (деплой) → человек с активной бронью открывает поездку и видит **пустой
  посадочный код и пустой чат**, без единого слова о том, что что-то не так; плашки «нет связи»
  тоже нет — связь-то есть. Это тот же класс лжи, ради которого заводили `serverUnreachable`,
  просто с другой стороны.

* **Доказательство**
  * `ApiClient.kt:2755` — 5xx → `Result.failure(ApiException(code, …))`, `serverUnreachable`
    не трогается;
  * `ApiClient.kt:2732-2734` — на любом полученном коде флаг **гасится**
    (`serverUnreachable.value = false`), т.е. при 500 плашка гарантированно скрыта;
  * `UiKit.kt:406-416` — плашка рисуется исключительно по `serverUnreachable`.
  * Проверка сделана дважды: наивный скан по окну 700 символов дал 67 мест и **ложно**
    обвинил `BookingActiveTripScreen.kt:1228/1296/1561/1834` (`driverStatus`, `rateBooking`,
    `cancelBooking`, `shareTrip`) — у них `.onFailure` есть, просто дальше по тексту.
    В список выше вошли только те, что подтвердились брейс-точным разбором.

* **Как чинить**
  Дешёвый шов на одну строку: в `call()` рядом с `:2755` заводить второй глобальный сигнал
  (`serverError: MutableStateFlow<Boolean>`) для кодов 500..599 и показывать ту же плашку.
  Отдельно закрыть руками мутацию `SupportChatScreen.kt:431` и чтения активной поездки
  (`BookingActiveTripScreen.kt:1030,1096`) — там честная ошибка нужна точечно, глобальной
  плашки мало.

---

### [P2] Очередь офлайн-сообщений молча выбрасывает письмо при любой ошибке сервера

* **Файл:строка** `android/app/src/main/java/com/yuldash/app/data/TripPass.kt:258-264`

* **Что не так**

  ```kotlin
  val err = result.exceptionOrNull()
  if (err is ApiException) {
      // Сервер увидел запрос и отверг (не сеть) — повтор не поможет, снимаем из очереди.
      list = list.drop(1).toMutableList()
      writeAll(context, list); changed = true
  }
  ```

  `ApiException` — это **любой** HTTP-код: 500 (сервер моргнул), 429 («слишком часто»),
  401 (access истёк, пока телефон был офлайн, а refresh не прошёл). Во всех трёх случаях
  повтор бы помог, но действие удаляется навсегда и **без единого сигнала наверх**: `flush()`
  возвращает `Boolean` «что-то изменилось», а не «что потерялось».

* **Что видит пользователь**
  Пассажир на перевале пишет водителю «я у второго магазина», видит «в очереди», спускается в
  зону связи — и сообщение просто исчезает. Он уверен, что его отправили.

* **Доказательство**
  `ApiClient.kt:2755` создаёт `ApiException` для **всего** диапазона не-2xx.
  Retryable-коды (408, 429, 500..599) от неretryable (400, 403, 404, 409, 422) в
  `Outbox.flush` не различаются.

* **Как чинить**
  Выбрасывать из очереди только при кодах, где повтор бессмыслен (400, 403, 404, 409, 422);
  429 и 5xx оставлять с ограничением попыток (например 5) и возрастом (сутки). Возвращать из
  `flush()` список выброшенных, чтобы экран мог честно пометить сообщение «не доставлено» —
  для этого в чате уже есть механика (`ChatSocket.onRejected`, `ChatSocket.kt:31-33`).

---

### [P2] 7 из 9 «выстрелил и забыл» — мёртвый код, дублирующий мутирующие ручки

* **Файл:строка** `android/app/src/main/java/com/yuldash/app/data/ApiClient.kt:95-131`

* **Что не так**
  `fireCreateRequest` (:95), `firePublishRide` (:99), `fireAddContact` (:111),
  `fireRequestCallback` (:115), `fireSendMessage` (:127), `fireShareTrip` (:131),
  `fireSetTripStatus` (:135) — **не вызываются ниоткуда** (grep по всему `com/yuldash/app`
  вне `ApiClient.kt` даёт ноль). Живы только `fireUpdateName` (`LoginScreen.kt:443`) и
  `fireAddRecentPlace` (`InstantOrderScreen.kt:1740,1754`) — оба честно best-effort.

* **Что видит пользователь** Ничего — но это ловушка для следующего разработчика: имена
  выглядят как штатный путь публикации поездки и создания заявки, а `Result` в них
  выбрасывается на `bg`-scope. Один вызов `firePublishRide` вместо `publishRide` — и человек
  никогда не узнает, что сервер отверг публикацию.

* **Как чинить** Удалить семь неиспользуемых. Двум оставшимся — `@Suppress`-комментарий
  «результат сознательно игнорируется, это best-effort».

---

### [P2] Русскому пользователю показывается служебный код вместо текста ошибки

* **Файл:строка** `android/app/src/main/java/com/yuldash/app/data/ApiClient.kt:80-82`;
  источник — `backend/app/security.py:157` (`raise HTTPException(403, "phone_required")`).

* **Что не так**

  ```kotlin
  is String -> if (detail.isNotBlank())
      return if (langBa) genericByStatus(status, true) else detail
  ```

  `detail`-строка отдаётся русскому пользователю **как есть**. Для `phone_required` это
  машинный код. Экран входа его перехватывает по статусу (`LoginScreen.kt:449`), но
  `current_user` — общий guard: тот же 403 прилетит на **любом** авторизованном запросе,
  если у аккаунта телефон-заглушка. На всех остальных экранах пользователь увидит буквально
  `phone_required`.

* **Доказательство** `backend/app/security.py:155-157` — проверка `is_placeholder_phone`
  стоит в `current_user`, т.е. в зависимости всех 300+ авторизованных ручек.

* **Как чинить** На сервере — перевести `phone_required` (и другие машинные коды) на
  двуязычный `herr()` из `backend/app/errors.py:11`. На клиенте — в `errorMessage()` не отдавать
  наружу строку без пробелов и из `[a-z_]` (это код, а не текст), подставляя
  `genericByStatus`.

---

### [P2] Флаг `retryOnNetwork` теряется при повторе после обновления токена

* **Файл:строка** `android/app/src/main/java/com/yuldash/app/data/ApiClient.kt:2747`

* **Что не так**
  `if (tryRefresh(usedToken)) call(method, path, body, auth, isRetry = true)` — параметр
  `retryOnNetwork` не передаётся и сбрасывается в дефолтный `true`. Вызов, который автор
  сознательно пометил «не повторять», после 401 начинает повторяться.

* **Что видит пользователь** Сегодня — ничего (единственный `retryOnNetwork = false` стоит на
  `healthOk()`, ручке без авторизации). Дыра сработает ровно тогда, когда флагом закроют
  неидемпотентный POST — то есть при починке P1 выше.

* **Как чинить** Дописать `retryOnNetwork = retryOnNetwork` в рекурсивный вызов.

---

## ПРОВЕРЕНО И ЧИСТО

**Пути.** 306 путей приложения против 353 маршрутов сервера — 0 отсутствующих, 0 расхождений
по методу. Алиас `/api/v1` (`main.py:130-133`) учтён, префиксов у роутеров нет.

**Поля запросов.** Автосверка тел POST против Pydantic-моделей: 137 моделей разобрано
(с балансировкой скобок в сигнатурах — иначе `Depends(get_session)` рвёт разбор; модели
резолвятся по паре `(файл, имя)`, иначе четыре разных `RespondIn`/`ResolveIn`/`CancelIn`
схлопываются и дают ложные срабатывания). **113 совпавших ручек, 0 расхождений**: ни одного
обязательного поля, которого приложение не шлёт, ни одного лишнего поля. Отдельно вручную
проверены `POST /rides` (24 поля против `schemas.py:15` `RideIn`, обязательные `from_city`/
`to_city`/`depart_at` шлются), `POST /instant/orders` (`instantBody()` даёт обязательные
`from_lat/from_lng/to_lat/to_lng` из `instant.py:76-86` `EstimateIn`), `POST /instant/schedule`
(`ScheduleIn(OrderIn)` + `scheduled_at`).

**Поля ответов.** 458 различных ключей, которые приложение читает из JSON — **все** встречаются
в исходниках бэкенда. Разбор ответов сделан безопасно: во всём `ApiClient.kt` **ноль**
`getString`/`getInt`/`getJSONObject` на JSON-ответах и **ноль** операторов `!!`; только
`opt*` с дефолтами и хелперы `nStr`/`nInt`/`nDbl` (`ApiClient.kt:2843-2846`), проверяющие
`isNull`. Отсутствующее поле не роняет приложение.

**Авто-refresh токена.** `refreshMutex` (`ApiClient.kt:52`) + `tryRefresh`
(`ApiClient.kt:2830-2840`) реализованы правильно:
* пачка параллельных 401 рефрешит **один раз** — второй поток видит `token != staleToken`
  и сразу возвращает `true` (сравнение по «протухшему» токену, а не по факту «кто-то
  обновлял»);
* бесконечный цикл невозможен: `isRetry = true` на повторе и на самом `/auth/refresh`;
* мёртвый refresh → `logout()` + `sessionExpired.value = true` (`ApiClient.kt:2751-2752`),
  UI ловит на `YuldashApp.kt:321-325`, а не молча деградирует в «пусто»;
* потери запроса нет — повтор идёт тем же `method/path/body`.

**Выход из аккаунта.** `logout()` (`ApiClient.kt:323-338`) отвязывает пуш **до** гашения токена
(порядок важен и он верный), затем гасит серверную сессию, затем синхронно чистит локальное:
токены, кеш ответов, `push_token`, паспорта поездок с чужими ПДн, очередь исходящих
(`ApiClient.kt:360-378`). Сервер честно ревокацию соблюдает —
`_token_revoked` по `tokens_valid_from` (`backend/app/security.py:154-165`) применяется и в
REST, и в WS (`authenticate_ws`, `security.py:102-111`).

**Логи.** Во всём `com/yuldash/app` — **ноль** `Log.d/e/i/w/v`, `println`, `System.out`.
Единственный внешний сигнал — `Sentry.captureMessage` на `ApiClient.kt:182` с константной
строкой без PII (факт «Keystore недоступен»). `Analytics.kt` шлёт только имя события и
строковые параметры; телефон/координаты/токен туда не попадают. Токен в WS передаётся
**первым сообщением**, а не в query-string (`ChatSocket.kt:107`, `MapFeedSocket.kt:50`,
`LocationSocket.kt:59`) — именно чтобы не утечь в логи nginx; сервер того же и требует
(`chat.py:198-200`).

**Хранение секретов.** Токены — `EncryptedSharedPreferences` с миграцией со старого открытого
хранилища (`ApiClient.kt:154-176`); недоступность Keystore не ломает вход, но поднимает флаг
`secureStorageUnavailable` и предупреждение в Sentry. Телефон водителя в офлайн-паспорте —
там же (`TripPass.kt:88-107`).

**Таймауты.** HTTP: `connectTimeout/readTimeout = 15 000` (`ApiClient.kt:2720-2721`), multipart
20 000/30 000 (`ApiClient.kt:2795-2796`), геокодер 8 000/8 000 (`GeocoderClient.kt:42-43`),
`postWithToken` 15 000 (`ApiClient.kt:345-346`). Не осталось ни одного вызова без таймаута.

**WebSocket — жизненный цикл.** Все четыре клиента написаны по одному шаблону и он верный:
* backoff `1,2,4,8,16,30…` с потолком 30 с — сервер не заливается;
* «бесконечность» ретрая ограничена жизнью экрана: `close()` ставит `closed = true`
  (`ChatSocket.kt:176`, `MapFeedSocket.kt:79`, `LocationSocket.kt:133`);
* терминальные коды **не** зацикливаются: `1008` и `4000..4999` реконнект не запускают
  (`ChatSocket.kt:141-143`), при этом `1008 "not active"` для гео вынесен в мягкий ретрай
  с жёстким потолком (`LocationSocket.kt:127-131`, `MAX_SOFT_ATTEMPTS = 40`);
* `onClosing` отвечает встречным `close()` — иначе handshake не завершается и стрим тихо
  умирает до TCP-таймаута (`ChatSocket.kt:134-137`);
* гонка «reconnect ↔ connect» закрыта: `@Synchronized openSocket()` + `ws?.close(4999)`
  перед созданием нового — двух живых сокетов на канал не бывает (`ChatSocket.kt:104`);
* отписка от `NetworkMonitor` в `close()` есть везде; сам монитор держит слушателей через
  `WeakReference` и чистит мёртвые (`NetworkMonitor.kt:31,54-77`) — забытая отписка не течёт;
* `OkHttpClient` — один на процесс (`by lazy` в `companion object`), планировщик реконнектов —
  один демон-поток на класс. Утечки соединений при уходе с экрана нет.
* Контракт авторизации совпадает с сервером: `{"type":"auth","token":…}` первым кадром
  (`chat.py:203-206`, `location.py`), сервер проверяет ревокацию через `authenticate_ws`.

**Экранирование query.** 21 использование `enc()` (`ApiClient.kt:589`); все
неэкранированные интерполяции в query — числа (`limit`, `lat`, `lng`, id) либо серверные
enum-ы (`status`, `period`, `scope`, `role`). Пользовательский текст без `URLEncoder` в URL
не попадает.

**Ретрай геокодера и «нет связи».** `GeocoderClient.suggestResult` честно различает
«спросили и не нашли» (`success(emptyList())`) и «спросить не смогли» (`failure`) —
`GeocoderClient.kt:33-64`. Логика правильная; беда только в том, что из-за P0-1 она **всегда**
идёт по второй ветке.

---

## НЕ ДОКАЗАНО

**Двуязычие ошибок сервера — половина ручек только по-русски.** `herr()`
(`backend/app/errors.py:11`, `detail={"ru","ba"}`) использован **270** раз, «сырой»
`HTTPException` со строкой — **261** раз. Для этих 261 башкирский пользователь получает не
конкретную причину, а общую фразу по коду (`ApiClient.kt:56-66`) — клиент отрабатывает
корректно, но текст теряет смысл («Проверь введённые данные» вместо «У объявления не выбран
тариф»). Это нарушение правила §3 CLAUDE.md, но на стороне бэкенда и вне моего среза —
нужен отдельный проход по `backend/app/routers/`.

**Гонка «мигания» индикатора связи в WS.** При замене сокета (`ws?.close(4999, "replaced")`,
`ChatSocket.kt:104`) старый листенер получит `onClosed` → `onConnected(false)`, а новый —
`onOpen` → `onConnected(true)`. Порядок между потоками не гарантирован, теоретически возможен
кратковременный «не в сети» после успешного подключения. Воспроизвести без устройства не могу,
влияние — косметика.

**Вечный поллинг закрытого заказа.** `InstantOrderScreen.kt:4054-4058`: цикл
`while (isActive) { delay(5_000); getInstantOrder(...).onSuccess {…} }` выходит только по
статусу `done/cancelled/expired`. Если сервер начнёт отдавать 404/410 (заказ вычищен
ретеншеном), `order` останется старым и опрос будет идти каждые 5 с всё время, пока экран
открыт. Нужен прогон на устройстве — не доказано.

**Реальное поведение прода.** Все выводы получены статически, по коду в этом дереве. Развёрнутый
`yulbash.ru` мог отстать от репозитория — в частности, если там ещё живёт версия `/geocode`
**без** `Depends(current_user)`, P0-1 в проде пока не проявится, но проявится с ближайшим
деплоем. Проверяется одной командой с устройства/машины с сетью:
`curl -i https://yulbash.ru/geocode?q=Сибай` — 200 значит старая версия, 403 значит дыра уже
живая.

**Мёртвые эндпоинты по webapp.** Список «никто не зовёт» опирается на текстовый поиск путей в
`webapp/src` и `web/`. Пути, которые там собираются из нескольких переменных
(`${base}${sub}/x`), поиск не увидит, поэтому отдельные из 18 могут оказаться живыми. Ключевой
вывод — «админ-поддержка и админ-антифрод не имеют клиента» — проверен дополнительно: в
`ApiClient.kt` нет ни одной строки `admin/support` или `admin/bans`, и файла экрана с таким
названием в `com/yuldash/app` тоже нет.
