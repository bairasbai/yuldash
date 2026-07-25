"""«Щит рейтинга» (Справедливость, фаза 1): админ снимает спорную/накрученную оценку из среднего.

Проверяем: снятая оценка выпадает из среднего И из числа учтённых; возврат восстанавливает;
только админ; 404 на несуществующую. Профиль и карточка (user_rating / drivers_bundle) — один фильтр.
"""
from app.db import engine
from app.models import Rating, UserRole
from app.services import user_rating
from sqlmodel import Session, select


def _rate(ratee_id, rater_id, stars):
    with Session(engine) as s:
        s.add(Rating(rater_id=rater_id, ratee_id=ratee_id, stars=stars))
        s.commit()


def _rating_id(ratee_id, stars):
    with Session(engine) as s:
        return s.exec(select(Rating).where(Rating.ratee_id == ratee_id, Rating.stars == stars)).first().id


def test_excluded_rating_drops_from_average_and_count(client, user_factory):
    drv = user_factory("ExclDrv", role=UserRole.driver)
    r1 = user_factory("ExclR1"); r2 = user_factory("ExclR2")
    _rate(drv["id"], r1["id"], 5)
    _rate(drv["id"], r2["id"], 1)   # месть-единица
    with Session(engine) as s:
        avg, cnt = user_rating(s, drv["id"])
    assert cnt == 2 and round(avg, 1) == 3.0
    admin = user_factory("ExclAdmin", role=UserRole.admin)
    rid = _rating_id(drv["id"], 1)
    resp = client.post(f"/admin/ratings/{rid}/exclude", headers=admin["auth"], json={"excluded": True})
    assert resp.status_code == 200 and resp.json()["excluded"] is True
    with Session(engine) as s:
        avg2, cnt2 = user_rating(s, drv["id"])
    assert cnt2 == 1 and round(avg2, 1) == 5.0   # единица вне среднего И вне числа


def test_exclude_toggles_back(client, user_factory):
    drv = user_factory("TglDrv", role=UserRole.driver)
    r1 = user_factory("TglR1")
    _rate(drv["id"], r1["id"], 2)
    admin = user_factory("TglAdmin", role=UserRole.admin)
    rid = _rating_id(drv["id"], 2)
    client.post(f"/admin/ratings/{rid}/exclude", headers=admin["auth"], json={"excluded": True})
    with Session(engine) as s:
        assert user_rating(s, drv["id"])[1] == 0   # снята → 0 учтённых
    client.post(f"/admin/ratings/{rid}/exclude", headers=admin["auth"], json={"excluded": False})
    with Session(engine) as s:
        assert user_rating(s, drv["id"])[1] == 1   # вернули → снова учитывается


def test_exclude_admin_only(client, user_factory):
    drv = user_factory("AoDrv", role=UserRole.driver)
    r1 = user_factory("AoR1")
    _rate(drv["id"], r1["id"], 4)
    rid = _rating_id(drv["id"], 4)
    assert client.post(f"/admin/ratings/{rid}/exclude", headers=r1["auth"], json={"excluded": True}).status_code == 403


def test_exclude_missing_rating_404(client, user_factory):
    admin = user_factory("MissAdmin", role=UserRole.admin)
    assert client.post("/admin/ratings/999999/exclude", headers=admin["auth"], json={"excluded": True}).status_code == 404
