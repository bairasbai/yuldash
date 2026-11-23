"""Validate a supplied taxi staging QA record; no network or device access."""

import argparse
import json
import re
import sys
from pathlib import Path

BUILD = {"backend_commit": 40, "android_commit": 40, "pwa_commit": 40,
         "apk_sha256": 64, "pwa_dist_sha256": 64}
CHANNELS = ("android_passenger", "android_driver", "pwa_api", "pwa_document",
            "android_share", "pwa_share", "dummy_sms_share")
SHARES = CHANNELS[-3:]
ORIGIN = re.compile(r"(https?)://([a-z0-9-]+(?:\.[a-z0-9-]+)+)(?::([1-9][0-9]{0,4}))?")
PUBLIC_HOST = "yulbash.ru"


def _keys(obj, fields):
    return type(obj) is dict and obj.keys() == set(fields)


def _provenance(obj):
    if not _keys(obj, (*BUILD, "migration")):
        return False
    return all(type(obj[key]) is str and re.fullmatch(f"[a-f0-9]{{{n}}}", obj[key])
               for key, n in BUILD.items()) and (
        type(obj["migration"]) is str
        and bool(re.fullmatch(r"[a-zA-Z0-9_\-]{1,80}", obj["migration"]))
    )


def _origin(value):
    match = ORIGIN.fullmatch(value) if type(value) is str else None
    if not match:
        return False
    scheme, host, port = match.groups()
    if host == PUBLIC_HOST or host.endswith("." + PUBLIC_HOST):
        return False
    if len(host) > 253 or any(len(label) > 63 or label.startswith("-") or label.endswith("-")
                              for label in host.split(".")):
        return False
    if host != "127.0.0.1" and (scheme != "https" or not re.fullmatch(r"[a-z]{2,63}", host.split(".")[-1])):
        return False
    return not port or int(port) <= 65535 and port != ("443" if scheme == "https" else "80")


def validate(expected, observed):
    """Check two separately supplied records; error codes never echo their data."""
    if not _keys(expected, ("provenance", "origins")) or not _provenance(expected["provenance"]):
        return "invalid_expected_provenance"
    origins = expected["origins"]
    if not _keys(origins, CHANNELS) or not all(_origin(origin) for origin in origins.values()):
        return "invalid_expected_origins"
    if (origins["android_passenger"] != origins["android_driver"]
            or origins["android_share"] != origins["android_passenger"]
            or origins["pwa_share"] != origins["pwa_api"]):
        return "inconsistent_client_origin_expectation"
    if not _keys(observed, ("provenance", "installed_apk_sha256", "routes", "public_rideshare")):
        return "incomplete_observation"
    if not _provenance(observed["provenance"]) or observed["provenance"] != expected["provenance"]:
        return "provenance_mismatch"
    installed = observed["installed_apk_sha256"]
    if (not _keys(installed, CHANNELS[:2])
            or any(digest != expected["provenance"]["apk_sha256"] for digest in installed.values())):
        return "installed_apk_mismatch"
    if observed["public_rideshare"] != {"path_template": "/r/{id}", "exercised": False} or (
        observed["public_rideshare"]["exercised"] is not False
    ):
        return "public_rideshare_in_scope"
    routes = observed["routes"]
    if not _keys(routes, CHANNELS):
        return "missing_route_observation"
    for channel in CHANNELS:
        route = routes[channel]
        fields = ("origins", "final_origin")
        if channel in SHARES:
            fields += ("path_template", "final_path_template")
        if not _keys(route, fields) or (channel in SHARES and
                (route["path_template"], route["final_path_template"]) != ("/t/{token}", "/t/{token}")):
            return f"invalid_route_observation:{channel}"
        chain = route["origins"]
        # All observed hops and the reported response origin must stay on the
        # same authorized per-channel origin; never follow a redirect here.
        if (type(chain) is not list or not chain or route["final_origin"] != origins[channel]
                or any(hop != origins[channel] for hop in chain)):
            return f"origin_mismatch:{channel}"
    return None


def _unique(pairs):
    obj = dict(pairs)
    if len(obj) != len(pairs):
        raise ValueError
    return obj


class _Parser(argparse.ArgumentParser):
    def error(self, _message):
        # argparse's default message can contain raw argument values.
        self.exit(2, "FAIL: invalid_arguments\n")


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = _Parser(prog="preflight.py", description=__doc__, allow_abbrev=False, add_help=False)
    parser.add_argument("expectation", type=Path, help="independently approved staging expectations")
    parser.add_argument("observation", type=Path, help="redacted observations")
    # Built-in short help can accept a suffix (e.g. -hfoo); help is only valid alone.
    if argv in (["-h"], ["--help"]):
        parser.print_help()
        return 0
    args = parser.parse_args(argv)
    try:
        # Never print capture values, paths, credentials or parse exception text.
        with args.expectation.open(encoding="utf-8") as first, args.observation.open(encoding="utf-8") as second:
            failure = validate(json.load(first, object_pairs_hook=_unique),
                               json.load(second, object_pairs_hook=_unique))
    except (OSError, ValueError, RecursionError):
        failure = "invalid_input"
    if failure:
        print(f"FAIL: {failure}")
        return 1
    print("PASS: supplied observations match expectation; device and network safety not verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
