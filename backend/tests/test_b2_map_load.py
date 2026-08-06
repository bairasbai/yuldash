"""Фаза 1 · B2 — «эффект толпы» на карте (архитектурное ревью 2026-07-20).

notify_map_changed будит ВСЕХ с открытой картой, они дружно дёргают тяжёлый /rides/near.
Фикс: (1) дебаунс сигнала refresh (всплеск изменений → один refresh в окно);
      (2) кэш /rides/near на 15с (залп читается из Redis, не из БД).
"""
from sqlmodel import Session

from app.db import engine
from app.models import Ride, RideStatus, UserRole
from app.timeutil import utcnow


# --------------------------- дебаунс сигнала карты ---------------------------

class _FakeGateRedis:
    """Мини-эмуляция Redis: SET NX EX (ворота) + счётчик publish."""
    def __init__(self):
        self._keys = set()
        self.published = []

    def set(self, key, val, nx=False, ex=None):
        if nx and key in self._keys:
            return None            # ключ уже стоит → NX не проходит (окно ещё открыто)
        self._keys.add(key)
        return True

    def publish(self, channel, data):
        self.published.append((channel, data))


def test_map_refresh_debounced(monkeypatch):
    from app import services
    fake = _FakeGateRedis()
    monkeypatch.setattr(services, "_cache_client", lambda: fake)
    for _ in range(3):            # всплеск: 3 изменения подряд в одном окне
        services.notify_map_changed()
    assert len(fake.published) == 1, "всплеск из 3 событий должен схлопнуться в 1 refresh"


def test_map_refresh_passes_after_window(monkeypatch):
    from app import services
    fake = _FakeGateRedis()
    monkeypatch.setattr(services, "_cache_client", lambda: fake)
    services.notify_map_changed()
    fake._keys.clear()            # эмулируем истечение окна (TTL ворот)
    services.notify_map_changed()
    assert len(fake.published) == 2, "после окна следующий refresh должен проходить"


def test_map_refresh_noop_without_redis(monkeypatch):
    from app import services
    monkeypatch.setattr(services, "_cache_client", lambda: None)
    services.notify_map_changed()   # без Redis — тихий no-op, не падаем (polling подстрахует)


# --------------------------- кэш /rides/near ---------------------------

def test_rides_near_served_from_cache(client, user_factory, monkeypatch):
    """Второй запрос в пределах TTL отдаётся из кэша (не из БД); сброс кэша → снова из БД."""
    store: dict = {}
    monkeypatch.setattr("app.routers.rides.cache_get_json", lambda k: store.get(k))
    monkeypatch.setattr("app.routers.rides.cache_set_json", lambda k, v, ttl: store.__setitem__(k, v))

    drv = user_factory("B2Drv", role=UserRole.driver)
    with Session(engine) as s:
        s.add(Ride(driver_id=drv["id"], from_city="Кэштаун", to_city="Кэшсити",
                   depart_at=utcnow(), seats_total=3, seats_left=3, status=RideStatus.active))
        s.commit()

    pax = user_factory("B2Pax")
    params = {"from_city": "Кэштаун", "to_city": "Кэшсити"}

    r1 = client.get("/rides/near", headers=pax["auth"], params=params)
    assert r1.status_code == 200 and r1.json()["count"] == 1
    assert store, "первый запрос должен наполнить кэш"

    # добавляем 2-ю поездку — в пределах TTL near отдаёт кэшированный список, новую не видит
    with Session(engine) as s:
        s.add(Ride(driver_id=drv["id"], from_city="Кэштаун", to_city="Кэшсити",
                   depart_at=utcnow(), seats_total=3, seats_left=3, status=RideStatus.active))
        s.commit()
    r2 = client.get("/rides/near", headers=pax["auth"], params=params)
    assert r2.json()["count"] == 1, "в пределах TTL — из кэша, новая поездка не видна"

    # сброс кэша (истёк TTL) → снова считает из БД, видит обе
    store.clear()
    r3 = client.get("/rides/near", headers=pax["auth"], params=params)
    assert r3.json()["count"] == 2, "после сброса кэша — свежий счёт из БД"
