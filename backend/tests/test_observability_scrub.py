"""Sentry не должен унести наружу чужой телефон, координаты и токены.

`send_default_pii=False` закрывает только автоматическое приложение тел и куки. Если номер
попал ВНУТРЬ текста ошибки («Номер +7... занят») или в адрес запроса — он уедет в облако.
Здесь проверяем последний рубеж: `before_send`.
"""
from app.observability import before_send, scrub_text


def test_phone_scrubbed_in_any_form():
    for raw in ("+79171234567", "8 917 123 45 67", "8-917-123-45-67", "89171234567"):
        assert "<телефон>" in scrub_text(f"Номер {raw} занят"), raw
        assert raw not in scrub_text(f"Номер {raw} занят"), raw


def test_coordinates_scrubbed():
    out = scrub_text("GET /rides?lat=54.0512&lng=58.3187&seats=2")
    assert "54.0512" not in out and "58.3187" not in out
    assert "seats=2" in out           # безобидное не трогаем


def test_token_scrubbed():
    jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
    out = scrub_text(f"401 Unauthorized: {jwt}")
    assert jwt not in out and "<токен>" in out


def test_secrets_in_query_scrubbed():
    out = scrub_text("POST /auth/verify?code=482913&token=abc123def")
    assert "482913" not in out and "abc123def" not in out


def test_harmless_text_untouched():
    for s in ("Ride 42 not found", "цена 800 руб, 3 места", "подъезд 89", "seats=2"):
        assert scrub_text(s) == s, s


def test_before_send_walks_nested_event():
    event = {
        "message": "Номер 89171234567 занят",
        "request": {"url": "https://yulbash.ru/rides?lat=54.05&lng=58.31"},
        "exception": {"values": [{"value": "user +7 917 123 45 67 not found"}]},
        "breadcrumbs": [{"message": "GET /me?token=secret123"}],
        "extra": {"seats": 2, "ok": True, "none": None},
    }
    out = before_send(event, None)
    flat = repr(out)
    assert "89171234567" not in flat
    assert "54.05" not in flat and "58.31" not in flat
    assert "secret123" not in flat
    assert "917 123 45 67" not in flat
    assert out["extra"]["seats"] == 2 and out["extra"]["ok"] is True   # не-строки целы


def test_before_send_never_raises_and_drops_on_error():
    class Boom(dict):
        def items(self):
            raise RuntimeError("сломанное событие")
    # лучше потерять отчёт о сбое, чем отправить наружу непрочищенные данные
    assert before_send(Boom(), None) is None


def test_before_send_depth_limited():
    """Глубоко вложенное событие не должно ронять чистку."""
    deep = cur = {}
    for _ in range(40):
        cur["next"] = {}
        cur = cur["next"]
    cur["message"] = "тел 89171234567"
    assert before_send(deep, None) is not None
