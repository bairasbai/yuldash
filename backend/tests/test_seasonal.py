"""F15: сезонные события — чистая логика окна (тесты не зависят от реальной даты сервера)."""
from datetime import date

from app.routers.seasonal import active_events


def test_sabantuy_active_in_june():
    ev = active_events(date(2026, 6, 15), window=21)
    s = next((e for e in ev if e["code"] == "sabantuy"), None)
    assert s is not None and s["active"] is True


def test_sep1_upcoming_late_august():
    ev = active_events(date(2026, 8, 25), window=21)     # окно до ~15 сен → «1 сентября» попадает
    s = next((e for e in ev if e["code"] == "sep1"), None)
    assert s is not None and s["active"] is False        # 25 авг — ещё не началось (28 авг–3 сен)


def test_newyear_crosses_year_boundary():
    ev = active_events(date(2026, 12, 30), window=10)     # окно до 9 янв 2027 → НГ (28.12–03.01) попадает
    ny = next((e for e in ev if e["code"] == "newyear"), None)
    assert ny is not None and ny["active"] is True        # 30 дек — праздник уже идёт (через год)


def test_nothing_when_far_from_events():
    # конец июля: ближайшее («1 сентября») дальше окна, лето уже прошло
    ev = active_events(date(2026, 7, 28), window=21)
    assert all(e["code"] not in ("sabantuy", "sep1", "newyear") for e in ev)


def test_endpoint_public_ok(client):
    r = client.get("/seasonal-events")
    assert r.status_code == 200 and isinstance(r.json(), list)
