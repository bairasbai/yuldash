"""Сохранённые и недавние адреса (places.py): CRUD, upsert home/work, лимиты, дедуп,
анти-IDOR (чужое не видно/не удалить), стирание при удалении аккаунта (152-ФЗ)."""
from sqlmodel import Session, select

from app.db import engine
from app.models import RecentPlace, SavedPlace, User


# ------------------------------ сохранённые ------------------------------
def test_saved_crud_and_home_upsert(client, user_factory):
    u = user_factory()
    # пусто в начале
    r = client.get("/places/saved", headers=u["auth"])
    assert r.status_code == 200 and r.json() == []

    # создать home
    r = client.post("/places/saved", headers=u["auth"],
                    json={"kind": "home", "label": "Дом", "address": "Уфа, ул. Ленина 1",
                          "lat": 54.7, "lng": 55.9})
    assert r.status_code == 200
    home_id = r.json()["id"]
    assert r.json()["kind"] == "home"

    # повторный POST home → upsert (та же строка, новый адрес), не второй home
    r = client.post("/places/saved", headers=u["auth"],
                    json={"kind": "home", "label": "Дом", "address": "Уфа, новый адрес"})
    assert r.status_code == 200 and r.json()["id"] == home_id
    assert r.json()["address"] == "Уфа, новый адрес"

    # custom-место
    r = client.post("/places/saved", headers=u["auth"],
                    json={"kind": "custom", "label": "Мама", "address": "Стерлитамак"})
    assert r.status_code == 200
    custom_id = r.json()["id"]

    rows = client.get("/places/saved", headers=u["auth"]).json()
    assert {p["kind"] for p in rows} == {"home", "custom"}
    assert len(rows) == 2

    # удалить своё custom
    r = client.delete(f"/places/saved/{custom_id}", headers=u["auth"])
    assert r.status_code == 200
    assert len(client.get("/places/saved", headers=u["auth"]).json()) == 1


def test_saved_requires_address_or_coords(client, user_factory):
    u = user_factory()
    r = client.post("/places/saved", headers=u["auth"], json={"kind": "custom", "label": "X"})
    assert r.status_code == 400


def test_saved_idor_delete(client, user_factory):
    owner = user_factory()
    other = user_factory()
    pid = client.post("/places/saved", headers=owner["auth"],
                      json={"kind": "custom", "address": "секретный адрес"}).json()["id"]
    # чужой не видит в своём списке
    assert client.get("/places/saved", headers=other["auth"]).json() == []
    # чужой не может удалить → 404 (не раскрываем существование)
    assert client.delete(f"/places/saved/{pid}", headers=other["auth"]).status_code == 404
    # у владельца место на месте
    assert len(client.get("/places/saved", headers=owner["auth"]).json()) == 1


# ------------------------------ недавние ------------------------------
def test_recent_dedup_and_cap(client, user_factory):
    u = user_factory()
    # один и тот же адрес дважды → одна строка, used_at обновлён
    client.post("/places/recent", headers=u["auth"], json={"address": "Точка А", "lat": 54.0, "lng": 56.0})
    client.post("/places/recent", headers=u["auth"], json={"address": "Точка А", "lat": 54.1, "lng": 56.1})
    rows = client.get("/places/recent", headers=u["auth"]).json()
    a_rows = [p for p in rows if p["address"] == "Точка А"]
    assert len(a_rows) == 1
    assert a_rows[0]["lat"] == 54.1   # координаты обновились

    # добавляем много разных → держим не больше 10, свежие сверху
    for i in range(15):
        client.post("/places/recent", headers=u["auth"], json={"address": f"Адрес {i}"})
    rows = client.get("/places/recent", headers=u["auth"]).json()
    assert len(rows) == 10
    assert rows[0]["address"] == "Адрес 14"   # самый свежий сверху


def test_recent_idor(client, user_factory):
    owner = user_factory()
    other = user_factory()
    client.post("/places/recent", headers=owner["auth"], json={"address": "мой недавний"})
    assert client.get("/places/recent", headers=other["auth"]).json() == []


# ------------------------------ 152-ФЗ: удаление аккаунта ------------------------------
def test_places_wiped_on_account_delete(client, user_factory):
    u = user_factory()
    client.post("/places/saved", headers=u["auth"], json={"kind": "home", "address": "адрес"})
    client.post("/places/recent", headers=u["auth"], json={"address": "недавний"})
    uid = u["id"]
    with Session(engine) as s:
        assert s.exec(select(SavedPlace).where(SavedPlace.user_id == uid)).first() is not None
        assert s.exec(select(RecentPlace).where(RecentPlace.user_id == uid)).first() is not None

    from app.account import delete_user_account
    with Session(engine) as s:
        user = s.get(User, uid)
        delete_user_account(s, user)
    with Session(engine) as s:
        assert s.exec(select(SavedPlace).where(SavedPlace.user_id == uid)).all() == []
        assert s.exec(select(RecentPlace).where(RecentPlace.user_id == uid)).all() == []
