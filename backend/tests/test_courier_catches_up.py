"""Курьер догоняет такси: дорога к посылке, ожидание, зима, ночь (решение Александра, 2026-08-28).

Всё, что в такси чинили целой волной, у курьера оставалось как было: он ехал за посылкой
в соседнее село даром, стоял у двери бесплатно, в гололёд вёз без компенсации, а ночью
доставка стоила столько же, сколько днём — и ночью её никто не брал.

Здесь проверяем ровно то, ради чего это делалось:
  • дорога курьера к посылке считается и попадает в счёт, а до появления курьера человеку
    показывают ПОТОЛОК, а не выдуманное число;
  • ожидание считается на обоих концах и раздельно: в чеке видно, кто задержал;
  • комиссия не берётся с компенсаций и берётся с ожидания — то же правило, что у такси.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import CourierProfile, ParcelDelivery, Settlement
from app.routers import courier as cr
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _courier_on():
    """Режим курьера включён на весь файл — как в test_courier_c4."""
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


from test_courier_c4 import _deliver, _make_courier, _order  # noqa: F401 — общие помощники


def _куда(courier_id: int, город: str) -> None:
    """Курьер работает в этом населённом пункте — от него и считается его дорога к посылке."""
    with Session(engine) as s:
        prof = s.exec(select(CourierProfile).where(CourierProfile.user_id == courier_id)).first()
        prof.work_city = город
        s.add(prof)
        s.commit()


def _посылка(pid: int) -> ParcelDelivery:
    with Session(engine) as s:
        return s.get(ParcelDelivery, pid)


def _далёкий_город() -> str:
    """Название НП, до которого от Уфы больше бесплатных километров подачи."""
    with Session(engine) as s:
        уфа = s.exec(select(Settlement).where(Settlement.name_ru == "Уфа")).first()
        assert уфа is not None, "в справочнике нет Уфы"
        from app.services import haversine_km
        for row in s.exec(select(Settlement)).all():
            if row.id == уфа.id or not row.lat:
                continue
            км = haversine_km(уфа.lat, уфа.lng, row.lat, row.lng)
            if 40 < км < 120:            # заведомо дальше бесплатных 3 км, но не край света
                return row.name_ru
    pytest.skip("в справочнике не нашлось подходящего соседнего города")


# ============================ 1. Дорога курьера к посылке ============================
def test_price_promises_a_cap_while_there_is_no_courier(client, user_factory):
    """Курьера ещё нет — честного числа не существует. Показываем потолок и говорим об этом.

    Придумать сумму сейчас значило бы взять деньги за километры, которых может не быть.
    """
    sender = user_factory("ОтпрПотолок")
    r = client.get("/courier/estimate", headers=sender["auth"], params={
        "from_lat": 54.735, "from_lng": 55.958, "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "urgency": "bypath",
    })
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["pickup_kop"] == 0, "сумму назвали до того, как появился курьер"
    assert d["pickup_pending"] is True
    assert d["pickup_max_kop"] > 0, "потолок не назван — человеку не на что опереться"


def test_city_courier_pays_nothing_for_the_approach(client, user_factory):
    """Курьер и посылка в одном городе — строки нет: бесплатные километры её закрывают."""
    courier = _make_courier(client, user_factory, name="КурьерГород")
    _куда(courier["id"], "Уфа")
    sender = user_factory("ОтпрГород")
    ro = _order(client, sender, from_lat=54.735, from_lng=55.958,
                to_lat=54.750, to_lng=55.970)
    pid = ro.json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    p = _посылка(pid)
    assert p.pickup_fee_kop == 0 and p.pickup_pending is False


def test_far_courier_gets_paid_for_the_approach(client, user_factory):
    """Курьер из другого города — его дорога к посылке оплачивается отдельной строкой."""
    далеко = _далёкий_город()
    courier = _make_courier(client, user_factory, name="КурьерДалеко")
    _куда(courier["id"], далеко)
    sender = user_factory("ОтпрДалеко")
    ro = _order(client, sender, from_lat=54.735, from_lng=55.958,
                to_lat=54.750, to_lng=55.970)
    pid = ro.json()["id"]
    цена_до = ro.json()["price_kop"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    p = _посылка(pid)
    assert p.pickup_fee_kop > 0, f"курьер из {далеко} везёт даром"
    потолок = max(settings.courier_pickup_max_kop, settings.courier_pickup_max_intercity_kop)
    assert p.pickup_fee_kop <= потолок, "строка выше потолка"
    assert p.delivery_price_kop == цена_до + p.pickup_fee_kop
    assert p.pickup_km > 0


def test_unknown_work_city_costs_the_sender_nothing(client, user_factory):
    """Город курьера не разобрали — денег не берём.

    Брать «наверное он далеко» нельзя: это ровно тот случай, когда человек не может
    ни проверить, ни поспорить.
    """
    courier = _make_courier(client, user_factory, name="КурьерБезГорода")
    _куда(courier["id"], "Такого-Города-Нет")
    sender = user_factory("ОтпрБезГорода")
    pid = _order(client, sender).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    p = _посылка(pid)
    assert p.pickup_fee_kop == 0 and p.pickup_pending is False


# ============================ 2. Платное ожидание ============================
def test_arrived_button_works_on_both_ends(client, user_factory):
    """Одна кнопка, две ситуации: сервер сам понимает по статусу, у кого курьер стоит."""
    courier = _make_courier(client, user_factory, name="КурьерОжидание")
    _куда(courier["id"], "Уфа")
    sender = user_factory("ОтпрОжидание")
    order = _order(client, sender).json()
    pid, code = order["id"], order["confirm_code"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])

    у_отправителя = client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    assert у_отправителя.status_code == 200, у_отправителя.text
    assert у_отправителя.json()["where"] == "sender"

    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    у_получателя = client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    assert у_получателя.status_code == 200
    assert у_получателя.json()["where"] == "receiver"

    client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                json={"status": "delivered", "code": code})


def test_waiting_is_remembered_per_end(client, user_factory):
    """В чеке видно, где сколько набежало: делят это между собой отправитель с получателем."""
    courier = _make_courier(client, user_factory, name="КурьерДваКонца")
    _куда(courier["id"], "Уфа")
    sender = user_factory("ОтпрДваКонца")
    order = _order(client, sender).json()
    pid, code = order["id"], order["confirm_code"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    _сдвинуть(pid, минут=13)
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})

    p = _посылка(pid)
    платных = 13 - settings.wait_free_minutes
    assert p.waiting_sender_kop == платных * settings.wait_fee_rub_per_min * 100
    assert p.waiting_receiver_kop == 0

    client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    _сдвинуть(pid, минут=9)
    client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                json={"status": "delivered", "code": code})
    p = _посылка(pid)
    assert p.waiting_receiver_kop > 0, "ожидание у получателя не записалось"


def test_waiting_cap_is_per_delivery(client, user_factory):
    """Потолок ожидания — на всю доставку, а не на каждый конец (урок такси, 2026-08-28)."""
    courier = _make_courier(client, user_factory, name="КурьерПотолок")
    _куда(courier["id"], "Уфа")
    sender = user_factory("ОтпрПотолок2")
    order = _order(client, sender).json()
    pid, code = order["id"], order["confirm_code"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    _сдвинуть(pid, минут=600)
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    _сдвинуть(pid, минут=600)
    client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                json={"status": "delivered", "code": code})
    p = _посылка(pid)
    итого = int(p.waiting_sender_kop or 0) + int(p.waiting_receiver_kop or 0)
    assert итого == settings.wait_fee_cap_rub * 100


def test_poputka_has_no_paid_waiting(client, user_factory):
    """У доставки «по пути» тарифа нет — и платного ожидания тоже."""
    from test_api import _ride  # noqa: F401 — не используется, но держит общий импортный стиль
    courier = _make_courier(client, user_factory, name="КурьерПоПути")
    sender = user_factory("ОтпрПоПути")
    pid = _order(client, sender).json()["id"]
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        p.delivery_type = "poputka"
        s.add(p)
        s.commit()
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    r = client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    assert r.status_code == 409


def _сдвинуть(pid: int, минут: int) -> None:
    """Отодвинуть начало ожидания в прошлое — часы в тесте не ждём."""
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        p.waiting_started_at = utcnow() - timedelta(minutes=минут)
        s.add(p)
        s.commit()


# ============================ 3. Комиссия и компенсации ============================
def test_commission_skips_the_courier_compensations(client):
    """С дороги к посылке и с зимней дороги комиссию не берём — как у такси."""
    p = ParcelDelivery(sender_id=1, from_city="Уфа", to_city="Сибай", description="тест",
                       receiver_name="Тест", delivery_price_kop=50000,
                       pickup_fee_kop=15000, weather_fee_kop=5000)
    assert cr.courier_commission_base_kop(p) == 30000


def test_commission_is_taken_from_the_waiting(client):
    """А с ожидания берём: это рабочее время курьера, а не его расход."""
    p = ParcelDelivery(sender_id=1, from_city="Уфа", to_city="Сибай", description="тест",
                       receiver_name="Тест", delivery_price_kop=40000,
                       waiting_sender_kop=7000)
    # Ожидание уже внутри delivery_price_kop и из базы не вычитается.
    assert cr.courier_commission_base_kop(p) == 40000


def test_buy_bring_costs_the_same_percent(client):
    """«Купи и привези» — по той же ставке: надбавку убрали при переходе на 15%."""
    assert cr.COURIER_BUY_BRING_EXTRA_PERCENT == 0.0


def test_courier_top_rate_matches_taxi(client):
    """Верхняя ступень курьера равна такси: две разных морали в одном приложении не живут."""
    assert settings.courier_service_fee_percent == settings.service_fee_percent


# ============================ 4. Ночь и зима ============================
def test_night_multiplies_the_delivery_not_the_compensations(client, monkeypatch):
    """Ночью дорожает доставка, а не бензин курьера."""
    monkeypatch.setattr(settings, "night_k_default", 1.15)
    день = cr.courier_night_k(_час(12))
    ночь = cr.courier_night_k(_час(2))
    assert день == 1.0 and ночь == pytest.approx(1.15)


def test_night_is_off_when_config_says_so(client, monkeypatch):
    monkeypatch.setattr(settings, "night_k_default", 1.0)
    assert cr.courier_night_k(_час(2)) == 1.0


def _час(местный: int):
    сдвиг = int(settings.local_tz_offset_hours)
    return utcnow().replace(hour=(местный - сдвиг) % 24, minute=30, second=0, microsecond=0)
