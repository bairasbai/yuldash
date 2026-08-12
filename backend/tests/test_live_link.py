"""B7c «Live-ссылка для близких»: публичная страница /t/{token} + /t/{token}/state.json.

Покрываем: токен в share + SMS со ссылкой; страница/state по валидному токену; невалидный
токен 404; после done — «завершена» БЕЗ координат; телефоны/фамилии не текут; кэш-позиция
пишется при WS-приёме кадра водителя (fakeredis) и отдаётся в state.json; без Redis
car=null и ничего не падает; отзыв share гасит токен; ленивый токен для строк до миграции.
"""
import json
import re

from sqlmodel import Session

from app.db import engine
from app.models import TripShare, UserRole

from test_api import _ride
from test_instant import fake_redis  # noqa: F401 — fixture реэкспорт
from test_taxi_polish import _accepted_order
from test_taxi_polish2 import _contact


def _share(client, pax, order_id, cid):
    r = client.post(f"/instant/orders/{order_id}/share", headers=pax["auth"], json={"contact_id": cid})
    assert r.status_code == 200, r.text
    return r.json()


# ---------------------------------------------------------------------------
# Как проверять «координат в ответе нет»
#
# Раньше тут стояло `assert "52.6" not in st.text and "58.3" not in st.text` — поиск подстроки
# по СЫРОМУ тексту ответа. Это была не проверка, а лотерея: в ответе есть `updated_at`, а
# `datetime.isoformat()` пишет секунды с долями — «…T10:33:52.612345». Подстрока «52.6»
# появляется там, когда секунда равна 52, а микросекунды начинаются с 6, то есть РОВНО
# 1 прогон из 600 (то же для «58.3»). Продукт при этом чист: координат в теле нет вообще.
#
# Доказано заморозкой часов на 10:33:52.612345 — старая строка падала, а `body` не содержал
# ни одной координаты (аудит 2026-08-08). Урок — в docs/lessons.md: прибор, который умеет
# сказать «утекло» на пустом месте, не измеряет ничего.
#
# Правильный прибор смотрит ЗНАЧЕНИЯ, а не текст, и знает, что время — не место.
# ---------------------------------------------------------------------------

#: Полный набор полей завершённой поездки (см. share._finished). Закрытый список — чтобы
#: новое поле с гео нельзя было добавить незаметно: тест упадёт на самом факте появления ключа.
_FINISHED_KEYS = {"kind", "status", "phase_text", "car", "passenger_first_name", "updated_at"}

#: Ключи, где числа с точкой — это НЕ координата (время и версии). Их не смотрим.
_NOT_A_PLACE = {"updated_at", "created_at", "ts"}

#: Широта/долгота в наших краях: две цифры, точка, ещё минимум три знака (52.5915 / 58.3175).
_COORD_RE = re.compile(r"\b\d{2}\.\d{3,}")


def coord_like_values(body, *, key=None):
    """Значения ответа, похожие на координату. Пусто → гео не утекло.

    Смотрим и числа (диапазон Башкортостана), и строки. Ключи из `_NOT_A_PLACE` пропускаем:
    там лежит время, и его дробные секунды не имеют отношения к месту.
    """
    if key in _NOT_A_PLACE:
        return []
    if isinstance(body, dict):
        return [x for k, v in body.items() for x in coord_like_values(v, key=k)]
    if isinstance(body, (list, tuple)):
        return [x for v in body for x in coord_like_values(v, key=key)]
    if isinstance(body, bool):
        return []
    if isinstance(body, (int, float)):
        return [(key, body)] if 40.0 <= abs(float(body)) <= 80.0 else []
    if isinstance(body, str) and _COORD_RE.search(body):
        return [(key, body)]
    return []


# ============================ Токен + SMS со ссылкой ============================
def test_share_returns_token_and_sms_contains_link(client, user_factory, fake_redis, monkeypatch):
    """Share выдаёт случайный токен (≥16 байт → ≥22 симв. urlsafe), SMS близкому — ссылку /t/{token}."""
    sent = []
    from app.routers import family as family_router
    monkeypatch.setattr(family_router, "send_text", lambda phone, text: sent.append((phone, text)))
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "LnkDrv", "LnkPax")
    cid = _contact(client, pax, phone="+79990001001")
    share = _share(client, pax, order["id"], cid)
    token = share["token"]
    assert token and len(token) >= 22
    assert len(sent) == 1
    assert f"https://yulbash.ru/t/{token}" in sent[0][1]
    # Дедуп: повторный share возвращает ТОТ ЖЕ токен (ссылка у близкого не протухает).
    assert _share(client, pax, order["id"], cid)["token"] == token


def test_booking_share_sms_contains_link(client, user_factory, fake_redis, monkeypatch):
    """Попутка: share брони тоже шлёт близкому SMS со ссылкой, state отвечает по токену."""
    sent = []
    from app.routers import family as family_router
    monkeypatch.setattr(family_router, "send_text", lambda phone, text: sent.append((phone, text)))
    drv = user_factory("LnkBkDrv", role=UserRole.driver)
    pax = user_factory("LnkBkPax")
    ride_id = _ride(client, drv)
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    cid = _contact(client, pax, phone="+79990001002")
    r = client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": cid})
    assert r.status_code == 200, r.text
    token = r.json()["token"]
    assert token and f"/t/{token}" in sent[0][1]
    st = client.get(f"/t/{token}/state.json")
    assert st.status_code == 200
    body = st.json()
    assert body["status"] == "wait"                      # бронь pending — поездка ещё не началась
    assert body["from"]["text"] == "Баймак" and body["to"]["text"] == "Сибай"
    assert body["car"] is None


# ============================ Страница и state по валидному токену ============================
def test_live_page_and_state_valid_token(client, user_factory, fake_redis):
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "PgDrv", "PgPax")
    cid = _contact(client, pax, phone="+79990001003")
    token = _share(client, pax, order["id"], cid)["token"]

    page = client.get(f"/t/{token}")
    assert page.status_code == 200
    assert page.headers["content-type"].startswith("text/html")
    assert page.headers["cache-control"] == "no-store"
    assert "Юлдаш" in page.text and "state.json" in page.text

    st = client.get(f"/t/{token}/state.json")
    assert st.status_code == 200
    body = st.json()
    assert body["status"] == "to_pickup"                          # accepted → машина едет к посадке
    assert body["phase_text"]["ru"] and body["phase_text"]["ba"]  # два языка всегда
    assert body["from"]["lat"] and body["to"]["lat"]
    assert body["car"] is None                                    # водитель ещё не слал координаты
    assert body["passenger_first_name"] == "PgPax"
    assert body["updated_at"]


def test_no_phone_or_lastname_leak(client, user_factory, fake_redis):
    """Наружу — ТОЛЬКО имя пассажира: ни фамилии, ни телефонов, ни внутренних id участников."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "LeakDrv", "Гульнара Фамилиева")
    cid = _contact(client, pax, phone="+79990001004")
    token = _share(client, pax, order["id"], cid)["token"]
    for resp in (client.get(f"/t/{token}"), client.get(f"/t/{token}/state.json")):
        assert resp.status_code == 200
        assert "Фамилиева" not in resp.text          # фамилия не течёт
        assert "tg-test-" not in resp.text           # телефоны участников (user_factory) не текут
        assert "+7999" not in resp.text              # телефон доверенного контакта не течёт
    body = client.get(f"/t/{token}/state.json").json()
    assert body["passenger_first_name"] == "Гульнара"
    assert "passenger_id" not in body and "driver_id" not in body


# ============================ Невалидный токен ============================
def test_invalid_token_404(client):
    for path in ("/t/nonexistent-token-1234567890", "/t/nonexistent-token-1234567890/state.json",
                 "/t/x", "/t/x/state.json"):    # короткий токен даже не ищем (анти-перебор)
        assert client.get(path).status_code == 404


# ============================ Кэш позиции: WS-приём → state.json ============================
def test_ws_driver_location_cached_and_served(client, user_factory, fake_redis):
    """Кадр координат водителя в /ws/instant/{id}/location пишет livepos-кэш (fakeredis),
    state.json отдаёт машину близкому."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "WsCchDrv", "WsCchPax")
    oid = order["id"]
    cid = _contact(client, pax, phone="+79990001005")
    token = _share(client, pax, oid, cid)["token"]
    with client.websocket_connect(f"/ws/instant/{oid}/location") as pax_ws:
        pax_ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        with client.websocket_connect(f"/ws/instant/{oid}/location") as drv_ws:
            drv_ws.send_text(json.dumps({"type": "auth", "token": d["token"]}))
            drv_ws.send_text(json.dumps({"type": "loc", "lat": 52.60, "lng": 58.32, "bearing": 45}))
            got = pax_ws.receive_json()          # пассажир получил ретрансляцию → кэш уже записан
            assert got["lat"] == 52.60
    assert fake_redis.get(f"livepos:order:{oid}") is not None
    body = client.get(f"/t/{token}/state.json").json()
    assert body["car"] == {"lat": 52.60, "lng": 58.32, "bearing": 45}


def test_state_without_redis_car_null(client, user_factory, fake_redis):
    """Без Redis (car-кэша нет) state.json не падает: car=null, статусы работают."""
    from app import instant_service as isv
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "NoRDrv", "NoRPax")
    cid = _contact(client, pax, phone="+79990001006")
    token = _share(client, pax, order["id"], cid)["token"]
    isv._redis_override = None      # выключаем Redis (fixture в teardown всё равно сбросит)
    try:
        st = client.get(f"/t/{token}/state.json")
        assert st.status_code == 200 and st.json()["car"] is None
    finally:
        isv._redis_override = fake_redis


# ============================ После done — «завершена» БЕЗ координат ============================
def test_finished_hides_coordinates(client, user_factory, fake_redis):
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "FinDrv", "FinPax")
    oid = order["id"]
    cid = _contact(client, pax, phone="+79990001007")
    token = _share(client, pax, oid, cid)["token"]
    # Водитель успел прислать позицию — она в кэше. Точка — у подачи: следующим шагом он жмёт
    # «Я на месте», а эта кнопка теперь сверяется с последней позицией из трека (аудит 2026-08-07).
    with client.websocket_connect(f"/ws/instant/{oid}/location") as pax_ws:
        pax_ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        with client.websocket_connect(f"/ws/instant/{oid}/location") as drv_ws:
            drv_ws.send_text(json.dumps({"type": "auth", "token": d["token"]}))
            drv_ws.send_text(json.dumps({"type": "loc", "lat": 52.5915, "lng": 58.3175}))
            pax_ws.receive_json()
    for step in ("arrived", "onboard", "done"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200
    st = client.get(f"/t/{token}/state.json")
    assert st.status_code == 200
    body = st.json()
    assert body["status"] == "finished" and body["car"] is None
    assert "from" not in body and "to" not in body               # координат нет вообще
    assert set(body) == _FINISHED_KEYS                           # и никаких новых полей с гео
    assert coord_like_values(body) == []
    assert "завершена" in body["phase_text"]["ru"] and body["phase_text"]["ba"]
    assert client.get(f"/t/{token}").status_code == 200          # страница живёт, покажет «завершена»


# ============================ Прибор проверки: не врёт и не слеп ============================
# Мигающий тест — это почти всегда сломанный прибор, а не сломанный продукт. Прошлый прибор
# («подстрока 52.6 в тексте ответа») умел кричать «утекло» на пустом месте. Новый обязан
# уметь ОБА ответа, и это проверяется здесь — иначе через полгода никто не вспомнит, почему
# проверка написана именно так, и вернёт «проще, через .text».

def test_coord_check_survives_the_timestamp_that_used_to_break_it(client, user_factory, fake_redis, monkeypatch):
    """Часы на «секунда 52, микросекунды с 6» — ровно тот момент, что ронял старую проверку."""
    from datetime import datetime

    d, pax, order = _accepted_order(client, user_factory, fake_redis, "TsDrv", "TsPax")
    oid = order["id"]
    cid = _contact(client, pax, phone="+79990001099")
    token = _share(client, pax, oid, cid)["token"]
    for step in ("arrived", "onboard", "done"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200

    monkeypatch.setattr("app.routers.share.utcnow", lambda: datetime(2026, 8, 8, 10, 33, 52, 612345))
    st = client.get(f"/t/{token}/state.json")
    body = st.json()

    assert "52.6" in st.text                      # подстрока в тексте ЕСТЬ — это время, не широта
    assert body["updated_at"] == "2026-08-08T10:33:52.612345"
    assert coord_like_values(body) == []          # ...и прибор это понимает
    assert set(body) == _FINISHED_KEYS


def test_coord_check_still_catches_a_real_leak():
    """И обратная сторона: настоящую координату прибор обязан находить, иначе он бесполезен."""
    leaked = {"status": "finished", "updated_at": "2026-08-08T10:33:52.612345",
              "from": {"lat": 52.5915, "lng": 58.3175}}
    found = coord_like_values(leaked)
    assert ("lat", 52.5915) in found and ("lng", 58.3175) in found
    # И строкой тоже — координата умеет приехать в текстовом поле.
    assert coord_like_values({"note": "я на 52.5915, 58.3175"}) != []


# ============================ Отзыв share гасит токен ============================
def test_revoke_share_kills_token(client, user_factory, fake_redis):
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "RevDrv", "RevPax")
    oid = order["id"]
    cid = _contact(client, pax, phone="+79990001008")
    share = _share(client, pax, oid, cid)
    token = share["token"]
    assert client.get(f"/t/{token}").status_code == 200
    # Чужак и водитель отозвать не могут.
    outsider = user_factory("RevOutsider")
    assert client.delete(f"/instant/orders/{oid}/share/{share['id']}", headers=outsider["auth"]).status_code == 403
    assert client.delete(f"/instant/orders/{oid}/share/{share['id']}", headers=d["auth"]).status_code == 403
    # Пассажир отзывает → токен «сгорает» (страница и state — 404), шаринг пропадает из списка.
    r = client.delete(f"/instant/orders/{oid}/share/{share['id']}", headers=pax["auth"])
    assert r.status_code == 200
    assert client.get(f"/t/{token}").status_code == 404
    assert client.get(f"/t/{token}/state.json").status_code == 404
    assert client.get(f"/instant/orders/{oid}/shares", headers=pax["auth"]).json() == []


def test_revoke_booking_share(client, user_factory, fake_redis):
    drv = user_factory("RevBkDrv", role=UserRole.driver)
    pax = user_factory("RevBkPax")
    ride_id = _ride(client, drv)
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    cid = _contact(client, pax, phone="+79990001009")
    share = client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": cid}).json()
    assert client.delete(f"/bookings/{bid}/share/{share['id']}", headers=pax["auth"]).status_code == 200
    assert client.get(f"/t/{share['token']}").status_code == 404


# ============================ Строки до миграции: токен лениво ============================
def test_legacy_share_without_token_gets_one_on_reshare(client, user_factory, fake_redis):
    """Share, созданный до w2_livelink (token=NULL), получает токен при следующем share."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "LegDrv", "LegPax")
    cid = _contact(client, pax, phone="+79990001010")
    share = _share(client, pax, order["id"], cid)
    with Session(engine) as s:      # имитируем строку до миграции
        row = s.get(TripShare, share["id"])
        row.token = None
        s.add(row)
        s.commit()
    again = _share(client, pax, order["id"], cid)
    assert again["id"] == share["id"] and again["token"] and len(again["token"]) >= 22
