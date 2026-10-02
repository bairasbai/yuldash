# Карточка: `android/app/src/main/java/com/yuldash/app/DriverEarningsScreen.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: Opus 5.5 (ревью денег)

## Назначение

«Мой заработок» водителя попутки — история по периодам (неделя/месяц/всё), итог + разбивка
по дням с барами. ВАЖНО (комментарий файла): `total`/`sum` приходят в РУБЛЯХ, не в копейках —
в отличие от курьерского аналога (`CourierEarningsScreen.kt`), который в копейках.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `DriverEarningsScreen` / `load(p)` | 62–149 | Грузит заработок за период, рисует состояния | `error`/`loading`/`data` — три независимых флага; `stale`-полоска, если `error` при уже загруженных `data` | ок, R3, R4 |
| `EarnPeriodChip` | 152–170 | Переключатель периода | `semantics(mergeDescendants, role=RadioButton, selected)` — доступность через смысловую роль, не руками `contentDescription` | ок |
| `EarnTotalsCard` | 173–199 | Итог: сумма во всю ширину + «Поездок» | `fmtRub(d.total)` — деньги; `d.trips` — ШТУКИ (см. «Найденные ошибки», E3) | исправлено, R1, R2 |
| `EarnDayRow` | 202–238 | Строка дня: дата, бар ∝ сумме, сумма, поездки | `maxSum.coerceAtLeast(1)` — защита от деления на 0 при единственном дне с нулевой суммой | ок |
| `shortDay` | 241–245 | ISO-дата → «дд.мм» | Неожиданный формат → возвращает строку как есть, не выдумывает дату (`try/catch`) | ок |

## Связи

- `ApiClient.getDriverEarnings(period)` (`data/ApiClient.kt`, вне зоны) →
  `GET /driver/earnings?period=week|month|all`.
- `fmtRub` — из `WalletScreen.kt` (этот же лист): используется ТАК ЖЕ, как в кошельке и
  в разборе чека таксиста (единый денежный шрифт/формат по приложению).
- `MoneyLine`/`MoneyStaleStrip`/`MoneyType` — общие кирпичи денежных экранов, определены в
  `CourierEarningsScreen.kt` (этот же лист, см. его карточку).

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Счётчик поездок — штуки, НЕ деньги: без денежного разряда-пробела | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::tripsCount_isPlainNumber_notMoneyGrouped` | да — M18 |
| R2 | Итоговая сумма — деньги, С разрядом-пробелом (`fmtRub`) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::tripsCount_isPlainNumber_notMoneyGrouped`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::serverError_showsRetryable_errorState` | да — M19 |
| R3 | Пустая история (новичок) → дружелюбная заглушка с понятным текстом | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::noTrips_showsFriendlyEmptyState` | да — M20 |
| R4 | Сбой сервера → ошибка с «Повторить» (пусто) / полоска «может быть старыми» (данные уже есть) — и то и другое реально работает | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::serverError_showsRetryable_errorState`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::dataAlreadyShown_nextLoadFails_showsStaleStripNotError` | да — M21 |
| R5 | Смена периода уходит правильным параметром на сервер | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::periodSwitch_requestsCorrectPeriod_eachTime` | подтверждает корректность запроса; отдельной денежной мутации не заводил (не про суммы) |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E3 | Строка «Поездок» форматировалась тем же денежным форматтером (`fmtRub`), что и суммы в рублях. На малых числах (до 999) это незаметно — разряд-пробел просто не появляется. Но у водителя, который ездит давно, за «Всё время» поездок может быть ≥ 1000 — и счётчик ВДРУГ получал денежный вид («1 234» вместо «1234»), визуально путая «сколько поездок» с «сколько рублей» — ровно тем языком, которым в этом же приложении размечены ИМЕННО суммы (ср. `CourierEarningsScreen.kt`, где аналогичный счётчик доставок уже делался через `.toString()`, без денежного формата). | Открыть «Мой заработок» → «Всё время» с историей ≥ 1000 поездок (или просто: любой ответ сервера с `trips: 1234`) | `fmtRub(d.trips)` → `d.trips.toString()` — счётчик больше не проходит через денежный форматтер | `DriverEarningsScreenTest::tripsCount_isPlainNumber_notMoneyGrouped` — до правки экран показал бы «1 234», тест ожидает «1234» и отсутствие «1 234»; после правки тест проходит |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M18 | Возврат `fmtRub(d.trips)` вместо `.toString()` | `tripsCount_isPlainNumber_notMoneyGrouped` | KILLED: тест упал |
| M19 | Итог без разряда-пробела (`"${d.total} ₽"` вместо `fmtRub`) | `tripsCount_isPlainNumber_notMoneyGrouped` | KILLED: тест упал |
| M20 | Пустая история никогда не показывает заглушку (`if (d.byDay.isEmpty())` → `if (false)`) | `noTrips_showsFriendlyEmptyState` | KILLED: тест упал |
| M21 | Сбой сервера больше не ставит `error=true` — ни ошибки, ни «может быть старыми» не будет никогда | `serverError_showsRetryable_errorState` | KILLED: тест упал |

## Остаток и ограничения

Реальный жест «потянуть вниз» (`AppPullRefresh`) не эмулировался пальцем — вместо него
смена периода дёргает тот же код `load(period)`, что и жест. Визуальная анимация бара
(`animateFloatAsState`/каскад) не проверялась на реальном кадре — только то, что финальное
состояние (пропорция) верно задаётся.
