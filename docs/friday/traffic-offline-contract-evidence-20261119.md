# Offline traffic reference harness: verified evidence

Base: [offline design dc3cf741](https://github.com/bairasbai/yuldash/blob/dc3cf74159ba247a4c67b0083d71415e87334ad9/docs/friday/traffic-aware-offline-acceptance-20261119.md).
Scope: standalone synthetic stdlib reference model. No application imports, provider, networking, database, production rank, or real ETA/UI acceptance.

## New checked invariant

A numeric pickup **estimate** must derive from the same fresh captured/current context, never from search waiting or passenger trip duration. Cache reads do not rejuvenate original sample timestamps. Unknown traffic observation age cannot justify a live-traffic flag.

Complete equal-quality comparable synthetic road cohorts can be ordered for the diagnostic. Mixed, incomplete, unavailable, stale, proxy or unequal-quality cohorts return `UNDECIDED` and no fallback order. No product mixed-failure policy or new ranking weights are selected.

## Execution

Source SHA256: `767a2da1f0b623d7c83396f59f4ba67d644a0ab4ab9f675c4ad9a4144c222558`.

- Independent frozen static review: PASS.
- Independent unittest: **18 methods plus embedded subtests, 0.001s, actual exit 0**.
- Author/coordinator repeat: **18 methods plus embedded subtests, 0.002s, actual exit 0**.
- Source SHA256 unchanged after execution; bytecode generation disabled.
- Run from repository root: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s docs/friday/fixtures -p test_traffic_offline_contract.py -v`.

Assertions cover clock/label separation; exact route-age and cache-expiry boundaries; future/malformed metadata; fresh HTTP versus traffic age; stale/moved position; owner/order/candidate/vehicle/pickup/profile/departure changes; invalid duration; explicit synthetic zero semantics; A1km/12min vs B2km/4min reversal; cohort uncertainty and shortlist limitations.

Distinct bad controls demonstrate copied trip duration, read-time cache rejuvenation, and fabricated live-traffic labels violating original provenance.

## Preserved review finding

Initial frozen `10ff5a270c590502a13d451c5696aac6237e79dbf166e396cf55187dceb5cbca` had a **static P2 test-oracle gap**, not a discovered app bug: numeric output validation did not reject a fabricated `traffic_fresh=True`. Corrected before execution, then added unknown/stale negative and known-fresh positive assertions. Initial source was preserved in local evidence; it was not run, so no fabricated RED execution is claimed.

## Boundaries

Synthetic positions are opaque IDs, not real coordinates or geographic-validation proof. Thresholds, timestamps, quality and zero-colocation semantics are injected fixture values, not production defaults. The estimate is not an arrival countdown. Current-context equality is a pure model checkpoint, not atomic/concurrent publication protection. Arbitrary hostile-object/unbounded-number security is outside this fixture scope.

No actual provider/parser, courier tracking endpoint, PWA/Android, GPS, HTTP/auth, database locks, integration/FULL or live trial was tested. Existing pricing fallback and production candidate selection are unchanged. Licensing, quota/accounting, privacy, partial-cohort scoring and activation decisions remain open in the design handoff. No vendor contact, external routing calls, trial activation, dependencies, CI dispatch, main/prod/schema changes. PG readiness remains HOLD.
