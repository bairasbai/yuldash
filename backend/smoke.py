# -*- coding: utf-8 -*-
"""Смоук-тест бэкенда без сервера/порта: гоняем весь сценарий через TestClient.
Запуск: backend/.venv ... python smoke.py  (из папки backend)
"""
import atexit
import os
import pathlib
import sys
import tempfile

# Консоль Windows (cp1251) не умеет «→» и «₽»: без этого print ронял смоук
# UnicodeEncodeError уже ПОСЛЕ прошедших проверок (23.11.2026).
sys.stdout.reconfigure(errors="replace")

# Изолированная СВЕЖАЯ база на каждый прогон — как в tests/conftest.py.
# Раньше смоук шёл в рабочую ./yuldash.db: зависел от её состояния (старые строки
# с NULL auth_epoch давали 401 после входа, 23.11.2026) и засорял её тестовыми людьми.
_DB = pathlib.Path(tempfile.gettempdir()) / f"yuldash_smoke_{os.getpid()}.db"
_DB.unlink(missing_ok=True)
os.environ["DATABASE_URL"] = f"sqlite:///{_DB.as_posix()}"
os.environ["ENV"] = "dev"
os.environ["SEED_DEMO"] = "false"
atexit.register(lambda: _DB.unlink(missing_ok=True))

from datetime import timedelta, timezone

from fastapi.testclient import TestClient

from app.main import app
from app.timeutil import utcnow

# Дата выезда — всегда в будущем и С ПОЯСОМ (+00:00): время без пояса сервер считает
# уфимским (UTC+5), и наивный UTC уходил на 5 часов в прошлое → 400. Захардкоженная
# дата была бомбой замедленного действия (сломалась после 23.06.2026).
# +20 минут, не «завтра»: посадку водитель отмечает не раньше чем за 30 минут до
# выезда (DONE_EARLY_GRACE) — «завтра» сервер отклоняет 409 «рано».
DEPART = (utcnow() + timedelta(minutes=20)).replace(tzinfo=timezone.utc).isoformat()
DEPART_NEXT = (utcnow() + timedelta(days=1)).replace(tzinfo=timezone.utc).isoformat()

with TestClient(app) as c:
    assert c.get("/health").json()["status"] == "ok"

    # вход водителя по телефону + OTP (в dev код приходит в ответе)
    code = c.post("/auth/request-code", json={"phone": "+79270000001"}).json()["dev_code"]
    v = c.post("/auth/verify", json={"phone": "+79270000001", "code": code, "name": "Байрас"}).json()
    token = v["access_token"]
    h = {"Authorization": f"Bearer {token}"}
    print("driver:", v["user"]["name"], "id", v["user"]["id"])

    # вход пассажира (бронирует чужие поездки — свои бронировать нельзя)
    pcode = c.post("/auth/request-code", json={"phone": "+79270000002"}).json()["dev_code"]
    pv = c.post("/auth/verify", json={"phone": "+79270000002", "code": pcode, "name": "Гульназ"}).json()
    ph = {"Authorization": f"Bearer {pv['access_token']}"}
    print("passenger:", pv["user"]["name"], "id", pv["user"]["id"])

    # /me с токеном
    assert c.get("/me", headers=h).json()["phone"] == "+79270000001"
    assert c.get("/me", headers=ph).json()["phone"] == "+79270000002"

    # водитель создаёт поездку
    ride = c.post("/rides", headers=h, json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": DEPART, "seats_total": 3, "price": 350, "category": "regular",
    }).json()
    print("ride:", ride["id"], ride["from_city"], "->", ride["to_city"], ride["price"], "RUB")

    # поиск
    found = c.get("/rides", params={"from_city": "Баймак", "to_city": "Сибай"}).json()
    print("search found:", len(found))
    assert len(found) >= 1

    # заявка пассажира + матчинг
    req = c.post("/requests", headers=ph, json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1, "category": "regular",
    }).json()
    matched = c.get("/match/rides", headers=ph, params={"request_id": req["id"]}).json()
    print("match:", len(matched))
    assert len(matched) >= 1

    # голосовая заявка: voice_url + transcript сохраняются и возвращаются
    vreq = c.post("/requests", headers=ph, json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1, "category": "regular",
        "voice_url": "/media/voice/test.m4a", "transcript": "Баймак Сибай одно место",
    }).json()
    assert vreq["voice_url"] == "/media/voice/test.m4a"
    assert vreq["transcript"] == "Баймак Сибай одно место"
    mine = c.get("/requests/mine", headers=ph).json()
    assert any(r["id"] == vreq["id"] and r["transcript"] == "Баймак Сибай одно место" for r in mine)
    print("voice request:", vreq["id"], "voice_url+transcript persisted")

    # пассажир бронирует, водитель подтверждает
    bk = c.post("/bookings", headers=ph, json={"ride_id": ride["id"], "seats": 1}).json()
    print("booking:", bk["id"], bk["status"], "boarding_code", bk["boarding_code"])
    bk2 = c.post(f"/bookings/{bk['id']}/confirm", headers=h).json()
    assert bk2["status"] == "confirmed"

    # водитель на линии
    dp = c.post("/driver/online", headers=h, json={"online": True}).json()
    assert dp["online"] is True

    # чат по брони
    c.post(f"/bookings/{bk['id']}/messages", headers=ph, json={"text": "Буду у вокзала в 17:20"})
    msgs = c.get(f"/bookings/{bk['id']}/messages", headers=h).json()
    print("messages:", len(msgs))
    assert len(msgs) >= 1

    # семейный контроль: контакт пассажира -> шаринг поездки -> статус «доехал»
    # (посадку с 17.10.2026 отмечает водитель; пассажирский «sat» сервер отклоняет 409)
    contact = c.post("/trusted-contacts", headers=ph, json={
        "name": "Гульназ", "relation": "Сестра", "phone": "+79277778899",
    }).json()
    c.post(f"/bookings/{bk['id']}/share", headers=ph, json={"contact_id": contact["id"]})
    # «Доехал» — только «в пути» и после окна «Отменить» водителя (5 минут, приёмка PR174):
    # водитель отмечает «Сел», а отметку двигаем в прошлое — ждать пять минут смоук не будет.
    c.post(f"/bookings/{bk['id']}/driver-status", headers=h, json={"status": "boarded"})
    from datetime import timedelta
    from sqlmodel import Session
    from app.db import engine
    from app.models import Booking
    from app.timeutil import utcnow
    with Session(engine) as s:
        b = s.get(Booking, bk["id"])
        b.onboard_at = utcnow() - timedelta(minutes=6)
        s.add(b)
        s.commit()
    shares = c.post(f"/bookings/{bk['id']}/trip-status", headers=ph, json={"status": "arrived"}).json()
    print("family share status:", shares[0]["last_status"])
    assert shares[0]["last_status"] == "arrived"

    # безопасность: SOS от пассажира
    sos = c.post("/sos", headers=ph, json={"category": "medical", "note": "нужна помощь"}).json()
    print("sos:", sos["id"], sos["category"], sos["status"])
    assert sos["status"] == "open"

    # премиум-предпочтения поездки round-trip (питомцы / детское кресло / только женщины / багаж).
    # «Только женщины» публикует лишь ПОДТВЕРЖДЁННАЯ женщина за рулём (волна 168):
    # самодекларации пола мало — нужно gender_verified у профиля водителя.
    fcode = c.post("/auth/request-code", json={"phone": "+79270000003"}).json()["dev_code"]
    fv = c.post("/auth/verify", json={"phone": "+79270000003", "code": fcode, "name": "Айгуль"}).json()
    fh = {"Authorization": f"Bearer {fv['access_token']}"}
    from app.models import DriverProfile, User
    from sqlmodel import Session as _FS, select as _fsel
    from app.db import engine as _feng
    with _FS(_feng) as _fs:
        _fu = _fs.exec(_fsel(User).where(User.phone == "+79270000003")).first()
        _fu.gender = "female"
        _fs.add(_fu)
        _fs.add(DriverProfile(user_id=_fu.id, gender_verified=True))
        _fs.commit()
    pride = c.post("/rides", headers=fh, json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": DEPART_NEXT, "seats_total": 3, "price": 700,
        "pets_allowed": True, "child_seat": True, "women_only": True, "baggage": True,
    }).json()
    assert pride["pets_allowed"] and pride["child_seat"] and pride["women_only"] and pride["baggage"]
    print("prefs ride:", pride["id"], "pets/child/women/baggage =",
          pride["pets_allowed"], pride["child_seat"], pride["women_only"], pride["baggage"])
    wonly = c.get("/rides", params={"from_city": "Сибай", "to_city": "Уфа", "women_only": True}).json()
    assert len(wonly) >= 1 and wonly[0]["women_only"] is True and wonly[0]["child_seat"] is True
    print("women_only filter found:", len(wonly), "child_seat shown in card:", wonly[0]["child_seat"])

    # проверка водителя: профиль авто -> фото -> отправка на проверку -> статус pending
    c.post("/driver/profile", headers=h, json={
        "car_make": "Kia", "car_model": "Rio", "car_color": "Белый", "car_plate": "А123ВС102", "seats": 4,
    })
    # Загрузка снифит формат (sniff_image=True): «hello» вместо фото — 400.
    # Генерируем настоящую маленькую картинку; OCR-автопроверку выключаем —
    # смоук проверяет ручной путь модерации, а не распознавание документов.
    import base64
    import io
    from PIL import Image
    from app.config import settings as _settings
    _settings.driver_autocheck_enabled = False
    _buf = io.BytesIO()
    Image.new("RGB", (32, 32), (180, 180, 180)).save(_buf, format="JPEG")
    _photo = base64.b64encode(_buf.getvalue()).decode()
    lic = c.post("/upload/photo", headers=h, json={"photo_b64": _photo, "ext": "jpg"}).json()["url"]
    carp = c.post("/upload/photo", headers=h, json={"photo_b64": _photo, "ext": "jpg"}).json()["url"]
    vr = c.post("/driver/verify", headers=h, json={"license_url": lic, "car_photo_url": carp}).json()
    assert vr["docs_status"] == "pending"
    st = c.get("/driver/status", headers=h).json()
    print("driver verify:", st["docs_status"], "car:", st["car_make"], st["car_model"], st["car_plate"])
    assert st["car_make"] == "Kia" and st["docs_status"] == "pending"

    # модерация админом: подтверждаем водителя -> verified=True.
    # С 11.2026 админ входит ТОЛЬКО через Telegram (cn_admin_telegram_cutover):
    # SMS-токен админку больше не открывает. Играем Telegram синтетически, как
    # tests/conftest.py::telegram_login_ready — без единого внешнего запроса.
    import httpx
    from app.config import settings
    settings.telegram_bot_token = "smoke-synthetic-bot"
    settings.telegram_webhook_secret = "smoke-synthetic-webhook"
    _real_post = httpx.post
    def _synthetic_post(url, **kwargs):
        if str(url).startswith("https://api.telegram.org/"):
            return httpx.Response(200, json={"ok": True, "result": {}})
        return _real_post(url, **kwargs)
    httpx.post = _synthetic_post
    _tg_headers = {"X-Telegram-Bot-Api-Secret-Token": "smoke-synthetic-webhook"}
    _tg_id = 777000777
    _challenge = c.post("/auth/tg/start").json()["request_id"]
    assert c.post("/telegram/webhook", headers=_tg_headers, json={"message": {
        "text": "/start " + _challenge, "from": {"id": _tg_id},
        "chat": {"id": _tg_id, "type": "private"},
    }}).status_code == 200
    assert c.post("/telegram/webhook", headers=_tg_headers, json={"message": {
        "from": {"id": _tg_id}, "chat": {"id": _tg_id, "type": "private"},
        "contact": {"user_id": _tg_id, "phone_number": "+79270000777"},
    }}).status_code == 200
    from app.models import TgAuth
    from sqlmodel import Session as _TS, select as _tsel
    from app.db import engine as _teng
    with _TS(_teng) as _ts:
        _tg_code = _ts.exec(_tsel(TgAuth).where(TgAuth.request_id == _challenge)).one().code
    av = c.post("/auth/tg/verify", json={"request_id": _challenge, "code": _tg_code}).json()
    # делаем этого пользователя админом напрямую в БД (в проде роль выдаётся вручную)
    from sqlmodel import Session as _S, select as _sel
    from app.db import engine as _eng
    from app.models import User as _U, UserRole as _R
    with _S(_eng) as _s:
        _au = _s.exec(_sel(_U).where(_U.phone == "+79270000777")).first()
        assert _au is not None
        _au.role = _R.admin
        _s.add(_au)
        _s.commit()
    ah = {"Authorization": f"Bearer {av['access_token']}"}
    driver_id = v["user"]["id"]
    mod = c.post(f"/admin/drivers/{driver_id}/moderate", headers=ah, json={"approve": True}).json()
    print("moderate:", mod)
    assert mod["verified"] is True and mod["docs_status"] == "verified"

    print("SMOKE OK")