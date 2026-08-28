"""Миграции должны доезжать до прода — а они не доезжали.

Что случилось (аудит 2026-08-21). Выкатка выполняет `alembic upgrade head`. Эта команда
работает, только если голова ОДНА. Их накопилось восемь: ветки заводились параллельно
и ни разу не сходились обратно. С таким деревом alembic отвечает «Multiple head revisions
are present» и не накатывает НИЧЕГО.

Почему этого никто не замечал. Локально база SQLite, и `db.py::_migrate_sqlite_add_columns`
молча дописывает недостающие колонки сам — приложение работает, тесты зелёные. В проде
PostgreSQL, он так не умеет, и новые колонки просто не появляются. Ломается это не в момент
выкатки, а позже — первым запросом к таблице, у которой в коде поле есть, а в базе нет.

Здесь два сторожа: голова одна, и каждая колонка моделей когда-нибудь доедет.
"""
import pathlib
import re

import pytest
from sqlalchemy import inspect
from sqlmodel import SQLModel

from app.db import engine

_VERSIONS = pathlib.Path(__file__).resolve().parents[1] / "alembic" / "versions"


def _revisions() -> dict:
    """revision → (список предков, имя файла). Понимает и одиночного предка, и кортеж."""
    out = {}
    for f in _VERSIONS.glob("*.py"):
        text = f.read_text(encoding="utf-8")
        rev = re.search(r'^revision = ["\'](.+?)["\']', text, re.M)
        if not rev:
            continue
        one = re.search(r'^down_revision = ["\'](.+?)["\']', text, re.M)
        many = re.search(r"^down_revision = \((.*?)\)", text, re.M | re.S)
        parents = [one.group(1)] if one else (
            re.findall(r'["\'](.+?)["\']', many.group(1)) if many else [])
        out[rev.group(1)] = (parents, f.name)
    return out


def test_there_is_exactly_one_head():
    """Одна голова — иначе `alembic upgrade head` падает и не накатывает ничего.

    Это не про аккуратность дерева, а про то, доедут ли изменения схемы до живых людей.
    Разошлись ветки — заведи ревизию слияния (образец: az_merge_heads_20260821).
    """
    revs = _revisions()
    assert revs, "ревизий не нашлось — разбор сломался"
    children = set()
    for parents, _ in revs.values():
        children.update(parents)
    heads = sorted(k for k in revs if k not in children)
    assert len(heads) == 1, (
        f"голов {len(heads)}, а `alembic upgrade head` работает только с одной: {heads}. "
        "Пока их больше, схема прода не обновляется вообще — ни этой веткой, ни чужими."
    )


def test_every_revision_points_at_a_real_parent():
    """Ссылка на несуществующего предка рвёт цепочку так же надёжно, как лишняя голова."""
    revs = _revisions()
    known = set(revs) | {None}
    broken = {
        name: [p for p in parents if p not in known]
        for parents, name in revs.values()
        if any(p not in known for p in parents)
    }
    assert not broken, f"ревизии ссылаются в пустоту: {broken}"


def test_alembic_itself_agrees_there_is_one_head():
    """Своя проверка выше читает файлы глазами; эта спрашивает сам alembic.

    Две проверки одного факта разными путями: наш разбор может не понять экзотическую
    запись предков, а alembic — источник истины для той команды, что идёт на выкатке.
    """
    alembic_cfg = pytest.importorskip("alembic.config")
    script_mod = pytest.importorskip("alembic.script")
    cfg = alembic_cfg.Config(str(_VERSIONS.parents[1] / "alembic.ini"))
    heads = script_mod.ScriptDirectory.from_config(cfg).get_heads()
    assert len(heads) == 1, f"alembic видит {len(heads)} голов: {heads}"


# Таблицы, которые целиком создаются на месте и в проде не мигрируются отдельно.
# Пусто: пока таких нет, но список нужен — иначе исключение впишут прямо в проверку.
_TABLES_WITHOUT_MIGRATIONS: set[str] = set()


def test_taxi_order_columns_exist_in_the_database(client):
    """Все поля такси-заказа реально есть в базе (client поднимает приложение и схему).

    Косвенно проверяет и миграцию: если колонку добавили в модель, а ревизию забыли,
    на SQLite это скроет авто-догонялка из db.py — но здесь мы хотя бы увидим, что список
    полей вообще сходится, и не забудем свериться с alembic перед выкаткой.
    """
    table = SQLModel.metadata.tables.get("instantorder")
    assert table is not None, "модель заказа пропала из метаданных"
    have = {c["name"] for c in inspect(engine).get_columns("instantorder")}
    missing = {c.name for c in table.columns} - have
    assert not missing, f"в базе нет колонок заказа: {sorted(missing)}"


def test_new_taxi_columns_are_covered_by_a_revision():
    """Поля волн 158–159 названы в ревизии поимённо.

    Проверяем именно текстом файла: колонка, добавленная в модель без ревизии, доедет
    до прода только чудом, а обнаружится первым запросом к таблице у живого человека.
    """
    text = (_VERSIONS / "ba_taxi_route_edit_20260821.py").read_text(encoding="utf-8")
    expected = [
        "round_trip", "return_wait_min",           # круговой рейс
        "pricing_k", "driven_km",                  # цена и след машины
        "destination_changed_at", "destination_changes", "destination_ack_at",
        "pending_to_lat", "pending_to_lng", "pending_to_text",
        "pending_price", "pending_asked_at", "pending_reason",
        "early_finish_reason",                     # «не смогу»
        "waypoints_json", "stop_started_at",       # остановки
    ]
    missing = [c for c in expected if f'"{c}"' not in text]
    assert not missing, f"колонки есть в модели, но не в ревизии: {missing}"
