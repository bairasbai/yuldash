"""Сквозные «журнальные» проверки волны 1 — попутки (PATH-01/02/03/12/13).

Зачем отдельная папка. Остальные тесты бэкенда проверяют одну ручку или одно правило и
заводят людей прямо в базе (`user_factory`). Здесь — путь человека ЦЕЛИКОМ, как он идёт
в приложении: каждый шаг — настоящий HTTP-запрос, люди входят через ту же дверь, что и
в проде (вход через Telegram-бота: SMS на проде выключены), водитель, пассажир, близкий и
админ — разные аккаунты. Ни одной записи в базу мимо API.

Два режима, один и тот же код теста:
* по умолчанию — в общем прогоне pytest через `TestClient` (тот же ASGI-стек, без сети);
  так пути проверяются в CI на SQLite и на PostgreSQL;
* `JOURNEY_BASE_URL=http://127.0.0.1:<порт>` — против ЖИВОГО сервера uvicorn по сети.
  Тогда журнал админа читается из лога сервера (`JOURNEY_SERVER_LOG`), а админом становится
  номер из `JOURNEY_ADMIN_PHONE` (сервер запущен с тем же `ADMIN_PHONES`).
  Как поднять — `docs/wave1-phone-check.md`, раздел «Для разработчика».
"""
from __future__ import annotations

import base64
import logging
import os
import re
import secrets
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

import httpx
import pytest

from app.config import settings

LIVE_URL = os.environ.get("JOURNEY_BASE_URL", "").rstrip("/")
LIVE_LOG = os.environ.get("JOURNEY_SERVER_LOG", "")
LIVE_ADMIN_PHONE = os.environ.get("JOURNEY_ADMIN_PHONE", "")

_CODE = re.compile(r"\b(\d{6})\b")


@dataclass
class Person:
    """Человек, вошедший через Telegram-бота. Только то, что вернул сервер."""
    name: str
    phone: str
    tg_id: int
    id: int
    access: str
    refresh: str
    device: str
    user: dict = field(default_factory=dict)

    @property
    def auth(self) -> dict:
        return {"Authorization": f"Bearer {self.access}", "X-Device-Id": self.device}


def _phone() -> str:
    # +7 999 ххх-хх-хх: случайный хвост — повторный прогон на той же базе не встретит «свой» номер.
    return "+7999" + "".join(secrets.choice("0123456789") for _ in range(7))


def _tg_id() -> int:
    return 7_000_000_000 + secrets.randbelow(900_000_000)


class Kit:
    """Шаги, общие для всех путей. Каждый — настоящий HTTP; проверки статусов внутри."""

    new_phone = staticmethod(_phone)
    new_tg_id = staticmethod(_tg_id)

    def __init__(self, api: httpx.Client, monkeypatch: pytest.MonkeyPatch):
        self.api = api
        self._monkeypatch = monkeypatch

    # ---------- вход через Telegram (дверь прода) ----------
    def tg_start(self) -> str:
        r = self.api.post("/auth/tg/start")
        assert r.status_code == 200, r.text
        return r.json()["request_id"]

    def tg_bot_start(self, request_id: str, tg_id: int, first_name: str) -> str:
        """Человек нажал «Старт» в боте → бот отвечает кодом (ответ вебхука и есть сообщение)."""
        r = self.api.post("/telegram/webhook", json={"message": {
            "text": f"/start {request_id}",
            "from": {"id": tg_id, "first_name": first_name},
            "chat": {"id": tg_id, "type": "private"},
        }})
        assert r.status_code == 200, r.text
        m = _CODE.search(r.json().get("text", ""))
        assert m, f"бот не прислал код: {r.text}"
        return m.group(1)

    def tg_share_phone(self, tg_id: int, phone: str) -> None:
        """Кнопка «Поделиться номером» в боте: только СВОЙ контакт."""
        r = self.api.post("/telegram/webhook", json={"message": {
            "from": {"id": tg_id}, "chat": {"id": tg_id, "type": "private"},
            "contact": {"phone_number": phone, "user_id": tg_id},
        }})
        assert r.status_code == 200, r.text

    def tg_verify(self, request_id: str, code: str, device: str) -> httpx.Response:
        return self.api.post("/auth/tg/verify", json={"request_id": request_id, "code": code},
                             headers={"X-Device-Id": device})

    def login(self, name: str, phone: str | None = None, tg_id: int | None = None,
              device: str | None = None) -> Person:
        phone = phone or _phone()
        tg_id = tg_id or _tg_id()
        device = device or f"journey-{secrets.token_hex(6)}"
        rid = self.tg_start()
        code = self.tg_bot_start(rid, tg_id, name)
        self.tg_share_phone(tg_id, phone)
        r = self.tg_verify(rid, code, device)
        assert r.status_code == 200, r.text
        return self._person(name, phone, tg_id, device, r.json())

    @staticmethod
    def _person(name, phone, tg_id, device, body) -> Person:
        u = body["user"]
        return Person(name=name, phone=phone, tg_id=tg_id, id=u["id"], access=body["access_token"],
                      refresh=body["refresh_token"], device=device, user=u)

    def login_admin(self) -> Person:
        """Админ — по номеру из ADMIN_PHONES (тот же механизм, что у владельца в проде)."""
        if LIVE_URL:
            assert LIVE_ADMIN_PHONE, "живой режим: задай JOURNEY_ADMIN_PHONE = ADMIN_PHONES сервера"
            phone = LIVE_ADMIN_PHONE
            # Один и тот же Telegram на каждый прогон: повторный вход попадает в тот же аккаунт.
            tg_id = 6_000_000_000 + int(re.sub(r"\D", "", phone)[-9:])
        else:
            phone, tg_id = _phone(), _tg_id()
            self._monkeypatch.setattr(settings, "admin_phones", phone)
        adm = self.login("Админ", phone=phone, tg_id=tg_id)
        assert adm.user["role"] == "admin", adm.user
        return adm

    def upload_doc(self, who: Person) -> str:
        """Фото документа так же, как шлёт приложение: крошечный JPEG → приватный /secure/docs URL."""
        b64 = base64.b64encode(b"\xff\xd8\xffjourney-doc-" + secrets.token_bytes(8)).decode()
        r = self.api.post("/upload/photo", headers=who.auth, json={"photo_b64": b64, "ext": "jpg"})
        assert r.status_code == 200, r.text
        return r.json()["url"]

    # ---------- общие шаги попутки ----------
    @staticmethod
    def depart_in(minutes: int) -> str:
        """Время выезда с поясом Уфы — как шлёт приложение."""
        ufa = timezone(timedelta(hours=5))
        return (datetime.now(timezone.utc) + timedelta(minutes=minutes)).astimezone(ufa).isoformat()

    def publish(self, driver: Person, **over) -> dict:
        body = {
            "from_city": "Баймак", "to_city": "Уфа", "depart_at": self.depart_in(20),
            "seats_total": 3, "price": 900, "comment": "Еду через Сибай, заберу у автовокзала",
            "pickup": "Автовокзал Баймак, у входа", "pickup_lat": 52.5912, "pickup_lng": 58.3110,
        }
        body.update(over)
        r = self.api.post("/rides", json=body, headers=driver.auth)
        assert r.status_code == 200, r.text
        return r.json()

    def find_ride(self, ride_id: int, who: Person | None = None, **filters) -> dict | None:
        """Поиск по маршруту, как в ленте. Листаем страницами: в общей базе прогона рейсов
        «Баймак → Уфа» от других тестов много, и наш мог не попасть в первую страницу."""
        params = {"from_city": "Баймак", "to_city": "Уфа", "limit": 200, "offset": 0}
        params.update(filters)
        for _ in range(25):
            r = self.api.get("/rides", params=params, headers=who.auth if who else None)
            assert r.status_code == 200, r.text
            page = r.json()
            hit = next((x for x in page if x["id"] == ride_id), None)
            if hit is not None or len(page) < params["limit"]:
                return hit
            params["offset"] += params["limit"]
        return None

    def book(self, passenger: Person, ride_id: int, seats: int = 1) -> dict:
        r = self.api.post("/bookings", json={"ride_id": ride_id, "seats": seats}, headers=passenger.auth)
        assert r.status_code == 200, r.text
        return r.json()

    def confirm(self, driver: Person, booking_id: int) -> dict:
        r = self.api.post(f"/bookings/{booking_id}/confirm", headers=driver.auth)
        assert r.status_code == 200, r.text
        return r.json()

    def role(self, who: Person, booking_id: int) -> dict:
        r = self.api.get(f"/bookings/{booking_id}/role", headers=who.auth)
        assert r.status_code == 200, r.text
        return r.json()

    def details(self, who: Person, booking_id: int) -> dict:
        r = self.api.get(f"/bookings/{booking_id}/details", headers=who.auth)
        assert r.status_code == 200, r.text
        return r.json()

    def driver_status(self, driver: Person, booking_id: int, status: str) -> httpx.Response:
        return self.api.post(f"/bookings/{booking_id}/driver-status", json={"status": status},
                             headers=driver.auth)

    def settle_boarding(self, booking_id: int) -> bool:
        """Окно «Отменить» водителя (5 минут) прошло — пассажир может завершить (приёмка PR174).
        В процессе — двигаем отметку «Сел» в базе; на живом сервере время не подвинуть: False."""
        if LIVE_URL:
            return False
        from boarding_helpers import settle_boarding
        settle_boarding(booking_id)
        return True

    def trip_status(self, passenger: Person, booking_id: int, status: str) -> httpx.Response:
        return self.api.post(f"/bookings/{booking_id}/trip-status", json={"status": status},
                             headers=passenger.auth)

    def notifications(self, who: Person) -> list[dict]:
        r = self.api.get("/notifications", headers=who.auth)
        assert r.status_code == 200, r.text
        return r.json()["items"]

    def notified(self, who: Person, title_ru: str, ref_id: int | None = None) -> dict | None:
        return next((n for n in self.notifications(who)
                     if n["title_ru"] == title_ru and (ref_id is None or n.get("ref_id") == ref_id)), None)


@pytest.fixture
def api(client, request):
    """HTTP-клиент пути: живой сервер по сети или тот же ASGI-стек в процессе."""
    if LIVE_URL:
        with httpx.Client(base_url=LIVE_URL, timeout=30) as c:
            yield c
    else:
        request.getfixturevalue("telegram_login_ready")
        yield client


@pytest.fixture
def kit(api, monkeypatch) -> Kit:
    return Kit(api, monkeypatch)


@pytest.fixture
def admin_journal(caplog):
    """Журнал админа: строки `[ADMIN] admin=<id> …` (см. app.logs.admin_action).

    В живом режиме — хвост лога сервера, появившийся за время теста; иначе — записи логгера."""
    caplog.set_level(logging.INFO, logger="yuldash")
    start = os.path.getsize(LIVE_LOG) if LIVE_LOG and os.path.exists(LIVE_LOG) else 0

    def lines() -> list[str]:
        if LIVE_URL:
            assert LIVE_LOG, "живой режим: задай JOURNEY_SERVER_LOG — лог uvicorn"
            with open(LIVE_LOG, "rb") as f:
                f.seek(start)
                text = f.read().decode("utf-8", errors="replace")
            return [ln for ln in text.splitlines() if "[ADMIN]" in ln]
        return [r.getMessage() for r in caplog.records if "[ADMIN]" in r.getMessage()]

    return lines
