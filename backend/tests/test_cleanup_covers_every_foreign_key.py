"""Сторож ночной чистки: каждое правило обязано учитывать ВСЕ ссылки на свою таблицу
(аудит 2026-08-07).

Что было не так. Ночная чистка удаляет старые брони, поездки, такси-заказы и доставки
пакетами по 2000 строк. Чтобы удаление не упало по внешнему ключу, у правила стоит список
проверок «на эту строку никто не ссылается». Список вели руками — и он отстал от базы:

* у броней не хватало трёх ссылок — заработок водителя, платёж и жалоба;
* у такси-заказов — спора;
* у доставок — спора и публичной ссылки «следить за курьером».

Чем это плохо. Одна старая бронь с заработком роняет ВЕСЬ пакет: удалено 0 строк, в лог
падает ошибка внешнего ключа. Следующей ночью пакет собирается тем же условием — и в него
попадает та же самая бронь. Чистка встаёт **навсегда и молча**: снаружи это выглядит как
«таблицы просто растут», а не как поломка.

Почему тест такой. Список ссылок он НЕ хранит — берёт его из схемы (`SQLModel.metadata`)
и сверяет с текстом правила. Появится новая таблица со ссылкой на бронь — тест покраснеет
сам, без правки этого файла. Ровно так же устроен сторож админских ручек
(`test_admin_gate_everywhere.py`): проверять надо форму, а не список, иначе сторож
устаревает вместе с тем, что сторожит.
"""
from __future__ import annotations

import re

import pytest
from sqlmodel import SQLModel

import app.models  # noqa: F401 — регистрирует таблицы в metadata
from app.cleanup import _rules
from app.timeutil import utcnow


def _referencing_columns(table: str) -> set[tuple[str, str]]:
    """Все (таблица, колонка), которые ссылаются на `table` внешним ключом."""
    found = set()
    for tname, tbl in SQLModel.metadata.tables.items():
        for col in tbl.columns:
            for fk in col.foreign_keys:
                if fk.column.table.name == table:
                    found.add((tname, col.name))
    return found


# Правила-«родители»: удаляют строку, на которую могут ссылаться другие таблицы.
# Эфемерное (сообщения, коды, токены) сюда не входит — на него никто не ссылается.
_PARENT_TABLES = ("booking", "ride", "instantorder", "parceldelivery", "riderequest")


@pytest.mark.parametrize("table", _PARENT_TABLES)
def test_rule_guards_every_foreign_key(table: str):
    """У правила чистки должна быть проверка на КАЖДУЮ ссылку — иначе пакет упадёт."""
    rules = [r for r in _rules(utcnow()) if r[1] == table]
    if not rules:
        pytest.skip(f"правила чистки для {table} нет — удалять нечего")

    where = " ".join(r[2] for r in rules)
    missing = []
    for ref_table, ref_col in sorted(_referencing_columns(table)):
        # Ищем упоминание пары «таблица.колонка» в любом NOT EXISTS этого правила:
        # алиас произвольный, поэтому цепляемся за FROM <таблица> и за <алиас>.<колонка>.
        pattern = rf"FROM\s+{ref_table}\s+(\w+)\s+WHERE\s+\1\.{ref_col}\s*="
        if not re.search(pattern, where, re.IGNORECASE):
            missing.append(f"{ref_table}.{ref_col}")

    assert not missing, (
        f"правило чистки таблицы «{table}» не проверяет ссылки: {', '.join(missing)}.\n"
        f"Одна такая строка роняет весь пакет удаления по внешнему ключу — и роняет его\n"
        f"каждую ночь заново, потому что пакет собирается тем же условием. Чистка встаёт\n"
        f"навсегда и молча. Добавь в правило NOT EXISTS на каждую ссылку."
    )


def test_every_cleaned_table_is_whitelisted():
    """SQL собирается f-строкой по имени таблицы — имя обязано быть из белого списка."""
    from app.cleanup import _ALLOWED_TABLES
    for _label, table, _where, _params in _rules(utcnow()):
        assert table in _ALLOWED_TABLES, f"таблица {table} не в белом списке _ALLOWED_TABLES"


def test_children_are_cleaned_before_parents():
    """Порядок правил важен: ребёнок раньше родителя, иначе удаление родителя упадёт."""
    order = [table for _label, table, _where, _params in _rules(utcnow())]
    for parent in _PARENT_TABLES:
        if parent not in order:
            continue
        parent_at = order.index(parent)
        for child_table, _col in _referencing_columns(parent):
            if child_table in order:
                assert order.index(child_table) < parent_at, (
                    f"«{child_table}» чистится ПОСЛЕ «{parent}» — удаление родителя упадёт "
                    f"на внешнем ключе живого ребёнка"
                )
