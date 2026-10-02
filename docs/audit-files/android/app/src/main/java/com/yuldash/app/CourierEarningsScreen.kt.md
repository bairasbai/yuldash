# Карточка: `android/app/src/main/java/com/yuldash/app/CourierEarningsScreen.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: ожидает ревью

## Назначение

«Мой заработок» курьера: чистыми / комиссия / доставки по периодам, разбивка по дням. ВАЖНО
(комментарий файла): ВСЕ суммы в КОПЕЙКАХ (в отличие от экрана водителя, где сервер отдаёт
рубли) — форматируются через `kopToRub`. Также определяет общие «денежные» кирпичи
(`MoneyType`, `MoneyLine`, `MoneySectionHeader`, `MoneyStaleStrip`), которыми пользуются
`DriverEarningsScreen.kt` и чек поездки (вне зоны) — единый вид денег по всему приложению.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `MoneyType` | 76–86 | Шкала размеров денежных экранов (Hero/Value/Body/Caption) | Совпадает со шкалой `CanonSourceGuardTest` (34/19/14/12 sp) — не отдельная самодельная шкала | ок |
| `CourierEarningsScreen` / when-блок | ~156–221 | Выбирает ветку: скелетон / ошибка / (пусто или данные) | П2 (ревью Opus) ИСПРАВЛЕНО: ошибка срабатывает при `d==null || d.period != period` — раньше смена периода, сорвавшаяся на сети, либо подставляла сумму ЧУЖОГО периода под stale-плашкой, либо (если у старого периода было 0 доставок) рисовала ЛОЖНОЕ «Пока нет доставок» вообще без намёка на ошибку. `val cd = d ?: return@LazyColumn` — безопасный паттерн (не `d!!`, который реально падал `NullPointerException` в этой же правке — см. E9) | ок (после правки), R4, R5 |
| `CourierTotalsCard` | ~303–376 | Итог: чистыми (анимированная смена по периоду) + доставки + комиссия + неоплаченные доставки | `kopToRub(net)`/`kopToRub(commissionKop)` — копейки не теряются; `d.deliveries.toString()` — штуки без денежного формата (R2); `d.unpaidDeliveries>0` → отдельная строка (R7) | ок, R1, R2, R7 |
| `CourierDayRow` | 340–394 | Строка дня: дата, бар, сумма, доставки | `maxNet.coerceAtLeast(1)` — без деления на 0 | ок |
| `MoneyLine`/`MoneySectionHeader`/`MoneyStaleStrip` | 404–455 | Общие кирпичи денежных экранов | `MoneyLine` не добавляет «₽» сама — валюту решает вызывающий (копейки vs штуки видно по тому, что именно передано) | ок |
| `courierDayLabel` | 458–463 | ISO-дата → «дд.мм» | Неожиданный формат → как есть, без выдумывания | ок |
| `MoneyPeriodSegment` | ~265–301 | Один сегмент переключателя периода | П3 (ревью Opus, исполнено): `.semantics(mergeDescendants=true){selected=active; role=Role.Tab}` — TalkBack теперь говорит, какой период выбран (у водителя `EarnPeriodChip` это уже было, через `Role.RadioButton`) | ок (после правки), R8 |

## Связи

- `ApiClient.getCourierEarnings(period)` (та же правка листа: DTO несёт `period`/
  `unpaidNetKop`/`unpaidDeliveries`) → `GET /courier/earnings?period=…`.
- `NavSignals.openCourierOrders` (`TaxiOfferNotifier.kt`, вне зоны) — пустое состояние ведёт
  на вкладку «Заказы», а не просто советует словами.
- `kopToRub` (`CouponsScreen.kt`, вне зоны) — общий копеечный форматтер.
- Кирпичи (`MoneyLine` и др.) переиспользуются в `DriverEarningsScreen.kt` этого же листа.
- Сервер (`backend/app/routers/courier.py::courier_earnings`, вне зоны, только чтение):
  `unpaid_net_kop`/`unpaid_deliveries` — доставки, по которым разбор жалобы подтвердил
  «курьеру не заплатили» (волна 191, по образцу волны 190 у водителя).

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Деньги (чистыми, комиссия) — через `kopToRub`, копейки не теряются | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::totalsUseKopToRub_keepingKopecks` | да — M22 |
| R2 | Доставки — штуки, НЕ деньги: `.toString()`, без копеечного формата | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::totalsUseKopToRub_keepingKopecks`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::deliveriesOverThousand_noThousandsSeparator_plainCount` | да — M23 |
| R3 | Пусто по неделе/месяцу называет период конкретно, «всё время» — обобщённо; действие ведёт на вкладку заказов | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::emptyState_weekAndMonth_mentionThePeriod_elseIsGeneric`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::emptyState_actionOpensCourierOrdersTab_andGoesBack` | да — M24 |
| R4 | Сбой сервера → ошибка с рабочим «Повторить»; смена периода, сорвавшаяся на сети, НЕ выдаёт ложное «Пока нет доставок» и не подставляет чужой период | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::serverError_showsRetryable_errorState`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::periodSwitchFails_showsHonestError_notStaleOrFalseEmptyFromWrongTab` | да — M25, M36 |
| R5 | Смена периода уходит правильным параметром | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::periodSwitch_requestsCorrectPeriod_eachTime` | подтверждает корректность запроса |
| R6 | Сумма КОНКРЕТНОГО дня — через `kopToRub`, копейки не теряются | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::dayRow_showsOwnNetSum_keepingKopecks` | да — M37 |
| R7 | Неоплаченные доставки (`unpaid_*`) показываются отдельной строкой, не пропадают молча | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::unpaidDeliveries_shownSeparately_notSilentlyDropped`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::noUnpaidDeliveries_noBannerShown` | да — M38 |
| R8 | Переключатель периода называет роль (Tab) и состояние выбора для TalkBack | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::periodSwitch_exposesTabRoleAndSelectedState_forTalkBack` | да — M42 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E7-к (P2, ревью Opus) | Та же ошибка, что у водителя (E7): смена «Неделя→Месяц», сорвавшаяся на сети, либо подставляла сумму ЧУЖОГО периода со stale-плашкой, либо — если у СТАРОГО периода было 0 доставок — рисовала ЛОЖНОЕ «Пока нет доставок» СОВСЕМ без ошибки (курьер с сотней доставок видел «пока нет»). | Переключиться на «Месяц», когда запрос за месяц отвечает 500, при наличии успешно загруженной «Недели» | `error && (d == null || d.period != period)` — отдельная проверка периода | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::periodSwitchFails_showsHonestError_notStaleOrFalseEmptyFromWrongTab` (новый) — до правки виднелась бы чужая сумма/ложная пустота; после — честная ошибка |
| E9 (найдено при написании тестов к этой же правке) | Безопасный паттерн курьера (`val cd = d!!`, написанный при первой версии правки периода) падал `NullPointerException` в Robolectric-тестах при ПЕРВОЙ отрисовке экрана — `d` ещё не гарантирован ненулевым в этой ветке в самый первый кадр под тестовым диспетчером. | `./gradlew testDebugUnitTest` на любом тесте экрана — крашился уже на `setContent{}` | `d!!` → `d ?: return@LazyColumn`, тот же безопасный паттерн, что уже был у водителя | Все тесты файла — до правки минимум 3 падали с `NullPointerException`/`CoroutinesInternalError`, после — зелёные |
| E10 (P2, ревью Opus, из «ВНЕ ЗОНЫ» леада — исполнено) | Доставки, по которым разбор жалобы подтвердил «курьеру не заплатили» (`unpaid_net_kop`/`unpaid_deliveries`), не читались и не показывались. | Ответ сервера с `unpaid_deliveries: 1, unpaid_net_kop: 30000` | `CourierEarningsDto` получил поля `unpaidNetKop`/`unpaidDeliveries`; `CourierTotalsCard` показывает их отдельной строкой | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::unpaidDeliveries_shownSeparately_notSilentlyDropped` — до правки строки не было; после — «Ещё 1 доставка на 300 ₽ не оплачена…» |
| E17 (P3, ревью Opus, исполнено) | Переключатель периода (три сегмента одной пилюли) не имел `role`/`selected` — TalkBack не мог сказать, какой период выбран сейчас (у водителя аналогичный `EarnPeriodChip` это уже умел). | TalkBack на реальном устройстве (не проверялось — эмулятора нет) | `.semantics(mergeDescendants=true){selected=active; role=Role.Tab}` на `Surface` сегмента | `periodSwitch_exposesTabRoleAndSelectedState_forTalkBack` — до правки роли не было вовсе |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M22 | Главная сумма без `kopToRub` — копейки отваливаются | `totalsUseKopToRub_keepingKopecks` | KILLED |
| M23 | Доставки снова форматируются как деньги (`fmtRub`, реальный аналог ошибки E3 у водителя) | `deliveriesOverThousand_noThousandsSeparator_plainCount` | KILLED |
| M24 | Пустое состояние за неделю больше не называет период | `emptyState_weekAndMonth_mentionThePeriod_elseIsGeneric` | KILLED |
| M25 | Сбой сервера больше не ставит `error=true` | `serverError_showsRetryable_errorState` | KILLED |
| M36 | Сравнение периода снято — ложная пустота/чужой период возвращаются | `periodSwitchFails_showsHonestError_notStaleOrFalseEmptyFromWrongTab` | KILLED |
| M37 | Сумма дня без `kopToRub` | `dayRow_showsOwnNetSum_keepingKopecks` | KILLED |
| M38 | Неоплаченные доставки перестают показываться | `unpaidDeliveries_shownSeparately_notSilentlyDropped` | KILLED |
| M42 | `role`/`selected` сняты с переключателя периода | `periodSwitch_exposesTabRoleAndSelectedState_forTalkBack` | KILLED |

## Остаток и ограничения

`freshness`/`reveal` (приглушение старых цифр при подгрузке нового периода,
`graphicsLayer{alpha=...}`) проверено по конечному состоянию (значения `reveal`/`freshness`
корректно приходят к 1.0 после загрузки), но не по КАДРАМ анимации — настоящий визуальный
плавный переход на эмуляторе не смотрел. Реальный жест «потянуть вниз» не эмулировался
(аналогично `DriverEarningsScreen.kt`).
