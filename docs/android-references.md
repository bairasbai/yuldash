# 📚 Эталонные Android-репозитории — что изучаем и как применяем в Юлдаше

> Изучено 2026-07-03 (по README/структуре, репо не клонировали — слишком тяжёлые).
> Цель: держать планку мирового топа. Каждый репо → конкретный урок → куда лёг в Юлдаше.
> Под эти уроки настроены агенты в `.claude/agents/` (см. `.claude/agents/README.md`).

---

## Таблица: репо → чему учит → агент-владелец

| Репо | Стек / чем известен | Урок для Юлдаша | Агент |
| --- | --- | --- | --- |
| [android/nowinandroid](https://github.com/android/nowinandroid) | Kotlin, Compose, **Material 3**, Hilt, DataStore, мультимодуль, offline-first, тёмная тема, Roborazzi | Дизайн-система как отдельный слой; адаптивные layouts; тёмная тема через токены; тест-дубли через шов репозитория без mock-либ | compose-ui, architect, qa |
| [android/architecture-samples](https://github.com/android/architecture-samples) | **MVVM + Repository + UDF**, Compose, Flow/coroutines, Room, Hilt, flavors mock/prod | Один ViewModel на экран; реактивный UI на Flow; репозиторий разделяет источники; flavors mock/prod | architect, qa |
| [skydoves/Pokedex](https://github.com/skydoves/Pokedex) | Clean Arch + MVVM, Hilt, Retrofit, **Room-кеш**, Coroutines/Flow, Turbine | **Single source of truth:** сначала кеш, потом сеть; репозиторий отдаёт Flow; тесты потоков Turbine | architect, qa |
| [chrisbanes/tivi](https://github.com/chrisbanes/tivi) | **Kotlin Multiplatform**, Compose Multiplatform, продвинутый Compose, Ktlint/Spotless | Продвинутый Compose и стабильность; чистая модуляризация; (KMP — задел на будущее, iOS не сейчас) | compose-ui |
| [android/uamp](https://github.com/android/uamp) | MediaSession, ExoPlayer/Media3, **foreground service** для фона | **Foreground service + уведомление** для фонового трекинга поездки (наш `TripLocationService`) | maps-geo |
| [signalapp/Signal-Android](https://github.com/signalapp/Signal-Android) | E2E-шифрование, приватность по умолчанию, минимум данных | Приватность = фича, не опция; минимум личных данных; не логировать телефон/гео; гео скрыто до подтверждения | security-privacy |
| [wasabeef/awesome-android-ui](https://github.com/wasabeef/awesome-android-ui) | Каталог UI-библиотек и анимаций | Идеи: Lottie (интро/пустые), Shimmer (скелетоны), Balloon (подсказки «зачем разрешение»), Konfetti (успех). Тянуть либу — только если без неё никак (§10) | compose-ui |

---

## Разбор по пунктам

### 1. nowinandroid — эталон современного production-Android
- **Дизайн-система как слой.** У них `core:designsystem` + catalog-модуль. У нас аналог — `CanonTokens.kt` + `UiKit.kt`: единые токены `Canon*` и формы. Урок: любой новый компонент строим переиспользуемо, а не разово в экране.
- **Тёмная тема через токены** (Material 3, dynamic color). У нас уже так: `Canon*` адаптивны (свет/тьма), WCAG заложен в комментарии токенов. Правило: **никогда хардкод `Color(0xFF…)` в экране.**
- **Тесты без mock-библиотек:** production-реализацию меняют на тест-дубль через Hilt. У нас DI нет, но есть **шов `Mocks` ↔ `ApiClient`** — тот же принцип.
- **Адаптивные layouts** под разные экраны + baseline profiles для быстрого старта.

### 2. architecture-samples — канон MVVM
- **MVVM + Repository + однонаправленный поток (UDF):** состояние вниз, события вверх. У нас: `YuldashViewModel` держит состояние (`screen/language/startHomeTab`), `@Composable` только рисует.
- **Flow + coroutines** для асинхронного UI. Готовим data-слой на Flow под реальный сервер.
- **Flavors mock/prod** — идея на будущее: сейчас фолбэк на `Mocks.kt` при недоступности `yulbash.ru`.

### 3. Pokedex — компактный образец offline-first
- **Single source of truth:** UI читает из репозитория; репозиторий сначала отдаёт кеш (Room), потом освежает из сети. Наш вектор при переходе моков → API.
- **Turbine** для тестов `Flow` — берём в QA.

### 4. tivi — высокая планка Compose
- Продвинутый Compose, управление стабильностью (меньше лишних рекомпозиций → 60fps), чистая модуляризация, Ktlint/Spotless.
- **KMP (iOS/desktop) — НЕ сейчас.** Юлдаш только Android. Держим как ориентир на будущее, не тянем сложность раньше времени.

### 5. uamp — фоновые сервисы
- Фоновая работа (у них аудио, у нас **гео-трекинг поездки**) = **foreground service с честным уведомлением**. Уже реализовано в `TripLocationService` (GPS ~раз в 7с, «поездка активна»). Урок: отписка от локаций на финише, экономия батареи.

### 6. Signal — приватность как продукт
- **Приватность по умолчанию, минимум данных.** Для Юлдаша («между своими») это прямо продукт: доверие = фича.
- Практика: секреты вне git; телефон/точные координаты — только по нужде и после согласия; не логируем чувствительное; **гео скрыто до подтверждения поездки** (уже так).

### 7. awesome-android-ui — банк идей UI
- Не код для копирования — каталог. Полезное для нас: **Shimmer** (скелетоны загрузки вместо голого спиннера), **Lottie** (брендовое интро, пустые состояния), **Balloon** (подсказка «зачем нужно разрешение на гео»), **Konfetti** (микро-радость на успехе поездки/донате).
- Дисциплина: новая зависимость — только если без неё никак и совместима (CLAUDE.md §10).

---

## Что сознательно НЕ берём сейчас
- **KMP / iOS** (tivi) — только Android, не раздуваем.
- **Hilt/DI** — пока шва `Mocks`↔`ApiClient` хватает; вводить DI — отдельное решение (в `decisions.md`), не молча.
- **Media3/ExoPlayer** (uamp) — берём только паттерн foreground service, не сам плеер.
- Массовую тягу UI-библиотек — точечно и по нужде.
