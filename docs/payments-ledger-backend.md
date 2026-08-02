# 💰 «Деньги v1» — статус БЭКЕНДА (Фаза 3, D3)

> Ветка `feat/payments-ledger` (от `feat/instant-order`). Реализация D3 **v1** из `product-plan-2026-07.md`
> (спека живёт на ветке `docs/product-plan`). Этот файл — «что сделано / что осталось», лид сводит в product-plan при мерже.
> **v1 = БЕЗ hold/capture** (двухстадийный платёж — v2, позже). Пассажир платит ПОСЛЕ поездки.

## ✅ Что сделано (бэкенд целиком, с тестами)

### 1. Ledger — кошелёк водителя (append-only)
- Модель `LedgerEntry(id, driver_id, order_id NULL, booking_id NULL, kind[earn|fee|payout|adj], amount_kop, created_at, note)`.
- **Историю денег НЕ редактируем и НЕ удаляем.** Баланс = `SUM(amount_kop)` (никакого «изменяемого баланса»). Ошибку правим отдельной записью `kind=adj`.
- Деньги — **только целые копейки** (`int amount_kop`), никаких float. `earn` > 0; `fee`/`payout` < 0; `adj` любой знак.
- `app/ledger.py`: `driver_balance()`, `ledger_entries()`, `fee_kop_for()`, `settle_instant_order()`, `settle_booking()`, `reconcile()`.

### 2. Комиссия сервиса
- `service_fee_percent` в `config.py` — **дефолт 15%**, помечен «УТОЧНИТ АЛЕКСАНДР» (эталон ЯндексТакси/inDrive ~10–25%).
- `fee_kop_for(amount_kop, percent)` — считает через `Decimal` ROUND_HALF_UP → **предсказуемые целые копейки** без float-дрейфа даже на «некруглых» процентах (12.5%).
- Успешная безналичная оплата → в ledger **две записи**: `earn` (+вся сумма водителю) и `fee` (−комиссия). Баланс за поездку = earn − fee.

### 3. Оплата после done (через СУЩЕСТВУЮЩУЮ ЮKassa-инфру)
- `POST /instant/orders/{order_id}/pay` — оплатить завершённый быстрый заказ.
- `POST /bookings/{booking_id}/pay` — оплатить завершённую бронь плановой поездки.
- Тело: `{method: "cash" | "card" | "sbp"}`.
- Права: платить может **только пассажир-владелец** (анти-IDOR → 403), **только статус done** (иначе 409).
- **Безнал** (card/sbp) → `payments.create_payment` (ЮKassa). mock/dev → `succeeded` сразу (активируем и начисляем); прод-yookassa → `confirmation_url`, начисление придёт по **webhook**.
- **Наличные** (cash) → заказ/бронь помечаются `paid=True, payment_method="cash"`, но **ledger НЕ трогаем** — деньги идут мимо нас (реальность РБ).
- **Идемпотентность (деньги критичны):** `payments._activate_payment` фиксирует `payment.status=succeeded`, затем `ledger.settle_*` под **row-lock** гейтит по флагу `paid` заказа/брони. Повторный webhook → `settle` возвращает `"already"`, ledger НЕ задваивается (двойная защита: и на уровне payment, и на уровне заказа).

### 4. Сверка (админ)
- `GET /admin/ledger/reconcile?days=N` (или `date_from`/`date_to` ISO) — **только админ**.
- Считает за период: `SUM(earn)` (начислено), `SUM(fee)` (комиссия), `SUM(успешных безналичных Payment ride/booking)` (прошло через ЮKassa).
- Инвариант: каждая безналичная оплата поездки = ровно один `earn` на ту же сумму → `diff = earn − payments` должно быть **0**. `diff≠0` → расхождение, `ok=false` (алерт вешает Александр). Наличные в сверку не входят.

### 5. Кошелёк водителя
- `GET /wallet/balance` — баланс (SUM ledger) по СВОЕМУ токену.
- `GET /wallet/ledger` — история записей, **всегда по своему id** (чужой ledger не виден — анти-IDOR).

## 🧪 Тесты
`backend/tests/test_ledger.py` — **19 тестов**. Полный прогон: **`pytest -q` → 225 passed, 1 skipped**.
Покрыто: комиссия (целые копейки, ROUND_HALF_UP), начисление earn+fee и баланс=earn−fee, эндпоинты кошелька, оплата только done (все не-done статусы → 409), наличные не двигают ledger, идемпотентность (повторный settle + повторный webhook → начисление ровно один раз), append-only баланс=SUM, сверка (матч → diff стабилен; orphan-earn → diff ловит, ok=false), сверка только админ, анти-IDOR (чужой заказ не оплатить → 403; чужой ledger не увидеть), оплата брони плановой поездки.

Alembic: `upgrade head` — **оба пути**: baseline (create_all → no-op) и прод (создаёт `ledgerentry` c 5 индексами + добавляет колонки в `payment`/`instantorder`/`booking`); идемпотентно (повторный прогон чист).

## ⚙️ Конфиг / модель данных
- Новый ключ `config.py`: `service_fee_percent = 15.0` (**уточнит Александр**).
- Миграция `p3_ledger` (down `p2_instant_order`): таблица `ledgerentry` + колонки `payment(order_id, booking_id, method)`, `instantorder(paid, payment_method)`, `booking(paid, payment_method)`, индекс `payment.created_at`.
- Секреты ЮKassa (`YOOKASSA_SHOP_ID`/`YOOKASSA_SECRET_KEY`) — **только в `.env`, НЕ в git** (не трогали, переиспользуем инфру F21).

## 🔜 Что осталось (не входит в эту ветку)
- **UI оплаты** (Android): экран «Оплатить поездку» после done (карта/СБП/наличные), экран кошелька водителя (баланс + история). Отдельная ветка следом.
- **v2 — hold→capture** («безопасная сделка»: холд на карте при accept → капчер по done) + **выплаты водителям** (payout: v1 вручную по реестру из ledger, v2 — API ЮKassa).
- **ЮKassa-чеки 54-ФЗ** для оплаты поездок (сейчас `_receipt` есть для boost/донатов — распространить на ride/booking при включении реального приёма).
- Александру: **финальный процент комиссии** (`service_fee_percent`), **оферта + тарифы комиссии** (юр.шляпа, на подпись), ключи ЮKassa в проде для приёма оплаты поездок, порог алерта на `reconcile.diff`.

## 🌐 Переводы (RU/BA) — на проверку Александру
Серверные строки (ошибки оплаты) сейчас только RU — это бэкенд-тексты, не UI. Двуязычие оплаты — на UI-слое (`appText`) при реализации экранов; черновой башкирский соберём в `tasks.md` на этапе UI.
