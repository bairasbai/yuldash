import pytest
from fastapi import HTTPException
from sqlmodel import Session, select
from app import security
from app.db import engine
from app.models import RefreshToken


@pytest.mark.parametrize('stage', ['random', 'sign', 'commit'])
def test_failed_rotation_preserves_old_token_and_creates_no_orphan(client, user_factory, monkeypatch, stage):
    user = user_factory('RotationFailure' + stage)
    with Session(engine) as session:
        raw = security.issue_tokens(session, user['id'])['refresh_token']
        before = len(session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all())
    def fail(*args, **kwargs):
        raise RuntimeError('injected issuance failure')
    with monkeypatch.context() as patch:
        if stage == 'random': patch.setattr(security.secrets, 'token_urlsafe', fail)
        elif stage == 'sign': patch.setattr(security, 'make_token', fail)
        with Session(engine) as session:
            if stage == 'commit':
                original = session.commit
                def fail_replacement_commit():
                    if any(isinstance(obj, RefreshToken) for obj in session.new): fail()
                    original()
                patch.setattr(session, 'commit', fail_replacement_commit)
            with pytest.raises(RuntimeError, match='injected'):
                security.rotate_refresh(session, raw)
            old = session.exec(select(RefreshToken).where(RefreshToken.token_hash == security._hash_refresh(raw))).one()
            assert not old.revoked, 'failed rotation must roll back before returning to its caller'
    with Session(engine) as session:
        rows = session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all()
        old = next(row for row in rows if row.token_hash == security._hash_refresh(raw))
        assert not old.revoked, 'old token was burned without a usable replacement'
        assert len(rows) == before, 'failed issuance committed an orphan replacement'
        replacement = security.rotate_refresh(session, raw)
        assert replacement['refresh_token'] != raw
        with pytest.raises(HTTPException) as rejected:
            security.rotate_refresh(session, raw)
        assert rejected.value.status_code == 401


def test_initial_issuance_signing_failure_does_not_commit_orphan(client, user_factory, monkeypatch):
    user = user_factory('InitialSigningFailure')
    with Session(engine) as session:
        before = len(session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all())
    def fail(*args, **kwargs):
        raise RuntimeError('signing failed')
    monkeypatch.setattr(security, 'make_token', fail)
    with Session(engine) as session:
        with pytest.raises(RuntimeError, match='signing failed'):
            security.issue_tokens(session, user['id'])
    with Session(engine) as session:
        assert len(session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all()) == before


def test_lost_committed_rotation_response_is_not_recoverable_with_old_token(client, user_factory):
    """Legacy recovery limitation when the client sends no rotation_id.

    Discard the successful HTTP response as a disconnected client would. The
    database has committed, so retrying the client's only known token is 401.
    Preserve this one-time-token security contract: recovery requires the
    separately bound rotation_id; accepting any revoked token would be unsafe.
    """
    user = user_factory('LostCommittedRotation')
    with Session(engine) as session:
        raw = security.issue_tokens(session, user['id'])['refresh_token']
        initial_rows = session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all()
        before = len(initial_rows)
        active_before = sum(not row.revoked for row in initial_rows)

    # TestClient completes the server request; intentionally never consume its
    # token pair. This models lost delivery, not a live network interruption.
    assert client.post('/auth/refresh', json={'refresh_token': raw}).status_code == 200
    assert client.post('/auth/refresh', json={'refresh_token': raw}).status_code == 401

    with Session(engine) as session:
        rows = session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all()
        assert len(rows) == before + 1, 'retry must not create another successor'
        assert next(row for row in rows if row.token_hash == security._hash_refresh(raw)).revoked
        assert sum(not row.revoked for row in rows) == active_before
