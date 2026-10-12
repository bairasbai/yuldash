"""leaf-1.1 · backend/app/ledger.py — закрывает щели, которых не было в старом наборе тестов.

Старый набор (test_ledger.py, test_wallet_never_goes_negative.py, …) проверяет наличные и
идемпотентность для БЫСТРЫХ ЗАКАЗОВ (instant) и вывод с РАЗНЫМИ ключами для ОДНОГО водителя.
Четыре соседних правила той же природы нигде отдельно не проверялись:

R1 — наличные за БРОНЬ (попутку) тоже не должны трогать ledger (settle_booking, не только
     settle_instant_order).
R2 — повторный settle_booking (безнал) не должен начислить earn/fee ВТОРОЙ раз.
R3 — вывод без ключа идемпотентности (пустая строка/только пробелы) обязан отказывать, а не
     проводить вывод «на доверии» — иначе дабл-тап/ретрай сети без ключа спишет дважды.
R4 — ключ идемпотентности выплаты именной ПО ВОДИТЕЛЮ (`payout:{driver_id}:{key}`), а не голый
     сырой ключ от клиента. Настоящий риск — не наша БД (там выборка и так фильтрует по
     driver_id), а ЮKassa: её Idempotence-Key ГЛОБАЛЬНЫЙ, без понятия «чей». Два разных водителя,
     приславших одинаковый сырой ключ (низкая энтропия клиентского генератора, совпадение,
     повтор чужого) — без неймспейса ушли бы к провайдеру с ОДНИМ И ТЕМ ЖЕ Idempotence-Key, и
     провайдер отдал бы ОБОИМ запросам один и тот же ответ (кэш по ключу на его стороне):
     второй водитель получил бы чужой provider_id/confirmation_url, а его реальная выплата
     «проглотилась» бы. Проверяем именно ТО, что уходит наружу (idempotence_key в create_payout),
     а не только наш внутренний учёт.

R5/R6 — F1 (найдено независимым ревью Opus, 2026-10-02, подтверждено и исправлено здесь):
     settle_instant_order при оплате КАРТОЙ брал комиссию с ПОЛНОЙ цены (включая компенсацию
     водителю — подачу/кресло/зимнюю дорогу), хотя при оплате НАЛИЧНЫМИ (debt.accrue_for_order →
     order_commission_kop) комиссия всегда честно считалась с цены МИНУС компенсация. Чек при
     этом обещал пассажиру и водителю «с компенсации комиссия не берётся» — ровно то же враньё,
     ради которого когда-то написан compensation.py, только с другой стороны (карта, а не нал).
     R5 — без промокода; R6 — с промокодом (скидка гасится полной, не заниженной, комиссией).
"""
from datetime import timedelta

from sqlmodel import Session, select

from app import debt as debt_mod
from app import ledger
from app import promo_ride
from app.config import settings
from app.db import engine
from app.models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus, LedgerEntry, LedgerKind, Ride,
    UserRole,
)
from app.timeutil import utcnow


def _make_done_booking(driver_id: int, passenger_id: int, price_rub: int = 300) -> int:
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow(), price=price_rub)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1,
                    price=price_rub, status=BookingStatus.done)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


def _ledger_rows(driver_id: int, booking_id: int) -> list[LedgerEntry]:
    with Session(engine) as s:
        return list(s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == driver_id, LedgerEntry.booking_id == booking_id)).all())


def test_r1_cash_booking_does_not_touch_ledger(client, user_factory):
    drv = user_factory("CashBookDrv", role=UserRole.driver)
    pax = user_factory("CashBookPax")
    bid = _make_done_booking(drv["id"], pax["id"], price_rub=250)

    with Session(engine) as s:
        status = ledger.settle_booking(s, bid, "cash", 25_000)

    assert status == "settled"
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.paid is True and b.payment_method == "cash"
    assert _ledger_rows(drv["id"], bid) == [], "наличные за бронь не должны создавать записи в кошельке"
    with Session(engine) as s:
        assert ledger.driver_balance(s, drv["id"]) == 0


def test_r2_settle_booking_twice_cashless_does_not_double_post(client, user_factory):
    drv = user_factory("TwiceBookDrv", role=UserRole.driver)
    pax = user_factory("TwiceBookPax")
    bid = _make_done_booking(drv["id"], pax["id"], price_rub=300)

    with Session(engine) as s:
        first = ledger.settle_booking(s, bid, "card", 30_000)
    with Session(engine) as s:
        second = ledger.settle_booking(s, bid, "card", 30_000)

    assert first == "settled"
    assert second == "already"
    rows = _ledger_rows(drv["id"], bid)
    earn_rows = [r for r in rows if r.kind == LedgerKind.earn]
    assert len(earn_rows) == 1, f"повторный settle задвоил earn: {rows}"


def test_r3_payout_without_an_idempotency_key_is_rejected(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "payout_min_kop", 1_00, raising=False)
    monkeypatch.setattr(settings, "payout_max_kop", 500_000, raising=False)
    drv = user_factory("NoKeyDrv", role=UserRole.driver)
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.earn, amount_kop=50_000,
                          note="заработок"))
        s.commit()

    with Session(engine) as s:
        for пустой_ключ in ("", "   "):
            try:
                ledger.request_payout(s, drv["id"], 10_000, payout_token="tok",
                                      idempotency_key=пустой_ключ)
                assert False, f"вывод без ключа идемпотентности ({пустой_ключ!r}) обязан отказать"
            except ledger.PayoutError as e:
                assert e.code == "idempotency"
        assert ledger.driver_balance(s, drv["id"]) == 50_000, "отказ не должен был тронуть баланс"


def test_r4_the_same_raw_key_from_two_different_drivers_reach_the_provider_differently(
        client, user_factory, monkeypatch):
    """Сырой ключ от двух разных водителей обязан прийти к ЮKassa РАЗНЫМ Idempotence-Key.

    Провайдер судит по этому ключу один-в-один: одинаковый ключ от разных водителей он бы
    счёл ОДНИМ И ТЕМ ЖЕ запросом и отдал бы обоим ответ первого — второй водитель не получил
    бы свою реальную выплату. Наша собственная БД эту дыру не покажет (там выборка и так
    фильтрует по driver_id) — проверять нужно ровно то, что уходит наружу.
    """
    monkeypatch.setattr(settings, "payout_min_kop", 1_00, raising=False)
    monkeypatch.setattr(settings, "payout_max_kop", 500_000, raising=False)
    a = user_factory("PayoutDriverA", role=UserRole.driver)
    b = user_factory("PayoutDriverB", role=UserRole.driver)
    общий_сырой_ключ = "общий-ключ-от-разных-людей"
    with Session(engine) as s:
        for кто in (a, b):
            s.add(LedgerEntry(driver_id=кто["id"], kind=LedgerKind.earn, amount_kop=20_000,
                              note="заработок"))
        s.commit()

    увиденные_ключи: list[str] = []

    def шпион_вместо_юkassa(amount_kop, payout_token, description, metadata, *,
                            idempotence_key="", customer_phone=""):
        увиденные_ключи.append(idempotence_key)
        return {"status": "succeeded", "provider_id": f"mock-{len(увиденные_ключи)}"}

    monkeypatch.setattr("app.payments.create_payout", шпион_вместо_юkassa)

    with Session(engine) as s:
        res_a = ledger.request_payout(s, a["id"], 15_000, payout_token="tok",
                                      idempotency_key=общий_сырой_ключ)
        res_b = ledger.request_payout(s, b["id"], 15_000, payout_token="tok",
                                      idempotency_key=общий_сырой_ключ)

    assert res_a["status"] == "ok" and res_b["status"] == "ok"
    assert len(увиденные_ключи) == 2
    assert увиденные_ключи[0] != увиденные_ключи[1], (
        f"оба водителя ушли к провайдеру с ОДНИМ ключом {увиденные_ключи[0]!r} — "
        "ключ идемпотентности обязан быть именным по водителю"
    )
    assert str(a["id"]) in увиденные_ключи[0] or str(a["id"]) in увиденные_ключи[1]
    assert str(b["id"]) in увиденные_ключи[0] or str(b["id"]) in увиденные_ключи[1]


def _заказ_с_подачей(driver_id: int, passenger_id: int, *, price_rub: int = 500,
                     pickup_fee_kop: int = 10_000, promo_discount_kop: int = 0) -> int:
    """Такси-заказ ценой `price_rub`, из которых `pickup_fee_kop` — компенсация водителю
    за дальнюю подачу (копейки). Цена ПОЛНАЯ (компенсация уже внутри неё, как в проде —
    см. instant_service.price_fields), ровно как в сценарии ревью."""
    with Session(engine) as s:
        o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=price_rub,
                         price_final=price_rub, pickup_fee_kop=pickup_fee_kop,
                         promo_discount_kop=promo_discount_kop,
                         created_at=utcnow(), done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def test_r5_card_fee_excludes_compensation_like_cash_debt(client, user_factory):
    """F1. Цена 500 ₽ = поездка 400 + подача 100. При 15% нал держит 60 ₽ — карта обязана
    держать те же 60 ₽, а не 75 ₽ (15% от всей цены, вместе с бензином водителя)."""
    drv = user_factory("КартаКомпДрв", role=UserRole.driver)
    pax = user_factory("КартаКомпПас")
    oid = _заказ_с_подачей(drv["id"], pax["id"], price_rub=500, pickup_fee_kop=10_000)

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        pct = debt_mod.driver_fee_percent(s, drv["id"], order.created_at)
        ожидаемая_комиссия = debt_mod.order_commission_kop(order, pct)   # честная база: 400 ₽, не 500 ₽
        assert ledger.settle_instant_order(s, oid, "card", 50_000) == "settled"

    with Session(engine) as s:
        fee_rows = s.exec(select(LedgerEntry).where(
            LedgerEntry.order_id == oid, LedgerEntry.kind == LedgerKind.fee)).all()
        списано = -sum(e.amount_kop for e in fee_rows)

    assert списано == ожидаемая_комиссия, (
        f"карта удержала {списано} коп., нал удержал бы {ожидаемая_комиссия} коп. за ту же "
        "поездку — комиссия считается с компенсации водителю (бензина), а не должна"
    )


def test_r6_card_fee_with_promo_still_excludes_compensation(client, user_factory):
    """F1 + промокод. Скидка гасится ПОЛНОЙ (цена минус компенсация) комиссией, не раздутой.

    Скидка 3 ₽ НАРОЧНО меньше обеих возможных полных комиссий (12 ₽ честно / 15 ₽ по ошибке
    F1 при 3%-й ставке новичка) — если взять скидку больше любой из них, обе ветки дают
    fee_due=0 и тест ничего не отличит (ровно так и было в первой редакции этого теста)."""
    drv = user_factory("КартаКомпПромоДрв", role=UserRole.driver)
    pax = user_factory("КартаКомпПромоПас")
    oid = _заказ_с_подачей(drv["id"], pax["id"], price_rub=500, pickup_fee_kop=10_000,
                           promo_discount_kop=300)   # скидка 3 ₽

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        pct = debt_mod.driver_fee_percent(s, drv["id"], order.created_at)
        честная_полная_комиссия = debt_mod.order_commission_kop(order, pct)   # с 400 ₽
        ожидаемая_к_оплате, _ = promo_ride.split_commission(честная_полная_комиссия, 300)
        assert ожидаемая_к_оплате > 0, "скидка проглотила всю комиссию — тест ничего не докажет"
        # Пассажир платит цену минус скидка.
        к_оплате = order.price_final * 100 - 300
        assert ledger.settle_instant_order(s, oid, "card", к_оплате) == "settled"

    with Session(engine) as s:
        fee_rows = s.exec(select(LedgerEntry).where(
            LedgerEntry.order_id == oid, LedgerEntry.kind == LedgerKind.fee)).all()
        списано = -sum(e.amount_kop for e in fee_rows)

    assert списано == ожидаемая_к_оплате, (
        f"с промокодом карта удержала {списано} коп., честно (от цены минус компенсация, "
        f"гашено скидкой) должно быть {ожидаемая_к_оплате} коп."
    )


def test_r5_cash_and_card_charge_the_same_commission_for_the_same_order(client, user_factory):
    """Прямое сравнение двух путей оплаты ОДНОЙ и той же поездки (разные заказы, одна формула):
    долг Модели А (нал) и удержание в ledger (карта) обязаны сойтись копейка в копейку."""
    drv = user_factory("НалКартаДрв", role=UserRole.driver)
    pax1 = user_factory("НалКартаПас1")
    pax2 = user_factory("НалКартаПас2")
    cash_oid = _заказ_с_подачей(drv["id"], pax1["id"], price_rub=500, pickup_fee_kop=10_000)
    card_oid = _заказ_с_подачей(drv["id"], pax2["id"], price_rub=500, pickup_fee_kop=10_000)

    with Session(engine) as s:
        cash_order = s.get(InstantOrder, cash_oid)
        долг = debt_mod.accrue_for_order(s, cash_order)
        assert долг is not None
        долг_коп = долг.amount_kop

        assert ledger.settle_instant_order(s, card_oid, "card", 50_000) == "settled"
        fee_rows = s.exec(select(LedgerEntry).where(
            LedgerEntry.order_id == card_oid, LedgerEntry.kind == LedgerKind.fee)).all()
        карта_коп = -sum(e.amount_kop for e in fee_rows)

    assert карта_коп == долг_коп, (
        f"нал держит {долг_коп} коп., карта держит {карта_коп} коп. за идентичную поездку — "
        "один человек платит за бензин водителя, другой нет"
    )


def test_reconcile_platform_net_formula_is_fee_minus_three_expenses(client, user_factory):
    """Независимое ревью Opus, §4, п.6: reconcile() целиком был без своей поломки.

    platform_net_kop = fee_kop − promo_comp_kop − refund_kop − adj_other_kop — доход минус ВСЕ
    три расхода. Перепутать знак у любого слагаемого значит показать Александру доход выше
    настоящего ровно на тот расход, который платформа реально понесла (волна 217)."""
    drv = user_factory("СверкаФормула", role=UserRole.driver)
    период_начало = utcnow() - timedelta(days=1)
    период_конец = utcnow() + timedelta(days=1)
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.fee, amount_kop=-10_000,
                          note="комиссия сервиса"))                                      # доход 100 ₽
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=3_000,
                          ext_id="promo:999901", note="компенсация промокода"))           # расход 30 ₽
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=2_000,
                          ext_id="refund:order:999902", note="возврат комиссии"))         # расход 20 ₽
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=1_000,
                          ext_id="", note="ручная доплата админа"))                       # расход 10 ₽
        s.commit()

    with Session(engine) as s:
        отчёт = ledger.reconcile(s, период_начало, период_конец)

    assert отчёт["fee_kop"] >= 10_000 and отчёт["promo_comp_kop"] >= 3_000
    assert отчёт["refund_kop"] >= 2_000 and отчёт["adj_other_kop"] >= 1_000
    # Считаем ДЕЛЬТАМИ не получится (общая БД на сессию) — проверяем саму формулу напрямую
    # на этих же числах отчёта, а не абсолютные значения.
    ожидаемый_net = отчёт["fee_kop"] - отчёт["promo_comp_kop"] - отчёт["refund_kop"] - отчёт["adj_other_kop"]
    assert отчёт["platform_net_kop"] == ожидаемый_net
