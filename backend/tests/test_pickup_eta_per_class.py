"""Подача по классу и опциям: стоимость отдельно от неподтверждённого времени."""
from __future__ import annotations

import fakeredis
import pytest
from sqlmodel import Session, select

from app import car_class as cc
from app import instant_service as isv
from app.db import engine
from app.models import DriverProfile, UserRole

# Баймак — точка А. Дальняя точка примерно в 10 км от неё: разница в минутах видна.
ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)
FAR10 = (52.681, 58.317)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _classes(user_id: int, available: str, enabled: str) -> None:
    """Проставляет водителю классы машины напрямую — заявка и модерация тут ни при чём."""
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
        assert p is not None, "у водителя нет профиля — тест проверял бы не то"
        p.car_classes_available = available
        p.car_classes_enabled = enabled
        s.add(p)
        s.commit()


def _online_at(client, user_factory, coord, *, available: str, enabled: str, name: str):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    _classes(d["id"], available, enabled)
    r = client.post("/instant/presence", headers=d["auth"],
                    json={"lat": coord[0], "lng": coord[1]})
    assert r.status_code == 200, r.text
    return d


def _quote(client, pax, **extra):
    r = client.post("/instant/estimate", headers=pax["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1],
        "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
        **extra,
    })
    assert r.status_code == 200, r.text
    return r.json()


def _estimate(client, pax):
    return {o["category"]: o for o in _quote(client, pax)["options"]}


def test_pickup_does_not_publish_a_straight_line_eta(client, user_factory, fake_redis):
    """Даже когда кандидаты видны, GEO-расстояние не подтверждает время подачи."""
    _online_at(client, user_factory, ORIG, available="economy", enabled="economy", name="Рядом")
    _online_at(client, user_factory, FAR10, available="business", enabled="business", name="Далеко")
    pax = user_factory("PaxEta")

    quote = _quote(client, pax)
    opts = {o["category"]: o for o in quote["options"]}
    assert quote["pickup_eta_min"] is None
    for category in ("standard", "business"):
        assert opts[category]["pickup_eta_min"] is None
        assert opts[category]["pickup_pending"] is False
    assert quote["eta_min"] > 0


def test_pickup_fee_quote_uses_the_selected_class(client, user_factory, fake_redis, monkeypatch):
    monkeypatch.setattr(isv, "pickup_enroute", lambda *_: False)
    _online_at(client, user_factory, ORIG, available="economy", enabled="economy", name="NearEconomy")
    _online_at(client, user_factory, FAR10, available="economy,business",
               enabled="business", name="FarBusiness")
    pax = user_factory("PickupQuotePax")

    opts = _estimate(client, pax)
    business = _quote(client, pax, category="business")
    assert opts["standard"]["pickup_fee"] == 0
    assert opts["business"]["pickup_fee"] == business["pickup_fee"] > 0
    assert business["pickup_pending"] is False
    assert business["pickup_eta_min"] is opts["business"]["pickup_eta_min"] is None


def test_missing_required_options_leave_pickup_pending(client, user_factory, fake_redis):
    driver = _online_at(client, user_factory, ORIG, available="business", enabled="business", name="NoSeat")
    pax = user_factory("PickupSeatPax")

    q = _quote(client, pax, category="business", options=[cc.OPT_SEAT_1_4])
    assert q["pickup_pending"] is True
    assert q["pickup_fee"] == 0 and q["pickup_max_rub"] > 0
    assert q["pickup_eta_min"] is None
    opt = next(o for o in q["options"] if o["category"] == "business")
    assert opt["pickup_eta_min"] is None and opt["pickup_pending"] is True
    with Session(engine) as s:
        profile = s.exec(select(DriverProfile).where(DriverProfile.user_id == driver["id"])).one()
        profile.car_options = cc.dump_options([cc.OPT_SEAT_1_4])
        s.add(profile)
        s.commit()
    q = _quote(client, pax, category="business", options=[cc.OPT_SEAT_1_4])
    assert q["pickup_pending"] is False and q["pickup_fee"] == 0
    assert q["pickup_eta_min"] is None


def test_pickup_discount_is_not_borrowed_from_another_class(client, user_factory, fake_redis, monkeypatch):
    economy = _online_at(client, user_factory, (52.65, 58.317), available="economy",
                         enabled="economy", name="OnTheWayEconomy")
    _online_at(client, user_factory, FAR10, available="business", enabled="business", name="NotOnWayBusiness")
    monkeypatch.setattr(isv, "pickup_enroute", lambda _s, did, *_: did == economy["id"])
    pax = user_factory("PickupDiscountPax")
    economy_quote = _quote(client, pax)
    business_quote = _quote(client, pax, category="business")
    assert 0 < economy_quote["pickup_fee"] < economy_quote["pickup_full_fee"]
    assert economy_quote["pickup_enroute"] is True
    assert business_quote["pickup_fee"] == business_quote["pickup_full_fee"] > 0
    assert business_quote["pickup_enroute"] is False


def test_disabled_class_has_no_pickup_candidate(client, user_factory, fake_redis):
    _online_at(client, user_factory, ORIG, available="economy,comfort",
               enabled="economy", name="КомфортВыключен")
    pax = user_factory("PaxOff")

    opts = _estimate(client, pax)
    assert opts["standard"]["pickup_pending"] is False
    assert opts["comfort"]["pickup_pending"] is True
    assert opts["minivan"]["pickup_pending"] is True
    assert all(opt["pickup_eta_min"] is None for opt in opts.values())


def test_без_редиса_поле_просто_пустое(client, user_factory):
    """Redis лёг — экран работает дальше, только без минут. Ронять заказ из-за подсказки нельзя."""
    isv._redis_override = None
    pax = user_factory("PaxNoRedis")
    opts = _estimate(client, pax)
    assert all(o["pickup_eta_min"] is None for o in opts.values()), opts


def test_остановок_не_больше_трёх(client, user_factory):
    """Каждая остановка удлиняет поездку и цену, а водитель на четвёртой начинает отказываться.

    Лимит держит сервер, а не только экран: правило про деньги и логистику не может жить
    в кнопке — её у стороннего клиента просто нет.
    """
    pax = user_factory("PaxStops")
    точки = [{"lat": 52.60 + i / 100, "lng": 58.32, "text": f"Остановка {i}"} for i in range(4)]
    r = client.post("/instant/estimate", headers=pax["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1],
        "to_lat": DEST[0], "to_lng": DEST[1],
        "waypoints": точки,
    })
    assert r.status_code == 422, f"четвёртая остановка должна быть отклонена: {r.text}"

    r3 = client.post("/instant/estimate", headers=pax["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1],
        "to_lat": DEST[0], "to_lng": DEST[1],
        "waypoints": точки[:3],
    })
    assert r3.status_code == 200, f"три остановки должны считаться: {r3.text}"
    assert r3.json()["waypoints_count"] == 3


def test_на_карте_у_машины_есть_класс(client, user_factory, fake_redis):
    """Метка на карте должна выглядеть как та машина, которая приедет.

    Раньше все точки рисовались одной жёлтой машинкой: человек выбирал Бизнес, смотрел на
    карту и не понимал, где эти бизнес-машины. Класс кузова — это «какая машина», а не «кто
    за рулём»: точка остаётся анонимной, ни id, ни имени, ни телефона в ответе нет.
    """
    d = _online_at(client, user_factory, ORIG, available="economy,comfort,business",
                   enabled="business", name="КартаБизнес")
    # Ищем профиль ПО ЭТОМУ водителю: база у тестов общая, и поиск «любой с бизнесом»
    # находил водителя соседнего теста — проверка шла бы не про наш случай.
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        p.car_class = "business"
        s.add(p)
        s.commit()
    pax = user_factory("PaxMap")

    r = client.get("/instant/nearby-drivers", headers=pax["auth"],
                   params={"lat": ORIG[0], "lng": ORIG[1]})
    assert r.status_code == 200, r.text
    машины = r.json()["drivers"]
    assert машины, "водитель на линии рядом — точка обязана быть"
    точка = машины[0]
    assert точка["category"] == "business", точка
    # Анонимность: наружу уходят только координаты, минуты и класс кузова.
    assert set(точка) <= {"lat", "lng", "eta_min", "category"}, точка
