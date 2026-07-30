# Проверки Kotlin, когда нет Android SDK

В веб-сессиях Claude Code нет Android SDK, а `dl.google.com` закрыт сетевой политикой —
значит androidx и Compose недоступны и **полную сборку сделать нельзя**. Проверено, не угадано:
`repo.maven.apache.org` отвечает 200, `dl.google.com` — 000, `androidx/compose/ui` на Maven
Central даёт 404.

Но кое-что можно проверить по-настоящему.

## 1. `compile-data-layer.sh` — НАСТОЯЩАЯ компиляция слоя данных

```bash
bash tools/compile-data-layer.sh
```

`data/*.kt` от Compose не зависит. Берём `kotlinc` с GitHub, `android.jar` из Robolectric
(`org.robolectric:android-all` — он на Maven Central), корутины и okhttp оттуда же, плюс пять
крошечных заглушек (`tools/stubs/`) вместо `BuildConfig`, `androidx.security.crypto`,
`androidx.compose.runtime` (только функции состояния, без `@Composable`) и Firebase.

Получаем полную проверку типов **`ApiClient.kt`** — ~5000 строк, 350+ функций, 130+ DTO,
от которого зависит весь остальной код. Скрипт заодно прогоняет **синтаксическую проверку всех
экранов**: собрать их нечем, но разобрать компилятор может, и ошибки разбора он видит.

Первый запуск качает ~270 МБ, дальше всё лежит в рабочей папке.

## 2. `ktcheck.py` — быстрая проверка, файл за файлом

```bash
python3 tools/ktcheck.py android/app/src/main/java/com/yuldash/app/*.kt
```

Баланс `{}()[]` (с учётом строк и комментариев) · `Icons.Default.X` без импорта ·
**блочный комментарий, закрытый посреди слова** · запятые между элементами `enum` ·
смесь кириллицы и латиницы внутри слова (порча башкирских строк).

## 3. `navcheck.py` — полнота навигации

```bash
python3 tools/navcheck.py
```

Сверяет `enum class Screen` (MainActivity.kt) с ветками `when (screen)` (YuldashApp.kt).
Пропущенная ветка без `else` = «when expression must be exhaustive», а глазами её в списке
из 88 экранов не найти.

## Правило (docs/lessons.md)

Новая проверка засчитывается, только если она **краснеет на до-фиксной версии файла**
и молчит на исправленной. Каждый класс ошибок, проскочивший мимо, добавляется сюда.

**Ничто из этого не заменяет сборку.** Экраны на Compose здесь не компилируются вообще.
Гейт прежний: `gradlew :app:assembleDebug`.
