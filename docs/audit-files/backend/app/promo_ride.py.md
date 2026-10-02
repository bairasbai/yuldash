# Карточка: `backend/app/promo_ride.py`

- Статус: verified
- Лист: leaf-1.3
- Проверял: Sonnet 5 (leaf-1.3); принимал: Opus 5.5

## Назначение

Один «шов» на всю фичу «скидка по промокоду на поездку в такси» (kind=`taxi_ride`). Платформа
не касается денег за саму поездку (пассажир платит водителю напрямую), поэтому скидку не
«дают просто так» — её оплачивает ПЛАТФОРМА из своей комиссии: сначала комиссия гасится
скидкой (до нуля), а остаток (если скидка больше комиссии) доплачивается водителю в кошелёк.
Итог по деньгам водителя всегда тот же, что без промокода.

Вызывается из `routers/promo.py` (окно действия, предпросмотр, карточка «мой промокод»),
`routers/instant.py` (списание при создании заказа/предзаказа), `instant_service.py`
(пересчёт потолка при активации предзаказа, возврат при отмене из `safety`-потока),
`taxi_worker.py`/`cleanup.py` (возврат скидки при системном закрытии зависших заказов),
`debt.py`/`ledger.py` (разбивка комиссии на «к оплате водителем» и «компенсация из кошелька»),
`compensation.py` (общий список компенсаций водителю, которые не считаются скидываемой базой).

## Функции и разбор

| Функция | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `in_window` | 47–54 | Промокод сейчас в силе по датам | `valid_from ≤ now < valid_until`, пустые границы = без них | ок |
| `granted_kop` | 59–68 | Скидка при АКТИВАЦИИ кода, копейки | `perk_value` (₽) × 100, обрезано потолком `promo_ride_max_discount_rub`; не-`taxi_ride` → 0 | ок |
| `cap_for_price` | 71–83 | Скидка, применимая к конкретной цене | доля `promo_ride_max_price_share` от цены, Decimal, округление ВНИЗ; `min(discount, доля)` | ок (R3 — защищено, была дыра: `int(Decimal*Decimal)` заменили бы на `round` — ловится) |
| `price_kop` | 86–91 | Полная цена поездки, копейки | `price_final` или `price_estimate`, `max(…,0)*100` | ок |
| `discountable_rub` | 94–108 | С какой суммы вправе давать скидку | цена МИНУС компенсации водителю (бензин/допопции/зимняя дорога из `compensation.py`) | ок |
| `payable_kop` | 111–115 | Сколько пассажир реально платит | `price − discount`, `max(…,0)` — не уходит в минус | ок (R2) |
| `split_commission` | 118–126 | Как платформа оплачивает скидку | `(max(C−D,0), max(D−C,0))` — комиссия и компенсация водителю никогда не отрицательны | ок (R6) |
| `_redemption` | 131–135 | Достать свою PromoRedemption, опц. row-lock | — | ок |
| `available` | 138–162 | Есть ли непотраченная скидка у юзера | kind=taxi_ride, кампания активна и в окне, сумма>0, не занята живым заказом | ок |
| `note` | 165–173 | Честное RU/BA объяснение скидки | `discount_kop≤0` → None | ок |
| `preview` | 176–186 | Блок скидки для оценки цены | честные нули, если скидки нет | ок |
| `consume` | 191–238 | Списать скидку на заказ, зафиксировать | row-lock + атомарный CAS; идемпотентно; **сверяет `order.passenger_id == user_id`** | ок (R1, R4 — была найдена и исправлена ошибка B1, см. ниже) |
| `release` | 244–273 | Вернуть скидку, если поездка не состоялась | только для `cancelled`/`expired`, атомарный UPDATE по статусу+сумме>0 | ок (R5) |
| `release_ids` | 279–297 | Пакетный `release` по списку заказов (своя сессия) | идемпотентно, для системных закрытий (cleanup/taxi_worker) | ок |
| `reclamp` | 300–318 | Ужать скидку под новый потолок доли | только УМЕНЬШАЕТ, после пересчёта цены предзаказа | ок |

## Связи

Экраны: `PromoCodeScreen.kt` (`/promo/apply`, `/promo/mine`) — скидка видна по полю
`discount_kop`/`discount_available`. Эндпоинты: `routers/promo.py::promo_apply/promo_mine`,
`routers/instant.py` (`/instant/orders`, `/instant/schedule`, оценка, `/pay`, чек). Таблицы:
`PromoRedemption` (сумма и `used_order_id`), `InstantOrder.promo_discount_kop`, `LedgerEntry`
(kind=adj — компенсация), `CommissionDebt`. Фоновые задачи: `taxi_worker.close_stuck_orders`,
`cleanup.close_stale_orders` → `release_ids`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Скидку нельзя применить к чужой поездке (`consume` сверяет владельца заказа) | backend/tests/walk/l1_3/test_l1_3_promo_ride_money.py::test_consume_refuses_foreign_order, ::test_consume_still_works_for_own_order | да — M8 |
| R2 | Оплата пассажира никогда не уходит в минус | backend/tests/walk/l1_3/test_l1_3_promo_ride_money.py::test_payable_never_negative | да — M9 |
| R3 | Потолок «доля от цены» — целые рубли, округление ВНИЗ (в пользу платформы) | backend/tests/walk/l1_3/test_l1_3_promo_ride_money.py::test_share_cap_rounds_down_in_favor_of_platform | да — M10 |
| R4 | Скидка не списывается дважды даже под реальной гонкой на PostgreSQL | backend/tests/walk/l1_3/test_l1_3_promo_ride_money.py::test_concurrent_consume_only_one_order_gets_discount | да — M11 |
| R5 | Отменённая/просроченная поездка возвращает скидку, а не сжигает её | backend/tests/walk/l1_3/test_l1_3_promo_ride_money.py::test_release_returns_discount_on_cancel | да — M12 |
| R6 | Комиссия водителя никогда не уходит в минус (остаток — компенсацией) | backend/tests/walk/l1_3/test_l1_3_promo_ride_money.py::test_split_commission_never_goes_negative | да — M13 |

Дополнительно (без отдельной мутации, проверено существующим сюитом `tests/test_promo_taxi.py`,
68 тестов, все зелёные и на SQLite, и на PostgreSQL): полный денежный инвариант «водитель
получает ровно столько же, как без промокода» (`test_driver_gets_exactly_same_money_as_without_promo`,
`test_card_payment_keeps_driver_whole` — оба случая: скидка меньше и больше комиссии).

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| B1 | `consume(session, user_id, order)` не проверял, что переданный `order` ПРИНАДЛЕЖИТ `user_id`. Сегодня оба вызова (`routers/instant.py`, создание обычного заказа и предзаказа) безопасны — они сами создают заказ с `passenger_id=user.id` прямо перед вызовом. Но сама функция ничего не гарантировала: случайная будущая правка (например, списание скидки на заказ по id из другого источника) молча подарила бы чужую скидку чужому заказу — скидку одного пассажира «съел» бы заказ постороннего, который её не заслужил и не просил | `backend/tests/walk/l1_3/test_l1_3_promo_ride_money.py::test_consume_refuses_foreign_order` — создать пассажиру А скидку, вызвать `consume(session, owner_id, order_постороннего)` | Добавлена сверка `order.passenger_id != user_id` в начале `consume` (1 строка + расширенный докстринг) | тест падал: `assert 10000 == 0` (скидка ушла на чужой заказ) → после правки весь файл (6 тестов) и весь существующий `test_promo_taxi.py` (68 тестов, SQLite+PostgreSQL) зелёные |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M8 | Из `consume` снята сверка владельца заказа (регрессия к B1) | test_consume_refuses_foreign_order | KILLED |
| M9 | В `payable_kop` снят нижний предел `max(…,0)` | test_payable_never_negative | KILLED |
| M10 | В `cap_for_price` округление вниз (`int(Decimal*Decimal)`) заменено на `round()` | test_share_cap_rounds_down_in_favor_of_platform | KILLED |
| M11 | В `consume` сняты ОБЕ блокировки строк (`lock=True→False`) И условие «скидка ещё свободна» в атомарном захвате | test_concurrent_consume_only_one_order_gets_discount (PostgreSQL, с принудительной задержкой перед захватом для надёжного окна гонки) | KILLED |
| M12 | В `release` условие «заказ действительно не состоялся» (`status.in_(_DEAD)`) заменено на заведомо пустое | test_release_returns_discount_on_cancel | KILLED |
| M13 | В `split_commission` снят нижний предел комиссии водителя (`max(c-d,0)` → `c-d`) | test_split_commission_never_goes_negative | KILLED |

`python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.3.json` →
`MUTATIONS KILLED 17/17` (весь лист, включая эти 6).

## Остаток и ограничения

Все денежные правила файла защищены тестами и нарочными поломками (включая PostgreSQL для
гонки двойного списания). Дополнительно прогнан весь существующий денежный сюит файла
(`test_promo_taxi.py`, 68 тестов) на SQLite и на изолированном PostgreSQL — зелёный.

Подозрений, требующих отдельной проверки, не осталось: единственная найденная дыра (B1)
исправлена и защищена тестом.
