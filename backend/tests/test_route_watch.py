"""F13 — подписка на маршрут «карауль поездку».

Проверяем: CRUD подписок; матчинг при публикации поездки шлёт push подходящему сторожу
и НЕ шлёт неподходящему; анти-спам (второй пуш в сутки не уходит); протухшие игнорируются.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier

import app.services as services
from app.db import engine
from app.models import DeviceToken, Notification, RouteWatch, WebPushSubscription
from app.timeutil import utcnow
from sqlmodel import Session, select


def _publish_ride(client, driver, frm="Сибай", to="Уфа", depart="2030-05-01T09:00:00"):
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": frm, "to_city": to,
        "depart_at": depart, "seats_total": 3, "price": 400,
    })
    assert r.status_code == 200, r.text
    return r.json()


def _capture_push(monkeypatch):
    """Подменяем services.send_push — собираем user_id, кому реально ушёл бы push."""
    sent: list[int] = []
    monkeypatch.setattr(services, "send_push", lambda session, user_id, title, body, data=None: sent.append(user_id))
    return sent


# ---------------------------- CRUD ----------------------------

def test_create_list_delete_route_watch(client, user_factory):
    u = user_factory("Watcher")
    # создать
    r = client.post("/route-watch", headers=u["auth"], json={"from_city": "Сибай", "to_city": "Уфа"})
    assert r.status_code == 200, r.text
    wid = r.json()["id"]
    assert r.json()["direction"] == "forward"
    assert r.json()["expires_at"] > r.json()["created_at"]

    # список — видит свою
    lst = client.get("/route-watch", headers=u["auth"])
    assert lst.status_code == 200
    assert any(w["id"] == wid for w in lst.json())

    # удалить
    d = client.delete(f"/route-watch/{wid}", headers=u["auth"])
    assert d.status_code == 200
    lst2 = client.get("/route-watch", headers=u["auth"])
    assert all(w["id"] != wid for w in lst2.json())


def test_delete_foreign_watch_404(client, user_factory):
    owner = user_factory("Owner")
    stranger = user_factory("Stranger")
    wid = client.post("/route-watch", headers=owner["auth"],
                      json={"from_city": "Баймак", "to_city": "Уфа"}).json()["id"]
    # чужой не может удалить
    assert client.delete(f"/route-watch/{wid}", headers=stranger["auth"]).status_code == 404
    # владелец видит подписку живой
    assert any(w["id"] == wid for w in client.get("/route-watch", headers=owner["auth"]).json())


def test_duplicate_watch_preserves_the_notification_quota_and_expiry(client, user_factory):
    u = user_factory("Dup")
    a = client.post("/route-watch", headers=u["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "watch_date": "2030-05-01T00:15:00+05:00",
    }).json()
    last = utcnow().replace(microsecond=0)
    with Session(engine) as session:
        row = session.get(RouteWatch, a["id"])
        row.last_notified_at = last
        session.commit()
    b = client.post("/route-watch", headers=u["auth"], json={
        "from_city": " сибай ", "to_city": "УФА", "watch_date": "2030-05-01T01:00:00",
    }).json()
    assert b["id"] == a["id"]
    assert b["watch_date"] == a["watch_date"]
    assert b["expires_at"] == a["expires_at"]
    assert b["last_notified_at"] == last.isoformat()


def test_date_direction_and_watch_kind_have_distinct_subscriptions(client, user_factory):
    user = user_factory("Different routes")
    headers = user["auth"]
    route = {"from_city": "Сибай", "to_city": "Уфа"}
    suffixes = ({}, {"watch_date": "2030-05-01T00:00:00"}, {"direction": "both"}, {"watch_kind": "requests"})
    ids = {client.post("/route-watch", headers=headers, json={**route, **extra}).json()["id"] for extra in suffixes}
    assert len(ids) == len(suffixes)
    for bad_place in ("", "A", "Уфа ⑤"):
        bad = client.post("/route-watch", headers=headers, json={**route, "from_city": bad_place})
        assert bad.status_code == 400
        assert bad.json()["detail"]["code"] == "watch_place_invalid"
    assert {watch["id"] for watch in client.get("/route-watch", headers=headers).json()} == ids


def test_registration_status_is_account_scoped_and_does_not_expose_tokens(client, user_factory):
    watcher = user_factory("With channels")
    other = user_factory("Without channels")
    with Session(engine) as session:
        session.add(DeviceToken(user_id=watcher["id"], token="synthetic-route-watch-token"))
        session.add(WebPushSubscription(user_id=watcher["id"], endpoint="https://fixture.invalid/route-watch"))
        session.commit()
    route = {"from_city": "Сибай", "to_city": "Уфа"}
    for user, expected in ((watcher, True), (other, False)):
        created = client.post("/route-watch", headers=user["auth"], json=route)
        assert created.status_code == 200, created.text
        response = client.get("/route-watch", headers=user["auth"]).json()
        assert response == [created.json()]
        status = response[0]
        assert status["registered_push_channels"] == {"app": expected, "web": expected}
        assert status["last_notified_at"] is None
        assert "quiet_from" in status and "quiet_to" in status
        assert {"token", "endpoint", "auth", "user_id"}.isdisjoint(status)


def test_parallel_posts_share_one_watch_and_serialize_account_limit(client, user_factory, monkeypatch):
    from app.routers import route_watch

    user = user_factory("Parallel watcher")
    monkeypatch.setattr(route_watch, "MAX_WATCHES_PER_USER", 2)

    def post_pair(routes):
        barrier = Barrier(2)

        def post(route):
            barrier.wait(timeout=5)
            return client.post("/route-watch", headers=user["auth"], json={"from_city": route, "to_city": "Уфа"})

        with ThreadPoolExecutor(max_workers=2) as workers:
            return list(workers.map(post, routes))

    first = post_pair(["Сибай", "сибай"])
    assert [response.status_code for response in first] == [200, 200]
    assert first[0].json()["id"] == first[1].json()["id"]
    last = post_pair(["Баймак", "Акъяр"])
    assert sorted(response.status_code for response in last) == [200, 400]
    assert len(client.get("/route-watch", headers=user["auth"]).json()) == 2


# ---------------------------- Матчинг ----------------------------

def test_matching_push_to_right_watcher_not_wrong(client, user_factory, monkeypatch):
    watcher = user_factory("RightWatcher")      # ждёт Сибай→Уфа
    other = user_factory("WrongWatcher")        # ждёт Уфа→Магнитогорск (не совпадёт)
    driver = user_factory("Driver1")
    client.post("/route-watch", headers=watcher["auth"], json={"from_city": "Сибай", "to_city": "Уфа"})
    client.post("/route-watch", headers=other["auth"], json={"from_city": "Уфа", "to_city": "Магнитогорск"})

    sent = _capture_push(monkeypatch)
    _publish_ride(client, driver, frm="Сибай", to="Уфа")

    assert watcher["id"] in sent          # подходящему — ушёл
    assert other["id"] not in sent        # неподходящему — нет
    # и в ленте уведомлений у сторожа появилась запись
    notes = client.get("/notifications", headers=watcher["auth"]).json()["items"]
    assert any(n["type"] == "route_watch" and "Сибай" in n["body_ru"] and "Уфа" in n["body_ru"] for n in notes)


def test_driver_does_not_notify_self(client, user_factory, monkeypatch):
    driver = user_factory("SelfDriver")
    client.post("/route-watch", headers=driver["auth"], json={"from_city": "Сибай", "to_city": "Уфа"})
    sent = _capture_push(monkeypatch)
    _publish_ride(client, driver, frm="Сибай", to="Уфа")
    assert driver["id"] not in sent       # сам себе push не шлём


def test_direction_both_matches_reverse(client, user_factory, monkeypatch):
    watcher = user_factory("BothWatcher")
    driver = user_factory("DriverBoth")
    client.post("/route-watch", headers=watcher["auth"],
                json={"from_city": "Сибай", "to_city": "Уфа", "direction": "both"})
    sent = _capture_push(monkeypatch)
    _publish_ride(client, driver, frm="Уфа", to="Сибай")   # обратное направление
    assert watcher["id"] in sent


def test_anti_spam_one_push_per_day(client, user_factory, monkeypatch):
    watcher = user_factory("SpamWatcher")
    driver = user_factory("DriverSpam")
    client.post("/route-watch", headers=watcher["auth"], json={"from_city": "Сибай", "to_city": "Уфа"})

    sent = _capture_push(monkeypatch)
    _publish_ride(client, driver, frm="Сибай", to="Уфа")   # 1-я поездка — push
    _publish_ride(client, driver, frm="Сибай", to="Уфа")   # 2-я в тот же день — НЕ push
    assert sent.count(watcher["id"]) == 1


def test_expired_watch_ignored(client, user_factory, monkeypatch):
    watcher = user_factory("ExpiredWatcher")
    driver = user_factory("DriverExpired")
    wid = client.post("/route-watch", headers=watcher["auth"],
                      json={"from_city": "Сибай", "to_city": "Уфа"}).json()["id"]
    # протухаем подписку в БД
    with Session(engine) as s:
        w = s.get(RouteWatch, wid)
        w.expires_at = utcnow() - timedelta(days=1)
        s.add(w)
        s.commit()
    # протухшая не видна в списке
    assert all(x["id"] != wid for x in client.get("/route-watch", headers=watcher["auth"]).json())

    sent = _capture_push(monkeypatch)
    _publish_ride(client, driver, frm="Сибай", to="Уфа")
    assert watcher["id"] not in sent       # протухшую не матчим


def test_watch_date_filters_by_day(client, user_factory, monkeypatch):
    watcher = user_factory("DateWatcher")
    driver = user_factory("DriverDate")
    client.post("/route-watch", headers=watcher["auth"],
                json={"from_city": "Сибай", "to_city": "Уфа", "watch_date": "2030-05-01T00:00:00"})
    sent = _capture_push(monkeypatch)
    _publish_ride(client, driver, frm="Сибай", to="Уфа", depart="2030-06-10T09:00:00")  # другой день
    assert watcher["id"] not in sent
    _publish_ride(client, driver, frm="Сибай", to="Уфа", depart="2030-05-01T18:00:00")  # нужный день
    assert watcher["id"] in sent


def test_bad_direction_rejected(client, user_factory):
    u = user_factory("BadDir")
    assert client.post("/route-watch", headers=u["auth"],
                       json={"from_city": "A", "to_city": "B", "direction": "sideways"}).status_code == 422
