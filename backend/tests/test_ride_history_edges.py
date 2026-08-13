"""F6 — история поездок водителя (фильтр статусов) + отзыв отклика (withdraw)."""

from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, Ride, RideRequest, RideStatus, User, UserRole
from app.timeutil import utcnow


def _make_ride(driver_id, status, hours_from_now=1, seats_total=3, seats_left=1):
    with Session(engine) as s:
        ride = Ride(
            driver_id=driver_id, from_city="HistA", to_city="HistB",
            depart_at=utcnow() + timedelta(hours=hours_from_now),
            seats_total=seats_total, seats_left=seats_left, price=300,
            status=status,
        )
        s.add(ride)
        s.commit()
        s.refresh(ride)
        return ride.id


def test_driver_rides_status_filter(client, user_factory):
    driver = user_factory("HistDriver", role=UserRole.driver)
    active_id = _make_ride(driver["id"], RideStatus.active, hours_from_now=2)
    done_id = _make_ride(driver["id"], RideStatus.done, hours_from_now=-5)
    cancelled_id = _make_ride(driver["id"], RideStatus.cancelled, hours_from_now=-3)

    # Дефолт (без параметра) — только активные, как раньше (не сломали Boost).
    default = client.get("/driver/rides", headers=driver["auth"])
    assert default.status_code == 200
    ids = {r["id"] for r in default.json()}
    assert active_id in ids
    assert done_id not in ids and cancelled_id not in ids

    # status=active — то же самое.
    active = client.get("/driver/rides", headers=driver["auth"], params={"status": "active"})
    assert {r["id"] for r in active.json()} == {active_id}

    # status=done — только завершённые.
    done = client.get("/driver/rides", headers=driver["auth"], params={"status": "done"})
    assert done.status_code == 200
    done_ids = {r["id"] for r in done.json()}
    assert done_id in done_ids
    assert active_id not in done_ids and cancelled_id not in done_ids
    assert all(r["status"] == "done" for r in done.json())

    # status=cancelled — только отменённые.
    cancelled = client.get("/driver/rides", headers=driver["auth"], params={"status": "cancelled"})
    assert {r["id"] for r in cancelled.json()} == {cancelled_id}

    # status=all — все три статуса водителя.
    all_rides = client.get("/driver/rides", headers=driver["auth"], params={"status": "all"})
    assert all_rides.status_code == 200
    all_ids = {r["id"] for r in all_rides.json()}
    assert {active_id, done_id, cancelled_id} <= all_ids


def test_driver_rides_status_isolated_per_driver(client, user_factory):
    driver_a = user_factory("HistDriverA", role=UserRole.driver)
    driver_b = user_factory("HistDriverB", role=UserRole.driver)
    a_done = _make_ride(driver_a["id"], RideStatus.done, hours_from_now=-2)
    b_done = _make_ride(driver_b["id"], RideStatus.done, hours_from_now=-2)

    resp = client.get("/driver/rides", headers=driver_a["auth"], params={"status": "all"})
    ids = {r["id"] for r in resp.json()}
    assert a_done in ids
    assert b_done not in ids   # чужой архив не виден


def _create_request(client, passenger, **overrides):
    body = {"from_city": "WdA", "to_city": "WdB", "seats": 1, "max_price": 400, **overrides}
    r = client.post("/requests", headers=passenger["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def test_withdraw_own_pending_response(client, user_factory):
    passenger = user_factory("WdPassenger")
    driver = user_factory("WdDriver", role=UserRole.driver)
    request = _create_request(client, passenger)

    resp = client.post(f"/requests/{request['id']}/respond", headers=driver["auth"], json={"price": 350})
    assert resp.status_code == 200
    response_id = resp.json()["id"]

    # Лента водителя отдаёт id своего отклика (чтобы кнопка «Отозвать» знала, что удалять).
    feed = client.get("/requests/feed", headers=driver["auth"])
    feed_row = next(item for item in feed.json() if item["id"] == request["id"])
    assert feed_row["responded"] is True
    assert feed_row["my_response_id"] == response_id

    # Отклик виден пассажиру.
    listed = client.get(f"/requests/{request['id']}/responses", headers=passenger["auth"])
    assert any(r["id"] == response_id for r in listed.json())

    # Водитель отзывает свой отклик — пока не принят.
    withdrawn = client.delete(f"/responses/{response_id}", headers=driver["auth"])
    assert withdrawn.status_code == 200
    assert withdrawn.json()["ok"] is True

    # Отклик исчез из списка пассажира.
    after = client.get(f"/requests/{request['id']}/responses", headers=passenger["auth"])
    assert all(r["id"] != response_id for r in after.json())

    # Повторный отзыв проходит спокойно (200): строка отклика остаётся в базе следом для
    # потолка темпа — раньше она удалялась, и на этом строился поток пушей пассажиру
    # («откликнулся → отозвал → откликнулся», аудит 2026-08-12, волна 51).
    assert client.delete(f"/responses/{response_id}", headers=driver["auth"]).status_code == 200
    # …и отклик по-прежнему не виден пассажиру — для него предложения нет.
    again = client.get(f"/requests/{request['id']}/responses", headers=passenger["auth"])
    assert all(r["id"] != response_id for r in again.json())


def test_withdraw_foreign_response_forbidden(client, user_factory):
    passenger = user_factory("WdFPassenger")
    driver = user_factory("WdFDriver", role=UserRole.driver)
    outsider = user_factory("WdFOutsider", role=UserRole.driver)
    request = _create_request(client, passenger, from_city="WdFA", to_city="WdFB")

    response_id = client.post(
        f"/requests/{request['id']}/respond", headers=driver["auth"], json={"price": 300}
    ).json()["id"]

    # Чужой водитель не может отозвать не свой отклик.
    assert client.delete(f"/responses/{response_id}", headers=outsider["auth"]).status_code == 403
    # Пассажир (владелец заявки, но не автор отклика) — тоже нет.
    assert client.delete(f"/responses/{response_id}", headers=passenger["auth"]).status_code == 403
    # Отклик остался на месте.
    still = client.get(f"/requests/{request['id']}/responses", headers=passenger["auth"])
    assert any(r["id"] == response_id for r in still.json())


def test_withdraw_accepted_response_conflict(client, user_factory):
    passenger = user_factory("WdAPassenger")
    driver = user_factory("WdADriver", role=UserRole.driver)
    request = _create_request(client, passenger, from_city="WdAA", to_city="WdAB")

    response_id = client.post(
        f"/requests/{request['id']}/respond", headers=driver["auth"], json={"price": 300}
    ).json()["id"]

    accepted = client.post(f"/responses/{response_id}/accept", headers=passenger["auth"])
    assert accepted.status_code == 200

    # После accept отозвать нельзя → 409 (поездка уже создана).
    conflict = client.delete(f"/responses/{response_id}", headers=driver["auth"])
    assert conflict.status_code == 409


def test_withdraw_missing_response_404(client, user_factory):
    driver = user_factory("WdMissingDriver", role=UserRole.driver)
    assert client.delete("/responses/99999999", headers=driver["auth"]).status_code == 404
