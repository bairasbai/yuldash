# Полный аудит кода — 2026-06-28

Этот отчёт отвечает на вопрос: «Ты прошёлся по всему коду?». Да: я прошёлся по рабочим деревьям `android/`, `backend/`, `web/`, `promo/`, `mobile/`, `docs/` и конфигам. Это не означает построчное ручное чтение каждого файла: проверка была комбинацией сборок, тестов, lint, `npm audit`, grep-сканов, чтения ключевых файлов и живого Android smoke на эмуляторе.

## Что проверено

| Зона | Метод проверки | Результат |
|---|---|---|
| Android | `gradlew.bat :app:assembleDebug --no-daemon` | `BUILD SUCCESSFUL` |
| Android UI | установка debug APK на `emulator-5554`, ручной smoke через ADB, скриншоты `android/screenshots/qa_*.png`, `logcat` | основные экраны открываются, `FATAL EXCEPTION` не найден |
| Android lint | `gradlew.bat :app:lintDebug --no-daemon` | не прошёл: 1 error, 63 warnings, 16 hints |
| Backend | `backend\.venv\Scripts\python.exe -m pytest tests -q` | `63 passed, 1 warning` |
| Web | `npm ci`, затем `npm run build` в `web/` | сборка Next прошла |
| Web security | `npm audit --json` в `web/` | 2 уязвимости: 1 moderate, 1 high |
| Promo | `npm ci`, `npm audit --json`, `npm run build` в `promo/` | audit чистый; build-скрипта нет |
| Mobile Flutter | чтение `mobile/lib` и `mobile/pubspec.yaml` | `flutter` в среде не установлен, `flutter analyze` подтвердить не могу |
| Секреты | `git status --ignored`, `git check-ignore`, grep по secret/key/token | реальные секретные файлы игнорируются; `.codex/config.toml` не игнорируется |

## Масштаб проверки

Подсчёт строк из локального скрипта:

| Дерево | Файлов | Строк |
|---|---:|---:|
| `android/app/src/main/java` | 27 | 15421 |
| `backend/app` | 25 | 4030 |
| `backend/tests` | 3 | 820 |
| `web` | 61 | 6886 |
| `mobile/lib` | 16 | 2836 |
| `docs` | 25 | 5013 |

## Найдено обязательно исправить

1. **Android lint сейчас красный.**  
   Источник: `android/app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`.  
   Блокирующая ошибка: `android/gradle.properties:2` — `org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr` должен быть записан как `C\:/Program Files/Android/Android Studio/jbr`. Debug-сборка проходит, но lint/CI будет падать.

2. **В `.codex/config.toml` лежит API-ключ Stitch, а папка `.codex/` не игнорируется git.**  
   Источник: `git status --short` показывает `?? .codex/`; чтение `.codex/config.toml` показало поле `X-Goog-Api-Key`. Значение ключа в отчёт не выношу. Риск: случайно добавить файл в git.

3. **`web` имеет dependency-risk по Next.js.**  
   Источник: `npm audit --json` в `web/`: `next` direct dependency, severity `high`, range `9.3.4-canary.0 - 16.3.0-canary.5`; `postcss` transitive, severity `moderate`. `npm audit` предлагает `next@16.2.9`, это major upgrade. Так как `web/next.config.mjs` использует `output: "export"`, часть server-side рисков может быть неактивна в статическом деплое, но сам audit красный.

4. **Android App Bundle может сломать встроенное переключение языка.**  
   Источник: lint warning `AppBundleLocaleChanges` на `MainActivity.kt:518`. В приложении есть динамический язык RU/BA, а в bundle-конфиге я не нашёл отключения language split. Для APK это не блокер, для AAB в Google Play надо проверить.

5. **Debug-сборка специально обходит вход.**  
   Источник: `YuldashApp.kt:267` — `BuildConfig.DEBUG -> Screen.Home`. Это удобно для QA, но любые проверки логина/токенов на debug UI не доказывают release-поведение. Release Telegram-login нужно проверять отдельно.

6. **Hardware Back с внутренних экранов ведёт сразу на Home.**  
   Источник: `YuldashApp.kt:407-408`. Это не краш, но UX-логика грубая: пользователь может ожидать возврат на предыдущий вложенный экран.

7. **В Android всё ещё есть прямые `Color(0x...)` вне токенов.**  
   Источник: `rg 'Color\(0x' android/app/src/main/java/com/yuldash/app --glob '!ui/theme/Theme.kt'` дал 72 строки, часть из них в `CanonTokens.kt`, часть в экранах: `ProfileScreen.kt`, `RidesRequestsChatScreens.kt`, `MapScreen.kt`, `SecondaryScreens.kt`, `YuldashApp.kt`. Это не всегда баг, но нарушает правило проекта «цвета через Canon*» и может ломать тёмную тему.

8. **Promo-проект не имеет build-скрипта.**  
   Источник: `promo/package.json` содержит `studio`, `render`, `render:gif`, но нет `build`; `npm run build` возвращает `Missing script: "build"`. Для Remotion это может быть нормой, но как web-build проверить нельзя.

9. **Flutter `mobile/` не подтверждён инструментами.**  
   Источник: команда `flutter` недоступна в среде. Я могу подтвердить только статическое чтение `mobile/lib`; `flutter analyze` и сборку подтвердить не могу. По правилам проекта `mobile/` старый и не должен быть рабочей зоной.

## Риски и наблюдения

- Секретные файлы есть локально: `android/app/google-services.json`, `android/keystore.properties`, `android/yuldash.jks`, `backend/firebase-service-account.json`, `backend/yuldash.db`, APK-артефакты. Источник: `git status --ignored --short`. Они игнорируются `.gitignore`; это нормально, но их копии также лежат в `.claude/worktrees/`, что повышает риск случайной утечки на диске.
- Backend сильно покрыт тестами по ключевым сценариям: auth, refresh/logout, rides, booking, chat, WebSocket access control, SOS, reports, blocks, driver verification, ads, reviews, boost, upload quota, YooKassa webhook. Источник: `backend/tests/test_api.py` и `backend/tests/test_flows.py`; прогон `63 passed`.
- Backend использует `print(...)` для логов в `middleware.py`, `services.py`, `routers/safety.py`, `routers/rides.py`. Источник: grep по `print(`. Чувствительные значения токенов я в логах не увидел, но OTP выводится в dev/mock ветках (`services.py`), что допустимо только вне prod.
- Backend production guard есть в `backend/app/config.py`: проверяет JWT secret, Telegram webhook secret, CORS, SQLite, media URL, платежные ключи по выбранному provider. Это снижает риск плохого prod-конфига, но не заменяет проверку реального `.env`.
- Web использует `dangerouslySetInnerHTML` только для JSON-LD (`web/components/StructuredData.tsx`) и inline Yandex Metrika script (`web/app/layout.tsx`). Я не могу подтвердить XSS-риск без отдельного security review, но прямого пользовательского HTML-ввода там не увидел.
- Android SOS открывает звонилку через `ACTION_DIAL`, а не прямой звонок. Источник: `SosVerifyScreens.kt`, `AccessibilityScreens.kt`, `YuldashApp.kt`. На эмуляторе это ограничено отсутствием реального телефона/SIM.
- FCM нельзя считать полностью проверенным этим аудитом: код и конфиги на месте, backend-тест проверяет `/push/register`, но доставку push на реальный телефон я сейчас не подтверждал.

## Что не могу подтвердить

- Реальную доставку FCM на физическое устройство.
- Release Telegram-login через прод-бота на реальном устройстве в этой сессии.
- Реальную оплату YooKassa/SBP с внешним подтверждением денег.
- Реальную публикацию в Google Play/RuStore.
- Flutter `mobile/` через `flutter analyze` или сборку.
- Юридическую корректность политики/оферты/152-ФЗ без юриста.

## Вывод

Костяк приложения подтверждён: Android debug собирается, основные экраны открываются на эмуляторе, backend-тесты зелёные, web собирается. Но перед пометкой «полностью готово к релизу» нужно закрыть минимум: Android lint error, `.codex/` с ключом, web dependency audit по Next.js, проверку release-login/FCM на физическом устройстве и решение по App Bundle language split.
