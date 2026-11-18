"""Diagnostic only: real PG commit outcomes versus precommit fakeredis EXEC.

Run ONLY in the guarded disposable revision PostgreSQL runner. PASS observes
remaining cross-store limitations; no real Redis network or lost-ACK transport.
"""
import pytest
from sqlalchemy import event, text
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401

pytestmark = [
    pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires disposable PostgreSQL"),
    pytest.mark.usefixtures("fake_redis"),
]


@pytest.mark.parametrize("lifecycle", [None, "d" * 32])
@pytest.mark.parametrize("outcome", ["deferred_commit_failure", "committed_then_synthetic_error"])
def test_pg_known_boundary_tried_survives_both_commit_outcomes(client, user_factory, monkeypatch, lifecycle, outcome):
    passenger = user_factory("PGRollbackDiagnosticPassenger")
    driver = user_factory("PGRollbackDiagnosticDriver", role=UserRole.driver)
    did = driver["id"]
    oid = _order(passenger["id"], S.searching, search_revision=20, tried_lifecycle=lifecycle)
    redis = isv._redis()
    key = isv._tried_key(oid, lifecycle)
    notifications, eligible_inputs, geo_calls = [], [], []

    def geo(*args, **kwargs):
        geo_calls.append(kwargs["radius"])
        return [(f"driver:{did}", 0.1)]

    def eligible(session, ids, order):
        eligible_inputs.append(list(ids))
        return list(ids)

    # Keep actual candidates(), its exclusion check and actual Redis history.
    # Only external candidate supply/eligibility/scoring are isolated.
    monkeypatch.setattr(redis, "geosearch", geo)
    monkeypatch.setattr(isv, "presence_alive", lambda r, candidate: True)
    monkeypatch.setattr(isv, "eligible", eligible)
    monkeypatch.setattr(isv, "rank", lambda session, ids, dist: list(ids))
    monkeypatch.setattr(isv, "_push_offer", lambda *a, **k: notifications.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *a, **k: notifications.append("status"))

    assert not redis.exists(key)
    with Session(engine) as baseline:
        assert isv.candidates(redis, baseline, baseline.get(InstantOrder, oid), exclude=set()) == [did]
    assert eligible_inputs == [[did]], "positive control without exclusion must find the driver"
    eligible_inputs.clear()
    geo_calls.clear()
    try:
        with engine.connect() as connection:
            connection.execute(text("SET lock_timeout = '3s'"))
            connection.execute(text("SET statement_timeout = '5s'"))
            connection.execute(text("CREATE TEMP TABLE tried_commit_parent (id INTEGER PRIMARY KEY) ON COMMIT PRESERVE ROWS"))
            connection.execute(text("CREATE TEMP TABLE tried_commit_child (parent_id INTEGER REFERENCES tried_commit_parent(id) DEFERRABLE INITIALLY DEFERRED) ON COMMIT PRESERVE ROWS"))
            setup_pid = connection.execute(text("SELECT pg_backend_pid()")).scalar_one()
            connection.commit()  # TEMP setup is outside the matcher transaction.
            commit_observations = []

            def before_actual_commit(conn):
                assert redis.sismember(key, did), "real EXEC must be visible before actual COMMIT"
                assert 0 < redis.ttl(key) <= 3600
                commit_observations.append(True)

            event.listen(connection, "commit", before_actual_commit)
            try:
                with Session(bind=connection) as session:
                    assert session.execute(text("SELECT pg_backend_pid()")).scalar_one() == setup_pid
                    order = session.get(InstantOrder, oid)
                    if outcome == "deferred_commit_failure":
                        session.execute(text("INSERT INTO tried_commit_child(parent_id) VALUES (987654321)"))
                        # INSERT succeeded: the violation must be raised by the
                        # real COMMIT inside matcher, not immediate validation.
                        assert session.execute(text("SELECT count(*) FROM tried_commit_child")).scalar_one() == 1
                        with pytest.raises(IntegrityError) as failure:
                            isv.try_offer_next(session, order)
                        assert getattr(failure.value.orig, "sqlstate", getattr(failure.value.orig, "pgcode", None)) == "23503"
                    else:
                        real_commit = session.commit

                        def committed_then_error():
                            assert redis.sismember(key, did), "actual Redis EXEC must precede SQL commit"
                            real_commit()
                            raise RuntimeError("synthetic lost acknowledgement after actual SQL commit")

                        monkeypatch.setattr(session, "commit", committed_then_error)
                        with pytest.raises(RuntimeError, match="synthetic lost acknowledgement"):
                            isv.try_offer_next(session, order)
                    session.rollback()  # Cannot undo the already committed case.
                assert commit_observations == [True], "one actual matcher COMMIT must have been attempted"
                assert eligible_inputs == [[did]], "actual candidates must select the supplied driver"
                assert redis.sismember(key, did)
                assert 0 < redis.ttl(key) <= 3600
                assert notifications == []
                with Session(engine) as observer:
                    assert observer.execute(text("SELECT pg_backend_pid()")).scalar_one() != setup_pid
                    saved = observer.get(InstantOrder, oid)
                    assert saved.search_revision == 20 and saved.tried_lifecycle == lifecycle
                    if outcome == "deferred_commit_failure":
                        assert saved.status == S.searching
                        assert saved.current_offer_driver_id is None and saved.offer_token is None
                        assert saved.search_round == 0 and saved.offer_expires_at is None
                    else:
                        assert saved.status == S.offered and saved.current_offer_driver_id == did
                        assert saved.offer_token and saved.offer_expires_at is not None
                        assert saved.search_round == 1
                if outcome == "deferred_commit_failure":
                    assert key == isv._tried_key(oid, lifecycle)
                    assert redis.sismember(key, did) and 0 < redis.ttl(key) <= 3600
                    eligible_inputs.clear()
                    geo_calls.clear()
                    with Session(engine) as retry:
                        result = isv.try_offer_next(retry, retry.get(InstantOrder, oid), notify=False)
                        assert result.status == S.expired and result.current_offer_driver_id is None
                    assert geo_calls == list(isv.RADII_KM), "all real candidate radii must be tried"
                    assert eligible_inputs == [[] for _ in isv.RADII_KM], "retained Redis member excludes the only supplied driver before eligibility"
                    assert notifications == []
                    with Session(engine) as observer:
                        saved = observer.get(InstantOrder, oid)
                        assert saved.status == S.expired and saved.search_round == 0
                        assert saved.offer_token is None
                print(f"KNOWN LIMITATION observed: {outcome}; retained tried is not compensated")
            finally:
                event.remove(connection, "commit", before_actual_commit)
                connection.rollback()
                connection.execute(text("DROP TABLE IF EXISTS pg_temp.tried_commit_child"))
                connection.execute(text("DROP TABLE IF EXISTS pg_temp.tried_commit_parent"))
                connection.commit()
    finally:
        redis.delete(key)
