"""Only for an attested, disposable PG16 service. No network provider calls."""
import json
import os
import sys
from contextlib import closing
from datetime import datetime, timedelta
from pathlib import Path

OLD = "dc_merge_taxi_consent_receipts"
NEW = "dd_taxi_stop_control"
NEW_COLUMNS = {"stop_route_revision": 36, "stop_wait_revision": 36,
               "stop_wait_kind": 12, "stop_active_waypoint_id": 36}
CLOCK = datetime(2030, 1, 2, 3, 4, 5)
A = {"lat": .21, "lng": .23, "text": "Synthetic A"}
B = {"lat": .25, "lng": .29, "text": "Synthetic B"}
C = {"lat": .27, "lng": .29, "text": "Synthetic C"}
PG_URL = ("postgresql+psycopg2://taxi_cross_version_qa@/taxi_cross_version_qa"
          "?host=/tmp/yuldash-taxi-cross-version-pg&port=5432&connect_timeout=5")


def verify_environment(phase):
    if sys.flags.optimize:
        raise ValueError
    expected = {
        "GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
        "QA_GUARD_INSTALLED": "1", "QA_DB": "postgresql", "DATABASE_URL": PG_URL,
        "ENV": "dev", "SEED_DEMO": "false", "TAXI_STOP_CONTROL_ENABLED": "false",
        "TAXI_EXTERNAL_PRICING_ENABLED": "false", "SMS_PROVIDER": "mock",
        "PAYMENTS_PROVIDER": "mock", "REDIS_URL": "",
    }
    assert all(os.environ.get(k) == v for k, v in expected.items())
    root = Path(os.environ["HOME"]).parent
    assert root.resolve(strict=True) == root and root.name.startswith("disposable-")
    denied = Path(os.environ["QA_DENIED"])
    assert denied == root / "denied" and not denied.exists()
    tree = "old" if phase == "seed-old" else "candidate"
    assert Path.cwd() == root / tree / "backend" and not (Path.cwd() / ".env").exists()
    return root


def schema(observe, *, old):
    assert observe.execute("SELECT version_num FROM alembic_version").fetchall() == [(OLD if old else NEW,)]
    rows = observe.execute(
        "SELECT column_name, data_type, character_maximum_length, is_nullable, column_default "
        "FROM information_schema.columns WHERE table_schema='public' AND table_name='instantorder'"
    ).fetchall()
    seen = {name: (typ, length, nullable, default)
            for name, typ, length, nullable, default in rows if name in NEW_COLUMNS}
    expected = {} if old else {name: ("character varying", length, "YES", None)
                               for name, length in NEW_COLUMNS.items()}
    assert seen == expected


def exercise(observe, root, *, old):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from sqlmodel import Session

    from app import instant_service as isv
    from app.config import settings
    from app.db import engine
    from app.models import InstantOrder, InstantOrderStatus as S, User, UserRole
    from app.routers import instant
    from app.security import issue_tokens

    assert engine.url.drivername == "postgresql+psycopg2"
    assert not settings.taxi_external_pricing_enabled
    if old:
        assert set(NEW_COLUMNS).isdisjoint(InstantOrder.model_fields)
    else:
        assert not settings.taxi_stop_control_enabled
    settings.wait_free_minutes = 3
    settings.wait_fee_rub_per_min = 5
    settings.wait_fee_cap_rub = 100
    clock = CLOCK
    isv.utcnow = lambda: clock
    quote_calls, notice_calls = [], []

    def quote(_session, order, _destination, *, waypoints_override):
        quote_calls.append(order.id)
        return dict(ok=True, price=order.price_estimate, base_price=order.price_estimate,
                    distance_km=order.distance_km, eta_min=order.eta_min)

    def notice(_session, order, _estimate):
        notice_calls.append(order.id)

    isv.destination_quote = quote
    isv.notify_driver_destination = notice
    state = root / "fixture.json"
    if old:
        assert not state.exists()
        assert observe.execute("SELECT count(*) FROM instantorder").fetchall() == [(0,)]
        assert observe.execute('SELECT count(*) FROM "user"').fetchall() == [(0,)]
        with Session(engine) as db:
            p = User(phone="synthetic-migration-passenger", verified=True)
            d = User(phone="synthetic-migration-driver", role=UserRole.driver, verified=True)
            db.add_all([p, d])
            db.flush()
            row = InstantOrder(passenger_id=p.id, driver_id=d.id, status=S.onboard,
                               waypoints_json=isv.dump_waypoints([A, B]), waiting_fee_kop=700,
                               from_lat=.11, from_lng=.13, to_lat=.31, to_lng=.33,
                               price_estimate=600, distance_km=8.0, eta_min=15.0)
            db.add(row)
            db.commit()
            oid, pid, did = row.id, p.id, d.id
    else:
        assert state.is_file() and not state.is_symlink()
        oid, pid, did, original_json = json.loads(state.read_text(encoding="utf-8"))
        assert observe.execute("SELECT count(*) FROM instantorder").fetchall() == [(1,)]
        assert observe.execute('SELECT id FROM "user" ORDER BY id').fetchall() == [(pid,), (did,)]
        with Session(engine) as db:
            p, d = db.get(User, pid), db.get(User, did)
            assert (p.phone, d.phone) == ("synthetic-migration-passenger", "synthetic-migration-driver")

    def observe_order():
        row = observe.execute(
            "SELECT passenger_id, driver_id, status, waypoints_json, stop_started_at, waiting_fee_kop, "
            "price_estimate, distance_km, eta_min" +
            ("" if old else ", stop_route_revision, stop_wait_revision, stop_wait_kind, stop_active_waypoint_id") +
            " FROM instantorder WHERE id=?", (oid,)
        ).fetchall()
        assert len(row) == 1 and tuple(row[0][:3]) == (pid, did, "onboard")
        return tuple(row[0])

    with Session(engine) as db:
        def headers(user_id):
            return {"Authorization": "Bearer " + issue_tokens(db, user_id)["access_token"]}

        passenger, driver = headers(pid), headers(did)

    api = FastAPI()
    api.include_router(instant.router)
    route = f"/instant/orders/{oid}"
    with TestClient(api, raise_server_exceptions=False) as client:
        def call(method, suffix, auth, *, body=None, expected=200):
            response = client.request(method, route + suffix, headers=auth, json=body)
            assert response.status_code == expected
            return response.json()

        if old:
            before = observe_order()
            assert before[4:] == (None, 700, 600, 8.0, 15.0)
            assert call("POST", "/stop", driver)["standing"] is True
            active = observe_order()
            assert active[3:] == (before[3], CLOCK, 700, 600, 8.0, 15.0)
            assert "stop_control" not in call("GET", "", passenger)
            state.write_text(json.dumps([oid, pid, did, active[3]]), encoding="utf-8")
            return

        active = observe_order()
        assert active[3:] == (original_json, CLOCK, 700, 600, 8.0, 15.0, None, None, None, None)
        for auth in (passenger, driver):
            card = call("GET", "", auth)
            assert card["standing"] and "stop_control" not in card
        clock += timedelta(minutes=1)
        edited = call("POST", "/waypoints", passenger, body={"waypoints": [A, C]})["order"]
        assert edited["stops"] == [{**A, "done": False}, {**C, "done": False}]
        after = observe_order()
        assert after[3] != active[3] and after[4:] == active[4:]
        assert quote_calls == notice_calls == [oid]
        clock = CLOCK + timedelta(minutes=5, seconds=30)
        assert call("POST", "/stop", driver)["standing"] is False
        closed = observe_order()
        assert closed[3:] == (after[3], None, 1700, 600, 8.0, 15.0, None, None, None, None)
        clock += timedelta(minutes=10)
        for auth in (passenger, driver):
            assert call("GET", "", auth)["standing"] is False
        assert observe_order() == closed and quote_calls == notice_calls == [oid]


def main():
    phase = sys.argv[1] if len(sys.argv) == 2 else ""
    if phase not in {"seed-old", "check-upgraded"}:
        print("preconditions:fail")
        return 1
    try:
        root = verify_environment(phase)
        from verify import audit  # outer runner supplies independently pinned #384/#392 guard

        with closing(audit(initial=False)) as observe:
            schema(observe, old=phase == "seed-old")
            exercise(observe, root, old=phase == "seed-old")
            assert not (root / "denied").exists()
    except Exception:
        print(f"{phase}:fail")
        return 1
    print(f"{phase}:pass")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
