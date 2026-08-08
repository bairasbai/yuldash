"""F9 «Женщинам — водитель-женщина»: пол водителя — строго opt-in.

Контракты:
1. opt-in по умолчанию: водитель без указанного пола не считается «женщиной» и
   не подмешивается в фильтр women_only только из-за пола.
2. Фильтр women_only находит поездку женщины-водителя, даже если у самой поездки
   флаг women_only НЕ выставлен.
3. Приватность: мужской пол наружу не выпячивается — витрина мужчины неотличима
   от «не указан» (driver_is_woman=False), и мужчина не попадает в women_only.
"""
from datetime import timedelta

from app.timeutil import utcnow
from app.models import UserRole


def _publish(client, drv, frm, to="Сибай", **extra):
    body = {"from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
            "seats_total": 3, "price": 300, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _set_gender(client, drv, gender):
    r = client.post("/driver/gender", headers=drv["auth"], json={"gender": gender})
    assert r.status_code == 200, r.text
    return r.json()


# 1. opt-in по умолчанию ----------------------------------------------------
def test_gender_optin_default_not_woman(client, user_factory):
    """Без указанного пола водитель НЕ помечается женщиной и НЕ попадает в women_only."""
    drv = user_factory("PlainDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="НейтралГрад")

    rows = client.get("/rides", params={"from_city": "НейтралГрад"}).json()
    row = next(r for r in rows if r["id"] == ride["id"])
    assert row["driver_is_woman"] is False          # опт-ин: пол не указан → не «женщина»

    # поездка без флага women_only и без пола → фильтр women_only её не показывает
    wonly = client.get("/rides", params={"from_city": "НейтралГрад", "women_only": True}).json()
    assert all(r["id"] != ride["id"] for r in wonly)


# 2. Фильтр находит женщину-водителя ---------------------------------------
def test_women_only_filter_finds_female_driver(client, user_factory):
    """Женщина-водитель без флага women_only на поездке — всё равно видна в фильтре."""
    drv = user_factory("FemDrv", role=UserRole.driver)
    _set_gender(client, drv, "female")
    ride = _publish(client, drv, frm="ЖенГрад")     # women_only НЕ выставлен намеренно

    wonly = client.get("/rides", params={"from_city": "ЖенГрад", "women_only": True}).json()
    match = next((r for r in wonly if r["id"] == ride["id"]), None)
    assert match is not None                         # найдена по полу водителя
    assert match["driver_is_woman"] is True          # бейдж «Водитель-женщина»


def test_women_only_flag_ride_stays_in_the_filter(client, user_factory):
    """Поездка с флагом women_only остаётся в фильтре — регресс.

    Раньше тест ставил флаг водителю БЕЗ пола: тогда отметка была пожеланием и её мог
    поставить кто угодно. С 2026-08-08 «только женщины» — правило, и ставит его женщина
    за рулём (см. `test_women_only_flag_requires_a_female_driver`), поэтому и здесь
    водитель — женщина.
    """
    drv = user_factory("FlagDrv", role=UserRole.driver, gender="female")
    ride = _publish(client, drv, frm="ФлагГрад", women_only=True)
    wonly = client.get("/rides", params={"from_city": "ФлагГрад", "women_only": True}).json()
    assert any(r["id"] == ride["id"] for r in wonly)


def test_women_only_flag_requires_a_female_driver(client, user_factory):
    """Мужчина за рулём не может пометить поездку «только женщины».

    Иначе отметка — приманка: женщина садится в машину, думая, что проверено.
    Не указавшему пол отвечаем не отказом, а просьбой заполнить профиль.
    """
    male = user_factory("FlagMale", role=UserRole.driver, gender="male")
    r = client.post("/rides", headers=male["auth"], json={
        "from_city": "ФлагГрад2", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "women_only": True,
    })
    assert r.status_code == 403, r.text

    unknown = user_factory("FlagNoGender", role=UserRole.driver)
    r2 = client.post("/rides", headers=unknown["auth"], json={
        "from_city": "ФлагГрад3", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "women_only": True,
    })
    assert r2.status_code == 403
    assert "профил" in r2.json()["detail"]["ru"].lower()   # просим заполнить, а не отказываем молча


# 3. Приватность: мужской пол не выпячиваем --------------------------------
def test_male_gender_not_surfaced(client, user_factory):
    """Мужчина-водитель: driver_is_woman=False, витрина не раскрывает «male»,
    и он НЕ попадает в фильтр women_only (без флага на поездке)."""
    drv = user_factory("MaleDrv", role=UserRole.driver)
    _set_gender(client, drv, "male")
    ride = _publish(client, drv, frm="МужГрад")

    rows = client.get("/rides", params={"from_city": "МужГрад"}).json()
    row = next(r for r in rows if r["id"] == ride["id"])
    assert row["driver_is_woman"] is False
    # витрина не должна содержать сырое поле пола — наружу уходит только бинарный сигнал
    assert "gender" not in row and "driver_gender" not in row

    wonly = client.get("/rides", params={"from_city": "МужГрад", "women_only": True}).json()
    assert all(r["id"] != ride["id"] for r in wonly)


def test_gender_own_profile_visible_and_validated(client, user_factory):
    """Свой пол водитель видит в /driver/status; недопустимое значение — 400."""
    drv = user_factory("StatusDrv", role=UserRole.driver)
    _set_gender(client, drv, "female")
    assert client.get("/driver/status", headers=drv["auth"]).json()["gender"] == "female"
    # можно снять (opt-out)
    _set_gender(client, drv, "")
    assert client.get("/driver/status", headers=drv["auth"]).json()["gender"] == ""
    # мусорное значение отклоняется
    assert client.post("/driver/gender", headers=drv["auth"], json={"gender": "other"}).status_code == 400
    # нужен токен
    assert client.post("/driver/gender", json={"gender": "female"}).status_code == 401
