"""Сторож выката: миграции накатываются с нуля, голова одна, лишнего в моделях нет.

Зачем. Тесты гоняются на базе, которую SQLModel создаёт прямо из моделей. Сервер живёт иначе:
там база меняется только миграциями. Всё, что ломает выкат, обычные тесты не видят —
это всплывает уже на проде, когда человек жмёт кнопку и получает пятисотку.

**Чего этот тест НЕ умеет — и почему.** Поймать «добавил поле в модель и забыл миграцию» он
не может: базовая миграция создаёт таблицы вызовом `create_all` прямо из моделей, поэтому
на чистой базе схема сходится всегда, какое поле ни добавь. На живом сервере, где базовая
миграция накатана год назад, такое поле не появится — и ручка упадёт. Проверить это
автоматически можно, только если базовую миграцию когда-нибудь зафиксируют явным SQL;
пока правило простое и человеческое: **добавил поле в модель — напиши миграцию**.

Что тест всё-таки стережёт, и это не мало:

* цепочка ревизий проходит с нуля без ошибок — битую миграцию видно здесь, а не на выкате;
* голова ровно одна. Две головы — это «alembic upgrade head» падает на сервере; в истории
  проекта такое уже случалось (отсюда merge-ревизии);
* в моделях нет полей, которых нет в базе после миграций, и наоборот.
"""
from __future__ import annotations

import os
import tempfile
from pathlib import Path

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlmodel import SQLModel, create_engine

import app.models  # noqa: F401 — импорт регистрирует все таблицы в metadata

BACKEND = Path(__file__).resolve().parents[1]


def _config(db_url: str | None = None) -> Config:
    cfg = Config(str(BACKEND / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND / "alembic"))
    if db_url:
        cfg.set_main_option("sqlalchemy.url", db_url)
    return cfg


@pytest.fixture
def fresh_db_url() -> str:
    return f"sqlite:///{(Path(tempfile.mkdtemp()) / 'deploy_check.db').as_posix()}"


def _upgrade_and_diff(db_url: str):
    old = os.environ.get("DATABASE_URL")
    os.environ["DATABASE_URL"] = db_url
    try:
        command.upgrade(_config(db_url), "head")
        with create_engine(db_url).connect() as conn:
            return compare_metadata(MigrationContext.configure(conn), SQLModel.metadata)
    finally:
        if old is None:
            os.environ.pop("DATABASE_URL", None)
        else:
            os.environ["DATABASE_URL"] = old


def test_миграции_накатываются_на_чистую_базу(fresh_db_url):
    """Главное про выкат: цепочка из девяноста с лишним ревизий проходит с нуля."""
    _upgrade_and_diff(fresh_db_url)   # битая миграция → исключение прямо здесь

    with create_engine(fresh_db_url).connect() as conn:
        from sqlalchemy import inspect

        tables = inspect(conn).get_table_names()
    assert len(tables) > 40, f"после миграций в базе всего {len(tables)} таблиц — что-то не накатилось"


def test_голова_миграций_одна(fresh_db_url):
    """Две головы — это «upgrade head» падает на сервере: alembic не знает, куда идти.
    В проекте так уже было, поэтому в истории есть merge-ревизии."""
    heads = ScriptDirectory.from_config(_config()).get_heads()

    assert len(heads) == 1, (
        f"у миграций {len(heads)} головы: {heads}. Сведи их merge-ревизией, иначе выкат упадёт "
        "на 'Multiple head revisions are present'."
    )


def test_в_моделях_нет_полей_которых_нет_в_базе(fresh_db_url):
    """Слабая, но не бесполезная проверка: см. оговорку в шапке файла."""
    diff = _upgrade_and_diff(fresh_db_url)

    missing = [f"{d[2]}.{d[3].name}" for d in diff if d[0] == "add_column"]
    assert not missing, f"этих колонок нет в базе после миграций: {missing}"

    extra = [f"{d[2]}.{d[3].name}" for d in diff if d[0] == "remove_column"]
    assert not extra, (
        f"эти колонки есть в базе, но пропали из моделей: {extra}. "
        "Либо верни поле, либо убери колонку миграцией — иначе следующий читатель не поймёт, "
        "заполняется оно ещё или уже нет."
    )
