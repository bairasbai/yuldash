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
    from app.models import InstantOrder, TaxiConsentAttempt
    from app.security import issue_tokens
    from app.timeutil import utcnow
    from sqlmodel import Session, select

    assert Path(conftest.engine.url.database).parent == Path(tempdir)
    counters = dict.fromkeys((*ENDPOINTS.values(), "card"), 0)
    delayed = None
    late_status = None

    def snapshot():
        with Session(conftest.engine) as db:
            orders = db.exec(select(InstantOrder.id).where(InstantOrder.passenger_id == uid)).all()
            attempts = db.exec(select(TaxiConsentAttempt.state).where(TaxiConsentAttempt.owner_id == uid)).all()
        write_private_json(args.state_file, {
            **counters, "orders": len(orders), "order_id": orders[0] if len(orders) == 1 else None,
            "attempts": sorted(attempts), "late_status": late_status,
        })

    class Bridge(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Routes, session details and request bodies are never logged.

        def forward(self):
            nonlocal delayed, late_status
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
            if key:
                counters[key] += 1
            disconnect = key == "confirm" and counters[key] == 1
            if disconnect and args.case == "closed":
                delayed = (body, headers)
                snapshot()
                self.close_connection = True
                return
            response = client.request(self.command, self.path, content=body, headers=headers)
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
        make_user = conftest.user_factory.__wrapped__(client)
        market.set_base(600)
        market.live_driver(client, make_user)
        uid = market.promo_user(client, make_user)["id"]
        with Session(conftest.engine) as db:
            session = issue_tokens(db, uid, auth_method="telegram", auth_time=utcnow())
        snapshot()
        write_private_json(args.session_file, {
            "uid": uid, "access": session["access_token"], "refresh": session["refresh_token"],
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
    parser.add_argument("--case", required=True, choices=("committed", "closed"))
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
