"""Bounded recovery of a committed refresh response; legacy replay stays denied."""
from datetime import timedelta
import pytest
from sqlmodel import Session, select
from app import security
from app.db import engine
from app.models import RefreshToken, User
from app.timeutil import utcnow

NONCE = 'a1' * 32

@pytest.fixture
def issued(client, user_factory):
    user = user_factory('Refresh recovery')
    with Session(engine) as session:
        raw = security.issue_tokens(session, user['id'])['refresh_token']
    return user, raw

def rotate(client, raw, nonce=NONCE, **kwargs):
    body = {'refresh_token': raw}
    if nonce is not None:
        body['rotation_id'] = nonce
    return client.post('/auth/refresh', json=body, **kwargs)

def test_same_attempt_recovers_one_child_without_extending_window(client, issued):
    user, raw = issued
    first = rotate(client, raw)
    assert first.status_code == 200
    with Session(engine) as session:
        parent = session.exec(select(RefreshToken).where(RefreshToken.token_hash == security._hash_refresh(raw))).one()
        rotated_at = parent.rotated_at
        expiry_before = {row.id: row.expires_at for row in session.exec(
            select(RefreshToken).where(RefreshToken.user_id == user['id'])).all()}
    second = rotate(client, raw)
    assert second.status_code == 200, second.text
    assert second.json()['refresh_token'] == first.json()['refresh_token']
    assert client.get('/me', headers={'Authorization': 'Bearer ' + second.json()['access_token']}).status_code == 200
    with Session(engine) as session:
        rows = session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all()
        assert len(rows) == 2
        parent = next(r for r in rows if r.token_hash == security._hash_refresh(raw))
        assert parent.rotated_at == rotated_at
        assert parent.rotation_id_hash == security._hash_refresh(NONCE)
        assert sum(not r.revoked for r in rows) == 1
        assert {row.id: row.expires_at for row in rows} == expiry_before

@pytest.mark.parametrize('nonce', [None, 'b2' * 32])
def test_other_attempt_cannot_recover(client, issued, nonce):
    _, raw = issued
    assert rotate(client, raw).status_code == 200
    assert rotate(client, raw, nonce).status_code == 401

@pytest.mark.parametrize('nonce', ['', 'a' * 63, 'A' * 64, 'g' * 64, 123])
def test_invalid_attempt_is_rejected_before_rotation(client, issued, nonce):
    _, raw = issued
    assert rotate(client, raw, nonce).status_code == 422
    assert rotate(client, raw).status_code == 200

@pytest.mark.parametrize('age,expected', [(119, 200), (120, 200), (121, 401), (-1, 401)])
def test_recovery_window_boundary(client, issued, monkeypatch, age, expected):
    _, raw = issued
    assert rotate(client, raw).status_code == 200
    with Session(engine) as session:
        parent = session.exec(select(RefreshToken).where(RefreshToken.token_hash == security._hash_refresh(raw))).one()
        moment = parent.rotated_at
    monkeypatch.setattr(security, 'utcnow', lambda: moment + timedelta(seconds=age))
    assert rotate(client, raw).status_code == expected

@pytest.mark.parametrize('expired', ['parent', 'child'])
def test_expired_tokens_cannot_recover(client, issued, expired):
    _, raw = issued
    child = rotate(client, raw).json()['refresh_token']
    with Session(engine) as session:
        target = raw if expired == 'parent' else child
        row = session.exec(select(RefreshToken).where(RefreshToken.token_hash == security._hash_refresh(target))).one()
        row.expires_at = utcnow() - timedelta(seconds=1)
        session.add(row); session.commit()
    assert rotate(client, raw).status_code == 401

def test_rotated_child_cannot_be_recovered_again(client, issued):
    _, raw = issued
    child = rotate(client, raw).json()['refresh_token']
    assert rotate(client, child, 'b2' * 32).status_code == 200
    assert rotate(client, raw).status_code == 401

def test_logout_revokes_recovery_and_child(client, issued):
    user, raw = issued
    child = rotate(client, raw).json()['refresh_token']
    assert client.post('/auth/logout', headers=user['auth']).status_code == 200
    assert rotate(client, raw).status_code == 401
    assert rotate(client, child).status_code == 401

@pytest.mark.parametrize('source', ['stored', 'header'])
def test_banned_device_cannot_recover(client, issued, source):
    from app.antifraud import ban_device
    user, raw = issued
    assert rotate(client, raw).status_code == 200
    with Session(engine) as session:
        ban_device(session, 'qa-recovery-banned', 'test')
        if source == 'stored':
            account = session.get(User, user['id']); account.last_device_id = 'qa-recovery-banned'
            session.add(account); session.commit()
    headers = {'X-Device-Id': 'qa-recovery-banned'} if source == 'header' else {}
    assert rotate(client, raw, headers=headers).status_code == 403

@pytest.mark.parametrize('stage', ['sign', 'commit'])
def test_failed_issuance_rolls_back_attempt_metadata(client, issued, monkeypatch, stage):
    user, raw = issued
    def fail(*args, **kwargs):
        raise RuntimeError('injected recovery failure')
    with monkeypatch.context() as patch:
        with Session(engine) as session:
            if stage == 'sign': patch.setattr(security, 'make_token', fail)
            else: patch.setattr(session, 'commit', fail)
            with pytest.raises(RuntimeError, match='injected'):
                security.rotate_refresh(session, raw, rotation_id=NONCE)
    with Session(engine) as session:
        parent = session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).one()
        assert not parent.revoked
        assert parent.rotated_at is None and parent.rotation_id_hash is None
    assert rotate(client, raw).status_code == 200
    assert rotate(client, raw).status_code == 200


def test_cleanup_preserves_old_parent_for_full_recovery_window(client, issued):
    from sqlalchemy import text
    from app.cleanup import _rules
    _, raw = issued
    with Session(engine) as session:
        parent = session.exec(select(RefreshToken).where(RefreshToken.token_hash == security._hash_refresh(raw))).one()
        parent.created_at = utcnow() - timedelta(days=7)
        session.add(parent); session.commit()
    assert rotate(client, raw).status_code == 200
    with Session(engine) as session:
        parent = session.exec(select(RefreshToken).where(RefreshToken.token_hash == security._hash_refresh(raw))).one()
        rotated_at = parent.rotated_at
        for seconds, deleted in [(120, 0), (121, 1)]:
            _, table, predicate, params = next(r for r in _rules(rotated_at + timedelta(seconds=seconds)) if r[1] == 'refreshtoken')
            result = session.execute(text(f'DELETE FROM {table} WHERE {predicate}'), params)
            assert result.rowcount == deleted
            session.commit()
            if seconds == 120:
                assert rotate(client, raw).status_code == 200


def test_recovery_migration_preserves_legacy_tokens():
    import importlib.util
    from pathlib import Path
    from sqlalchemy import create_engine, inspect, text
    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    path = Path(__file__).parents[1] / 'alembic/versions/bv_refresh_recovery.py'
    spec = importlib.util.spec_from_file_location('refresh_recovery_migration', path)
    migration = importlib.util.module_from_spec(spec); spec.loader.exec_module(migration)
    db = create_engine('sqlite://')
    with db.begin() as connection:
        connection.execute(text('CREATE TABLE refreshtoken (id INTEGER PRIMARY KEY, token_hash VARCHAR NOT NULL)'))
        connection.execute(text("INSERT INTO refreshtoken VALUES (1, 'legacy-hash')"))
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade(); migration.upgrade()
            assert connection.execute(text('SELECT token_hash, rotation_id_hash, rotated_at FROM refreshtoken')).one() == ('legacy-hash', None, None)
            migration.downgrade()
        assert {c['name'] for c in inspect(connection).get_columns('refreshtoken')} == {'id', 'token_hash'}
        assert connection.execute(text('SELECT token_hash FROM refreshtoken')).scalar_one() == 'legacy-hash'
    db.dispose()
