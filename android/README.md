# Юлдаш Android

Нативный Android-проект на Kotlin и Jetpack Compose.

## Используемые версии

Источник правды — `build.gradle.kts` (корень `android/`), `app/build.gradle.kts` и `gradle/wrapper/gradle-wrapper.properties`.

- Gradle (wrapper): `8.13`.
- Android Gradle Plugin: `8.13.2`.
- Kotlin Gradle plugin и Compose compiler plugin: `2.3.0`.
- Compose BOM: `2026.06.00`.
- Activity Compose: `1.13.0`.
- Yandex MapKit: `com.yandex.android:maps.mobile:4.39.0-full`.
- Firebase BOM `33.7.0` (Messaging, Analytics); `google-services` плагин применяется, только если есть `app/google-services.json`.
- `applicationId`: `com.yuldash.app`; `versionName` / `versionCode`: `1.0.0` / `2`.
- `compileSdk` / `targetSdk`: `36`.
- `minSdk`: `26`.
- `jvmTarget`: `17`.

## Запуск

1. Установить Android Studio (в ней есть JDK — `jbr`; нужен JDK 17+, CI собирает на Temurin 21).
2. Открыть папку `android` в Android Studio.
3. Дождаться Gradle Sync.
4. Запустить `app` на эмуляторе или Android-телефоне.

Если используется терминал (PowerShell):

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug --no-daemon
```

Готовые двойные клики: `build-debug.bat` (debug-сборка), `install-run.bat` (установить свежий debug-APK через `adb` и запустить), `build-release.bat` (релиз; нужен `keystore.properties`, см. ниже).

## Настройки сборки (`android/local.properties`, не в git)

Читаются в `app/build.gradle.kts`; пусто = функция выключена, сборка не падает.

| Ключ | Что делает |
|---|---|
| `YANDEX_MAPKIT_KEY` | ключ Яндекс MapKit |
| `YULDASH_DEBUG_API_BASE_URL` | адрес API для debug (по умолчанию `http://10.0.2.2:8000`; можно переменной окружения с тем же именем) |
| `YULDASH_RELEASE_API_BASE_URL` | адрес API для release (по умолчанию `https://yulbash.ru`) |
| `YULDASH_WEB_BASE_URL` | сайт для ссылок «поделиться поездкой» (по умолчанию `https://yulbash.ru`) |
| `YULDASH_SUPPORT_PHONE` | телефон поддержки (пусто — кнопка звонка скрыта) |
| `YULDASH_TELEGRAM_BOT`, `YULDASH_VK_APP_ID` | вход через Telegram / VK (пусто — «скоро») |
| `YULDASH_SMS_LOGIN` | `true` включает SMS-вход (по умолчанию заморожен) |
| `YULDASH_SENTRY_DSN` | сбор ошибок Sentry |

Подпись релиза — `android/keystore.properties` (в `.gitignore`); без него `assembleRelease` / `bundleRelease` падают сразу (CI собирает релиз с флагом `-Pyuldash.unsignedRelease`).

## Тесты

- JVM (`app/src/test`): `./gradlew :app:testDebugUnitTest`; покрытие — `:app:jacocoCoverageVerification` (его гоняет CI, job `android-unit-tests`).
- Инструментальные (`app/src/androidTest`) — на эмуляторе.
- Быстрые проверки без SDK — [../tools/README.md](../tools/README.md).
