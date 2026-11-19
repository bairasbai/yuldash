"""Offline actual-module tests. Locust is stubbed; no HTTP, server or load is run."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch


class Response:
    def __init__(self, status=200, data=None):
        self.status_code = status
        self.data = data if data is not None else dict(price=123, distance_km=2.5, eta_min=8, category="standard")
        self.results = []
    def json(self):
        if isinstance(self.data, Exception):
            raise self.data
        return self.data
    def success(self): self.results.append("success")
    def failure(self, message): self.results.append(message)
    def __enter__(self): return self
    def __exit__(self, *args): return False


class Contract(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {
            "YULDASH_LOADTEST_INSTANT": "1", "YULDASH_LOADTEST_WS": "0",
            "YULDASH_LOADTEST_INSTANT_FIXTURE": "isolated-no-external-providers",
            "YULDASH_LOADTEST_INSTANT_ORIGIN": "http://127.0.0.1:18080",
            "YULDASH_LOADTEST_INSTANT_TOKEN": "synthetic-not-a-real-token",
            "YULDASH_LOADTEST_INSTANT_ROUTE": '{"from_lat":1,"from_lng":2,"to_lat":3,"to_lng":4}',
        }, clear=True)
        self.env.start(); self.addCleanup(self.env.stop)
        stub = types.ModuleType("locust")
        stub.HttpUser = object
        stub.between = lambda *args: None
        stub.task = lambda fn: fn if callable(fn) else (lambda fn: fn)
        stub.events = types.SimpleNamespace(quitting=types.SimpleNamespace(add_listener=lambda fn: fn))
        spec = importlib.util.spec_from_file_location("offline_locustfile", Path(__file__).with_name("locustfile.py"))
        self.mod = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {"locust": stub}): spec.loader.exec_module(self.mod)
        self.user = self.mod.InstantUser()
        self.user.host = "http://127.0.0.1:18080"
        self.calls = []; self.stops = []
        self.user.stop = lambda: self.stops.append(True)
        self.response = Response()
        def post(*args, **kw):
            self.calls.append((args, kw)); return self.response
        self.user.client = types.SimpleNamespace(post=post)

    def test_exact_positive_request_and_single_attempt(self):
        self.user.on_start(); self.user.estimate(); self.user.estimate()
        self.assertEqual(len(self.calls), 1)
        args, kw = self.calls[0]
        self.assertEqual(args, ("http://127.0.0.1:18080/instant/estimate",))
        self.assertEqual(kw["json"], dict(from_lat=1, from_lng=2, to_lat=3, to_lng=4))
        self.assertEqual(kw["headers"], {"Authorization":"Bearer synthetic-not-a-real-token"})
        self.assertFalse(kw["allow_redirects"]); self.assertEqual(kw["timeout"], 5)
        self.assertTrue(kw["catch_response"])
        self.assertEqual(self.response.results, ["success"])
        self.assertFalse(hasattr(self.mod.InstantUser, "create_order"))

    def test_disabled_never_requests(self):
        self.mod.INSTANT_ENABLED = False
        self.user.on_start(); self.user.estimate()
        self.assertEqual(self.calls, [])

    def test_invalid_config_never_requests(self):
        cases = [("YULDASH_LOADTEST_INSTANT_FIXTURE", ""),
                 ("YULDASH_LOADTEST_INSTANT_TOKEN", ""),
                 ("YULDASH_LOADTEST_INSTANT_TOKEN", "bad\ntoken"),
                 ("YULDASH_LOADTEST_INSTANT_ROUTE", "{}"),
                 ("YULDASH_LOADTEST_INSTANT_ROUTE", "null"),
                 ("YULDASH_LOADTEST_INSTANT_ROUTE", '{"from_lat":true,"from_lng":2,"to_lat":3,"to_lng":4}'),
                 ("YULDASH_LOADTEST_INSTANT_ROUTE", '{"from_lat":NaN,"from_lng":2,"to_lat":3,"to_lng":4}')]
        for key, val in cases:
            with self.subTest(key=key, value=val), patch.dict(os.environ, {key:val}):
                with self.assertRaises(ValueError): self.user.estimate()
                self.assertEqual(self.calls, [])

    def test_wrong_origins_never_requests(self):
        for host in ["https://yulbash.ru", "http://localhost:18080", "http://127.0.0.1:18081", "http://127.0.0.1:18080/path", "http://u@127.0.0.1:18080", "http://127.0.0.1:18080?x=1"]:
            with self.subTest(host=host):
                self.user.host = host
                with self.assertRaises(ValueError): self.user.estimate()
                self.assertEqual(self.calls, [])
        for origin in ["http://example.invalid:18080", "http://127.0.0.1:18080/path", "http://127.0.0.1:bad"]:
            with self.subTest(origin=origin), patch.dict(os.environ, {"YULDASH_LOADTEST_INSTANT_ORIGIN":origin}):
                self.user.host = origin
                with self.assertRaises(ValueError): self.user.estimate()
                self.assertEqual(self.calls, [])

    def test_all_non_200_statuses_fail(self):
        for status in [0,201,204,301,302,307,400,401,403,404,422,429,500,503]:
            with self.subTest(status=status):
                r=Response(status); self.mod._check_instant_quote(r)
                self.assertEqual(r.results, [f"instant estimate HTTP {status}"])

    def test_malformed_quotes_fail_without_response_logging(self):
        for data in [[],{},"secret",ValueError("secret"), dict(price=True,distance_km=1,eta_min=2,category="standard"),dict(price=1,distance_km=float('inf'),eta_min=2,category="standard"),dict(price=1,distance_km=1,eta_min=-1,category="standard"),dict(price=1,distance_km=1,eta_min=1,category="comfort")]:
            with self.subTest(data=type(data).__name__):
                r=Response(data=data); self.mod._check_instant_quote(r)
                self.assertEqual(len(r.results),1); self.assertNotEqual(r.results,["success"])
                self.assertNotIn("secret",r.results[0])

    def test_unrepresentably_large_numbers_fail_cleanly(self):
        huge = 10 ** 400
        route = json.dumps(dict(from_lat=huge, from_lng=2, to_lat=3, to_lng=4))
        with patch.dict(os.environ, {"YULDASH_LOADTEST_INSTANT_ROUTE": route}):
            with self.assertRaises(ValueError):
                self.user.estimate()
            self.assertEqual(self.calls, [])
        r = Response(data=dict(price=huge, distance_km=1, eta_min=1, category="standard"))
        self.mod._check_instant_quote(r)
        self.assertEqual(r.results, ["instant estimate malformed quote"])

    def test_failure_does_not_retry(self):
        self.response=Response(429)
        self.user.estimate(); self.user.estimate()
        self.assertEqual(len(self.calls),1)
        self.assertNotIn("success",self.response.results)

    def test_transport_exception_stops_without_retry(self):
        def fail(*args, **kw): raise OSError("synthetic transport")
        self.user.client.post=fail
        with self.assertRaises(OSError): self.user.estimate()
        self.assertEqual(len(self.stops),1)
        self.user.estimate(); self.assertEqual(len(self.stops),2)


if __name__ == "__main__": unittest.main()
