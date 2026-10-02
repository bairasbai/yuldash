# Карточка: `android/app/src/main/java/com/yuldash/app/CourierEarningsScreen.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: Opus 5.5 (ревью денег)

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
| `CourierEarningsScreen` | 100–224 | Экран целиком: переключатель периода, итог, дни, заметка | См. R1–R4 ниже | ок |
| `CourierTotalsCard` | 291–334 | Итог: чистыми (анимированная смена по периоду) + доставки + комиссия | `kopToRub(net)`/`kopToRub(commissionKop)` — копейки не теряются; `d.deliveries.toString()` — штуки без денежного формата (R2) | ок, R1, R2 |
| `CourierDayRow` | 340–394 | Строка дня: дата, бар, сумма, доставки | `maxNet.coerceAtLeast(1)` — без деления на 0 | ок |
| `MoneyLine`/`MoneySectionHeader`/`MoneyStaleStrip` | 404–455 | Общие кирпичи денежных экранов | `MoneyLine` не добавляет «₽» сама — валюту решает вызывающий (копейки vs штуки видно по тому, что именно передано) | ок |
| `courierDayLabel` | 458–463 | ISO-дата → «дд.мм» | Неожиданный формат → как есть, без выдумывания | ок |

## Связи

- `ApiClient.getCourierEarnings(period)` (вне зоны) → `GET /courier/earnings?period=…`.
- `NavSignals.openCourierOrders` (`TaxiOfferNotifier.kt`, вне зоны) — пустое состояние ведёт
  на вкладку «Заказы», а не просто советует словами.
- `kopToRub` (`CouponsScreen.kt`, вне зоны) — общий копеечный форматтер.
- Кирпичи (`MoneyLine` и др.) переиспользуются в `DriverEarningsScreen.kt` этого же листа.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Деньги (чистыми, комиссия) — через `kopToRub`, копейки не теряются | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::totalsUseKopToRub_keepingKopecks` | да — M22 |
| R2 | Доставки — штуки, НЕ деньги: `.toString()`, без копеечного формата | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::totalsUseKopToRub_keepingKopecks` | да — M23 |
| R3 | Пусто по неделе/месяцу называет период конкретно («за эту неделю» / «за этот месяц»), «всё время» — обобщённо; действие ведёт на вкладку заказов | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::emptyState_weekAndMonth_mentionThePeriod_elseIsGeneric`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::emptyState_actionOpensCourierOrdersTab_andGoesBack` | да — M24 |
| R4 | Сбой сервера → ошибка с рабочим «Повторить» | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::serverError_showsRetryable_errorState` | да — M25 |
| R5 | Смена периода уходит правильным параметром | `android/app/src/test/java/com/yuldash/app/walk/l1_4/CourierEarningsScreenTest.kt::periodSwitch_requestsCorrectPeriod_eachTime` | подтверждает корректность запроса |

## Найденные ошибки

Ошибок не найдено. Этот экран — ОБРАЗЕЦ правильного разделения «деньги vs штуки»
(`d.deliveries.toString()`), на который опирается исправление в `DriverEarningsScreen.kt`
(там было наоборот перепутано).

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M22 | Главная сумма без `kopToRub` (`"$net ₽"`) — копейки отваливаются | `totalsUseKopToRub_keepingKopecks` | KILLED: тест упал |
| M23 | Доставки форматируются как деньги (`kopToRub(d.deliveries)`) — «3» превращается в «0,03 ₽» | `totalsUseKopToRub_keepingKopecks` | KILLED: тест упал |
| M24 | Пустое состояние за неделю больше не называет период (ветка `"week"` отключена) | `emptyState_weekAndMonth_mentionThePeriod_elseIsGeneric` | KILLED: тест упал |
| M25 | Сбой сервера больше не ставит `error=true` | `serverError_showsRetryable_errorState` | KILLED: тест упал |

## Остаток и ограничения

`freshness`/`reveal` (приглушение старых цифр при подгрузке нового периода,
`graphicsLayer{alpha=...}`) проверено по конечному состоянию (значения `reveal`/`freshness`
корректно приходят к 1.0 после загрузки), но не по КАДРАМ анимации — настоящий визуальный
плавный переход на эмуляторе не смотрел. Реальный жест «потянуть вниз» не эмулировался
(аналогично `DriverEarningsScreen.kt`).
