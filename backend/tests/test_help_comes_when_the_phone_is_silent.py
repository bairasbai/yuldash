"""Помощь должна приходить как раз тогда, когда телефон замолчал.

Зимний протокол устроен так: через какое-то время после начала пути приложение спрашивает
«ты доехал(а)?». Ответил — тишина. Молчишь полчаса — тем, кому человек сам расшарил поездку,
уходит SMS «позвони, проверь, всё ли хорошо».

Что было не так (аудит 2026-08-08, волна 114). Второй шаг — «полчаса прошло, зовём близких» —
выполнялся, только когда приложение ещё раз спрашивало сервер. А спрашивает оно, пока экран
открыт. Получалось наоборот:

* Зухра едет ночью по трассе Сибай–Уфа. Телефон разрядился (или машина в кювете, или человек
  без сознания) — приложение молчит, и маме не уходит НИЧЕГО. Никогда.
* Зухра доехала, всё хорошо, утром открыла приложение — оно спрашивает сервер, срок вышел,
  и маме улетает тревога «не отметилась, позвони».

То есть сигнал приходил ровно у той, у кого всё в порядке. Проверено пробой: 45 минут молчания
и прогон всех фоновых задач — ноль SMS; один запрос из приложения — SMS уходит.

Теперь шаг эскалации делает ночной робот, а сам зов близких остался один на всех — и для
робота, и для приложения. Тот же приём, что с непринятым SOS (волна 82): то, от чего зависит
безопасность человека, не может зависеть от того, открыт ли у него экран.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

import app.services as svc
from app.db import engine
from app.models import Booking, Ride, UserRole
from app.timeutil import utcnow

from test_api import _ride


@pytest.fixture
def смс(monkeypatch):
    """Ловим SMS, которые уходят близким."""
    поймано: list[tuple[str, str]] = []
    monkeypatch.setattr(svc, "send_text", lambda phone, text: поймано.append((phone, text)))
    import app.routers.safety as safety
    monkeypatch.setattr(safety, "send_text", lambda phone, text: поймано.append((phone, text)))
    return поймано


@pytest.fixture
def зухра_в_пути(client, user_factory):
    """Пассажирка выехала и расшарила поездку маме. Спросили «доехала?» — ответа нет."""
    водитель = user_factory("ЗимаВодитель", role=UserRole.driver)
    зухра = user_factory("ЗимаЗухра")
    мама = client.post("/trusted-contacts", headers=зухра["auth"],
                       json={"name": "Мама", "phone": "+79990000114"}).json()
    ride_id = _ride(client, водитель, comment="ночная трасса")
    bid = client.post("/bookings", headers=зухра["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"]).status_code == 200
    client.post(f"/bookings/{bid}/board", headers=водитель["auth"], json={"code": ""})
    assert client.post(f"/bookings/{bid}/share", headers=зухра["auth"],
                       json={"contact_id": мама["id"]}).status_code == 200
    with Session(engine) as s:            # поездка уже выехала
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(hours=3)
        s.add(r)
        s.commit()
    ответ = client.post(f"/bookings/{bid}/winter-check", headers=зухра["auth"]).json()
    assert ответ["state"] == "check_sent", ответ
    return зухра, bid


def _прошло_минут(booking_id: int, минут: int) -> None:
    with Session(engine) as s:
        b = s.get(Booking, booking_id)
        b.winter_check_sent_at = utcnow() - timedelta(minutes=минут)
        s.add(b)
        s.commit()


def _ночной_робот() -> int:
    from app import taxi_worker
    with Session(engine) as s:
        return taxi_worker.run_once(s).get("winter_escalated", 0)


def test_молчащий_телефон_поднимает_тревогу_сам(client, зухра_в_пути, смс):
    """Главное: человек больше ничего не нажимает — и именно поэтому зовём близких."""
    _, bid = зухра_в_пути
    смс.clear()

    _прошло_минут(bid, 45)
    _ночной_робот()

    assert смс, (
        "прошло 45 минут молчания, а маме не ушло ничего. Если телефон сел или машина "
        "в кювете, приложение больше никогда не спросит сервер — и помощи не будет"
    )
    телефон, текст = смс[-1]
    assert телефон == "+79990000114"
    assert "не отметил" in текст, текст


def test_кто_ответил_доехал_никого_не_тревожит(client, зухра_в_пути, смс):
    """Обратная сторона: нажала «Доехала» — маму не дёргаем ни сейчас, ни ночью."""
    _, bid = зухра_в_пути
    assert client.post(f"/bookings/{bid}/winter-check/ok",
                       headers=зухра_в_пути[0]["auth"]).status_code == 200
    смс.clear()

    _прошло_минут(bid, 45)
    _ночной_робот()

    assert not смс, f"человек отметился, что доехал, а маме всё равно позвонили: {смс}"


def test_до_срока_никого_не_будим(client, зухра_в_пути, смс):
    """Полчаса — это полчаса: спросили десять минут назад, тревожить рано."""
    _, bid = зухра_в_пути
    смс.clear()

    _прошло_минут(bid, 10)
    _ночной_робот()

    assert not смс, f"тревога ушла раньше срока: {смс}"


def test_близких_зовут_один_раз(client, зухра_в_пути, смс):
    """Робот крутится часто — маме не должно прийти двадцать одинаковых SMS."""
    _, bid = зухра_в_пути
    смс.clear()

    _прошло_минут(bid, 45)
    _ночной_робот()
    _ночной_робот()
    _ночной_робот()

    assert len(смс) == 1, f"вместо одного зова ушло {len(смс)}: {смс}"


def test_приложение_и_робот_зовут_одним_кодом():
    """Сторож: два пути к близким разойдутся текстами и правилами, если их станет два.

    Так уже было с чатом, кнопкой «застрял» и показом суммы отмены — поэтому у зимнего
    протокола зов близких намеренно один на ручку и на робота.
    """
    from pathlib import Path

    app_dir = Path(__file__).resolve().parents[1] / "app"
    safety = (app_dir / "routers" / "safety.py").read_text(encoding="utf-8")
    worker = (app_dir / "taxi_worker.py").read_text(encoding="utf-8")
    assert "escalate_now" in safety, (
        "ручка снова зовёт близких сама — значит появилась вторая копия правил эскалации"
    )
    assert "winter_escalate.escalate_silent" in worker, (
        "ночной робот перестал проверять молчащих: помощь снова зависит от того, "
        "открыл ли человек приложение"
    )
