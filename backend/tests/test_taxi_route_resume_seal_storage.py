"""Inert same-row SQL storage; synthetic private SQLite only."""
from datetime import timedelta
from uuid import uuid4

import pytest
from alembic import command
from alembic.script import ScriptDirectory
from sqlalchemy import MetaData, event, inspect, select
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, create_engine

from app import taxi_marked_offer_card as card, taxi_route_resume as resume
from app.models import InstantOrder, InstantOrderStatus as S, Message, User
from app.taxi_offered_waypoint_preview import preview_offered_waypoints
from app.timeutil import utcnow
from test_migrations_match_the_models import _config
from test_taxi_offered_waypoint_preview import _mutate, _row, _STOP, _unexpected, testdb  # noqa: F401

_PARENT = "de_taxi_route_edit_outcome"
_SEAL_REV = "df_taxi_route_resume_seal"
_DRIVER_OUTCOME_REV = "dg_taxi_driver_accept_outcome"
_NAME = resume._SEAL
_VALUE = b"\x91" * 32
_DROP = f"ALTER TABLE instantorder DROP COLUMN {_NAME}"
_TABLE = InstantOrder.__table__


@pytest.fixture
def database(tmp_path, monkeypatch):
    url = f"sqlite:///{tmp_path / 'actual-seal-migration.sqlite'}"
    monkeypatch.setenv("DATABASE_URL", url)
    db = create_engine(url, hide_parameters=True)
    try:
        yield db, _config(url)
    finally:
        db.dispose()


def _history(db, cfg):
    # Actual Alembic chain through the parent; its initial baseline imports
    # today's metadata. Restore the exact historical absent-column shape.
    command.upgrade(cfg, _PARENT)
    with db.begin() as conn:
        conn.exec_driver_sql(_DROP)
    assert _NAME not in _names(db)


def _names(db):
    return {c["name"] for c in inspect(db).get_columns("instantorder")}


def _version(db):
    with db.connect() as conn:
        return conn.exec_driver_sql("SELECT version_num FROM alembic_version").scalar_one()


def test_fresh_bootstrap_head_metadata_and_existing_exact_upgrade(database):
    db, cfg = database
    script = ScriptDirectory.from_config(cfg)
    head, = script.get_heads()  # refuse multiple heads, allow later descendants
    revisions = {revision.revision for revision in script.iterate_revisions(head, _PARENT)}
    assert {_SEAL_REV, _DRIVER_OUTCOME_REV} <= revisions
    assert script.get_revision(_DRIVER_OUTCOME_REV).down_revision == _SEAL_REV
    command.upgrade(cfg, "head")
    assert _NAME in _names(db) and _version(db) == head
    assert inspect(db).has_table("taxidriveracceptoutcome")
    assert len([c for c in inspect(db).get_check_constraints("instantorder")
                if c["name"] == "ck_instantorder_route_resume_seal_len"]) == 1


def test_upgrade_downgrade_historical_parent_and_private_storage(database):
    db, cfg = database
    _history(db, cfg)
    with Session(db) as s:
        user = User(phone="+79999005301")
        s.add(user)
        s.flush()
        owner = user.id
        s.commit()
    with db.begin() as conn:
        # Generate INSERT from exact model defaults except the new column.
        # This preserves unrelated NOT NULL values and models an old writer.
        legacy_table = _TABLE.to_metadata(MetaData())
        legacy_table._columns.remove(legacy_table.c[_NAME])
        oid = conn.execute(legacy_table.insert().values(passenger_id=owner)
                           .returning(legacy_table.c.id)).scalar_one()
    with Session(db) as s:
        s.add(Message(sender_id=owner, order_id=oid, text="synthetic referenced order"))
        s.commit()
    command.upgrade(cfg, _SEAL_REV)
    with Session(db) as s:
        order = s.get(InstantOrder, oid)
        assert order.route_resume_agreement_seal is None
        order.route_resume_agreement_seal = _VALUE
        s.commit()
        s.refresh(order)
        assert order.route_resume_agreement_seal == _VALUE
        assert _NAME not in repr(order) and _NAME not in order.model_dump()
        assert _NAME not in order.model_dump_json()
    with pytest.raises(RuntimeError, match="Drain taxi retirement agreement seals"):
        command.downgrade(cfg, _PARENT)
    assert _version(db) == _SEAL_REV
    with db.begin() as conn:
        assert conn.execute(select(_TABLE.c[_NAME])).scalar_one() == _VALUE
        conn.execute(_TABLE.update().where(_TABLE.c.id == oid).values({_NAME: None}))
    command.downgrade(cfg, _PARENT)
    assert _NAME not in _names(db) and _version(db) == _PARENT
    with db.connect() as conn:
        assert conn.exec_driver_sql("SELECT order_id FROM message").scalar_one() == oid
        assert conn.exec_driver_sql("PRAGMA foreign_key_check").all() == []


@pytest.mark.parametrize("direction", ("upgrade", "downgrade"))
def test_actual_sqlite_ddl_rollback_and_stamp_restart(database, direction):
    db, cfg = database
    _history(db, cfg)
    if direction == "downgrade":
        command.upgrade(cfg, _SEAL_REV)
    previous = _version(db)
    present = _NAME in _names(db)
    seen = []

    class Interrupted(Exception):
        pass

    def inject(conn, cursor, statement, parameters, context, executemany):
        fragment = f"{'ADD' if direction == 'upgrade' else 'DROP'} COLUMN {_NAME}"
        if "ALTER TABLE instantorder" in statement and fragment in statement:
            # Inject after genuine DDL, inside the SQLAlchemy/SQLite writer.
            assert conn.connection.driver_connection.in_transaction
            seen.append(fragment)
            raise Interrupted

    target = _SEAL_REV if direction == "upgrade" else _PARENT
    op = getattr(command, direction)
    event.listen(Engine, "after_cursor_execute", inject)
    try:
        with pytest.raises(Interrupted):
            op(cfg, target)
    finally:
        event.remove(Engine, "after_cursor_execute", inject)
    assert len(seen) == 1 and (_NAME in _names(db)) is present and _version(db) == previous
    op(cfg, target)
    assert (_NAME in _names(db)) is not present and _version(db) == target
    # Completed physical DDL with the earlier Alembic stamp, both directions.
    with db.begin() as conn:
        conn.exec_driver_sql("UPDATE alembic_version SET version_num=?", (previous,))
    op(cfg, target)
    assert (_NAME in _names(db)) is not present and _version(db) == target


@pytest.mark.parametrize("mode", ("missing_check", "misdeclared", "extra_index", "expression_index"))
def test_foreign_or_partial_prestamp_shape_is_not_adopted_or_removed(database, mode):
    db, cfg = database
    _history(db, cfg)
    with db.begin() as conn:
        if mode in ("extra_index", "expression_index"):
            conn.exec_driver_sql(
                f"ALTER TABLE instantorder ADD COLUMN {_NAME} BLOB "
                f"CONSTRAINT ck_instantorder_route_resume_seal_len CHECK (length({_NAME}) = 32)",
            )
            index_key = _NAME if mode == "extra_index" else f'length("{_NAME}")'
            conn.exec_driver_sql(f"CREATE INDEX unrelated_seal_index ON instantorder({index_key})")
        elif mode == "misdeclared":
            conn.exec_driver_sql(
                f"ALTER TABLE instantorder ADD COLUMN {_NAME} TEXT "
                f"CONSTRAINT ck_instantorder_route_resume_seal_len CHECK (length({_NAME}) = 32)",
            )
        else:
            conn.exec_driver_sql(f"ALTER TABLE instantorder ADD COLUMN {_NAME} BLOB")
    def schema_snapshot():
        with db.connect() as conn:
            return conn.exec_driver_sql(
                "SELECT type,name,sql FROM sqlite_schema WHERE tbl_name='instantorder' ORDER BY type,name",
            ).all()

    actual = schema_snapshot()
    with pytest.raises(RuntimeError, match="Unexpected taxi retirement seal schema"):
        command.upgrade(cfg, _SEAL_REV)
    assert _version(db) == _PARENT and _NAME in _names(db)
    with db.begin() as conn:
        conn.exec_driver_sql("UPDATE alembic_version SET version_num=?", (_SEAL_REV,))
    with pytest.raises(RuntimeError, match="Unexpected taxi retirement seal schema"):
        command.downgrade(cfg, _PARENT)
    assert _version(db) == _SEAL_REV and _NAME in _names(db) and schema_snapshot() == actual


def test_seal_only_is_neither_ordinary_available_nor_complete_capped(request, monkeypatch):
    db, owner, oid, driver, *_ = request.getfixturevalue("testdb")
    _mutate(db, oid, **{_NAME: _VALUE})
    before = _row(db, oid)
    monkeypatch.setattr("app.instant_service.destination_quote", _unexpected)
    assert preview_offered_waypoints(
        db, owner_id=owner, order_id=oid, requested=[_STOP],
        quote_valid_until=utcnow() + timedelta(seconds=20), guard_budget=_unexpected,
    ) is None
    assert resume.observe(db, oid) is None
    with db.connect() as conn:
        assert card.bind_current_card(conn, b"\xb0" * 32, owner_id=owner, order_id=oid, driver_id=driver) is None
    assert _row(db, oid) == before


def test_lab_refuses_sealed_claim_offer_card_but_owned_expiry_clears_all(request):
    db, owner, oid, previous, successor, *_ = request.getfixturevalue("testdb")
    at = utcnow()
    _mutate(db, oid, status=S.searching, current_offer_driver_id=None, offer_expires_at=None,
            searching_at=at-timedelta(seconds=150), offered_at=at-timedelta(seconds=140),
            route_resume_id=uuid4().hex, route_resume_previous_driver_id=previous,
            route_resume_retired_at=at-timedelta(seconds=4), route_resume_ready_at=at-timedelta(seconds=2),
            route_resume_deadline_at=at+timedelta(seconds=116), **{_NAME: _VALUE})
    assert resume.claim(db, resume.observe(db, oid), lease_seconds=20) is None
    _mutate(db, oid, route_resume_claim_id=uuid4().hex, route_resume_ready_at=at+timedelta(seconds=20))
    assert resume.store_offer(db, resume.observe(db, oid), driver_id=successor, offer_seconds=20) is None
    _mutate(db, oid, status=S.offered, search_round=2, current_offer_driver_id=successor,
            offered_at=at, offer_expires_at=at+timedelta(seconds=20),
            destination_changed_at=at-timedelta(seconds=4), route_resume_claim_id=None, route_resume_ready_at=None)
    with db.connect() as conn:
        assert card.bind_current_card(conn, b"\xb0" * 32, owner_id=owner, order_id=oid,
                                      driver_id=successor) is None
    _mutate(db, oid, offered_at=at-timedelta(seconds=8), offer_expires_at=at-timedelta(seconds=3),
            route_resume_retired_at=at-timedelta(seconds=123), route_resume_deadline_at=at-timedelta(seconds=3))
    stale = resume.observe(db, oid)
    _mutate(db, oid, **{_NAME: b"\x93" * 32})
    assert resume.store_timeout(db, stale) is None  # exact old seal participates in CAS
    assert _row(db, oid).status is S.offered
    done = resume.store_timeout(db, resume.observe(db, oid))
    assert done is not None
    final = resume._state(done)
    assert final["status"] is S.cancelled and all(final[n] is None for n in resume._MARKERS)


def test_sqlite_length_constraint_and_explicit_blob_type_contract(request):
    db, _, oid, _, _, *_ = request.getfixturevalue("testdb")
    for value in (b"", b"\x14" * 31, b"\x15" * 33):
        with pytest.raises(IntegrityError, match="ck_instantorder_route_resume_seal_len"):
            _mutate(db, oid, **{_NAME: value})
    with db.begin() as conn:
        conn.exec_driver_sql(f"UPDATE instantorder SET {_NAME}=? WHERE id=?", ("t" * 32, oid))
        seen = conn.exec_driver_sql(f"SELECT {_NAME}, typeof({_NAME}) FROM instantorder WHERE id=?",
                                    (oid,)).one()
    # The portable constraint is length only. The app must validate bytes,
    # never coerce the 32-character text into trusted provenance.
    assert seen[1] == "text"
    assert not resume._seal_shape(seen[0])
    assert resume._seal_shape(_VALUE) and resume._seal_shape(None)


def test_real_dev_boot_on_historical_parent_produces_exact_alembic_shape(database, monkeypatch):
    from app import db as appdb
    db, cfg = database
    _history(db, cfg)
    with Session(db) as s:
        user = User(phone="+79999005302")
        s.add(user)
        s.flush()
        owner = user.id
        s.commit()
    with db.begin() as conn:
        legacy_table = _TABLE.to_metadata(MetaData())
        legacy_table._columns.remove(legacy_table.c[_NAME])
        oid = conn.execute(legacy_table.insert().values(passenger_id=owner, to_text="Synthetic old route")
                           .returning(legacy_table.c.id)).scalar_one()
    monkeypatch.setattr(appdb, "engine", db)
    monkeypatch.setattr(appdb.settings, "database_url", db.url.render_as_string(hide_password=False))
    appdb.init_db()
    appdb.init_db()
    assert _version(db) == _PARENT
    column, = [c for c in inspect(db).get_columns("instantorder") if c["name"] == _NAME]
    assert column["nullable"] and column["default"] is None
    check, = [c for c in inspect(db).get_check_constraints("instantorder")
              if c["name"] == "ck_instantorder_route_resume_seal_len"]
    assert check["sqltext"] == f"length({_NAME}) = 32"
    command.upgrade(cfg, _SEAL_REV)
    appdb.init_db()
    assert _version(db) == _SEAL_REV
    with Session(db) as s:
        order = s.get(InstantOrder, oid)
        assert order.to_text == "Synthetic old route" and order.route_resume_agreement_seal is None
