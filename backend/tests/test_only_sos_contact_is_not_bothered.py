"""Пометка «Только SOS» у близкого должна что-то значить.

История. В профиле человек добавляет доверенных близких. У каждого — переключатель, и
приложение подписывает его прямо: «Статус» или «Только SOS». Смысл понятен без объяснений:
пожилую маму тревожат, только если случилась беда, а не на каждый шаг поездки.

Сервер про эту пометку не знал (аудит 2026-08-08, волна 81). Мама с пометкой «Только SOS»
получала SMS «Гульнара села в машину», потом «доехала до места» — каждый раз, когда дочь
отмечала статус. Для пожилого человека сообщение от незнакомого сервиса — это тревога,
а не забота. Плюс каждое такое SMS платит платформа.

Что при этом трогать нельзя:
* SOS приходит ВСЕМ близким без исключений — там пометка не действует, жизнь дороже настроек;
* разовую ссылку слежения контакт получает и с пометкой: ею поделились явно, это не поток;
* близкий со «Статусом» обязан получать статусы, иначе поделиться поездкой станет бессмысленно.
"""
from __future__ import annotations

import pytest

from app.models import UserRole

from test_api import _ride


@pytest.fixture
def caught_sms(monkeypatch):
    """Ловим исходящие SMS близким (текст + номер)."""
    import app.routers.family as fam
    sent: list[tuple[str, str]] = []
    monkeypatch.setattr(fam, "send_text", lambda phone, text: sent.append((phone, text)))
    return sent


def _contact(client, who, name: str, phone: str, notify: bool) -> int:
    r = client.post("/trusted-contacts", headers=who["auth"], json={
        "name": name, "relation": "мать", "phone": phone, "notify_by_default": notify,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _trip_with_contact(client, user_factory, tag: str, notify: bool):
    driver = user_factory(f"{tag}Водитель", role=UserRole.driver)
    passenger = user_factory(f"{tag}Пассажир")
    ride_id = _ride(client, driver, comment=tag)
    booking_id = client.post("/bookings", headers=passenger["auth"],
                             json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200
    phone = f"+7999000{abs(hash(tag)) % 9000 + 1000}"
    contact_id = _contact(client, passenger, "Мама", phone, notify)
    assert client.post(f"/bookings/{booking_id}/share", headers=passenger["auth"],
                       json={"contact_id": contact_id}).status_code == 200
    return passenger, booking_id, phone


def test_маму_с_пометкой_только_sos_не_дёргают_статусами(client, user_factory, caught_sms):
    passenger, booking_id, phone = _trip_with_contact(client, user_factory, "ТолькоSOS", notify=False)
    caught_sms.clear()   # разовая ссылка при шаринге уже ушла — она к статусам не относится

    r = client.post(f"/bookings/{booking_id}/trip-status", headers=passenger["auth"],
                    json={"status": "sat"})
    assert r.status_code == 200, r.text

    assert not [t for p, t in caught_sms if p == phone], \
        f"контакту «Только SOS» пришли статусы поездки: {caught_sms}"


def test_близкий_со_статусом_получает_как_прежде(client, user_factory, caught_sms):
    """Обратная сторона: выключив лишнее, нельзя выключить нужное."""
    passenger, booking_id, phone = _trip_with_contact(client, user_factory, "СоСтатусом", notify=True)
    caught_sms.clear()

    r = client.post(f"/bookings/{booking_id}/trip-status", headers=passenger["auth"],
                    json={"status": "sat"})
    assert r.status_code == 200, r.text

    texts = [t for p, t in caught_sms if p == phone]
    assert texts, "близкий, которого просили держать в курсе, ничего не получил"
    assert "сел в машину" in texts[0], texts


def test_sos_приходит_всем_включая_пометку(client, user_factory, monkeypatch):
    """SOS — исключение из всех настроек: беда важнее тишины."""
    import app.routers.safety as saf
    sent: list[str] = []
    monkeypatch.setattr(saf, "_send_sos_sms", lambda phones, text: sent.extend(phones))

    passenger = user_factory("СОСПассажир")
    quiet_phone = "+79990007777"
    _contact(client, passenger, "Мама", quiet_phone, notify=False)

    r = client.post("/sos", headers=passenger["auth"], json={"category": "medical", "note": "помогите"})
    assert r.status_code == 200, r.text

    assert quiet_phone in sent, "в беде SOS не дошёл до близкого из-за пометки «Только SOS»"
