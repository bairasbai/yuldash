"""«Двоим близким отправлено» — при том что не отправлено никому (волна 184).

История. Женщина в чужой машине жмёт красную кнопку. Ответ приложения: сигнал принят,
двоим близким ушло SMS. Она выдыхает и ждёт маму.

Мама ничего не получила и не получит. На проде канал SMS выключен целиком
(`sms_provider=mock`, вход у нас через Telegram, юрлица для sms.ru нет) — сообщения не уходят,
они пишутся в лог. Решение осознанное и само по себе нормальное. Ненормальным было то, что
ручки безопасности продолжали считать «скольким ушло» по длине списка контактов: они отвечали
намерением, а человек читал это как факт.

Тот же обман жил на «застрял на трассе»: курьер в минус двадцать читал «близкие и поддержка
получили твои координаты» и переставал звонить сам. В волне 122 этот текст уже чинили — тогда
экран обещал помощь даже тому, у кого доверенных нет, — но чинили только вторую половину:
число стали спрашивать у сервера, а сервер считал по-прежнему намерение.

И третье место — зимний протокол: отправителю посылки приходило «мы уже предупредили его
близких», хотя никого не предупредили.

Теперь правило одно на все три двери (`services.sms_will_reach`): в ответ человеку идёт
число тех, кому сообщение РЕАЛЬНО уйдёт. Ноль — значит ноль, и рядом честная подсказка
позвонить самому.

Почему прежние тесты этого не видели. Они подменяли отправку заглушкой, которая всегда
«успешна», и сверяли число с количеством контактов. Такой тест проверяет арифметику списка,
а не судьбу сообщения: сломай канал — он останется зелёным. Тот же класс, что в волнах 180–183.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import TrustedContact, UserRole
from app.routers.safety import SOS_SMS_PER_HOUR

from test_api import _ride


@pytest.fixture
def канал_молчит(monkeypatch):
    """Прод как он есть: SMS не уходят никуда, только строчка в логе."""
    monkeypatch.setattr(settings, "sms_provider", "mock")
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **kw: True)


@pytest.fixture
def канал_живой(monkeypatch):
    """Обратная сторона: оператор подключён, сообщения уходят по-настоящему."""
    monkeypatch.setattr(settings, "sms_provider", "smsru")
    monkeypatch.setattr(settings, "sms_ru_api_id", "test-id")
    monkeypatch.setattr("app.routers.safety._send_sos_sms", lambda phones, *a, **kw: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **kw: True)


def _человек_с_мамой(user_factory, метка: str, сколько: int = 1):
    человек = user_factory(метка)
    with Session(engine) as s:
        for i in range(сколько):
            s.add(TrustedContact(user_id=человек["id"], name=f"Близкий {i}",
                                 phone=f"+7917000{человек['id']:04d}{i}"))
        s.commit()
    return человек


def _sos(client, человек):
    return client.post("/sos", headers=человек["auth"],
                       json={"category": "other", "note": "тест", "lat": 54.7, "lng": 55.9})


# --------------------------- красная кнопка ---------------------------

def test_sos_не_записывает_маме_сообщение_которого_она_не_получит(client, user_factory, канал_молчит):
    """Главное: женщина в беде не должна ждать помощь, которую никто не позвал."""
    человек = _человек_с_мамой(user_factory, "СОСЖенщина184", сколько=2)

    тело = _sos(client, человек).json()

    assert тело["contacts_notified"] == 0, (
        f"ответ обещает {тело['contacts_notified']} уведомлённых близких, а канал SMS молчит — "
        "женщина будет ждать маму, которая ничего не получила"
    )


def test_sos_говорит_почему_родные_молчат_и_что_делать(client, user_factory, канал_молчит):
    """Ноль без объяснения — это половина правды: человек решит, что у него нет контактов."""
    человек = _человек_с_мамой(user_factory, "СОСЖенщинаПодсказка184", сколько=2)

    тело = _sos(client, человек).json()

    assert тело["hint_ru"] and тело["hint_ba"], "подсказка не на двух языках"
    assert "позвони" in тело["hint_ru"].lower(), (
        f"человеку не сказали, что делать вместо ожидания: {тело['hint_ru']}"
    )
    assert тело["sms_suppressed"] is False, (
        "сказали «слишком много сигналов», хотя сигнал первый: это неправда и она сбивает с толку"
    )


def test_sos_не_путает_молчащий_канал_с_отсутствием_близких(client, user_factory, канал_молчит):
    """Кому звонить некого — тому и подсказывать нечего."""
    человек = user_factory("СОСБезРодных184")

    тело = _sos(client, человек).json()

    assert тело["contacts_total"] == 0
    assert not тело["hint_ru"], (
        f"человеку без доверенных советуют позвонить несуществующим близким: {тело['hint_ru']}"
    )


def test_sos_при_живом_операторе_честно_говорит_что_позвали(client, user_factory, канал_живой):
    """Обратная сторона: когда помощь правда вызвана, человек обязан это знать."""
    человек = _человек_с_мамой(user_factory, "СОСЖенщинаЖивой184", сколько=2)

    тело = _sos(client, человек).json()

    assert тело["contacts_notified"] == 2, (
        f"оператор подключён, у женщины двое близких, а в ответе {тело['contacts_notified']}: "
        "она не увидит, что помощь позвали, и будет паниковать зря"
    )
    assert not тело["hint_ru"], "лишняя тревожная подсказка там, где всё сработало"


def test_потолок_сигналов_не_сломан_молчащим_каналом(client, user_factory, канал_живой):
    """Перестраховка не должна съесть рабочую защиту: кеп SMS остаётся на месте."""
    человек = _человек_с_мамой(user_factory, "СОСЖенщинаСемь184")
    for _ in range(SOS_SMS_PER_HOUR):
        _sos(client, человек)

    тело = _sos(client, человек).json()

    assert тело["sms_suppressed"] is True, "потолок сигналов перестал срабатывать"
    assert тело["contacts_notified"] == 0


# --------------------------- застрял на трассе ---------------------------

@pytest.fixture
def в_поездке(client, user_factory):
    водитель = user_factory("ТрассаВодитель184", role=UserRole.driver)
    пассажир = user_factory("ТрассаКурьер184")
    ride_id = _ride(client, водитель, comment="зимняя трасса 184")
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    return пассажир, bid


def _застрял(client, человек, bid):
    return client.post(f"/bookings/{bid}/stuck", headers=человек["auth"],
                       json={"lat": 52.6, "lng": 58.3, "note": ""})


def test_на_трассе_не_обещаем_что_близкие_уже_едут(client, в_поездке, канал_молчит):
    """Курьер в минус двадцать перестаёт звонить сам, если верит, что его уже ищут."""
    человек, bid = в_поездке
    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Брат", "phone": "+79990001841"})

    тело = _застрял(client, человек, bid).json()

    assert тело["contacts_notified"] == 0, (
        f"экран напишет «близкие получили твои координаты» ({тело['contacts_notified']} шт.), "
        "а брату не ушло ничего"
    )
    assert тело["contacts_total"] == 1, (
        "экран не отличит «звать некого» от «есть кого, но сообщение не уйдёт» и посоветует "
        "добавить доверенных тому, кто их уже добавил"
    )


def test_на_трассе_при_живом_операторе_счёт_прежний(client, в_поездке, канал_живой):
    """Обратная сторона: рабочий канал — рабочее обещание."""
    человек, bid = в_поездке
    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Брат", "phone": "+79990001842"})
    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Сосед", "phone": "+79990001843"})

    тело = _застрял(client, человек, bid).json()

    assert тело["contacts_notified"] == 2, тело
    assert тело["contacts_total"] == 2


# --------------------------- зимний протокол ---------------------------

def test_зимний_протокол_не_говорит_отправителю_что_курьера_ищут(client, user_factory, канал_молчит):
    """Отправитель посылки ждал, что близкие курьера уже поехали проверять. Не поехали."""
    from app import winter_escalate
    from app.models import Notification

    курьер = user_factory("ЗимаКурьер184")
    отправитель = user_factory("ЗимаОтправитель184")
    with Session(engine) as s:
        итог = winter_escalate.escalate_now(
            s, kind="parcel", obj_id=918401, watch_user_id=курьер["id"],
            contact_phones=["+79990001844"], also_notify_user_id=отправитель["id"],
        )
        письмо = s.exec(
            __import__("sqlmodel").select(Notification)
            .where(Notification.user_id == отправитель["id"])
            .order_by(Notification.id.desc())
        ).first()

    assert итог["contacts_notified"] == 0, (
        f"протокол отчитался о {итог['contacts_notified']} предупреждённых близких, "
        "хотя сообщение не ушло никому"
    )
    assert письмо is not None, "отправитель вообще не узнал, что курьер молчит"
    assert "предупредили его близких" not in (письмо.body_ru or ""), (
        f"отправителю обещали то, чего не сделали: {письмо.body_ru!r}"
    )
    assert письмо.body_ba, "сообщение отправителю не на двух языках"


def test_зимний_протокол_при_живом_операторе_зовёт_близких(client, user_factory, канал_живой):
    """Обратная сторона: рабочий канал — близкие правда позваны, и так и написано."""
    from app import winter_escalate
    from app.models import Notification

    курьер = user_factory("ЗимаКурьерЖивой184")
    отправитель = user_factory("ЗимаОтправительЖивой184")
    with Session(engine) as s:
        итог = winter_escalate.escalate_now(
            s, kind="parcel", obj_id=918402, watch_user_id=курьер["id"],
            contact_phones=["+79990001845"], also_notify_user_id=отправитель["id"],
        )
        письмо = s.exec(
            __import__("sqlmodel").select(Notification)
            .where(Notification.user_id == отправитель["id"])
            .order_by(Notification.id.desc())
        ).first()

    assert итог["contacts_notified"] == 1, итог
    assert "предупредили его близких" in (письмо.body_ru or ""), письмо.body_ru


# --------------------------- сторож правила ---------------------------

def test_правило_живёт_в_одном_месте():
    """Сторож: «дошло или нет» считается одной функцией, а не тремя копиями.

    Именно из-за копий дыра и прожила так долго: обещание на экране починили в волне 122,
    а счёт остался прежним в трёх разных файлах.
    """
    from pathlib import Path

    корень = Path(__file__).resolve().parents[1] / "app"
    for файл in ("routers/safety.py", "winter_escalate.py"):
        текст = (корень / файл).read_text(encoding="utf-8")
        assert "sms_will_reach" in текст, (
            f"{файл} снова считает уведомлённых сам по себе — правило разъедется молча"
        )


def test_экран_на_трассе_различает_три_случая():
    """Сторож: текст живёт на телефоне и разойтись с сервером может молча.

    Ровно так дыра и прожила: обещание починили в волне 122, а считать продолжали намерение.
    """
    from pathlib import Path

    экран = (Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "java" /
             "com" / "yuldash" / "app" / "RoadsideHelp.kt").read_text(encoding="utf-8")
    assert "contactsTotal == 0" in экран, (
        "экран снова путает «звать некого» с «есть кого, но сообщение не уйдёт»: человеку "
        "с полным списком доверенных советуют их добавить"
    )
    assert "позвони им сам" in экран, (
        "человеку на трассе не сказали, что делать вместо ожидания помощи, которую не позвали"
    )
    assert "үҙең шылтырат" in экран, "подсказка на трассе не на двух языках"
