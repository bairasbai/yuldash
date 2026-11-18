"""Negative controls for the one inactive history ownership/PK exception."""
from copy import deepcopy

import pytest
from sqlalchemy import Column, ForeignKey, Integer, MetaData, PrimaryKeyConstraint, String, Table

from app.cleanup import KEEP_FOREVER, OWNED_RETENTION
from test_cleanup_covers_every_foreign_key import _validated_owned_retention
from test_service_survives_outside_failures import _history_primary_key_supports_set_lookup


def _metadata(action="CASCADE", parent="instantorder.id", include_fk=True):
    md = MetaData()
    Table("instantorder", md, Column("id", Integer, primary_key=True))
    Table("otherorder", md, Column("id", Integer, primary_key=True))
    args = [ForeignKey(parent, ondelete=action)] if include_fk else []
    Table("instanttriedexclusion", md, Column("order_id", Integer, *args))
    return md


@pytest.mark.parametrize("kwargs", [{"action": "RESTRICT"}, {"action": None},
                                     {"parent": "otherorder.id"}, {"include_fk": False}])
def test_owned_retention_rejects_missing_or_wrong_cascade(kwargs):
    with pytest.raises(AssertionError):
        _validated_owned_retention(_metadata(**kwargs), OWNED_RETENTION)


def test_registry_cannot_redirect_parent_or_add_generic_cascade_exception():
    registry = deepcopy(OWNED_RETENTION)
    registry["instanttriedexclusion.order_id"]["parent"] = "otherorder.id"
    with pytest.raises(AssertionError):
        _validated_owned_retention(_metadata(parent="otherorder.id"), registry)
    registry = {"anotherchild.order_id": next(iter(OWNED_RETENTION.values()))}
    with pytest.raises(AssertionError):
        _validated_owned_retention(_metadata(), registry)
    assert _validated_owned_retention(_metadata(), {}) == set()
    assert "instanttriedexclusion" not in KEEP_FOREVER


def test_actual_fk_guard_fails_if_ownership_registration_is_removed(monkeypatch):
    import test_cleanup_covers_every_foreign_key as guard
    monkeypatch.setattr(guard, "OWNED_RETENTION", {})
    with pytest.raises(AssertionError, match="instanttriedexclusion.order_id"):
        guard.test_rule_guards_every_foreign_key("instantorder")


def test_actual_retention_guard_fails_without_registered_decision(monkeypatch):
    import test_nothing_grows_forever as guard
    monkeypatch.setattr(guard, "OWNED_RETENTION", {})
    with pytest.raises(AssertionError, match="instanttriedexclusion"):
        guard.test_у_каждой_таблицы_есть_решение_о_сроке_хранения()


@pytest.mark.parametrize("columns", [(), ("driver_id", "order_id", "lifecycle"),
    ("lifecycle", "order_id", "driver_id"), ("order_id", "driver_id", "lifecycle"),
    ("order_id", "lifecycle"), ("order_id",)])
def test_history_lookup_requires_exact_unique_primary_key(columns):
    table = Table("instanttriedexclusion", MetaData(),
                  Column("order_id", Integer, nullable=False),
                  Column("lifecycle", String(32), nullable=False),
                  Column("driver_id", Integer, nullable=False), PrimaryKeyConstraint(*columns))
    assert not _history_primary_key_supports_set_lookup(table)


def test_history_lookup_rejects_nullable_or_missing_or_unnamed_table():
    assert not _history_primary_key_supports_set_lookup(None)
    for name, nullable in (("instanttriedexclusion", True), ("anotherchild", False)):
        table = Table(name, MetaData(), Column("order_id", Integer, primary_key=True, nullable=nullable),
                      Column("lifecycle", String(32), primary_key=True),
                      Column("driver_id", Integer, primary_key=True))
        assert not _history_primary_key_supports_set_lookup(table)
