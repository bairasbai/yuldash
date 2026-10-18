"""Стоящая машина не пропадает у близких: точка «на том же месте» продлевает её (проверка PR170).

Сервер держит последнюю точку машины для ссылки близким и для SOS 120 с (`LIVEPOS_TTL_SEC`).
Приложение водителя раньше просило у телефона точки «не ближе 10 м» — стоящая машина не слала
ничего, и через две минуты стоянки мама по ссылке и сигнал SOS теряли её: ровно в случае
«машина встала в поле». Теперь приложение на стоянке шлёт ту же точку раз в 45 с (пульс).

Пульс работает, только если сервер такую точку не выбрасывает. Здесь — договор сервера с
приложением: точка, которая не сдвинулась, проходит анти-телепорт, уходит пассажиру и заново
заводит двухминутный срок в кэше. Склеит кто-нибудь «одинаковые точки» ради экономии — тест
покраснеет раньше, чем машина начнёт мигать у близких.
"""
from __future__ import annotations

import json

import fakeredis
import pytest

from app import instant_service as isv
from app import livepos
from app.models import UserRole

from test_api import _ride

# Сломанный канал должен краснеть за 30 с, а не держать CI.
pytestmark = pytest.mark.timeout(30)

ПОЛЕ = (52.5911, 58.3178)   # машина встала и стоит


@pytest.fixture
def redis_живой():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def едем(client, user_factory):
    """Бронь подтверждена, водитель отметил «Выехал» — точка машины уже идёт близким."""
    водитель = user_factory("СтоянкаВодитель", role=UserRole.driver)
    пассажир = user_factory("СтоянкаПассажирка")
    ride_id = _ride(client, водитель, seats=2)
    bid = client.post("/bookings", headers=пассажир["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"]).status_code == 200
    r = client.post(f"/bookings/{bid}/driver-status", headers=водитель["auth"], json={"status": "departed"})
    assert r.status_code == 200, r.text
    return {"booking": bid, "водитель": водитель, "пассажир": пассажир}


def test_точка_на_том_же_месте_продлевает_машину_у_близких(client, едем, redis_живой):
    bid = едем["booking"]
    ключ = f"livepos:booking:{bid}"
    путь = f"/ws/trip/{bid}/location"
    with client.websocket_connect(путь) as ухо:
        ухо.send_text(json.dumps({"type": "auth", "token": едем["пассажир"]["token"]}))
        with client.websocket_connect(путь) as рот:
            рот.send_text(json.dumps({"type": "auth", "token": едем["водитель"]["token"]}))
            рот.send_text(json.dumps({"type": "loc", "lat": ПОЛЕ[0], "lng": ПОЛЕ[1], "ts": 1}))
            assert json.loads(ухо.receive_text())["ts"] == 1   # барьер: первая точка обработана
            assert redis_живой.get(ключ) is not None

            # Почти две минуты стоянки: до конца срока секунды.
            redis_живой.expire(ключ, 5)

            # Пульс приложения — то же самое место.
            рот.send_text(json.dumps({"type": "loc", "lat": ПОЛЕ[0], "lng": ПОЛЕ[1], "ts": 2}))
            пульс = json.loads(ухо.receive_text())
            assert пульс["ts"] == 2 and пульс["lat"] == pytest.approx(ПОЛЕ[0]), (
                f"точку «на том же месте» сервер не переслал пассажиру: {пульс}")

    assert redis_живой.ttl(ключ) > livepos.LIVEPOS_TTL_SEC - 10, (
        "пульс стоящей машины не продлил её в кэше — через секунды мама по ссылке и SOS её потеряют")
    поз = livepos.livepos_get("booking", bid)
    assert поз is not None and поз["lat"] == pytest.approx(ПОЛЕ[0])
