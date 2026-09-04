# -*- coding: utf-8 -*-
"""Недельный лимит труда водителя (разбор №2, 2026-08-03).

Дневной лимит был с самого начала: 8 часов на линии — иди отдыхать. Недельного не было
вообще, и это давало ровно ту дыру, из-за которой усталость и накапливается: восемь часов
в день семь дней подряд — 56 часов за рулём без единого выходного, и каждый отдельный день
формально в порядке.

Окно СКОЛЬЗЯЩЕЕ (последние 7 дней), а не «неделя с понедельника». Так водитель не выпадает
из работы на несколько суток подряд: завтра самый старый день уходит из окна и освобождает
часы. Для райцентра, где машин две-три, это важнее строгости — иначе лимит бьёт по пассажирам.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import workday as wd
from app.config import settings
from app.db import engine
from app.models import TaxiWorkDay, UserRole


def _seed_days(driver_id: int, hours_per_day: float, days: int, *, offset: int = 0):
    """Готовые дни на линии в истории. Прямой посев: гоняем не учёт, а сам лимит."""
    today = wd.local_day()
    with Session(engine) as s:
        for i in range(days):
            s.add(TaxiWorkDay(
                driver_id=driver_id,
                day=today - timedelta(days=i + offset),
                seconds_online=int(hours_per_day * 3600),
            ))
        s.commit()


@pytest.fixture
def driver(user_factory):
    return user_factory(name="НедельныйВодитель", role=UserRole.driver)


def test_normal_week_is_not_blocked(client, driver):
    """Пять дней по шесть часов — обычная неделя. Мешать такому водителю нельзя."""
    _seed_days(driver["id"], hours_per_day=6, days=5)
    with Session(engine) as s:
        assert wd.week_block_until(s, driver["id"]) is None


def test_week_over_the_limit_is_blocked(client, driver):
    """Семь дней по восемь часов — 56 часов. Формально каждый день в порядке, суммарно нет."""
    _seed_days(driver["id"], hours_per_day=8, days=7)
    with Session(engine) as s:
        assert wd.week_block_until(s, driver["id"]) is not None


def test_block_lifts_the_next_day(client, driver):
    """Блок до конца местного дня, а не на неделю: завтра старый день выпадет из окна."""
    _seed_days(driver["id"], hours_per_day=8, days=7)
    with Session(engine) as s:
        until = wd.week_block_until(s, driver["id"])
    assert until is not None
    tomorrow_local_start = wd.local_day() + timedelta(days=1)
    assert wd.local_now(until).date() == tomorrow_local_start


def test_hours_older_than_the_window_do_not_count(client, driver):
    """Наработал много две недели назад — сегодня это не его проблема."""
    _seed_days(driver["id"], hours_per_day=10, days=7, offset=14)
    with Session(engine) as s:
        assert wd.week_block_until(s, driver["id"]) is None


def test_limit_can_be_switched_off(client, driver, monkeypatch):
    """0 часов = лимит выключен: цифра должна оставаться в руках Александра, а не в коде."""
    _seed_days(driver["id"], hours_per_day=12, days=7)
    monkeypatch.setattr(settings, "taxi_week_limit_hours", 0)
    with Session(engine) as s:
        assert wd.week_block_until(s, driver["id"]) is None


def test_gate_explains_the_weekly_reason(client, driver):
    """Текст блокировки должен объяснять НЕДЕЛЮ, иначе водитель решит, что это сбой:
    сегодня он ещё не наездил дневной лимит, а его не пускают.

    Посев по шесть часов, а не по восемь: раньше стояло восемь, то есть ровно дневной лимит
    на сегодня — и тест противоречил собственному описанию. Держался он на том, что дневной
    блок срабатывал только по флагу, а флаг посев не ставил. С волны 212 гейт считает лимит
    живьём, и восемь часов «сегодня» дают именно дневной блок — верно, но это уже другой
    тест. Шесть часов в день: сегодня до лимита далеко, а за неделю сорок два часа.
    """
    from fastapi import HTTPException
    _seed_days(driver["id"], hours_per_day=6, days=7)
    with Session(engine) as s:
        with pytest.raises(HTTPException) as e:
            wd.guard_taxi_rested(s, driver["id"])
    assert "неделю" in str(e.value.detail).lower()


def test_week_seconds_counts_only_the_window(client, driver):
    _seed_days(driver["id"], hours_per_day=2, days=3)             # в окне
    _seed_days(driver["id"], hours_per_day=9, days=2, offset=20)  # далеко за окном
    with Session(engine) as s:
        assert wd.week_seconds(s, driver["id"]) == 3 * 2 * 3600
