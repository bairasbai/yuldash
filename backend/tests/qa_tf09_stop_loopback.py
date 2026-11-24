"""Opt-in TF09 HTTP QA. No browser implied. Run via the sterile fixture launcher only."""
from contextlib import closing, contextmanager
from datetime import timedelta
from http.client import HTTPConnection, RemoteDisconnected
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
from socketserver import TCPServer
import threading
import time

import pytest
import uvicorn

from app import instant_service
from app.config import settings
from app.db import engine
from app.main import app
from app.timeutil import utcnow
from test_taxi_stop_control import A, C, scene  # noqa: F401 - use the exact production-shaped fixture

pytestmark = pytest.mark.skipif(os.environ.get("YULDASH_TF09_LOOPBACK_QA") != "1", reason="local QA only")


@contextmanager
def fixture_transport(order_id, inspect, drop_start):
    # Fail before any socket or request if the startup guard or SQLite fixture is absent.
    import sitecustomize

    assert sitecustomize.TF09_LOOPBACK_ISOLATION
    assert engine.dialect.name == "sqlite"
    raw = Path(engine.url.database).resolve()
    assert raw == Path(os.environ["TMPDIR"]).resolve() / f"yuldash_test_{os.getpid()}.db"
    registered = sitecustomize.ports
    assert not registered
    root = f"/instant/orders/{order_id}"
    allowed = {root, root + "/waypoints/v1/prepare", root + "/waypoints/v1", root + "/stop/v1"}
    for probe in (("198.51.100.19", 443), ("127.0.0.1", 1)):
        with socket.socket() as unexpected:
            with pytest.raises(RuntimeError, match="non-fixture"):
                unexpected.connect(probe)

    class LocalServer(ThreadingHTTPServer):
        def server_bind(self):
            # Never ask the resolver for a reverse hostname just to label the socket.
            TCPServer.server_bind(self)
            self.server_name, self.server_port = self.server_address

    calls, lost, exceptions = [], [], []
    try:
        with socket.socket() as upstream:
            upstream.bind(("127.0.0.1", 0))
            upstream.listen()
            upstream_port = upstream.getsockname()[1]
            registered.add(upstream_port)
            server = uvicorn.Server(uvicorn.Config(app, lifespan="off", ws="none", loop="asyncio",
                                                  proxy_headers=False, access_log=False, log_config=None))

            def forward(method, path, auth, raw):
                with closing(HTTPConnection("127.0.0.1", upstream_port, timeout=4)) as conn:
                    conn.request(method, path, body=raw,
                                 headers={"Authorization": auth, "Content-Type": "application/json"})
                    res = conn.getresponse()
                    return res.status, res.read()

            class Proxy(BaseHTTPRequestHandler):
                def log_message(self, *_args):
                    pass

                def relay(self):
                    if self.path not in allowed:
                        return self.send_error(404)
                    raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                    body = json.loads(raw) if raw else {}
                    action = body.get("action") if self.path.endswith("/stop/v1") else None
                    calls.append((self.command, self.path.removeprefix(root), action))
                    try:
                        status, data = forward(self.command, self.path, self.headers["Authorization"], raw)
                        if drop_start and action == "start_planned" and not lost:
                            assert status == 200 and json.loads(data)["standing"] is True
                            lost.append(inspect()["stop_wait_revision"])  # Separate actual DB read before drop.
                            assert lost[0]
                            self.close_connection = True
                            self.connection.shutdown(socket.SHUT_RDWR)
                            return  # no response headers or body reach the original client.
                        self.send_response(status)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(data)))
                        self.end_headers()
                        self.wfile.write(data)
                    except BaseException as exc:
                        exceptions.append(type(exc).__name__)
                        self.close_connection = True

                do_POST = do_GET = relay

            with LocalServer(("127.0.0.1", 0), Proxy) as proxy:
                proxy_port = proxy.server_address[1]
                registered.add(proxy_port)
                uv = threading.Thread(target=server.run, kwargs={"sockets": [upstream]}, daemon=True)
                px = threading.Thread(target=proxy.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)

                def call(role, path="", body=None, method="POST"):
                    # A new TCP connection models transport on reopen; not a browser navigation.
                    with closing(HTTPConnection("127.0.0.1", proxy_port, timeout=4)) as conn:
                        raw = None if body is None else json.dumps(body)
                        conn.request(method, root + path, body=raw,
                                     headers={**role, "Content-Type": "application/json"})
                        res = conn.getresponse()
                        return res.status, json.loads(res.read())

                uv.start()
                try:
                    stop_at = time.monotonic() + 4
                    while not server.started and time.monotonic() < stop_at and uv.is_alive():
                        time.sleep(0.01)
                    assert server.started
                    px.start()
                    yield call, calls, lost
                finally:
                    if px.is_alive():
                        proxy.shutdown()
                        px.join(4)
                    server.should_exit = True
                    uv.join(4)
                    assert not uv.is_alive() and not px.is_alive() and not exceptions
    finally:
        registered.clear()


@pytest.mark.parametrize("replace", [False, True], ids=["delete-second", "replace-second"])
def test_duplicate_edit_and_cold_driver_resume_after_committed_lost_start(scene, monkeypatch, replace):  # noqa: F811
    clock = [utcnow()]
    monkeypatch.setattr(instant_service, "utcnow", lambda: clock[0])
    monkeypatch.setattr(settings, "wait_free_minutes", 3)
    monkeypatch.setattr(settings, "wait_fee_rub_per_min", 5)
    monkeypatch.setattr(settings, "wait_fee_cap_rub", 100)
    oid = scene.new(A, A)

    def inspect():
        return scene.snapshot(oid)

    with fixture_transport(oid, inspect, drop_start=True) as (http, calls, lost):
        status, prepared = http(scene.d, "/waypoints/v1/prepare")
        assert status == 200
        order = prepared["order"]
        first, second = order["stops"]
        active_id, other_id = first["waypoint_id"], second["waypoint_id"]
        assert active_id != other_id
        assert {k: first[k] for k in ("lat", "lng", "text")} == {k: second[k] for k in ("lat", "lng", "text")}
        c = order["stop_control"]
        with pytest.raises(RemoteDisconnected):
            http(scene.d, "/stop/v1", {"action": "start_planned", "route_revision": c["route_revision"],
                                       "wait_revision": c["wait_revision"], "waypoint_id": active_id})
        committed = inspect()
        assert committed["stop_active_waypoint_id"] == active_id and committed["stop_started_at"]
        assert lost == [committed["stop_wait_revision"]]

        clock[0] += timedelta(minutes=4)
        status, reopened = http(scene.d, method="GET")
        assert status == 200
        active = reopened["stop_control"]["active_wait"]
        assert active == {"kind": "planned", "wait_id": lost[0], "waypoint_id": active_id}
        assert reopened["standing"] is True and reopened["stops"] == order["stops"]
        assert inspect() == committed

        status, passenger = http(scene.p, method="GET")
        assert status == 200
        pc = passenger["stop_control"]
        assert pc["passenger_edit_v1"] is True
        edit = [{"kind": "keep", "waypoint_id": active_id}]
        if replace:
            edit.append({"kind": "new", **{k: C[k] for k in ("lat", "lng", "text")}})
        status, applied = http(scene.p, "/waypoints/v1", {"route_revision": pc["route_revision"],
                              "wait_revision": pc["wait_revision"], "waypoints": edit})
        assert status == 200 and applied["applied"] is True
        returned = applied["order"]
        assert returned["stops"][0] == first and len(returned["stops"]) == 1 + replace
        assert returned["stop_control"]["active_wait"] == active
        assert returned["stop_control"]["route_revision"] != pc["route_revision"]
        if replace:
            entry = returned["stops"][1]
            assert entry["waypoint_id"] not in {active_id, other_id}
            assert {k: entry[k] for k in ("lat", "lng", "text")} == {k: C[k] for k in ("lat", "lng", "text")}
        after_edit = inspect()
        for field in ("stop_started_at", "stop_wait_revision", "waiting_fee_kop", "price_estimate"):
            assert after_edit[field] == committed[field]
        assert scene.quotes == scene.notices == [oid]

        clock[0] += timedelta(minutes=1)
        status, resumed = http(scene.d, "/stop/v1", {"action": "resume", "wait_id": active["wait_id"]})
        assert status == 200 and resumed["standing"] is False
        assert resumed["order"]["stop_control"]["active_wait"] is None
        assert resumed["order"]["stop_control"]["wait_revision"] != lost[0]
        closed = inspect()
        assert closed["stop_started_at"] is None and closed["waiting_fee_kop"] == committed["waiting_fee_kop"] + 1000
        status, idle = http(scene.d, method="GET")
        assert status == 200 and idle["standing"] is False
        assert inspect() == closed
        assert [c for c in calls if c[0] == "POST"] == [
            ("POST", "/waypoints/v1/prepare", None), ("POST", "/stop/v1", "start_planned"),
            ("POST", "/waypoints/v1", None), ("POST", "/stop/v1", "resume"),
        ]
