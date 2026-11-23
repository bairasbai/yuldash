# Offline preflight for a two-phone taxi test

```sh
python tools/taxi_qa/preflight.py approved-staging.json observations.json
python -m unittest discover -s tools/taxi_qa
```

The first file must come from the reviewer who approved the isolated staging
environment, **not** from the devices or the observation collector. Both files
are JSON. Exit 0 means only that the supplied records agree. The tool does not
connect to anything, collect evidence, verify device identity, resolve DNS,
inspect an APK, check server isolation, or attest that requests were safe.
Do not use a pass as permission to proceed without the actual isolation and
pre-login checks required by [project QA](../../docs/qa.md).

- **Expectation:** `provenance` has exact lowercase full Git object IDs
  (`backend_commit`, `android_commit`, `pwa_commit`), `migration` (the
  applied revision), and lowercase `apk_sha256`, `pwa_dist_sha256`.
  `origins` maps the seven channel names below to approved staging origins.
- **Observation:** separate `provenance` with the same fields, measured
  `installed_apk_sha256` for `android_passenger` and `android_driver`,
  `routes` with the seven channel names, and explicit
  `"public_rideshare": {"path_template": "/r/{id}", "exercised": false}`.
- **Channels:** `android_passenger`, `android_driver` (API seen on each device),
  `pwa_api`, `pwa_document`, `android_share`, `pwa_share`, `dummy_sms_share`.
  Each route contains `origins` in request/redirect order (at least one)
  and `final_origin` from the final response; all must match the channel's
  approved origin. The three share routes also contain literal
  `path_template` and `final_path_template`, each `/t/{token}`. The server-generated SMS link must come
  from a test-only dummy sink, not from a real text. The server's SMS origin
  is independent of the API-derived links in Android/PWA.
- Keep only canonical origins (scheme, lowercase DNS host and optional
  nondefault port; explicit loopback may use HTTP). Never record tokens,
  full links, query strings, real numbers, device serials, credentials, or
  logs. Report unknown/blocked hops, incomplete chains or missing channels as
  missing evidence, not as successful observations. Do not follow an
  unapproved redirect just to collect its destination. Approved DNS names
  still need independent staging verification before any device action.

The synthetic input builder in `test_preflight.py` is a complete schema
example. This check supplements, not replaces, TAXI-E2E-10 or release signing.
