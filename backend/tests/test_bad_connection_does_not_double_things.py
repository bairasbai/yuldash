"""Рваная связь не должна удваивать то, что человек сделал один раз.

В деревне и на трассе связь обрывается посреди отправки — это не редкость, а обычный день.
Человек жмёт кнопку, ответа не видит, жмёт ещё раз. Приложение при этом само повторяет запрос.
Значит любой важный шаг обязан переживать двойное нажатие (аудит 2026-08-08, волна 135).

Проверено прогоном по всем заметным кнопкам. Большинство уже держало удар: бронь, заявка,
отклик водителя, подтверждение и отмена — второе нажатие возвращает то же самое. Две
не держали.

**Жалоба заводила новое дело на каждое нажатие.** Пассажирка нажала «Пожаловаться» трижды —
у админа три одинаковых дела, а счётчик подтверждённых жалоб считает их как три разных
случая. То есть водитель мог получить паузу за одну ситуацию просто потому, что у пассажирки
плохо ловило. Теперь пока прежнее обращение не разобрано, повторное нажатие отдаёт его же;
когда дело закрыто — новая жалоба заводится нормально, это уже другая история.

**Доверенный контакт добавлялся дважды.** В списке появлялись две одинаковые «Мамы» — и маме
приходили ДВА SMS на каждое событие поездки, а при беде два сигнала SOS. Тревожное сообщение,
пришедшее дважды, пугает сильнее и выглядит как поломка. Теперь тот же номер второй раз
обновляет имя, но дубля не создаёт.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Incident, UserRole

from test_api import _ride


@pytest.fixture
def поездка(client, user_factory):
    водитель = user_factory("СвязьВодитель", role=UserRole.driver)
    пассажирка = user_factory("СвязьГульнара")
    ride_id = _ride(client, водитель, comment="рваная связь")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    return водитель, пассажирка, ride_id, bid


def _пожаловаться(client, кто, bid, на_кого):
    return client.post("/incidents", headers=кто["auth"], json={
        "booking_id": bid, "type": "rude", "text": "нагрубил", "respondent_id": на_кого["id"],
    })


def test_три_нажатия_жалобы_дают_одно_дело(client, поездка):
    """Главное: наказание не должно зависеть от качества связи у заявителя."""
    водитель, пассажирка, _, bid = поездка

    ответы = [_пожаловаться(client, пассажирка, bid, водитель) for _ in range(3)]

    assert all(r.status_code == 200 for r in ответы), [r.status_code for r in ответы]
    номера = {r.json()["id"] for r in ответы}
    assert len(номера) == 1, (
        f"три нажатия завели {len(номера)} дела: админ разбирает одно и то же трижды, "
        "а счётчик жалоб считает их как три разных случая — водитель получит паузу "
        "за одну ситуацию"
    )
    with Session(engine) as s:
        дел = len(s.exec(select(Incident).where(
            Incident.respondent_id == водитель["id"])).all())
    assert дел == 1, f"в базе {дел} дел вместо одного"


def test_после_разбора_пожаловаться_снова_можно(client, поездка, user_factory):
    """Обратная сторона: закрытое дело не должно запирать человека навсегда.

    Нахамил второй раз через месяц — это новая история, и она обязана дойти до разбора.
    """
    водитель, пассажирка, _, bid = поездка
    первое = _пожаловаться(client, пассажирка, bid, водитель).json()["id"]
    with Session(engine) as s:                       # админ разобрал и закрыл
        inc = s.get(Incident, первое)
        inc.status = "closed"
        s.add(inc)
        s.commit()

    второе = _пожаловаться(client, пассажирка, bid, водитель)

    assert второе.status_code == 200, второе.text
    assert второе.json()["id"] != первое, (
        "после закрытого разбора новая жалоба не заводится — человек остался без защиты"
    )


def test_жалоба_другого_типа_не_склеивается(client, поездка):
    """«Нагрубил» и «не заплатил» — разные обращения, даже по одной поездке."""
    водитель, пассажирка, _, bid = поездка
    грубость = _пожаловаться(client, пассажирка, bid, водитель).json()["id"]

    деньги = client.post("/incidents", headers=пассажирка["auth"], json={
        "booking_id": bid, "type": "non_payment", "text": "не заплатил",
        "respondent_id": водитель["id"],
    })

    assert деньги.status_code == 200, деньги.text
    assert деньги.json()["id"] != грубость, "разные обвинения склеились в одно дело"


def test_двойное_добавление_контакта_не_плодит_дублей(client, user_factory):
    """Иначе маме придёт два SMS на каждое событие, а при беде — два сигнала."""
    человек = user_factory("СвязьОсторожный")

    первый = client.post("/trusted-contacts", headers=человек["auth"],
                         json={"name": "Мама", "phone": "+79990009999"})
    второй = client.post("/trusted-contacts", headers=человек["auth"],
                         json={"name": "Мама", "phone": "+79990009999"})

    assert первый.status_code == второй.status_code == 200
    assert первый.json()["id"] == второй.json()["id"], "завёлся дубль контакта"
    список = client.get("/trusted-contacts", headers=человек["auth"]).json()
    assert len(список) == 1, f"в списке {len(список)} одинаковых контакта: {список}"


def test_повторное_добавление_обновляет_имя(client, user_factory):
    """Человек уточнил подпись — это не повод заводить второй контакт."""
    человек = user_factory("СвязьУточнил")
    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Мама", "phone": "+79990009998"})

    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Мама Гульнара", "phone": "+79990009998"})

    список = client.get("/trusted-contacts", headers=человек["auth"]).json()
    assert len(список) == 1, f"вместо обновления имени завёлся дубль: {список}"
    assert список[0]["name"] == "Мама Гульнара", список


def test_разные_близкие_добавляются_как_разные(client, user_factory):
    """Обратная сторона: у человека может быть и мама, и брат."""
    человек = user_factory("СвязьСемья")

    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Мама", "phone": "+79990009001"})
    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Брат", "phone": "+79990009002"})

    список = client.get("/trusted-contacts", headers=человек["auth"]).json()
    assert len(список) == 2, f"второй близкий не добавился: {список}"


def test_бронь_и_отклик_по_прежнему_переживают_повтор(client, user_factory):
    """Эти кнопки держали удар и раньше — проверяем, что не сломались заодно."""
    водитель = user_factory("СвязьВодитель2", role=UserRole.driver)
    пассажир = user_factory("СвязьПассажир2")
    ride_id = _ride(client, водитель, comment="повтор")

    б1 = client.post("/bookings", headers=пассажир["auth"], json={"ride_id": ride_id, "seats": 1})
    б2 = client.post("/bookings", headers=пассажир["auth"], json={"ride_id": ride_id, "seats": 1})
    assert б1.json()["id"] == б2.json()["id"], "двойное нажатие создало две брони"

    rid = client.post("/requests", headers=пассажир["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-11-02T08:00:00", "seats": 1,
    }).json()["id"]
    о1 = client.post(f"/requests/{rid}/respond", headers=водитель["auth"], json={"price": 400})
    о2 = client.post(f"/requests/{rid}/respond", headers=водитель["auth"], json={"price": 400})
    assert о1.json()["id"] == о2.json()["id"], "двойное нажатие создало два отклика"


def test_один_номер_у_разных_людей_это_разные_контакты(client, user_factory):
    """В деревне мама одна на нескольких детей — и брат, и сестра добавят её номер.

    Дедуп обязан смотреть на владельца списка, а не только на телефон: иначе второй человек
    не сможет добавить маму вовсе — «такой контакт уже есть», хотя есть он у другого.
    """
    брат = user_factory("СвязьБрат")
    сестра = user_factory("СвязьСестра")
    номер_мамы = "+79990009777"

    у_брата = client.post("/trusted-contacts", headers=брат["auth"],
                          json={"name": "Мама", "phone": номер_мамы})
    у_сестры = client.post("/trusted-contacts", headers=сестра["auth"],
                           json={"name": "Мама", "phone": номер_мамы})

    assert у_брата.status_code == у_сестры.status_code == 200, (у_брата.text, у_сестры.text)
    assert у_брата.json()["id"] != у_сестры.json()["id"], (
        "контакт сестры склеился с контактом брата: она не сможет добавить маму, "
        "хотя у неё в списке пусто"
    )
    assert len(client.get("/trusted-contacts", headers=сестра["auth"]).json()) == 1
