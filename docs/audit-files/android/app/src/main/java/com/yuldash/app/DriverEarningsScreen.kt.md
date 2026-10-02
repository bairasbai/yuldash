# Карточка: `android/app/src/main/java/com/yuldash/app/DriverEarningsScreen.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: ожидает ревью

## Назначение

«Мой заработок» водителя попутки — история по периодам (неделя/месяц/всё), итог + разбивка
по дням с барами. ВАЖНО (комментарий файла): `total`/`sum` приходят в РУБЛЯХ, не в копейках —
в отличие от курьерского аналога (`CourierEarningsScreen.kt`), который в копейках.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `DriverEarningsScreen` / `load(p)` | 62–78 | Грузит заработок за период, рисует состояния | `error`/`loading`/`data` — три флага; `stale`-полоска, если `error` при уже загруженных `data` | ок, R3, R4 |
| `DriverEarningsScreen` / when-блок | ~104–149 | Выбирает ветку: скелетон / ошибка / данные (пусто или список) | П2 (ревью Opus) ИСПРАВЛЕНО: ошибка срабатывает, если ИЛИ `data==null`, ИЛИ `data.period != period` — раньше смена вкладки «Неделя→Месяц», сорвавшаяся на сети, молча показывала сумму ЗА НЕДЕЛЮ под выбранным «Месяцем» со «вводящей в заблуждение» плашкой «может быть устарело» (неделя — не «устаревший месяц», это ДРУГОЕ число) | ок (после правки), R4 |
| `EarnPeriodChip` | ~152–170 | Переключатель периода | `semantics(mergeDescendants, role=RadioButton, selected)` — доступность через смысловую роль, не руками `contentDescription` | ок |
| `EarnTotalsCard` | ~173–222 | Итог: сумма во всю ширину + «Поездок» + неоплаченные поездки | `fmtRub(d.total)` — деньги; `d.trips` — ШТУКИ (см. E3); `d.unpaidTrips>0` → отдельная строка про неоплаченные (см. E8) | исправлено, R1, R2, R7 |
| `EarnDayRow` | ~225–261 | Строка дня: дата, бар ∝ сумме, сумма, поездки | `maxSum.coerceAtLeast(1)` — защита от деления на 0; сумма дня — `fmtRub(day.sum)`, тот же формат, что у итога (R6) | ок, R6 |
| `shortDay` | ~264–268 | ISO-дата → «дд.мм» | Неожиданный формат → возвращает строку как есть, не выдумывает дату (`try/catch`) | ок |
| `notPaidAgreeFem` | ~286–292 | Согласование «не оплачена/не оплачены» по числу | Та же логика, что `tripsWordEarn`, но для сказуемого (жен. род, ед.ч.) | ок |

## Связи

- `ApiClient.getDriverEarnings(period)` (`data/ApiClient.kt`, та же правка листа: DTO теперь
  несёт `period`/`unpaidTotal`/`unpaidTrips`) → `GET /driver/earnings?period=week|month|all`.
- `fmtRub` — из `WalletScreen.kt` (этот же лист): используется ТАК ЖЕ, как в кошельке и
  в разборе чека таксиста (единый денежный шрифт/формат по приложению).
- `MoneyLine`/`MoneyStaleStrip`/`MoneyType` — общие кирпичи денежных экранов, определены в
  `CourierEarningsScreen.kt` (этот же лист, см. его карточку).
- Сервер (`backend/app/debt.py::driver_earnings`, вне зоны, только чтение): `unpaid_total`/
  `unpaid_trips` — поездки, по которым разбор жалобы подтвердил «денег не было» (волна 190);
  сервер явно пишет «спрятать совсем было бы вторым обманом».

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Счётчик поездок — штуки, НЕ деньги: без денежного разряда-пробела | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::tripsCount_isPlainNumber_notMoneyGrouped` | да — M18 |
| R2 | Итоговая сумма — деньги, С разрядом-пробелом (`fmtRub`) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::tripsCount_isPlainNumber_notMoneyGrouped`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::serverError_showsRetryable_errorState` | да — M19 |
| R3 | Пустая история (новичок) → дружелюбная заглушка с понятным текстом | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::noTrips_showsFriendlyEmptyState` | да — M20 |
| R4 | Сбой сервера → ошибка с «Повторить» (нет данных для ВЫБРАННОГО периода) / полоска «может быть старыми» (данные ЭТОГО ЖЕ периода уже есть) — смена вкладки, сорвавшаяся на сети, не выдаёт чужой период за текущий | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::serverError_showsRetryable_errorState`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::periodSwitchFails_showsHonestError_notStaleNumberFromWrongTab` | да — M21, M33 |
| R5 | Смена периода уходит правильным параметром на сервер | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::periodSwitch_requestsCorrectPeriod_eachTime` | подтверждает корректность запроса; отдельной денежной мутации не заводил |
| R6 | Сумма КОНКРЕТНОГО дня — деньги, с разрядом-пробелом (не только итог периода) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::dayRow_showsOwnSum_withThousandsSeparator` | да — M34 |
| R7 | Неоплаченные поездки (`unpaid_*`) показываются отдельной строкой, не пропадают молча | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::unpaidTrips_shownSeparately_notSilentlyDropped`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::noUnpaidTrips_noBannerShown` | да — M35 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E3 | Строка «Поездок» форматировалась тем же денежным форматтером (`fmtRub`), что и суммы в рублях. У водителя за «Всё время» поездок может быть ≥ 1000 — счётчик получал денежный вид («1 234» вместо «1234»). | Ответ сервера с `trips: 1234` | `fmtRub(d.trips)` → `d.trips.toString()` | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::tripsCount_isPlainNumber_notMoneyGrouped` — до правки «1 234», после — «1234» |
| E7 (P2, ревью Opus) | После неудачной смены периода (например, «Неделя»→«Месяц», запрос за месяц упал) экран держал СТАРЫЕ данные за неделю, но показывал их под выбранным «Месяцем» с плашкой «может быть устарело» — неделя не «устаревший месяц», это ЧУЖОЕ число. Существовавший тест `dataAlreadyShown_nextLoadFails_showsStaleStripNotError` это закреплял как правильное поведение. | Переключиться на «Месяц», когда запрос за месяц отвечает 500, при наличии успешно загруженной «Недели» | Сравнение `data?.period != period` — при несовпадении периодов показываем честную ошибку вместо чужого числа | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::periodSwitchFails_showsHonestError_notStaleNumberFromWrongTab` (новый, заменил прежний) — до правки сумма за неделю виднелась бы под «Месяцем»; после — честная ошибка, «Повторить» подтягивает верный месяц |
| E8 (P2, ревью Opus, из «ВНЕ ЗОНЫ» леада — исполнено) | Поездки, по которым разбор жалобы подтвердил «денег не было» (`unpaid_total`/`unpaid_trips` с сервера), сервер не включает в total/trips, но и не прячет совсем. Клиент эти поля не читал и не показывал — водитель видел МЕНЬШЕ поездок, чем сделал, без объяснения. | Ответ сервера с `unpaid_trips: 2, unpaid_total: 900` | `DriverEarningsDto` (ApiClient.kt) получил поля `unpaidTotal`/`unpaidTrips`; `EarnTotalsCard` показывает их отдельной строкой при `unpaidTrips>0` | `android/app/src/test/java/com/yuldash/app/walk/l1_4/DriverEarningsScreenTest.kt::unpaidTrips_shownSeparately_notSilentlyDropped` — до правки строки не было вовсе (поля не читались); после — «Ещё 2 поездки на 900 ₽ не оплачены…» |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M18 | Возврат `fmtRub(d.trips)` вместо `.toString()` | `tripsCount_isPlainNumber_notMoneyGrouped` | KILLED |
| M19 | Итог без разряда-пробела | `tripsCount_isPlainNumber_notMoneyGrouped` | KILLED |
| M20 | Пустая история никогда не показывает заглушку | `noTrips_showsFriendlyEmptyState` | KILLED |
| M21 | Сбой сервера больше не ставит `error=true` | `serverError_showsRetryable_errorState` | KILLED |
| M33 | Сравнение периода снято — чужой период снова выдаётся за текущий | `periodSwitchFails_showsHonestError_notStaleNumberFromWrongTab` | KILLED |
| M34 | Сумма дня без разряда-пробела | `dayRow_showsOwnSum_withThousandsSeparator` | KILLED |
| M35 | Неоплаченные поездки перестают показываться | `unpaidTrips_shownSeparately_notSilentlyDropped` | KILLED |

## Остаток и ограничения

Реальный жест «потянуть вниз» (`AppPullRefresh`) не эмулировался пальцем — вместо него
смена периода дёргает тот же код `load(period)`, что и жест. Визуальная анимация бара
(`animateFloatAsState`/каскад) не проверялась на реальном кадре — только то, что финальное
состояние (пропорция) верно задаётся.

**P3, не блокирует (из отчёта ведущему):**
- Пустая неделя у ОПЫТНОГО водителя (≥1 поездка когда-либо, но 0 за выбранный период) всё
  равно показывает «Заверши первую поездку» — для новичка это верно, для опытного — нет.
  Решение за Александром (нужно знать историю водителя целиком, не только за период).
- «Заработано» здесь — сумма ДО комиссии платформы (сервер отдаёт `price_final`/
  `price_estimate` как есть), а у курьера аналогичная подпись — «чистыми», ПОСЛЕ комиссии.
  Одинаковые слова, разный смысл — решение за Александром (подпись «Выручка до комиссии» или
  комиссия отдельной строкой, как у курьера).
- Три чипа периода (`Row` без `weight`, `maxLines=1`) при крупном системном шрифте могут
  обрезать «Всё время»/«Бөтә ваҡыт» — не проверено на эмуляторе, нет доступа на машине.
- Разряд-пробел в `fmtRub`/`kopToRub` — обычный пробел, не неразрывный: на 34sp при крупном
  шрифте сумма теоретически может перенестись между разрядами на две строки.
