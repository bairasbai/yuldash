# Юлдаш Android

Нативный Android-проект на Kotlin и Jetpack Compose.

## Локальный статус

На этой машине не найдены `java`, `gradle`, `ANDROID_HOME` и Android SDK, поэтому сборка APK здесь не проверена.

Проверенные локальные факты:

- `java -version` не найден;
- `gradle --version` не найден;
- переменные `ANDROID_HOME` и `ANDROID_SDK_ROOT` пустые.

## Используемые версии

- Android Gradle Plugin: `8.13.2`.
- Kotlin Gradle plugin: `2.3.0`.
- Compose BOM: `2026.06.00`.
- Activity Compose: `1.13.0`.
- `compileSdk` / `targetSdk`: `36`.
- `minSdk`: `26`.

Основания:

- Android Developers указывает Compose BOM `2026.06.00` как актуальный для Compose dependencies.
- Android Developers указывает, что Android Gradle Plugin `8.13.2` поддерживает Kotlin 2.3 и требует JDK 17.
- AndroidX Activity release notes указывает `activity-compose:1.13.0`.

## Запуск

1. Установить Android Studio.
2. Установить JDK 17.
3. Открыть папку `android` в Android Studio.
4. Дождаться Gradle Sync.
5. Запустить `app` на эмуляторе или Android-телефоне.

Если используется терминал:

```powershell
cd android
gradle :app:assembleDebug
```

Команда сработает только после установки Java/Gradle/Android SDK.
