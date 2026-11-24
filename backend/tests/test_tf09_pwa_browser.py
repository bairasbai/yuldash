"""Synthetic, localhost-only component/real-mounted-API E2E, not launcher or PWA service worker."""
import json
import mimetypes
import os
from datetime import timedelta
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from threading import Thread
from urllib.parse import urlsplit

import pytest
from app import instant_service
from app.config import settings
from app.routers import discovery
from app.timeutil import utcnow
from test_taxi_stop_control import A, B, scene  # noqa: F401

QUERY = "tf09 fixture address"
NEW = {"title": "Synthetic Fourth", "lat": 54.065, "lon": 55.085}


def http_bridge(client, root, order_id, posted, violations):
    orders = f"/instant/orders/{order_id}"
    allowed = {
        ("GET", orders), ("GET", "/geocode"), ("POST", "/me/update"),
        ("POST", orders + "/waypoints/v1/prepare"), ("POST", orders + "/stop/v1"),
        ("POST", orders + "/waypoints/v1"),
    }

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def response(self, status, content, media):
            self.send_response(status)
            self.send_header("Content-Type", media)
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(content)

        def dispatch(self):
            parsed = urlsplit(self.path)
            if parsed.scheme or parsed.netloc or parsed.fragment:
                violations.append("target")
                return self.response(400, b"", "text/plain")
            if not parsed.path.startswith("/api/"):
                name = parsed.path.removeprefix("/")
                file = (root / name).resolve()
                if self.command != "GET" or root not in file.parents or not file.is_file():
                    violations.append("asset")
                    return self.response(404, b"", "text/plain")
                return self.response(200, file.read_bytes(), mimetypes.guess_type(file)[0] or "application/octet-stream")

            endpoint = parsed.path.removeprefix("/api")
            if (self.command, endpoint) not in allowed or self.headers.get("Transfer-Encoding"):
                violations.append("application path")
                return self.response(404, b"", "text/plain")
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 <= length <= 8192:
                violations.append("body")
                return self.response(400, b"", "text/plain")
            headers = {key: self.headers[key] for key in ("Authorization", "Content-Type", "Accept-Language")
                       if key in self.headers}
            response = client.request(self.command, self.path.removeprefix("/api"),
                                      headers=headers, content=self.rfile.read(length))
            if self.command == "POST" and endpoint.startswith(orders + "/"):
                posted.append((endpoint, response.status_code))
            return self.response(response.status_code, response.content,
                                 response.headers.get("content-type", "application/json"))

        do_GET = do_POST = dispatch

    class Quiet(HTTPServer):
        def handle_error(self, request, address):
            violations.append("bridge exception")

    return Quiet(("127.0.0.1", 0), Handler)


@pytest.mark.skipif(os.environ.get("TF09_BROWSER_ISOLATED") != "1", reason="requires attested sandboxed disposable runner")
def test_exact_tf09_pwa_browser_off_drain(scene, client, monkeypatch):
    from guards import require_isolation, sandbox_verified
    from app.db import engine
    require_isolation()
    expected_private = Path(os.environ["TMPDIR"]).resolve()
    assert engine.url.get_backend_name() == "sqlite"
    assert Path(engine.url.database).resolve() == expected_private / f"yuldash_test_{os.getpid()}.db"
    from playwright.sync_api import expect, sync_playwright

    assets = (Path(__file__).resolve().parents[2] / "webapp" / "tf09-qa-dist").resolve()
    assert (assets / "tf09-qa.html").is_file()
    clock = utcnow()
    monkeypatch.setattr(instant_service, "utcnow", lambda: clock)
    monkeypatch.setattr(settings, "wait_free_minutes", 3)
    monkeypatch.setattr(settings, "wait_fee_rub_per_min", 5)
    monkeypatch.setattr(settings, "wait_fee_cap_rub", 100)
    monkeypatch.setattr(settings, "yandex_geocoder_key", "synthetic-not-a-service-key")
    # Use the real mounted /geocode; stub only a provider cache for one known synthetic key.
    def cached(key):
        assert key == f"geocode:v1:{QUERY}"
        return {"items": [NEW]}
    monkeypatch.setattr(discovery, "cache_get_json", cached)

    oid = scene.new(A, A, B)
    path = f"/instant/orders/{oid}"
    posted, violations = [], []
    server = http_bridge(client, assets, oid, posted, violations)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    origin = f"http://127.0.0.1:{server.server_port}"
    try:
        with sync_playwright() as p:
            kwargs = {"chromium_sandbox": True, "headless": True, "timeout": 15000}
            if binary := os.environ.get("TF09_CHROMIUM"):
                kwargs["executable_path"] = binary
            browser = p.chromium.launch(**kwargs)
            try:
                check = browser.new_page()
                check.goto("chrome://sandbox")
                rows = check.locator("tr").evaluate_all(
                    "(rows) => rows.map(row => Array.from(row.cells, cell => cell.innerText.trim()))"
                )
                assert sandbox_verified(check.locator("body").inner_text(), rows)
                check.close()

                def open_role(auth):
                    context = browser.new_context(service_workers="block", locale="ru-RU")
                    def local_only(route):
                        if not route.request.url.startswith(origin + "/"):
                            violations.append("browser origin")
                            route.abort()
                        else:
                            route.continue_()
                    context.route("**/*", local_only)
                    context.add_init_script(script="window.__tf09QA = " + json.dumps({
                        "order": oid, "token": auth["Authorization"].split(" ", 1)[1],
                    }))
                    page = context.new_page()
                    page.set_default_timeout(10000)
                    page.goto(origin + "/tf09-qa.html")
                    return context, page

                with open_role(scene.d)[0] as initial_context:
                    page = initial_context.pages[0]
                    page.get_by_role("button", name="Отметить остановку", exact=True).click()
                    page.get_by_role("button", name="Загрузить остановки", exact=True).click()
                    page.get_by_role("button", name="Остановка № 2: Synthetic A", exact=True).click()
                    expect(page.get_by_text("Ожидание на остановке № 2: Synthetic A", exact=True)).to_be_visible()

                waiting = scene.request(oid, "", scene.d, method="GET")
                active = waiting["stop_control"]["active_wait"]
                before = scene.snapshot(oid)
                assert active["waypoint_id"] == waiting["stops"][1]["waypoint_id"]
                assert before["stop_started_at"] == clock
                monkeypatch.setattr(settings, "taxi_stop_control_enabled", False)
                with open_role(scene.d)[0] as dc, open_role(scene.p)[0] as pc:
                    driver, passenger = dc.pages[0], pc.pages[0]
                    expect(driver.get_by_text("Ожидание на остановке № 2: Synthetic A", exact=True)).to_be_visible()
                    expect(driver.get_by_role("button", name="Отметить остановку", exact=True)).to_have_count(0)
                    locked = passenger.locator(".trip-stops__row").nth(1)
                    expect(locked.get_by_text("Водитель ждёт здесь", exact=True)).to_be_visible()
                    expect(locked.get_by_role("button")).to_have_count(0)
                    other = passenger.locator(".trip-stops__row").nth(2)
                    other.get_by_role("button", name="Заменить", exact=True).click()
                    passenger.get_by_role("textbox", name="Новая остановка", exact=True).fill(QUERY)
                    passenger.get_by_role("button", name=NEW["title"], exact=True).click()
                    expect(other.get_by_text(NEW["title"], exact=True)).to_be_visible()
                    updated = scene.request(oid, "", scene.p, method="GET")
                    assert updated["stop_control"]["active_wait"] == active
                    assert updated["stops"][:2] == waiting["stops"][:2]
                    assert updated["stops"][2]["waypoint_id"] != waiting["stops"][2]["waypoint_id"]
                    during = scene.snapshot(oid)
                    for field in ("stop_started_at", "stop_wait_revision", "waiting_fee_kop"):
                        assert during[field] == before[field]

                    clock += timedelta(minutes=5, seconds=30)
                    driver.get_by_role("button", name="Поехали", exact=True).click()
                    expect(driver.get_by_role("button", name="Поехали", exact=True)).to_have_count(0)

                frozen = scene.snapshot(oid)
                assert frozen["stop_started_at"] is None and frozen["waiting_fee_kop"] == before["waiting_fee_kop"] + 1000
                assert posted == [(path + "/waypoints/v1/prepare", 200), (path + "/stop/v1", 200),
                                  (path + "/waypoints/v1", 200), (path + "/stop/v1", 200)]
                scene.request(oid, "/stop/v1", scene.d, body={"action": "resume", "wait_id": active["wait_id"]}, status=404)
                assert scene.snapshot(oid) == frozen
                for role, auth in (("driver", scene.d), ("passenger", scene.p)):
                    assert "stop_control" not in scene.request(oid, "", auth, method="GET")
                    with open_role(auth)[0] as context:
                        page = context.pages[0]
                        if role == "passenger":
                            expect(page.get_by_text(NEW["title"], exact=True)).to_be_visible()
                            expect(page.get_by_role("button", name="Заменить", exact=True)).to_have_count(0)
                        else:
                            expect(page.get_by_role("button", name="Стоим на остановке", exact=True)).to_be_visible()
                            expect(page.get_by_role("button", name="Поехали", exact=True)).to_have_count(0)
                assert not violations
            finally:
                browser.close()
    finally:
        server.shutdown()
        thread.join(5)
        server.server_close()
