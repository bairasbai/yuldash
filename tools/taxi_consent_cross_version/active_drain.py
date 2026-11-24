"""Opt-in same-binary, separate-process active consent drain witness."""
import json
import os
from contextlib import closing
from datetime import timedelta
from pathlib import Path


def phase(name, verifier):
    # Run the same private socket/SQLite ownership check before app imports.
    guarded_database = verifier.audit()
    with closing(guarded_database) as connection:
        assert verifier.rev(connection) == [(verifier.NEW,)]
    from fakeredis import FakeStrictRedis
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from sqlmodel import Session, select

    from app import instant_service as isv, pricing, services, taxi_worker
    from app.config import settings
    from app.db import engine
    from app.models import (
        DriverProfile,
        InstantOrder,
        InstantOrderStatus as Status,
        TaxiApplication,
        TaxiApplicationStatus,
        TaxiConsentAttempt,
        User,
        UserRole,
    )
    from app.routers import instant
    from app.security import make_token

    admitting = name == "active-admit"
    root = Path(os.environ["HOME"]).parent
    assert Path.cwd() == root / "next" / "backend"
    assert settings.taxi_enabled and settings.taxi_worker_enabled
    assert settings.taxi_initial_consent_admission_enabled is admitting

    # Real pricing and matcher; synthetic route/weather measurements and local
    # presence/cache only. Push capture stores no payload or personal data.
    isv._redis_override = FakeStrictRedis(decode_responses=True)
    services._cache_client = lambda: isv._redis_override
    pricing.route_metrics = lambda *args: pricing.RouteMetrics(7.0, 15.0)
    pricing.weather_metrics = lambda *args: pricing.WeatherMetrics()
    notices = []

    def capture(_session, user, *_args, data=None, **_kwargs):
        if data and data.get("type") == "instant_offer":
            notices.append((user, int(data["order_id"])))

    isv.send_push = capture
    api = FastAPI()
    api.include_router(instant.router)

    def auth(user):
        return {"Authorization": "Bearer " + make_token(user)}

    def saved(passenger):
        with Session(engine) as session:
            return session.exec(select(InstantOrder).where(
                InstantOrder.passenger_id == passenger,
            )).one()

    def admission_rows(passenger):
        with Session(engine) as session:
            return session.exec(select(TaxiConsentAttempt.request_key, TaxiConsentAttempt.state).where(
                TaxiConsentAttempt.owner_id == passenger,
            )).all()

    route = {key: value for key, value in verifier.BODY.items() if key not in {"request_key", "quote_id"}}
    quotes = "/instant/quotes/v1"
    witness_file = root / "active-drain.json"
    with TestClient(api, raise_server_exceptions=False) as http:
        if admitting:
            with Session(engine) as session:
                isv.seed_tariffs(session)
                passenger = User(phone="+79990006304", verified=True)
                driver = User(phone="+79990006305", role=UserRole.driver, verified=True)
                session.add_all([passenger, driver])
                session.flush()
                pid, did = passenger.id, driver.id
                session.add_all([
                    DriverProfile(user_id=did, online=True),
                    TaxiApplication(user_id=did, status=TaxiApplicationStatus.approved),
                ])
                session.commit()

            assert isv.presence_heartbeat(did, route["from_lat"], route["from_lng"])
            quote = http.post(quotes, headers=auth(pid), json=route)
            assert quote.status_code == 200, quote.status_code
            initial = quote.json()
            body = route | {key: initial[key] for key in ("quote_id", "request_key")}
            interruption = []

            def lose_after_commit(*_args, **_kwargs):
                order = saved(pid)  # Independent SQL session observes the committed admission.
                assert order.status == Status.created and order.search_round == 0
                assert order.current_offer_driver_id is None and order.offered_at is None
                interruption.append(order.id)
                raise RuntimeError("synthetic interruption before dispatch and successful response")

            instant.moderate_open_text = lose_after_commit
            assert http.post(verifier.CONFIRM, headers=auth(pid), json=body).status_code == 500
            order = saved(pid)
            assert interruption == [order.id] and notices == []
            assert admission_rows(pid) == [(body["request_key"], "committed")]
            receipt = json.loads(order.consent_snapshot_json)
            assert 0 < receipt["accepted"]["payable_kop"] <= initial["payable_kop"]
            witness_file.write_text(json.dumps({
                "owner": pid, "driver": did, "order": order.id, "key": body["request_key"],
                "quote": body["quote_id"], "fare": receipt["accepted"]["payable_kop"],
            }))
            return

        witness = json.loads(witness_file.read_bytes())
        pid, did, oid = (witness[field] for field in ("owner", "driver", "order"))
        request = {"request_key": witness["key"]}
        body = route | request | {"quote_id": witness["quote"]}
        old = saved(pid)
        assert old.id == oid and old.status == Status.created and old.search_round == 0
        assert old.current_offer_driver_id is None and old.offered_at is None
        assert list(isv._redis_override.scan_iter(match="taxi:initial-quote:v1:*")) == []
        result = http.post(verifier.RESULT, headers=auth(pid), json=request)
        assert result.status_code == 200 and result.headers["cache-control"] == "no-store"
        assert result.json() == dict(schema="taxi-consent-result-v1", state="committed", order_id=oid,
                                     initial_payable_kop=witness["fare"], next="fetch_order")
        replay = http.post(verifier.CONFIRM, headers=auth(pid), json=body)
        assert replay.status_code == 200 and replay.json()["order"]["id"] == oid
        closed = http.post(verifier.CLOSE, headers=auth(pid), json=request)
        assert closed.status_code == 200 and closed.json() == result.json()
        for denied in (
            http.post(quotes, headers=auth(pid), json=route),
            http.post(verifier.CONFIRM, headers=auth(pid), json=body | {"request_key": verifier.KEY}),
        ):
            assert denied.status_code == 503 and denied.headers["cache-control"] == "no-store"
            assert denied.json()["detail"]["code"] == "taxi_consent_admission_disabled"
        assert saved(pid).status == Status.created and notices == []
        assert admission_rows(pid) == [(body["request_key"], "committed")]

        assert isv.presence_heartbeat(did, route["from_lat"], route["from_lng"])
        # Run the existing timer task through its normal scheduler entry, without
        # unrelated jobs in this intentionally incomplete synthetic market.
        task = tuple(entry for entry in taxi_worker.ЗАДАЧИ if entry[0] == "consent_dispatches")
        assert len(task) == 1
        taxi_worker.ЗАДАЧИ = task
        with Session(engine) as session:
            assert taxi_worker.run_once(session)["consent_dispatches"] == [oid]
        with Session(engine) as session:
            assert taxi_worker.run_once(session)["consent_dispatches"] == []
        final = saved(pid)
        assert (final.id, final.status, final.search_round, final.current_offer_driver_id) == (oid, Status.offered, 1, did)
        # The search window starts at the original creation, not this worker's
        # startup. Driver response TTL is a separate clock, not this bound.
        budget = timedelta(seconds=settings.instant_max_offers * settings.instant_offer_ttl_sec)
        assert final.created_at == old.created_at and final.offered_at is not None
        assert old.created_at <= final.offered_at < old.created_at + budget
        assert final.consent_snapshot_json == old.consent_snapshot_json and final.price_estimate == old.price_estimate
        assert notices == [(did, oid)]
        assert admission_rows(pid) == [(body["request_key"], "committed")]
        with closing(verifier.audit()) as connection:
            assert verifier.rev(connection) == [(verifier.NEW,)]
