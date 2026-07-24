"""G3 — «Пуш водителю: заявка по твоему направлению» (watch_kind=requests).

Водительская сторона попуток: водитель караулит направление (RouteWatch, watch_kind=requests),
и когда пассажир создаёт заявку по этому маршруту — водитель получает push + запись в ленте.
Зеркало F13 (карауль поездку). Проверяем: матч по заявке шлёт только requests/both-сторожам,
rides-сторож на заявку НЕ реагирует (и наоборот на поездку), автору не шлём, both, обратное
направление, анти-спам 1/сутки, watch_kind в выдаче и валидация.

Ассертим по ленте уведомлений (пишется синхронно) — детерминированно, без гонок с фоновым FCM.
"""
from datetime import timedelta

from app.db import engine
from app.models import RouteWatch
from app.timeutil import utcnow
from sqlmodel import Session

from test_route_watch import _publish_ride   # noqa: F401 — переиспользуем публикацию поездки


def _watch(client, user, kind="requests", frm="Сибай", to="Уфа", **extra):
    body = {"from_city": frm, "to_city": to, "watch_kind": kind}
    body.update(extra)
    r = client.post("/route-watch", headers=user["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _request(client, passenger, frm="Сибай", to="Уфа", **extra):
    body = {"from_city": frm, "to_city": to}
    body.update(extra)
    r = client.post("/requests", headers=passenger["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _req_notes(client, user):
    """Записи «пассажир на маршруте» (type=request_watch) в ленте пользователя."""
    items = client.get("/notifications", headers=user["auth"]).json()["items"]
    return [n for n in items if n["type"] == "request_watch"]


# ---------------------------- Матчинг ----------------------------

def test_driver_requests_watch_notified_on_matching_request(client, user_factory):
    driver = user_factory("ReqWatchDrv")
    passenger = user_factory("ReqPax")
    _watch(client, driver, kind="requests", frm="Сибай", to="Уфа")
    _request(client, passenger, frm="Сибай", to="Уфа")
    notes = _req_notes(client, driver)
    assert notes and "Сибай" in notes[0]["body_ru"] and "Уфа" in notes[0]["body_ru"]


def test_rides_watch_ignores_requests(client, user_factory):
    """Пассажирский сторож (watch_kind=rides, дефолт) на ЗАЯВКУ не реагирует — он ждёт поездки."""
    watcher = user_factory("RidesWatcher")
    passenger = user_factory("RPax2")
    _watch(client, watcher, kind="rides", frm="Сибай", to="Уфа")   # ждёт поездки
    _request(client, passenger, frm="Сибай", to="Уфа")
    assert _req_notes(client, watcher) == []


def test_requests_watch_ignores_rides(client, user_factory):
    """Водительский сторож (watch_kind=requests) на ПОЕЗДКУ не реагирует — он ждёт заявки."""
    driver_watcher = user_factory("ReqOnlyWatcher")
    other_driver = user_factory("OtherDrv")
    _watch(client, driver_watcher, kind="requests", frm="Сибай", to="Уфа")
    _publish_ride(client, other_driver, frm="Сибай", to="Уфа")
    # route_watch-записи (поездки) у него быть не должно
    items = client.get("/notifications", headers=driver_watcher["auth"]).json()["items"]
    assert all(n["type"] != "route_watch" for n in items)


def test_both_kind_notified_on_request_and_ride(client, user_factory):
    """watch_kind=both ловит и заявки, и поездки. Анти-спам (1/сутки на подписку) общий для обоих
    типов, поэтому между событиями сбрасываем last_notified_at (будто прошли сутки)."""
    user = user_factory("BothKind")
    passenger = user_factory("BothPax")
    driver = user_factory("BothDrv")
    wid = _watch(client, user, kind="both", frm="Сибай", to="Уфа")["id"]
    _request(client, passenger, frm="Сибай", to="Уфа")
    assert _req_notes(client, user)   # заявка долетела
    # Сброс анти-спама → проверяем и поездочную сторону both.
    with Session(engine) as s:
        w = s.get(RouteWatch, wid)
        w.last_notified_at = None
        s.add(w)
        s.commit()
    _publish_ride(client, driver, frm="Сибай", to="Уфа")
    items = client.get("/notifications", headers=user["auth"]).json()["items"]
    assert any(n["type"] == "route_watch" for n in items)     # поездка тоже долетела


def test_author_not_self_notified(client, user_factory):
    """Пассажир караулит заявки и сам же создаёт заявку — себе push не шлём."""
    pax = user_factory("SelfReq")
    _watch(client, pax, kind="requests", frm="Сибай", to="Уфа")
    _request(client, pax, frm="Сибай", to="Уфа")
    assert _req_notes(client, pax) == []


def test_direction_both_matches_reverse_request(client, user_factory):
    driver = user_factory("RevDrv")
    passenger = user_factory("RevPax")
    _watch(client, driver, kind="requests", frm="Сибай", to="Уфа", direction="both")
    _request(client, passenger, frm="Уфа", to="Сибай")   # обратное направление
    assert _req_notes(client, driver)


def test_anti_spam_one_per_day(client, user_factory):
    driver = user_factory("SpamReqDrv")
    p1 = user_factory("SpamPax1")
    p2 = user_factory("SpamPax2")
    _watch(client, driver, kind="requests", frm="Сибай", to="Уфа")
    _request(client, p1, frm="Сибай", to="Уфа")   # 1-я заявка — push
    _request(client, p2, frm="Сибай", to="Уфа")   # 2-я в тот же день — НЕ push
    assert len(_req_notes(client, driver)) == 1


def test_expired_requests_watch_ignored(client, user_factory):
    driver = user_factory("ExpReqDrv")
    passenger = user_factory("ExpReqPax")
    wid = _watch(client, driver, kind="requests", frm="Сибай", to="Уфа")["id"]
    with Session(engine) as s:
        w = s.get(RouteWatch, wid)
        w.expires_at = utcnow() - timedelta(days=1)
        s.add(w)
        s.commit()
    _request(client, passenger, frm="Сибай", to="Уфа")
    assert _req_notes(client, driver) == []


# ---------------------------- Контракт ----------------------------

def test_watch_kind_in_output_and_validated(client, user_factory):
    u = user_factory("KindOut")
    out = _watch(client, u, kind="requests", frm="Баймак", to="Уфа")
    assert out["watch_kind"] == "requests"
    # дефолт — rides (старый клиент без поля)
    d = client.post("/route-watch", headers=u["auth"], json={"from_city": "Учалы", "to_city": "Уфа"})
    assert d.status_code == 200 and d.json()["watch_kind"] == "rides"
    # мусорный kind — 422
    bad = client.post("/route-watch", headers=u["auth"],
                      json={"from_city": "A", "to_city": "B", "watch_kind": "everything"})
    assert bad.status_code == 422


def test_same_route_different_kind_not_deduped(client, user_factory):
    """rides и requests на одном маршруте — разные интенты: две подписки, не одна."""
    u = user_factory("TwoKinds")
    a = _watch(client, u, kind="rides", frm="Сибай", to="Уфа")
    b = _watch(client, u, kind="requests", frm="Сибай", to="Уфа")
    assert a["id"] != b["id"]
