# 🧪 Тест-промт Юлдаша (адаптированный под наш проект)

> Готовый промт для агента: «прогони полное тестирование Юлдаша до production-ready».
> Скопируй блок ниже в новую сессию Claude Code. Адаптирован из общего Android-тест-промта
> под РЕАЛЬНУЮ инфраструктуру проекта: Kotlin/Compose UI, эмулятор `Pixel_5` (API 37),
> бэкенд FastAPI на `yulbash.ru`, тестирование через `adb` + `uiautomator` (Espresso @Ignore на API 37).

---

## Промт (копипаст)

```
Ты — senior QA-инженер Android (Kotlin/Compose) + backend (FastAPI). Прогони ПОЛНОЕ тестирование
приложения Юлдаш до production-ready. Работай на полном автомате: мелкие баги чинишь сам,
показываешь diff для рискованного, не помечаешь «готово» без зелёной сборки.

ОКРУЖЕНИЕ (не спрашивай — оно такое):
- Рабочий код: android/ (Kotlin + Jetpack Compose). НЕ трогать mobile/ (старый Flutter).
- Сборка: cd android; $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon
- ADB: C:\Users\Bayra\AppData\Local\Android\Sdk\platform-tools\adb.exe
- Эмулятор: AVD `Pixel_5` (API 37, экран 1080x2340). Запуск: emulator -avd Pixel_5 (если занят — фича «multiinstance», добавь -read-only). Ждать boot_completed=1.
- local.properties НЕТ в worktree (в .gitignore) — скопируй из C:\Users\Bayra\Yuldash\android\local.properties (там ключи MapKit/геокодера, БЕЗ них карта не соберётся).
- Бэкенд LIVE: https://yulbash.ru (health: {"status":"ok","db":"ok"}). SSH: ssh root@85.239.52.55 (ключ на ноуте). pytest: cd /opt/yuldash && ./.venv/bin/python -m pytest tests/ -q.
- Токен для API-тестов без телефона: ssh ... "cd /opt/yuldash && ./.venv/bin/python -c 'from app.security import make_token; print(make_token(<user_id>))'". Юзеры: 1-4 водители (Ильдар/Айгуль/Рустам/Гүзәл), 27 водитель codex, 28 пассажир Codex.

ШАГИ:
1. Прочитай docs/00-INDEX.md + docs/architecture.md (карта кода по строкам, НЕ читай MainActivity целиком).
2. Скопируй local.properties в worktree. Собери :app:assembleDebug — зелёная? Установи -r на эмулятор.
3. Unit: gradlew :app:testDebugUnitTest (CoreLogicTest). Instrumentation: gradlew :app:connectedDebugAndroidTest
   (YuldashViewModelInstrumentedTest пройдёт; BilingualComposeTest под @Ignore — Espresso 3.6.1 несовместим с API 37).
4. РУЧНОЕ E2E через adb shell input tap + uiautomator dump (координаты хрупкие — всегда сверяй по dump, парси bounds
   через python с PYTHONIOENCODING=utf-8, иначе кириллица ломается в cp1251-консоли):
   - Карта: 5 вкладок, тёмная тема, SOS-экран (112/102/101/103 + координаты).
   - 6 фильтров «Ближайшие» (Только женщины/Кресло/Животное/Багаж/Кондиционер/Некурящий) — каждый меняет счётчик «N рядом».
   - Карточка поездки → детали → «Поехать» → активная поездка (код посадки, статус, чат, «поделиться», отмена).
   - Заявка: форма (7 условий-тумблеров + обязательная дата/время), «Проверка заявки» (серый чек у незаполненного), создание.
   - Чат-инбокс (3 вкладки: Активные/Заявки/Система). Профиль (реферал, бейдж, язык RU↔BA — весь UI перерисовывается).
   - Состояния: оффлайн (svc wifi/data disable + airplane_mode_on 1) → карта на моках, «Поездки» = «Не удалось загрузить · Повторить». Retry после возврата сети.
5. LIVE-трекинг БЕЗ 2 телефонов: symлируй водителя WS-стримом с сервера
   (ws://127.0.0.1:8000/ws/trip/{booking_id}/location, первым {"type":"auth","token":...}, дальше {"type":"loc",...}).
   Подтверди бронь (POST /bookings/{id}/confirm водителем), выстави departed (POST /bookings/{id}/driver-status {"status":"departed"}) →
   пассажир видит баннер «Водитель выехал к вам». В UI debug-кнопка «▶ Симуляция» внизу-слева карты (только BuildConfig.DEBUG).
6. Бэкенд: pytest на сервере, /health, журнал ошибок (systemctl status yuldash-api / journalctl). Симулируй поездки/заявки/чат через API.
7. Баги чини сам (корневая причина, не костыль). Двуязычие: любая надпись через appText(ru, ba). Пересобери зелёным. Перепроверь фикс на эмуляторе.
8. Отчёт: test_artifacts/TEST_REPORT.md (дата, окружение, статистика тестов, упавшие с логами, скриншоты, вердикт production-ready).
   Обнови docs/tasks.md + lessons.md. Скриншоты — test_artifacts/screenshots/.

ПРАВИЛА:
- Один агент — один файл (MainActivity/экраны). Эмулятор/сборка — по очереди, не параллель.
- Не помечай «готово» без BUILD SUCCESSFUL. Не коммить без явной просьбы Александра.
- Сначала вывод, потом детали. По-русски, просто. Диктофонные баги — с фрагментом лога.
```

---

## Заметки к промту (почему именно так)

- **Espresso @Ignore на API 37** — эмулятор `Pixel_5` = Android 17/API 37 (будущий). `InputManager.getInstance` удалён → `createComposeRule` падает `NoSuchMethodException`. Поэтому Compose-UI-тесты скипаются, E2E делаем через `adb`+`uiautomator`. Снять `@Ignore` на эмуляторе API ≤36.
- **Кириллица в консоли** — Windows-консоль cp1251, `uiautomator dump` содержит `→`/кириллицу. Парсить bounds через `python` с `export PYTHONIOENCODING=utf-8`, иначе `UnicodeEncodeError`.
- **local.properties** — в `.gitignore`, в worktree его нет. Без ключей MapKit сборка карты падает. Копировать из основного репо.
- **API-токены для тестов** — `make_token(user_id)` минует SMS/Telegram-вход. Быстрый способ гонять E2E-потоки поездок без реального логина.
- **LIVE-трекинг код-тестом** — 2 телефона не нужны: WS-стрим водителя запускается скриптом на сервере, пассажир смотрится на эмуляторе. Так проверяется реле `/ws/trip/{id}/location` + баннер статуса + плавная нав-стрелка.
