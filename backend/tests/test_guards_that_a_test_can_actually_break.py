"""Волна 201: защита, которую нельзя сломать тестом, — это не защита.

Часть наших защит от одновременных действий держится ТОЛЬКО на блокировке строки
(`with_for_update`). На боевом Postgres она работает. Но тесты, локальная разработка
и демо-база эмулятора живут на SQLite, а он такую блокировку ИГНОРИРУЕТ. Значит:

  * ни один тест не краснеет, если блокировку удалить при рефакторинге;
  * на SQLite защиты нет вовсе — а на ней у нас крутится демо и проверяется всё.

Проект уже сделал правильный вывод в одном месте: при бронировании места условие живёт
внутри самого `UPDATE` («мест хватает» и вычитание одним запросом), и это работает на обеих
базах. Здесь тот же приём применён к погашению купона и к зачёту долга из кошелька — двум
местам, где повтор стоит денег.

Погашение купона: код гасит бизнес у себя. Второе погашение того же кода — это второй
рубль в statement партнёру (10 ₽ за погашение) и второй разговор с клиентом, которому
скидку уже дали.
"""
from __future__ import annotations

from sqlmodel import Session, select

from app import debt as debt_mod
from app import ledger
from app.db import engine
from app.models import (
    CommissionDebt, Coupon, CouponRedemption, DebtStatus, InstantOrder,
    InstantOrderStatus as S, LedgerEntry, LedgerKind, UserRole,
)
from app.timeutil import utcnow

from test_coupons import _make_active_coupon, _register_active_partner


# ==================== 1. Купон: два кассира с одним QR ====================
def test_same_coupon_code_cannot_be_redeemed_twice(client, user_factory):
    """Два кассира сканируют один QR одновременно. Погасить обязан ровно один.

    Проверка идёт на SQLite, где блокировка строки — пустая операция. Значит правило
    должно держаться самой базой: условие «код ещё не погашен» внутри `UPDATE`.
    """
    owner, _admin, _pid, _plan = _register_active_partner(client, user_factory)
    cid = _make_active_coupon(client, owner)
    гость = user_factory(name="Гость купона")
    код = client.post(f"/coupons/{cid}/activate", headers=гость["auth"]).json()["code"]

    первый = client.post("/coupons/redeem", headers=owner["auth"], json={"code": код})
    второй = client.post("/coupons/redeem", headers=owner["auth"], json={"code": код})

    assert первый.status_code == 200, первый.text
    assert второй.status_code == 409, (
        f"код погашен дважды: второй кассир получил {второй.status_code} вместо 409"
    )
    with Session(engine) as s:
        assert s.get(Coupon, cid).redeemed_count == 1, (
            "счётчик погашений вырос дважды — по нему выставляется счёт партнёру"
        )


def test_coupon_redeem_is_atomic_even_with_a_stale_read(client, user_factory, monkeypatch):
    """Главная проба: между проверкой «код ещё не погашен» и записью вклинивается чужой кассир.

    Ровно это и происходит на боевой базе при двух одновременных запросах. Проверка
    в Python тут бессильна — отсечь второго обязана сама база.
    """
    owner, _admin, _pid, _plan = _register_active_partner(client, user_factory)
    cid = _make_active_coupon(client, owner)
    гость = user_factory(name="Гость купона 2")
    код = client.post(f"/coupons/{cid}/activate", headers=гость["auth"]).json()["code"]

    # Вклиниваемся В СЕРЕДИНЕ запроса: наш кассир уже прочитал «код свободен», и ровно
    # в этот момент второй кассир гасит его целиком. Точка врезки — `utcnow()`, который
    # роутер зовёт после проверки статуса и до записи.
    import app.routers.coupons as coupons_mod

    настоящий = coupons_mod.utcnow
    сработало = {"раз": False}

    def время_с_вклиниванием():
        if not сработало["раз"]:
            сработало["раз"] = True
            with Session(engine) as чужая:
                red = чужая.exec(
                    select(CouponRedemption).where(CouponRedemption.code == код)).first()
                red.status = "redeemed"
                red.redeemed_at = настоящий()
                red.redeemed_by = owner["id"]
                чужая.add(red)
                c = чужая.get(Coupon, cid)
                c.redeemed_count = (c.redeemed_count or 0) + 1
                чужая.add(c)
                чужая.commit()
        return настоящий()

    monkeypatch.setattr(coupons_mod, "utcnow", время_с_вклиниванием)
    ответ = client.post("/coupons/redeem", headers=owner["auth"], json={"code": код})

    assert ответ.status_code == 409, f"второе погашение прошло: {ответ.status_code}"
    with Session(engine) as s:
        assert s.get(Coupon, cid).redeemed_count == 1


def test_coupon_redeem_still_works_the_first_time(client, user_factory):
    """Защита не сломана: первое погашение проходит и всё возвращает как раньше."""
    owner, _admin, _pid, _plan = _register_active_partner(client, user_factory)
    cid = _make_active_coupon(client, owner)
    гость = user_factory(name="Гость купона 3")
    код = client.post(f"/coupons/{cid}/activate", headers=гость["auth"]).json()["code"]

    r = client.post("/coupons/redeem", headers=owner["auth"], json={"code": код})

    assert r.status_code == 200, r.text
    assert r.json()["ok"] is True
    assert r.json()["coupon_title"] == "Скидка на кофе"
    assert "phone" not in r.json(), "приватность: телефон держателя наружу не отдаём"
    with Session(engine) as s:
        red = s.exec(select(CouponRedemption).where(CouponRedemption.code == код)).first()
        assert red.status == "redeemed"
        assert red.redeemed_by == owner["id"]
        assert red.redeemed_at is not None


# ==================== 2. Кошелёк: то же правило, вторая дверь ====================
def _водитель_с_долгом(user_factory, имя: str, кошелёк_коп: int, долг_коп: int):
    водитель = user_factory(имя, role=UserRole.driver)
    пассажир = user_factory(имя + "Пас")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         from_lat=52.591, from_lng=58.317, to_lat=52.716, to_lng=58.664,
                         status=S.done, price_estimate=300, price_final=300,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
        s.add(LedgerEntry(driver_id=водитель["id"], kind=LedgerKind.adj,
                          amount_kop=кошелёк_коп, ext_id=f"promo:{oid}", note="Компенсация"))
        s.add(CommissionDebt(driver_id=водитель["id"], order_id=oid, week="2026-W35",
                             amount_kop=долг_коп, status=DebtStatus.unpaid,
                             created_at=utcnow(), due_at=utcnow()))
        s.commit()
    return водитель, oid


def test_wallet_settlement_is_atomic_even_with_a_stale_read(client, user_factory, monkeypatch):
    """Волна 200 поставила замок; здесь правило переносится в саму базу.

    Замок на SQLite не работает, а демо-база и все тесты живут на нём. Между чтением
    долгов и записью вклинивается параллельный зачёт — второй проход не должен списать
    деньги повторно.
    """
    водитель, oid = _водитель_с_долгом(user_factory, "Атомарный", 30_000, 20_000)
    # Точка врезки — `utcnow()`: в `settle_debt_from_wallet` он зовётся уже ПОСЛЕ того,
    # как список долгов прочитан. Именно там на боевой базе и помещается чужой запрос.
    настоящий = debt_mod.utcnow
    сработало = {"раз": False}

    def время_с_вклиниванием():
        if not сработало["раз"]:
            сработало["раз"] = True
            with Session(engine) as чужая:       # параллельный зачёт успевает целиком
                debt_mod.settle_debt_from_wallet(чужая, водитель["id"])
        return настоящий()

    monkeypatch.setattr(debt_mod, "utcnow", время_с_вклиниванием)
    with Session(engine) as s:
        debt_mod.settle_debt_from_wallet(s, водитель["id"])

    with Session(engine) as s:
        баланс = ledger.driver_balance(s, водитель["id"])
        списано = -sum(e.amount_kop for e in s.exec(
            select(LedgerEntry).where(LedgerEntry.driver_id == водитель["id"],
                                      LedgerEntry.kind == LedgerKind.fee)).all())

    assert списано == 20_000, f"списали {списано / 100:g} ₽ при долге 200 ₽"
    assert баланс == 10_000, f"в кошельке {баланс / 100:g} ₽ вместо 100 ₽"


def test_already_paid_debt_is_never_charged_again(client, user_factory):
    """Долг, закрытый кем-то другим, не должен уйти в списание второй раз."""
    водитель, oid = _водитель_с_долгом(user_factory, "УжеЗакрыт", 30_000, 20_000)
    with Session(engine) as s:                    # кто-то закрыл долг между делом
        d = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first()
        d.status = DebtStatus.paid
        s.add(d)
        s.commit()

    with Session(engine) as s:
        снято = debt_mod.settle_debt_from_wallet(s, водитель["id"])
        баланс = ledger.driver_balance(s, водитель["id"])

    assert снято == 0
    assert баланс == 30_000, "деньги списали за уже закрытый долг"


def test_own_note_on_the_debt_is_not_overwritten(client, user_factory):
    """Правило перенесли в UPDATE — своя пометка на долге обязана уцелеть.

    Админ мог написать «простить» или иную причину. Зачёт кошелька ставит свою пометку
    ТОЛЬКО когда поля нет: чужой текст затирать нельзя, по нему потом разбирают спор."""
    водитель, oid = _водитель_с_долгом(user_factory, "СвояПометка", 30_000, 20_000)
    with Session(engine) as s:
        d = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first()
        d.note = "разбор №17: ждём чек"
        s.add(d)
        s.commit()

    with Session(engine) as s:
        debt_mod.settle_debt_from_wallet(s, водитель["id"])

    with Session(engine) as s:
        d = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first()
        assert d.status == DebtStatus.paid
        assert d.note == "разбор №17: ждём чек", f"чужую пометку затёрли: {d.note!r}"


def test_debt_without_a_note_gets_the_wallet_one(client, user_factory):
    """А если пометки не было — пишем свою, иначе непонятно, чем долг закрыт."""
    водитель, oid = _водитель_с_долгом(user_factory, "БезПометки", 30_000, 20_000)

    with Session(engine) as s:
        debt_mod.settle_debt_from_wallet(s, водитель["id"])

    with Session(engine) as s:
        d = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first()
        assert d.note == debt_mod.WALLET_PAID_NOTE, f"причина закрытия не записана: {d.note!r}"


def test_a_debt_closed_by_someone_else_does_not_block_the_next_one(client, user_factory,
                                                                   monkeypatch):
    """У водителя два долга, денег хватает на оба. Старший закрыли без нас — второй
    обязан погаситься, а не остаться висеть.

    Разница между «пропустить этот долг» и «остановиться совсем» стоит человеку работы:
    непогашенный долг блокирует такси, а деньги на него в кошельке лежат.
    """
    from datetime import timedelta

    водитель = user_factory("ДваДолга", role=UserRole.driver)
    пассажир = user_factory("ДваДолгаПас")
    номера = []
    with Session(engine) as s:
        for сумма, дней in ((20_000, 3), (10_000, 1)):
            o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                             from_lat=52.591, from_lng=58.317, to_lat=52.716, to_lng=58.664,
                             status=S.done, price_estimate=300, price_final=300,
                             paid=True, done_at=utcnow())
            s.add(o)
            s.commit()
            s.refresh(o)
            номера.append(o.id)
            s.add(CommissionDebt(driver_id=водитель["id"], order_id=o.id, week="2026-W35",
                                 amount_kop=сумма, status=DebtStatus.unpaid,
                                 created_at=utcnow() - timedelta(days=дней), due_at=utcnow()))
        s.add(LedgerEntry(driver_id=водитель["id"], kind=LedgerKind.adj,
                          amount_kop=30_000, ext_id="promo:двадолга", note="Компенсация"))
        s.commit()
    старший, младший = номера

    # Пока наш проход держит в руках оба долга, админ прощает старший.
    настоящий = debt_mod.utcnow
    сработало = {"раз": False}

    def время_с_вклиниванием():
        if not сработало["раз"]:
            сработало["раз"] = True
            with Session(engine) as чужая:
                d = чужая.exec(select(CommissionDebt).where(
                    CommissionDebt.order_id == старший)).first()
                d.status = DebtStatus.paid
                d.note = "прощено разбором"
                чужая.add(d)
                чужая.commit()
        return настоящий()

    monkeypatch.setattr(debt_mod, "utcnow", время_с_вклиниванием)
    with Session(engine) as s:
        debt_mod.settle_debt_from_wallet(s, водитель["id"])

    with Session(engine) as s:
        второй = s.exec(select(CommissionDebt).where(
            CommissionDebt.order_id == младший)).first()
        баланс = ledger.driver_balance(s, водитель["id"])

    assert второй.status == DebtStatus.paid, (
        "старший долг закрыл кто-то другой — и на этом зачёт остановился, "
        "второй долг остался висеть и блокировать такси при полном кошельке"
    )
    assert баланс == 20_000, f"списали не 100 ₽, а {(30_000 - баланс) / 100:g} ₽"
