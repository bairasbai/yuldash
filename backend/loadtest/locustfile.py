"""Юлдаш — нагрузочные сценарии (Locust) для горячих путей API.

Цель (Фаза 5, план 2026-07): p95 < 300 мс на ленте/near/matcher при ~200 RPS,
убийство одного uvicorn-воркера не роняет сервис.

Гоняем ТОЛЬКО против staging (никогда против прода yulbash.ru — реальные данные/SMS).
Как запускать — см. README.md рядом с этим файлом.

Сценарии моделируют три роли:
  • ReadUser        — «смотрит ленту»: /rides/near, /rides, /requests/near, /feed,
                      /popular-routes, /rides/price_hint. Самый массовый и самый
                      тяжёлый для БД путь (full-scan без индексов → EXPLAIN-чеклист в runbook).
  • RiderUser       — авторизуется (OTP dev-поток), смотрит «мои», иногда создаёт бронь.
  • InstantUser     — «Быстрый заказ» (matcher /instant/*). Включается флагом
                      YULDASH_LOADTEST_INSTANT=1 (эндпоинты появляются с веткой feat/instant-order;
                      пока их нет — 404 считаем ожидаемым, не «падением»).
  • ChatWSUser      — держит WebSocket чата (/ws/bookings/{id}) для проверки ip_hash-стикинга
                      и Redis pub/sub между воркерами. Требует websocket-client (см. requirements.txt).

Секретов в коде нет: базовый URL и токены берём из переменных окружения (см. README).
"""
from __future__ import annotations

import json
import os
import random
import time

from locust import HttpUser, between, events, task

# ------------------------------------------------------------------------------------
# Конфиг из окружения (никаких хардкодов адресов/секретов)
# ------------------------------------------------------------------------------------
# Базовый URL задаётся флагом locust --host (например http://staging.internal:8000).
# Ниже — только «поведенческие» переключатели.
INSTANT_ENABLED = os.getenv("YULDASH_LOADTEST_INSTANT", "0") == "1"
WS_ENABLED = os.getenv("YULDASH_LOADTEST_WS", "0") == "1"
# env=dev на staging → /auth/request-code возвращает dev_code, можно логиниться без SMS.
# Если staging НЕ в dev-режиме — задай пул готовых токенов через YULDASH_LOADTEST_TOKENS
# (через запятую) — их выдаст сид тестовых пользователей (см. README).
STATIC_TOKENS = [t.strip() for t in os.getenv("YULDASH_LOADTEST_TOKENS", "").split(",") if t.strip()]

# Реальные маршруты Башкортостана — чтобы фильтры по городам работали как в бою.
RB_CITIES = [
    ("Уфа", 54.7388, 55.9721),
    ("Стерлитамак", 53.6303, 55.9508),
    ("Салават", 53.3617, 55.9247),
    ("Нефтекамск", 56.0910, 54.2486),
    ("Октябрьский", 54.4816, 53.4655),
    ("Сибай", 52.7080, 58.6668),
    ("Белорецк", 53.9640, 58.4092),
    ("Ишимбай", 53.4520, 56.0396),
    ("Туймазы", 54.5996, 53.6949),
    ("Бирск", 55.4159, 55.5406),
]


def _route() -> tuple[tuple, tuple]:
    """Случайная пара разных городов РБ (откуда, куда)."""
    a, b = random.sample(RB_CITIES, 2)
    return a, b


# ------------------------------------------------------------------------------------
# Роль 1: ReadUser — массовый анонимный/полу-анонимный трафик ленты (самый горячий путь)
# ------------------------------------------------------------------------------------
class ReadUser(HttpUser):
    """Смотрит ленту поездок и заявок. Большая часть реального трафика — чтение.
    Веса подобраны под профиль «пассажир ищет машину на маршруте»."""

    weight = 6                      # самый частый пользователь
    wait_time = between(1, 4)       # человек листает, а не долбит

    @task(6)
    def rides_near(self):
        """Горячий путь №1 — лента ближайших машин по маршруту + гео-дистанция.
        На постгресе идёт через ST_DWithin (GiST), на больших объёмах — критично по индексам."""
        (fc, flat, flng), (tc, _, _) = _route()
        # ~половина запросов с координатами (радиус-фильтр), половина без — как в UI.
        params = {"from_city": fc, "to_city": tc}
        if random.random() < 0.5:
            params.update({"lat": flat, "lng": flng, "radius_km": random.choice([30, 70, 150])})
        if random.random() < 0.3:                    # пагинация «показать ещё»
            params.update({"limit": 20, "offset": 0})
        self.client.get("/rides/near", params=params, name="/rides/near")

    @task(3)
    def rides_list(self):
        """Горячий путь №2 — общая лента активных поездок (сортировка Boost→время)."""
        (fc, _, _), (tc, _, _) = _route()
        self.client.get("/rides", params={"from_city": fc, "to_city": tc}, name="/rides")

    # Заявки пассажиров ЗДЕСЬ НЕ ДЁРГАЕМ. Первый живой прогон (2026-08-04) показал 331 ошибку
    # 401 подряд: `/requests/near` требует входа — и это правильно, там маршрут живого человека,
    # а не опубликованная поездка. Приложение зовёт эту ручку тоже только с токеном (MapScreen).
    # Сценарий переехал к авторизованному пользователю ниже, чтобы нагрузка отражала реальность,
    # а не рисовала 10% «отказов», которых на проде не будет.

    @task(2)
    def feed(self):
        """Смешанная лента главного экрана (discovery)."""
        self.client.get("/feed", name="/feed")

    @task(1)
    def popular_routes(self):
        self.client.get("/popular-routes", name="/popular-routes")

    @task(1)
    def price_hint(self):
        (fc, _, _), (tc, _, _) = _route()
        self.client.get(
            "/rides/price_hint", params={"from_city": fc, "to_city": tc}, name="/rides/price_hint"
        )


# ------------------------------------------------------------------------------------
# Роль 2: RiderUser — авторизованный пассажир (логин, «мои», иногда бронь)
# ------------------------------------------------------------------------------------
class RiderUser(HttpUser):
    """Авторизованный пользователь. На старте получает токен, дальше ходит в личные ручки.
    Логин через OTP dev-поток (staging env=dev) ИЛИ из пула YULDASH_LOADTEST_TOKENS."""

    weight = 3
    wait_time = between(2, 6)

    def on_start(self):
        self.token = self._login()
        self.headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}

    def _login(self) -> str | None:
        # Вариант А: заранее выданные токены (staging без dev-режима) — просто берём случайный.
        if STATIC_TOKENS:
            return random.choice(STATIC_TOKENS)
        # Вариант Б: dev-поток OTP — уникальный «тестовый» номер, код прилетает в ответе (env=dev).
        phone = f"+7900{random.randint(1000000, 9999999)}"
        with self.client.post(
            "/auth/request-code", json={"phone": phone}, name="/auth/request-code", catch_response=True
        ) as r:
            if r.status_code != 200:
                r.failure(f"request-code {r.status_code}")
                return None
            code = r.json().get("dev_code")
            if not code:
                # staging не в dev-режиме и токены не заданы — дальше только чтение.
                r.success()
                return None
            r.success()
        with self.client.post(
            "/auth/verify",
            json={"phone": phone, "code": code, "name": "LoadTest"},
            name="/auth/verify",
            catch_response=True,
        ) as r:
            if r.status_code != 200:
                r.failure(f"verify {r.status_code}")
                return None
            r.success()
            return r.json().get("access_token") or r.json().get("token")

    @task(4)
    def my_feed(self):
        """Даже авторизованный чаще всего просто смотрит ленту."""
        (fc, _, _), (tc, _, _) = _route()
        self.client.get("/rides/near", params={"from_city": fc, "to_city": tc}, name="/rides/near")

    @task(2)
    def me(self):
        if self.headers:
            self.client.get("/me", headers=self.headers, name="/me")

    @task(2)
    def bookings_mine(self):
        if self.headers:
            self.client.get("/bookings/mine", headers=self.headers, name="/bookings/mine")

    @task(2)
    def requests_near(self):
        """Лента заявок пассажиров рядом — водитель смотрит, кто ищет попутку на его маршруте.
        Ручка закрыта входом (в заявке маршрут живого человека), поэтому идёт с токеном."""
        if not self.headers:
            return
        (fc, flat, flng), (tc, _, _) = _route()
        self.client.get(
            "/requests/near",
            params={"from_city": fc, "to_city": tc, "lat": flat, "lng": flng},
            headers=self.headers,
            name="/requests/near",
        )

    @task(1)
    def create_booking(self):
        """Иногда бронирует место в случайной активной поездке (запись → row-lock путь)."""
        if not self.headers:
            return
        # находим кандидата в ленте
        (fc, _, _), (tc, _, _) = _route()
        r = self.client.get("/rides/near", params={"from_city": fc, "to_city": tc}, name="/rides/near")
        try:
            items = r.json().get("items", [])
        except Exception:  # noqa: BLE001
            items = []
        if not items:
            return
        ride_id = items[0].get("id")
        if not ride_id:
            return
        with self.client.post(
            "/bookings",
            json={"ride_id": ride_id, "seats": 1},
            headers=self.headers,
            name="/bookings",
            catch_response=True,
        ) as resp:
            # 400/409 (нет мест / своя поездка / уже бронировал) — нормальная бизнес-логика, не сбой сервера.
            if resp.status_code in (200, 400, 409):
                resp.success()
            else:
                resp.failure(f"bookings {resp.status_code}")


# ------------------------------------------------------------------------------------
# Роль 3: InstantUser — «Быстрый заказ» / matcher (опционально, флаг)
# ------------------------------------------------------------------------------------
class InstantUser(HttpUser):
    """Матчер «Быстрого заказа». Включается YULDASH_LOADTEST_INSTANT=1.
    Пути /instant/* появляются с веткой feat/instant-order; пока их нет — 404 ожидаем."""

    weight = 2 if INSTANT_ENABLED else 0
    wait_time = between(2, 5)

    def on_start(self):
        if not INSTANT_ENABLED:
            self.stop()

    @task(3)
    def estimate(self):
        """Оценка цены/времени подачи по точкам А→Б (лёгкий гео-запрос к presence)."""
        (fc, flat, flng), (tc, tlat, tlng) = _route()
        with self.client.get(
            "/instant/estimate",
            params={"from_lat": flat, "from_lng": flng, "to_lat": tlat, "to_lng": tlng},
            name="/instant/estimate",
            catch_response=True,
        ) as r:
            self._ok_or_expected_404(r)

    @task(1)
    def create_order(self):
        """Создание заказа → запускается matcher-цикл (arq). Тяжёлый по GEOSEARCH в Redis."""
        (fc, flat, flng), (tc, tlat, tlng) = _route()
        with self.client.post(
            "/instant/order",
            json={
                "from_lat": flat, "from_lng": flng, "from_city": fc,
                "to_lat": tlat, "to_lng": tlng, "to_city": tc,
            },
            name="/instant/order",
            catch_response=True,
        ) as r:
            self._ok_or_expected_404(r)

    @staticmethod
    def _ok_or_expected_404(r):
        # 404 = ветка matcher ещё не задеплоена на staging → это ожидаемо, не «падение».
        # 401/422 = нужен токен/иные поля (появятся с реальной веткой) → тоже не сбой сервера.
        if r.status_code in (200, 401, 404, 422):
            r.success()
        else:
            r.failure(f"instant {r.status_code}")


# ------------------------------------------------------------------------------------
# Роль 4: ChatWSUser — держит WebSocket чата (проверка ip_hash-стикинга + Redis pub/sub)
# ------------------------------------------------------------------------------------
# Locust не умеет WS «из коробки». Держим лёгкий WS-пользователь на websocket-client,
# считая метрику вручную через events.request. Включается YULDASH_LOADTEST_WS=1
# (нужен валидный booking_id + токен; см. README — как засидить пару в staging).
if WS_ENABLED:
    try:
        import websocket  # websocket-client (см. requirements.txt)
    except ImportError:  # pragma: no cover
        websocket = None

    from locust import User

    WS_BOOKING_ID = os.getenv("YULDASH_LOADTEST_WS_BOOKING", "")
    WS_TOKEN = os.getenv("YULDASH_LOADTEST_WS_TOKEN", "")

    class ChatWSUser(User):
        weight = 1
        wait_time = between(5, 15)

        @task
        def open_chat_socket(self):
            if websocket is None or not WS_BOOKING_ID:
                return
            base = self.host.replace("http://", "ws://").replace("https://", "wss://")
            url = f"{base}/ws/bookings/{WS_BOOKING_ID}"
            start = time.time()
            exc = None
            try:
                ws = websocket.create_connection(url, timeout=10)
                # первый кадр — авторизация токеном (контракт чата: {"token": ...})
                ws.send(json.dumps({"token": WS_TOKEN}))
                ws.settimeout(10)
                try:
                    ws.recv()      # ждём приветственный/исторический кадр
                except Exception:  # noqa: BLE001 — таймаут ожидания сообщения не считаем ошибкой соединения
                    pass
                ws.close()
            except Exception as e:  # noqa: BLE001
                exc = e
            events.request.fire(
                request_type="WS",
                name="/ws/bookings/{id}",
                response_time=int((time.time() - start) * 1000),
                response_length=0,
                exception=exc,
            )


# ------------------------------------------------------------------------------------
# Порог качества: печатаем вердикт p95<300ms по завершении прогона.
# ------------------------------------------------------------------------------------
P95_TARGET_MS = int(os.getenv("YULDASH_LOADTEST_P95_MS", "300"))


def _say(text: str) -> None:
    """Печать, которая переживает windows-консоль.

    Первый живой прогон (2026-08-04) упал на последней строке: консоль Windows в cp1251 не умеет
    печатать «❌», и весь вердикт превратился в UnicodeEncodeError с кодом выхода 1 — то есть
    инструмент сообщал о провале теста, хотя тест прошёл. Ошибка в градуснике хуже, чем
    отсутствие градусника: ей верят.
    """
    try:
        print(text)
    except UnicodeEncodeError:
        import sys as _sys
        enc = _sys.stdout.encoding or "ascii"
        print(text.encode(enc, errors="replace").decode(enc, errors="replace"))


@events.quitting.add_listener
def _assert_p95(environment, **_kw):
    stats = environment.stats.total
    p95 = stats.get_response_time_percentile(0.95)
    fail_ratio = stats.fail_ratio
    _say("\n" + "=" * 60)
    _say(f"[Юлдаш loadtest] p95={p95} ms (цель <{P95_TARGET_MS}), "
         f"RPS={stats.total_rps:.1f}, ошибок={fail_ratio * 100:.2f}%")
    # Ненулевой код выхода в CI, если не уложились в цель или много ошибок.
    if p95 is None or p95 > P95_TARGET_MS:
        _say(f"[Юлдаш loadtest] ПРОВАЛ: p95 {p95} ms > цель {P95_TARGET_MS} ms")
        environment.process_exit_code = 1
    elif fail_ratio > 0.01:
        _say(f"[Юлдаш loadtest] ПРОВАЛ: доля ошибок {fail_ratio * 100:.2f}% > 1%")
        environment.process_exit_code = 1
    else:
        _say("[Юлдаш loadtest] ЦЕЛЬ ДОСТИГНУТА")
        environment.process_exit_code = 0
    _say("=" * 60)
