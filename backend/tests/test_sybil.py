"""Детект накрутки доверия сговором (Sybil) — админ-сигнал /admin/sybil/suspects (Справедливость, фаза 2).

Проверяем: пара со взаимным рефералом попадает в подозрительные; форма ответа; только админ.
Детектор — только СИГНАЛ (авто-наказаний нет), накрученное админ снимает Rating.excluded (фаза 1).
"""
from app.db import engine
from app.models import User, UserRole
from sqlmodel import Session


def _set_referral(uid, referred_by_id):
    with Session(engine) as s:
        u = s.get(User, uid)
        u.referred_by = referred_by_id
        s.add(u)
        s.commit()


def test_reciprocal_referral_flagged(client, user_factory):
    """A ввёл код B и B ввёл код A (у честных так не бывает) → пара в подозрительных с сигналом."""
    a = user_factory("SybRefA"); b = user_factory("SybRefB")
    _set_referral(a["id"], b["id"])
    _set_referral(b["id"], a["id"])
    admin = user_factory("SybRefAdmin", role=UserRole.admin)
    resp = client.get("/admin/sybil/suspects", headers=admin["auth"])
    assert resp.status_code == 200
    data = resp.json()
    assert "count" in data and "suspects" in data and "scan_cap" in data
    mine = next((x for x in data["suspects"] if sorted(x["pair"]) == sorted([a["id"], b["id"]])), None)
    assert mine is not None, "взаимный реферал не отмечен как подозрительный"
    assert "reciprocal_invite" in mine["signals"] and mine["score"] >= 1


def test_sybil_requires_admin(client, user_factory):
    assert client.get("/admin/sybil/suspects", headers=user_factory("SybU")["auth"]).status_code == 403
