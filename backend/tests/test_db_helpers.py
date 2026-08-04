"""Догонялка локальной базы: старая база подхватывает новые поля моделей.

Что это. На сервере схему меняют миграциями. Но на ноутбуке разработчика база живёт
месяцами и отстаёт: в коде у поездки появилось поле «багаж», а в локальной таблице его нет —
и приложение падает на любом запросе поездок. Эта догонялка добавляет недостающие колонки
сама, чтобы локальная разработка не требовала каждый раз сносить базу.

Проверяем именно то, что ломается молча: подбор типа колонки и значения по умолчанию.
Ошибка здесь выглядит как «у всех поездок багаж почему-то NULL» — не падение, а тихая
неправильность, которую замечают через неделю.
"""
from __future__ import annotations

import enum

import pytest
from sqlalchemy import Boolean, Column, DateTime, Float, Integer, Numeric, String, Text

from app import db


class _Kind(str, enum.Enum):
    regular = "regular"


@pytest.mark.parametrize(
    "column, expected, why",
    [
        (Column("a", Integer()), "INTEGER", "число мест"),
        (Column("b", Boolean()), "INTEGER", "галочка «можно с животными» — в SQLite это 0/1"),
        (Column("c", Float()), "REAL", "рейтинг водителя"),
        (Column("d", Numeric()), "REAL", "деньги"),
        (Column("e", DateTime()), "DATETIME", "время выезда"),
        (Column("f", String()), "TEXT", "город"),
        (Column("g", Text()), "TEXT", "комментарий"),
    ],
)
def test_тип_колонки_подбирается_под_sqlite(column, expected, why):
    assert db._sqlite_coltype(column) == expected, why


def test_без_значения_по_умолчанию_ставится_null():
    assert db._sqlite_default(Column("x", Integer())) == "NULL"


def test_вычисляемое_значение_по_умолчанию_не_попадает_в_ddl():
    # default=utcnow — функция; её нельзя записать литералом в ALTER TABLE.
    assert db._sqlite_default(Column("x", DateTime(), default=lambda: 1)) == "NULL"


def test_галочка_превращается_в_ноль_и_единицу():
    assert db._sqlite_default(Column("x", Boolean(), default=True)) == "1"
    assert db._sqlite_default(Column("x", Boolean(), default=False)) == "0"


def test_числа_пишутся_как_есть():
    assert db._sqlite_default(Column("x", Integer(), default=4)) == "4"
    assert db._sqlite_default(Column("x", Float(), default=1.5)) == "1.5"


def test_строка_экранируется():
    # Апостроф в значении по умолчанию иначе разорвал бы SQL-запрос пополам.
    assert db._sqlite_default(Column("x", String(), default="Ленина")) == "'Ленина'"
    assert db._sqlite_default(Column("x", String(), default="д'Артаньян")) == "'д''Артаньян'"


def test_перечисление_берёт_своё_значение():
    assert db._sqlite_default(Column("x", String(), default=_Kind.regular)) == "'regular'"


def test_догонялка_идемпотентна():
    # Второй прогон на уже догнанной базе не должен ничего делать и не должен падать.
    db._migrate_sqlite_add_columns()
    db._migrate_sqlite_add_columns()


def test_инициализация_базы_повторяема():
    # init_db зовётся при каждом старте процесса; на нескольких воркерах — одновременно.
    db.init_db()
    db.init_db()


def test_сессия_отдаётся_и_закрывается():
    gen = db.get_session()
    session = next(gen)
    assert session is not None
    with pytest.raises(StopIteration):
        next(gen)
