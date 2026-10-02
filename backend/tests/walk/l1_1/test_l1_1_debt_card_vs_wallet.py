"""leaf-1.1 · F2 (найдено независимым ревью Opus, 2026-10-02): долг такси, который водитель
начал оплачивать КАРТОЙ (ЮKassa), пока счёт висит в статусе pending, параллельно гасился
КОШЕЛЬКОМ — settle_debt_from_wallet ничего не знал о выставленном счёте. Деньги платформы
(например, возврат/компенсация) приходят в кошелёк в это окно → кошелёк закрывает ТЕ ЖЕ
долги → когда банк подтверждает перевод, водитель уже заплатил дважды за одну комиссию.

У курьера эта дверь закрыта волной 218 (снимок ID доставок на Payment.tier). У такси её не
было. Исправление — зеркало курьерского: `taxi_debt_snapshot`/`make_taxi_debt_snapshot_tier`/
`taxi_debt_snapshot_ids` в debt.py, снимок пишется при создании счёта в routers/debt.py,
`settle_debt_from_wallet` исключает долги из снимка, пока счёт `pending`.

R1 — пока счёт на оплату долга картой висит, кошелёк НЕ трогает долги из его снимка.
R2 — долг, НАЧИСЛЕННЫЙ ПОСЛЕ выставления счёта (не входит в снимок), кошелёк гасит как обычно.
R3 — как только счёт ушёл из pending (отменён/не найден), новый вызов снова гасит всё как раньше.
R4 — повторное нажатие «оплатить» с висящим счётом отдаёт ТУ ЖЕ сумму (не протухает), потому
     что кошелёк больше не может тронуть снимок, пока счёт жив.
"""
from sqlmodel import Session, select

from app import debt as debt_mod
from app.config import settings
from app.db import engine
from app.models import CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, LedgerEntry, LedgerKind, Payment, UserRole
from app.timeutil import utcnow


def _водитель_с_двумя_долгами(user_factory, имя: str, по: int = 10_000):
    водитель = user_factory(имя, role=UserRole.driver)
    пассажир = user_factory(имя + "Пас")
    ids = []
    with Session(engine) as s:
        for i in range(2):
            o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                             from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                             status=InstantOrderStatus.done, price_estimate=200, price_final=200,
                             paid=True, done_at=utcnow())
            s.add(o)
            s.commit()
            s.refresh(o)
            d = CommissionDebt(driver_id=водитель["id"], order_id=o.id, amount_kop=по,
                               week="2026-W40", status=DebtStatus.unpaid, created_at=utcnow())
            s.add(d)
            s.commit()
            s.refresh(d)
            ids.append(d.id)
    return водитель, ids


def _pending_provider(monkeypatch):
    def create_payment(amount_kop, description, metadata, customer_phone="", idempotence_key=""):
        return {"provider_id": "yk_f2_test", "confirmation_url": "https://pay.example/yk_f2_test",
                "status": "pending", "mock": False}

    def fetch_payment(provider_id):
        # Без реальных ключей ЮKassa fetch_payment по умолчанию мокается как "succeeded"
        # (app/payments.py) — для сценария «счёт ещё висит» нужен настоящий pending.
        return {"status": "pending", "metadata": {}, "confirmation_url": "https://pay.example/yk_f2_test"}

    monkeypatch.setattr("app.routers.payments.create_payment", create_payment)
    monkeypatch.setattr("app.payments.fetch_payment", fetch_payment)
    monkeypatch.setattr("app.routers.payments.fetch_payment", fetch_payment)
    monkeypatch.setattr(settings, "payments_provider", "yookassa")


def test_r1_wallet_does_not_touch_debts_billed_by_a_pending_card_invoice(client, user_factory, monkeypatch):
    _pending_provider(monkeypatch)
    drv, (d1, d2) = _водитель_с_двумя_долгами(user_factory, "F2Карта")

    счёт = client.post("/driver/debt/paid", headers=drv["auth"]).json()
    assert счёт["status"] == "pending"
    assert счёт["amount_kop"] == 20_000

    # В окно, пока счёт висит, в кошелёк приходят деньги платформы (возврат/компенсация).
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=20_000,
                          note="тест: компенсация пришла, пока счёт висит"))
        s.commit()

    with Session(engine) as s:
        списано = debt_mod.settle_debt_from_wallet(s, drv["id"])

    assert списано == 0, (
        "кошелёк погасил долги, которые уже выставлены в висящем счёте картой — "
        "водитель заплатит за одну комиссию дважды, когда банк подтвердит перевод"
    )
    with Session(engine) as s:
        for did in (d1, d2):
            assert s.get(CommissionDebt, did).status == DebtStatus.unpaid


def test_r2_a_new_debt_after_the_invoice_is_still_settled_from_wallet(client, user_factory, monkeypatch):
    _pending_provider(monkeypatch)
    drv, (d1, d2) = _водитель_с_двумя_долгами(user_factory, "F2Новый")
    client.post("/driver/debt/paid", headers=drv["auth"])   # счёт на d1+d2 висит

    # Новая поездка закончилась УЖЕ ПОСЛЕ того, как счёт выставлен — в снимок она не попала.
    with Session(engine) as s:
        pax = user_factory("F2НовыйПас2")
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=200, price_final=200,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        новый_долг = CommissionDebt(driver_id=drv["id"], order_id=o.id, amount_kop=5_000,
                                    week="2026-W40", status=DebtStatus.unpaid, created_at=utcnow())
        s.add(новый_долг)
        s.commit()
        s.refresh(новый_долг)
        new_id = новый_долг.id
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=5_000,
                          note="тест: хватает только на новый долг"))
        s.commit()

    with Session(engine) as s:
        списано = debt_mod.settle_debt_from_wallet(s, drv["id"])

    assert списано == 5_000, "новый (не из снимка висящего счёта) долг обязан гаситься как обычно"
    with Session(engine) as s:
        assert s.get(CommissionDebt, new_id).status == DebtStatus.paid
        assert s.get(CommissionDebt, d1).status == DebtStatus.unpaid   # из снимка — не тронут


def test_r3_after_invoice_is_gone_wallet_settles_normally_again(client, user_factory, monkeypatch):
    _pending_provider(monkeypatch)
    drv, (d1, d2) = _водитель_с_двумя_долгами(user_factory, "F2Снят")
    client.post("/driver/debt/paid", headers=drv["auth"])

    with Session(engine) as s:
        pay = s.exec(select(Payment).where(Payment.user_id == drv["id"],
                                           Payment.purpose == "taxi_debt")).one()
        pay.status = "canceled"           # банк окончательно отказал / счёт истёк
        s.add(pay)
        s.commit()
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=20_000,
                          note="тест: счёт снят, деньги пришли"))
        s.commit()

    with Session(engine) as s:
        списано = debt_mod.settle_debt_from_wallet(s, drv["id"])

    assert списано == 20_000, "после отмены счёта кошелёк обязан снова видеть эти долги"
    with Session(engine) as s:
        assert s.get(CommissionDebt, d1).status == DebtStatus.paid
        assert s.get(CommissionDebt, d2).status == DebtStatus.paid


def test_r4_repeated_pay_click_with_a_live_invoice_keeps_quoting_the_same_amount(
        client, user_factory, monkeypatch):
    _pending_provider(monkeypatch)
    drv, (d1, d2) = _водитель_с_двумя_долгами(user_factory, "F2Повтор")
    первый = client.post("/driver/debt/paid", headers=drv["auth"]).json()
    assert первый["amount_kop"] == 20_000

    # Деньги в кошелёк пришли между первым и вторым нажатием — без фикса второй вызов
    # сначала списал бы это кошельком (строка 61), а потом отдал бы счёт с устаревшей суммой.
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=20_000,
                          note="тест: пришло между двумя нажатиями"))
        s.commit()

    второй = client.post("/driver/debt/paid", headers=drv["auth"]).json()
    assert второй["payment_id"] == первый["payment_id"]
    assert второй["amount_kop"] == 20_000, (
        f"повторное нажатие отдало {второй['amount_kop']} коп. вместо честных 20000 — "
        "счёт устарел относительно того, что кошелёк уже тихо списал"
    )


def test_r5_wallet_never_settles_a_debt_on_an_unpaid_order(client, user_factory):
    """Независимое ревью Opus, §4, п.1 (debt.py — InstantOrder.paid == True в зачёте кошельком):
    долг заводится на «Завершил», а СПОСОБ ОПЛАТЫ пассажиром выясняется позже. Пока заказ
    не оплачен (order.paid=False), гасить его долг кошельком нельзя: если пассажир потом
    заплатит картой, комиссия удержится ЕЩЁ РАЗ записью `fee` — водитель заплатит дважды.
    """
    drv = user_factory("F2НеОплаченД", role=UserRole.driver)
    пассажир = user_factory("F2НеОплаченПас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=drv["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=200, price_final=200,
                         paid=False,   # пассажир ЕЩЁ НЕ заплатил — способ оплаты неизвестен
                         done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
        d = CommissionDebt(driver_id=drv["id"], order_id=oid, amount_kop=3_000,
                           week="2026-W40", status=DebtStatus.unpaid, created_at=utcnow())
        s.add(d)
        s.commit()
        s.refresh(d)
        did = d.id
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=3_000,
                          note="тест: деньги в кошельке есть"))
        s.commit()

    with Session(engine) as s:
        списано = debt_mod.settle_debt_from_wallet(s, drv["id"])

    assert списано == 0, "кошелёк погасил долг по НЕОПЛАЧЕННОМУ заказу — способ оплаты ещё не известен"
    with Session(engine) as s:
        assert s.get(CommissionDebt, did).status == DebtStatus.unpaid

    # Пассажир заплатил (неважно чем) → order.paid=True → теперь кошелёк вправе гасить этот долг.
    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        order.paid = True
        s.add(order)
        s.commit()
    with Session(engine) as s:
        списано_после = debt_mod.settle_debt_from_wallet(s, drv["id"])
    assert списано_после == 3_000
    with Session(engine) as s:
        assert s.get(CommissionDebt, did).status == DebtStatus.paid
