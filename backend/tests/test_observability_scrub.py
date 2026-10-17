"""Sentry не должен унести наружу чужой телефон, координаты и токены.

`send_default_pii=False` закрывает куки, IP и заголовок Authorization — но НЕ тело запроса:
FastAPI-интеграция Sentry прикладывает JSON-тело к событию сама (проверено на sentry_sdk 2.72,
задача #42). Поэтому тела выключены отдельно (`max_request_body_size="never"`), а `before_send`
их выбрасывает. И если номер попал ВНУТРЬ текста ошибки («Номер +7... занят») или в адрес
запроса — он уедет в облако. Здесь проверяем последний рубеж: `before_send` и мойку `scrub_text`.
"""
import json
import os
import random
import subprocess
import sys
import uuid
from pathlib import Path

import pytest

from app.observability import before_send, scrub_text

BACKEND = Path(__file__).resolve().parents[1]


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


def test_mobile_without_country_code_scrubbed():
    """«Звони 917 123-45-67» — так номер пишут в свободном поле чаще всего, без +7/8.
    Раньше мойка его пропускала: в ленте заявок он уходил целиком (приёмка PR168, 17.10)."""
    for raw in ("9171234567", "917 123-45-67", "917-123-45-67", "(917) 123 45 67", "917.123.45.67"):
        out = scrub_text(f"за маму, звони {raw}")
        assert "<телефон>" in out and "123" not in out, (raw, out)


def test_bare_mobile_rule_spares_numbers_that_are_not_phones():
    # девять или одиннадцать цифр, другая первая цифра, кусок токена — не номер
    for s in ("заказ 917123456", "счёт 91712345678", "цена 900, 3 места", "рейс 8123456789x",
              "id=a9171234567b", "год 1999, подъезд 9"):
        assert "<телефон>" not in scrub_text(s), s


def test_tokens_and_coordinates_win_over_phone_rules():
    """Телефонный шаблон не должен откусывать кусок координаты или токена раньше их правил:
    «52.9123456789, 58.6612345» раньше становилось «52.<телефон>, 58.6612345» — долгота
    оставалась открытой (приёмка PR169, 17.10)."""
    out = scrub_text("точка 52.9123456789, 58.6612345 у дома")
    assert out == "точка <коорд> у дома", out
    jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI5MTcxMjM0NTY3In0.ab-9171234567-cdefgh"
    out = scrub_text(f"Bearer {jwt}")
    assert jwt not in out and "<токен>" in out and "9171234567" not in out, out
    assert scrub_text("/t/abcd-9171234567-efghijkl") == "/t/***"


# ------------------- номер с любым разделителем (задача #31, 17.10.2026) -------------------
# Мойка знала только пробел, дефис, точку и скобки. А номер из контактов телефона приходит
# с неразрывными пробелами, автозамена «Заметок» превращает дефис в тире, кто-то пишет через
# слэш или запятую. Комментарий заявки — свободный текст, и новые клиенты шлют его как есть:
# «звоните 8–917–765–43–21» уходил в ленту и на карту целиком.
РАЗДЕЛИТЕЛИ = {
    "неразрывный пробел U+00A0": "\u00a0",
    "узкий неразрывный U+202F": "\u202f",
    "тонкий пробел U+2009": "\u2009",
    "короткое тире": "\u2013",
    "длинное тире": "\u2014",
    "неразрывный дефис U+2011": "\u2011",
    "минус U+2212": "\u2212",
    "широкий дефис U+FF0D": "\uff0d",
    "слэш": "/",
    "запятая": ",",
    "подчёркивание": "_",
    "перевод строки": "\n",
    "табуляция": "\t",
    "тире в пробелах": " \u2013 ",
    "тире в неразрывных": "\u00a0\u2014\u00a0",
    "невидимый пробел U+200B": "\u200b",
    "мягкий перенос U+00AD": "\u00ad",
}
ФОРМЫ_НОМЕРА = {
    "с 8": ("8", "917", "765", "43", "21"),
    "с +7": ("+7", "917", "765", "43", "21"),
    "без кода": ("917", "765", "43", "21"),
}
ТЕКСТЫ = {
    "по-русски": "за маму, звоните {} вечером",
    "по-башкирски": "Миңә шылтырат: {}, рәхмәт",
}


@pytest.mark.parametrize("разделитель", list(РАЗДЕЛИТЕЛИ.values()), ids=list(РАЗДЕЛИТЕЛИ))
def test_номер_с_любым_разделителем_замазан(разделитель):
    for форма, части in ФОРМЫ_НОМЕРА.items():
        номер = разделитель.join(части)
        for язык, шаблон in ТЕКСТЫ.items():
            out = scrub_text(шаблон.format(номер))
            assert out == шаблон.format("<телефон>"), (форма, язык, out)


@pytest.mark.parametrize("номер", [
    "347 264-18-90",            # Уфа: код 347 и семь цифр
    "(347) 264 18 90",
    "(34786) 2-64-18",          # район: код 34786 и пять цифр
    "(3473) 24-56-78",          # Стерлитамак: код 3473 и шесть цифр
    "347\u00a0226\u201118\u201190",
    "8 (347) 264-18-90",        # с восьмёркой — ловилось и раньше, держим
])
def test_городской_башкортостана_без_восьмёрки_замазан(номер):
    """Городской номер без 8 впереди: «347 264-18-90», «(34786) 2-64-18». Все коды республики
    начинаются с 347, поэтому правило узкое: «347» и ровно десять цифр всего."""
    for шаблон in ("звоните в контору {} до обеда", "Өйгә шылтырат: {}, рәхмәт"):
        out = scrub_text(шаблон.format(номер))
        assert out == шаблон.format("<телефон>"), out


def test_буква_по_прежнему_рвёт_цепочку():
    """Шире стали только разделители. Буква между цифрами — это уже не номер."""
    for s in ("8 917 abc 765 43 21", "8 917 и 765 43 21", "8 917 һәм 765 43 21", "917 мест 765 43 21"):
        assert scrub_text(s) == s, s


# Что мойка НЕ должна трогать: она работает на всём — логах, трейсбеках, Sentry, аналитике.
# Каждая строка здесь — то, что реально лежит в наших логах и событиях. Запятая, слэш и
# подчёркивание считаются разделителем только поодиночке и вплотную к цифрам: «, » с пробелом —
# это перечисление («800, 1 200, 1 500», параметры SQL), через него номер не собирается.
ЛОЖНЫЕ_СРАБАТЫВАНИЯ = [
    # время и даты
    "2026-10-17 15:07:18",
    "15:07:18.123",
    "2026-10-17T15:07:18.123456+05:00",
    "2026-10-17 08:15:00,123 INFO [REQ] GET /rides -> 200 12ms",
    "17.10.2026 8:15",
    "1760702838", "1760702838123",
    # деньги
    "1 500 руб", "1\u00a0500\u00a0₽", "15000", "цена 8 500 ₽, 9 000 ₽", "800 – 900 руб",
    "цены: 800, 1 200, 1 500",
    # номера броней и заявок
    "бронь #12345", "заявка #91712", "booking_id=812345", "request 9171 not found",
    # IP-адреса — в том числе те, что старая мойка принимала за номер (11 цифр с 8, 10 с 9)
    "85.239.52.55", "10.0.0.1", "85.239.152.155", "92.168.200.10", "77.88.8.8",
    "X-Forwarded-For: 85.239.152.155, 10.0.0.1",
    "upstream 92.168.200.10:8000 timed out",
    # версии и трейсбеки
    "sentry-sdk 1.11.3", "Python 3.12.3", "AGP 8.10.2",
    '  File "/srv/yuldash/backend/app/routers/requests.py", line 582, in requests_feed',
    "tests/test_x.py:123: AssertionError", "test_x.py:123",
    "[parameters: (8, 900, 1000, 15, 2)]",
    "ids=[9, 1, 2, 3, 4, 5, 6, 7, 8, 9]",
    # идентификаторы
    "550e8400-e29b-41d4-a716-446655440000",
    "request_id=3f2b8c1e-9a4d-4e7b-8c2f-1a2b3c4d5e6f",
    "trace_id=4c7e81234567890fab12cd34ef567890",   # 32 hex: внутри «81234567890», это не номер
]


@pytest.mark.parametrize("s", ЛОЖНЫЕ_СРАБАТЫВАНИЯ)
def test_мойка_не_портит_служебный_текст(s):
    assert scrub_text(s) == s


def test_координаты_и_токены_по_прежнему_раньше_телефона():
    """Порядок правил держим: координаты и токены — до телефона (приёмка PR169)."""
    out = scrub_text("точка 52.9123456789,\u00a058.6612345 звони 8–917–765–43–21")
    assert out == "точка <коорд> звони <телефон>", out
    assert scrub_text("GET /rides?lat=54.0512&lng=58.3187") == "GET /rides?lat=<коорд>&lng=<коорд>"
    refresh = "9171234567" + "ab" * 27
    assert scrub_text(f"refresh {refresh}") == "refresh <токен>"
    jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI5MTcxMjM0NTY3In0.ab_917_765_43_21-cdefgh"
    assert scrub_text(f"Bearer {jwt}") == "Bearer <токен>"


def test_uuid_и_hex_идентификаторы_никогда_не_становятся_телефоном():
    """UUID и hex-идентификаторы (event_id, trace_id Sentry, client_id аналитики) — не номер.
    Старая мойка портила примерно каждый 120-й случайный UUID: «…-8123-4567-891a-…» —
    восьмёрка и десять цифр через дефис. Испорченный event_id Sentry отбрасывает событие."""
    rnd = random.Random(31)
    for _ in range(3000):
        u = str(uuid.UUID(int=rnd.getrandbits(128), version=4))
        h = "%032x" % rnd.getrandbits(128)
        assert "<телефон>" not in scrub_text(u), u
        assert "<телефон>" not in scrub_text(f"trace {h}"), h


# ---------------- тело запроса не уходит в Sentry (задача #42, 17.10.2026) ----------------
def test_before_send_выбрасывает_тело_и_куки_запроса():
    """Тело запроса — это имя, код из СМС, координаты, комментарий. Разбору сбоя оно не нужно."""
    event = {
        "request": {
            "url": "https://yulbash.ru/requests?lat=54.05&lng=58.31", "method": "POST",
            "data": {"name": "Айгуль Хабибуллина", "code": "582731", "lat": 52.9321},
            "cookies": {"yl_session": "cookie-secret-value"},
            "headers": {"user-agent": "okhttp/4.12", "cookie": "[Filtered]"},
        },
        "exception": {"values": [{"value": "звоните 8–917–765–43–21"}]},
    }
    out = before_send(event, None)
    assert "data" not in out["request"] and "cookies" not in out["request"], out["request"]
    assert out["request"]["method"] == "POST"
    assert out["request"]["headers"]["user-agent"] == "okhttp/4.12"
    assert out["request"]["url"] == "https://yulbash.ru/requests?lat=<коорд>&lng=<коорд>"
    assert out["exception"]["values"][0]["value"] == "звоните <телефон>"


def test_init_sentry_не_просит_тела_и_локальные_переменные(monkeypatch):
    """`send_default_pii=False` тела НЕ выключает — нужен отдельный запрет. Локальные переменные
    обработчика — та же беда: там лежит разобранное тело (`body=RequestIn(name=…, comment=…)`)."""
    import sentry_sdk

    from app import observability
    from app.config import settings

    seen: dict = {}
    monkeypatch.setattr(sentry_sdk, "init", lambda **kw: seen.update(kw))
    monkeypatch.setattr(settings, "sentry_dsn", "https://public@sentry.invalid/1", raising=False)
    monkeypatch.setattr(observability, "_sentry_ready", False, raising=False)

    assert observability.init_sentry() is True
    assert seen["send_default_pii"] is False
    assert seen["max_request_body_size"] == "never"
    assert seen["include_local_variables"] is False
    assert seen["before_send"] is observability.before_send
    assert seen["before_send_transaction"] is observability.before_send


# Настоящий путь SDK, без подмен: наш init_sentry(), FastAPI, наш обработчик 500, падение
# в ручке — и смотрим, что Sentry собрал бы для отправки. Отдельным процессом: sentry_sdk.init
# глобально патчит Starlette/логирование, остальные тесты это задевать не должно.
_SDK_PROBE = r'''
import json, os, sys
import sentry_sdk
from sentry_sdk.transport import Transport

events = []

class Box(Transport):
    def capture_envelope(self, envelope):
        for item in envelope.items:
            ev = item.get_event() or item.get_transaction_event()
            if ev is not None:
                events.append(ev)

_real_init = sentry_sdk.init
def _init(*a, **kw):
    kw["transport"] = Box()          # вместо отправки в облако — в коробку
    return _real_init(*a, **kw)
sentry_sdk.init = _init

from app.observability import init_sentry
assert init_sentry() is True         # до создания приложения — как create_app() в main.py

from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel
from app.middleware import unhandled_exception_handler

app = FastAPI()
app.add_exception_handler(Exception, unhandled_exception_handler)

class RequestIn(BaseModel):
    name: str
    phone: str
    code: str
    comment: str
    lat: float

@app.post("/requests")
def create(body: RequestIn):
    raise RuntimeError("сбой в обработчике")

r = TestClient(app, raise_server_exceptions=False).post(
    "/requests",
    json={"name": "Айгуль Хабибуллина", "phone": "+79171234567", "code": "582731",
          "comment": "жду у дома, ул. Ленина 12", "lat": 52.9321},
    cookies={"yl_session": "cookie-secret-value"},
    headers={"Authorization": "Bearer sekret-bearer-value"},
)
assert r.status_code == 500, r.status_code
sentry_sdk.flush()
print("EVENTS_JSON:" + json.dumps(events, ensure_ascii=False, default=str))
'''


def _события_пробы(скрипт: str, tmp_path, **env_extra) -> list:
    """Запустить пробу настоящего SDK отдельным процессом и вернуть собранные события."""
    env = dict(os.environ)
    env.update({
        "ENV": "dev",
        "DATABASE_URL": f"sqlite:///{(tmp_path / 'probe.db').as_posix()}",
        "SENTRY_DSN": "https://public@sentry.invalid/1",
        "PYTHONIOENCODING": "utf-8",
        **env_extra,
    })
    proc = subprocess.run([sys.executable, "-c", скрипт], cwd=BACKEND, env=env,
                          capture_output=True, text=True, encoding="utf-8", timeout=180)
    lines = [ln for ln in proc.stdout.splitlines() if ln.startswith("EVENTS_JSON:")]
    assert proc.returncode == 0 and lines, proc.stdout[-2000:] + proc.stderr[-3000:]
    return json.loads(lines[-1][len("EVENTS_JSON:"):])


def test_настоящий_sentry_не_уносит_тело_запроса(tmp_path):
    events = _события_пробы(_SDK_PROBE, tmp_path)

    errors = [e for e in events if "сбой в обработчике" in json.dumps(e, ensure_ascii=False)]
    assert errors, f"SDK не собрал событие о сбое — проверять нечего: {events!r}"[:2000]
    for e in events:
        req = e.get("request") or {}
        assert "data" not in req, f"тело запроса ушло бы в Sentry: {req.get('data')!r}"
        assert "cookies" not in req, f"куки ушли бы в Sentry: {req.get('cookies')!r}"
    blob = json.dumps(events, ensure_ascii=False)
    for личное in ("Айгуль Хабибуллина", "582731", "Ленина 12", "9171234567", "52.9321",
                   "cookie-secret-value", "sekret-bearer-value"):
        assert личное not in blob, f"{личное!r} уехало бы в Sentry"


# ---------- ключи сервера в исходящих запросах (приёмка #178, P2, 17.10.2026) ----------
# Sentry сам записывает каждый исходящий HTTP-запрос сервера «хлебной крошкой» — с полным
# адресом и строкой запроса. А в адресе Telegram лежит ТОКЕН БОТА (/bot<id>:<токен>/…), в строке
# запроса sms.ru — ключ api_id и текст СМС с кодом входа. С токеном бота любой пишет от имени
# Юлдаша всем, кто его запустил; с ключом sms.ru — шлёт СМС за наш счёт.
# Выдуманный токен собираем по частям: целиком он похож на настоящий, и сторож секретов
# (test_no_secrets_under_version_control) справедливо поднял бы тревогу на исходник теста.
ТОКЕН_БОТА = "123456789:" + "FAKE-not-a-real-token_" + "0" * 13
КЛЮЧ_SMS_RU = "7C2E1A9B-4D3F-4A1B-9C8D-0E1F2A3B4C5D"


def test_мойка_прячет_токен_бота_в_адресе():
    out = scrub_text(f"POST https://api.telegram.org/bot{ТОКЕН_БОТА}/sendMessage -> 502")
    assert out == "POST https://api.telegram.org/bot<скрыто>/sendMessage -> 502", out


def test_мойка_прячет_ключи_и_текст_смс_в_строке_запроса():
    query = f"api_id={КЛЮЧ_SMS_RU}&to=%2B79171234567&msg=Yuldash%3A+kod+582731&json=1"
    assert scrub_text(query) == "api_id=<скрыто>&to=%2B<телефон>&msg=<скрыто>&json=1"
    assert scrub_text("chat_id=-1001234567890&text=SOS%3A+52.93%2C+58.66") == "chat_id=<скрыто>&text=<скрыто>"
    assert scrub_text("geocode=Баймак&apikey=0f1e2d3c-aaaa&lang=ru_RU") == "geocode=Баймак&apikey=<скрыто>&lang=ru_RU"


@pytest.mark.parametrize("s", [
    "context=feed", "pretext=1", "msg_count=3", "textarea rows=4", "message=ok",
    "https://api.telegram.org/getMe", "robot123:running", "/bots/12",
])
def test_новые_правила_не_задевают_похожее(s):
    assert scrub_text(s) == s


def test_хлебная_крошка_http_оставляет_только_схему_хост_метод_и_статус():
    from app.observability import before_breadcrumb

    crumb = {
        "type": "http", "category": "httplib", "level": "info", "timestamp": "2026-10-17T15:07:18Z",
        "message": f"GET https://sms.ru/sms/send?api_id={КЛЮЧ_SMS_RU}",
        "data": {
            "url": f"https://user:pass@api.telegram.org:443/bot{ТОКЕН_БОТА}/sendMessage",
            "http.method": "POST", "http.response.status_code": 200, "reason": "OK",
            "http.query": f"api_id={КЛЮЧ_SMS_RU}&msg=kod+582731", "http.fragment": "x",
        },
    }
    out = before_breadcrumb(crumb, {})
    assert out == {
        "type": "http", "category": "httplib", "level": "info", "timestamp": "2026-10-17T15:07:18Z",
        "data": {"url": "https://api.telegram.org:443", "http.method": "POST",
                 "http.response.status_code": 200},
    }, out


def test_хлебная_крошка_лога_проходит_мойку_а_сломанная_выбрасывается():
    from app.observability import before_breadcrumb

    crumb = {"type": "log", "category": "yuldash", "level": "warning",
             "message": f"[SMS] звонить 8–917–765–43–21, бот /bot{ТОКЕН_БОТА}/getMe",
             "data": {"url": f"https://sms.ru/sms/send?api_id={КЛЮЧ_SMS_RU}"}}
    out = before_breadcrumb(crumb, {})
    assert out["message"] == "[SMS] звонить <телефон>, бот /bot<скрыто>/getMe", out
    assert out["data"] == {"url": "https://sms.ru/sms/send?api_id=<скрыто>"}, out
    assert crumb["message"].startswith("[SMS] звонить 8–917"), "исходную крошку не трогаем"

    class Сломанная(dict):
        def get(self, *a, **kw):
            raise RuntimeError("чужая структура")
    assert before_breadcrumb(Сломанная(), {}) is None   # лучше потерять крошку, чем отдать ключ


def test_init_sentry_чистит_хлебные_крошки(monkeypatch):
    import sentry_sdk

    from app import observability
    from app.config import settings

    seen: dict = {}
    monkeypatch.setattr(sentry_sdk, "init", lambda **kw: seen.update(kw))
    monkeypatch.setattr(settings, "sentry_dsn", "https://public@sentry.invalid/1", raising=False)
    monkeypatch.setattr(observability, "_sentry_ready", False, raising=False)

    assert observability.init_sentry() is True
    assert seen["before_breadcrumb"] is observability.before_breadcrumb


# Длинная строка без единого совпадения не должна вешать мойку: она работает на логах,
# трейсбеках и тексте от людей. Старые правила токена и почты пробовали начать совпадение
# с каждой буквы — время росло как квадрат длины: 40 КБ «a-a-…» — 2,3 с, 100 КБ — десятки секунд.
@pytest.mark.timeout(120)
@pytest.mark.parametrize("кусок", ["a-", "x", "a_", "1.", "deadbeef-", "a.", "%+", "aaaaaaaa.", "a@"])
def test_мойка_линейна_на_длинной_строке(кусок):
    import time

    s = (кусок * (100_000 // len(кусок) + 1))[:100_000]
    t = time.perf_counter()
    scrub_text(s)
    прошло = time.perf_counter() - t
    assert прошло < 1.0, f"100 КБ из {кусок!r}: {прошло:.2f} с"


# Настоящий путь SDK: наш init_sentry(), сервер шлёт админу в Telegram и СМС с кодом через
# sms.ru (сеть подменена — ответы отдаёт заглушка, Sentry видит обычный httpx), потом падает.
_SDK_PROBE_OUTGOING = r'''
import json, logging, os, sys
import httpx
import sentry_sdk

# Худший случай: корневой логгер на INFO — тогда httpx сам пишет «HTTP Request: GET https://sms.ru/
# sms/send?api_id=…&msg=…», и эта строка тоже становится хлебной крошкой (уже лога, не http).
logging.getLogger().setLevel(logging.INFO)
from sentry_sdk.transport import Transport

events = []

class Box(Transport):
    def capture_envelope(self, envelope):
        for item in envelope.items:
            ev = item.get_event() or item.get_transaction_event()
            if ev is not None:
                events.append(ev)

_real_init = sentry_sdk.init
def _init(*a, **kw):
    kw["transport"] = Box()
    return _real_init(*a, **kw)
sentry_sdk.init = _init

PHONE = "+79171234567"
def _fake_network(self, request):        # вместо интернета: ответы Telegram и sms.ru
    if request.url.host == "sms.ru":
        return httpx.Response(200, json={"status": "OK", "sms": {PHONE: {"status_code": 100}}},
                              request=request)
    return httpx.Response(200, json={"ok": True}, request=request)
httpx.HTTPTransport.handle_request = _fake_network

from app.observability import init_sentry
assert init_sentry() is True

from fastapi import FastAPI
from fastapi.testclient import TestClient
from app import services
from app.middleware import unhandled_exception_handler

app = FastAPI()
app.add_exception_handler(Exception, unhandled_exception_handler)

@app.post("/otp")
def otp():
    assert services.notify_admin_telegram("новая заявка") is True
    services.send_sms(PHONE, "582731")
    raise RuntimeError("сбой после отправки")

r = TestClient(app, raise_server_exceptions=False).post("/otp")
assert r.status_code == 500, r.status_code
sentry_sdk.flush()
print("EVENTS_JSON:" + json.dumps(events, ensure_ascii=False, default=str))
'''


def test_настоящий_sentry_не_уносит_ключи_из_исходящих_запросов(tmp_path):
    events = _события_пробы(
        _SDK_PROBE_OUTGOING, tmp_path,
        TELEGRAM_BOT_TOKEN=ТОКЕН_БОТА, ADMIN_TELEGRAM_CHAT_ID="-1001234567890",
        SMS_PROVIDER="smsru", SMS_RU_API_ID=КЛЮЧ_SMS_RU,
    )
    errors = [e for e in events if "сбой после отправки" in json.dumps(e, ensure_ascii=False)]
    assert errors, f"SDK не собрал событие о сбое — проверять нечего: {events!r}"[:2000]
    crumbs = [c for e in errors for c in (e.get("breadcrumbs") or {}).get("values", [])
              if c.get("category") == "httplib"]
    hosts = {(c.get("data") or {}).get("url") for c in crumbs}
    assert {"https://api.telegram.org", "https://sms.ru"} <= hosts, (
        f"крошки исходящих запросов должны остаться — с хостом, без пути и запроса: {crumbs!r}"
    )
    for c in crumbs:
        assert set(c.get("data") or {}) <= {"url", "http.method", "http.response.status_code"}, c
    blob = json.dumps(events, ensure_ascii=False)
    for секрет in (ТОКЕН_БОТА, ТОКЕН_БОТА.split(":")[1], КЛЮЧ_SMS_RU, "582731", "9171234567",
                   "-1001234567890"):
        assert секрет not in blob, f"{секрет!r} уехал бы в Sentry"
    assert "http.query" not in blob
