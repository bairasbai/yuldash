"""Воронка такси «посмотрел цену → заказал» (app/funnel.py).

Цифра нужна, чтобы правку цены можно было проверить, а не угадать: если после надбавки
заказов стало меньше, воронка отличает «людей мало» от «цена отпугнула».

Поэтому здесь проверяем ровно то, что делает её честной:
  • бурст оценок при перетаскивании пина — ОДИН просмотр, а не двадцать;
  • заказ засчитан только тому, кто перед этим цену видел (конверсия не может быть > 100%);
  • сбой Redis гасит метрику, но не роняет ни оценку, ни заказ — деньги важнее статистики;
  • координаты человека в ключи не утекают.
"""
from datetime import timedelta

import fakeredis
import pytest

from app import funnel
from app import instant_service as isv
from app.config import settings
from app.models import UserRole
from app.timeutil import local_date, utcnow

from test_instant import _order_body, fake_redis  # noqa: F401 — фикстура реэкспортом

ORIG = (52.591, 58.317)     # Баймак
DEST = (52.716, 58.664)     # Сибай


@pytest.fixture
def rds():
    """Redis без подмены глобального клиента — для юнит-проверок самой воронки."""
    return fakeredis.FakeStrictRedis(decode_responses=True)


class _BrokenRedis:
    """Redis, который на всё отвечает падением — так выглядит сбой в проде."""

    def __getattr__(self, _name):
        def boom(*_a, **_kw):
            raise RuntimeError("redis down")
        return boom


# ============================ 1. Склейка бурста ============================
def test_dragging_the_pin_is_one_price_view(rds):
    """Двадцать оценок одного маршрута подряд — один просмотр.

    Клиент пересчитывает цену на каждое движение пальца. Без склейки воронка мерила бы
    нервность пальца, а не интерес людей, и любая конверсия выглядела бы ничтожной.
    """
    for _ in range(20):
        funnel.note_price_view(rds, 1, ORIG, DEST)
    assert funnel.stats(rds)["views_today"] == 1


def test_another_route_is_another_view(rds):
    """Посмотрел цену на другой маршрут — это другой интерес, считаем отдельно."""
    funnel.note_price_view(rds, 1, ORIG, DEST)
    funnel.note_price_view(rds, 1, ORIG, (53.9, 58.9))
    assert funnel.stats(rds)["views_today"] == 2


def test_each_person_counted_separately(rds):
    """Склейка — по человеку: два пассажира на одном маршруте это два просмотра."""
    funnel.note_price_view(rds, 1, ORIG, DEST)
    funnel.note_price_view(rds, 2, ORIG, DEST)
    assert funnel.stats(rds)["views_today"] == 2


def test_the_same_route_counts_again_after_the_window(rds):
    """Окно склейки кончилось — человек вернулся к тому же маршруту, это новый интерес."""
    assert funnel.note_price_view(rds, 1, ORIG, DEST) is True
    key = f"funnel:seen:{funnel.TAXI}:1:{funnel._route_token(ORIG, DEST)}"
    assert rds.ttl(key) == settings.funnel_view_dedupe_min * 60, "окно склейки берётся из конфига"
    rds.delete(key)                                  # так выглядит истёкшее окно
    assert funnel.note_price_view(rds, 1, ORIG, DEST) is True
    assert funnel.stats(rds)["views_today"] == 2


# ============================ 2. Вторая половина воронки ============================
def test_order_after_a_price_view_is_counted(rds):
    funnel.note_price_view(rds, 1, ORIG, DEST)
    assert funnel.note_order(rds, 1) is True
    s = funnel.stats(rds)
    assert (s["views_today"], s["orders_today"], s["percent_today"]) == (1, 1, 100.0)


def test_order_without_a_price_view_is_not_counted(rds):
    """Заказал, не глядя на цену (повтор из истории) — это не наша воронка.

    Иначе конверсия могла бы перевалить за 100% и перестала бы что-либо значить.
    """
    assert funnel.note_order(rds, 1) is False
    assert funnel.stats(rds)["orders_today"] == 0


def test_attribution_window_is_longer_than_the_dedupe_window(rds):
    """Человек посмотрел цену, дошёл до подъезда и заказал через полчаса — это конверсия.

    Метка «видел цену» обязана жить дольше окна склейки, иначе воронка теряла бы именно тех,
    кто думал перед заказом, — и показывала бы, что цену смотрят одни, а заказывают другие.
    """
    assert settings.funnel_attribution_min > settings.funnel_view_dedupe_min
    funnel.note_price_view(rds, 7, ORIG, DEST)
    assert rds.ttl(f"funnel:eye:{funnel.TAXI}:7") == settings.funnel_attribution_min * 60


def test_conversion_is_none_when_nobody_looked(rds):
    """Ни одного просмотра — конверсия не 0%, а «неизвестно».

    «Никто не смотрел» и «смотрели, но никто не заказал» — разные новости: первая про то,
    что людей нет, вторая про то, что цена отпугнула. Показать 0% в обоих случаях значит
    соврать в половине.
    """
    s = funnel.stats(rds)
    assert s["views_today"] == 0 and s["percent_today"] is None


# ============================ 3. Сбои не роняют деньги ============================
def test_no_redis_is_quiet(rds):
    """Redis не настроен — метрика молчит, вызовы не падают."""
    assert funnel.note_price_view(None, 1, ORIG, DEST) is False
    assert funnel.note_order(None, 1) is False
    s = funnel.stats(None)
    assert s["views_today"] == 0 and s["percent_today"] is None
    assert len(s["by_day"]) == settings.funnel_window_days


def test_broken_redis_does_not_raise(rds):
    """Redis упал посреди дня — оценка и заказ обязаны пройти, метрика просто нулевая."""
    broken = _BrokenRedis()
    assert funnel.note_price_view(broken, 1, ORIG, DEST) is False
    assert funnel.note_order(broken, 1) is False
    assert funnel.stats(broken)["views_today"] == 0


# ============================ 4. Приватность и календарь ============================
def test_route_is_hashed_not_stored(rds):
    """В ключах Redis нет координат человека — только необратимый хэш маршрута."""
    funnel.note_price_view(rds, 1, ORIG, DEST)
    keys = "|".join(rds.keys("*"))
    for coord in (ORIG[0], ORIG[1], DEST[0], DEST[1]):
        assert f"{coord:.3f}" not in keys, "координаты пассажира утекли в ключ Redis"
        assert str(coord) not in keys


def test_day_bucket_is_local_not_utc(rds):
    """День считается по Уфе. По UTC он переворачивался бы в 5 утра, и вечерние просмотры
    падали бы в завтрашний столбец — график врал бы каждый вечер (урок в lessons.md)."""
    funnel.note_price_view(rds, 1, ORIG, DEST)
    assert rds.get(f"funnel:view:{local_date(utcnow()).isoformat()}") == "1"


def test_stats_gives_a_row_per_day_newest_first(rds):
    funnel.note_price_view(rds, 1, ORIG, DEST)
    s = funnel.stats(rds, days=3)
    days = [row["day"] for row in s["by_day"]]
    today = local_date(utcnow())
    assert days == [(today - timedelta(days=i)).isoformat() for i in range(3)]
    assert s["views_period"] == 1 and s["window_days"] == 3


def test_counters_expire_so_redis_is_not_an_archive(rds):
    """Счётчики дня живут месяц и уходят сами: метрика не должна расти вечно."""
    funnel.note_price_view(rds, 1, ORIG, DEST)
    ttl = rds.ttl(f"funnel:view:{local_date(utcnow()).isoformat()}")
    assert 0 < ttl <= 86400 * 31


# ============================ 5. Живьём: оценка → заказ → пульс ============================
def _admin(user_factory):
    from sqlmodel import Session

    from app.db import engine
    from app.models import User
    a = user_factory("ВоронкаАдмин")
    with Session(engine) as s:
        u = s.get(User, a["id"])
        u.role = UserRole.admin
        s.add(u)
        s.commit()
    return a


def test_estimate_then_order_shows_up_in_admin_pulse(client, user_factory, fake_redis):
    """Полный путь: три оценки одного маршрута + заказ → в пульсе 1 просмотр и 1 заказ."""
    admin = _admin(user_factory)
    pax = user_factory("ВоронкаПас")
    body = _order_body()
    for _ in range(3):
        assert client.post("/instant/estimate", headers=pax["auth"], json=body).status_code == 200
    assert client.post("/instant/orders", headers=pax["auth"], json=body).status_code == 200

    f = client.get("/admin/taxi/pulse", headers=admin["auth"]).json()["funnel"]
    assert f["views_today"] == 1, "три оценки одного маршрута — один просмотр"
    assert f["orders_today"] == 1
    assert f["percent_today"] == 100.0


def test_pulse_funnel_is_admin_only(client, user_factory, fake_redis):
    """Воронка — часть админ-пульса, обычному пользователю его не отдают."""
    someone = user_factory("ВоронкаЧужак")
    assert client.get("/admin/taxi/pulse", headers=someone["auth"]).status_code == 403


def test_price_view_survives_broken_redis_in_the_endpoint(client, user_factory, monkeypatch):
    """Redis лёг — оценка цены обязана работать: человек всё ещё должен узнать цену."""
    pax = user_factory("ВоронкаБезРедиса")
    monkeypatch.setattr(isv, "_redis_override", _BrokenRedis())
    r = client.post("/instant/estimate", headers=pax["auth"], json=_order_body())
    assert r.status_code == 200, r.text
    assert r.json()["price"] > 0
