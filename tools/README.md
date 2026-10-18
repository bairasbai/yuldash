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
(`org.robolectric:android-all` — он на Maven Central), корутины и okhttp оттуда же, плюс шесть
крошечных заглушек (`tools/stubs/`) вместо `BuildConfig`, `androidx.security.crypto`,
`androidx.compose.runtime` (только функции состояния, без `@Composable`), Firebase и Sentry.

Получаем полную проверку типов **`ApiClient.kt`** (`data/`) — ~8000 строк, 490+ функций, 170+ DTO,
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
из ~96 экранов не найти.

⚠️ Скрипт читает **только** `YuldashApp.kt`, а часть веток теперь живёт в `AppNavAdmin.kt` / `AppNavOrders.kt` / `AppNavHome.kt`, поэтому на текущем коде он сообщает «нет своей ветки» для экранов из этих файлов (ложное срабатывание). Пока скрипт не обновлён, истина — сборка (`gradlew :app:assembleDebug`).

## 4. `contrast.py` — WCAG числами

```bash
python3 tools/contrast.py
```

Рядом с токенами в `CanonTokens.kt` стоят пометки «≥4.5:1» — это утверждения, и здесь они
проверяются расчётом (с учётом альфы: полупрозрачный цвет накладывается на фон). Порог 4.5 для
обычного текста, 3.0 для значащей графики и крупного жирного (надпись кнопки — 16sp Black).

Так 2026-07-27 нашлось, что в тёмной теме бейдж с госномером машины давал контраст **1.05** —
номер не виден вообще, хотя весь смысл бейджа в том, чтобы пассажир сверил машину.

## 5. `ktbaseline.sh` — правки в экранах

```bash
bash tools/ktbaseline.sh save    # эталон ДО правок
bash tools/ktbaseline.sh check   # после правок
```

Экраны не собрать, но разобрать компилятор может. Неразрешённых имён 511 — все из Compose.
Любое **новое** имя после правок = ссылка на то, чего нет.

## 6. Прочие скрипты

- `audit_inventory.py` — детерминированная инвентаризация исходников (хэши файлов) для реестра аудита: `--write` пересоздаёт `docs/audit-code-inventory.json`, `--check` падает при дрейфе (тест — `tools/tests/test_audit_inventory.py`). Статусы аудита ведёт `docs/audit-blocks.md`.
- `audit_*.py` (`audit_home_booking`, `audit_private_destination`, `audit_*_notification` и др.) — **ручные** проверки на живом эмуляторе API 35 с локальным фикстурным сервером (обычно порт `5198`: debug-сборка с `YULDASH_DEBUG_API_BASE_URL=http://127.0.0.1:5198`; нужны `adb` и запущенный эмулятор). В CI не входят.
- `tariff_icons.py` — подготовка картинок машин для тарифов (обрезка полей, WebP) из папки-источника в `android/app/src/main/res/drawable-nodpi`: `python tools/tariff_icons.py <папка-с-исходниками>`.

## Правило (docs/lessons.md)

Новая проверка засчитывается, только если она **краснеет на до-фиксной версии файла**
и молчит на исправленной. Каждый класс ошибок, проскочивший мимо, добавляется сюда.

**Ничто из этого не заменяет сборку.** Экраны на Compose здесь не компилируются вообще.
Гейт прежний: `gradlew :app:assembleDebug`.
