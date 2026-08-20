"""Четыре находки про самых незащищённых: детей, больных и пожилых (волна 160).

**Телефон мамы оставался у водителя навсегда.** Пятнадцатилетняя Айгуль один раз съездила
из Баймака в Уфу. При брони указала взрослого — «Мама Гульнара, +7917…». Год спустя водитель
открывает старую бронь: свой телефон девочки сервер давно закрыл (окно 48 часов), а телефон
мамы всё ещё на экране. Мама в Юлдаше не зарегистрирована, согласия не давала и убрать свой
номер не может никак. Отменённую бронь закрыли раньше, а «завершена» — это статус навсегда.

**«Кто едет в больницу» уходило по публичной ссылке без входа.** Связку поездки с клиникой
прячут четыре двери. Пятая — витрина для мессенджеров — отдавала `hospital` рядом с именем
водителя, датой и маршрутом, вообще без входа. Причина обидная: обезличенная копия поездки
готовилась, но использовалась только как признак «показывать или нет», а в витрину уходил
исходный объект. Номера поездок идут подряд — за вечер собирается список, кто из района
и когда ездит лечиться.

**Зимнюю проверку «доехала?» гасил сам водитель.** Зимняя ночь, Сибай–Уфа, четыре часа трассы.
Пассажирка поделилась поездкой с мамой — это и есть сигнал «меня ждут». Договор такой: сервер
спрашивает «всё в порядке?», и если полчаса нет ответа — шлёт маме SMS. Кнопку «всё в порядке»
мог нажать водитель — тот самый человек, от которого защита и стоит. Одним запросом, навсегда:
отметка ставится один раз, и больше маму не позовёт ни ручка, ни ночной робот. У третьей двери
того же протокола — доставки — проверка стояла. Не было её ровно там, где человек едет один
с незнакомцем.

**Пометку «только SOS» нельзя было поставить задним числом.** Человек добавил маму в доверенные,
потом понял: пожилую женщину не надо дёргать SMS на каждый шаг поездки, пусть приходит только
при беде. Ручки «изменить контакт» на сервере нет, значит единственный путь — прислать тот же
номер с новой пометкой. Сервер обновлял только имя, а пометку молча выбрасывал.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, UserRole
from app.timeutil import utcnow

from test_api import _ride


@pytest.fixture
def поездка_подростка(client, user_factory):
    водитель = user_factory("ВодительПодростка", role=UserRole.driver)
    подросток = user_factory("Айгуль15")
    ride_id = _ride(client, водитель, comment="в Уфу на олимпиаду")
    bid = client.post("/bookings", headers=подросток["auth"], json={
        "ride_id": ride_id, "seats": 1, "minor_passenger": True,
        "minor_guardian_name": "Мама Гульнара", "minor_guardian_phone": "+79170000002",
    }).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    return водитель, подросток, bid, ride_id


def _поездка_была_давно(bid: int, ride_id: int, дней: int):
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(days=дней)
        s.add(b)
        s.add(r)
        s.commit()


def test_телефон_мамы_не_остаётся_навсегда(client, поездка_подростка):
    """Главное: номер человека, который даже не в приложении, не живёт у водителя вечно."""
    водитель, _, bid, ride_id = поездка_подростка
    _поездка_была_давно(bid, ride_id, дней=365)

    карточка = client.get(f"/bookings/{bid}/details", headers=водитель["auth"]).json()

    assert not карточка.get("minor_guardian_phone"), (
        f"спустя год водитель видит телефон мамы {карточка.get('minor_guardian_phone')}: "
        "она согласия не давала и удалить свой номер не может"
    )
    assert not карточка.get("minor_guardian_name"), "имя взрослого тоже осталось открытым"


def test_до_подтверждения_водитель_видит_взрослого(client, user_factory):
    """Обратная сторона: решение «брать ли ребёнка» принимается ДО подтверждения брони.

    Проверяем именно неподтверждённую бронь: после подтверждения открыты уже все контакты,
    и такой тест не поймал бы перестраховку.
    """
    водитель = user_factory("ВодительРешает", role=UserRole.driver)
    подросток = user_factory("Подросток2")
    ride_id = _ride(client, водитель, comment="в Уфу")
    bid = client.post("/bookings", headers=подросток["auth"], json={
        "ride_id": ride_id, "seats": 1, "minor_passenger": True,
        "minor_guardian_name": "Мама Гульнара", "minor_guardian_phone": "+79170000002",
    }).json()["id"]

    карточка = client.get(f"/bookings/{bid}/details", headers=водитель["auth"]).json()

    assert карточка["minor_guardian_phone"] == "+79170000002", (
        "водитель не видит, кто из взрослых отвечает за подростка, — а ему прямо сейчас решать, "
        "садить ли ребёнка в машину и брать ли ответственность"
    )
    assert карточка["driver_phone"] == "", "до подтверждения обычные контакты открываться не должны"


def test_сразу_после_поездки_связь_ещё_открыта(client, поездка_подростка):
    """Обратная сторона: вещь забыли, ребёнок не вышел где надо — дозвониться надо суметь."""
    водитель, _, bid, ride_id = поездка_подростка
    _поездка_была_давно(bid, ride_id, дней=0)

    карточка = client.get(f"/bookings/{bid}/details", headers=водитель["auth"]).json()

    assert карточка["minor_guardian_phone"] == "+79170000002", (
        "поездка только что кончилась, а телефон взрослого уже закрыт: если ребёнок не вышел "
        "там, где договорились, звонить некому"
    )


def _поездка_в_клинику(client, user_factory, метка: str) -> int:
    водитель = user_factory(метка, role=UserRole.driver)
    ride_id = _ride(client, водитель, comment="в РКБ на приём")
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        r.category = "hospital"
        s.add(r)
        s.commit()
    return ride_id


def test_публичная_ссылка_не_рассказывает_про_больницу(client, user_factory):
    """Главное: из мессенджера видно поездку, но не диагноз-намёк."""
    ride_id = _поездка_в_клинику(client, user_factory, "БольницаВодитель")

    витрина = client.get(f"/r/{ride_id}/preview").json()

    assert витрина.get("category") != "hospital", (
        "ссылка без входа рассказывает, что человек едет лечиться: номера поездок идут подряд, "
        "и за вечер собирается список, кто из района и когда ездит в больницу"
    )


def test_обычная_поездка_в_витрине_осталась(client, user_factory):
    """Обратная сторона: ссылкой делятся, чтобы её открыли — витрина обязана работать."""
    водитель = user_factory("ОбычнаяВитрина", role=UserRole.driver)
    ride_id = _ride(client, водитель, comment="Баймак — Сибай")

    витрина = client.get(f"/r/{ride_id}/preview")

    assert витрина.status_code == 200, f"обычная поездка перестала открываться: {витрина.text[:150]}"
    assert витрина.json()["from_city"] == "Баймак"


@pytest.fixture
def ночная_поездка(client, user_factory):
    водитель = user_factory("НочнойВодитель", role=UserRole.driver)
    пассажирка = user_factory("НочнаяПассажирка")
    ride_id = _ride(client, водитель, comment="Сибай — Уфа, ночь")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    return водитель, пассажирка, bid


def test_водитель_не_отвечает_за_пассажирку(client, ночная_поездка):
    """Главное: «я доехала» говорит тот, кого ждут дома."""
    водитель, пассажирка, bid = ночная_поездка
    client.post(f"/bookings/{bid}/winter-check", headers=пассажирка["auth"])

    ответ = client.post(f"/bookings/{bid}/winter-check/ok", headers=водитель["auth"])

    assert ответ.status_code == 403, (
        f"водитель нажал за пассажирку «всё в порядке» (ответ {ответ.status_code}): проверка, "
        "которая должна была позвать её маму, погашена тем самым человеком, от которого стоит"
    )
    assert "ba" in ответ.text, "отказ не на двух языках"


def test_пассажирка_отвечает_сама(client, ночная_поездка):
    """Обратная сторона: доехала — сказала, маму зря не тревожим."""
    _, пассажирка, bid = ночная_поездка
    client.post(f"/bookings/{bid}/winter-check", headers=пассажирка["auth"])

    ответ = client.post(f"/bookings/{bid}/winter-check/ok", headers=пассажирка["auth"])

    assert ответ.status_code == 200, f"пассажирка не может ответить «всё хорошо»: {ответ.text[:150]}"


def test_пометку_только_sos_можно_поставить_позже(client, user_factory):
    """Пожилую маму просили не дёргать по каждой поездке — просьба должна доходить."""
    человек = user_factory("ЗаботливыйСын")
    номер = "+79175550002"
    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Мама", "phone": номер, "notify_by_default": True})

    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Мама", "phone": номер, "notify_by_default": False})

    контакты = client.get("/trusted-contacts", headers=человек["auth"]).json()
    мама = [c for c in контакты if c["phone"] == номер]
    assert len(мама) == 1, f"вместо изменения настройки завёлся дубль: {контакты}"
    assert мама[0]["notify_by_default"] is False, (
        "человек попросил тревожить маму только при беде — сервер оставил уведомления "
        "по каждому шагу поездки, а другого способа это поменять в приложении нет"
    )


def test_имя_контакта_по_прежнему_обновляется(client, user_factory):
    """Обратная сторона: то, ради чего дедуп и писали, должно работать."""
    человек = user_factory("ЗаботливыйСын2")
    номер = "+79175550003"
    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Мама", "phone": номер, "notify_by_default": True})

    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Мама Гульнара", "phone": номер, "notify_by_default": True})

    контакты = client.get("/trusted-contacts", headers=человек["auth"]).json()
    мама = [c for c in контакты if c["phone"] == номер]
    assert len(мама) == 1, "дубль контакта — маме придут два SMS на каждое событие"
    assert мама[0]["name"] == "Мама Гульнара"
    assert мама[0]["notify_by_default"] is True, "заодно сбросили то, о чём не просили"
