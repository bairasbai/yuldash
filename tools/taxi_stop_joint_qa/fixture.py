"""Synthetic mounted-client oracle. Only for an externally guarded disposable loopback AVD."""
import argparse
import base64
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
from urllib.parse import parse_qs, urlsplit

A, B, C, ROUTE, NEW_ROUTE, WAIT = [f"00000000-0000-4000-a000-{i:012d}" for i in range(1, 7)]
ID = 77
PATH = f"/instant/orders/{ID}"
DUPLICATE = {"lat": 52.5, "lng": 58.1, "text": "Synthetic stop"}
NEW = {"lat": 52.6, "lng": 58.2, "text": "Synthetic replacement"}
QUERY = "joint-fixture-replacement"
DRIVER, PASSENGER = 71, 72


def jwt(uid):
    sub = base64.urlsafe_b64encode(json.dumps({"sub": str(uid)}).encode()).decode().rstrip("=")
    return f"e30.{sub}.{secrets.token_urlsafe(32)}"


class Scene:
    def __init__(self):
        self.sessions = {role: jwt(uid) for role, uid in (("driver", DRIVER), ("passenger", PASSENGER))}
        self.phase = "on"
        self.failed = False
        self.off_reads = set()
        self.idle_reads = set()

    @property
    def verdict(self):
        if self.failed:
            return "failed"
        return "passed" if self.phase == "drained" and self.idle_reads == set(self.sessions) else "pending"

    def snapshot(self, who):
        active = self.phase in ("off", "edited")
        planned = {"kind": "planned", "waypoint_id": B, "wait_id": WAIT} if active else None
        stops = [{**DUPLICATE, "done": False, "waypoint_id": A},
                 {**DUPLICATE, "done": False, "waypoint_id": B}]
        if self.phase in ("edited", "drained"):
            stops[0] = {**NEW, "done": False, "waypoint_id": C}
        result = {"id": ID, "role": who, "status": "onboard", "standing": active, "stops": stops,
                  "from_lat": 52.48, "from_lng": 58.05, "to_lat": 52.68, "to_lng": 58.29,
                  "from_text": "Synthetic pickup", "to_text": "Synthetic end", "category": "standard",
                  "price_estimate": 600, "passenger_price_kop": 60000}
        if self.phase == "drained":
            for stop in stops:
                del stop["waypoint_id"]
        else:
            result["stop_control"] = {"route_revision": NEW_ROUTE if self.phase == "edited" else ROUTE,
                                      "wait_revision": WAIT if active else None, "active_wait": planned,
                                      "admission_enabled": self.phase == "on", "passenger_edit_v1": True}
        return result

    def refuse(self, status=409):
        self.failed = True
        return status, {"detail": {"code": "tf09_joint_unexpected"}}

    def handle(self, method, target, auth, body=b""):
        who = next((role for role, token in self.sessions.items()
                    if hmac.compare_digest(auth.encode(), ("Bearer " + token).encode())), None)
        if not who:
            return self.refuse(401)
        if method == "GET" and not body:
            if target == PATH:
                if self.phase == "off":
                    self.off_reads.add(who)
                if self.phase == "drained":
                    self.idle_reads.add(who)
                return 200, self.snapshot(who)
            url = urlsplit(target)
            if (who == "passenger" and self.phase == "off" and not url.netloc and not url.scheme
                    and not url.fragment and url.path == "/geocode" and parse_qs(url.query) == {"q": [QUERY]}):
                return 200, {"items": [{"title": NEW["text"], "lat": NEW["lat"], "lon": NEW["lng"]}]}
        if method != "POST":
            return self.refuse()
        try:
            command = json.loads(body)
        except ValueError:
            return self.refuse()
        if who == "driver" and target == PATH + "/stop/v1":
            if self.phase == "on" and command == {
                "action": "start_planned", "route_revision": ROUTE, "wait_revision": None, "waypoint_id": B,
            }:
                self.phase = "off"
                return 200, {"ok": True, "standing": True, "order": self.snapshot(who)}
            if self.phase == "edited" and command == {"action": "resume", "wait_id": WAIT}:
                self.phase = "drained"
                return 200, {"ok": True, "standing": False, "order": self.snapshot(who)}
        if (who == "passenger" and target == PATH + "/waypoints/v1" and self.phase == "off"
                and self.off_reads == set(self.sessions) and command == {
                    "route_revision": ROUTE, "wait_revision": WAIT,
                    "waypoints": [{"kind": "new", **NEW}, {"kind": "keep", "waypoint_id": B}],
                }):
            self.phase = "edited"
            return 200, {"ok": True, "applied": True, "order": self.snapshot(who)}
        return self.refuse()


def held_file(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        stream.write(data)


def serve(private):
    if not private.is_dir() or private.stat().st_mode & 0o077:
        raise SystemExit("private directory required")
    scene = Scene()

    def status():
        tmp = private / "result.tmp"
        tmp.write_text(scene.verdict + "\n")
        tmp.replace(private / "result")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def send_error(self, *_args, **_kwargs):
            scene.refuse()
            status()
            self.close_connection = True

        def setup(self):
            self.request.settimeout(5)
            super().setup()

        def dispatch(self):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 <= length <= 4096 or self.headers.get("Transfer-Encoding") is not None:
                    raise ValueError()
                body = self.rfile.read(length)
                if len(body) != length:
                    raise ValueError()
                code, value = scene.handle(self.command, self.path, self.headers.get("Authorization", ""), body)
            except (ValueError, OSError):
                code, value = scene.refuse()
            status()
            raw = json.dumps(value).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        do_GET = do_POST = dispatch

    class QuietServer(HTTPServer):
        def handle_error(self, *_args):
            scene.refuse()
            status()

    with QuietServer(("127.0.0.1", 8000), Handler) as server:
        for who, uid in (("driver", DRIVER), ("passenger", PASSENGER)):
            held_file(private / (who + ".json"), json.dumps({"uid": uid, "access": scene.sessions[who]}))
        status()
        server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("private", type=Path)
    serve(parser.parse_args().private)
