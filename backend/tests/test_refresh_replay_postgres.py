"""Refresh recovery concurrency; run only against an isolated PostgreSQL DB."""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event

import pytest
from fastapi import HTTPException
from jose import JWTError
from sqlalchemy import text
from sqlmodel import Session, select

from app import security
from app.db import engine
from app.models import RefreshToken, User
from app.routers import auth


pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL"
)


def _connection(session):
    session.execute(text("SET lock_timeout = '5s'"))
    session.execute(text("SET statement_timeout = '10s'"))
    return session.execute(text("SELECT pg_backend_pid()")).scalar_one()


def test_recovery_migration_on_postgres_preserves_legacy_row():
    import importlib.util
    from pathlib import Path
    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    path = Path(__file__).parents[1] / 'alembic/versions/bv_refresh_recovery.py'
    spec = importlib.util.spec_from_file_location('pg_refresh_recovery_migration', path)
    migration = importlib.util.module_from_spec(spec); spec.loader.exec_module(migration)
    with engine.begin() as connection:
        # Temporary table shadows only on this connection; shared app table is untouched.
        connection.execute(text('CREATE TEMP TABLE refreshtoken (id INTEGER PRIMARY KEY, token_hash VARCHAR NOT NULL) ON COMMIT DROP'))
        connection.execute(text("INSERT INTO refreshtoken VALUES (1, 'legacy-hash')"))
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade(); migration.upgrade()
            assert connection.execute(text('SELECT token_hash, rotation_id_hash, rotated_at FROM refreshtoken')).one() == ('legacy-hash', None, None)
            migration.downgrade()
        assert connection.execute(text('SELECT token_hash FROM refreshtoken')).scalar_one() == 'legacy-hash'


@pytest.mark.parametrize('first', ['login', 'logout'])
def test_initial_issuance_and_logout_obey_the_same_lock_order(client, user_factory, monkeypatch, first):
    user = user_factory('PgLoginLogout')
    acquired, contender = Event(), Event()
    original_lock = security.lock_refresh_user

    def ordered_lock(session, user_id):
        operation = session.info.get('initial_issuance_operation')
        if operation == first:
            owner = original_lock(session, user_id)
            acquired.set()
            assert contender.wait(timeout=4), 'second operation did not use shared lock'
            return owner
        assert acquired.wait(timeout=4)
        contender.set()
        return original_lock(session, user_id)

    monkeypatch.setattr(security, 'lock_refresh_user', ordered_lock)
    monkeypatch.setattr(auth, 'lock_refresh_user', ordered_lock)

    def worker(operation):
        with Session(engine) as session:
            pid = _connection(session)
            session.info['initial_issuance_operation'] = operation
            if operation == 'login':
                return pid, security.issue_tokens(session, user['id'])
            owner = session.get(User, user['id'])
            assert auth.logout(user=owner, session=session) == {'ok': True}
            return pid, None

    with ThreadPoolExecutor(max_workers=2) as pool:
        leader = pool.submit(worker, first)
        assert acquired.wait(timeout=4), 'initial issuance did not acquire shared user lock'
        follower = pool.submit(worker, 'logout' if first == 'login' else 'login')
        results = [leader.result(timeout=20), follower.result(timeout=20)]
    assert len({r[0] for r in results}) == 2
    pair = next(r[1] for r in results if r[1] is not None)
    with Session(engine) as session:
        rows = session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all()
        assert len(rows) == 1
        assert rows[0].revoked == (first == 'login')
        if first == 'login':
            with pytest.raises(JWTError):
                security.authenticate_ws(pair['access_token'], session)
        else:
            assert security.authenticate_ws(pair['access_token'], session).id == user['id']


@pytest.mark.parametrize("same_nonce", [True, False])
def test_parallel_recovery_intents_have_one_child(client, user_factory, same_nonce):
    user = user_factory("PgReplay")
    with Session(engine) as session:
        raw = security.issue_tokens(session, user["id"])["refresh_token"]
        before = len(session.exec(select(RefreshToken).where(
            RefreshToken.user_id == user["id"]
        )).all())
    barrier = Barrier(2)

    def worker(nonce):
        with Session(engine) as session:
            pid = _connection(session)
            barrier.wait(timeout=10)
            try:
                pair = security.rotate_refresh(session, raw, rotation_id=nonce)
                return pid, 200, pair
            except HTTPException as exc:
                assert exc.status_code == 401
                return pid, exc.status_code, None

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker, nonce) for nonce in (
            "a" * 64, ("a" if same_nonce else "b") * 64
        )]
        results = [future.result(timeout=20) for future in futures]
    assert len({result[0] for result in results}) == 2
    assert sorted(result[1] for result in results) == (
        [200, 200] if same_nonce else [200, 401]
    )
    winners = [result[2] for result in results if result[1] == 200]
    assert len({pair["refresh_token"] for pair in winners}) == 1
    with Session(engine) as session:
        rows = session.exec(select(RefreshToken).where(
            RefreshToken.user_id == user["id"]
        )).all()
        assert len(rows) == before + 1
        assert next(row for row in rows if row.token_hash == security._hash_refresh(raw)).revoked
        active = [row for row in rows if not row.revoked]
        assert len(active) == 1
        assert active[0].token_hash == security._hash_refresh(winners[0]["refresh_token"])
        for pair in winners:
            assert security.authenticate_ws(pair["access_token"], session).id == user["id"]


@pytest.mark.parametrize("recover", [False, True], ids=["rotate", "recover"])
@pytest.mark.parametrize("first", ["refresh", "logout"])
def test_logout_serializes_with_refresh_in_both_orders(
    client, user_factory, monkeypatch, recover, first
):
    user = user_factory("PgReplayLogout")
    nonce = "c" * 64
    with Session(engine) as session:
        initial = security.issue_tokens(session, user["id"])
        raw = initial["refresh_token"]
        if recover:
            security.rotate_refresh(session, raw, rotation_id=nonce)

    acquired, contender_entered = Event(), Event()
    real_lock = security.lock_refresh_user

    def controlled_lock(session, user_id):
        operation = session.info.get("replay_test_operation")
        if operation == first:
            result = real_lock(session, user_id)
            acquired.set()
            assert contender_entered.wait(timeout=4), "second connection did not attempt lock"
            return result
        if operation in {"refresh", "logout"}:
            assert acquired.wait(timeout=4), "first connection did not acquire user lock"
            contender_entered.set()
        return real_lock(session, user_id)

    monkeypatch.setattr(security, "lock_refresh_user", controlled_lock)
    # Support either a module-level import or a local security helper import in auth.
    if hasattr(auth, "lock_refresh_user"):
        monkeypatch.setattr(auth, "lock_refresh_user", controlled_lock)

    def worker(operation):
        with Session(engine) as session:
            pid = _connection(session)
            session.info["replay_test_operation"] = operation
            if operation == "logout":
                owner = session.get(User, user["id"])
                assert owner is not None
                assert auth.logout(user=owner, session=session) == {"ok": True}
                return operation, pid, 200, None
            try:
                pair = security.rotate_refresh(session, raw, rotation_id=nonce)
                return operation, pid, 200, pair
            except HTTPException as exc:
                assert exc.status_code == 401
                return operation, pid, exc.status_code, None

    second = "logout" if first == "refresh" else "refresh"
    with ThreadPoolExecutor(max_workers=2) as pool:
        leading = pool.submit(worker, first)
        assert acquired.wait(timeout=4), "first operation did not use shared user lock"
        following = pool.submit(worker, second)
        results = [leading.result(timeout=20), following.result(timeout=20)]
    assert len({result[1] for result in results}) == 2
    by_operation = {result[0]: result for result in results}
    assert by_operation["logout"][2] == 200
    assert by_operation["refresh"][2] == (200 if first == "refresh" else 401)

    with Session(engine) as session:
        rows = session.exec(select(RefreshToken).where(
            RefreshToken.user_id == user["id"]
        )).all()
        assert rows and all(row.revoked for row in rows)
        access_tokens = [initial["access_token"]]
        pair = by_operation["refresh"][3]
        if pair:
            access_tokens.append(pair["access_token"])
        for access in access_tokens:
            with pytest.raises(JWTError):
                security.authenticate_ws(access, session)
        with pytest.raises(HTTPException) as denied:
            security.rotate_refresh(session, raw, rotation_id=nonce)
        assert denied.value.status_code == 401
