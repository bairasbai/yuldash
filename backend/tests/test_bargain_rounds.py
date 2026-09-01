# -*- coding: utf-8 -*-
"""Торг о цене — второй круг (механика inDrive, `docs/gaps-taxi-courier-2026-07-26.md` → «ПОТОМ»).

Было: отклик водителя = «бери или уходи». Водитель назвал 500, пассажир хотел 400 — сделка
просто не случалась, хотя обе стороны согласились бы на 450. В селе торговаться — привычка,
а не неудобство, и половина поездок гибла на разнице в полсотни рублей.

Правила, которые проверяем: ходят по очереди, свою цену принять нельзя, лимит ходов есть,
поездка создаётся по цене НА СТОЛЕ (а не по первой), торг видят обе стороны.
"""
import pytest
from sqlmodel import Session, select

from app import models as M
from app.db import engine
from app.models import UserRole
from app.routers.requests import BARGAIN_MAX_ROUNDS


@pytest.fixture()
def deal(client, user_factory):
    """Заявка пассажира + отклик водителя за 500 ₽ — стартовая позиция торга."""
    pax = user_factory(name="Пассажир-торг")
    drv = user_factory(name="Водитель-торг", role=UserRole.driver)
    r = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1, "max_price": 450,
    })
    assert r.status_code == 200, r.text
    rid = r.json()["id"]
    rr = client.post(f"/requests/{rid}/respond", headers=drv["auth"], json={"price": 500, "comment": "Еду в 8"})
    assert rr.status_code == 200, rr.text
    return {"pax": pax, "drv": drv, "request_id": rid, "response_id": rr.json()["id"]}


def _mine(client, deal_):
    """Отклик глазами водителя (его собственный список)."""
    rows = client.get("/responses/mine", headers=deal_["drv"]["auth"]).json()
    return next(x for x in rows if x["id"] == deal_["response_id"])


def _seen_by_passenger(client, deal_):
    rows = client.get(f"/requests/{deal_['request_id']}/responses", headers=deal_["pax"]["auth"]).json()
    return next(x for x in rows if x["id"] == deal_["response_id"])


# ============================== стартовая позиция ==============================

def test_first_offer_is_on_the_table(client, deal):
    """Пока торга не было, на столе стоит цена водителя — и ход пассажира."""
    row = _seen_by_passenger(client, deal)
    assert row["current_price"] == 500
    assert row["last_offer_by"] == "driver"
    assert row["can_accept"] is True and row["can_counter"] is True


def test_offer_carries_real_request_and_driver_context(client, deal):
    """Премиальная карточка не дорисовывает маршрут, бюджет и доверие моками."""
    with Session(engine) as s:
        s.add(M.DriverProfile(user_id=deal["drv"]["id"], car_make="Lada", car_model="Vesta"))
        s.commit()
    row = _seen_by_passenger(client, deal)
    assert row["request_from_city"] == "Сибай"
    assert row["request_to_city"] == "Уфа"
    assert row["request_seats"] == 1
    assert row["request_max_price"] == 450
    assert row["driver_verified"] is True
    assert row["driver_car"] == "Lada Vesta"
    assert row["driver_trips_count"] == 0


def test_driver_cannot_move_twice_in_a_row(client, deal):
    """Водитель уже сходил — второй раз подряд нельзя, иначе это давление, а не торг."""
    r = client.post(f"/responses/{deal['response_id']}/counter", headers=deal["drv"]["auth"], json={"price": 480})
    assert r.status_code == 409


# ============================== второй круг ==============================

def test_passenger_counters_and_driver_accepts(client, deal):
    """Главный сценарий: 500 → пассажир даёт 420 → водитель соглашается."""
    c = client.post(f"/responses/{deal['response_id']}/counter", headers=deal["pax"]["auth"], json={"price": 420})
    assert c.status_code == 200, c.text
    assert c.json()["current_price"] == 420 and c.json()["last_offer_by"] == "passenger"
    a = client.post(f"/responses/{deal['response_id']}/accept", headers=deal["drv"]["auth"])
    assert a.status_code == 200, a.text
    with Session(engine) as s:
        b = s.get(M.Booking, a.json()["booking_id"])
        ride = s.get(M.Ride, b.ride_id)
    assert ride.price == 420          # поездка по цене НА СТОЛЕ, а не по первой
    assert b.price == 420


def test_three_moves_meet_in_the_middle(client, deal):
    """500 → 400 → 450: ровно та сделка, которая раньше не случалась вообще."""
    client.post(f"/responses/{deal['response_id']}/counter", headers=deal["pax"]["auth"], json={"price": 400})
    client.post(f"/responses/{deal['response_id']}/counter", headers=deal["drv"]["auth"], json={"price": 450})
    a = client.post(f"/responses/{deal['response_id']}/accept", headers=deal["pax"]["auth"])
    assert a.status_code == 200, a.text
    with Session(engine) as s:
        ride = s.get(M.Ride, s.get(M.Booking, a.json()["booking_id"]).ride_id)
    assert ride.price == 450


def test_history_shows_how_the_haggling_went(client, deal):
    """Без истории торг превращается в «я же называл другую цену»."""
    client.post(f"/responses/{deal['response_id']}/counter", headers=deal["pax"]["auth"], json={"price": 400})
    client.post(f"/responses/{deal['response_id']}/counter", headers=deal["drv"]["auth"], json={"price": 450})
    assert _seen_by_passenger(client, deal)["bargain_history"] == "d:500,p:400,d:450"


# ============================== кто может ходить ==============================

def test_cannot_accept_your_own_price(client, deal):
    """Принять собственное предложение — не сделка, а повтор своих слов."""
    client.post(f"/responses/{deal['response_id']}/counter", headers=deal["pax"]["auth"], json={"price": 400})
    r = client.post(f"/responses/{deal['response_id']}/accept", headers=deal["pax"]["auth"])
    assert r.status_code == 409


def test_stranger_cannot_bargain(client, deal, user_factory):
    stranger = user_factory(name="Посторонний-торг")
    r = client.post(f"/responses/{deal['response_id']}/counter", headers=stranger["auth"], json={"price": 100})
    assert r.status_code == 403


def test_driver_sees_the_counter_offer_in_his_own_list(client, deal):
    """Без этого списка второй круг не работал бы: водитель узнавал бы о встречной только из пуша."""
    client.post(f"/responses/{deal['response_id']}/counter", headers=deal["pax"]["auth"], json={"price": 420})
    row = _mine(client, deal)
    assert row["current_price"] == 420
    assert row["can_accept"] is True and row["last_offer_by"] == "passenger"


def test_turn_flags_are_personal(client, deal):
    """Пассажир сходил → кнопки гаснут у него и загораются у водителя, а не у обоих сразу."""
    client.post(f"/responses/{deal['response_id']}/counter", headers=deal["pax"]["auth"], json={"price": 420})
    assert _seen_by_passenger(client, deal)["can_accept"] is False
    assert _mine(client, deal)["can_accept"] is True


# ============================== границы ==============================

def test_bargaining_has_an_end(client, deal):
    """Лимит ходов: иначе торг превращается в изматывание. Дальше — принять или отказаться."""
    actors = ["pax", "drv"]
    for i in range(BARGAIN_MAX_ROUNDS * 2):
        who = deal[actors[i % 2]]
        r = client.post(f"/responses/{deal['response_id']}/counter", headers=who["auth"], json={"price": 400 + i})
        assert r.status_code == 200, f"ход {i}: {r.text}"
    nxt = deal[actors[(BARGAIN_MAX_ROUNDS * 2) % 2]]
    r = client.post(f"/responses/{deal['response_id']}/counter", headers=nxt["auth"], json={"price": 999})
    assert r.status_code == 409
    assert _seen_by_passenger(client, deal)["can_counter"] is False


def test_accept_still_works_after_the_limit(client, deal):
    """Лимит закрывает торг, но не сделку — иначе мы бы просто ломали людям поездку."""
    actors = ["pax", "drv"]
    for i in range(BARGAIN_MAX_ROUNDS * 2):
        client.post(f"/responses/{deal['response_id']}/counter",
                    headers=deal[actors[i % 2]]["auth"], json={"price": 430})
    last_by = _seen_by_passenger(client, deal)["last_offer_by"]
    taker = deal["pax"] if last_by == "driver" else deal["drv"]
    assert client.post(f"/responses/{deal['response_id']}/accept", headers=taker["auth"]).status_code == 200


def test_decline_closes_the_bargain_but_not_the_request(client, deal, user_factory):
    """Не договорились — заявка остаётся живой: другие водители продолжают откликаться."""
    d = client.post(f"/responses/{deal['response_id']}/decline", headers=deal["pax"]["auth"])
    assert d.status_code == 200, d.text
    assert client.post(f"/responses/{deal['response_id']}/accept",
                       headers=deal["pax"]["auth"]).status_code == 409
    other = user_factory(name="Водитель-второй", role=UserRole.driver)
    r = client.post(f"/requests/{deal['request_id']}/respond", headers=other["auth"], json={"price": 430})
    assert r.status_code == 200, r.text


def test_decline_is_idempotent(client, deal):
    client.post(f"/responses/{deal['response_id']}/decline", headers=deal["drv"]["auth"])
    r = client.post(f"/responses/{deal['response_id']}/decline", headers=deal["drv"]["auth"])
    assert r.status_code == 200 and r.json().get("already") is True


def test_counter_is_clamped_to_the_price_cap(client, deal):
    """Тот же потолок, что при создании поездки — торг не обходит общий лимит."""
    r = client.post(f"/responses/{deal['response_id']}/counter", headers=deal["pax"]["auth"],
                    json={"price": 1_000_000})
    assert r.status_code == 422


def test_bargaining_stops_when_request_is_closed(client, deal):
    """Заявка закрыта → торговаться не о чем."""
    client.post(f"/requests/{deal['request_id']}/cancel", headers=deal["pax"]["auth"])
    r = client.post(f"/responses/{deal['response_id']}/counter", headers=deal["pax"]["auth"], json={"price": 400})
    assert r.status_code == 409


def test_old_response_without_bargain_fields_still_works(client, user_factory):
    """Отклики, созданные до торга (current_price=0), не должны выглядеть как «0 ₽»."""
    pax = user_factory(name="Пассажир-старый")
    drv = user_factory(name="Водитель-старый", role=UserRole.driver)
    rid = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Баймак", "to_city": "Уфа", "seats": 1}).json()["id"]
    resp_id = client.post(f"/requests/{rid}/respond", headers=drv["auth"], json={"price": 600}).json()["id"]
    with Session(engine) as s:                       # имитируем строку до миграции
        row = s.get(M.RequestResponse, resp_id)
        row.current_price = 0
        s.add(row); s.commit()
    rows = client.get(f"/requests/{rid}/responses", headers=pax["auth"]).json()
    assert next(x for x in rows if x["id"] == resp_id)["current_price"] == 600
    a = client.post(f"/responses/{resp_id}/accept", headers=pax["auth"])
    assert a.status_code == 200, a.text
    with Session(engine) as s:
        ride = s.get(M.Ride, s.get(M.Booking, a.json()["booking_id"]).ride_id)
    assert ride.price == 600


def test_my_responses_lists_only_mine(client, deal, user_factory):
    other = user_factory(name="Водитель-чужой", role=UserRole.driver)
    rows = client.get("/responses/mine", headers=other["auth"]).json()
    assert all(x["id"] != deal["response_id"] for x in rows)


def test_accepted_response_is_not_open_for_bargaining(client, deal):
    client.post(f"/responses/{deal['response_id']}/accept", headers=deal["pax"]["auth"])
    r = client.post(f"/responses/{deal['response_id']}/counter", headers=deal["pax"]["auth"], json={"price": 300})
    assert r.status_code == 409


def test_automatch_ranks_by_price_on_the_table(client, user_factory):
    """Авто-подбор сравнивает ту же цену, по которой создастся поездка."""
    from app.automatch import _best_response
    pax = user_factory(name="Пассажир-автоподбор")
    d1 = user_factory(name="Водитель-А", role=UserRole.driver)
    d2 = user_factory(name="Водитель-Б", role=UserRole.driver)
    rid = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Учалы", "to_city": "Уфа", "seats": 1}).json()["id"]
    r1 = client.post(f"/requests/{rid}/respond", headers=d1["auth"], json={"price": 700}).json()["id"]
    client.post(f"/requests/{rid}/respond", headers=d2["auth"], json={"price": 600})
    client.post(f"/responses/{r1}/counter", headers=pax["auth"], json={"price": 300})   # А стал дешевле
    with Session(engine) as s:
        offers = s.exec(select(M.RequestResponse).where(M.RequestResponse.request_id == rid)).all()
        best = _best_response(s, offers)
    assert best.id == r1
