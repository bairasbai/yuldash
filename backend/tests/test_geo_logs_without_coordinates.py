"""Координаты человека не уходят в лог через текст ошибки базы (§8, приёмка PR #186, S4).

Префильтр PostGIS в «Ближайших» (поездки и заявки) ловит ошибку и пишет предупреждение. Текст
ошибки SQLAlchemy — это SQL с ``ST_MakePoint(:lng, :lat)`` и строка ``[parameters: …]`` с самими
координатами. Главная стена — в предупреждение пишем только вид ошибки (``type(e).__name__``).
Вторая — движок прячет строку ``[parameters: …]`` SQLAlchemy (``hide_parameters``). Она НЕ полная:
psycopg2 подставляет значения в запрос на клиенте, и сам Postgres повторяет их в своём тексте
(``LINE 1: SELECT f(52.34…)``, ``DETAIL: Key (phone)=(…)``) — поэтому ``{e}`` в лог не пишем нигде.
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
    """Строка параметров SQLAlchemy в тексте ошибки скрыта. На SQLite значений нет вовсе; на Postgres
    сервер сам повторяет подставленный запрос (см. шапку) — там проверяем только скрытую строку."""
    assert engine.hide_parameters is True
    with Session(engine) as s:
        with pytest.raises(Exception) as exc:
            s.execute(text("SELECT no_such_function_yuldash(:lat, :phone)"),
                      {"lat": LAT, "phone": "+79170001122"})
        s.rollback()
    msg = str(exc.value)
    assert "no_such_function_yuldash" in msg, "сам SQL в тексте ошибки остаётся — по нему видно, что сломалось"
    assert "[SQL parameters hidden due to hide_parameters=True]" in msg, msg
    assert "[parameters:" not in msg, msg
    if engine.dialect.name == "sqlite":
        assert "52.345" not in msg and "79170001122" not in msg, msg
