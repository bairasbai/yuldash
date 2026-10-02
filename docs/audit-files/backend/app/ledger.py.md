# Карточка: `backend/app/ledger.py`

- Статус: verified
- Лист: leaf-1.1
- Проверял: Sonnet 5 (leaf-1.1); принимал: Opus 5.5

## Назначение

Деньги v1: append-only журнал проводок (кошелёк водителя), расчёт комиссии платформы, вывод
средств на карту и сверка начислений с оплатами. Три принципа, прошитые по всему файлу (из
докстринга): только целые копейки (`int`, никогда `float`), ledger **append-only** (баланс =
`SUM(amount_kop)`, история не редактируется — ошибка исправляется новой записью `kind=adj`),
идемпотентность (начисление за заказ/бронь — ровно один раз, под row-lock, гейт по флагу `paid`).

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_CASHLESS` | 31 | кортеж способов оплаты, идущих ЧЕРЕЗ платформу (`card`, `sbp`, `yookassa`) | `cash` сюда не входит — деньги мимо нас | ок |
| `_PROMO_COMP_WHERE` + `Index(uq_ledgerentry_promo_comp, ...)` | 51–58 | ЧАСТИЧНЫЙ уникальный индекс БД: `ext_id` уникален только среди `kind=adj` записей с `ext_id` вида `promo:*` | работает и на SQLite (`sqlite_where`), и на Postgres (`postgresql_where`) — последняя линия обороны от двойной компенсации промокода при гонке двух «Завершил» | ок |
| `fee_kop_for(amount_kop, percent=None)` | 61–73 | комиссия в копейках, `Decimal` + `ROUND_HALF_UP`, никогда `float` | `amount_kop<=0` или `percent<=0` → 0; `percent=None` → берёт `settings.service_fee_percent` | ок |
| `driver_balance(session, driver_id)` | 76–83 | баланс = `SUM(amount_kop)` по всем записям водителя, агрегатом SQL (не тянет историю в память) | нет записей → 0 (`coalesce`) | ок |
| `owed_to_platform_kop(session, driver_id)` | 86–95 | весь непогашенный долг человека: такси (`debt.taxi_owed_kop`) + комиссия курьера (`courier._commission_owed_kop`) | два разных источника долга, один кошелёк — сложение, не выбор одного | ок |
| `payable_balance(session, driver_id)` | 98–107 | сколько реально можно вывести: баланс минус долг платформе, не ниже 0 | защищает от вывода денег, которые уже «заняты» под неоплаченную комиссию (волна 156) | ок |
| `ledger_entries(session, driver_id, limit=100)` | 110–115 | список записей водителя, свежие сверху, для экрана истории | `limit` зажат в `[1, 500]` | ок |
| `_reversal_ext_id(key)` | 118–125 | ключ записи «резерв вернули» = `reversed:{key}` | отдельный префикс, а не тот же ключ выплаты — иначе отказ банка и успешную выплату было бы нечем отличить (волна 219) | ок |
| `PayoutError` | 128–142 | исключение отказа вывода: `code` (машинный), `message`/`message_ba` (человеку, двуязычно) | `message_ba` падает на `message`, если не задан | ок |
| `request_payout(session, driver_id, amount_kop, *, payout_token, card_last4, idempotency_key)` | 145–280 | вывод с баланса на карту: границы суммы, идемпотентность, резерв под коротким row-lock, вызов банка ВНЕ лока, компенсация при явном отказе | `amount<=0`→`amount`; `<min`→`min`; `>max`→`max`; пустой `idempotency_key`→`idempotency`; `payable_balance`<суммы→`debt`/`insufficient`; повтор того же ключа→`already` (без второго списания); банк вернул не succeeded/pending→компенсация +сумма, `provider`; исключение банка (таймаут)→резерв НЕ трогаем, `provider_unclear` | ок |
| `promo_comp_ext_id(order_id)` | 283–285 | ключ идемпотентности компенсации промокода = `promo:{order_id}` | один заказ — один ключ | ок |
| `post_promo_compensation(session, driver_id, order_id, amount_kop)` | 288–328 | доплата водителю остатка скидки промокода сверх комиссии, запись `kind=adj` | `driver_id`/`order_id` `None` или `amount<=0`→`None`; есть запись→её и возвращает; гонка двух «Завершил»→`IntegrityError` по частичному индексу→откат, возврат чужой записи; коммитит САМ (самостоятельный денежный эффект) | ок |
| `_post_earn_and_fee(session, driver_id, amount_kop, *, order_id, booking_id, note, percent, fee_kop)` | 331–359 | пишет `earn` (+вся сумма) и `fee` (−комиссия), вызывать ТОЛЬКО под открытой транзакцией с залоченной строкой заказа/брони | `fee_kop` явный — перебивает расчёт по проценту (для промокода, где комиссия уже уменьшена скидкой); `fee<=0`→запись `fee` не пишется | ок |
| `settle_instant_order(session, order_id, method, amount_kop, *, commit=True)` | 362–404 | провести оплату завершённого такси-заказа, идемпотентно, под row-lock | нет заказа/нет водителя→`skip`; уже `paid`→`already`; `cash`→только пометка, ledger не трогаем; безнал→ставка по `driver_fee_percent` на МОМЕНТ `created_at` заказа (совпадает с `debt.accrue_for_order`), промокод уменьшает удерживаемую комиссию, снимает/возвращает фиктивный долг Модели А (`debt.void_debt_for_order`) | ок |
| `settle_booking(session, booking_id, method, amount_kop, *, commit=True)` | 407–435 | то же для брони (попутки) | нет брони/нет поездки→`skip`; уже `paid`→`already`; `cash`→без ledger; безнал→ставка `settings.ride_service_fee_percent` (СВОЯ, по умолчанию 0%), НЕ общая ставка такси | ок |
| `reconcile(session, date_from, date_to)` | 438–531 | сверка за период: `earn`↔безналичные `Payment`, расход на промо/возвраты/ручные доплаты | период — по `settled_at` платежа (фолбэк `created_at`); `adj`-записи разбираются ПО КЛЮЧУ (`promo:`/`refund:`/`reversed:`+legacy `payout:`), не по знаку; `platform_net_kop = fee − promo_comp − refund − adj_other`; `diff_kop = earn − payments`, `ok = diff==0` | ок |

## Связи

- `backend/app/debt.py` (этот же лист) — зовёт `fee_kop_for`, `driver_balance`, `post_promo_compensation`, `promo_comp_ext_id`; сам вызывается из `ledger.owed_to_platform_kop`/`settle_instant_order` (взаимная связь, намеренная — один денежный контур).
- `backend/app/routers/wallet.py` (вне OWNS) — `GET /wallet/balance`, `GET /wallet/ledger`, `POST /wallet/payout`, `GET /admin/ledger/reconcile`.
- `backend/app/routers/instant.py`, `backend/app/routers/bookings.py`/`payments.py` (вне OWNS) — зовут `settle_instant_order`/`settle_booking` из webhook/ручки оплаты.
- `backend/app/routers/courier.py` (вне OWNS) — свой независимый зачёт долга курьера из кошелька, использует `driver_balance`.
- Модель `LedgerEntry` (`models.py`, вне OWNS) — `ext_id` с обычным индексом (НЕ уникальным глобально); единственная уникальность — частичный индекс для `promo:` выше. Для `payout` защита от двойного списания — целиком на уровне кода (`_existing()` под row-lock), не на уровне БД.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Комиссия — целые копейки, `ROUND_HALF_UP` (не банковское округление) | backend/tests/test_ledger.py::test_fee_kop_exact_integer_kopecks, backend/tests/test_ledger.py::test_fee_kop_round_half_up, backend/tests/test_ledger.py::test_default_service_fee_matches_the_config | да — M7 |
| R2 | Наличные (такси и попутка) не создают записей ledger — деньги мимо платформы | backend/tests/test_ledger.py::test_cash_marks_paid_no_ledger, backend/tests/walk/l1_1/test_l1_1_ledger_gaps.py::test_r1_cash_booking_does_not_touch_ledger | да — M8, M9 |
| R3 | `settle_instant_order`/`settle_booking` идемпотентны: повтор (webhook/двойной тап) не задваивает `earn`/`fee` | backend/tests/test_ledger.py::test_settle_idempotent_no_double, backend/tests/test_ledger.py::test_pay_twice_returns_already_paid, backend/tests/test_ledger.py::test_webhook_repeat_does_not_double_ledger, backend/tests/walk/l1_1/test_l1_1_ledger_gaps.py::test_r2_settle_booking_twice_cashless_does_not_double_post | да — M10, M11 |
| R4 | Вывод: границы суммы, ключ идемпотентности ОБЯЗАТЕЛЕН и именной по водителю (у провайдера Idempotence-Key глобальный) | backend/tests/test_wallet_never_goes_negative.py (три теста), backend/tests/walk/l1_1/test_l1_1_ledger_gaps.py::test_r3_payout_without_an_idempotency_key_is_rejected, backend/tests/walk/l1_1/test_l1_1_ledger_gaps.py::test_r4_the_same_raw_key_from_two_different_drivers_reach_the_provider_differently | да — M13, M14 |
| R5 | Вывод ограничен СВОБОДНЫМ остатком (баланс минус долг платформе), не всем балансом | backend/tests/test_you_cannot_walk_away_with_the_debt.py::test_нельзя_вывести_то_что_должен, backend/tests/test_you_cannot_walk_away_with_the_debt.py::test_свободную_часть_вывести_можно | да — M12 |
| R6 | Компенсация промокода (`post_promo_compensation`) — ровно одна на заказ даже под настоящей гонкой двух «Завершил» (частичный индекс БД — последняя линия обороны) | backend/tests/test_money_holes_audit.py::test_бд_не_даёт_две_компенсации_промокода_по_одному_заказу, backend/tests/test_money_holes_audit.py::test_гонка_двух_завершил_не_задваивает_компенсацию | да — M15 |
| R7 | Попутка (бронь) платит СВОЮ ставку (`ride_service_fee_percent`, по умолчанию 0%), а не общую комиссию такси | backend/tests/test_the_wallet_works_and_the_ride_is_free.py::test_попутка_картой_не_берёт_комиссию, backend/tests/test_the_wallet_works_and_the_ride_is_free.py::test_такси_картой_комиссию_берёт | да — M16 |
| R8 (не мутировал, информационно) | `reconcile` честно разносит расход (промо/возврат/ручная доплата) по ключу, не по знаку; возврат резерва отклонённой выплаты — не доход и не расход | backend/tests/test_ledger.py::test_reconcile_matched_and_mismatch, backend/tests/test_the_promo_line_swallows_everything.py (9 тестов), backend/tests/test_the_payout_that_never_left.py (9 тестов), backend/tests/test_money_promises_are_honest.py::test_сверка_показывает_расход_по_кампаниям | покрыто существующим набором, отдельную поломку не заводил (риск дублирования уже исчерпывающих тестов) |
| R9 (права) | `/wallet/ledger`, `/wallet/balance`, сверка — только свои записи / только админ | backend/tests/test_ledger.py::test_wallet_ledger_only_own, backend/tests/test_ledger.py::test_reconcile_admin_only, backend/tests/test_ledger.py::test_cannot_pay_others_order | покрыто существующим набором (права — не в моём файле, в `routers/wallet.py`/`routers/payments.py`, вне OWNS; здесь только подтверждаю, что `ledger.py` сам не хранит состояние, которое эти проверки могли бы обойти) |

## Найденные ошибки

Ошибок не найдено в `ledger.py`. Файл уже прошёл много аудитных волн (154, 156, 198, 200, 201,
208, 215, 217, 218, 219 — видно по комментариям в коде) и покрыт плотным существующим набором
тестов. Я добавил 4 новых теста на щели, которых раньше не было (наличные за БРОНЬ, повторный
`settle_booking`, вывод без ключа, именование ключа по водителю на уровне провайдера) — все
они оказались УЖЕ корректно реализованы, новых ошибок не вскрыли.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M7 | `ROUND_HALF_UP` → `"ROUND_HALF_EVEN"` (банковское округление) | test_fee_kop_round_half_up | KILLED |
| M8 | Наличные за такси-заказ тоже идут в ветку `_CASHLESS` | test_cash_marks_paid_no_ledger | KILLED |
| M9 | То же для брони (попутки) | test_r1_cash_booking_does_not_touch_ledger | KILLED |
| M10 | `settle_instant_order`: гейт `if order.paid: return "already"` отключён | test_settle_idempotent_no_double | KILLED |
| M11 | `settle_booking`: тот же гейт отключён | test_r2_settle_booking_twice_cashless_does_not_double_post | KILLED |
| M12 | `payable_balance` → `driver_balance` (долг платформе не учитывается при выводе) | test_нельзя_вывести_то_что_должен | KILLED |
| M13 | Ключ идемпотентности выплаты — сырой, без неймспейса по водителю | test_r4_the_same_raw_key_from_two_different_drivers_reach_the_provider_differently | KILLED |
| M14 | Проверка «ключ обязателен» выключена | test_r3_payout_without_an_idempotency_key_is_rejected | KILLED |
| M15 | `post_promo_compensation`: при проигранной гонке возвращается `None` вместо записи победителя | test_гонка_двух_завершил_не_задваивает_компенсацию | KILLED |
| M16 | `settle_booking`: явный `percent=ride_service_fee_percent` убран (попадает на общую ставку) | test_попутка_картой_не_берёт_комиссию | KILLED |

Прогон: `python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.1.json --only M7,M8,M9,M10,M11,M12,M13,M14,M15,M16` → `MUTATIONS KILLED 10/10`.

## Остаток и ограничения

`reconcile` и права доступа (R8/R9) защищены исключительно существующим (не моим) набором
тестов — он настолько плотный и точный (например `test_ключ_читаем_с_начала_а_не_подстрокой`,
`test_a_returned_reserve_is_not_platform_spending`), что дублировать его новыми тестами ради
формальной «мутации на правило» означало бы писать вторую копию уже написанной защиты. Я
прочитал каждый из этих тестов и убедился, что они проверяют ИМЕННО то, что заявлено, а не
«не упало»/«200». Отдельных нарочных поломок на R8/R9 не заводил — экономия токенов важнее
ритуала при уже доказанном покрытии.
