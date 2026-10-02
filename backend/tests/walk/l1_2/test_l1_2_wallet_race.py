"""leaf-1.2 — гонка двух запросов на оплату ОДНОГО и того же быстрого заказа.

Что было (найдено в этом обходе). `pay_instant_order` → `_pay_cashless` создаёт строку
Payment и ТОЛЬКО ПОТОМ, отдельным коммитом внутри `_start_yookassa`, помечает её
`method="yookassa"`. Между этими двумя коммитами есть окно: строка уже видна другим
запросам как pending, но ещё с "card"/"sbp" и без `provider_id`. Дедуп в `_pay_cashless`
такую строку не узнавал — условие требовало либо `provider_id`, либо `method == "yookassa"`.
Бронь (`booking_id`) от этого защищена (там `method` сразу "yookassa"), а быстрый заказ — нет.

Человеку это грозит так: открыл приложение на двух устройствах, или сеть лагнула и клиент
отправил «оплатить» повторно, пока первый запрос ещё в процессе. Второй запрос заводил
ВТОРОЙ Payment со своим Idempotence-Key — на настоящей ЮKassa это значит два РЕАЛЬНЫХ
списания за одну и ту же поездку (повторный запрос с ТЕМ ЖЕ ключом ЮKassa не задваивает,
с другим — задваивает).

Тест подставляет ровно то промежуточное состояние (строка первого запроса уже создана,
`_start_yookassa` её ещё не коснулась) и проверяет: второй вызов обязан найти и
использовать ИМЕННО эту строку, а не завести вторую.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import InstantOrder, Payment, UserRole

from test_ledger import _make_done_order

PRICE_RUB = 250
AMOUNT_KOP = PRICE_RUB * 100   # без промокода payable_kop == полная цена (см. app/promo_ride.py)


def _seed_inflight_payment(order_id: int, user_id: int) -> int:
    """То самое промежуточное состояние чужого запроса: строка уже вставлена и закоммичена,
    но `_start_yookassa` ещё не сходил (метод — то, что выбрал человек, не 'yookassa';
    provider_id пуст)."""
    with Session(engine) as session:
        payment = Payment(user_id=user_id, purpose="ride", order_id=order_id,
                          amount_kop=AMOUNT_KOP, method="card")
        session.add(payment)
        session.commit()
        session.refresh(payment)
        return payment.id


def _ride_payments(order_id: int) -> list[Payment]:
    with Session(engine) as session:
        return list(session.exec(
            select(Payment).where(Payment.order_id == order_id, Payment.purpose == "ride")
            .order_by(Payment.id)
        ).all())


def test_second_pay_call_reuses_in_flight_invoice_not_a_new_one(client, user_factory):
    driver = user_factory("L12RaceDriver", role=UserRole.driver)
    passenger = user_factory("L12RacePassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=PRICE_RUB)

    first_id = _seed_inflight_payment(order_id, passenger["id"])

    response = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"],
                           json={"method": "card"})
    assert response.status_code == 200, response.text

    rows = _ride_payments(order_id)
    assert [p.id for p in rows] == [first_id], (
        f"второй запрос завёл СВОЙ счёт {[p.id for p in rows]} вместо того, чтобы "
        f"использовать уже висящий {first_id} — на настоящей ЮKassa это два разных "
        "Idempotence-Key, то есть два реальных списания за одну и ту же поездку"
    )
    assert response.json()["payment_id"] == first_id
    assert rows[0].status == "succeeded"
    assert rows[0].method == "yookassa"

    with Session(engine) as session:
        order = session.get(InstantOrder, order_id)
        assert order.paid is True


def test_paying_an_already_paid_order_again_does_not_touch_anything(client, user_factory):
    """Идемпотентность верхнего гейта `if order.paid:` отдельно от защиты F1 внутри
    _activate_payment (test_l1_2_second_payment_refund_due.py): повторный вызов не должен
    даже создавать вторую строку Payment — он обязан остановиться на самом первом чтении
    заказа, не доходя ни до какого платежа вообще."""
    driver = user_factory("L12RepeatDriver", role=UserRole.driver)
    passenger = user_factory("L12RepeatPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=PRICE_RUB)

    first = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"], json={"method": "card"})
    assert first.status_code == 200 and first.json()["status"] == "succeeded", first.text

    second = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"], json={"method": "card"})

    assert second.status_code == 200 and second.json()["status"] == "already_paid", second.text
    rows = _ride_payments(order_id)
    assert len(rows) == 1, (
        f"повторная оплата уже оплаченного заказа создала {len(rows)} строк Payment вместо одной — "
        "гейт order.paid отключён, в дело вступает только более поздняя (и более дорогая) защита"
    )


def test_driver_is_credited_exactly_once_despite_the_race(client, user_factory):
    """Тот же сценарий, но смотрим на кошелёк водителя: гонка не должна начислить дважды.

    Комиссия (лесенка водителя) тут ни при чём — net = earn - fee зависит от процента,
    поэтому инвариант проверяем в записях ledger: РОВНО одна проводка earn и РОВНО одна fee,
    а не две (это отдельный уровень защиты внутри ledger.settle_instant_order — он СПАС
    кошелёк водителя даже притом, что на уровне Payment гонка уже завела вторую строку)."""
    from app.models import LedgerEntry

    driver = user_factory("L12RaceDriver2", role=UserRole.driver)
    passenger = user_factory("L12RacePassenger2")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=PRICE_RUB)
    _seed_inflight_payment(order_id, passenger["id"])

    response = client.post(f"/instant/orders/{order_id}/pay", headers=passenger["auth"],
                           json={"method": "card"})
    assert response.status_code == 200, response.text

    with Session(engine) as session:
        entries = session.exec(
            select(LedgerEntry).where(LedgerEntry.driver_id == driver["id"])
        ).all()
    kinds = sorted(e.kind.value for e in entries)
    assert kinds == ["earn", "fee"], (
        f"ожидали ровно одну пару earn+fee, получили {kinds} — "
        "гонка на уровне Payment задвоила начисление"
    )
    earn = next(e for e in entries if e.kind.value == "earn")
    assert earn.amount_kop == AMOUNT_KOP, "начисленная сумма должна быть ПОЛНОЙ ценой поездки"
