"""Тесты «Ранний доступ / лист ожидания» (волна 2, §11 «Запуск», батч B6).

Покрываем:
- публичная подача БЕЗ токена (лендинг + приложение до входа);
- валидация телефона (regex как в family.py) и нормализация (пробелы/дефисы/скобки);
- дедуп: повторная подача обновляет city/role, НЕ дублирует; город без значения не затирает;
- строгий rate-limit на /waitlist: нормальная подача проходит, спам отсекается;
- админ: список + счётчики (по городам/ролям), фильтры city/role/invited, CSV, invite;
- invite не перетирает уже позванных (номер волны по времени сохраняется);
- IDOR/приватность: не-админ (и аноним) не видит телефоны — 403/401 на админ-ручках.
"""
import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import UserRole, WaitlistEntry


@pytest.fixture(autouse=True)
def _clean_waitlist():
    """БД сессионная и общая — чистим лист ожидания после каждого теста."""
    yield
    with Session(engine) as s:
        for e in s.exec(select(WaitlistEntry)).all():
            s.delete(e)
        s.commit()


def _rows():
    with Session(engine) as s:
        return s.exec(select(WaitlistEntry)).all()


# ------------------------------ публичная подача ------------------------------
def test_join_public_without_token(client):
    r = client.post("/waitlist", json={"phone": "+79170000001", "city": "Сибай", "role": "passenger"})
    assert r.status_code == 200 and r.json() == {"ok": True}
    rows = _rows()
    assert len(rows) == 1
    assert rows[0].phone == "+79170000001" and rows[0].city == "Сибай" and rows[0].role == "passenger"
    assert rows[0].invited_at is None


def test_join_invalid_phone(client):
    for bad in ("12345", "abc", "+7-917-abc-00-00", ""):
        r = client.post("/waitlist", json={"phone": bad})
        assert r.status_code in (400, 422), bad
    assert _rows() == []


def test_join_invalid_role(client):
    r = client.post("/waitlist", json={"phone": "+79170000002", "role": "hacker"})
    assert r.status_code == 422
    assert _rows() == []


def test_join_normalizes_and_dedups(client):
    """«+7 (917) 000-00-03» и «+79170000003» — один номер: повтор обновляет city/role."""
    r1 = client.post("/waitlist", json={"phone": "+7 (917) 000-00-03", "city": "Баймак", "role": "passenger"})
    assert r1.status_code == 200
    r2 = client.post("/waitlist", json={"phone": "+79170000003", "city": "Сибай", "role": "driver"})
    assert r2.status_code == 200
    rows = _rows()
    assert len(rows) == 1                      # не задублировалось
    assert rows[0].phone == "+79170000003"     # нормализованный номер
    assert rows[0].city == "Сибай" and rows[0].role == "driver"   # обновилось


def test_dedup_keeps_city_when_omitted(client):
    """Повтор без города не затирает известный город (лендинг мог не спросить)."""
    client.post("/waitlist", json={"phone": "+79170000004", "city": "Учалы"})
    client.post("/waitlist", json={"phone": "+79170000004", "role": "driver"})
    rows = _rows()
    assert len(rows) == 1
    assert rows[0].city == "Учалы" and rows[0].role == "driver"


def test_rate_limit_allows_normal_blocks_spam(client):
    """Строгий лимит на /waitlist: первая подача проходит, спам номеров отсекается.
    Лимитер для тестов выключен глобально (conftest) — включаем локально.
    Свой IP через X-Real-IP, чтобы не пересекаться со счётчиками других тестов."""
    from app.config import settings
    saved_en, saved_lim = settings.rate_limit_enabled, settings.rate_limit_auth_per_min
    settings.rate_limit_enabled = True
    settings.rate_limit_auth_per_min = 3
    try:
        codes = [
            client.post("/waitlist", json={"phone": f"+7917000010{i}"},
                        headers={"x-real-ip": "10.77.0.1"}).status_code
            for i in range(6)
        ]
        assert codes[0] == 200      # нормальная подача проходит
        assert 429 in codes, codes  # спам отсекается
    finally:
        settings.rate_limit_enabled = saved_en
        settings.rate_limit_auth_per_min = saved_lim


# ------------------------------ админ ------------------------------
def _seed(client):
    client.post("/waitlist", json={"phone": "+79170000021", "city": "Сибай", "role": "passenger"})
    client.post("/waitlist", json={"phone": "+79170000022", "city": "Сибай", "role": "driver"})
    client.post("/waitlist", json={"phone": "+79170000023", "city": "Уфа", "role": "passenger"})
    client.post("/waitlist", json={"phone": "+79170000024"})   # без города


def test_admin_list_counters_and_filters(client, user_factory):
    admin = user_factory("Admin", role=UserRole.admin)
    _seed(client)
    body = client.get("/admin/waitlist", headers=admin["auth"]).json()
    assert body["total"] == 4 and body["invited"] == 0
    assert body["by_role"] == {"passenger": 3, "driver": 1}
    by_city = {c["city"]: c["count"] for c in body["by_city"]}
    assert by_city == {"Сибай": 2, "Уфа": 1, "—": 1}
    assert len(body["items"]) == 4
    # фильтры: город / роль / invited
    items = client.get("/admin/waitlist?city=Сибай", headers=admin["auth"]).json()["items"]
    assert {i["phone"] for i in items} == {"+79170000021", "+79170000022"}
    items = client.get("/admin/waitlist?role=driver", headers=admin["auth"]).json()["items"]
    assert [i["phone"] for i in items] == ["+79170000022"]
    items = client.get("/admin/waitlist?invited=true", headers=admin["auth"]).json()["items"]
    assert items == []
    items = client.get("/admin/waitlist?invited=false", headers=admin["auth"]).json()["items"]
    assert len(items) == 4


def test_admin_csv_export(client, user_factory):
    admin = user_factory("Admin", role=UserRole.admin)
    _seed(client)
    r = client.get("/admin/waitlist.csv", headers=admin["auth"])
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/csv")
    assert "attachment" in r.headers.get("content-disposition", "")
    lines = r.text.strip().splitlines()
    assert lines[0].startswith("id,phone,city,role,created_at,invited_at")
    assert len(lines) == 5                      # шапка + 4 записи
    assert any("+79170000021" in ln and "Сибай" in ln for ln in lines[1:])
    # фильтр в CSV работает так же, как в списке
    r2 = client.get("/admin/waitlist.csv?role=driver", headers=admin["auth"])
    assert len(r2.text.strip().splitlines()) == 2


def test_admin_invite_marks_wave(client, user_factory):
    admin = user_factory("Admin", role=UserRole.admin)
    _seed(client)
    ids = [i["id"] for i in client.get("/admin/waitlist", headers=admin["auth"]).json()["items"]][:2]
    r = client.post("/admin/waitlist/invite", json={"ids": ids}, headers=admin["auth"])
    assert r.status_code == 200 and r.json()["invited"] == 2
    invited = client.get("/admin/waitlist?invited=true", headers=admin["auth"]).json()["items"]
    assert {i["id"] for i in invited} == set(ids)
    first_stamp = invited[0]["invited_at"]
    assert first_stamp
    # повторный invite не перетирает метку волны и не считает заново
    r2 = client.post("/admin/waitlist/invite", json={"ids": ids}, headers=admin["auth"])
    assert r2.json()["invited"] == 0
    again = client.get("/admin/waitlist?invited=true", headers=admin["auth"]).json()["items"]
    assert again[0]["invited_at"] == first_stamp
    # несуществующий id — не падаем
    r3 = client.post("/admin/waitlist/invite", json={"ids": [999999]}, headers=admin["auth"])
    assert r3.status_code == 200 and r3.json()["invited"] == 0


def test_admin_endpoints_forbidden_for_non_admin(client, user_factory):
    """IDOR/приватность: телефоны из листа видит только админ."""
    joe = user_factory("Joe")   # обычный пассажир
    _seed(client)
    assert client.get("/admin/waitlist", headers=joe["auth"]).status_code == 403
    assert client.get("/admin/waitlist.csv", headers=joe["auth"]).status_code == 403
    assert client.post("/admin/waitlist/invite", json={"ids": [1]}, headers=joe["auth"]).status_code == 403
    # аноним — тоже мимо (401/403 от bearer)
    assert client.get("/admin/waitlist").status_code in (401, 403)
    assert client.get("/admin/waitlist.csv").status_code in (401, 403)
    assert client.post("/admin/waitlist/invite", json={"ids": [1]}).status_code in (401, 403)


def test_availability_returns_city_for_prefill(client, user_factory):
    """Аддитивное поле city в /instant/availability — для предзаполнения формы листа."""
    u = user_factory("CityGuy")
    body = client.get("/instant/availability?lat=52.716&lng=58.664", headers=u["auth"]).json()
    assert "city" in body
    assert body["city"] == "Сибай"
