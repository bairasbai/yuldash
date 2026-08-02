"""B1 (аудит #73): verified больше не выдаётся при регистрации → эскалация роли закрыта.

Непроверенный пользователь (как теперь даёт регистрация) — НЕ L2 «Проверен», не может звать
в круг «своих». L2 достаётся только реально промодерированному водителю (docs_status=verified).
"""
from sqlmodel import Session

from app.db import engine
from app.models import DriverProfile, User
from app.security import make_token


def _auth(uid: int) -> dict:
    return {"Authorization": f"Bearer {make_token(uid)}"}


def test_b1_unverified_not_L2_cannot_invite(client):
    with Session(engine) as s:
        # Непроверенный: имя+фото есть (L1), но модерации нет → НЕ L2.
        plain = User(phone="b1-plain", name="Иван", avatar_url="a.jpg", telegram_id="b1p", verified=False)
        s.add(plain)
        # Промодерированный водитель: verified=True + docs_status='verified' → L2.
        mod = User(phone="b1-mod", name="Пётр", avatar_url="b.jpg", telegram_id="b1m", verified=True)
        s.add(mod)
        s.commit()
        s.refresh(plain)
        s.refresh(mod)
        s.add(DriverProfile(user_id=mod.id, docs_status="verified"))
        s.commit()
        plain_id, mod_id = plain.id, mod.id

    pt = client.get("/me/trust", headers=_auth(plain_id)).json()
    assert pt["level"] < 2 and pt["can_invite"] is False, "непроверенный не должен быть L2 / звать в круг"
    assert client.post("/invites", headers=_auth(plain_id)).status_code == 403

    mt = client.get("/me/trust", headers=_auth(mod_id)).json()
    assert mt["level"] >= 2 and mt["can_invite"] is True, "промодерированный водитель — L2, может звать"
    assert client.post("/invites", headers=_auth(mod_id)).status_code == 200
