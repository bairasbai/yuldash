"""F15: сезонные события — авто-расчёт дат (тесты не зависят от реальной даты сервера).

Проверяем: окно активности, авто-расчёт мусульманских дат (лунный календарь → григорианская),
N-й weekday (Сабантуй, дни городов), переход через год, публичный эндпоинт.
"""
from datetime import date

from app.routers.seasonal import _nth_weekday, active_events


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


def test_nth_weekday_computation():
    assert _nth_weekday(2026, 6, 5, 2) == date(2026, 6, 13)   # 2-я суббота июня 2026
    assert _nth_weekday(2027, 6, 5, 2) == date(2027, 6, 12)   # 2-я суббота июня 2027 (сдвинулась сама)
    assert _nth_weekday(2026, 8, 5, -1) == date(2026, 8, 29)  # последняя суббота августа
    assert _nth_weekday(2026, 7, 6, 3) == date(2026, 7, 19)   # 3-е воскресенье июля (День металлурга)


def test_uraza_auto_lands_in_march_2026():
    # Ураза-байрам 2026 — 20 марта (по муфтияту). Считается автоматически, без хардкода на год.
    ev = active_events(date(2026, 3, 18), window=21)
    u = next((e for e in ev if e["code"] == "uraza"), None)
    assert u is not None
    assert u["anchor"] == "2026-03-20"


def test_kurban_auto_lands_in_may_2026():
    # Курбан-байрам 2026 — 27 мая. Дата пересчитывается каждый год (лунный календарь).
    ev = active_events(date(2026, 5, 20), window=21)
    k = next((e for e in ev if e["code"] == "kurban"), None)
    assert k is not None
    assert k["anchor"] == "2026-05-27"


def test_uraza_moves_earlier_next_year():
    # Мусульманские даты уходят ~на 11 дней раньше ежегодно — проверяем, что расчёт это отражает.
    a26 = date.fromisoformat(next(e for e in active_events(date(2026, 3, 18), 21) if e["code"] == "uraza")["anchor"])
    a27 = date.fromisoformat(next(e for e in active_events(date(2027, 3, 8), 21) if e["code"] == "uraza")["anchor"])
    assert (a27.month, a27.day) < (a26.month, a26.day)   # 10 марта раньше в году, чем 20 марта


def test_rbday_republic_day_october():
    # День Республики Башкортостан — 11 октября.
    ev = active_events(date(2026, 10, 5), window=21)
    r = next((e for e in ev if e["code"] == "rbday"), None)
    assert r is not None and r["anchor"] == "2026-10-11" and r["category"] == "rb"


def test_ufa_city_day_present_in_june():
    ev = active_events(date(2026, 6, 8), window=21)
    u = next((e for e in ev if e["code"] == "ufa_day"), None)
    assert u is not None and u["category"] == "city"


def test_endpoint_public_ok(client):
    r = client.get("/seasonal-events")
    assert r.status_code == 200 and isinstance(r.json().get("items"), list)


def test_endpoint_window_param(client):
    r = client.get("/seasonal-events?days=60")
    assert r.status_code == 200
    for e in r.json()["items"]:              # контракт ответа для клиента-баннера
        assert {"code", "name_ru", "name_ba", "emoji", "anchor",
                "starts_at", "ends_at", "active", "category"} <= set(e.keys())
