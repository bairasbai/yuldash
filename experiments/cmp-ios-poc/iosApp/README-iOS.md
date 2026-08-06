# iOS-хост (Swift) — как собрать

> ⚠️ **Нужен Mac + Xcode.** iOS-приложение физически не собрать на Windows/Linux — это требование Apple,
> а не Compose Multiplatform. Варианты: (1) Mac; (2) облачный Mac (напр. macOS-раннер в GitHub Actions).

Здесь лежат только **исходники Swift** (`iOSApp.swift`, `ContentView.swift`). Сам Xcode-проект
(`iosApp.xcodeproj`) **не** кладём в git руками — его надёжнее сгенерировать штатным инструментом,
иначе легко получить «битый» проект.

## Порядок (на Mac)

1. Установи **Android Studio** + плагин **Kotlin Multiplatform**, либо возьми официальный шаблон
   на **https://kmp.jetbrains.com** (Kotlin Multiplatform Wizard).
2. Сгенерируй проект-шаблон KMP — в нём уже будет готовый `iosApp/iosApp.xcodeproj`, правильно
   слинкованный с модулем `:shared` (framework search paths + фаза сборки
   `./gradlew :shared:embedAndSignAppleFrameworkForXcode`).
3. Замени в сгенерированном `iosApp/` два файла на наши `iOSApp.swift` и `ContentView.swift`,
   а модуль `shared/` — на наш (он уже настроен под iOS-таргеты).
4. Открой `iosApp.xcodeproj` в Xcode → выбери симулятор iPhone → **Run**.

Появится тот же экран, что и на Android: карточка «Юлдаш», приветствие и кнопка смены языка RU ⇄ BA.

## Почему так, а не готовый .xcodeproj

Файл `project.pbxproj` — это низкоуровневый конфиг Xcode. Написанный вручную и не проверенный
сборкой, он почти наверняка окажется несовместим с версией Xcode на конкретной машине. Штатный
генератор делает его правильно под твою систему. Наши два `.swift` — это ровно то содержимое,
которое в этот проект нужно положить.
