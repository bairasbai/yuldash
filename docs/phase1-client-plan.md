# 🧱 Фаза 1 · Клиентский фундамент — turnkey-план (для машины с Android SDK)

> **Зачем этот файл:** самый большой рычаг из [architecture-review-2026-07-20.md](architecture-review-2026-07-20.md) — клиентский фундамент (резать экраны, слой Repository+ViewModel). Но его **нельзя довести до «готово» в облачном контейнере** (нет Android SDK/`local.properties` → не собрать, а правило проекта — не помечать готовым без зелёной сборки). Здесь — пошаговый план, чтобы выполнить его там, где сборка есть (машина Александра / сессия Claude Code с настроенным SDK). Каждый шаг: **не меняет поведение → `gradlew :app:assembleDebug` зелёный → отдельный коммит**.

## Предусловия (перед стартом)
1. Android SDK установлен; `android/local.properties` есть (`sdk.dir` + `YANDEX_MAPKIT_KEY`) — в worktree скопировать из основного репо (см. `lessons.md`).
2. Один UI-агент за раз на `MainActivity.kt`/общий слой (§12 CLAUDE.md).
3. Базовая сборка зелёная ДО начала: `cd android && ./gradlew :app:assembleDebug`.

---

## Шаг 1 — Резать экраны-«свалки» по файлам (C3, P1) — РАЗБЛОКИРУЕТ ВСЁ

**Проблема:** `SecondaryScreens.kt` (15 экранов), `RidesRequestsChatScreens.kt` (5 потоков), `ProfileScreen.kt` (5) — несколько несвязанных экранов в файле → два агента не могут править параллельно (last-write-wins).

**Как (механически, по ОДНОМУ экрану за раз):**
1. Создать пакеты по фиче: `ui/settings/`, `ui/admin/`, `ui/rides/`, `ui/chat/`, `ui/profile/` (тот же пакет `com.yuldash.app.*`, символы `internal`).
2. Вырезать один `@Composable`-экран (со всеми его приватными хелперами) в свой файл `ui/<feature>/<Screen>.kt`. Импорты — по месту. **Ничего не переименовывать, логику не трогать.**
3. `gradlew :app:assembleDebug` → зелёный → коммит `refactor(ui): вынести XScreen в свой файл`.
4. Повторять. Порядок (от худшего файла): `SecondaryScreens.kt` (15 экранов) → `RidesRequestsChatScreens.kt` (5) → `ProfileScreen.kt` (5) → `AccessibilityScreens.kt`.
5. Обновить `docs/architecture.md` (карта файлов) после каждой пачки.

**Критерий готовности:** один экран = один файл; сборка зелёная после каждого; поведение 1:1.
**Осторожно:** не разрывай пару `@Composable`↔`fun` объявлением типа между ними (`lessons.md`).

---

## Шаг 2 — Пилот Repository + ViewModel на КАРТЕ (C1+C2, P1)

Доказать шаблон на одной фиче, снять reload-счётчики/ручной поллинг с `MapScreen`.

**Проблема:** 164 прямых вызова `ApiClient.*` из UI; серверные данные в `remember{}` экранов (перезагрузка на поворот); нет single source of truth.

### 2.1 Repository-интерфейс + реализация
```kotlin
// data/RidesRepository.kt
internal interface RidesRepository {
    fun nearby(from: String?, to: String?, lat: Double?, lng: Double?, radiusKm: Double?): Flow<Result<List<Ride>>>
}
// data/RidesRepositoryImpl.kt — обёртка над ApiClient (транспорт не трогаем), кеш + маппинг DTO→Ride в ОДНОМ месте
internal class RidesRepositoryImpl(private val api: ApiClient = ApiClient) : RidesRepository { ... }
```
- Маппинг `RideDto.toUi()` собрать в `data/` (сейчас размазан по 23 сайтам) и звать только отсюда.
- `Mocks` → `FakeRidesRepository` (тот же интерфейс) для превью/тестов.

### 2.2 ViewModel c UiState
```kotlin
// ui/map/MapViewModel.kt
internal sealed interface MapUiState { object Loading; data class Content(val rides: List<Ride>); data class Error(val msg: String) }
internal class MapViewModel(private val repo: RidesRepository) : ViewModel() {
    val state: StateFlow<MapUiState> = ...   // viewModelScope, переживает поворот; ключи в SavedStateHandle
}
```

### 2.3 MapScreen только рисует
- `val ui by mapVm.state.collectAsStateWithLifecycle()` вместо `remember{mutableStateOf}` + `LaunchedEffect(reload)`.
- Убрать `nearbyReload`/ручной `while(true){delay}` — репозиторий отдаёт «живой» `Flow` (поллинг/сокет внутри, один на приложение).
- Состояния loading/empty/error — через готовый `AppStateContainer` (`UiKit.kt`).

**Критерий готовности:** карта работает как раньше; поворот НЕ перезагружает сеть; `MapScreen` не зовёт `ApiClient` напрямую; сборка зелёная; (по возможности) smoke на эмуляторе.

---

## Шаг 3 — Размножить шаблон + простой DI
- Повторить 2.1–2.3 по фичам: booking/chat, profile, ads (каждая — свой `*Repository` + `*ViewModel`).
- Простой ручной DI (`AppContainer` — держит репозитории), без Hilt на старте; подмена `Fake*Repository` в тестах.
- Разнести `ApiClient` (1598 строк): `HttpEngine` (транспорт) / `TokenStore` (auth) / `*Api` по фичам / `dto/`. Убрать развилку `fireXxx` — оставить `suspend` + `Result` (ошибка в `UiState`, не теряется).

---

## Шаг 4 — Быстрые победы (можно отдать отдельному агенту, параллельно после Шага 1)
- **UiKit внедрить:** `Button`→`AppButton` (100+ сырых), ручные loading/error→`AppStateContainer` (сейчас 0 использований). Линт/ревью-чеклист против `Button(` в экранах.
- **Ключи списков:** `items(list, key = { it.id })` в 8+ местах `SecondaryScreens.kt` (сейчас `items(list.size)`).
- Тяжёлые производные (`bookings.filter{}`) → `remember(key)`/`derivedStateOf`.

---

## Шаг 5 (Фаза 2) — Navigation-Compose
После Шагов 1–2: заменить `enum Screen` + `when` (44 ветки) на Navigation-Compose с типизированными маршрутами (`booking/{id}`) → back-stack и restore из коробки + **deep links под уже готовый FCM** (пуш «водитель выехал» открывает нужную бронь). Мигрировать пачками (сначала листья), `Screen`-enum оставить мостом. Return-флаги (`createRideReturnScreen`…) удаляются сами.

---

## Проверка каждого шага (гейт «готово»)
1. `cd android && ./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**.
2. По возможности — эмулятор: экран открывается, состояния loading/empty/error видны, поворот не теряет данные/не перезагружает сеть.
3. Двуязычие не сломано (`appText`), тёмная тема (`Canon*`), тач-цели ≥48dp.
4. Отдельный коммит на шаг; `docs/architecture.md` обновлён.

> Оценка объёма (ревью): Шаг 1 — 2-3 дня; Шаг 2 (пилот) — 3-4 дня; Шаг 3 — по фиче; Шаг 4 — 1-2 дня (параллелится); Шаг 5 — 3-5 дней.

---

## 🐞 Известные Android-баги из код-аудита 2026-07-20 (чинить на сборочной машине)

Крашей нет, но при рефакторинге/перед мержем клиентских веток закрыть (из [code-audit-2026-07-20.md](code-audit-2026-07-20.md)):
- **P2 — `MapView` пересоздаётся на каждой рекомпозиции.** `BookingActiveTripScreen.kt:541` — `remember(fromPoint, toPoint)` завязан на MapKit-`Point`, который НЕ переопределяет `equals()` → новый ключ каждую рекомпозицию → видимая карта маршрута гаснет + утечка `MapView`. Фикс: `remember(fromPoint.latitude, fromPoint.longitude, toPoint.latitude, toPoint.longitude)` (примитивы стабильны).
- **P3 — stale role/status при смене брони.** `BookingActiveTripScreen.kt:764,782` — `remember { mutableStateOf("") }` без ключа `bookingId` → до ответа `getTripState` (~12с) видна старая роль. Фикс: `remember(bookingId)`.
- **P3 — молчаливый сбой.** `MapScreen.kt:305` — `getNearbyRequests` только `.onSuccess`; нет сети → маркеры заявок не появляются без индикации. Фикс: `.onFailure`.
- **P3 — MapKit-локаль.** `BookingActiveTripScreen.kt:542` и `MapScreen.kt:1706` зовут `MapKitFactory.initialize` мимо `ensureMapKit` → карта не в ru-локали при определённом порядке экранов. Фикс: единая точка `ensureMapKit(context)`.
- **P3 — утечка `MediaPlayer`.** `BookingActiveTripScreen.kt:1400` — при битом `voiceUrl` созданный плеер не `release()`-ится. Фикс: создавать в локальную переменную, `release()` в catch.

Проверка каждого — `gradlew :app:assembleDebug` зелёный + smoke на эмуляторе (карта маршрута не гаснет, заявки-маркеры при офлайне показывают состояние).
