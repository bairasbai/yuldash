"""F17 — постоянные маршруты водителя (DriverSchedule): CRUD, публичный список,
защита чужого, валидация дней недели и времени."""

from app.models import UserRole


def _valid_payload(**over):
    body = {
        "from_city": "Баймак",
        "to_city": "Уфа",
        "weekdays": "5",
        "time": "08:00",
        "comment": "Каждую пятницу",
    }
    body.update(over)
    return body


def test_schedule_crud_and_mine(client, user_factory):
    driver = user_factory("SchedDriver", role=UserRole.driver)

    created = client.post("/driver/schedule", headers=driver["auth"], json=_valid_payload())
    assert created.status_code == 200, created.text
    sched = created.json()
    assert sched["from_city"] == "Баймак"
    assert sched["to_city"] == "Уфа"
    assert sched["weekdays"] == "5"
    assert sched["time"] == "08:00"
    assert sched["active"] is True
    sid = sched["id"]

    mine = client.get("/driver/schedule", headers=driver["auth"])
    assert mine.status_code == 200
    assert any(s["id"] == sid for s in mine.json())

    deleted = client.delete(f"/driver/schedule/{sid}", headers=driver["auth"])
    assert deleted.status_code == 200
    assert deleted.json()["ok"] is True

    mine_after = client.get("/driver/schedule", headers=driver["auth"])
    assert all(s["id"] != sid for s in mine_after.json())


def test_public_schedule_visible_without_auth(client, user_factory):
    driver = user_factory("PublicSchedDriver", role=UserRole.driver)
    client.post("/driver/schedule", headers=driver["auth"], json=_valid_payload(weekdays="1,3,5"))

    # публичный список — без токена
    resp = client.get(f"/drivers/{driver['id']}/schedule")
    assert resp.status_code == 200
    rows = resp.json()
    assert len(rows) >= 1
    row = rows[0]
    assert row["driver_id"] == driver["id"]
    assert row["weekdays"] == "1,3,5"
    assert row["from_city"] == "Баймак"


def test_weekdays_normalized_sorted_and_deduped(client, user_factory):
    driver = user_factory("NormSchedDriver", role=UserRole.driver)
    created = client.post("/driver/schedule", headers=driver["auth"], json=_valid_payload(weekdays="5, 1 ,3,1"))
    assert created.status_code == 200
    assert created.json()["weekdays"] == "1,3,5"


def test_foreign_schedule_not_deletable(client, user_factory):
    owner = user_factory("SchedOwner", role=UserRole.driver)
    other = user_factory("SchedOther", role=UserRole.driver)

    created = client.post("/driver/schedule", headers=owner["auth"], json=_valid_payload())
    sid = created.json()["id"]

    forbidden = client.delete(f"/driver/schedule/{sid}", headers=other["auth"])
    assert forbidden.status_code == 403

    # владелец всё ещё видит своё расписание
    mine = client.get("/driver/schedule", headers=owner["auth"])
    assert any(s["id"] == sid for s in mine.json())


def test_delete_missing_schedule_404(client, user_factory):
    driver = user_factory("SchedMissing", role=UserRole.driver)
    resp = client.delete("/driver/schedule/99999999", headers=driver["auth"])
    assert resp.status_code == 404


def test_schedule_requires_auth(client):
    resp = client.post("/driver/schedule", json=_valid_payload())
    assert resp.status_code == 401


def test_invalid_weekdays_rejected(client, user_factory):
    driver = user_factory("BadWeekdaysDriver", role=UserRole.driver)
    for bad in ["", "0", "8", "пятница", "1,9"]:
        resp = client.post("/driver/schedule", headers=driver["auth"], json=_valid_payload(weekdays=bad))
        assert resp.status_code == 400, f"weekdays={bad!r} should be rejected"


def test_invalid_time_rejected(client, user_factory):
    driver = user_factory("BadTimeDriver", role=UserRole.driver)
    for bad in ["", "8", "25:00", "08:70", "утро", "8-00"]:
        resp = client.post("/driver/schedule", headers=driver["auth"], json=_valid_payload(time=bad))
        assert resp.status_code == 400, f"time={bad!r} should be rejected"


def test_same_city_rejected(client, user_factory):
    driver = user_factory("SameCityDriver", role=UserRole.driver)
    resp = client.post("/driver/schedule", headers=driver["auth"], json=_valid_payload(to_city="баймак"))
    assert resp.status_code == 400


def test_create_schedule_dedups_identical(client, user_factory):
    """Двойной сабмит одинакового расписания → тот же объект, не дубль (идемпотентность)."""
    driver = user_factory("SchedDupDriver", role=UserRole.driver)
    payload = _valid_payload()
    r1 = client.post("/driver/schedule", headers=driver["auth"], json=payload)
    r2 = client.post("/driver/schedule", headers=driver["auth"], json=payload)
    assert r1.status_code == 200 and r2.status_code == 200, r2.text
    assert r1.json()["id"] == r2.json()["id"]                       # тот же, не второй
    mine = client.get("/driver/schedule", headers=driver["auth"]).json()
    assert len([s for s in mine if s["id"] == r1.json()["id"]]) == 1
