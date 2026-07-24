"""G7 — «Диагностика: почему мало откликов»: GET /rides/{id}/tips.

Мягкие ДОБРЫЕ советы водителю по его поездке (нет фото / нет проверки / цена выше средней /
нет деталей). Не наказание. Только своя поездка. Read-only. Уникальные маршруты в тестах —
чтобы средняя цена не зависела от поездок других тестов (общая сессионная БД).
"""
from app.db import engine
from app.models import User
from sqlmodel import Session


def _publish(client, driver, frm, to, price=400, comment="", depart="2030-05-01T09:00:00"):
    body = {"from_city": frm, "to_city": to, "depart_at": depart, "seats_total": 3, "price": price}
    if comment:
        body["comment"] = comment
    r = client.post("/rides", headers=driver["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _tips(client, driver, ride_id):
    r = client.get(f"/rides/{ride_id}/tips", headers=driver["auth"])
    assert r.status_code == 200, r.text
    return r.json()


def _codes(payload):
    return {t["code"] for t in payload["tips"]}


def _set_user(uid, **fields):
    with Session(engine) as s:
        u = s.get(User, uid)
        for k, v in fields.items():
            setattr(u, k, v)
        s.add(u)
        s.commit()


def test_default_driver_photo_and_details_tips(client, user_factory):
    """Дефолтный водитель (verified=True, без аватара), поездка без комментария →
    советы «добавь фото» и «добавь детали»; «пройди проверку» НЕ показываем (уже verified)."""
    drv = user_factory("TipDrv")
    ride = _publish(client, drv, "Г7дефА", "Г7дефБ", comment="")
    out = _tips(client, drv, ride["id"])
    codes = _codes(out)
    assert "add_photo" in codes
    assert "add_details" in codes
    assert "get_verified" not in codes
    assert out["all_good"] is False


def test_unverified_driver_gets_verify_tip(client, user_factory):
    drv = user_factory("UnverDrv")
    _set_user(drv["id"], verified=False)
    ride = _publish(client, drv, "Г7неверА", "Г7неверБ")
    assert "get_verified" in _codes(_tips(client, drv, ride["id"]))


def test_all_good_when_profile_complete(client, user_factory):
    """Аватар + проверен + комментарий + мало данных по маршруту → советов нет, all_good."""
    drv = user_factory("GoodDrv")
    _set_user(drv["id"], avatar_url="https://x/y.jpg", verified=True)
    ride = _publish(client, drv, "Г7хорА", "Г7хорБ", comment="Встреча у вокзала, багаж ок")
    out = _tips(client, drv, ride["id"])
    assert out["tips"] == []
    assert out["all_good"] is True


def test_price_tip_when_above_route_avg(client, user_factory):
    """3 дешёвых поездки по маршруту + наша дорогая → мягкий совет снизить цену."""
    frm, to = "Г7ценаА", "Г7ценаБ"
    for i in range(3):
        d = user_factory(f"CheapDrv{i}")
        _publish(client, d, frm, to, price=200)
    drv = user_factory("PriceyDrv")
    _set_user(drv["id"], avatar_url="a", verified=True)   # чтобы остался только ценовой совет
    ride = _publish(client, drv, frm, to, price=600, comment="ок")
    out = _tips(client, drv, ride["id"])
    assert "lower_price" in _codes(out)
    assert out["route_avg_price"] > 0 and out["route_sample"] >= 4


def test_no_price_tip_when_few_samples(client, user_factory):
    """Мало данных по маршруту (<3) → про цену молчим, даже если цена высокая."""
    drv = user_factory("SoloDrv")
    _set_user(drv["id"], avatar_url="a", verified=True)
    ride = _publish(client, drv, "Г7солоА", "Г7солоБ", price=9999, comment="ок")
    out = _tips(client, drv, ride["id"])
    assert "lower_price" not in _codes(out)
    assert out["all_good"] is True


def test_foreign_ride_404(client, user_factory):
    """Чужую поездку не диагностируем (404 — не раскрываем)."""
    drv = user_factory("OwnerDrv")
    stranger = user_factory("StrangerDrv")
    ride = _publish(client, drv, "Г7чужА", "Г7чужБ")
    r = client.get(f"/rides/{ride['id']}/tips", headers=stranger["auth"])
    assert r.status_code == 404
