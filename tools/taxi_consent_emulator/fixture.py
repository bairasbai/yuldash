"""Loopback bridge between an Android emulator and the isolated FastAPI taxi test market."""

import argparse
import contextlib
import json
import os
import re
import signal
import socket
import sys
import tempfile
import time
from datetime import timedelta
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from unittest.mock import patch

QUOTE = "/instant/quotes/v1"
CONFIRM = "/instant/orders/confirmed/v1"
RESULT = "/instant/orders/result/v1"
CLOSE = "/instant/orders/close-attempt/v1"
ENDPOINTS = {
    ("POST", QUOTE): "quote",
    ("POST", CONFIRM): "confirm",
    ("POST", RESULT): "result",
    ("POST", CLOSE): "close",
    ("POST", "/instant/orders"): "legacy",
    ("POST", "/instant/schedule"): "schedule",
}


def write_private_json(filename, obj):
    with tempfile.NamedTemporaryFile(mode="w", dir=filename.parent, delete=False) as fp:
        json.dump(obj, fp)
    os.replace(fp.name, filename)


def serve(args, tempdir):
    backend = Path(__file__).resolve().parents[2] / "backend"
    sys.path[:0] = [str(backend), str(backend / "tests")]
    # Select the test fixture's own SQLite file before importing the backend.
    os.environ.pop("DATABASE_URL", None)
    tempfile.tempdir = tempdir
    import conftest
    import pytest
    import test_taxi_quote_consent as market
    from test_promo_taxi import DEST, ORIG
    from app import instant_service, pricing
    from app.config import settings
    from app.models import InstantOrder, InstantOrderStatus, TaxiConsentAttempt
    from app.security import issue_tokens
    from app.timeutil import utcnow
    from sqlmodel import Session, select

    assert Path(conftest.engine.url.database).parent == Path(tempdir)
    counters = dict.fromkeys((*ENDPOINTS.values(), "card", "activate"), 0)
    scheduled_case = args.case in ("scheduled", "late", "unavailable")
    scheduled_route = None
    activation_released = False
    quoted_round_trips = []
    delayed = None
    late_status = None
    last_card_status = None

    selection_keys = []
    addressed_slots = {"result": [], "close": []}

    def snapshot():
        with Session(conftest.engine) as db:
            orders = db.exec(select(InstantOrder).where(InstantOrder.passenger_id == uid)).all()
            only = orders[0] if len(orders) == 1 else None
            order_fields = {
                "order_id": only.id if only else None,
                "order_status": only.status.value if only else None, "last_card_status": last_card_status,
                "confirmed_round_trip": only.round_trip if only else None,
                "confirmed_return_wait_min": only.return_wait_min if only else None,
            }
            if scheduled_case and only:
                order_fields["route"] = {
                    "stops": [point["text"] for point in instant_service.parse_waypoints(only.waypoints_json)],
                    "km": only.distance_km, "minutes": only.eta_min, "fare": only.price_estimate,
                }
            attempts = db.exec(select(TaxiConsentAttempt).where(
                TaxiConsentAttempt.owner_id == uid,
            ).order_by(TaxiConsentAttempt.request_key)).all()
            states = [attempt.state for attempt in attempts]
            selection = {"states_by_slot": states, "addressed_slots": addressed_slots} if selection_keys else {}
        write_private_json(args.state_file, {
            **counters, **order_fields, "scheduled_route": scheduled_route, "released": activation_released, "orders": len(orders), "quoted_round_trips": quoted_round_trips,
            "attempts": sorted(states), "late_status": late_status, **selection,
        })

    class Bridge(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Routes, session details and request bodies are never logged.

        def forward(self):
            nonlocal delayed, late_status, last_card_status, scheduled_route, activation_released
            parsed = urlsplit(self.path)
            if parsed.scheme or parsed.netloc or not self.path.startswith("/") or self.path.startswith("//"):
                return self.send_error(400)
            size = int(self.headers.get("Content-Length", 0))
            if not 0 <= size <= 64 * 1024:
                return self.send_error(413)
            body = self.rfile.read(size)
            headers = {k: self.headers[k] for k in ("Authorization", "Content-Type") if k in self.headers}
            key = ENDPOINTS.get((self.command, parsed.path))
            if self.command == "GET" and re.fullmatch(r"/instant/orders/[1-9][0-9]*", parsed.path):
                key = "card"
            if self.command == "POST" and re.fullmatch(r"/instant/scheduled/[1-9][0-9]*/activate", parsed.path):
                key = "activate"
            if key:
                counters[key] += 1
            if selection_keys and key in addressed_slots:
                request_key = json.loads(body).get("request_key")
                slot = selection_keys.index(request_key) + 1 if request_key in selection_keys else 0
                addressed_slots[key].append(slot)
            if key == "quote":
                route = json.loads(body)
                quoted_round_trips.append([route.get("round_trip", False), route.get("return_wait_min", 0)])
            disconnect = args.case != "roundtrip" and key == "confirm" and counters[key] == 1
            if disconnect and args.case == "closed":
                delayed = (body, headers)
                snapshot()
                self.close_connection = True
                return
            response = client.request(self.command, self.path, content=body, headers=headers)
            if key == "schedule" and response.status_code == 200:
                order = response.json()
                scheduled_route = {"stops": [s["text"] for s in order["stops"]],
                                   "km": order["distance_km"], "minutes": order["eta_min"], "fare": order["price_estimate"]}
            if key == "activate" and args.case == "late" and response.status_code == 200:
                snapshot()  # The server has committed; the UI still awaits this exact response.
                release = args.state_file.with_suffix(".release")
                deadline = time.monotonic() + 45
                while not release.exists():
                    if time.monotonic() > deadline:
                        raise TimeoutError("Passenger did not leave before the activation response")
                    time.sleep(0.05)
                activation_released = True
            if key == "card" and response.status_code == 200:
                last_card_status = response.json()["status"]
            if disconnect and args.case == "committed" and response.status_code == 200:
                # The normal authenticated card read will reconcile the expired driver offer.
                # This makes the recovered status observably different from the lost receipt.
                with Session(conftest.engine) as db:
                    order = db.get(InstantOrder, response.json()["order"]["id"])
                    assert order.passenger_id == uid and order.status == InstantOrderStatus.offered
                    order.offer_expires_at = utcnow() - timedelta(seconds=1)
                    db.add(order)
                    db.commit()
            if key == "close" and delayed is not None and response.status_code == 200:
                saved_body, saved_headers = delayed
                delayed = None
                late_status = client.post(CONFIRM, content=saved_body, headers=saved_headers).status_code
            snapshot()
            if disconnect:
                if response.status_code != 200:
                    raise RuntimeError(f"Synthetic confirmation failed with HTTP {response.status_code}")
                self.close_connection = True
                return
            self.send_response(response.status_code)
            for name in ("Content-Type", "Cache-Control"):
                if name in response.headers:
                    self.send_header(name, response.headers[name])
            self.send_header("Content-Length", str(len(response.content)))
            self.end_headers()
            self.wfile.write(response.content)

        do_GET = do_POST = forward

    with contextlib.ExitStack() as stack:
        server = stack.enter_context(HTTPServer(("127.0.0.1", 8000), Bridge))
        client = stack.enter_context(contextlib.contextmanager(conftest.client.__wrapped__)())
        monkeypatch = stack.enter_context(pytest.MonkeyPatch.context())
        stack.enter_context(contextlib.contextmanager(market.consent_market.__wrapped__)(client, monkeypatch))
        # Keep the sole synthetic driver present during UI assertions on slower emulators.
        monkeypatch.setattr(settings, "presence_ttl_sec", 900)
        monkeypatch.setattr(settings, "instant_offer_ttl_sec", 900)
        if args.case == "roundtrip":
            monkeypatch.setattr(pricing, "route_metrics", lambda *_: pricing.RouteMetrics(180.0, 140.0))
        if scheduled_case:
            # Distinct directed legs make dropping OR reversing stops change distance and ETA.
            a, b = (52.64, 58.41), (52.68, 58.54)
            metrics = {(ORIG, a): (52.0, 23.0), (a, b): (61.0, 31.0), (b, DEST): (67.0, 36.0)}
            monkeypatch.setattr(pricing, "route_metrics", lambda frm, to: pricing.RouteMetrics(
                *metrics.get((tuple(frm), tuple(to)), (66.0, 33.0))))
            monkeypatch.setattr(instant_service, "zone_for_km", lambda _: "intercity")
            from app.routers import discovery
            geocoded = {"geocode:v1:qa alpha": {"title": "QA Stop A", "lat": 52.64, "lon": 58.41},
                        "geocode:v1:qa bravo": {"title": "QA Stop B", "lat": 52.68, "lon": 58.54}}
            monkeypatch.setattr(settings, "yandex_geocoder_key", "fixture")
            monkeypatch.setattr(discovery, "cache_get_json",
                                lambda key: {"items": [geocoded[key]] if key in geocoded else []}
                                if key.startswith("geocode:") else None)
        make_user = conftest.user_factory.__wrapped__(client)
        market.set_base(600, per_km=2 if scheduled_case else 0)
        if args.case != "unavailable":
            market.live_driver(client, make_user)
        passenger = make_user("ScheduledPassenger") if scheduled_case else market.promo_user(client, make_user)
        uid = passenger["id"]
        for label, (lat, lng) in (("QA Origin", ORIG), ("QA Destination", DEST)):
            saved = client.post("/places/saved", headers=passenger["auth"], json={
                "label": label, "kind": "custom", "address": label, "lat": lat, "lng": lng,
            })
            assert saved.status_code == 200, "Cannot provision synthetic saved places"
        with Session(conftest.engine) as db:
            session = issue_tokens(db, uid, auth_method="telegram", auth_time=utcnow())
        if args.case == "selection":
            selection_keys = sorted(market.quote(client, passenger)["request_key"] for _ in range(3))
            assert len(set(selection_keys)) == 3
        snapshot()
        write_private_json(args.session_file, {
            "uid": uid, "access": session["access_token"], "refresh": session["refresh_token"],
            **({"request_keys": selection_keys} if selection_keys else {}),
        })
        server.serve_forever()


def local_connect_only(original):
    def connect(sock, address):
        if sock.family in (socket.AF_INET, socket.AF_INET6) and address[0] not in ("127.0.0.1", "::1"):
            raise OSError("The isolated backend fixture allows only loopback connections")
        return original(sock, address)
    return connect


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", required=True, choices=("committed", "closed", "roundtrip", "selection", "scheduled", "late", "unavailable"))
    parser.add_argument("--session-file", required=True, type=Path)
    parser.add_argument("--state-file", required=True, type=Path)
    args = parser.parse_args()
    if args.session_file.resolve() == args.state_file.resolve() or any(
        path.exists() for path in (args.session_file, args.state_file)
    ):
        parser.error("Use two new private fixture files for each case")
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    with tempfile.TemporaryDirectory() as tempdir, patch.object(
        socket.socket, "connect", local_connect_only(socket.socket.connect)
    ):
        serve(args, tempdir)


if __name__ == "__main__":
    main()
