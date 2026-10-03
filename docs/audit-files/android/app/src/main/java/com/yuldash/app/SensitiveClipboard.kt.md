# Карточка: `android/app/src/main/java/com/yuldash/app/SensitiveClipboard.kt`

- Статус: verified
- Лист: leaf-2.2
- Проверял: Sonnet 5 (leaf-2.2); принимал: Opus 5.5

## Назначение

Единая точка копирования «чужого»/опасного в буфер обмена: номер телефона для перевода по СБП,
код вручения посылки, ссылка «следить за поездкой», текст для диктовки при SOS. На Android 13+
ставит `ClipDescription.EXTRA_IS_SENSITIVE`, что убирает системный предпросмотр содержимого
(видимый случайному соседу по плечу) и просит систему/клавиатуры не запоминать значение. Коды
скидок (`CouponsScreen.kt`) копируются обычным способом намеренно — их показывают, чтобы человек
их передавал другим.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `copySensitive` | 30–39 | Берёт `ClipboardManager`, создаёт `ClipData.newPlainText("", text)` (пустой label — он тоже виден в системном UI), на SDK ≥ 33 (TIRAMISU) ставит `EXTRA_IS_SENSITIVE=true` через `PersistableBundle`, кладёт в `setPrimaryClip` | `getSystemService` может вернуть `null` (тип `ClipboardManager?`) → `return` без крэша, молча ничего не копирует | ок |

## Связи

Android `ClipboardManager`. Вызывается из экранов с личными данными: оплата СБП, вручение
посылки, трекинг поездки, диктовка SOS (список закреплён сторожем `SensitiveClipboardGuardTest`,
который требует `copySensitive(...)` везде, где есть `clipboard.setText(` — кроме явного
исключения `CouponsScreen.kt`).

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Личное копируется ТОЛЬКО через `copySensitive`, а не обычным `clipboard.setText(...)` — кроме явно перечисленного исключения (коды скидок) | `android/app/src/test/java/com/yuldash/app/SensitiveClipboardGuardTest.kt::"чувствительное не копируется обычным способом"` | да — существующий тест (структурный, по всем исходникам) |
| R2 | Исключения из R1 задокументированы человеческой причиной (не «просто забыли») | `android/app/src/test/java/com/yuldash/app/SensitiveClipboardGuardTest.kt::"причины для обычного копирования написаны для человека"` (длина ≥ 20 символов) | да — существующий тест |
| R3 | `copySensitive` реально ставит системный флаг `EXTRA_IS_SENSITIVE`, а не просто копирует как обычно | `android/app/src/test/java/com/yuldash/app/SensitiveClipboardGuardTest.kt::"помощник действительно ставит системный флаг"`, `android/app/src/test/java/com/yuldash/app/walk/l2_2/SensitiveClipboardBehaviorTest.kt::copySensitive_setsExtraIsSensitive_true` (новый, поведенческий — реально читает `ClipDescription.extras` после вызова) | да — M6, KILLED |

## Найденные ошибки

Ошибок не найдено.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M6 | `copySensitive`: `putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)` → `putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)` | `walk/l2_2/SensitiveClipboardBehaviorTest::copySensitive_setsExtraIsSensitive_true` | KILLED (см. отчёт листа) |

## Остаток и ограничения

- R1/R2 — существующие структурные тесты, не перемутированы заново в этом листе (приоритет отдан
  R3 — единственному правилу БЕЗ поведенческой проверки: прежний `SensitiveClipboardGuardTest`
  проверял лишь присутствие строки `EXTRA_IS_SENSITIVE` в исходнике, а не то, что флаг реально
  попадает в `true`, а не, скажем, в `false` по опечатке).
- SDK < 33 (TIRAMISU) ветка не ставит флаг вовсе (его в API не существует) — это по спецификации
  Android, не проверяется отдельно (`@Config(sdk=[34])`, как и весь остальной проект).
- Содержимое буфера после `setPrimaryClip` на реальном устройстве (действительно ли системная
  всплывашка-предпросмотр не показывается) — не проверяется юнит-тестом, это поведение ОС, не
  приложения; полагаемся на документированный контракт `EXTRA_IS_SENSITIVE`.
