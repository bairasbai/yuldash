# -*- coding: utf-8 -*-
"""Регрессия: старая локальная база с NULL auth_epoch ломала вход (401 после verify).

Причина (разбор 23.11.2026): догонялка `_migrate_sqlite_add_columns` смотрела только
Python-default колонки. У `auth_epoch` дефолт объявлен как `server_default="0"` при
явном sa_column — Python-default в колонку не попадает, колонка добавлялась с NULL.
Свежий токен наследовал NULL, `_token_revoked` требовал int — и каждый запрос после
успешного входа отвечал 401. Свежие базы (тесты, CI) этого не видели: там колонка
существует с рождения. Прод не затронут: alembic cm_auth_epoch ставит server_default.
"""
from __future__ import annotations

from sqlalchemy import BigInteger, Column, String, create_engine, text
from sqlmodel import Session, SQLModel

from app import db, models


def test_server_default_попадает_в_ddl_как_литерал():
    col = Column("auth_epoch", BigInteger, nullable=False, server_default="0")
    assert db._sqlite_default(col) == "0"


def test_выражение_server_default_не_подставляется():
    # CURRENT_TIMESTAMP — выражение, не литерал: в ALTER/UPDATE его не рендерим.
    col = Column("t", String, nullable=False, server_default=text("CURRENT_TIMESTAMP"))
    assert db._sqlite_default(col) == "NULL"


def test_старая_база_чинит_null_auth_epoch(monkeypatch, tmp_path):
    eng = create_engine(f"sqlite:///{(tmp_path / 'legacy.db').as_posix()}")
    SQLModel.metadata.create_all(eng)
    with Session(eng) as s:
        s.add(models.User(phone="+79270000001", name="Старый"))
        s.commit()
    # Имитируем базу ДО появления колонки: старая догонялка добавила её без дефолта.
    with eng.begin() as conn:
        conn.execute(text('ALTER TABLE "user" DROP COLUMN auth_epoch'))
    monkeypatch.setattr(db, "engine", eng)
    db._migrate_sqlite_add_columns()
    with eng.connect() as conn:
        val = conn.execute(text(
            'SELECT auth_epoch FROM "user" WHERE phone=\'+79270000001\'')).scalar()
    assert val == 0, "новая колонка должна прийти к старым строкам со значением 0, не NULL"

    # Вторая половина дефекта: колонка УЖЕ есть, но старые строки остались NULL.
    with eng.begin() as conn:
        conn.execute(text('UPDATE "user" SET auth_epoch = NULL'))
    db._migrate_sqlite_add_columns()
    with eng.connect() as conn:
        val = conn.execute(text(
            'SELECT auth_epoch FROM "user" WHERE phone=\'+79270000001\'')).scalar()
    assert val == 0, "backfill должен чинить существующие NULL, а не только новые колонки"
    eng.dispose()