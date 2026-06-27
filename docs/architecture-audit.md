# 🏗️ Архитектурный аудит Юлдаша (senior-разбор)

> Дата: 2026-06-27. Метод: 4 параллельных read-only аудита (UI-ядро, крупные экраны, слой данных, бэкенд).
> Цель: понять архитектуру, найти плохие решения / дубли / узкие места / риски роста / проблемы поддержки.
> ⚠️ **Поведение приложения НЕ менялось.** Это разбор + план, код не тронут. Аудит делался в worktree `pedantic-kare-ef1707`, параллельно шла другая сессия (отдельный worktree) — конфликтов нет.

---

## 1. Понятный разбор архитектуры (как устроено и как текут данные)

**Три слоя, но «прототипные»:**

```
┌─────────────────────────────────────────────┐
│  UI (Jetpack Compose)                         │
│  YuldashApp() — ОДИН гигантский composable:   │
│   • всё состояние в remember{} (нет ViewModel)│
│   • навигация = enum Screen + when (нет NavHost)│
│   • данные раздаются props-drilling (HomeScreen= 37 параметров)│
│   • экраны сами зовут сеть из LaunchedEffect  │
└───────────────┬───────────────────────────────┘
                │ ApiClient.xxx()  (напрямую, без репозитория)
┌───────────────▼───────────────────────────────┐
│  Данные (Android)                              │
│  object ApiClient — синглтон на HttpURLConnection│
│   • JSON парсится руками в каждом методе       │
│   • JWT в SharedPreferences (открытым текстом) │
│   • запись = fireXxx (fire-and-forget, ошибки теряются)│
│   • ChatSocket (OkHttp WS) только в активной поездке│
│  ☠️ Repository.kt / MockRepository.kt — мёртвый код│
└───────────────┬───────────────────────────────┘
                │ HTTPS → yulbash.ru
┌───────────────▼───────────────────────────────┐
│  Бэкенд (FastAPI + PostgreSQL)                 │
│  main.py — 1216 строк, ВСЕ ~60 эндпоинтов      │
│   • роуты ходят в БД напрямую (нет сервис-слоя)│
│   • миграций нет (на Postgres — ручной ALTER)  │
│   • матчинг наивный (LIKE %город% + haversine в Python)│
│   • WebSocket-чат в памяти процесса            │
└────────────────────────────────────────────────┘
```

**Главный вывод:** приложение **работает**, но держится на паттернах прототипа. Два корневых решения тянут за собой почти все проблемы:
1. **Нет слоя состояния** (ViewModel) на клиенте → состояние теряется, бизнес-логика в UI.
2. **Нет настоящей навигации и нет сервис-слоя на бэке** → god-объекты, которые невозможно параллелить и тестировать.

Это нормально для стадии «кликабельный прототип». Но перед бетой с реальными людьми часть вещей — уже риск.

---

## 2. Критичные проблемные места (🔴 — чинить до беты)

### Безопасность и доверие (ядро продукта «между своими»)

| # | Где | Проблема | Почему критично |
|---|-----|----------|-----------------|
| S1 | `backend/app/main.py:843` | **WS-чат не проверяет, твоя ли это бронь.** Любой залогиненный по `?token=свой&booking_id=любой` читает и **пишет** в чужой приватный чат. | Утечка телефонов и договорённостей. REST это проверяет, WebSocket — забыли. |
| S2 | `backend/app/main.py:178` | **Перебор SMS-кода.** 4-значный код (10 000 вариантов), нет счётчика попыток и rate-limit → угон аккаунта за секунды. | Telegram-вход защищён, SMS — нет. Сейчас SMS заморожен, но код жив → включат флаг и дыра открыта. |
| S3 | `ChatSocket.kt:30` + `main.py:846` | **JWT-токен в URL WebSocket** (`?token=...`). Попадает в логи nginx/прокси. Токен живёт 30 дней. | Долгоиграющая утечка доступа к аккаунту. |
| S4 | `data/ApiClient.kt:79` | **JWT хранится открытым текстом** в SharedPreferences (нет шифрования, нет срока годности). | На root-устройстве/в бэкапе читается напрямую. |
| S5 | `main.py:122,146` | **Телефоны и коды печатаются в логи** (`[OTP] {phone} -> {code}`). | Нарушение 152-ФЗ (перс.данные в plaintext-логах). |
| S6 | `data/GeocoderClient.kt:26` | **Ключ Яндекс-геокодера уходит с устройства** в URL → извлекается из APK/трафика. | Любой тратит вашу квоту. |
| S7 | `config.py:3` + `security.py:22` | **Один секрет на JWT и на Telegram-HMAC**, дефолт `dev-secret-change-me`. Защита держится только на правильном `ENV=prod`. | Случайный старт с `ENV=dev` на проде → токены подделываются. |

### Надёжность

| # | Где | Проблема | Почему критично |
|---|-----|----------|-----------------|
| R1 | `data/ApiClient.kt:37-73` | **`fireXxx` глотает ошибки** — бронь и **SOS** fire-and-forget: при плохой сети операция молча проваливается, UI рапортует «ок». | Для кнопки SOS («между своими») это недопустимо. |
| R2 | `backend/app/main.py:602` | **Гонка при бронировании** последнего места (нет `SELECT FOR UPDATE`). Два одновременных бронирования → овербукинг, `seats_left` в минус. | Реальные деньги/доверие на старте. |
| R3 | `YuldashApp.kt:507` | `BookingScreen(ride = selectedRide ?: rides.first())` — **краш** `NoSuchElementException`, если сервер недоступен и моки пусты. | Падение на пустом состоянии. |
| R4 | `BookingActiveTripScreen.kt:509` | **Stale-closure в WS-обработчике чата**: лямбда читает устаревший снимок `messages` → потеря/дубли сообщений на быстром потоке. | Чат «съедает» сообщения. |

---

## 3. Архитектурные проблемы и риски роста (🟡)

### Фундамент
- **Нет ViewModel / `rememberSaveable`** (`YuldashApp.kt`): состояние (текущий экран, язык, активная поездка, набранная заявка) теряется при повороте экрана и сворачивании (process death). 0 использований `rememberSaveable` во всём проекте.
- **Навигация на `enum Screen` + `when`** (26 экранов в одном `when`): нет back-stack (кнопка «назад» всегда кидает на Home), нет аргументов экранов, нет deep links. Развалится на сценарии «чат → профиль водителя → назад в чат».
- **God-composable `YuldashApp`** (~390 строк логики + бизнес-логика брони/шаринга/парсинга прямо в UI-лямбдах). Один файл — один писатель: блокирует параллельную работу агентов.
- **Бэкенд-монолит** `main.py` 1216 строк — все эндпоинты, схемы, логика, гео, WS в одном файле. Нельзя параллелить, тяжело тестировать.
- **Нет миграций на Postgres** (`db.py`): `create_all` не добавляет колонки → любое изменение модели = ручной SQL на сервере или потеря данных.

### Узкие места по скорости
- **Списки без `key`** (`MapScreen`, `RidesRequestsChatScreens`, `BookingActiveTripScreen`): `LazyColumn`/`LazyRow` без ключей → лишние рекомпозиции, сбой анимаций `appearIn`, потеря позиций. **Самый дешёвый перф-выигрыш.**
- **Поллинг `while(true){ apiCall; delay() }`** в `MapHero` (`MapScreen.kt:419`) — два бесконечных цикла ради «живой» карусели. На реальном трафике × все юзеры = постоянная нагрузка на бэк.
- **N+1 на бэке** (`main.py:485`): `_ride_out()` для каждой поездки делает отдельные запросы User + DriverProfile + рейтинг. 50 поездок = 150+ запросов.
- **Гео в Python** (`main.py:507`): `/rides/near` тянет все активные поездки и считает haversine в цикле; город — `LIKE %x%` (не использует индекс).
- **Блокирующая отправка SMS** в обработчике (`_send_sms` через `httpx.get(timeout=10)`): SOS шлёт SMS синхронно в цикле по контактам — запрос висит до ответа sms.ru.
- **Файлы как Base64 в JSON** (`ApiClient.kt:425`): фото/голос +33% объёма и весь файл строкой в памяти.

### Риски при росте
- **Нет пагинации нигде** (клиент + бэк): `/rides`, `/messages`, `/conversations` отдают всё. Чат на 10 000 сообщений вернётся целиком.
- **WebSocket в памяти процесса** (`main.py:816`): с несколькими воркерами (обычный прод) сообщения между воркерами не доставляются. Нужен Redis pub/sub.
- **Props-drilling 37 параметров** в `HomeScreen` — каждый новый экран = правка в 3 местах.
- **Общий `bg`-scope без отмены** (`ApiClient.kt:35`): спам-тапы плодят неотменяемые корутины по 15с.

---

## 4. Дублирующаяся логика (🟡)

| Что дублируется | Где | Фикс |
|-----------------|-----|------|
| Маппинг `RideDto` (~25 полей) | `ApiClient.kt:181` (getRides) **vs** `:216` (getNearbyRides) | `JSONObject.toRideDto()` — звать из обоих |
| Маппинг `RideDto → Ride` | `YuldashApp.kt:335` **vs** `MainActivity.toUiRide()` | оставить только `toUiRide()` |
| Карточки поездки (~250 строк) | `RideCard` / `FullRideCard` / `NearbyRideCard` | `RideCardCore(ride, variant)` + слоты |
| Плеер голосовых | `MessageBubble` (Booking:740) **vs** `VoiceMessageCard` (Rides:1422) | `rememberAudioPlayer()` |
| Двуязычный Toast (×9) | `YuldashApp.kt` | хелпер `toast(ru, ba)` |
| `_send_sms` ≈ `_send_text` | `main.py:111` | один `_sms(phone, text, is_otp=)` |
| Боль «booking_ids юзера» | `conversations`/`notifications`/`driver_bookings` | `_user_booking_ids(session, user)` |

**Мёртвый код на удаление:**
- Android: `data/Repository.kt`, `data/MockRepository.kt`, `data/Models.kt` (0 ссылок), `vkCallback`/`whatsappCallback` (VK/WhatsApp убраны).
- Backend: `security.py:40` `sign_telegram`/`verify_telegram` (Telegram-вход теперь по коду, подпись не зовётся).

---

## 5. Проблемы с поддержкой (🟡🟢)

- **Импорты-копипаста** (~240 строк) дословно в `MainActivity`/`Mocks`/`YuldashApp` и в 4 экранах, включая лишние (в `Mocks.kt` импортирован MapKit/MediaRecorder без нужды).
- **Функции на 100–255 строк**: `ActiveTripScreen` (255), `AdsAdminPreview` (70), затенение `val stats` дважды.
- **Хардкод `Color(0xFF…)` в экранах** (67 вхождений) вместо `Canon*` — точечно ломает тёмную тему (нарушение CLAUDE.md §4.5).
- **Тесты — только happy-path** (`smoke.py`): ни одного теста на отказ доступа. Именно поэтому WS-IDOR и перебор кода не заметили. Нет негативных тестов авторизации.
- **Глобальный синглтон темы** `object ThemePrefs` с `mutableStateOf` — состояние вне дерева Compose, мешает превью/тестам.

---

## 6. Стратегия рефакторинга (поэтапно, под соло-разработчика)

> Принцип: каждый шаг **не меняет поведение**, собирается зелёным, коммитится отдельно. Идём сверху вниз по рычагу.

### 🚑 Фаза 0 — Безопасность до беты (дни). Это НЕ меняет работу для пользователя.
1. **WS-IDOR** (`main.py:843`): после `verify_token` вызвать `_booking_and_ride_for_user(...)`, иначе `close(1008)`.
2. **Перебор кода** (`main.py:178`): поле `attempts` в `OtpCode` + лимит 5 + rate-limit на `/auth/request-code` и `/auth/verify`.
3. **Токен из URL WS** → передавать первым WS-сообщением после `accept` (клиент + сервер).
4. **JWT** → `EncryptedSharedPreferences` (`androidx.security-crypto`) + срок жизни.
5. **Логи**: маскировать телефон (`+7****1234`), коды не писать никогда.
6. **Геокодер** → проксировать через свой бэкенд, ключ убрать с клиента.
7. **SOS и бронь** — убрать из `fireXxx`: показывать loading/ok/error + «Повторить».
8. **Бронь** (`main.py:602`): `with_for_update()` или атомарный `UPDATE ... WHERE seats_left >= :n`.
9. **Секреты**: разнести `JWT_SECRET` и `TELEGRAM_SIGN_SECRET`; падать на дефолте всегда.

### 🧱 Фаза 1 — Фундамент (недели). Разблокирует всё остальное.
1. **Один `YuldashViewModel`** + `rememberSaveable` для критичного состояния (screen, language, activeTrip). Чинит потерю состояния при повороте/process-death.
2. **Navigation-Compose** вместо `enum Screen` + `when`: типизированные маршруты, back-stack, аргументы. Заодно разблокирует параллельную работу агентов (разные destination-функции вместо одного `when`).
3. **Бэкенд → роутеры** (`routers/auth.py`, `rides.py`, `bookings.py`, `chat.py`) + слой `services/`. + **Alembic** для миграций.

### ✨ Фаза 2 — Качество (по ходу).
1. **`key` во все списки** — дешёвый перф-выигрыш, можно делать сразу.
2. **Дедуп карточек** → `RideCardCore`; **дедуп маппинга** `RideDto`.
3. **REST на OkHttp/Retrofit** + типизированный парсинг (поэтапно, начиная с новых эндпоинтов; OkHttp уже в зависимостях).
4. **Бэк:** `selectinload` против N+1, пагинация на списках, индекс/PostGIS под гео, SMS в `BackgroundTasks`.
5. **Удалить мёртвый код**, почистить импорты.

---

## 7. Улучшенный код продакшен-уровня (примеры before/after)

### 7.1 🔴 WS-IDOR — закрыть доступ к чужому чату (`main.py:843`)
```python
# BEFORE — проверяется только валидность токена:
@app.websocket("/ws/bookings/{booking_id}")
async def ws_booking_chat(websocket: WebSocket, booking_id: int, token: str):
    user = verify_token(token)            # ← кто угодно с валидным токеном
    if not user:
        await websocket.close(code=1008); return
    await manager.connect(booking_id, websocket)
    ...

# AFTER — проверяем, что юзер участвует именно в этой брони:
@app.websocket("/ws/bookings/{booking_id}")
async def ws_booking_chat(websocket: WebSocket, booking_id: int):
    await websocket.accept()
    raw = await websocket.receive_json()          # токен первым сообщением, НЕ в URL
    user = verify_token(raw.get("token", ""))
    if not user:
        await websocket.close(code=1008); return
    with Session(engine) as session:
        booking, ride = _booking_and_ride_for_user(session, booking_id, user)
        if booking is None:                       # чужая бронь → отказ
            await websocket.close(code=1008); return
    await manager.connect(booking_id, websocket)
    ...
```

### 7.2 🔴 Перебор кода — счётчик попыток (`main.py:178`)
```python
# AFTER — в OtpCode добавить attempts; в /auth/verify:
otp = session.exec(select(OtpCode).where(OtpCode.phone == phone)).first()
if not otp or otp.attempts >= 5:
    raise HTTPException(429, "Слишком много попыток. Запроси новый код.")
otp.attempts += 1
session.add(otp); session.commit()
if otp.code != code or otp.expires_at < datetime.utcnow():
    raise HTTPException(400, "Неверный или истёкший код.")
# + rate-limit на /auth/request-code (напр. slowapi: @limiter.limit("3/minute"))
```

### 7.3 🟡 Списки без `key` — стабильный diff и анимации (`*.kt`, везде)
```kotlin
// BEFORE — diff по позиции, анимация appearIn переигрывается, чат теряет позиции:
itemsIndexed(messages) { i, msg -> MessageBubble(msg) }

// AFTER — стабильный ключ по id:
items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
```

### 7.4 🟡 Дедуп маппинга `RideDto` (`ApiClient.kt:181` и `:216`)
```kotlin
// AFTER — один extension, оба метода зовут его:
private fun JSONObject.toRideDto(distanceKm: Double? = null) = RideDto(
    id = optInt("id"),
    fromCity = optString("from_city"),
    toCity = optString("to_city"),
    // …все 25 полей в ОДНОМ месте…
    distanceKm = distanceKm,
)
// getRides:        arr.map { it.toRideDto() }
// getNearbyRides:  arr.map { it.toRideDto(distanceKm = it.optDouble("distance_km")) }
```

### 7.5 🔴 SOS/бронь — не терять ошибку (`ApiClient.kt:37`)
```kotlin
// BEFORE — молча проваливается на плохой сети:
fun fireSos(...) = bg.launch { sos(...) }   // Result отброшен

// AFTER — возвращаем состояние в UI:
suspend fun sos(...): Result<Unit> { ... }  // экран показывает loading→ok/«Не отправилось, повторить?»
```

---

## 8. Что НЕ трогали (намеренно)
- Поведение приложения — без изменений (это аудит).
- `mobile/` (старый Flutter).
- `MainActivity.kt` и UI-файлы — их могла параллельно править соседняя сессия.
- Карта (Яндекс MapKit) работает, ЖЦ через `DisposableEffect` корректен — утечки нет.
- Централизованные мапперы/форматтеры в `MainActivity.kt` (`toUiRide`, `fmtKm`, `formatDepart`) — сделаны **правильно**, в одном месте.

---

## Журнал исправлений

### ✅ Фаза 0 — Безопасность (2026-06-27, выполнено и проверено)
> Поведение для пользователя не менялось. Worktree `pedantic-kare-ef1707`, без конфликта с параллельной сессией (backend + слой `data/` — не UI-файлы).

**Backend (`backend/`):**
- WS-IDOR закрыт: `main.py` `websocket_endpoint` проверяет участника брони (`_booking_and_ride_for_user`-логика), токен принимается первым сообщением, не в URL (query-фоллбэк для совместимости).
- Перебор OTP: `OtpCode.attempts`+`created_at` (модель + Alembic-миграция `d1e2f3a4b5c6`), `/auth/verify` лимит 5 → 429; `/auth/request-code` throttle ≤3/мин → 429.
- PII в логах: `_mask_phone()` (`+7****1234`) во всех `print`; код OTP не пишется в проде.
- Овербукинг: `book()` берёт поездку `with_for_update()`.
- CORS сужен (`GET/POST/OPTIONS`, `Authorization/Content-Type`); длина текста ограничена (`Field(max_length=...)`); мёртвые `sign_telegram/verify_telegram` удалены.
- Геокодер-прокси: новый `GET /geocode` (ключ Яндекса на сервере, `config.yandex_geocoder_key`).
- Проверка: `smoke.py` → `SMOKE OK`; новый `smoke_security.py` → `SECURITY SMOKE OK` (чужая бронь 403, чужой WS отключён, перебор→429, throttle→429).
- Деплой: миграция через Alembic (`alembic upgrade head` — голова `d1e2f3a4b5c6`, как у FK-миграции соседа); `.env.example` дополнен `YANDEX_GEOCODER_KEY`.

**Client (`android/.../data/`):**
- `ChatSocket.kt` — токен первым WS-сообщением `{type:auth,token}`, не в URL.
- `GeocoderClient.kt` — ходит на наш `/geocode` (ключ убран с устройства).
- `ApiClient.kt` — JWT в `EncryptedSharedPreferences` (+ миграция старого plaintext-токена, мягкий фоллбэк), `apiBase()`.
- Зависимость `androidx.security:security-crypto:1.1.0-alpha06`.
- Проверка: `gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (APK собран).

**⛔ Нужно от Александра, чтобы заработало на проде:**
1. Задеплоить бэкенд: scp `app/` (вкл. `config.py`) + `alembic/` → на сервере `alembic upgrade head` (применит миграцию OTP-колонок, как делали для FK).
2. Вписать `YANDEX_GEOCODER_KEY` в серверный `.env` (иначе подсказки адресов будут пустыми, но приложение работает).
3. Собрать/выложить новый клиент (WS-токен и геокодер согласованы с новым бэком — деплоить вместе).

**Отложено (нужны UI-файлы — зона параллельной сессии):**
- SOS/бронь `fireXxx` → показывать loading/ошибку/«Повторить» (правки экранов).

### ✅ Безопасные чистки (2026-06-27, зоны `data/` + backend, не UI)
- **Мёртвый код удалён:** `data/Models.kt`, `data/Repository.kt`, `data/MockRepository.kt` (старый repo-слой, 0 ссылок — проверено грепом). `vkCallback`/`whatsappCallback` из `ApiClient.kt` (VK/WhatsApp убраны из входа).
- **Дедуп маппинга `RideDto`:** один `JSONObject.toRideDto()` вместо копипасты в `getRides`/`getNearbyRides` (~25 полей в одном месте; `distance_km` через `isNull`).
- **Дедуп backend:** хелпер `_user_bookings(session, user)` вместо повтора «брони как пассажир+водитель» в `conversations`/`notifications`.
- **Итог:** −176 строк нетто (393 удалено / 217 добавлено).
- **Проверка:** `SMOKE OK` + `SECURITY SMOKE OK` + ручной чек `/conversations`+`/notifications` (DEDUP CHECK OK) + `assembleDebug` → **BUILD SUCCESSFUL**.

**Остаётся в Фазе 2 (в UI-файлах — ждёт освобождения зоны):** `key` в списки, дедуп карточек поездки (`RideCard`/`FullRideCard`/`NearbyRideCard`), `key`/stale-closure в чате, `appearIn`-индексы.

### 🔀 Сведение с параллельной сессией (2026-06-27)
Параллельная сессия закоммитила в `main` FCM-push + UI-фиксы + **свой** WS-IDOR. Моя ветка отребейзена на текущий `main` (`f243a32`), конфликты сведены:
- **WS-IDOR** — был дубль (обе сессии нашли аудитом). Оставлен ОДИН вариант: мой (токен первым сообщением, не в URL) + их интеграция push в чат. Их дубль-проверка убрана.
- **Овербукинг `FOR UPDATE`** — тоже дубль, код идентичен → один.
- **FCM (их) + безопасность (моя)** уживаются: `config.py` (firebase + geocoder ключи), `models.py` (DeviceToken + OtpCode.attempts), `main.py` (`_send_push` + `_user_bookings`), `ApiClient.kt` (push-register + шифрование/дедуп), `build.gradle.kts` (firebase + security-crypto).
- Проверено после слияния: `SMOKE OK` · `SECURITY SMOKE OK` · `assembleDebug` BUILD SUCCESSFUL.
- Ветка `claude/pedantic-kare-ef1707` = `main` + 1 коммит, готова к fast-forward merge.

---

## Итог одной строкой
Юлдаш — рабочий прототип на «прототипных» паттернах. **Перед бетой обязательна Фаза 0 (безопасность)** — 9 пунктов, поведение для юзера не меняется. Дальше Фаза 1 (ViewModel + Navigation + роутеры на бэке) разблокирует рост и командную работу. Всё остальное — по ходу, без спешки.
