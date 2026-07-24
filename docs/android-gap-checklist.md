# 📱 Android-чеклист GAP-фич (G1/G3/G7/G8) — под сборку у Александра

> Бэкенд этих фич **готов и в PR #100** (все 3 CI зелёные). Клиентские части ещё не написаны —
> собрать/проверить APK в облаке нельзя (нет Android SDK, Google Maven заблокирован прокси).
> Здесь — точная спека: API, DTO, куда встраивать, состояния, строки. Код-наброски ApiClient/DTO
> сделаны по существующим паттернам (`call(...)`, `data class …Dto`) — **проверить сборкой** (я не компилировал).
> Строки RU готовы; **черновой BA** — в `docs/tasks.md` (разделы «Переводы на проверку»), финал за носителем.
> Стандарт качества: все состояния (загрузка/пусто/ошибка), тёмная тема, `Canon*`-токены, тач-цель ≥48dp,
> двуязычие через `appText(ru, ba)` (CLAUDE.md §4, §4.5).

Паттерн ApiClient (подтверждён по коду): `call("METHOD","/path", jsonBodyOrNull, auth=true): Result<JSONObject>`;
списки читаются как `obj.optJSONArray("items")`; события — `Analytics.log("...")`.

---

## G1 — «Поделиться отслеживанием» посылки (для получателя)

**Кому:** отправитель посылки. **Где:** карточка своей посылки в `ParcelsScreen.kt` (список из `getMyParcels`),
показывать пока `status ∈ {created, accepted, in_transit}` (не для delivered/canceled).

**API:** `POST /parcels/{id}/track-link` (auth, только отправитель).
Ответ: `{ "token": "…", "url": "https://yulbash.ru/t/…", "sms_sent": true|false }`.
Ошибки: `404` (не твоя посылка), `409` (уже завершена).

**ApiClient (добавить):**
```kotlin
data class TrackLinkDto(val token: String, val url: String, val smsSent: Boolean)

/** G1: получить трекинг-ссылку посылки для получателя (кнопка «Поделиться отслеживанием»). */
suspend fun parcelTrackLink(id: Int): Result<TrackLinkDto> =
    call("POST", "/parcels/$id/track-link", null, auth = true).map {
        TrackLinkDto(it.optString("token"), it.optString("url"), it.optBoolean("sms_sent"))
    }.onSuccess { Analytics.log("parcel_track_link") }
```

**UI:** кнопка «Поделиться отслеживанием · Күҙәтеү менән бүлеш» на карточке отправителя →
`parcelTrackLink(id)` → **переиспользовать готовый `LiveLinkCard` из `TripLiveLink.kt`** (ссылка + «Скопировать» +
системный share-sheet). Если `smsSent == true` — подсказка «Отправили ссылку получателю по SMS». Состояния:
лоадер на кнопке, ошибка сети → тост «Не получилось. Повторить?».

**Готово, когда:** отправитель жмёт кнопку → видит ссылку/шэрит; открытие `url` в браузере показывает живую
доставку (страница уже на бэке). Телефоны нигде не показываются.

---

## G3 — Водителю «караулить заявки по моему направлению»

Расширяет существующий route-watch (подписку на маршрут). Сейчас `createRouteWatch`/`RouteWatchDto`
**не знают `watch_kind`** — добавить.

**API:** `POST /route-watch` — тело теперь принимает `watch_kind: "rides" | "requests" | "both"`
(дефолт `"rides"` = прежнее поведение). `GET /route-watch` отдаёт `watch_kind` в каждом элементе.
**Пуш водителю** при новой подходящей заявке: `type = "request_watch"`, заголовок «Пассажир на твоём
маршруте», тело — «Город → Город», `data.ref_kind="request"`, `data.ref_id=<id заявки>`.

**ApiClient (правки существующего):**
```kotlin
// createRouteWatch — добавить параметр и поле:
suspend fun createRouteWatch(
    fromCity: String,
    toCity: String,
    direction: String = "forward",
    watchDate: String? = null,
    watchKind: String = "rides",          // G3: rides | requests | both
): Result<Int> = call(
    "POST", "/route-watch",
    JSONObject()
        .put("from_city", fromCity).put("to_city", toCity)
        .put("direction", direction).put("watch_kind", watchKind)
        .apply { watchDate?.takeIf { it.isNotBlank() }?.let { put("watch_date", it) } },
    auth = true,
).map { it.optInt("id") }.onSuccess { Analytics.log("route_watch_create") }

// RouteWatchDto — добавить поле watchKind (default "rides"); в getRouteWatches распарсить:
//   watchKind = o.optString("watch_kind").ifBlank { "rides" },
```

**UI:** на экране подписки на маршрут — переключатель/сегмент «Что караулить»:
«Поездки (я пассажир)» / «Заявки (я водитель)» / «И то, и то» → маппинг rides|requests|both.
В `MainActivity` (обработчик FCM/уведомлений) добавить ветку `type == "request_watch"` → открыть ленту
заявок (`requests/near` / экран заявок), по возможности к заявке `ref_id`.

**Готово, когда:** водитель подписался с «Заявки» → при создании пассажиром подходящей заявки приходит
пуш «Пассажир на твоём маршруте» и тап ведёт в ленту заявок. Старые подписки (rides) работают как раньше.

---

## G7 — Карточка «Почему мало откликов» (диагностика водителю)

**Кому:** водитель — на экране СВОЕЙ поездки / в кабинете. **Где:** `InstantOrderScreen.kt` или карточка
поездки водителя (там, где он видит свою опубликованную поездку).

**API:** `GET /rides/{id}/tips` (auth, только водитель этой поездки; иначе `404`).
Ответ: `{ "ride_id": int, "tips": [ {"code","ru","ba"} … ], "all_good": bool, "route_avg_price": int, "route_sample": int }`.
Коды советов: `add_photo`, `get_verified`, `lower_price`, `add_details`.

**ApiClient (добавить):**
```kotlin
data class RideTipDto(val code: String, val ru: String, val ba: String)
data class RideTipsDto(val tips: List<RideTipDto>, val allGood: Boolean, val routeAvgPrice: Int, val routeSample: Int)

/** G7: мягкие советы «как получить больше заявок» по своей поездке. */
suspend fun getRideTips(id: Int): Result<RideTipsDto> =
    call("GET", "/rides/$id/tips", null, auth = true).map { o ->
        val arr = o.optJSONArray("tips") ?: JSONArray()
        RideTipsDto(
            tips = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                RideTipDto(t.optString("code"), t.optString("ru"), t.optString("ba"))
            },
            allGood = o.optBoolean("all_good"),
            routeAvgPrice = o.optInt("route_avg_price"),
            routeSample = o.optInt("route_sample"),
        )
    }
```

**UI:** карточка «Как получить больше заявок» в `Canon*`-стиле: список советов (иконка + текст `appText(ru,ba)`).
`allGood == true` (tips пусто) → дружелюбная заглушка «Всё выглядит хорошо — заявки скоро появятся 👍», не пустой
экран. Совет `lower_price` может показать ориентир `routeAvgPrice` ₽. Состояния: загрузка/ошибка.

**Готово, когда:** у поездки без фото/проверки/с завышенной ценой водитель видит добрые советы; у «хорошей»
поездки — позитивную заглушку.

---

## G8 — Карточка «Достижения» в профиле

**Где:** `ProfileScreen.kt`.

**API:** `GET /me/achievements` (auth).
Ответ: `{ "trips": int, "parcels_helped": int, "days_with_yuldash": int, "earned_count": int,
"achievements": [ {"code","ru","ba","goal":int,"value":int,"earned":bool} … ] }`.
Коды: `first_trip, trips_10, trips_50, trips_100, parcel_helper, year_with_yuldash, verified`.

**ApiClient (добавить):**
```kotlin
data class AchievementDto(val code: String, val ru: String, val ba: String,
                          val goal: Int, val value: Int, val earned: Boolean)
data class AchievementsDto(val trips: Int, val parcelsHelped: Int, val daysWithYuldash: Int,
                           val earnedCount: Int, val items: List<AchievementDto>)

/** G8: бейджи-достижения профиля (из реальных данных). */
suspend fun getAchievements(): Result<AchievementsDto> =
    call("GET", "/me/achievements", null, auth = true).map { o ->
        val arr = o.optJSONArray("achievements") ?: JSONArray()
        AchievementsDto(
            trips = o.optInt("trips"), parcelsHelped = o.optInt("parcels_helped"),
            daysWithYuldash = o.optInt("days_with_yuldash"), earnedCount = o.optInt("earned_count"),
            items = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                AchievementDto(a.optString("code"), a.optString("ru"), a.optString("ba"),
                    a.optInt("goal"), a.optInt("value"), a.optBoolean("earned"))
            },
        )
    }
```

**UI:** сетка бейджей в профиле: полученные (`earned`) — яркие с эмодзи, остальные — приглушённые с прогрессом
`value/goal` (полоска). `Canon*`-стиль, тёмная тема, тач-цель. Плавное появление (`AnimatedVisibility`). Состояния:
загрузка/ошибка; пусто быть не может (verified/first_trip почти всегда есть).

**Готово, когда:** в профиле видна сетка бейджей с реальными цифрами и прогрессом до следующего.

---

## После реализации (общее)
1. Собрать `assembleDebug` — зелёно.
2. Прогнать на телефоне: клиент/водитель/курьер — основные сценарии 4 фич.
3. Строки BA — сверить с носителем (`docs/tasks.md`).
4. Обновить `docs/architecture.md` (у каждой фичи снять пометку «Android — по чеклисту»).
