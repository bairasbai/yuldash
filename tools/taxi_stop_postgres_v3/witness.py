"""TF09 typed passenger edits under real PG16 contention, only on the attested CI socket."""

import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from contextvars import ContextVar
from datetime import timedelta
from pathlib import Path
from threading import Event
from time import monotonic, sleep

PG_SOCKET = Path("/tmp/yuldash-taxi-cross-version-pg")
PG_ROLE_DB = "taxi_cross_version_qa"
PG_OPTIONS = dict(dbname=PG_ROLE_DB, user=PG_ROLE_DB, host=str(PG_SOCKET), port="5432", connect_timeout="5")
PG_URL = f"postgresql+psycopg2://{PG_ROLE_DB}@/{PG_ROLE_DB}?host={PG_SOCKET}&port=5432&connect_timeout=5"
ALEMBIC_REVISION = "dd_taxi_stop_control"
MARKER = ContextVar("tf09_witness_request", default=None)
TIMEOUT = 8


def preflight():
    """The outer runner must attest the #385 socket service before migration."""
    if sys.flags.optimize:
        raise ValueError
    for name, expected in {
        "GITHUB_ACTIONS": "true",
        "RUNNER_OS": "Linux",
        "RUNNER_ARCH": "X64",
        "QA_GUARD_INSTALLED": "1",
        "QA_DB": "postgresql",
        "DATABASE_URL": PG_URL,
        "ENV": "dev",
        "SEED_DEMO": "false",
        "TAXI_STOP_CONTROL_ENABLED": "true",
        "TAXI_EXTERNAL_PRICING_ENABLED": "false",
        "SMS_PROVIDER": "mock",
        "PAYMENTS_PROVIDER": "mock",
        "REDIS_URL": "",
    }.items():
        assert os.environ.get(name) == expected
    assert re.fullmatch(r"[0-9a-f]{64}", os.environ["QA_SERVICE_CONTAINER"])
    root = Path(os.environ["HOME"]).parent
    assert root.name.startswith("disposable-") and root.resolve(strict=True) == root
    assert (root / "home") == Path(os.environ["HOME"])
    assert (root / "denied") == Path(os.environ["QA_DENIED"]) and not (root / "denied").exists()
    assert Path.cwd() == root / "candidate" / "backend"
    assert not (Path.cwd() / ".env").exists()
    node = PG_SOCKET / ".s.PGSQL.5432"
    assert PG_SOCKET.resolve(strict=True) == PG_SOCKET and node.is_socket() and not node.is_symlink()
    st = node.lstat()
    assert os.environ["QA_SOCKET_ID"] == f"{st.st_dev}:{st.st_ino}"


def db_probe(observer, query, args=()):
    with observer.cursor() as cursor:
        cursor.execute(query, args)
        return cursor.fetchall()


def assert_owned_empty_orders(observer):
    assert db_probe(observer,
                    "SELECT current_database(),session_user,current_schema(),"
                    "inet_server_addr() IS NULL,inet_client_addr() IS NULL,"
                    "current_setting('server_version_num')::int/10000") == [
        (PG_ROLE_DB, PG_ROLE_DB, "public", True, True, 16),
    ]
    assert db_probe(observer, "SELECT version_num FROM alembic_version") == [(ALEMBIC_REVISION,)]
    assert db_probe(observer, "SELECT count(*) FROM instantorder") == [(0,)]


def exercise(observer):
    from fastapi import FastAPI, Request
    from fastapi.testclient import TestClient
    from sqlalchemy import event
    from sqlalchemy.orm import Session as OrmSession
    from sqlmodel import Session

    from app import instant_service as isv
    from app import taxi_stop_control as stop
    from app.config import settings
    from app.db import engine
    from app.models import InstantOrder, InstantOrderStatus, User, UserRole
    from app.routers import instant
    from app.security import issue_tokens
    from app.timeutil import utcnow

    assert settings.taxi_stop_control_enabled and not settings.taxi_external_pricing_enabled
    settings.wait_free_minutes, settings.wait_fee_rub_per_min, settings.wait_fee_cap_rub = 3, 5, 100
    clock = utcnow()
    isv.utcnow = lambda: clock  # controlled wait accrual, not used to time SQL blocking
    app = FastAPI()
    app.include_router(instant.router)
    real_lock = stop.lock_order
    active_case = None
    quotes, notices = [], []

    @app.middleware("http")
    async def request_boundary(request: Request, call_next):
        token = MARKER.set(request.headers.get("X-TF09-Synthetic-Request"))
        try:
            return await call_next(request)
        finally:
            MARKER.reset(token)

    def recording_lock(session, order_id, *scope):
        case, marker = active_case, MARKER.get()
        if case is not None and case["order"] == order_id and marker in ("winner", "contender"):
            raw = session.connection().connection.driver_connection
            case[marker + "_pid"] = raw.get_backend_pid()
            if marker == "winner":
                session.info["tf09_hold_commit"] = case
            else:
                case["contender_entered"].set()
        # Calls the real product SELECT FOR UPDATE. No simulated lock/result.
        return real_lock(session, order_id, *scope)

    @event.listens_for(OrmSession, "before_commit")
    def before_commit(session):
        if (case := session.info.pop("tf09_hold_commit", None)) is not None:
            session.flush()  # Send the winner's real UPDATE before blocking COMMIT.
            case["flushed"].set()
            if not case["commit_permitted"].wait(TIMEOUT):
                raise TimeoutError("witness did not release the real commit")

    def quote(_session, order, _destination, *, waypoints_override):
        quotes.append(order.id)
        return dict(ok=True, price=order.price_estimate, base_price=order.price_estimate,
                    distance_km=order.distance_km, eta_min=order.eta_min)

    def notice(_session, order, _estimate):
        notices.append(order.id)

    stop.lock_order = recording_lock
    isv.destination_quote = quote
    isv.notify_driver_destination = notice
    identical = {"lat": 0.21, "lng": 0.23, "text": "Аптека"}
    last = {"lat": 0.25, "lng": 0.29, "text": "Магазин"}
    replacement = {"lat": 0.27, "lng": 0.29, "text": "Библиотека"}

    def seed(case_no):
        with Session(engine) as session:
            passenger = User(phone="synthetic-passenger-" + str(case_no), verified=True)
            driver = User(phone="synthetic-driver-" + str(case_no), role=UserRole.driver, verified=True)
            session.add_all([passenger, driver])
            session.flush()
            order = InstantOrder(
                passenger_id=passenger.id, driver_id=driver.id, status=InstantOrderStatus.onboard,
                waypoints_json=isv.dump_waypoints([identical, identical, last]),
                from_lat=0.11, from_lng=0.13, to_lat=0.31, to_lng=0.33,
                price_estimate=600, distance_km=8.0, eta_min=15.0, waiting_fee_kop=700,
            )
            session.add(order)
            session.commit()
            pid, did, oid = passenger.id, driver.id, order.id
            p = issue_tokens(session, pid)["access_token"]
            d = issue_tokens(session, did)["access_token"]
            assert p != d
            return oid, {"Authorization": "Bearer " + p}, {"Authorization": "Bearer " + d}

    def persisted(oid):
        return db_probe(observer, "SELECT status,waypoints_json,stop_route_revision,"
                        "stop_wait_revision,stop_wait_kind,stop_active_waypoint_id,stop_started_at,"
                        "waiting_fee_kop,price_estimate FROM instantorder WHERE id=%s", (oid,))[0]

    def keep(identity):
        return {"kind": "keep", "waypoint_id": identity}

    def typed(control, *waypoints):
        return {"route_revision": control["route_revision"], "wait_revision": control["wait_revision"],
                "waypoints": list(waypoints)}

    def run(case_no, *, driver_wins, replace_duplicate=False):
        nonlocal active_case, clock
        oid, passenger, driver = seed(case_no)
        route = f"/instant/orders/{oid}"
        with (
            TestClient(app, raise_server_exceptions=False) as passenger_client,
            TestClient(app, raise_server_exceptions=False) as driver_client,
        ):
            prepared = driver_client.post(route + "/waypoints/v1/prepare", headers=driver)
            assert prepared.status_code == 200
            ready = prepared.json()["order"]
            control = ready["stop_control"]
            first, second, trailing = ready["stops"]
            assert {k: v for k, v in first.items() if k != "waypoint_id"} == {
                k: v for k, v in second.items() if k != "waypoint_id"
            } == {**identical, "done": False}
            assert control["passenger_edit_v1"] is True
            first_id, second_id, trailing_id = [point["waypoint_id"] for point in ready["stops"]]
            assert len({first_id, second_id, trailing_id}) == 3
            select_first = {"action": "start_planned", "waypoint_id": first_id,
                            "wait_revision": control["wait_revision"], "route_revision": control["route_revision"]}
            remove_first = typed(control, keep(second_id), keep(trailing_id))
            before = persisted(oid)

            def request(driver_request, marker):
                h = (driver if driver_request else passenger) | {"X-TF09-Synthetic-Request": marker}
                if driver_request:
                    return driver_client.post(route + "/stop/v1", headers=h, json=select_first)
                return passenger_client.post(route + "/waypoints/v1", headers=h, json=remove_first)

            case = dict(order=oid, flushed=Event(), commit_permitted=Event(), contender_entered=Event())
            active_case = case
            try:
                with ThreadPoolExecutor(max_workers=2) as pool:
                    winner = pool.submit(request, driver_wins, "winner")
                    try:
                        assert case["flushed"].wait(TIMEOUT)
                        contender = pool.submit(request, not driver_wins, "contender")
                        assert case["contender_entered"].wait(TIMEOUT)
                        wp, cp = case["winner_pid"], case["contender_pid"]
                        assert len({wp, cp, observer.get_backend_pid()}) == 3
                        deadline = monotonic() + TIMEOUT
                        while monotonic() < deadline:
                            if db_probe(observer, "SELECT %s = ANY(pg_blocking_pids(%s))", (wp, cp)) == [(True,)]:
                                break
                            assert not contender.done()
                            sleep(0.02)
                        else:
                            raise AssertionError("expected actual PostgreSQL block not observed")
                        # A third connection still sees the old row despite the winner's physical UPDATE.
                        assert persisted(oid) == before
                        assert not winner.done() and not contender.done()
                    finally:
                        case["commit_permitted"].set()
                    accepted, refused = winner.result(TIMEOUT), contender.result(TIMEOUT)
            finally:
                active_case = None

            assert (accepted.status_code, refused.status_code) == (200, 409)
            assert refused.json()["detail"]["code"] == "taxi_waypoints_stale"
            won = accepted.json()["order"]
            for client, auth in ((passenger_client, passenger), (driver_client, driver)):
                match = client.get(route, headers=auth)
                assert match.status_code == 200
                assert match.json()["stops"] == won["stops"]
                assert match.json()["stop_control"] == won["stop_control"]

            if not driver_wins:
                assert won["standing"] is False and won["stop_control"]["active_wait"] is None
                assert won["stops"] == [second, trailing]
                after = persisted(oid)
                assert isv.parse_waypoints(after[1]) == won["stops"] and after[2] != before[2]
                assert after[4:9] == (None, None, None, 700, 600)
                # Replacing only the revision may not bind the no-longer-existing
                # ID to the surviving identical location.
                stale = driver_client.post(route + "/stop/v1", headers=driver, json={
                    **select_first, "route_revision": won["stop_control"]["route_revision"],
                })
                assert stale.status_code == 409 and stale.json()["detail"]["code"] == "taxi_waypoints_stale"
                assert persisted(oid) == after
                assert quotes.count(oid) == notices.count(oid) == 1
                return

            wait = won["stop_control"]["active_wait"]
            assert wait["waypoint_id"] == first_id and won["standing"] is True
            after = persisted(oid)
            assert (after[2], after[4], after[5], after[7:]) == (
                before[2], "planned", first_id, (700, 600),
            ) and after[6] is not None
            current = won["stop_control"]
            assert current["passenger_edit_v1"] is True
            # V0 sees only values, so it cannot know which literal duplicate
            # remains. V1 must also reject omitting the protected ID, even when
            # the payload includes its indistinguishable sibling or a new copy.
            legacy = passenger_client.post(route + "/waypoints", headers=passenger,
                                           json={"waypoints": [identical, last]})
            assert legacy.status_code == 409
            assert legacy.json()["detail"]["code"] == "taxi_active_waypoint_protected"
            for edit in ([keep(second_id), keep(trailing_id)],
                         [{"kind": "new", **identical}, keep(trailing_id)]):
                protected = passenger_client.post(route + "/waypoints/v1", headers=passenger,
                                                  json=typed(current, *edit))
                assert protected.status_code == 409
                assert protected.json()["detail"]["code"] == "taxi_active_waypoint_protected"
            assert persisted(oid) == after
            assert quotes.count(oid) == 1 and notices.count(oid) == 0

            changes = [keep(first_id)]
            if replace_duplicate:
                changes.append({"kind": "new", **replacement})
            changes.append(keep(trailing_id))
            payload = typed(current, *changes)
            allowed = passenger_client.post(route + "/waypoints/v1", headers=passenger, json=payload)
            assert allowed.status_code == 200
            edited = allowed.json()["order"]
            assert edited["stops"][0] == first and edited["stops"][-1] == trailing
            assert len(edited["stops"]) == (3 if replace_duplicate else 2)
            assert second_id not in [p["waypoint_id"] for p in edited["stops"]]
            if replace_duplicate:
                new = edited["stops"][1]
                assert new["waypoint_id"] not in {first_id, second_id, trailing_id}
                assert {k: v for k, v in new.items() if k != "waypoint_id"} == {**replacement, "done": False}
            assert edited["stop_control"]["active_wait"] == wait
            after_edit = persisted(oid)
            assert isv.parse_waypoints(after_edit[1]) == edited["stops"] and after_edit[2] != after[2]
            assert after_edit[3:9] == after[3:9]
            replay = passenger_client.post(route + "/waypoints/v1", headers=passenger, json=payload)
            assert replay.status_code == 409 and replay.json()["detail"]["code"] == "taxi_waypoints_stale"
            assert persisted(oid) == after_edit

            clock += timedelta(minutes=5, seconds=30)
            for _ in range(2):
                resumed = driver_client.post(route + "/stop/v1", headers=driver,
                                             json={"action": "resume", "wait_id": wait["wait_id"]})
                assert resumed.status_code == 200 and resumed.json()["standing"] is False
                final = persisted(oid)
                assert final[1:3] == after_edit[1:3]
                assert final[4:9] == (None, None, None, 1700, 600)
            assert quotes.count(oid) == 2 and notices.count(oid) == 1

    try:
        run(1, driver_wins=False)
        print("typed-passenger-first:pass", flush=True)
        run(2, driver_wins=True)
        print("typed-driver-first-delete-duplicate:pass", flush=True)
        run(3, driver_wins=True, replace_duplicate=True)
        print("typed-driver-first-replace-duplicate:pass", flush=True)
    finally:
        event.remove(OrmSession, "before_commit", before_commit)


def main():
    phase = "preconditions"
    try:
        preflight()
        import psycopg2

        with closing(psycopg2.connect(**PG_OPTIONS)) as observer:
            observer.autocommit = True
            assert_owned_empty_orders(observer)
            phase = "commit-races"
            exercise(observer)
            assert not Path(os.environ["QA_DENIED"]).exists()
    except Exception:
        # An unapproved database or unexpected test failure must never print values,
        # credentials, headers, DSN, or device/location data.
        print(f"{phase}:fail", flush=True)
        return 1
    print("tf09-v3-postgresql-witness:pass", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
