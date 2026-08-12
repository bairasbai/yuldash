"""M2 — промокоды и кампании: позитив и негатив.

Проверяем оба опыта:
- Позитив: admin создаёт код (welcome и boost) → юзер applies welcome → ok, mine показывает;
  юзер applies boost → referral_credits вырос на perk_value; блогер-owner видит stats applied=N;
  active растёт после «живой» done-поездки приведённого юзера.
- Негатив: второй код тем же юзером → 409; истёкший → 422; неизвестный → 404; свой код → 409;
  исчерпанный общий лимит → 409; чужой /promo/{code}/stats → 404; дубль code у admin → 409.

Все 4xx-detail пользователю — двуязычный dict {ru, ba}: ассертим через str(r.json()["detail"]).
"""
from datetime import datetime, timedelta

from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus, UserRole


def _past():
    return (datetime.utcnow() - timedelta(days=1)).replace(microsecond=0).isoformat()


def _future():
    return (datetime.utcnow() + timedelta(days=30)).replace(microsecond=0).isoformat()


def _create_promo(client, admin, **overrides):
    body = {"code": "PROMO", "title": "Акция", "kind": "welcome", "perk_value": 0}
    body.update(overrides)
    r = client.post("/admin/promo", headers=admin["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _make_live_passenger(user_factory):
    """Юзер + одна «живая» done-поездка как пассажир (дистанция > 1 км)."""
    u = user_factory(name="Приведённый")
    with Session(engine) as s:
        s.add(InstantOrder(
            passenger_id=u["id"], status=InstantOrderStatus.done, distance_km=5.0,
            done_at=datetime.utcnow(),
        ))
        s.commit()
    return u


# ------------------------- ПОЗИТИВ -------------------------

def test_apply_welcome_and_mine(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    _create_promo(client, admin, code="welcome1", kind="welcome")

    user = user_factory(name="Юзер")
    r = client.post("/promo/apply", headers=user["auth"], json={"code": "WELCOME1"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ok"] is True
    assert body["kind"] == "welcome"
    assert body["message_ru"] and body["message_ba"]

    mine = client.get("/promo/mine", headers=user["auth"]).json()
    assert mine["promo"]["code"] == "WELCOME1"      # хранится в верхнем регистре
    assert mine["promo"]["kind"] == "welcome"
    assert mine["redeemed_at"]


def test_apply_boost_grants_credits(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    _create_promo(client, admin, code="boost5", kind="boost", perk_value=3)

    user = user_factory(name="Водитель")
    before = client.get("/referral/me", headers=user["auth"]).json()["credits"]
    r = client.post("/promo/apply", headers=user["auth"], json={"code": "boost5"})
    assert r.status_code == 200, r.text
    assert r.json()["kind"] == "boost"
    assert r.json()["perk_value"] == 3
    after = client.get("/referral/me", headers=user["auth"]).json()["credits"]
    assert after == before + 3


def test_owner_stats_applied_and_active(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    blogger = user_factory(name="Блогер")
    # код привязан к блогеру по телефону
    with Session(engine) as s:
        pass
    # узнаём телефон блогера
    from app.models import User
    with Session(engine) as s:
        phone = s.get(User, blogger["id"]).phone
    _create_promo(client, admin, code="blog1", kind="welcome", owner_phone=phone, campaign="insta")

    # двое применяют, один из них «живой»
    live_user = _make_live_passenger(user_factory)
    cold_user = user_factory(name="Просто скачал")
    assert client.post("/promo/apply", headers=live_user["auth"], json={"code": "blog1"}).status_code == 200
    assert client.post("/promo/apply", headers=cold_user["auth"], json={"code": "blog1"}).status_code == 200

    stats = client.get("/promo/blog1/stats", headers=blogger["auth"])
    assert stats.status_code == 200, stats.text
    js = stats.json()
    assert js["code"] == "BLOG1"
    assert js["campaign"] == "insta"
    assert js["applied"] == 2
    assert js["active"] == 1        # только «живой» пассажир засчитан


def test_admin_can_see_any_stats(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    _create_promo(client, admin, code="pub1", kind="welcome")   # owner=None (общая акция)
    user = user_factory(name="Юзер")
    client.post("/promo/apply", headers=user["auth"], json={"code": "pub1"})
    r = client.get("/promo/pub1/stats", headers=admin["auth"])
    assert r.status_code == 200
    assert r.json()["applied"] == 1


# ------------------------- НЕГАТИВ -------------------------

def test_second_code_rejected(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    _create_promo(client, admin, code="first1", kind="welcome")
    _create_promo(client, admin, code="second1", kind="welcome")
    user = user_factory(name="Юзер")
    assert client.post("/promo/apply", headers=user["auth"], json={"code": "first1"}).status_code == 200
    r = client.post("/promo/apply", headers=user["auth"], json={"code": "second1"})
    assert r.status_code == 409
    assert "уже активировал" in str(r.json()["detail"])


def test_expired_code(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    _create_promo(client, admin, code="old1", kind="welcome", valid_until=_past())
    user = user_factory(name="Юзер")
    r = client.post("/promo/apply", headers=user["auth"], json={"code": "old1"})
    assert r.status_code == 422
    assert "истёк" in str(r.json()["detail"]) or "ваҡыты" in str(r.json()["detail"])


def test_unknown_code(client, user_factory):
    user = user_factory(name="Юзер")
    r = client.post("/promo/apply", headers=user["auth"], json={"code": "NOPE404"})
    assert r.status_code == 404
    assert "не найден" in str(r.json()["detail"]) or "табылманы" in str(r.json()["detail"])


def test_own_code_rejected(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    owner = user_factory(name="Блогер-владелец")
    from app.models import User
    with Session(engine) as s:
        phone = s.get(User, owner["id"]).phone
    _create_promo(client, admin, code="mine1", kind="welcome", owner_phone=phone)
    r = client.post("/promo/apply", headers=owner["auth"], json={"code": "mine1"})
    assert r.status_code == 409
    assert "Свой код" in str(r.json()["detail"]) or "Үҙ кодыңды" in str(r.json()["detail"])


def test_total_limit_exhausted(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    _create_promo(client, admin, code="lim1", kind="welcome", limit_total=1)
    u1 = user_factory(name="Первый")
    u2 = user_factory(name="Второй")
    assert client.post("/promo/apply", headers=u1["auth"], json={"code": "lim1"}).status_code == 200
    r = client.post("/promo/apply", headers=u2["auth"], json={"code": "lim1"})
    assert r.status_code == 409
    assert "исчерпан" in str(r.json()["detail"]) or "бөттө" in str(r.json()["detail"])


def test_foreign_stats_hidden(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    _create_promo(client, admin, code="secret1", kind="welcome")   # owner=None
    stranger = user_factory(name="Чужой")
    r = client.get("/promo/secret1/stats", headers=stranger["auth"])
    assert r.status_code == 404
    assert "не найден" in str(r.json()["detail"]) or "табылманы" in str(r.json()["detail"])


def test_duplicate_code_create(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    _create_promo(client, admin, code="dup1", kind="welcome")
    r = client.post("/admin/promo", headers=admin["auth"], json={"code": "DUP1", "kind": "welcome"})
    assert r.status_code == 409
    assert "уже есть" in str(r.json()["detail"])


def test_status_toggle_disables_apply(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    created = _create_promo(client, admin, code="off1", kind="welcome")
    # выключаем кампанию
    rs = client.post(f"/admin/promo/{created['id']}/status", headers=admin["auth"], json={"active": False})
    assert rs.status_code == 200
    user = user_factory(name="Юзер")
    r = client.post("/promo/apply", headers=user["auth"], json={"code": "off1"})
    assert r.status_code == 404       # выключенный код не раскрываем


def test_ферма_на_удалении_аккаунта_видна_в_статистике(client, user_factory):
    """Аккаунт удалили и завели заново на тот же номер — скидку выдадут снова.

    Обещание «один промокод на всю жизнь аккаунта» держится буквально: у АККАУНТА. Аккаунт
    одноразовый, а номер телефона — нет, и бюджет кампании тает по-настоящему (скидку на такси
    оплачивает Юлдаш). Пробой волны 25: один номер получил скидку 5 раз подряд.

    Закрыть это без нового следа от удалённого человека нельзя (решение Александра — tasks.md),
    поэтому здесь мы держим хотя бы ПРИБОР: разрыв «выдано» и «применивших осталось» показывает
    ферму. Раньше статистика показывала ноль применивших и выглядела спокойной.
    """
    admin = user_factory(name="Админ статистики", role=UserRole.admin)
    r = client.post("/admin/promo", headers=admin["auth"], json={
        "code": "FARM100", "title": "Тест фермы", "kind": "welcome", "limit_total": 100,
    })
    assert r.status_code == 200, r.text

    for _ in range(3):
        u = user_factory(name="Одноразовый")
        assert client.post("/promo/apply", headers=u["auth"],
                           json={"code": "FARM100"}).status_code == 200
        assert client.post("/me/delete", headers=u["auth"]).status_code == 200

    st = client.get("/promo/FARM100/stats", headers=admin["auth"]).json()
    assert st["issued"] == 3, st          # бюджет кампании потрачен три раза
    assert st["applied"] == 0, st         # а спросить уже не с кого
    assert st["vanished"] == 3, st        # ровно это и есть след фермы
