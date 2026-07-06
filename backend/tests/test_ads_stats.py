"""F20 — статистика кабинета рекламодателя: показы/клики/CTR/срок из AdEvent.

Ключевое:
- CTR считается верно (клики / показы * 100).
- Пустая статистика (без событий) = нули, CTR 0.0.
- IDOR: партнёр видит ТОЛЬКО свою статистику, чужую — 404 / не в списке.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Ad, AdEvent
from app.timeutil import utcnow


def _make_ad(owner_id, title="Реклама", status="active", package="city", days=None):
    with Session(engine) as s:
        ad = Ad(owner_id=owner_id, title=title, text="текст", status=status, package=package)
        if days is not None:
            ad.starts_at = utcnow()
            ad.ends_at = utcnow() + timedelta(days=days)
        s.add(ad)
        s.commit()
        s.refresh(ad)
        return ad.id


def _events(ad_id, impressions=0, clicks=0):
    with Session(engine) as s:
        for _ in range(impressions):
            s.add(AdEvent(ad_id=ad_id, event_type="impression"))
        for _ in range(clicks):
            s.add(AdEvent(ad_id=ad_id, event_type="click"))
        s.commit()


def test_stats_requires_auth(client):
    assert client.get("/ads/mine/stats").status_code == 401


def test_stats_empty_for_new_user(client, user_factory):
    u = user_factory(name="Партнёр без реклам")
    r = client.get("/ads/mine/stats", headers=u["auth"])
    assert r.status_code == 200
    assert r.json() == []


def test_stats_ctr_aggregation(client, user_factory):
    """Показы/клики агрегируются из AdEvent, CTR = клики/показы*100."""
    u = user_factory(name="Партнёр")
    ad_id = _make_ad(u["id"], title="Кафе", days=30)
    _events(ad_id, impressions=200, clicks=10)
    r = client.get("/ads/mine/stats", headers=u["auth"])
    assert r.status_code == 200
    rows = r.json()
    assert len(rows) == 1
    st = rows[0]
    assert st["ad_id"] == str(ad_id)
    assert st["impressions"] == 200
    assert st["clicks"] == 10
    assert st["ctr"] == 5.0                 # 10 / 200 * 100
    assert st["days_left"] in (29, 30)      # срок размещения виден


def test_stats_zero_impressions_no_div_by_zero(client, user_factory):
    """Пустая статистика: 0 показов → CTR 0.0, без падения."""
    u = user_factory(name="Партнёр")
    ad_id = _make_ad(u["id"], title="Новое")
    r = client.get("/ads/mine/stats", headers=u["auth"])
    assert r.status_code == 200
    st = next(x for x in r.json() if x["ad_id"] == str(ad_id))
    assert st["impressions"] == 0 and st["clicks"] == 0 and st["ctr"] == 0.0
    assert st["days_left"] is None          # срок не задан (не запущено/бессрочно)


def test_stats_single_ad_owner_ok(client, user_factory):
    u = user_factory(name="Партнёр")
    ad_id = _make_ad(u["id"], title="Одно")
    _events(ad_id, impressions=50, clicks=5)
    r = client.get(f"/ads/{ad_id}/stats", headers=u["auth"])
    assert r.status_code == 200
    assert r.json()["ctr"] == 10.0


def test_stats_single_ad_idor_404(client, user_factory):
    """IDOR: чужую статистику по id не отдаём — 404 (не раскрываем существование)."""
    a = user_factory(name="Владелец")
    b = user_factory(name="Чужой")
    ad_id = _make_ad(a["id"], title="Секрет А")
    _events(ad_id, impressions=99, clicks=9)
    assert client.get(f"/ads/{ad_id}/stats", headers=b["auth"]).status_code == 404


def test_stats_list_owner_scoped_idor(client, user_factory):
    """IDOR: в списке /ads/mine/stats — только свои объявления, чужие не протекают."""
    a = user_factory(name="Партнёр А")
    b = user_factory(name="Партнёр Б")
    ad_a = _make_ad(a["id"], title="Реклама А")
    _events(ad_a, impressions=30, clicks=3)
    rb = client.get("/ads/mine/stats", headers=b["auth"])
    assert rb.status_code == 200
    assert all(x["ad_id"] != str(ad_a) for x in rb.json())


def test_stats_missing_ad_404(client, user_factory):
    u = user_factory()
    assert client.get("/ads/999999/stats", headers=u["auth"]).status_code == 404
