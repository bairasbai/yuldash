# Карточка: `android/app/src/main/java/com/yuldash/app/IncomeCalculatorScreen.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: ожидает ревью

## Назначение

Калькулятор дохода АВТОРА (админ-инструмент для Александра) — чистый расчёт на клиенте, без
сервера. Показывает оценку месячной выручки/чистыми с бизнеса (купоны, Boost, опционально
комиссия такси) и честную экономику водителя (валовый − бензин − комиссия). Не пользовательский
экран — Александр показывает его партнёрам и сам крутит ползунки.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `rub(v)` | 54–63 | Рубли с разрядом-пробелом (узкий неразрывный, U+202F) + «₽» | Делегирует общему `fmtRub` (WalletScreen.kt) вместо своей копии — см. E1 | исправлено, R1 |
| Карточка «Чистыми тебе в месяц» | ~109–122 | Главная сумма + подпись | П3 (ревью Opus, исполнено): `Color.White` → `CanonOnAccent` (тот же белый, но токен, не хардкод) | ок (после правки) |
| `IncomeCalculatorScreen` (расчёт) | ~79–95 | Купоны+Boost(+такси) → `gross` → `net` автора | Чистая арифметика, Double; никаких округлений до показа (округляет только `rub()`) | ок |
| Честная экономика водителя | ~90–95 | `driverGrossMonth − fuelMonth − driverCommissionMonth` | П3 (ревью Opus, исполнено): `coerceAtLeast(0.0)` снят — убыток показывается честно, тем же `rub()` с рабочим минусом; цвет суммы красный (`CanonRed`), когда < 0 | ок (после правки), R3 |
| Тумблер «Включить такси» | ~146–159 | Вкл/выкл такси-режим | П3 (ревью Opus, исполнено): `contentDescription` на двух языках через `appText` (значение считано ДО `.semantics{}` — внутри этого блока `@Composable`-функции звать нельзя) | ок (после правки) |
| `CalcSlider` | ~216–247 | Один ползунок + подпись-значение | П3 (ревью Opus, исполнено): `Modifier.semantics{contentDescription=label}` — TalkBack называет тему ползунка, не голое число; `testTag` — опциональный, в проде ничего не меняет | ок (после правки), R4 |

## Связи

- `fmtRub` (WalletScreen.kt, тот же модуль, `internal`) — общий денежный форматтер
  приложения; до правки файл носил свою копию.
- Чисто локальный экран: сети, ApiClient, модели данных не использует вовсе.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Деньги форматируются общим `fmtRub`: разряд-пробел (узкий неразрывный, не ломается при переносе строки), минус не отрывается лишним пробелом на отрицательных суммах, у которых число ЦИФР (без минуса) кратно трём | `android/app/src/test/java/com/yuldash/app/walk/l1_4/IncomeCalculatorScreenTest.kt::rub_negativeMultipleOfThousand_keepsMinusGlued`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/IncomeCalculatorScreenTest.kt::rub_roundsToNearestWholeRuble` | да — M15 |
| R2 | Комиссия водителя считается от ЕГО СОБСТВЕННОГО валового (а не от выручки автора) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/IncomeCalculatorScreenTest.kt::driverEconomy_subtractsFuelAndCommissionFromGross` | да — M16 |
| R3 | «Чистыми водителю» показывает ЧЕСТНЫЙ убыток (не «0 ₽», когда расшифровка ниже вычитает больше валового) — красным цветом | `android/app/src/test/java/com/yuldash/app/walk/l1_4/IncomeCalculatorScreenTest.kt::driverEconomy_honestlyShowsLoss_whenFuelExceedsGross` | да — M17 |
| R4 | Ползунок называет свою тему для TalkBack (`contentDescription = label`, не голое число) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/IncomeCalculatorScreenTest.kt::slider_exposesHumanLabelAsContentDescription_forTalkBack` | да — M43 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E1 | `rub()` был самодельным форматтером: группировка разрядов через переворот строки (reverse → по 3 символа → join пробелом → reverse обратно), и эта наивная схема цепляла знак минуса как обычный символ. Ломались ТОЛЬКО отрицательные суммы, у которых число ЦИФР (без минуса) кратно трём. | Прямой вызов `rub(-100000.0)`/`rub(-100.0)` | `rub()` делегирует `fmtRub` вместо собственной реализации | `IncomeCalculatorScreenTest::rub_negativeMultipleOfThousand_keepsMinusGlued` — см. M15 (воспроизводит ИМЕННО этот алгоритм) |
| E14 (P3, ревью Opus, исполнено) | Жадные настройки бензина давали расход больше валового. «Чистыми водителю» показывало «0 ₽» (`coerceAtLeast(0.0)`), а расшифровка ниже честно писала «вычли бензин 10 800 000 ₽ + комиссия 60 000 ₽» из валовых 600 000 ₽ — цифры не сходились. | Пройти по ползункам как в `driverEconomy_honestlyShowsLoss_whenFuelExceedsGross` | `coerceAtLeast(0.0)` снят; сумма показывается как есть («-10 260 000 ₽»), цвет — `CanonRed` | Тот же тест — до правки искали бы «0 ₽», после ищут «-10 260 000 ₽» и честно сходящуюся расшифровку |
| E15 (P3, ревью Opus, исполнено) | `Color.White` вместо `CanonOnAccent` — визуально тот же белый, но хардкод в обход палитры (CLAUDE.md §11 запрещает) | `grep Color.White` в файле | Заменено на `CanonOnAccent` (тот же `0xFFFFFFFF`) | Правило CLAUDE.md выполнено по тексту; `CanonSourceGuardTest` цвета не проверяет вообще |
| E16 (P3, ревью Opus, исполнено) | У ползунков и тумблера «Включить такси» не было `contentDescription` — TalkBack читал бы голое число/«вкл-выкл» без темы | TalkBack на реальном устройстве (не проверялось — эмулятора нет) | `Modifier.semantics{contentDescription=...}` на слайдере (через уже двуязычный `label`) и на тумблере | `slider_exposesHumanLabelAsContentDescription_forTalkBack` — до правки описание было пустым |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M15 | Воспроизводит РЕАЛЬНЫЙ исторический алгоритм (reverse→chunked(3)→join(" ")→reverse) | `rub_negativeMultipleOfThousand_keepsMinusGlued` | KILLED |
| M16 | Комиссия водителя считается от `gross` (выручка автора) вместо `driverGrossMonth` | `driverEconomy_subtractsFuelAndCommissionFromGross` | KILLED |
| M17 | Возврат `coerceAtLeast(0.0)` — убыток снова прячется за нулём | `driverEconomy_honestlyShowsLoss_whenFuelExceedsGross` | KILLED |
| M43 | Слайдер снова без `contentDescription` | `slider_exposesHumanLabelAsContentDescription_forTalkBack` | KILLED |

## Остаток и ограничения

Экран не пользовательский (админ-инструмент), поэтому стандартные состояния
загрузка/пусто/ошибка к нему неприменимы — расчёт мгновенный и локальный. Визуальная проверка
на эмуляторе (ползунки на палец, звук TalkBack) не делалась — только Robolectric через точный
`SemanticsActions.SetProgress`/разбор дерева семантики.

**P3, не блокирует (из отчёта ведущему, оставлено как есть):**
- «Заработано» у водителя (до комиссии) и у курьера (после) означают разное — решение за
  Александром, не этот файл.
