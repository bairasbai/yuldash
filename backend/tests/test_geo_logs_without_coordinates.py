"""Координаты человека не уходят в лог через текст ошибки базы (§8, приёмка PR #186, S4).

Префильтр PostGIS в «Ближайших» (поездки и заявки) ловит ошибку и пишет предупреждение. Текст
ошибки SQLAlchemy — это SQL с ``ST_MakePoint(:lng, :lat)`` и строка ``[parameters: …]`` с самими
координатами. Две стены: в предупреждение — только вид ошибки; движок базы прячет значения
параметров в тексте любой ошибки (``hide_parameters``) — на случай, если где-то ещё пишут ``{e}``.
"""
from __future__ import annotations

import logging

import pytest
from sqlalchemy import text
from sqlmodel import Session

from app.db import engine
from app.models import UserRole

# Координаты с «узнаваемыми» хвостами — их и ищем в логе.
LAT, LNG = 52.3456789, 58.6123457


def _force_postgis_branch(monkeypatch):
    """На SQLite ветка PostGIS не включается — притворяемся Postgres: ``ST_*`` там нет, запрос
    упадёт, как падает на Postgres без PostGIS. На настоящем Postgres ничего не трогаем."""
    if engine.dialect.name == "sqlite":
        monkeypatch.setattr(engine.dialect, "name", "postgresql")


def _warnings(caplog, marker: str) -> list[str]:
    return [r.getMessage() for r in caplog.records if marker in r.getMessage()]


def _assert_no_coordinates(lines: list[str]):
    for ln in lines:
        assert "ST_" not in ln and "52.345" not in ln and "58.612" not in ln, ln


@pytest.mark.parametrize("path,marker", [
    ("/rides/near", "PostGIS prefilter skipped"),
    ("/requests/near", "requests PostGIS prefilter skipped"),
])
def test_postgis_prefilter_logs_only_the_error_type(client, user_factory, monkeypatch, caplog, path, marker):
    drv = user_factory("Geo Log Driver", role=UserRole.driver)
    with monkeypatch.context() as m, caplog.at_level(logging.WARNING, logger="yuldash"):
        _force_postgis_branch(m)
        r = client.get(path, headers=drv["auth"], params={"lat": LAT, "lng": LNG, "radius_km": 5})
    assert r.status_code == 200, r.text
    lines = _warnings(caplog, marker)
    if not lines and engine.dialect.name == "postgresql":
        pytest.skip("на этом Postgres есть PostGIS — префильтр не упал, ловить нечего")
    assert lines, "предупреждение о пропуске префильтра не записано"
    _assert_no_coordinates(lines)


def test_db_errors_hide_query_parameters():
    """Любая ошибка базы с параметрами: в её тексте нет значений (координат, телефонов)."""
    assert engine.hide_parameters is True
    with Session(engine) as s:
        with pytest.raises(Exception) as exc:
            s.execute(text("SELECT no_such_function_yuldash(:lat, :phone)"),
                      {"lat": LAT, "phone": "+79170001122"})
        s.rollback()
    msg = str(exc.value)
    assert "no_such_function_yuldash" in msg, "сам SQL в тексте ошибки остаётся — по нему видно, что сломалось"
    assert "52.345" not in msg and "79170001122" not in msg, msg
