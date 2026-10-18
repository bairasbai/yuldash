"""Место посадки в брони: где садится пассажир (решение Александра 18.10.2026, PR1 серии).

Что было. На поездке с остановками (`Ride.waypoints`) бронь не знала, где садится пассажир. Водитель
узнавал это в чате (если узнавал), а правила «Подъезжаю»/«Не пришёл» не могли отличить пассажира у
пина в начале маршрута от пассажирки на остановке за 40 км.

Что стало (сервер):
* `Booking.pickup_stop`: "" — начало маршрута, «Ургаза» — остановка (написание как в поездке),
  NULL — неизвестно (правила PR184). Координат не храним.
* Бронь принимает только начало маршрута (любым написанием) или остановку из поездки; конец
  маршрута и незнакомое место — 400 с кодом, мест не тронули. Не прислали / пусто — NULL
  (невнимательный пассажир остановки не попадает в правила «начала»). Повтор с другим местом — 409.
* Водитель видит место в `/driver/bookings` и деталях (у живой и недавно завершённой брони), пуш —
  только «посадка по пути» без названия села (экран блокировки). Пассажир видит своё. Другие
  пассажиры, лента, карточка поездки, ссылка близким — ничего нового.
* Остановки при публикации — только названия (буквы, пробел, дефис, точка, скобки; ≤ 60; без цифр;
  ≤ 4), в публичной выдаче старых поездок недопустимые части не показываются.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

import app.services as svc
from app.db import engine
from app.geo import lookup, normalize_place, snapshot_cached
from app.models import Booking, Notification, Ride, UserRole
from app.ride_stops import (canonical_stop, parse_stops, pickup_of, ride_has_stops_raw, stop_point,
                            stop_text_ok)
from app.timeutil import utcnow

STOPS = "Ургаза | Бурибай"          # сёла Баймакского и Хайбуллинского районов по пути Сибай → Акъяр
SIBAY = (52.716, 58.664)


def _when(days: int = 400) -> str:
    """Выезд далеко: правила брони от времени не зависят. Чужие ленты рейсы этого файла не теснят —
    после каждого теста они полные (`_rides_full_after_test`)."""
    return (utcnow() + timedelta(days=days)).replace(microsecond=0).isoformat() + "+00:00"


@pytest.fixture(autouse=True)
def _rides_full_after_test():
    """Рейсы этого файла (`comment` «pickup-…») после теста — без свободных мест: полный рейс не попадает
    ни в ленту, ни в «Ближайшие», и тесты общей базы, которые листают ленту, их не видят (A-P3-4)."""
    yield
    with Session(engine) as s:
        for r in s.exec(select(Ride).where(Ride.comment.startswith("pickup-"), Ride.seats_left > 0)).all():
            r.seats_left = 0
            s.add(r)
        s.commit()


def _publish(client, drv, tag: str, *, waypoints: str = STOPS, frm: str = "Сибай", to: str = "Акъяр",
             seats: int = 3, depart: str | None = None, pin: bool = True):
    body = {"from_city": frm, "to_city": to, "depart_at": depart or _when(), "seats_total": seats,
            "price": 700, "comment": f"pickup-{tag}", "waypoints": waypoints,
            "pickup": "У автовокзала"}
    if pin:
        body |= {"pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1]}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id: int, **extra):
    return client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1, **extra})


def _seats(ride_id: int) -> int:
    with Session(engine) as s:
        return s.get(Ride, ride_id).seats_left


def _stored(bid: int):
    with Session(engine) as s:
        return s.get(Booking, bid).pickup_stop


@pytest.fixture
def pushes(monkeypatch) -> list:
    got: list = []
    monkeypatch.setattr(svc, "send_push", lambda session, uid, title, body, **k: got.append((uid, title, body)))
    return got


# ── Что записывается в бронь ──

@pytest.mark.parametrize("sent,stored", [
    ("Ургаза", "Ургаза"),
    ("Урғаҙа", "Ургаза"),          # башкирское написание той же остановки — запись как в поездке
    ("  ургаза ", "Ургаза"),        # регистр и пробелы
    ("Сибай", ""),                  # начало маршрута — только его названием
    ("с. Сибай", ""),               # приставка «с.» снимается
    ("", None),                     # пусто — неизвестно (P2-5), а не «начало»
    (None, None),                   # старое приложение поле не шлёт
])
def test_what_the_booking_stores(client, user_factory, sent, stored):
    drv = user_factory("PS Driver", role=UserRole.driver)
    pax = user_factory("PS Pax")
    ride = _publish(client, drv, f"store-{sent}-{stored}-{pax['id']}")
    extra = {} if sent is None else {"pickup_stop": sent}
    r = _book(client, pax, ride["id"], **extra)
    assert r.status_code == 200, r.text
    assert r.json()["pickup_stop"] == stored
    assert _stored(r.json()["id"]) == stored


def test_bashkir_spelling_of_the_start_is_the_start(client, user_factory):
    """«Баймаҡ» на поездке «Баймак → …» — начало маршрута, а не 400 (P3)."""
    drv = user_factory("PS BA Driver", role=UserRole.driver)
    pax = user_factory("PS BA Pax")
    ride = _publish(client, drv, "ba-start", frm="Баймак", to="Акъяр", waypoints="Ургаза")
    r = _book(client, pax, ride["id"], pickup_stop="Баймаҡ")
    assert r.status_code == 200 and r.json()["pickup_stop"] == "", r.text


@pytest.mark.parametrize("sent,code", [
    ("Акъяр", "pickup_stop_is_end"),
    ("Аҡъяр", "pickup_stop_is_end"),
    ("Темясово", "pickup_stop_unknown"),
    ("мой дом у школы", "pickup_stop_unknown"),
])
def test_end_or_unknown_place_is_refused_before_seats(client, user_factory, sent, code):
    drv = user_factory("PS Refuse Driver", role=UserRole.driver)
    pax = user_factory("PS Refuse Pax")
    ride = _publish(client, drv, f"refuse-{code}-{pax['id']}")
    before = _seats(ride["id"])
    r = _book(client, pax, ride["id"], pickup_stop=sent)
    assert r.status_code == 400, r.text
    d = r.json()["detail"]
    assert d["code"] == code and d["ru"] and d["ba"] and d["ru"] != d["ba"]
    assert _seats(ride["id"]) == before, "мест не тронули"
    with Session(engine) as s:
        assert s.exec(select(Booking).where(Booking.ride_id == ride["id"])).first() is None


def test_too_long_place_is_refused(client, user_factory):
    drv = user_factory("PS Long Driver", role=UserRole.driver)
    pax = user_factory("PS Long Pax")
    ride = _publish(client, drv, "long")
    assert _book(client, pax, ride["id"], pickup_stop="а" * 61).status_code == 422


def test_ride_without_stops_always_stores_the_start(client, user_factory):
    drv = user_factory("PS NoStops Driver", role=UserRole.driver)
    pax = user_factory("PS NoStops Pax")
    ride = _publish(client, drv, "nostops", waypoints="")
    r = _book(client, pax, ride["id"], pickup_stop="Ургаза")
    assert r.status_code == 200 and r.json()["pickup_stop"] == "", r.text


def test_raw_stops_without_valid_ones_store_unknown(client, user_factory):
    """Старая поездка с «ул. Ленина 5» в остановках: показать нечего, но правила остановок не
    выключаются — бронь NULL (P2-В), даже если пассажир назвал начало маршрута."""
    drv = user_factory("PS Raw Driver", role=UserRole.driver)
    pax = user_factory("PS Raw Pax")
    ride = _publish(client, drv, "raw", waypoints="")
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        r.waypoints = "ул. Ленина 5 | 89170000000"
        s.add(r)
        s.commit()
        assert ride_has_stops_raw(r) is True and parse_stops(r) == []
    r = _book(client, pax, ride["id"], pickup_stop="Сибай")
    assert r.status_code == 200 and r.json()["pickup_stop"] is None, r.text


def test_repeat_with_another_place_is_409_same_place_is_idempotent(client, user_factory):
    drv = user_factory("PS Repeat Driver", role=UserRole.driver)
    pax = user_factory("PS Repeat Pax")
    ride = _publish(client, drv, "repeat")
    first = _book(client, pax, ride["id"], pickup_stop="Ургаза")
    assert first.status_code == 200
    again = _book(client, pax, ride["id"], pickup_stop="Ургаза")
    assert again.status_code == 200 and again.json()["id"] == first.json()["id"]
    silent = _book(client, pax, ride["id"])          # повтор без места — та же бронь
    assert silent.status_code == 200 and silent.json()["id"] == first.json()["id"]
    other = _book(client, pax, ride["id"], pickup_stop="Бурибай")
    assert other.status_code == 409 and other.json()["detail"]["code"] == "pickup_stop_conflict", other.text
    assert _stored(first.json()["id"]) == "Ургаза"


def test_old_unknown_booking_is_returned_as_is(client, user_factory):
    drv = user_factory("PS OldNull Driver", role=UserRole.driver)
    pax = user_factory("PS OldNull Pax")
    ride = _publish(client, drv, "oldnull")
    first = _book(client, pax, ride["id"])
    assert first.json()["pickup_stop"] is None
    again = _book(client, pax, ride["id"], pickup_stop="Ургаза")
    assert again.status_code == 200 and again.json()["id"] == first.json()["id"]
    assert _stored(first.json()["id"]) is None


# ── Кто что видит ──

def test_driver_push_says_on_the_way_without_the_village(client, user_factory, pushes):
    drv = user_factory("PS Push Driver", role=UserRole.driver)
    pax = user_factory("PS Push Pax")
    ride = _publish(client, drv, "push")
    pushes.clear()
    bid = _book(client, pax, ride["id"], pickup_stop="Ургаза").json()["id"]
    mine = [(t, b) for uid, t, b in pushes if uid == drv["id"]]
    assert mine and "посадка по пути" in mine[0][1], mine
    assert all("Ургаза" not in t and "Ургаза" not in b for t, b in mine), "название села — не на экран блокировки"
    with Session(engine) as s:
        n = s.exec(select(Notification).where(Notification.ref_id == bid,
                                              Notification.user_id == drv["id"])).first()
        assert "посадка по пути" in n.body_ru and "юл ыңғайында ултыра" in n.body_ba
        assert "Ургаза" not in n.body_ru + n.body_ba + n.title_ru + n.title_ba


def test_start_and_unknown_bookings_push_without_the_mark(client, user_factory, pushes):
    drv = user_factory("PS Push2 Driver", role=UserRole.driver)
    ride = _publish(client, drv, "push2")
    for place in ("Сибай", None):
        pax = user_factory("PS Push2 Pax")
        pushes.clear()
        _book(client, pax, ride["id"], **({} if place is None else {"pickup_stop": place}))
        assert all("посадка по пути" not in b for uid, _t, b in pushes if uid == drv["id"])


def _row(client, drv, bid):
    return next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == bid)


def test_driver_sees_the_place_while_the_booking_lives(client, user_factory):
    drv = user_factory("PS Row Driver", role=UserRole.driver)
    pax_stop, pax_start, pax_null = (user_factory(f"PS Row {i}") for i in range(3))
    ride = _publish(client, drv, "rows")
    b_stop = _book(client, pax_stop, ride["id"], pickup_stop="Ургаза").json()["id"]
    b_start = _book(client, pax_start, ride["id"], pickup_stop="Сибай").json()["id"]
    b_null = _book(client, pax_null, ride["id"]).json()["id"]
    assert _row(client, drv, b_stop)["pickup_stop"] == "Ургаза"
    assert _row(client, drv, b_start)["pickup_stop"] == ""
    row_null = _row(client, drv, b_null)
    assert row_null["pickup_stop"] is None and row_null["ride_has_stops"] is True
    # Отменённая бронь села пассажирки водителю больше не показывает (как контакты взрослого).
    assert client.post(f"/bookings/{b_stop}/cancel", headers=pax_stop["auth"]).status_code == 200
    assert _row(client, drv, b_stop)["pickup_stop"] is None


def test_ride_without_stops_row(client, user_factory):
    drv = user_factory("PS Row2 Driver", role=UserRole.driver)
    pax = user_factory("PS Row2 Pax")
    ride = _publish(client, drv, "rows2", waypoints="")
    bid = _book(client, pax, ride["id"]).json()["id"]
    row = _row(client, drv, bid)
    assert row["pickup_stop"] == "" and row["ride_has_stops"] is False


def test_details_stop_booking_meets_at_the_stop_not_the_pin(client, user_factory):
    drv = user_factory("PS Det Driver", role=UserRole.driver)
    pax = user_factory("PS Det Pax")
    ride = _publish(client, drv, "details")
    bid = _book(client, pax, ride["id"], pickup_stop="Ургаза").json()["id"]
    pending = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert pending["pickup_stop"] == "Ургаза" and pending["pickup"] == "" and pending["pickup_lat"] is None
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    for who in (pax, drv):
        d = client.get(f"/bookings/{bid}/details", headers=who["auth"]).json()
        assert d["pickup_stop"] == "Ургаза" and d["ride_has_stops"] is True
        assert d["pickup"] == "Ургаза", "место встречи брони на остановке — сама остановка"
        assert d["pickup_lat"] is None and d["pickup_lng"] is None, "пин начала маршрута ей не отдаём"


def test_details_start_booking_keeps_the_pin(client, user_factory):
    drv = user_factory("PS Det2 Driver", role=UserRole.driver)
    pax = user_factory("PS Det2 Pax")
    ride = _publish(client, drv, "details2")
    bid = _book(client, pax, ride["id"], pickup_stop="Сибай").json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    d = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert d["pickup_stop"] == "" and d["pickup"] == "У автовокзала"
    assert (d["pickup_lat"], d["pickup_lng"]) == SIBAY


def test_passenger_sees_own_place_in_my_bookings_and_export(client, user_factory):
    drv = user_factory("PS Mine Driver", role=UserRole.driver)
    pax = user_factory("PS Mine Pax")
    ride = _publish(client, drv, "mine")
    bid = _book(client, pax, ride["id"], pickup_stop="Бурибай").json()["id"]
    mine = next(x for x in client.get("/bookings/mine", headers=pax["auth"]).json() if x["id"] == bid)
    assert mine["pickup_stop"] == "Бурибай"
    text = client.get("/me/export", headers=pax["auth"]).text
    assert "посадка: Бурибай" in text
    assert "ултырыу: Бурибай" in client.get("/me/export", params={"lang": "ba"}, headers=pax["auth"]).text


def test_cancel_answer_does_not_carry_the_place(client, user_factory):
    drv = user_factory("PS Out Driver", role=UserRole.driver)
    pax = user_factory("PS Out Pax")
    ride = _publish(client, drv, "out")
    bid = _book(client, pax, ride["id"], pickup_stop="Ургаза").json()["id"]
    r = client.post(f"/bookings/{bid}/cancel", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["pickup_stop"] is None
    assert _stored(bid) == "Ургаза", "в базе место осталось — пассажир видит его в своей истории"
    # Детали отменённой брони: водителю место больше не показываем, пассажиру — своё (A-P3-2).
    assert client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()["pickup_stop"] is None
    assert client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()["pickup_stop"] == "Ургаза"


def test_driver_loses_the_place_after_the_contacts_window(client, user_factory):
    """Завершённая бронь: место посадки водителю видно, пока открыты контакты (окно после поездки),
    дальше — нет, как телефон самого пассажира (A-P3-2)."""
    from app.config import settings
    from app.models import BookingStatus
    drv = user_factory("PS Old Driver", role=UserRole.driver)
    pax = user_factory("PS Old Pax")
    ride = _publish(client, drv, "olddone")
    bid = _book(client, pax, ride["id"], pickup_stop="Ургаза").json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200

    def done_hours_ago(hours: float):
        with Session(engine) as s:
            b = s.get(Booking, bid)
            r = s.get(Ride, ride["id"])
            b.status = BookingStatus.done
            r.depart_at = utcnow() - timedelta(hours=hours)
            s.add(b)
            s.add(r)
            s.commit()

    done_hours_ago(1)
    assert _row(client, drv, bid)["pickup_stop"] == "Ургаза", "недавно завершённая — ещё видно"
    assert client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()["pickup_stop"] == "Ургаза"
    done_hours_ago(settings.chat_after_trip_hours + 1)
    assert _row(client, drv, bid)["pickup_stop"] is None
    assert client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()["pickup_stop"] is None
    assert client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()["pickup_stop"] == "Ургаза"


def test_others_and_public_outputs_learn_nothing(client, user_factory):
    """Другой пассажир, лента, карточка поездки, превью `/r/{id}` — ни слова о месте чужой посадки."""
    drv = user_factory("PS Pub Driver", role=UserRole.driver)
    pax, other = user_factory("PS Pub Pax"), user_factory("PS Pub Other")
    ride = _publish(client, drv, "public", waypoints="Ургаза | Бурибай", seats=3)
    _book(client, pax, ride["id"], pickup_stop="Бурибай")
    other_bid = _book(client, other, ride["id"], pickup_stop="Сибай").json()["id"]
    card = client.get(f"/rides/{ride['id']}", headers=other["auth"]).json()
    assert "pickup_stop" not in card
    preview = client.get(f"/r/{ride['id']}/preview").json()
    assert "pickup_stop" not in preview
    other_details = client.get(f"/bookings/{other_bid}/details", headers=other["auth"]).json()
    assert other_details["pickup_stop"] == "", "в своей брони — только своё место"
    other_mine = client.get("/bookings/mine", headers=other["auth"]).json()
    assert all(x.get("pickup_stop") in ("", None) for x in other_mine)


# ── Публикация и чистка остановок ──

@pytest.mark.parametrize("waypoints,code", [
    ("Ургаза | ул. Ленина 5", "stop_invalid"),
    ("Ургаза | 89171234567", "stop_invalid"),
    ("Ургаза | ⑧⑨①⑦①②③④⑤⑥⑦", "stop_invalid"),           # телефон обведёнными цифрами (S-P3-1)
    ("Тубинский, поворот на Юлук", "stop_invalid"),     # запятая — не разделитель
    ("Ургаза | Бурибай | Акмурун | Ишмурзино | Юлбарсово", "stops_too_many"),
    ("а" * 61, "stop_invalid"),
])
def test_publish_refuses_bad_stops(client, user_factory, waypoints, code):
    drv = user_factory("PS Pub Bad Driver", role=UserRole.driver)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Акъяр", "depart_at": _when(), "seats_total": 2, "price": 500,
        "waypoints": waypoints})
    assert r.status_code == 400, r.text
    d = r.json()["detail"]
    assert d["code"] == code and d["ru"] and d["ba"] and d["ru"] != d["ba"]


def test_publish_takes_real_village_names_and_normalizes(client, user_factory):
    """Без «запретных слов»: «Улу-Теляк», «Кваркено», «Домашкино», «д. Акмурун», «(Баймакский р-н)»."""
    drv = user_factory("PS Pub Ok Driver", role=UserRole.driver)
    ride = _publish(client, drv, "pubok", waypoints="  Улу-Теляк | | Кваркено |Домашкино| д. Акмурун ")
    assert ride["waypoints"] == "Улу-Теляк | Кваркено | Домашкино | д. Акмурун"
    ride2 = _publish(client, drv, "pubok2", waypoints="Абзаково (Баймакский р-н) | Һаҡмар")
    assert ride2["waypoints"] == "Абзаково (Баймакский р-н) | Һаҡмар"


def test_old_ride_bad_stops_are_hidden_in_public_outputs(client, user_factory):
    """«Ближайшие» — только этот день и этот маршрут: тест не зависит от чужих поездок общей базы
    (свой день; остальные рейсы файла после тестов полные)."""
    drv = user_factory("PS Clean Driver", role=UserRole.driver)
    depart = _when(days=433)
    ride = _publish(client, drv, "clean", waypoints="Ургаза", frm="Сибай", to="Акъяр", depart=depart)
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        r.waypoints = "Ургаза | ул. Ленина 5 | +7 917 000-00-00 | Сибай"
        s.add(r)
        s.commit()
    card = client.get(f"/rides/{ride['id']}").json()
    assert card["waypoints"] == "Ургаза", card["waypoints"]
    near = client.get("/rides/near", params={"from_city": "Сибай", "to_city": "Акъяр", "date": depart[:10]}).json()
    mine = [x for x in near["items"] if x["id"] == ride["id"]]
    assert mine and mine[0]["waypoints"] == "Ургаза", near


# ── Справочник и правила, без сервера ──

def test_normalize_place_strips_prefixes():
    assert normalize_place("д. Акмурун") == "Акмурун"
    assert normalize_place("с.Ургаза") == "Ургаза"
    assert normalize_place("пос. Бурибай") == "Бурибай"
    assert normalize_place("деревня Ургаза") == "Ургаза"
    assert normalize_place("Сибай") == "Сибай"
    assert normalize_place("Домашкино") == "Домашкино"


def test_stop_text_rule():
    for ok in ("Ургаза", "Улу-Теляк", "Һаҡмар", "д. Акмурун", "Абзаково (Баймакский р-н)", "Kvarkeno"):
        assert stop_text_ok(ok), ok
    for bad in ("ул. Ленина 5", "8917", "Ургаза, Сибай", "", " ", ".", "а" * 61, "дом_5"):
        assert not stop_text_ok(bad), bad


@pytest.mark.parametrize("text", [
    "⑧⑨①⑦①②③④⑤⑥⑦",            # обведённые цифры — для `\d` не цифры
    "Акъяр ⁸⁹¹⁷¹²³⁴⁵⁶⁷",           # надстрочные
    "Ленина Ⅻ",                     # римская цифра
    "Ургаза ٥",                     # арабско-индийская цифра
])
def test_stop_text_rejects_unicode_digit_lookalikes(text):
    """Телефон или номер дома, набранный «цифрами Юникода», — не название села (S-P3-1)."""
    assert stop_text_ok(text) is False


def _ride_obj(**kw) -> Ride:
    base = {"driver_id": 1, "from_city": "Сибай", "to_city": "Акъяр", "depart_at": utcnow(),
            "from_lat": 52.716, "from_lng": 58.664, "to_lat": 51.872, "to_lng": 58.236, "waypoints": STOPS}
    return Ride(**{**base, **kw})


def test_stops_equal_to_the_ends_are_not_stops(client):
    entry = snapshot_cached()
    r = _ride_obj(from_city="Уфа", to_city="Баймак", waypoints="Өфө | Баймаҡ | д. Уфа")
    assert ride_has_stops_raw(r, entry) is False and parse_stops(r, entry) == []
    r2 = _ride_obj(waypoints="Сибай | Ургаза | ургаза")
    assert parse_stops(r2, entry) == ["Ургаза"], "без начала маршрута и без повтора"


def test_namesakes_resolve_by_the_route_corridor(client):
    """Абзаково — три тёзки (Баймакский, Белорецкий, Учалинский р-н). Баймак → Сибай: в коридоре одна
    — она и есть. Сибай → Белорецк: в коридоре две — «неизвестна». С районом — ровно та."""
    entry = snapshot_cached()
    assert len(lookup(entry, "Абзаково")) >= 3
    near_baymak = _ride_obj(from_city="Баймак", to_city="Сибай", from_lat=52.591, from_lng=58.317,
                            to_lat=52.716, to_lng=58.664, waypoints="Абзаково")
    p = stop_point(near_baymak, "Абзаково", entry)
    assert p is not None and abs(p.lat - 52.804) < 0.01 and p.kind == "village"
    two = _ride_obj(from_city="Сибай", to_city="Белорецк", to_lat=53.968, to_lng=58.410, waypoints="Абзаково")
    assert stop_point(two, "Абзаково", entry) is None
    explicit = stop_point(two, "Абзаково (Белорецкий р-н)", entry)
    assert explicit is not None and abs(explicit.lat - 53.83) < 0.01


def test_far_from_the_corridor_or_no_route_coords_is_unknown(client):
    entry = snapshot_cached()
    far = _ride_obj(waypoints="Ургаза", from_city="Уфа", to_city="Стерлитамак", from_lat=54.735,
                    from_lng=55.958, to_lat=53.630, to_lng=55.950)
    assert stop_point(far, "Ургаза", entry) is None, "Ургаза за 150 км от этой дороги — не наша"
    nocoords = _ride_obj(from_lat=None, from_lng=None, to_lat=None, to_lng=None)
    assert stop_point(nocoords, "Ургаза", entry) is None, "без координат концов тёзок не отличить"


def test_canonical_stop_and_pickup_kinds(client):
    entry = snapshot_cached()
    r = _ride_obj()
    assert canonical_stop(r, "Урғаҙа", entry) == "Ургаза"
    assert canonical_stop(r, "Темясово", entry) is None
    assert pickup_of(r, Booking(pickup_stop=None), entry).kind == "unknown"
    assert pickup_of(r, Booking(pickup_stop=""), entry).kind == "start"
    stop = pickup_of(r, Booking(pickup_stop="Ургаза"), entry)
    assert stop.kind == "stop" and stop.point is not None and 30 < stop.km < 50
    assert pickup_of(_ride_obj(waypoints=""), Booking(pickup_stop="Ургаза"), entry).kind == "start"
    unknown = pickup_of(_ride_obj(waypoints="Поворот на Юлук | Бурибай"), Booking(pickup_stop="Поворот на Юлук"), entry)
    assert unknown.kind == "stop" and unknown.point is None and unknown.est_km is not None


# ── Миграция ──

def test_migration_adds_an_empty_column_and_back(tmp_path):
    """Старая база: колонка появляется пустой у всех броней (старым местом не выдумываем); повтор —
    без изменений; откат убирает колонку."""
    import importlib.util
    from pathlib import Path

    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    from sqlalchemy import create_engine, inspect, text

    path = Path(__file__).resolve().parents[1] / "alembic" / "versions" / "bz_booking_pickup_stop_20261018.py"
    spec = importlib.util.spec_from_file_location("bz_pickup_stop_mig", path)
    mig = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mig)
    assert mig.down_revision == "by_booking_arriving_at_20261018"
    eng = create_engine(f"sqlite:///{(tmp_path / 'old.db').as_posix()}")
    with eng.begin() as c:
        c.execute(text("CREATE TABLE booking (id INTEGER PRIMARY KEY, ride_id INTEGER, status VARCHAR)"))
        c.execute(text("INSERT INTO booking VALUES (1, 1, 'confirmed'), (2, 1, 'done')"))
        with Operations.context(MigrationContext.configure(c)):
            mig.upgrade()
            mig.upgrade()   # идемпотентно
        assert c.execute(text("SELECT COUNT(*) FROM booking WHERE pickup_stop IS NOT NULL")).scalar_one() == 0
        c.execute(text("INSERT INTO booking (id, ride_id, status) VALUES (3, 1, 'pending')"))   # старый код
    with eng.begin() as c:
        with Operations.context(MigrationContext.configure(c)):
            mig.downgrade()
        assert "pickup_stop" not in {x["name"] for x in inspect(c).get_columns("booking")}
