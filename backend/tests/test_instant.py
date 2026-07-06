"""Тесты «Быстрого заказа» (Фаза 2) — ядро продукта, покрываем плотно:
тариф (сервер считает, клиент не влияет), presence, matcher (ближайший/радиус/фильтры/пусто),
полная машина состояний + запрет неверных переходов, гонка двух accept (→409),
таймаут оффера, отмены обеих сторон, приватность (телефон только после accept),
presence/заказ без Redis не падает.
"""
import threading

import fakeredis
import pytest
from fastapi import HTTPException
from sqlmodel import Session

from app.db import engine
from app import instant_service as isv
from app.models import InstantOrder, InstantOrderStatus as S, User, UserRole

# Координаты (lat, lng): Баймак — точка А; Сибай — точка Б; FAR10 — ~10 км от А.
ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)
FAR10 = (52.681, 58.317)


@pytest.fixture
def fake_redis():
    """Свежий fakeredis на тест + подмена клиента presence/matcher. После — сброс на None."""
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


# ------------------------------ helpers ------------------------------
def _driver_online(client, user_factory, name="Drv"):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    return d


def _heartbeat(client, d, coord):
    r = client.post("/instant/presence", headers=d["auth"], json={"lat": coord[0], "lng": coord[1]})
    assert r.status_code == 200, r.text
    return r.json()


def _order_body(frm=ORIG, to=DEST, **extra):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай", **extra}


def _create_order(client, pax, **extra):
    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body(**extra))
    assert r.status_code == 200, r.text
    return r.json()


# ============================ Тариф ============================
def test_estimate_server_computes(client, user_factory):
    pax = user_factory("EstPax")
    r = client.post("/instant/estimate", headers=pax["auth"], json=_order_body())
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["price"] % 10 == 0            # округление до 10 ₽
    assert body["price"] >= 150               # не ниже min_price города
    assert body["distance_km"] > 0 and body["eta_min"] > 0
    assert body["zone"] == "city"


def test_estimate_ignores_client_price(client, user_factory):
    """Сервер считает сам: подсунутая клиентом цена не влияет на результат."""
    pax = user_factory("EstPax2")
    honest = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()["price"]
    cheated = client.post("/instant/estimate", headers=pax["auth"],
                          json=_order_body(price=1, price_estimate=1, price_final=1)).json()["price"]
    assert cheated == honest                  # лишние поля цены проигнорированы


def test_estimate_zone_intercity(client, user_factory):
    """Дальняя поездка попадает в зону межгород (другой тариф)."""
    pax = user_factory("EstPax3")
    ufa = (54.735, 55.958)
    body = client.post("/instant/estimate", headers=pax["auth"], json=_order_body(to=ufa)).json()
    assert body["zone"] == "intercity"
    assert body["price"] >= 400


# ============================ Presence ============================
def test_presence_requires_online(client, user_factory, fake_redis):
    d = user_factory("PresDrv", role=UserRole.driver)   # НЕ на линии
    r = client.post("/instant/presence", headers=d["auth"], json={"lat": ORIG[0], "lng": ORIG[1]})
    assert r.status_code == 409


def test_presence_writes_geo(client, user_factory, fake_redis):
    d = _driver_online(client, user_factory, "PresOK")
    assert _heartbeat(client, d, ORIG)["ok"] is True
    assert fake_redis.exists(f"presence:hb:{d['id']}") == 1


def test_presence_no_redis_graceful(client, user_factory):
    """Без Redis heartbeat не падает — просто возвращает ok=False."""
    d = _driver_online(client, user_factory, "PresNoRedis")
    r = client.post("/instant/presence", headers=d["auth"], json={"lat": ORIG[0], "lng": ORIG[1]})
    assert r.status_code == 200 and r.json()["ok"] is False


# ============================ Matcher ============================
def test_matcher_offers_nearest(client, user_factory, fake_redis):
    """Ближайший водитель получает оффер (скоринг по подаче)."""
    near = _driver_online(client, user_factory, "Near")
    far = _driver_online(client, user_factory, "Far")
    _heartbeat(client, near, ORIG)
    _heartbeat(client, far, FAR10)
    pax = user_factory("MatchPax")
    order = _create_order(client, pax)
    assert order["status"] == "offered"
    # оффер ушёл ближайшему — проверяем в БД, т.к. payload не раскрывает оффер пассажиру
    with Session(engine) as s:
        o = s.get(InstantOrder, order["id"])
        assert o.current_offer_driver_id == near["id"]


def test_matcher_expands_radius(client, user_factory, fake_redis):
    """Круг 3 км пуст → расширяемся до 15 км и находим водителя в ~10 км."""
    d = _driver_online(client, user_factory, "Radius")
    _heartbeat(client, d, FAR10)              # ~10 км: вне 3 и 7, внутри 15
    pax = user_factory("RadiusPax")
    order = _create_order(client, pax)
    assert order["status"] == "offered"
    with Session(engine) as s:
        assert s.get(InstantOrder, order["id"]).current_offer_driver_id == d["id"]


def test_matcher_nobody_expires(client, user_factory, fake_redis):
    """Никого рядом → заказ expired («рядом никого»)."""
    pax = user_factory("EmptyPax")
    order = _create_order(client, pax)
    assert order["status"] == "expired"


def test_matcher_skips_unverified(client, user_factory, fake_redis):
    """Неверифицированный водитель не подходит → заказ expired."""
    d = _driver_online(client, user_factory, "Unverif")
    _heartbeat(client, d, ORIG)
    with Session(engine) as s:
        u = s.get(User, d["id"]); u.verified = False; s.add(u); s.commit()
    pax = user_factory("UnverifPax")
    assert _create_order(client, pax)["status"] == "expired"


def test_matcher_skips_busy(client, user_factory, fake_redis):
    """Занятый другим заказом водитель исключается → expired, если он один рядом."""
    d = _driver_online(client, user_factory, "Busy")
    _heartbeat(client, d, ORIG)
    pax0 = user_factory("BusyPax0")
    # искусственно делаем водителя занятым (активный заказ в accepted)
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax0["id"], from_lat=ORIG[0], from_lng=ORIG[1],
                         to_lat=DEST[0], to_lng=DEST[1], status=S.accepted, driver_id=d["id"])
        s.add(o); s.commit()
    pax = user_factory("BusyPax")
    assert _create_order(client, pax)["status"] == "expired"


def test_matcher_skips_blocked(client, user_factory, fake_redis):
    """Водитель в чёрном списке пассажира исключается → expired."""
    d = _driver_online(client, user_factory, "Blocked")
    _heartbeat(client, d, ORIG)
    pax = user_factory("BlockPax")
    from app.models import Block
    with Session(engine) as s:
        s.add(Block(user_id=pax["id"], blocked_user_id=d["id"])); s.commit()
    assert _create_order(client, pax)["status"] == "expired"


# ============================ Машина состояний ============================
def _offered_order(client, user_factory, fake_redis, dname="SmDrv", pname="SmPax"):
    d = _driver_online(client, user_factory, dname)
    _heartbeat(client, d, ORIG)
    pax = user_factory(pname)
    order = _create_order(client, pax)
    assert order["status"] == "offered"
    return d, pax, order


def test_state_machine_full_path(client, user_factory, fake_redis):
    d, pax, order = _offered_order(client, user_factory, fake_redis, "FullDrv", "FullPax")
    oid = order["id"]
    assert client.post(f"/instant/orders/{oid}/accept", headers=d["auth"]).json()["status"] == "accepted"
    assert client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"]).json()["status"] == "arriving"
    assert client.post(f"/instant/orders/{oid}/onboard", headers=d["auth"]).json()["status"] == "onboard"
    done = client.post(f"/instant/orders/{oid}/done", headers=d["auth"]).json()
    assert done["status"] == "done"
    assert done["price_final"] == order["price_estimate"]
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        assert o.driver_id == d["id"] and o.accepted_at and o.done_at


def test_invalid_transition_rejected(client, user_factory, fake_redis):
    """onboard до arriving нельзя; accept не тем водителем нельзя."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "InvDrv", "InvPax")
    oid = order["id"]
    # onboard пока offered — 409
    assert client.post(f"/instant/orders/{oid}/onboard", headers=d["auth"]).status_code == 409
    # чужой водитель принять не может — 403
    other = _driver_online(client, user_factory, "InvOther")
    assert client.post(f"/instant/orders/{oid}/accept", headers=other["auth"]).status_code == 403
    # пассажир не «принимает» (нет такого действия у пассажира) — accept ждёт водителя
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    # done сразу после accept (минуя arriving/onboard) — 409
    assert client.post(f"/instant/orders/{oid}/done", headers=d["auth"]).status_code == 409


def test_terminal_no_exit(client, user_factory, fake_redis):
    """Из done выхода нет: повторные переходы отклоняются."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "TermDrv", "TermPax")
    oid = order["id"]
    for path in ("accept", "arrived", "onboard", "done"):
        client.post(f"/instant/orders/{oid}/{path}", headers=d["auth"])
    assert client.get(f"/instant/orders/{oid}", headers=d["auth"]).json()["status"] == "done"
    assert client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"]).status_code == 409


def test_double_accept_race_second_409_sequential(client, user_factory, fake_redis):
    """Детерминированно: второй accept (source уже не offered) получает 409."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "RaceDrv", "RacePax")
    oid = order["id"]
    with Session(engine) as s1:
        o1 = isv.transition(s1, oid, isv.Actor.driver, S.accepted, d["id"], idempotent=False)
        assert o1.status == S.accepted
    with Session(engine) as s2, pytest.raises(HTTPException) as e:
        isv.transition(s2, oid, isv.Actor.driver, S.accepted, d["id"], idempotent=False)
    assert e.value.status_code == 409


def test_double_accept_race_threads(client, user_factory, fake_redis):
    """Под реальной гонкой два accept одного оффера → ровно один 200, один 409 (условный UPDATE)."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "TRaceDrv", "TRacePax")
    oid = order["id"]
    results, lock = [], threading.Lock()

    def attempt():
        code = client.post(f"/instant/orders/{oid}/accept", headers=d["auth"]).status_code
        with lock:
            results.append(code)

    ts = [threading.Thread(target=attempt) for _ in range(2)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert results.count(200) == 1, results
    assert results.count(409) == 1, results


def test_offer_timeout_advances(client, user_factory, fake_redis):
    """Протухший оффер (единственный водитель) → заказ expired при следующем чтении."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "ToDrv", "ToPax")
    oid = order["id"]
    from app.timeutil import utcnow
    from datetime import timedelta
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.offer_expires_at = utcnow() - timedelta(seconds=1)
        s.add(o); s.commit()
    # чтение заказа запускает ленивый reconcile таймаута
    body = client.get(f"/instant/orders/{oid}", headers=pax["auth"]).json()
    assert body["status"] == "expired"


def test_decline_moves_to_next(client, user_factory, fake_redis):
    """Отклонение оффера → matcher предлагает следующему кандидату."""
    d1 = _driver_online(client, user_factory, "Dec1")
    d2 = _driver_online(client, user_factory, "Dec2")
    _heartbeat(client, d1, ORIG)              # ближе — оффер ему первым
    _heartbeat(client, d2, FAR10)
    pax = user_factory("DecPax")
    order = _create_order(client, pax)
    first = order["id"]
    with Session(engine) as s:
        offered_to = s.get(InstantOrder, first).current_offer_driver_id
    decliner = d1 if offered_to == d1["id"] else d2
    other = d2 if decliner is d1 else d1
    client.post(f"/instant/orders/{first}/decline", headers=decliner["auth"])
    with Session(engine) as s:
        assert s.get(InstantOrder, first).current_offer_driver_id == other["id"]


# ============================ Отмены ============================
def test_passenger_cancel(client, user_factory, fake_redis):
    d, pax, order = _offered_order(client, user_factory, fake_redis, "CanDrv", "CanPax")
    oid = order["id"]
    r = client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"], json={"reason": "передумал"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    assert r.json()["cancel_by"] == "passenger"
    # идемпотентно
    assert client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"]).json()["status"] == "cancelled"


def test_driver_cancel_after_accept(client, user_factory, fake_redis):
    d, pax, order = _offered_order(client, user_factory, fake_redis, "DCanDrv", "DCanPax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    r = client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"], json={"reason": "поломка"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled" and r.json()["cancel_by"] == "driver"


def test_driver_cannot_cancel_before_accept(client, user_factory, fake_redis):
    """Водитель, которому только предложен оффер, не «отменяет» заказ (у него decline)."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "NoCanDrv", "NoCanPax")
    oid = order["id"]
    # оффер отправлен, но driver_id ещё не назначен → cancel от этого водителя = 403 (не его заказ)
    assert client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"]).status_code == 403


# ============================ Приватность ============================
def test_phone_hidden_until_accept(client, user_factory, fake_redis):
    d, pax, order = _offered_order(client, user_factory, fake_redis, "PrivDrv", "PrivPax")
    oid = order["id"]
    # до accept пассажир не видит телефон водителя
    before = client.get(f"/instant/orders/{oid}", headers=pax["auth"]).json()
    assert before["driver_phone"] == "" and before["driver_name"] == ""
    # после accept — телефон/имя водителя раскрыты пассажиру
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    after = client.get(f"/instant/orders/{oid}", headers=pax["auth"]).json()
    with Session(engine) as s:
        drv_phone = s.get(User, d["id"]).phone
    assert after["driver_phone"] == drv_phone and after["driver_name"]
    # водитель видит телефон пассажира только после accept
    drv_view = client.get(f"/instant/orders/{oid}", headers=d["auth"]).json()
    with Session(engine) as s:
        pax_phone = s.get(User, pax["id"]).phone
    assert drv_view["passenger_phone"] == pax_phone


def test_order_access_forbidden_for_outsider(client, user_factory, fake_redis):
    d, pax, order = _offered_order(client, user_factory, fake_redis, "OutDrv", "OutPax")
    outsider = user_factory("Outsider")
    assert client.get(f"/instant/orders/{order['id']}", headers=outsider["auth"]).status_code == 403


# ============================ Без Redis не падает ============================
def test_create_order_no_redis_graceful(client, user_factory):
    """Заказ без Redis не крашится — просто expired (matcher никого не находит)."""
    pax = user_factory("NoRedisPax")
    order = _create_order(client, pax)
    assert order["status"] == "expired"
