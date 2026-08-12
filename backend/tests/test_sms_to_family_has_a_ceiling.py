"""SMS близким за счёт платформы нельзя слать бесконечно.

История. «Поделиться поездкой с близким» отправляет SMS на номер, который человек вписал сам.
Номер никто не подтверждал — им может оказаться кто угодно. Потолок в сервисе был, но считал
он ЧИСЛО близких (десять), а не число сообщений: контакт удаляется, место освобождается,
следующий номер получает новую SMS.

Проба до правки: шестьдесят сообщений на шестьдесят разных номеров подряд, ни одного отказа.
Это и деньги платформы, и готовый способ доставать человека, который на нас не подписывался:
каждое сообщение приходит от имени Юлдаша.

У трекинг-ссылки посылки потолок был (двадцать в сутки) — то есть о проблеме знали, но
закрыли одну дверь из трёх. Теперь счёт общий и ведётся у отправителя, поэтому не обнуляется
удалением контактов и не обходится переходом в соседний раздел (аудит 2026-08-12, волна 48).
"""
from __future__ import annotations

from datetime import timedelta
from pathlib import Path

from sqlmodel import Session

from app.db import engine
from app.models import (Booking, BookingStatus, InstantOrder, InstantOrderStatus, Ride, RideStatus,
                        UserRole)
from app.services import FAMILY_SMS_PER_DAY
from app.timeutil import utcnow

ORIG = (54.7388, 55.9721)
DEST = (54.7500, 55.9800)


def _booking(session: Session, passenger_id: int, driver_id: int) -> int:
    ride = Ride(driver_id=driver_id, from_city="Сибай", to_city="Уфа",
                depart_at=utcnow() + timedelta(hours=2), seats_total=3, seats_left=2,
                price=500, status=RideStatus.active)
    session.add(ride)
    session.commit()
    session.refresh(ride)
    b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1, price=500,
                status=BookingStatus.confirmed, boarding_code="123456")
    session.add(b)
    session.commit()
    session.refresh(b)
    return b.id


def _taxi_order(session: Session, passenger_id: int) -> int:
    o = InstantOrder(passenger_id=passenger_id, status=InstantOrderStatus.searching,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     from_text="Сибай", to_text="Уфа", category="standard",
                     created_at=utcnow() - timedelta(hours=1))
    session.add(o)
    session.commit()
    session.refresh(o)
    return o.id


def _catch_sms(monkeypatch) -> list:
    """Ловим ровно то, что реально ушло бы на телефон.

    Патчим имя в КАЖДОМ модуле-отправителе: они импортируют `send_text` к себе, и подмена
    в `app.services` до них уже не доходит (первый прогон этого теста поймал именно это —
    в логах были настоящие «[SMS-MOCK]»)."""
    sent: list = []
    for mod in ("app.routers.family", "app.instant_service"):
        monkeypatch.setattr(f"{mod}.send_text", lambda phone, text: sent.append((phone, text)))
    return sent


def _share_to_new_number(client, auth, booking_id: int, i: int):
    """Добавить нового близкого, поделиться поездкой, удалить контакт — как делал бы тот,
    кто рассылает. Освободившееся место раньше означало новую бесплатную SMS."""
    c = client.post("/trusted-contacts", headers=auth,
                    json={"name": f"близкий{i}", "phone": f"+7999111{i:04d}"})
    if c.status_code != 200:
        return c
    cid = c.json()["id"]
    r = client.post(f"/bookings/{booking_id}/share", headers=auth, json={"contact_id": cid})
    client.delete(f"/trusted-contacts/{cid}", headers=auth)
    return r


def test_рассылка_по_чужим_номерам_упирается_в_потолок(client, user_factory, monkeypatch):
    sent = _catch_sms(monkeypatch)
    pax = user_factory("SmsCapPax", role=UserRole.passenger)
    drv = user_factory("SmsCapDrv", role=UserRole.driver)
    with Session(engine) as s:
        bid = _booking(s, pax["id"], drv["id"])

    refused = 0
    for i in range(FAMILY_SMS_PER_DAY + 10):
        if _share_to_new_number(client, pax["auth"], bid, i).status_code == 429:
            refused += 1

    assert len(sent) == FAMILY_SMS_PER_DAY
    assert refused > 0
    assert len({phone for phone, _ in sent}) == FAMILY_SMS_PER_DAY   # номера все разные — это рассылка


def test_потолок_не_обходится_переходом_в_такси(client, user_factory, monkeypatch):
    """Исчерпал на попутках — в такси то же самое. Счёт один на человека, а не на раздел."""
    sent = _catch_sms(monkeypatch)
    pax = user_factory("SmsCapCross", role=UserRole.passenger)
    drv = user_factory("SmsCapCrossDrv", role=UserRole.driver)
    with Session(engine) as s:
        bid = _booking(s, pax["id"], drv["id"])
        oid = _taxi_order(s, pax["id"])

    for i in range(FAMILY_SMS_PER_DAY):
        _share_to_new_number(client, pax["auth"], bid, 1000 + i)
    assert len(sent) == FAMILY_SMS_PER_DAY

    c = client.post("/trusted-contacts", headers=pax["auth"],
                    json={"name": "близкий-такси", "phone": "+79992220000"})
    r = client.post(f"/instant/orders/{oid}/share", headers=pax["auth"],
                    json={"contact_id": c.json()["id"]})
    assert r.status_code == 429
    assert len(sent) == FAMILY_SMS_PER_DAY   # ни одного лишнего


def test_обычному_человеку_потолок_не_мешает(client, user_factory, monkeypatch):
    """Страховка от перестраховки: у человека двое близких — сообщения уходят обоим."""
    sent = _catch_sms(monkeypatch)
    pax = user_factory("SmsOkPax", role=UserRole.passenger)
    drv = user_factory("SmsOkDrv", role=UserRole.driver)
    with Session(engine) as s:
        bid = _booking(s, pax["id"], drv["id"])

    for name, phone in (("мама", "+79993330001"), ("брат", "+79993330002")):
        c = client.post("/trusted-contacts", headers=pax["auth"], json={"name": name, "phone": phone})
        r = client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": c.json()["id"]})
        assert r.status_code == 200
        assert r.json()["token"]        # ответ не «обнулился» учётом сообщений

    assert len(sent) == 2


def test_статусы_поездки_не_ругаются_а_просто_молчат(client, user_factory, monkeypatch):
    """Потолок исчерпан, человек едет. «Сел в машину» не должно падать красной ошибкой
    посреди дороги — сообщение просто не уходит, а поездка живёт дальше."""
    sent = _catch_sms(monkeypatch)
    pax = user_factory("SmsStatusPax", role=UserRole.passenger)
    drv = user_factory("SmsStatusDrv", role=UserRole.driver)
    with Session(engine) as s:
        bid = _booking(s, pax["id"], drv["id"])

    c = client.post("/trusted-contacts", headers=pax["auth"],
                    json={"name": "мама", "phone": "+79994440001"})
    cid = c.json()["id"]
    assert client.post(f"/bookings/{bid}/share", headers=pax["auth"],
                       json={"contact_id": cid}).status_code == 200
    for i in range(FAMILY_SMS_PER_DAY):
        _share_to_new_number(client, pax["auth"], bid, 2000 + i)
    before = len(sent)

    st = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "sat"})
    assert st.status_code == 200
    assert st.json()[0]["last_status"] == "sat"   # поездка живёт, ответ целый
    assert len(sent) == before                    # но SMS сверх потолка не ушла


def test_каждая_смс_близкому_идёт_через_общий_счёт():
    """Сторож на класс. Новый вход, который шлёт близкому SMS мимо `may_send_family_sms`,
    вернул бы дыру целиком — а заметить это на глаз нельзя: строка выглядит безобидно."""
    root = Path(__file__).resolve().parents[1] / "app"
    for rel in ("routers/family.py", "instant_service.py"):
        lines = (root / rel).read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if "send_text(" not in line or line.strip().startswith("#"):
                continue
            if "import" in line or "def send_text" in line:
                continue
            window = "\n".join(lines[max(0, i - 8):i + 1])
            assert "may_send_family_sms" in window, (
                f"{rel}:{i + 1} шлёт SMS близкому мимо общего счёта — "
                "оберни вызов в may_send_family_sms(session, user_id, kind)"
            )
