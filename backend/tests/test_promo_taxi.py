"""M2 — промокод-скидка на поездку в такси (kind="taxi_ride"). Деньги, поэтому плотно.

Главный тест здесь — `test_driver_gets_exactly_same_money_as_without_promo`: он считает деньги
водителя ЧИСЛАМИ на двух одинаковых поездках (с промокодом и без) и требует полного совпадения.
Скидку пассажиру оплачивает ПЛАТФОРМА: сначала своей комиссией, а если скидка больше комиссии —
остаток доплачивает водителю в кошелёк. Водитель не должен потерять ни копейки — он ни в чём
не виноват.

Покрываем: скидка видна в оценке; фиксируется в заказе; списывается РОВНО один раз (в т.ч. при
параллельной попытке); комиссия гасится; остаток уходит в кошелёк; итог водителя = «без
промокода»; оба потолка (₽ и доля от цены); отмена возвращает скидку; чужой/просроченный/
выключенный/неподходящий код скидки не даёт; welcome и boost работают как раньше.
"""
import threading
from datetime import datetime, timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app import promo_ride
from app.config import settings
from app.db import engine
from app.ledger import driver_balance, promo_comp_ext_id
from app.models import (
    CommissionDebt, InstantOrder, InstantOrderStatus as S, LedgerEntry, LedgerKind,
    PromoRedemption, UserRole,
)

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _past():
    return (datetime.utcnow() - timedelta(days=1)).replace(microsecond=0).isoformat()


# ------------------------------ helpers ------------------------------
def _create_promo(client, admin, code, **overrides):
    body = {"code": code, "title": "Скидка на такси", "kind": promo_ride.KIND, "perk_value": 100}
    body.update(overrides)
    r = client.post("/admin/promo", headers=admin["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _pax_with_discount(client, user_factory, admin, code, rub=100, name="ПромоПас", **promo_kw):
    """Пассажир, у которого активирована скидка на такси в `rub` рублей."""
    _create_promo(client, admin, code, perk_value=rub, **promo_kw)
    pax = user_factory(name)
    r = client.post("/promo/apply", headers=pax["auth"], json={"code": code})
    assert r.status_code == 200, r.text
    assert r.json()["discount_kop"] == min(rub, settings.promo_ride_max_discount_rub) * 100
    return pax


def _order_body(frm=ORIG, to=DEST, **extra):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай", **extra}


def _driver_online(client, user_factory, name):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    return d


def _ride_to_done(client, d, pax):
    """Заказ этого пассажира этим водителем до done. Возврат: payload завершённого заказа."""
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert order["status"] == "offered", order
    oid = order["id"]
    assert client.post(f"/instant/orders/{oid}/accept", headers=d["auth"]).status_code == 200
    assert client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"]).status_code == 200
    assert client.post(f"/instant/orders/{oid}/onboard", headers=d["auth"]).status_code == 200
    done = client.post(f"/instant/orders/{oid}/done", headers=d["auth"]).json()
    assert done["status"] == "done", done
    return done


def _debt_kop(order_id: int) -> int:
    """Начисленный долг по комиссии за заказ (0 — записи нет: комиссия погашена скидкой)."""
    with Session(engine) as s:
        row = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == order_id)).first()
        return int(row.amount_kop) if row else 0


def _comp_kop(order_id: int) -> int:
    """Компенсация промокода, начисленная водителю в кошелёк по этому заказу."""
    with Session(engine) as s:
        row = s.exec(select(LedgerEntry).where(
            LedgerEntry.ext_id == promo_comp_ext_id(order_id), LedgerEntry.kind == LedgerKind.adj
        )).first()
        return int(row.amount_kop) if row else 0


def _redemption(user_id: int) -> PromoRedemption:
    with Session(engine) as s:
        return s.exec(select(PromoRedemption).where(PromoRedemption.user_id == user_id)).first()


# ============================ Оценка цены: выгода ВИДНА до заказа ============================
def test_estimate_shows_discount(client, user_factory):
    admin = user_factory("ПромоАдмин", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXI100", rub=100, name="ОценкаПас")

    est = client.post("/instant/estimate", headers=pax["auth"], json=_order_body())
    assert est.status_code == 200, est.text
    body = est.json()
    assert body["promo_discount_kop"] == 10000                 # 100 ₽ в копейках
    assert body["promo_code"] == "TAXI100"
    assert body["price_with_discount"] == body["price"] - 100
    assert body["promo_note"]["ru"] and body["promo_note"]["ba"]   # объяснение на двух языках


def test_estimate_without_promo_is_zero(client, user_factory):
    """Без промокода поля на месте и честно нулевые — клиенту нечего угадывать."""
    pax = user_factory("БезПромоОценка")
    body = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    assert body["promo_discount_kop"] == 0
    assert body["promo_code"] == ""
    assert body["price_with_discount"] == body["price"]
    assert body["promo_note"] is None


# ============================ Заказ: скидка фиксируется и списывается ============================
def test_order_fixes_discount_and_spends_it(client, user_factory, fake_redis):
    admin = user_factory("ПромоАдмин2", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXI150", rub=150, name="ЗаказПас")
    _driver_online(client, user_factory, "ПромоВод1")

    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert order["promo_discount_kop"] == 15000
    # Пассажир платит цену МИНУС скидка — и это видно обеим сторонам в карточке заказа.
    assert order["passenger_price_kop"] == order["price_estimate"] * 100 - 15000

    red = _redemption(pax["id"])
    assert red.used_order_id == order["id"] and red.used_at is not None
    # Скидка потрачена → в новой оценке её больше нет.
    est = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    assert est["promo_discount_kop"] == 0
    mine = client.get("/promo/mine", headers=pax["auth"]).json()
    assert mine["discount_available"] is False
    assert mine["discount_used_order_id"] == order["id"]


def test_discount_spent_once_on_parallel_attempt(client, user_factory, fake_redis):
    """Два заказа не могут потратить одну скидку: второй захват возвращает 0 (CAS по погашению)."""
    admin = user_factory("ПромоАдмин3", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXIONCE", rub=100, name="ОдинРазПас")
    _driver_online(client, user_factory, "ПромоВод2")

    first = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert first["promo_discount_kop"] == 10000
    # Второй заказ того же пассажира (первый ещё активен → API вернёт его же), поэтому берём
    # шов напрямую: другой заказ пытается забрать ту же скидку.
    with Session(engine) as s:
        other = InstantOrder(passenger_id=pax["id"], price_estimate=500,
                             from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1])
        s.add(other)
        s.commit()
        s.refresh(other)
        assert promo_ride.consume(s, pax["id"], other) == 0
        s.refresh(other)
        assert other.promo_discount_kop == 0
    assert _redemption(pax["id"]).used_order_id == first["id"]   # скидка осталась на первом заказе


def test_discount_race_threads_only_one_wins(client, user_factory, fake_redis):
    """Под реальной гонкой два параллельных захвата → скидку получает ровно один заказ."""
    admin = user_factory("ПромоАдмин4", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXIRACE", rub=100, name="ГонкаПас")
    with Session(engine) as s:
        orders = []
        for _ in range(2):
            o = InstantOrder(passenger_id=pax["id"], price_estimate=500,
                             from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1])
            s.add(o)
            s.commit()
            s.refresh(o)
            orders.append(o.id)

    got, lock = [], threading.Lock()

    def grab(order_id):
        with Session(engine) as s:
            o = s.get(InstantOrder, order_id)
            value = promo_ride.consume(s, pax["id"], o)
        with lock:
            got.append(value)

    ts = [threading.Thread(target=grab, args=(oid,)) for oid in orders]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert sorted(got) == [0, 10000], got                        # ровно один заказ со скидкой
    with Session(engine) as s:
        total = sum(int(s.get(InstantOrder, oid).promo_discount_kop or 0) for oid in orders)
    assert total == 10000                                        # и в БД скидка ровно одна


# ============================ Главное: водитель не теряет НИ КОПЕЙКИ ============================
def test_driver_gets_exactly_same_money_as_without_promo(client, user_factory, fake_redis):
    """Две одинаковые поездки: с промокодом и без. Итог водителя обязан совпасть до копейки.

    Считаем деньги как в жизни: на руки (пассажир платит наличными водителю напрямую)
    − долг по комиссии платформе + начисленное в кошелёк."""
    admin = user_factory("ПромоАдмин5", role=UserRole.admin)
    plain_pax = user_factory("БезПромоПас")
    promo_pax = _pax_with_discount(client, user_factory, admin, "TAXISAME", rub=100, name="СПромоПас")
    d = _driver_online(client, user_factory, "ЧестныйВод")

    plain = _ride_to_done(client, d, plain_pax)
    promo = _ride_to_done(client, d, promo_pax)
    assert plain["price_final"] == promo["price_final"], "поездки должны быть одинаковыми по цене"
    price_kop = int(plain["price_final"]) * 100
    disc = 10000
    assert promo["promo_discount_kop"] == disc

    def net(order, discount):
        cash = price_kop - discount                     # столько пассажир отдал водителю
        return cash - _debt_kop(order["id"]) + _comp_kop(order["id"])

    assert net(promo, disc) == net(plain, 0), "водитель потерял/выиграл на промокоде — так нельзя"
    # И это не «случайно совпало»: скидка ушла из комиссии платформы.
    assert _debt_kop(promo["id"]) == _debt_kop(plain["id"]) - disc + _comp_kop(promo["id"])


def test_commission_absorbs_discount(client, user_factory, fake_redis):
    """Скидка МЕНЬШЕ комиссии → долг просто уменьшается на неё, кошелёк не трогаем."""
    admin = user_factory("ПромоАдмин6", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXISMALL", rub=1, name="МалаяСкидка")
    d = _driver_online(client, user_factory, "ГасимКомиссией")

    done = _ride_to_done(client, d, pax)
    price_kop = int(done["price_final"]) * 100
    full_fee = int(price_kop * settings.fee_tier1_percent / 100)   # новичок → 1-я ступень
    assert full_fee > 100, "тест бессмыслен, если комиссия меньше скидки"
    assert _debt_kop(done["id"]) == full_fee - 100
    assert _comp_kop(done["id"]) == 0


def test_remainder_goes_to_driver_wallet(client, user_factory, fake_redis):
    """Скидка БОЛЬШЕ комиссии → долг обнуляется, остаток приходит водителю в кошелёк."""
    admin = user_factory("ПромоАдмин7", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXIBIG", rub=100, name="БольшаяСкидка")
    d = _driver_online(client, user_factory, "КошелёкВод")
    balance_before = driver_balance_of(d["id"])

    done = _ride_to_done(client, d, pax)
    price_kop = int(done["price_final"]) * 100
    full_fee = int(price_kop * settings.fee_tier1_percent / 100)
    assert full_fee < 10000, "тест бессмыслен, если комиссия больше скидки"
    assert _debt_kop(done["id"]) == 0                       # комиссии за эту поездку нет вовсе
    assert _comp_kop(done["id"]) == 10000 - full_fee        # остаток скидки — водителю
    assert driver_balance_of(d["id"]) == balance_before + (10000 - full_fee)
    # Идемпотентность: повторный «done» не начисляет компенсацию второй раз.
    client.post(f"/instant/orders/{done['id']}/done", headers=d["auth"])
    assert driver_balance_of(d["id"]) == balance_before + (10000 - full_fee)


def driver_balance_of(driver_id: int) -> int:
    with Session(engine) as s:
        return driver_balance(s, driver_id)


def _done_order_in_db(driver_id: int, passenger_id: int, price_rub: int) -> int:
    """Готовый завершённый заказ прямо в БД (минуем matcher — проверяем деньги)."""
    from app.timeutil import utcnow as _now
    with Session(engine) as s:
        o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                         from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                         from_text="А", to_text="Б", status=S.done,
                         price_estimate=price_rub, price_final=price_rub, done_at=_now())
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


@pytest.mark.parametrize("discount_rub", [5, 100])
def test_card_payment_keeps_driver_whole(client, user_factory, discount_rub):
    """Оплата КАРТОЙ (деньги идут через платформу) — водитель тоже получает ровно столько же.

    Проверяем оба случая: скидка меньше комиссии (гасится комиссией) и больше (остаток
    в кошелёк). Поездка 200 ₽, свежий таксист → комиссия 3% = 600 коп.
    """
    from app import debt as debt_mod
    admin = user_factory("ПромоАдмин21", role=UserRole.admin)
    drv = user_factory(f"КартаВод{discount_rub}", role=UserRole.driver)
    plain_pax = user_factory(f"КартаБезПромо{discount_rub}")
    promo_pax = _pax_with_discount(client, user_factory, admin, f"TAXICARD{discount_rub}",
                                   rub=discount_rub, name=f"КартаСПромо{discount_rub}")

    # Считаем ВЕСЬ денежный след каждой поездки: завершение (там начисляется компенсация)
    # плюс оплата картой. Снимок баланса — до завершения, иначе компенсация выпадет из счёта.
    plain_id = _done_order_in_db(drv["id"], plain_pax["id"], 200)
    before = driver_balance_of(drv["id"])
    with Session(engine) as s:
        debt_mod.accrue_for_order(s, s.get(InstantOrder, plain_id))
    assert client.post(f"/instant/orders/{plain_id}/pay", headers=plain_pax["auth"],
                       json={"method": "card"}).status_code == 200
    plain_delta = driver_balance_of(drv["id"]) - before

    promo_id = _done_order_in_db(drv["id"], promo_pax["id"], 200)
    with Session(engine) as s:
        assert promo_ride.consume(s, promo_pax["id"], s.get(InstantOrder, promo_id)) == discount_rub * 100
    before = driver_balance_of(drv["id"])
    with Session(engine) as s:
        debt_mod.accrue_for_order(s, s.get(InstantOrder, promo_id))
    assert client.post(f"/instant/orders/{promo_id}/pay", headers=promo_pax["auth"],
                       json={"method": "card"}).status_code == 200
    promo_delta = driver_balance_of(drv["id"]) - before

    assert plain_delta == 19400                      # 200 ₽ − 3% комиссии
    assert promo_delta == plain_delta, "картой водитель получил не столько же — скидка съела его деньги"


def test_receipt_and_cash_show_discounted_amount(client, user_factory, fake_redis):
    """Чек и «получил наличные» показывают сумму, которую человек РЕАЛЬНО заплатил."""
    admin = user_factory("ПромоАдмин8", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXICHEK", rub=100, name="ЧекПас")
    d = _driver_online(client, user_factory, "ЧекВод")

    done = _ride_to_done(client, d, pax)
    cash = client.post(f"/instant/orders/{done['id']}/cash-received", headers=d["auth"])
    assert cash.status_code == 200
    assert cash.json()["amount_kop"] == int(done["price_final"]) * 100 - 10000

    receipt = client.get(f"/instant/orders/{done['id']}/receipt", headers=pax["auth"]).json()
    assert receipt["promo_discount_kop"] == 10000
    assert receipt["price_kop"] == int(done["price_final"]) * 100
    assert receipt["amount"] == int(done["price_final"]) - 100


# ============================ Потолки: кампания не уводит платформу в минус ============================
def test_absolute_cap_limits_grant(client, user_factory, monkeypatch):
    """Потолок в рублях режет скидку уже при активации кода (опечатка в админке не страшна)."""
    monkeypatch.setattr(settings, "promo_ride_max_discount_rub", 200)
    admin = user_factory("ПромоАдмин9", role=UserRole.admin)
    _create_promo(client, admin, "TAXIHUGE", perk_value=5000)
    pax = user_factory("ПотолокПас")
    r = client.post("/promo/apply", headers=pax["auth"], json={"code": "TAXIHUGE"})
    assert r.status_code == 200
    assert r.json()["discount_kop"] == 20000                     # 200 ₽, а не 5000 ₽
    assert _redemption(pax["id"]).discount_kop == 20000


def test_share_cap_limits_discount_on_cheap_ride(client, user_factory, monkeypatch):
    """Потолок «доля от цены»: на дешёвой поездке скидка ужимается, поездку целиком не дарим."""
    monkeypatch.setattr(settings, "promo_ride_max_price_share", 0.5)
    admin = user_factory("ПромоАдмин10", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXISHARE", rub=300, name="ДоляПас")
    est = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    price = est["price"]
    assert est["promo_discount_kop"] == min(30000, (price // 2) * 100)
    assert est["price_with_discount"] >= price - price // 2      # платит минимум половину


def test_share_cap_is_applied_to_the_order_too(client, user_factory, fake_redis, monkeypatch):
    """Потолок доли действует и при списании — не только в витрине оценки."""
    monkeypatch.setattr(settings, "promo_ride_max_price_share", 0.1)
    admin = user_factory("ПромоАдмин11", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXISHARE2", rub=300, name="ДоляПас2")
    _driver_online(client, user_factory, "ДоляВод")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert order["promo_discount_kop"] == (int(order["price_estimate"] * 0.1)) * 100


# ============================ Отмена: скидка не сгорает ============================
def test_cancel_returns_discount(client, user_factory, fake_redis):
    """Поездка не состоялась → скидка возвращается: терять код из-за неприехавшего водителя нельзя."""
    admin = user_factory("ПромоАдмин12", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXIBACK", rub=100, name="ОтменаПас")
    _driver_online(client, user_factory, "ОтменаВод")

    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert order["promo_discount_kop"] == 10000
    cancelled = client.post(f"/instant/orders/{order['id']}/cancel", headers=pax["auth"],
                            json={"reason": "передумал"})
    assert cancelled.status_code == 200
    assert cancelled.json()["promo_discount_kop"] == 0

    red = _redemption(pax["id"])
    assert red.used_order_id is None and red.used_at is None
    est = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    assert est["promo_discount_kop"] == 10000                    # скидка снова в силе
    assert client.get("/promo/mine", headers=pax["auth"]).json()["discount_available"] is True


def test_expired_order_does_not_eat_discount(client, user_factory, fake_redis):
    """«Рядом никого» → заказ expired: скидка переезжает на следующий заказ, а не сгорает."""
    admin = user_factory("ПромоАдмин13", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXIWAIT", rub=100, name="ПустоПас")
    dead = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert dead["status"] == "expired"                            # водителей на линии нет
    assert dead["promo_discount_kop"] == 10000

    _driver_online(client, user_factory, "ПоздноВод")
    again = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert again["id"] != dead["id"]
    assert again["promo_discount_kop"] == 10000                   # скидка досталась новому заказу
    with Session(engine) as s:
        assert int(s.get(InstantOrder, dead["id"]).promo_discount_kop or 0) == 0   # и снята со старого


# ============================ Негатив: скидки быть не должно ============================
def test_deleted_driver_does_not_resurrect_spent_discount(client, user_factory, fake_redis):
    """Водитель удалил аккаунт → его заказы исчезли. Скидка пассажира уже ОТРАБОТАЛА и второй
    раз не выдаётся, хотя ссылки на заказ больше нет (иначе бесплатная поездка из воздуха)."""
    admin = user_factory("ПромоАдмин20", role=UserRole.admin)
    pax = _pax_with_discount(client, user_factory, admin, "TAXIDEL", rub=100, name="УдалПас")
    d = _driver_online(client, user_factory, "УдаляемыйВод")
    done = _ride_to_done(client, d, pax)
    assert done["promo_discount_kop"] == 10000

    with Session(engine) as s:                       # эмулируем каскад удаления аккаунта водителя
        from app.account import delete_user_account
        from app.models import User
        # долг по комиссии закрываем — иначе гейт /me/delete справедливо не пустит
        for row in s.exec(select(CommissionDebt).where(CommissionDebt.driver_id == d["id"])).all():
            s.delete(row)
        s.commit()
        delete_user_account(s, s.get(User, d["id"]))

    red = _redemption(pax["id"])
    assert red.used_order_id is None and red.used_at is not None   # ссылки нет, факт траты есть
    est = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    assert est["promo_discount_kop"] == 0
    assert client.get("/promo/mine", headers=pax["auth"]).json()["discount_available"] is False


def test_foreign_user_has_no_discount(client, user_factory):
    """Код активировал другой человек — у меня скидки нет."""
    admin = user_factory("ПромоАдмин14", role=UserRole.admin)
    _pax_with_discount(client, user_factory, admin, "TAXIMINE", rub=100, name="ВладелецКода")
    stranger = user_factory("ЧужойПас")
    est = client.post("/instant/estimate", headers=stranger["auth"], json=_order_body()).json()
    assert est["promo_discount_kop"] == 0


def test_expired_code_gives_no_discount(client, user_factory):
    """Просроченный код вообще не активируется (422) → скидки нет."""
    admin = user_factory("ПромоАдмин15", role=UserRole.admin)
    _create_promo(client, admin, "TAXIOLD", perk_value=100, valid_until=_past())
    pax = user_factory("ПросроченПас")
    r = client.post("/promo/apply", headers=pax["auth"], json={"code": "TAXIOLD"})
    assert r.status_code == 422
    est = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    assert est["promo_discount_kop"] == 0


def test_disabled_campaign_kills_unspent_discount(client, user_factory):
    """Кампанию выключили (напр. за абуз) → ещё не потраченная скидка гаснет."""
    admin = user_factory("ПромоАдмин16", role=UserRole.admin)
    created = _create_promo(client, admin, "TAXIOFF", perk_value=100)
    pax = user_factory("ВыклПас")
    assert client.post("/promo/apply", headers=pax["auth"], json={"code": "TAXIOFF"}).status_code == 200
    assert client.post("/instant/estimate", headers=pax["auth"],
                       json=_order_body()).json()["promo_discount_kop"] == 10000

    client.post(f"/admin/promo/{created['id']}/status", headers=admin["auth"], json={"active": False})
    assert client.post("/instant/estimate", headers=pax["auth"],
                       json=_order_body()).json()["promo_discount_kop"] == 0
    assert client.get("/promo/mine", headers=pax["auth"]).json()["discount_available"] is False


def test_zero_value_taxi_code_gives_no_discount(client, user_factory):
    """kind=taxi_ride с perk_value=0 — не скидка, а обычная атрибуция."""
    admin = user_factory("ПромоАдмин17", role=UserRole.admin)
    _create_promo(client, admin, "TAXIZERO", perk_value=0)
    pax = user_factory("НульПас")
    r = client.post("/promo/apply", headers=pax["auth"], json={"code": "TAXIZERO"})
    assert r.status_code == 200 and r.json()["discount_kop"] == 0
    assert client.post("/instant/estimate", headers=pax["auth"],
                       json=_order_body()).json()["promo_discount_kop"] == 0


# ============================ Старые виды кодов не сломались ============================
def test_welcome_and_boost_still_work(client, user_factory):
    """welcome — чистая атрибуция без скидки; boost — по-прежнему бесплатные поднятия."""
    admin = user_factory("ПромоАдмин18", role=UserRole.admin)
    _create_promo(client, admin, "OLDWELC", kind="welcome", perk_value=0)
    _create_promo(client, admin, "OLDBOOST", kind="boost", perk_value=3)

    w = user_factory("СтарыйWelcome")
    rw = client.post("/promo/apply", headers=w["auth"], json={"code": "OLDWELC"})
    assert rw.status_code == 200 and rw.json()["kind"] == "welcome"
    assert rw.json()["discount_kop"] == 0
    assert client.post("/instant/estimate", headers=w["auth"],
                       json=_order_body()).json()["promo_discount_kop"] == 0

    b = user_factory("СтарыйBoost")
    before = client.get("/referral/me", headers=b["auth"]).json()["credits"]
    rb = client.post("/promo/apply", headers=b["auth"], json={"code": "OLDBOOST"})
    assert rb.status_code == 200 and rb.json()["kind"] == "boost"
    assert rb.json()["discount_kop"] == 0
    assert client.get("/referral/me", headers=b["auth"]).json()["credits"] == before + 3
    assert client.post("/instant/estimate", headers=b["auth"],
                       json=_order_body()).json()["promo_discount_kop"] == 0


def test_boost_kind_price_untouched(client, user_factory):
    """Промокод-поднятия не трогает цену поездки — только taxi_ride даёт скидку."""
    admin = user_factory("ПромоАдмин19", role=UserRole.admin)
    _create_promo(client, admin, "BOOSTONLY", kind="boost", perk_value=2)
    pax = user_factory("БустПас")
    client.post("/promo/apply", headers=pax["auth"], json={"code": "BOOSTONLY"})
    est = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    assert est["price_with_discount"] == est["price"]
