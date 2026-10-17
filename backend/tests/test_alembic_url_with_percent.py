"""Пароль базы со знаком «%» не ломает миграции (хвост выкатки 13.10.2026, раздел 6 runbook).

Как это бывает. Пароль с «@» или «/» в адресе базы пишут закодированным: `p%40ss` вместо `p@ss`.
Значит, в адресе есть знак «%». А файл настроек alembic читает `configparser`, и для него «%» —
служебный знак подстановки (`%(имя)s`). Одинокий «%» он отвергает: `ValueError: invalid
interpolation syntax`. Миграция на сервере падала бы ещё до первого запроса к базе —
в самый неудобный момент, посреди выкатки.

Сторож проходит тем же путём, что и `alembic upgrade head` на сервере: `alembic/env.py`
берёт адрес из `DATABASE_URL` (или из настроек), кладёт в настройки alembic и отдаёт движку.
Движок подменён заглушкой, которая запоминает адрес и останавливает прогон: настоящий Postgres
не нужен, нужен только вопрос «дошёл ли адрес до движка таким, каким его написали».
"""
from __future__ import annotations

from pathlib import Path

import pytest
import sqlalchemy
from alembic import command
from alembic.config import Config

from app.config import settings

BACKEND = Path(__file__).resolve().parents[1]

# Пароль `p@ss%word!` в том виде, в каком он лежит в адресе: «@» → %40, «%» → %25, «!» → %21.
URL_WITH_PERCENT = "postgresql://yuldash:p%40ss%25word%21@127.0.0.1:5432/yuldash"


class _EngineReached(Exception):
    """Заглушка движка получила адрес — дальше идти незачем."""


def _config() -> Config:
    cfg = Config(str(BACKEND / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND / "alembic"))
    return cfg


def _url_that_reaches_the_engine(monkeypatch) -> str:
    seen: dict[str, str] = {}

    def fake_engine_from_config(configuration, prefix="sqlalchemy.", **kwargs):
        seen["url"] = configuration[prefix + "url"]
        raise _EngineReached

    # env.py при каждом запуске заново делает `from sqlalchemy import engine_from_config`,
    # поэтому подмена на самом пакете подхватывается.
    monkeypatch.setattr(sqlalchemy, "engine_from_config", fake_engine_from_config)
    with pytest.raises(_EngineReached):
        command.upgrade(_config(), "head")
    return seen["url"]


def test_адрес_с_процентом_из_DATABASE_URL_доходит_до_движка_без_изменений(monkeypatch):
    monkeypatch.setenv("DATABASE_URL", URL_WITH_PERCENT)

    assert _url_that_reaches_the_engine(monkeypatch) == URL_WITH_PERCENT


def test_адрес_с_процентом_из_настроек_доходит_до_движка_без_изменений(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setattr(settings, "database_url", URL_WITH_PERCENT)

    assert _url_that_reaches_the_engine(monkeypatch) == URL_WITH_PERCENT


def test_адрес_без_процента_не_меняется(monkeypatch):
    plain = "postgresql://yuldash:secret@127.0.0.1:5432/yuldash"
    monkeypatch.setenv("DATABASE_URL", plain)

    assert _url_that_reaches_the_engine(monkeypatch) == plain
