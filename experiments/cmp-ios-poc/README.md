# Yuldash · Compose Multiplatform — проба (iOS + Android из одного кода)

Мини-проект-доказательство: **один и тот же экран на Kotlin/Compose** рисуется и на **Android**, и на
**iPhone**. Написан в фирменном стиле Юлдаша — цвета `Canon*`, двуязычие `appText(ru, ba)`, анимация в коде.
Это «пробный экран» под задачу «писать Android и iOS одновременно».

Он **изолирован**: лежит в `experiments/`, **ничего в рабочем `android/` не трогает** — сборке Android ничто не угрожает.

---

## ⚠️ Честный статус сборки

**Этот проект НЕ собран в облаке.** Среда, где меня запустили, — Linux **без Android SDK** и **без macOS**,
поэтому скомпилировать здесь нечего: Android-часть требует Android SDK, iOS-часть — Mac + Xcode.
Код написан аккуратно и по стандартным версиям, но **проверить его сборкой должен ты на своей машине.**
Я специально **не** помечаю это «готово/собралось» — это было бы неправдой.

Что проверять:
- **Android** — собирается на Windows/Mac/Linux с Android SDK.
- **iOS** — собирается **только на Mac** (требование Apple).

---

## Что внутри

```
cmp-ios-poc/
├── shared/                     ← ОБЩИЙ код (один на обе платформы)
│   └── src/
│       ├── commonMain/         ← App.kt (экран), Canon.kt (цвета), AppText.kt (RU/BA) — тут вся суть
│       ├── androidMain/        ← манифест библиотеки
│       └── iosMain/            ← MainViewController.kt (мост в SwiftUI)
├── androidApp/                 ← тонкий Android-хост: setContent { App() }
└── iosApp/                     ← Swift-хост: ContentView → MainViewController() (см. README-iOS.md)
```

Главный файл — `shared/src/commonMain/kotlin/com/yuldash/shared/App.kt`. Именно он рисует экран на
обеих платформах. `Canon.kt` и `AppText.kt` — портированы 1:1 из рабочего `android/`, чтобы показать:
существующий стиль и двуязычие переезжают в общий код **без изменений**.

## Как собрать Android-часть

С установленным Android SDK (напр. через Android Studio):

```bash
cd experiments/cmp-ios-poc
./gradlew :androidApp:assembleDebug        # Windows: gradlew.bat :androidApp:assembleDebug
```

APK окажется в `androidApp/build/outputs/apk/debug/`. Или открой папку в Android Studio и нажми ▶.

## Как собрать iOS-часть

Нужен **Mac + Xcode**. Пошагово — в [`iosApp/README-iOS.md`](iosApp/README-iOS.md).

## Версии (зафиксированы под известные-совместимые)

| Что | Версия | Почему |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | как в рабочем `android/` |
| Kotlin | 2.3.0 | как в рабочем `android/` |
| Compose Multiplatform | 1.11.0 | стабильный iOS-релиз (май 2026) |
| compileSdk / minSdk | 36 / 26 | как в рабочем `android/` |

## Что тут СПЕЦИАЛЬНО не показано

Карта (Яндекс), сеть, пуши, хранилище — это самая тяжёлая часть настоящего переезда, у неё отдельный
план и блокеры. См. **[`docs/ios-compose-multiplatform.md`](../../docs/ios-compose-multiplatform.md)**.
