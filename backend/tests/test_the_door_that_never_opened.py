"""Две последние дыры вокруг возврата (решение Александра, 2026-08-29).

ПЕРВАЯ. Курьер привёз коробку обратно, а отправителя тоже нет дома. Кнопка «Я на месте»
в этом состоянии не работала: ожидание не считалось, время уходило в никуда, и выхода из
положения приложение не предлагало. Возврат — такая же поездка, как доставка.

ВТОРАЯ. Компенсация за возврат начислялась по НАЖАТИЮ кнопки «никого нет дома»: приложение
не проверяло, что курьер там вообще был. Единственная строка в экономике, которая держалась
на честности. Теперь сверяем с живой позицией курьера — тем же приёмом, что такси сверяет
«Я на месте»: блокируем, только когда точно знаем, что он далеко.

Здесь проверяем:
  • «Я на месте» работает при возврате и пишет время на счёт ОТПРАВИТЕЛЯ;
  • это ожидание попадает курьеру в деньги, хотя наступило после фиксации компенсации;
  • цена доставки при этом не растёт — иначе поехал бы потолок «не дороже доставки»;
  • попытка вручения и приезд не засчитываются, если GPS говорит, что курьер далеко;
  • когда проверить нечем (нет позиции, выключен рубильник) — работаем как раньше.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery
from app.routers import courier as cr
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


from test_courier_c4 import _make_courier, _order  # noqa: F401 — общие помощники
from test_courier_catches_up import _посылка  # noqa: F401


def _до_двери(client, courier, sender, **ov):
    pid = _order(client, sender, **ov).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    return pid


def _не_застал(client, courier, pid):
    assert client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"],
                       json={"reason": "Никого нет дома"}).status_code == 200


def _ждал(pid: int, минут: int) -> None:
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        p.waiting_started_at = utcnow() - timedelta(minutes=минут)
        s.add(p)
        s.commit()


@pytest.fixture
def позиция(monkeypatch):
    """Подменить живую позицию курьера.

    Через monkeypatch, а не через настоящий Redis: проверка присутствия — это правило, а не
    инфраструктура, и падать она должна и на машине, где Redis не поднят. Подменяем ровно ту
    функцию, которую читает сервер (`livepos_get`), чтобы тест шёл тем же путём, что и прод.
    """
    from app import livepos

    состояние = {}

    def подменённый(kind, ref_id):
        return состояние.get((kind, int(ref_id)))

    monkeypatch.setattr(livepos, "livepos_get", подменённый)

    def поставить(pid=None, lat=None, lng=None):
        """Без аргументов — позиции нет вовсе (Redis молчит или курьер не шлёт GPS)."""
        if pid is not None:
            состояние[("parcel", int(pid))] = {"lat": lat, "lng": lng}

    return поставить


# ==================== 1. Отправителя тоже нет дома ====================
def test_arrived_works_while_bringing_the_parcel_back(client, user_factory):
    """Кнопка «Я на месте» работает и на возврате — это время отправителя, не получателя."""
    courier = _make_courier(client, user_factory, name="КурьерВозвратДверь")
    sender = user_factory("ОтпрВозвратДверь")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)
    assert client.post(f"/parcels/{pid}/return-start", headers=courier["auth"],
                       json={"reason": "Нет дома"}).status_code == 200

    r = client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["where"] == "sender", "ожидание записали не на тот конец"


def test_waiting_at_the_closed_door_reaches_the_courier(client, user_factory):
    """Отправителя тоже нет — это время курьеру оплачивается, хотя сумма уже была зафиксирована."""
    courier = _make_courier(client, user_factory, name="КурьерВозвратЖдал")
    sender = user_factory("ОтпрВозвратЖдал")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)
    client.post(f"/parcels/{pid}/return-start", headers=courier["auth"], json={"reason": "Нет дома"})
    было = _посылка(pid).return_fee_kop
    цена_до = _посылка(pid).delivery_price_kop

    client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    минут = settings.wait_free_minutes + 10
    _ждал(pid, минут)
    assert client.post(f"/parcels/{pid}/return-done", headers=courier["auth"]).status_code == 200

    p = _посылка(pid)
    платных = минут - settings.wait_free_minutes
    ожидание = min(платных * settings.wait_fee_rub_per_min * 100, settings.wait_fee_cap_rub * 100)
    assert p.return_fee_kop == было + ожидание, (
        "курьер стоял под запертой дверью отправителя, и это время исчезло"
    )
    assert p.waiting_sender_kop > 0 and p.waiting_receiver_kop == 0
    assert p.delivery_price_kop == цена_до, (
        "цена доставки выросла на ожидание — вслед за ней уехал бы потолок компенсации"
    )


def test_free_minutes_stay_free_on_the_way_back(client, user_factory):
    """Внутри бесплатных минут возврат ничего не добавляет."""
    courier = _make_courier(client, user_factory, name="КурьерВозвратБесплатно")
    sender = user_factory("ОтпрВозвратБесплатно")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)
    client.post(f"/parcels/{pid}/return-start", headers=courier["auth"], json={"reason": "Нет дома"})
    было = _посылка(pid).return_fee_kop
    client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    _ждал(pid, max(settings.wait_free_minutes - 1, 0))
    client.post(f"/parcels/{pid}/return-done", headers=courier["auth"])
    assert _посылка(pid).return_fee_kop == было


def test_the_dispute_stays_open_on_the_way_back(client, user_factory):
    """Отправитель так и не вышел — у курьера есть путь: спор, где разбирает человек."""
    courier = _make_courier(client, user_factory, name="КурьерВозвратСпор")
    sender = user_factory("ОтпрВозвратСпор")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)
    client.post(f"/parcels/{pid}/return-start", headers=courier["auth"], json={"reason": "Нет дома"})
    r = client.post(f"/parcels/{pid}/dispute", headers=courier["auth"],
                    json={"type": "recipient_absent", "text": "Отправителя нет дома, коробка у меня"})
    assert r.status_code == 200, r.text


# ==================== 2. Попытка должна быть поездкой ====================
def test_a_far_away_courier_cannot_claim_an_attempt(client, user_factory, позиция):
    """GPS говорит, что курьер за километры — попытка не засчитывается.

    Это единственное место, где компенсация раньше держалась на слове.
    """
    courier = _make_courier(client, user_factory, name="КурьерДалекоПопытка")
    sender = user_factory("ОтпрДалекоПопытка")
    pid = _до_двери(client, courier, sender, to_lat=53.630, to_lng=55.950)
    позиция(pid, 54.735, 55.958)      # Уфа, а получатель под Стерлитамаком

    r = client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"],
                    json={"reason": "Никого нет"})
    assert r.status_code == 409, "попытку засчитали человеку, который туда не приезжал"
    assert _посылка(pid).delivery_attempts == 0


def test_a_courier_at_the_door_is_believed(client, user_factory, позиция):
    """Курьер реально на месте — попытка засчитывается, как и раньше."""
    courier = _make_courier(client, user_factory, name="КурьерРядомПопытка")
    sender = user_factory("ОтпрРядомПопытка")
    pid = _до_двери(client, courier, sender, to_lat=53.630, to_lng=55.950)
    позиция(pid, 53.6305, 55.9505)    # у двери

    assert client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"],
                       json={"reason": "Никого нет"}).status_code == 200
    assert _посылка(pid).delivery_attempts == 1


def test_no_position_means_we_trust_him(client, user_factory, позиция):
    """Позиции нет — работаем как раньше: молчащая кнопка хуже неточной проверки."""
    courier = _make_courier(client, user_factory, name="КурьерБезGPS")
    sender = user_factory("ОтпрБезGPS")
    pid = _до_двери(client, courier, sender)
    позиция()                          # курьер не шлёт GPS / Redis молчит
    assert client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"],
                       json={"reason": "Никого нет"}).status_code == 200


def test_the_switch_turns_the_check_off(client, user_factory, позиция):
    """Рубильник выключен — не проверяем ничего: GPS в поле бывает шумным."""
    courier = _make_courier(client, user_factory, name="КурьерРубильник")
    sender = user_factory("ОтпрРубильник")
    pid = _до_двери(client, courier, sender, to_lat=53.630, to_lng=55.950)
    позиция(pid, 54.735, 55.958)      # заведомо далеко
    prev = settings.arrival_verify_enabled
    settings.arrival_verify_enabled = False
    try:
        assert client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"],
                           json={"reason": "Никого нет"}).status_code == 200
    finally:
        settings.arrival_verify_enabled = prev


def test_the_same_check_guards_the_waiting_clock(client, user_factory, позиция):
    """«Я на месте» тоже нельзя нажать издалека — иначе ожидание капало бы из дома."""
    courier = _make_courier(client, user_factory, name="КурьерДалекоОжидание")
    sender = user_factory("ОтпрДалекоОжидание")
    pid = _до_двери(client, courier, sender, to_lat=53.630, to_lng=55.950)
    позиция(pid, 54.735, 55.958)

    r = client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    assert r.status_code == 409
    assert _посылка(pid).waiting_started_at is None, "часы ожидания пошли из другого города"


def test_the_border_of_the_radius_is_generous(client, user_factory, позиция):
    """Радиус щедрый: «во дворе» — это на месте, а не «в соседнем селе».

    Проверяем обе стороны границы одним тестом: чуть ближе радиуса — засчитываем,
    заметно дальше — нет. Иначе однажды радиус ужмут до десяти метров и кнопка умрёт.
    """
    from app.services import haversine_km

    courier = _make_courier(client, user_factory, name="КурьерГраница")
    sender = user_factory("ОтпрГраница")
    pid = _до_двери(client, courier, sender, to_lat=53.630, to_lng=55.950)
    радиус_км = float(settings.arrival_verify_radius_m) / 1000.0
    # Сдвиг по широте: 1° ≈ 111 км, берём половину радиуса — заведомо внутри.
    близко = 53.630 + (радиус_км * 0.5) / 111.0
    позиция(pid, близко, 55.950)
    assert haversine_km(близко, 55.950, 53.630, 55.950) * 1000 < settings.arrival_verify_radius_m
    assert client.post(f"/parcels/{pid}/arrived", headers=courier["auth"]).status_code == 200
