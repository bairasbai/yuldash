"""Живая точка машины в кэше и наблюдаемость падений.

Две разные вещи, но обе про одно: **приложение не должно ломаться из-за подсобной системы**.

Живая точка. Когда пассажир делится поездкой с близким, тот открывает обычную страницу
и видит машину на карте. Координаты для неё лежат в Redis 2 минуты и туда же исчезают —
в базу мы их не пишем никогда. Если Redis выключен или упал, страница обязана открыться
и показать «машина не видна», а не пятисотую ошибку.

Наблюдаемость. Sentry — это «чёрный ящик»: он показывает падения у водителя в Баймаке,
до которого нам не дозвониться. Но сам он не имеет права ничего уронить: нет ключа —
тихо не включаемся, кривой ключ — тихо не включаемся, отправка не удалась — молчим.
И главное: наружу не должен уехать ни один телефон пассажира (152-ФЗ).
"""
from __future__ import annotations

import fakeredis
import pytest

from app import livepos, observability
from app import instant_service as isv
from app.config import settings


@pytest.fixture
def redis_on():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def redis_off():
    isv._redis_override = None
    yield


# ---------- Живая точка машины ----------

def test_позиция_пишется_и_читается(redis_on):
    livepos.livepos_set("order", 42, lat=52.5911, lng=58.3178, bearing=90.0)
    got = livepos.livepos_get("order", 42)
    assert got is not None
    assert got["lat"] == pytest.approx(52.5911)
    assert got["lng"] == pytest.approx(58.3178)
    assert got["bearing"] == pytest.approx(90.0)
    assert got["ts"], "без метки времени близкий не поймёт, свежая ли точка"


def test_позиция_живёт_недолго_и_сама_исчезает(redis_on):
    livepos.livepos_set("booking", 7, 52.0, 58.0)
    assert redis_on.ttl("livepos:booking:7") <= livepos.LIVEPOS_TTL_SEC
    assert redis_on.ttl("livepos:booking:7") > 0, "без срока жизни координаты остались бы навсегда"


def test_чужой_номер_поездки_ничего_не_отдаёт(redis_on):
    livepos.livepos_set("order", 1, 52.0, 58.0)
    assert livepos.livepos_get("order", 2) is None
    assert livepos.livepos_get("booking", 1) is None, "заказ такси и попутка — разные пространства"


def test_конец_поездки_стирает_точку_не_дожидаясь_срока(redis_on):
    livepos.livepos_set("order", 5, 52.0, 58.0)
    livepos.livepos_clear("order", 5)
    assert livepos.livepos_get("order", 5) is None


def test_без_redis_всё_молчит_но_не_падает(redis_off):
    livepos.livepos_set("order", 1, 52.0, 58.0)      # не должно бросить
    assert livepos.livepos_get("order", 1) is None   # машины не видно — и это честно
    livepos.livepos_clear("order", 1)                # тоже без исключения


def test_сломанный_redis_не_роняет_поток(monkeypatch):
    class BrokenRedis:
        def set(self, *a, **kw): raise RuntimeError("redis упал")
        def get(self, *a, **kw): raise RuntimeError("redis упал")
        def delete(self, *a, **kw): raise RuntimeError("redis упал")

    isv._redis_override = BrokenRedis()
    try:
        livepos.livepos_set("order", 1, 52.0, 58.0)
        assert livepos.livepos_get("order", 1) is None
        livepos.livepos_clear("order", 1)
    finally:
        isv._redis_override = None


def test_битые_данные_в_кэше_не_роняют_страницу(redis_on):
    redis_on.set("livepos:order:9", "это не json")
    assert livepos.livepos_get("order", 9) is None


# ---------- Наблюдаемость ----------

def test_без_ключа_sentry_молча_не_включается(monkeypatch):
    monkeypatch.setattr(settings, "sentry_dsn", "", raising=False)
    monkeypatch.setattr(observability, "_sentry_ready", False, raising=False)
    assert observability.init_sentry() is False
    assert observability.sentry_enabled() is False


def test_кривой_ключ_не_роняет_сервер(monkeypatch):
    # Александр может вставить ключ с опечаткой. Сервер обязан подняться всё равно.
    monkeypatch.setattr(settings, "sentry_dsn", "не-адрес-вовсе", raising=False)
    monkeypatch.setattr(observability, "_sentry_ready", False, raising=False)
    assert observability.init_sentry() is False


def test_повторный_вызов_ничего_не_пересоздаёт(monkeypatch):
    monkeypatch.setattr(observability, "_sentry_ready", True, raising=False)
    assert observability.init_sentry() is True


def test_отправка_без_включённого_sentry_тихий_no_op(monkeypatch):
    monkeypatch.setattr(observability, "_sentry_ready", False, raising=False)
    observability.capture(ValueError("что-то сломалось"))   # не должно бросить


def test_последний_рубеж_глотает_ошибку_чистки(monkeypatch):
    # Если сама чистка события упадёт, отправлять НЕЛЬЗЯ: лучше потерять отчёт,
    # чем отправить наружу чужой телефон.
    def boom(_event):
        raise RuntimeError("чистка сломалась")

    monkeypatch.setattr(observability, "_scrub", boom, raising=False)
    assert observability.before_send({"message": "что угодно"}, None) is None


def test_нормальное_событие_проходит_чистку():
    event = {"message": "обычная ошибка без персональных данных"}
    out = observability.before_send(event, None)
    assert out is not None
