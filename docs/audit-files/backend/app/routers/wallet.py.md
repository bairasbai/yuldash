# Карточка: `backend/app/routers/wallet.py`

- Статус: verified
- Лист: leaf-1.2
- Проверял: Sonnet 5 (leaf-1.2); принимал: Opus 5.5

## Назначение

Деньги v1 (Фаза 3, D3): оплата ЗАВЕРШЁННОЙ поездки/брони пассажиром (карта/СБП через ЮKassa
или «наличные» мимо нас) + кошелёк-ledger водителя (баланс, история, вывод на карту) + сверка.
Безнал начисляет водителю через append-only ledger (earn − комиссия); наличные идут мимо нас.
Анти-IDOR последовательно: платить может ТОЛЬКО пассажир-владелец, кошелёк/историю видит
ТОЛЬКО сам водитель по своему токену, сверка и реестр выплат — только админ.

**Найденная и исправленная в этом листе ошибка (R1)** — гонка двух запросов на оплату ОДНОГО
и того же быстрого заказа создавала ВТОРОЙ счёт вместо переиспользования первого: на настоящей
ЮKassa это значило бы два реальных списания за одну поездку. См. раздел «Найденные ошибки».

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_guard_method` | 45–47 | Способ оплаты — только `cash`/`card`/`sbp` | 400 на прочее | ок |
| `_cancel_own_pending_cashless` | 50–66 | Оплата налом закрывает СВОЙ висящий безнал-платёж на тот же заказ/бронь | Иначе живая ссылка ЮKassa пережила бы нал — пассажир заплатил бы дважды (нал + поздняя карта) | ок |
| `_pay_cashless` | 69–~158 | Общий безналичный поток (карта/СБП) через ЮKassa: dev/mock → succeeded сразу; yookassa → confirmation_url | Прод+не-yookassa → 503; дедуп pending ПО ЗАКАЗУ/БРОНИ (не создаёт второй счёт на тот же заказ); booking_id-путь освобождает lock Booking перед внешним HTTP | исправлено (было ❌ — см. «Найденные ошибки», R1) |
| `pay_instant_order` | 156–182 `POST /instant/orders/{id}/pay` | Пассажир платит за ЗАВЕРШЁННЫЙ быстрый заказ | Только владелец (403), только `done` со статусом/водителем, идемпотентно (`already_paid`); сумма — `promo_ride.payable_kop` (цена минус промо-скидка) | ок |
| `_booking_amount_kop` | 186–191 | Сумма к оплате брони: `pay_amount` (если ЕСТЬ, в т.ч. явный 0) иначе `price` | `amount_kop<=0` → 409 «нет суммы к оплате» — явный 0 НЕ подменяется ценой (QA-B07-002) | ок |
| `pay_booking` | 194–~218 `POST /bookings/{id}/pay` | Пассажир платит за ЗАВЕРШЁННУЮ бронь | Row-lock на Booking сразу при входе (анти-гонка «правка цены vs оплата», QA-B07-003); только владелец, только `done`, идемпотентно | ок |
| `wallet_balance` | `GET /wallet/balance` | Баланс + сколько зарезервировано под долг платформе + сколько реально свободно | `payable_kop = max(bal-долг, 0)`; `reserved_kop` не может быть больше, чем реально лежит на балансе | ок |
| `wallet_ledger` | `GET /wallet/ledger` | История начислений/комиссий/выплат — ТОЛЬКО свои (по токену) | `limit` зажат [1, 500] | ок |
| `admin_ledger_reconcile` | `GET /admin/ledger/reconcile` | Сверка SUM(earn)↔SUM(успешных безналичных Payment) за период — ТОЛЬКО админ | `days` зажат [1, 366]; неверный ISO-8601 → 400, не 500 | ок |
| `_payout_profile` | — | Профиль водителя для выплат | — | ок |
| `wallet_payout_status` | `GET /wallet/payout/status` | Доступны ли выплаты + баланс + сохранённые реквизиты | Границы (`min_kop`/`max_kop`) с сервера, не хардкод клиента | ок |
| `save_payout_requisite` | `POST /wallet/payout/requisite` | Сохранить карту для выплат — ТОЛЬКО последние 4 цифры + токен провайдера | Полный номер (`card_number`) НЕ сохраняется и не логируется, уходит из памяти с концом запроса; < 12 цифр и без last4 → 400 | ок |
| `wallet_payout` | `POST /wallet/payout` | Вывод с баланса на карту (Модель Б, выкл. по умолчанию) | `payouts_ready=False` → мягкое 503 «скоро» (не 500); без реквизита → 400; дальше — `ledger.request_payout` (идемпотентность/границы/резерв — там, вне зоны) | ок |
| `admin_payouts` | `GET /admin/payouts` | Реестр выплат (ledger kind=payout), с пометкой отклонённых банком | Только админ; отменённая выплата остаётся записью, но ПОМЕЧЕНА `reversed` | ок |

## Связи

- `ledger.py` (вне зоны, но тесно связан): `driver_balance`, `ledger_entries`,
  `owed_to_platform_kop`, `payable_balance`, `reconcile`, `request_payout`, `PayoutError`,
  `settle_instant_order`/`settle_booking` (через `_activate_payment`).
- `routers/payments.py`: `_activate_payment`, `_start_yookassa`, `_sync_provider_status` —
  используются напрямую (импорт).
- `promo_ride.py`: `payable_kop` — сумма к оплате минус промо-скидка.
- `debt.py`/`routers/courier.py`: `owed_to_platform_kop` суммирует долг такси + комиссию курьера.
- Android: `ApiClient.kt` — `getPayoutStatus`/`savePayoutRequisite`/`requestPayout`/`payBooking`/
  `payInstantOrder`; DTO `PayoutStatusDto`/`PayoutResultDto`/`PayTripResultDto`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Гонка двух запросов на оплату ОДНОГО заказа не заводит два счёта/два реальных списания (НАЙДЕНО И ИСПРАВЛЕНО в этом листе) | `backend/tests/walk/l1_2/test_l1_2_wallet_race.py::test_second_pay_call_reuses_in_flight_invoice_not_a_new_one`, `::test_driver_is_credited_exactly_once_despite_the_race` | да — M24 |
| R2 | Явная сумма 0 (pay_amount) НЕ подменяется ценой поездки; платёж с 0 блокируется, а не списывает полную цену | `backend/tests/test_booking_payment_agreement_amount.py::test_explicit_zero_agreement_cannot_charge_the_original_positive_price`, `::test_legacy_null_agreement_uses_original_price` | да — M25 |
| R3 | Повторная оплата уже оплаченного заказа — идемпотентна (`already_paid`, второй раз не начисляет) | `backend/tests/test_ledger.py::test_pay_twice_returns_already_paid` | да — M26 |
| R4 | Анти-IDOR: чужой заказ не оплатить (владелец ≠ плательщик → 403, начисления нет) | `backend/tests/test_ledger.py::test_cannot_pay_others_order`, `backend/tests/test_booking_payment_agreement_amount.py::test_only_actual_passenger_can_pay_agreed_booking` | да — M27 |
| R5 | Сверка ledger↔оплат — только админ | `backend/tests/test_ledger.py::test_reconcile_admin_only` | да — M28 |
| R6 | Вывод выключен по умолчанию — мягкое 503 «скоро» (не 500), включается только флагом+реквизитом | `backend/tests/test_payouts.py::test_payout_disabled_by_default`, `::test_status_disabled_by_default`, `::test_payout_requires_requisite` | да — M29 |
| R7 | Номер карты не хранится — только последние 4 цифры + токен провайдера (приватность PAN) | `backend/tests/test_payouts.py::test_requisite_stores_only_last4` | да — M30 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| BUG-l1_2-01 | Пассажир мог попасть в окно гонки: `/instant/orders/{id}/pay` создаёт строку платежа и ТОЛЬКО ПОТОМ, отдельным commit внутри `_start_yookassa`, помечает её `method="yookassa"`. Между этими двумя commit дедуп «уже есть висящий платёж — используй его» не узнавал СВОЮ же свежую строку (требовал либо `provider_id`, либо `method=="yookassa"`, которых у неё ещё не было). Двойной тап «Оплатить» или ретрай клиента при лагнувшей сети в этом окне заводил ВТОРОЙ `Payment` с ДРУГИМ Idempotence-Key — на настоящей ЮKassa это два независимых реальных списания за одну и ту же поездку. У оплаты брони такого окна нет (там `method` выставляется в `"yookassa"` сразу при создании строки) — поэтому поломка касалась только быстрых заказов. | `backend/tests/walk/l1_2/test_l1_2_wallet_race.py` сеет строку-«в процессе» (pending, method="card", без provider_id) и зовёт `/instant/orders/{id}/pay` ещё раз — до правки заводилась вторая строка (`[1, 2]` вместо `[1]`) | `_pay_cashless`: условие дедупа `if existing and (booking_id is not None or existing.provider_id or existing.method == "yookassa"):` → `if existing:` — ЛЮБАЯ найденная по (user, цель, заказ/бронь, pending) строка теперь считается «уже идущей попыткой» и переиспользуется (как и было задумано комментарием над кодом: «уже есть висящий платёж → возвращаем его, НЕ создаём второй»). У брони условие и раньше было всегда истинным — поведение не меняется, подтверждено 6/6 тестами `test_pay_agreement_payment_concurrency.py` (настоящие PostgreSQL-блокировки) | `test_second_pay_call_reuses_in_flight_invoice_not_a_new_one`: до — `assert [1, 2] == [1]` падает (две строки); после — зелёный (одна строка, статус succeeded, provider-метод "yookassa"). Побочный эффект (кошелёк водителя) проверен отдельно: `test_driver_is_credited_exactly_once_despite_the_race` — ledger и ДО правки содержал ровно одну пару earn+fee (независимая защита `order.paid`-флага внутри `ledger.settle_instant_order`, вне зоны этого листа, уже спасала деньги водителя), но счёт у пассажира задваивался. |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M24 | `_pay_cashless`: откат исправления BUG-l1_2-01 (дедуп снова не узнаёт висящую строку заказа) | test_second_pay_call_reuses_in_flight_invoice_not_a_new_one (SQLite baseline + **PostgreSQL** replay) | KILLED |
| M25 | `_booking_amount_kop`: `is not None` → truthy (0 снова подменяется ценой) | test_explicit_zero_agreement_cannot_charge_the_original_positive_price | KILLED |
| M26 | `pay_instant_order`: гейт `order.paid` отключён (`and False`) | test_pay_twice_returns_already_paid | KILLED |
| M27 | `pay_instant_order`: анти-IDOR инвертирован (`!=` → `==`) | test_cannot_pay_others_order | KILLED |
| M28 | `admin_ledger_reconcile`: админ-гейт инвертирован | test_reconcile_admin_only | KILLED |
| M29 | `wallet_payout`: гейт `payouts_ready` инвертирован | test_payout_disabled_by_default | KILLED |
| M30 | `save_payout_requisite`: сохраняется весь номер карты вместо last4 | test_requisite_stores_only_last4 | KILLED |

## Остаток и ограничения

M24 прогнан и на SQLite, и на изолированном PostgreSQL (`walk_l1_2_x`/`walk_l1_2_conc`,
очищены `dropdb` после) — поймана в обоих случаях. Полная PostgreSQL-гонка НАСТОЯЩИМИ
параллельными потоками через HTTP (как делает `test_pay_agreement_payment_concurrency.py` для
брони) для быстрых заказов не писал: вместо неё — детерминированное воспроизведение точного
промежуточного состояния БД (строка уже вставлена, `_start_yookassa` её ещё не коснулась),
которое ЛЮБАЯ реальная гонка двух потоков обязана пройти через эту же точку — этого достаточно,
чтобы доказать дефект дедупа и фикс, но не измеряет вероятность/частоту реальной гонки под
нагрузкой. Полный набор `test_pay_agreement_payment_concurrency.py` (6/6) прогнан на СВОЕЙ
отдельной PostgreSQL-базе (файл сам требует отдельного прогона — docstring: «Root alone
launches this file»); вместе с остальными ~500 тестами листа он конфликтует по счётчику
тестовых телефонов, это особенность межфайловой изоляции тестов, не моей правки.
