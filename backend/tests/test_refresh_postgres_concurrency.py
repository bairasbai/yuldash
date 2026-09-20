"""Run only against an isolated PostgreSQL database."""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Lock
import pytest
from fastapi import HTTPException
from sqlalchemy import text
from sqlmodel import Session, select
from app import security
from app.db import engine
from app.models import RefreshToken

pytestmark = pytest.mark.skipif(engine.dialect.name != 'postgresql', reason='requires isolated PostgreSQL')

@pytest.mark.parametrize('fail_first', [False, True])
def test_two_connections_rotate_once_even_if_first_issuer_fails(client, user_factory, monkeypatch, fail_first):
    user = user_factory('PgRotate')
    with Session(engine) as session:
        raw = security.issue_tokens(session, user['id'])['refresh_token']
        before = len(session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all())
    real_random = security.secrets.token_urlsafe
    guard, barrier = Lock(), Barrier(2)
    failed = False
    def maybe_fail(*args, **kwargs):
        nonlocal failed
        with guard:
            if fail_first and not failed:
                failed = True
                raise RuntimeError('injected first issuer failure')
        return real_random(*args, **kwargs)
    monkeypatch.setattr(security.secrets, 'token_urlsafe', maybe_fail)
    def worker():
        with Session(engine) as session:
            session.execute(text("SET lock_timeout = '5s'"))
            session.execute(text("SET statement_timeout = '10s'"))
            pid = session.execute(text('SELECT pg_backend_pid()')).scalar_one()
            barrier.wait(timeout=10)
            try:
                pair = security.rotate_refresh(session, raw)
                return pid, 200, pair['refresh_token']
            except HTTPException as error:
                return pid, error.status_code, None
            except RuntimeError as error:
                assert str(error) == 'injected first issuer failure'
                return pid, 500, None
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker) for _ in range(2)]
        results = [f.result(timeout=20) for f in futures]
    print(f'PG rotate fail_first={fail_first}: pids={[r[0] for r in results]}, statuses={[r[1] for r in results]}')
    assert len({r[0] for r in results}) == 2
    assert sorted(r[1] for r in results) == ([200, 500] if fail_first else [200, 401])
    with Session(engine) as session:
        rows = session.exec(select(RefreshToken).where(RefreshToken.user_id == user['id'])).all()
        assert len(rows) == before + 1
        assert next(r for r in rows if r.token_hash == security._hash_refresh(raw)).revoked
        winner = next(r[2] for r in results if r[1] == 200)
        assert not next(r for r in rows if r.token_hash == security._hash_refresh(winner)).revoked
