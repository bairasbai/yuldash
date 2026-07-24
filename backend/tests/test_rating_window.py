"""G6 — «окно свежести» рейтинга: среднее по последним RATING_RECENT_WINDOW учтённым оценкам.

Проверяем: при ≤окна поведение как раньше (простое среднее); при >окна старые оценки выпадают
(«право исправиться»), но число оценок остаётся ПОЛНЫМ; user_rating и drivers_bundle (карточка)
дают ОДИНАКОВЫЙ результат (не расходятся); анти-накрутка пары сохранилась; легаси-null — как старая.
"""
from datetime import timedelta

from app.db import engine
from app.models import Rating, User, UserRole
from app.services import RATING_RECENT_WINDOW, _capped_entries, drivers_bundle, user_rating
from app.timeutil import utcnow
from sqlmodel import Session

_ctr = {"n": 0}


def _raters(n, prefix):
    """n реальных пользователей-оценщиков (FK на проде строгий — нужны настоящие user.id)."""
    ids = []
    with Session(engine) as s:
        for _ in range(n):
            _ctr["n"] += 1
            u = User(phone=f"rw-{prefix}-{_ctr['n']}", name="R", telegram_id=f"rw{prefix}{_ctr['n']}",
                     verified=True)
            s.add(u)
            s.commit()
            s.refresh(u)
            ids.append(u.id)
    return ids


def _rate(ratee_id, rater_ids, stars, when):
    with Session(engine) as s:
        for rid in rater_ids:
            s.add(Rating(rater_id=rid, ratee_id=ratee_id, stars=stars, created_at=when))
        s.commit()


def _rating(ratee_id):
    with Session(engine) as s:
        return user_rating(s, ratee_id)


def test_under_window_unchanged(client, user_factory):
    """≤ окна → простое среднее по всем (прежнее поведение)."""
    drv = user_factory("RwSmall")
    raters = _raters(4, "small")
    with Session(engine) as s:
        for rid, st in zip(raters, [5, 4, 3, 2]):
            s.add(Rating(rater_id=rid, ratee_id=drv["id"], stars=st, created_at=utcnow()))
        s.commit()
    avg, cnt = _rating(drv["id"])
    assert cnt == 4
    assert avg == 3.5   # (5+4+3+2)/4


def test_window_drops_old_ratings(client, user_factory):
    """> окна: 10 старых «1» + 50 свежих «5» → среднее по окну = 5.0, число ПОЛНОЕ (60)."""
    drv = user_factory("RwWin")
    old = _raters(10, "wold")
    new = _raters(RATING_RECENT_WINDOW, "wnew")   # ровно окно свежих
    _rate(drv["id"], old, 1, utcnow() - timedelta(days=200))
    _rate(drv["id"], new, 5, utcnow() - timedelta(days=1))
    avg, cnt = _rating(drv["id"])
    assert cnt == 10 + RATING_RECENT_WINDOW        # полное число (не окно)
    assert avg == 5.0                              # окно = последние 50 = только свежие 5★


def test_user_rating_and_bundle_consistent(client, user_factory):
    """КЛЮЧЕВОЕ: профиль (user_rating) и карточка в ленте (drivers_bundle) НЕ расходятся при >окна."""
    drv = user_factory("RwCons")
    _rate(drv["id"], _raters(10, "cold"), 1, utcnow() - timedelta(days=200))
    _rate(drv["id"], _raters(RATING_RECENT_WINDOW, "cnew"), 5, utcnow() - timedelta(days=1))
    with Session(engine) as s:
        single = user_rating(s, drv["id"])
        _, _, agg, _ = drivers_bundle(s, {drv["id"]})
    assert single == agg[drv["id"]]                # один хелпер → одинаковый (avg, count)
    assert single == (5.0, 10 + RATING_RECENT_WINDOW)


def test_pair_cap_still_limits(client, user_factory):
    """Анти-накрутка цела: 5 оценок одной пары за <30 дней → в агрегат идут только первые 3."""
    drv = user_factory("RwCap")
    spammer = _raters(1, "cap")[0]
    base = utcnow() - timedelta(days=1)
    with Session(engine) as s:
        for i, st in enumerate([5, 5, 5, 1, 1]):   # первые 3 (5,5,5) учтутся, два «1» — нет
            s.add(Rating(rater_id=spammer, ratee_id=drv["id"], stars=st,
                         created_at=base + timedelta(minutes=i)))
        s.commit()
    avg, cnt = _rating(drv["id"])
    assert cnt == 3
    assert avg == 5.0


def test_ride_card_exposes_rating_count(client, user_factory):
    """Карточка поездки (RideOut) отдаёт driver_rating_count → клиент покажет «Новичок» (<5) / «N оценок»."""
    drv = user_factory("CardDrv", role=UserRole.driver)
    ride_id = client.post("/rides", headers=drv["auth"], json={
        "from_city": "A", "to_city": "B", "depart_at": "2030-05-01T09:00:00",
        "seats_total": 3, "price": 400,
    }).json()["id"]
    r1, r2 = _raters(2, "card")
    _rate(drv["id"], [r1], 5, utcnow())
    _rate(drv["id"], [r2], 4, utcnow())
    card = client.get(f"/rides/{ride_id}", headers=drv["auth"]).json()
    assert card["driver_rating_count"] == 2
    assert card["driver_rating"] == 4.5


def test_capped_entries_handles_null_date():
    """Легаси-строка без created_at — как самая старая (в начале), не роняет сортировку (2+ nulls)."""
    now = utcnow()
    rows = [(1, 3, None), (2, 4, None), (3, 5, now)]   # (rater, stars, created_at)
    entries = _capped_entries(rows)
    assert [s for _, s in entries] == [3, 4, 5]        # nulls первыми (старые), свежая последней
    assert entries[-1][0] == now                       # самая свежая — в хвосте (для окна)
