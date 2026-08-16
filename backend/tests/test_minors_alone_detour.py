# -*- coding: utf-8 -*-
"""Три дыры третьей волны разбора конкурентов (2026-08-07).

① Несовершеннолетний пассажир. Возраст не спрашивался нигде: подросток регистрировался и
   садился к незнакомому человеку, водитель об этом не знал, а отвечать пришлось бы ему.
   Запретить нельзя — сайт прямо обещает «школьник доберётся», в районе это реальная нужда.
   Поэтому не запрет, а согласие взрослого (имя + телефон) и честная видимость для водителя,
   плюс его собственный выбор «не беру без сопровождения».

② «Остался один на один с водителем». История на 849 голосов (r/india): приставания начались
   ровно после высадки второй пассажирки, а SOS в такой момент не жмут — боятся поднимать шум.
   Сервер отдаёт тихий признак, клиент предлагает поделиться поездкой с близким.

③ Крюк с маршрута. Жалоба водителя с 15-летним стажем на 823 голоса: заявки без связи с его
   маршрутом, читать каждую руками. Теперь в ленте видно, на сколько километров уводит.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, UserRole


def _ride(client, drv, **extra):
    body = {"from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
            "seats_total": 3, "price": 300, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id, **extra):
    return client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1, **extra})


# ----------------------------- ① несовершеннолетние -----------------------------

def test_minor_without_guardian_is_refused(client, user_factory):
    """Отметил «младше 18», взрослого не указал → бронь не создаётся, текст объясняет почему."""
    drv = user_factory("MinorDrv", role=UserRole.driver)
    pax = user_factory("MinorPax")
    ride = _ride(client, drv)
    r = _book(client, pax, ride["id"], minor_passenger=True)
    assert r.status_code == 400, r.text
    assert "взрослого" in r.json()["detail"]["ru"]
    # места не тронуты — проверка стоит ДО списания
    assert client.get("/rides", params={"from_city": "Баймак"}).json()[0]["seats_left"] == 3


def test_minor_with_guardian_books_and_driver_sees_it(client, user_factory):
    """Взрослый указан → бронь проходит; водитель видит пометку и телефон взрослого."""
    drv = user_factory("MinorDrv2", role=UserRole.driver)
    pax = user_factory("MinorPax2")
    ride = _ride(client, drv)
    r = _book(client, pax, ride["id"], minor_passenger=True,
              minor_guardian_name="Гульнара", minor_guardian_phone="+79990001122")
    assert r.status_code == 200, r.text
    b = r.json()
    d = client.get(f"/bookings/{b['id']}/details", headers=drv["auth"]).json()
    assert d["minor_passenger"] is True
    assert d["minor_guardian_name"] == "Гульнара"
    assert d["minor_guardian_phone"] == "+79990001122"


def test_guardian_phone_is_not_leaked_to_passenger_side(client, user_factory):
    """Пометку пассажир видит, телефон взрослого в его ответе не дублируем — лишние ПДн в эфире.

    (Сам пассажир этот телефон и вводил; смысл в том, что ответ API не носит его туда,
    где он не нужен, — иначе он расползётся по логам и кэшам клиента.)
    """
    drv = user_factory("MinorDrv3", role=UserRole.driver)
    pax = user_factory("MinorPax3")
    ride = _ride(client, drv)
    b = _book(client, pax, ride["id"], minor_passenger=True,
              minor_guardian_name="Гульнара", minor_guardian_phone="+79990001122").json()
    d = client.get(f"/bookings/{b['id']}/details", headers=pax["auth"]).json()
    assert d["minor_passenger"] is True
    assert d["minor_guardian_phone"] == ""
    assert d["minor_guardian_name"] == ""


def test_driver_can_refuse_minors_upfront(client, user_factory):
    """Водитель отметил «не беру без сопровождения» → бронь за подростка не создаётся вовсе.

    Лучше честно сказать заранее, чем отказывать на месте, когда подросток уже стоит у дороги.
    """
    drv = user_factory("NoMinorDrv", role=UserRole.driver)
    pax = user_factory("NoMinorPax")
    ride = _ride(client, drv, no_minors=True)
    r = _book(client, pax, ride["id"], minor_passenger=True,
              minor_guardian_name="Гульнара", minor_guardian_phone="+79990001122")
    assert r.status_code == 409, r.text
    assert "18" in r.json()["detail"]["ru"]


def test_no_minors_ride_still_takes_adults(client, user_factory):
    """Тумблер сужает только подростков — обычные брони работают как раньше."""
    drv = user_factory("NoMinorDrv2", role=UserRole.driver)
    pax = user_factory("NoMinorPax2")
    ride = _ride(client, drv, no_minors=True)
    assert _book(client, pax, ride["id"]).status_code == 200


def test_ordinary_booking_stores_no_guardian_data(client, user_factory):
    """Не отметил подростка, но прислал контакты → не храним: лишних ПДн быть не должно."""
    drv = user_factory("PlainDrv2", role=UserRole.driver)
    pax = user_factory("PlainPax2")
    ride = _ride(client, drv)
    b = _book(client, pax, ride["id"], minor_guardian_name="Кто-то",
              minor_guardian_phone="+79990000000").json()
    with Session(engine) as s:
        row = s.get(Booking, b["id"])
        assert row.minor_passenger is False
        assert row.minor_guardian_name == "" and row.minor_guardian_phone == ""


def test_no_minors_flag_visible_in_ride_listing(client, user_factory):
    """Условие видно в выдаче — пассажир поймёт, почему бронь за подростка недоступна.

    Маршрут у поездки свой, необщий: в полном прогоне «Баймак → Сибай» публикуют десятки
    тестов, а у выдачи есть потолок — своя поездка просто не доезжала до страницы, и тест
    краснел от чужих данных, а не от ошибки (тот же урок, что в волне 92).
    """
    drv = user_factory("NoMinorDrv3", role=UserRole.driver)
    ride = _ride(client, drv, no_minors=True, from_city="Акъяр", to_city="Зилаир")
    rows = client.get("/rides", params={"from_city": "Акъяр"}).json()
    свои = [r for r in rows if r["id"] == ride["id"]]
    assert свои, "поездка не попала в выдачу по своему же городу"
    assert свои[0]["no_minors"] is True


# ------------------- ② остался один на один с водителем -------------------

def _confirm(client, drv, booking_id):
    assert client.post(f"/bookings/{booking_id}/confirm", headers=drv["auth"]).status_code == 200


def test_alone_flag_is_false_while_other_passenger_rides(client, user_factory):
    drv = user_factory("AloneDrv", role=UserRole.driver)
    a = user_factory("AlonePaxA")
    b = user_factory("AlonePaxB")
    ride = _ride(client, drv)
    ba = _book(client, a, ride["id"]).json()
    bb = _book(client, b, ride["id"]).json()
    _confirm(client, drv, ba["id"]); _confirm(client, drv, bb["id"])
    st = client.get(f"/bookings/{ba['id']}/role", headers=a["auth"]).json()
    assert st["alone_with_driver"] is False


def test_alone_flag_turns_on_when_the_other_passenger_leaves(client, user_factory):
    """Вторую бронь завершили → оставшийся пассажир помечен как «один на один»."""
    drv = user_factory("AloneDrv2", role=UserRole.driver)
    a = user_factory("AlonePaxA2")
    b = user_factory("AlonePaxB2")
    ride = _ride(client, drv)
    ba = _book(client, a, ride["id"]).json()
    bb = _book(client, b, ride["id"]).json()
    _confirm(client, drv, ba["id"]); _confirm(client, drv, bb["id"])
    with Session(engine) as s:                       # высадили второго
        row = s.get(Booking, bb["id"])
        row.status = BookingStatus.done
        s.add(row); s.commit()
    st = client.get(f"/bookings/{ba['id']}/role", headers=a["auth"]).json()
    assert st["alone_with_driver"] is True


def test_solo_booking_does_not_trigger_the_hint(client, user_factory):
    """Ехал один с самого начала — это не перемена, подсказку не показываем.

    Иначе она висела бы почти на каждой поездке и её перестали бы замечать — ровно тогда,
    когда она однажды понадобится.
    """
    drv = user_factory("SoloDrv3", role=UserRole.driver)
    pax = user_factory("SoloPax3")
    ride = _ride(client, drv)
    b = _book(client, pax, ride["id"]).json()
    _confirm(client, drv, b["id"])
    st = client.get(f"/bookings/{b['id']}/role", headers=pax["auth"]).json()
    assert st["alone_with_driver"] is False


def test_driver_never_sees_the_flag(client, user_factory):
    """Водителю признак не показываем: это не обвинение и его дело не касается."""
    drv = user_factory("AloneDrv4", role=UserRole.driver)
    a = user_factory("AlonePaxA4")
    b = user_factory("AlonePaxB4")
    ride = _ride(client, drv)
    ba = _book(client, a, ride["id"]).json()
    bb = _book(client, b, ride["id"]).json()
    _confirm(client, drv, ba["id"]); _confirm(client, drv, bb["id"])
    with Session(engine) as s:
        row = s.get(Booking, bb["id"]); row.status = BookingStatus.done; s.add(row); s.commit()
    st = client.get(f"/bookings/{ba['id']}/role", headers=drv["auth"]).json()
    assert st["alone_with_driver"] is False


# ----------------------------- ③ крюк с маршрута -----------------------------

def test_detour_is_none_without_own_rides(client, user_factory):
    """Нет своих поездок — сравнивать не с чем, число не выдумываем."""
    drv = user_factory("DetourDrv", role=UserRole.driver)
    pax = user_factory("DetourPax")
    client.post("/requests", headers=pax["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1})
    feed = client.get("/requests/feed", headers=drv["auth"]).json()
    assert feed and all(x["detour_km"] is None for x in feed)


def test_detour_small_for_request_along_the_route(client, user_factory):
    """Заявка по тому же маршруту → крюк около нуля."""
    drv = user_factory("DetourDrv2", role=UserRole.driver)
    pax = user_factory("DetourPax2")
    _ride(client, drv)                       # Баймак → Сибай, координаты проставит геокодер
    client.post("/requests", headers=pax["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1})
    feed = client.get("/requests/feed", headers=drv["auth"]).json()
    mine = [x for x in feed if x["from_city"] == "Баймак" and x["to_city"] == "Сибай"]
    assert mine and mine[0]["detour_km"] is not None
    assert mine[0]["detour_km"] <= 5


def test_detour_is_large_for_request_in_another_direction(client, user_factory):
    """Заявка в другую сторону → крюк большой, водитель отсеет её взглядом.

    Это и есть смысл поля: отличить «по пути» от «в другую сторону», а не печатать
    точный километраж.
    """
    drv = user_factory("DetourDrv3", role=UserRole.driver)
    pax = user_factory("DetourPax3")
    _ride(client, drv)                                        # Баймак → Сибай
    client.post("/requests", headers=pax["auth"], json={
        "from_city": "Уфа", "to_city": "Магнитогорск", "seats": 1})
    feed = client.get("/requests/feed", headers=drv["auth"]).json()
    far = [x for x in feed if x["from_city"] == "Уфа"]
    assert far and far[0]["detour_km"] is not None
    assert far[0]["detour_km"] > 100
