"""Заблокированный водитель не должен возвращаться через боковую дверь.

История. Гульнара заблокировала Рустама — он вёл себя так, что ехать с ним она больше
не хочет. Лента попуток обещание выполнила: его поездки оттуда пропали. Но приложение
показывает поездки не только лентой. Есть подбор под заявку («вот кто едет туда, куда
ты просила») и есть витрина клиники («кто едет в РКБ»). Обе эти двери про блокировку
не знали и спокойно предлагали Гульнаре ту же самую машину (аудит 2026-08-08, волна 71).

Витрина клиники тут тяжелее прочих: человек едет в больницу, ему и так тревожно, и
именно в этот момент ему предлагали сесть к тому, от кого он прятался.

Вторая половина проверки не менее важна: честный водитель обязан остаться виден в обеих
дверях. Правило «никого не показывать» тоже прошло бы проверку на блокировку — и убило бы
продукт. Поэтому Ильдар едет тем же маршрутом и обязан находиться.
"""
from __future__ import annotations

from datetime import timedelta

import pytest

from app.db import get_session
from app.models import MedicalPartner, SafetyProfile, UserRole
from app.timeutil import utcnow

from test_api import _ride


@pytest.fixture
def clinic():
    """Клиника-партнёр в справочнике (в тестовой базе он пуст)."""
    s = next(get_session())
    mp = MedicalPartner(name="РКБ им. Куватова", city="Уфа", address="ул. Достоевского, 132",
                        lat=54.7261, lng=55.9475, description="тест")
    s.add(mp)
    s.commit()
    s.refresh(mp)
    return mp.id


def _block(client, who, whom_id: int):
    r = client.post("/blocks", headers=who["auth"], json={"blocked_user_id": whom_id})
    assert r.status_code in (200, 201), r.text


def _pause(user_id: int, days: int = 7):
    """Пауза лестницы «Справедливости» (§2) — водитель временно не возит."""
    s = next(get_session())
    s.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=days)))
    s.commit()


def _request_id(client, passenger) -> int:
    r = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats": 1,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _clinic_ids(client, who, clinic_id) -> list[int]:
    r = client.get(f"/medical-partners/{clinic_id}/rides", headers=who["auth"])
    assert r.status_code == 200, r.text
    return [x["id"] for x in r.json()["items"]]


def _match_ids(client, who, request_id) -> list[int]:
    r = client.get(f"/match/rides?request_id={request_id}", headers=who["auth"])
    assert r.status_code == 200, r.text
    return [x["id"] for x in r.json()]


def test_витрина_клиники_помнит_блокировку(client, user_factory, clinic):
    rustam = user_factory("КлиникаРустам", role=UserRole.driver)
    ildar = user_factory("КлиникаИльдар", role=UserRole.driver)
    gulnara = user_factory("КлиникаГульнара")

    bad = _ride(client, rustam, comment="Рустам в РКБ", partner_id=clinic, category="hospital")
    good = _ride(client, ildar, comment="Ильдар в РКБ", partner_id=clinic, category="hospital")
    _block(client, gulnara, rustam["id"])

    ids = _clinic_ids(client, gulnara, clinic)
    assert bad not in ids, "заблокированный водитель предложен человеку, который едет в больницу"
    assert good in ids, "витрина клиники опустела — честный водитель тоже пропал"


def test_подбор_под_заявку_помнит_блокировку(client, user_factory):
    rustam = user_factory("ПодборРустам", role=UserRole.driver)
    ildar = user_factory("ПодборИльдар", role=UserRole.driver)
    gulnara = user_factory("ПодборГульнара")

    bad = _ride(client, rustam, comment="Рустам по заявке")
    good = _ride(client, ildar, comment="Ильдар по заявке")
    _block(client, gulnara, rustam["id"])

    ids = _match_ids(client, gulnara, _request_id(client, gulnara))
    assert bad not in ids, "заблокированный водитель пришёл прямо в ответ на заявку"
    assert good in ids, "подбор перестал находить кого-либо — заявка стала бесполезной"


def test_обе_двери_знают_про_паузу(client, user_factory, clinic):
    """Водитель на паузе за нарушения не возит — значит и предлагать его нечестно.
    Подтвердить бронь он всё равно не сможет, человек просто прождёт зря."""
    paused = user_factory("ПаузаРустам", role=UserRole.driver)
    ok_driver = user_factory("ПаузаИльдар", role=UserRole.driver)
    passenger = user_factory("ПаузаГульнара")

    bad_clinic = _ride(client, paused, comment="на паузе в РКБ", partner_id=clinic, category="hospital")
    good_clinic = _ride(client, ok_driver, comment="без паузы в РКБ", partner_id=clinic, category="hospital")
    bad_plain = _ride(client, paused, comment="на паузе обычная")
    good_plain = _ride(client, ok_driver, comment="без паузы обычная")
    _pause(paused["id"])

    ids = _clinic_ids(client, passenger, clinic)
    assert bad_clinic not in ids, "витрина клиники предлагает водителя, который сейчас не возит"
    assert good_clinic in ids

    ids = _match_ids(client, passenger, _request_id(client, passenger))
    assert bad_plain not in ids, "подбор предлагает водителя, который сейчас не возит"
    assert good_plain in ids
