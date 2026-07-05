"""F13 — подписка на маршрут «карауль поездку».

Проверяем: CRUD подписок; матчинг при публикации поездки шлёт push подходящему сторожу
и НЕ шлёт неподходящему; анти-спам (второй пуш в сутки не уходит); протухшие игнорируются.
"""
from datetime import timedelta

import app.services as services
from app.db import engine
from app.models import Notification, RouteWatch
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
    monkeypatch.setattr(services, "send_push", lambda session, user_id, title, body: sent.append(user_id))
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


def test_duplicate_watch_updates_not_duplicates(client, user_factory):
    u = user_factory("Dup")
    a = client.post("/route-watch", headers=u["auth"], json={"from_city": "Сибай", "to_city": "Уфа"})
    b = client.post("/route-watch", headers=u["auth"], json={"from_city": " сибай ", "to_city": "УФА"})
    assert a.json()["id"] == b.json()["id"]   # тот же маршрут (регистр/пробелы) → обновили, не размножили


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
    notes = client.get("/notifications", headers=watcher["auth"]).json()
    assert any(n["type"] == "route_watch" and "Сибай" in n["text"] and "Уфа" in n["text"] for n in notes)


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
