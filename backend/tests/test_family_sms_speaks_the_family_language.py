"""SMS близким приходит на языке семьи, а не всегда по-русски.

История. Гульнара живёт в башкироязычной семье и пользуется приложением по-башкирски. Она
едет и делится поездкой с мамой. Маме приходит SMS: «Юлдаш: Гульнара села в машину» —
по-русски. Для пожилого человека, который говорит в основном по-башкирски, это тревожное
сообщение на чужом языке от незнакомого сервиса (аудит 2026-08-08, волна 95).

Правило проекта «любая надпись на двух языках» до SMS просто не дошло: пуши давно уходят
на языке получателя, а короткие сообщения близким остались русскими — все до одного.

Языка самого близкого мы не знаем: в базе про него есть только имя и номер. Поэтому берём
язык того, кто его добавил, — человек знает свою маму лучше нас. Если он сам сидит
в приложении по-башкирски, родным привычнее башкирский.

Обратная сторона обязательна: русскоязычная семья не должна начать получать башкирский.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import User, UserRole

from test_api import _ride


@pytest.fixture
def caught_sms(monkeypatch):
    import app.routers.family as fam
    sent: list[tuple[str, str]] = []
    monkeypatch.setattr(fam, "send_text", lambda phone, text: sent.append((phone, text)))
    return sent


def _set_language(user_id: int, lang: str):
    with Session(engine) as s:
        u = s.get(User, user_id)
        u.language = lang
        s.add(u)
        s.commit()


def _trip_shared_with_mother(client, user_factory, tag: str, lang: str):
    driver = user_factory(f"{tag}Водитель", role=UserRole.driver)
    passenger = user_factory(f"{tag}Пассажир")
    _set_language(passenger["id"], lang)

    ride_id = _ride(client, driver, comment=tag)
    booking_id = client.post("/bookings", headers=passenger["auth"],
                             json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200

    phone = f"+7999100{abs(hash(tag)) % 9000 + 1000}"
    contact_id = client.post("/trusted-contacts", headers=passenger["auth"], json={
        "name": "Мама", "relation": "мать", "phone": phone, "notify_by_default": True,
    }).json()["id"]
    assert client.post(f"/bookings/{booking_id}/share", headers=passenger["auth"],
                       json={"contact_id": contact_id}).status_code == 200
    return passenger, booking_id, phone


def _texts_for(sms: list[tuple[str, str]], phone: str) -> list[str]:
    return [t for p, t in sms if p == phone]


def test_башкироязычной_семье_пишут_по_башкирски(client, user_factory, caught_sms):
    passenger, booking_id, phone = _trip_shared_with_mother(client, user_factory, "СемьяБа", "ba")

    share_sms = _texts_for(caught_sms, phone)
    assert share_sms, "ссылка слежения не ушла вовсе"
    assert "Сәфәрҙе күҙәт" in share_sms[0], f"ссылка близкому пришла по-русски: {share_sms[0]}"

    caught_sms.clear()
    r = client.post(f"/bookings/{booking_id}/trip-status", headers=passenger["auth"],
                    json={"status": "sat"})
    assert r.status_code == 200, r.text

    status_sms = _texts_for(caught_sms, phone)
    assert status_sms, "статус поездки не ушёл близкому"
    assert "машинаға ултырҙы" in status_sms[0], f"статус пришёл по-русски: {status_sms[0]}"


def test_русскоязычной_семье_пишут_по_русски(client, user_factory, caught_sms):
    """Обратная сторона: сделав башкирский возможным, нельзя навязать его всем."""
    passenger, booking_id, phone = _trip_shared_with_mother(client, user_factory, "СемьяРу", "ru")

    assert "Следи за поездкой" in _texts_for(caught_sms, phone)[0]

    caught_sms.clear()
    client.post(f"/bookings/{booking_id}/trip-status", headers=passenger["auth"],
                json={"status": "sat"})

    status_sms = _texts_for(caught_sms, phone)
    assert status_sms and "сел в машину" in status_sms[0], status_sms


def test_ссылка_слежения_осталась_в_сообщении(client, user_factory, caught_sms):
    """Главное в этом SMS — ссылка: без неё родные знают, что человек едет, но не видят где."""
    _trip_shared_with_mother(client, user_factory, "СемьяСсылка", "ba")

    assert any("/t/" in t for _, t in caught_sms), f"ссылка слежения пропала из SMS: {caught_sms}"
