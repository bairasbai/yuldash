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
#
# Тест кэша УБРАН при сборке релизной ветки (2026-08-06), и это не потеря покрытия.
# Ветка `architecture-review` лечила тяжёлый /rides/near кэшем в Redis на 15с. В main ту же
# боль вылечили иначе и глубже: PostGIS-префильтр по радиусу (GiST-индекс) плюс подсчёт
# дистанции/фильтров по лёгким колонкам, а полные объекты гидрируются только для страницы
# выдачи. Кэша в этом пути больше нет — значит и проверять в нём нечего.
# Дебаунс сигнала карты (выше) к рефакторингу не относится и остаётся в силе.
