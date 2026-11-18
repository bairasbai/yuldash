# PWA pickup label: narrow correction plan

Base ec5b69ce649341674c0bc110289a27e12852f60c includes published PWA wait-conflict
reconciliation; separate from inactive migration plan bd8c121 and ETA backlog e4b76ae.
No backend, ranking, routing, Android, dispatcher or private-storage changes.

Source-confirmed mismatch: TrackingView `enRoute` is accepted OR arriving
(InstantOrderScreen.tsx:1304). Its subtitle :1629 uses order.eta_min as minutes to pickup.
Backend instant_service.py:1293/1311 computes this field from passenger route duration,
not current driver's travel-to-pickup duration. Header already says driver is coming.
No new estimate can be inferred from search/wait timestamp, map distance or offer field.

Proposed minimal UI correction after independent RED confirmation: omit only the
numeric enRoute subtitle entirely. Preserve existing bilingual factual phase title and
progress, and retain onboard «Хорошей дороги / Юлың уң булһын» subtitle. No new language
string or draft translation is needed; existing RU/BA title is the honest status.
Do not manufacture a countdown, reuse offer pickup fallback or promise later estimates.

One mounted React/jsdom regression on actual TrackingView/screen and real language
provider, no mocked React hooks. Use established isolated test harness pattern; only
map/external boundary stubs, all outbound network denied before imports. Deliberately
long trip ETA (e.g.137) and separate pickup-like value (3) must never appear as numeric
pickup subtitle in accepted/arriving. Confirm both language branches, vary trip ETA,
and preserve onboard title/subtitle. Assert status classification explicitly, not by
removing the conflicting assertion. No backend ETA freshness or actual browser proof.

Save RED against unchanged UI with exact failing assertion and exit, then independent
read/reproduction confirms semantic defect, then sole-writer minimal fix. Static review,
GREEN actual mounted run, independent repeat, existing affected PWA tests, npm check and
build with explicit local API base. JS/cache/temp/logs workspace only. Coordinate heavy
runs with Amio. Browser visual NOT_RUN if normal launch unavailable; do not bypass.
No package install/native build or old interrupted download. No runtime change before
reviewed plan/RED; publication only verified separate branch, never main/production.

## Executed evidence and limits

Source correction freeze: d16ba68927012bf270c2aaffa2b8ac2f268ba076. Only the
TrackingView enRoute subtitle was removed; actual phase title, progress, onboard copy,
backend fields and request logic remain unchanged. A new standalone mounted test is a
separate explicit gate, not silently counted as part of the existing npm check runner.

Independent static review caught fixture issues before first execution: language
provider sync must allow the actual POST /me/update with exact synthetic {language},
not broadly permit mutations; the initial review's PATCH endpoint description was
corrected against source. Hardcoded executor path became required explicit canonical
workspace root/temp containment. Original test freeze6168eb83 and correction69edaa67
remain in local history.

First execution69edaa67: setup FAIL exit1 before mount, because installed jsdom31 does
not export ResourceLoader. Saved under yuldash-validation/pickup-label/author-red1;
this is NOT an ETA RED. Corrected fixture1e165297 uses supported requestInterceptor and
deny-only dispatcher, independently reviewed. A separate synthetic bounded probe proves
one rejected resource request and zero underlying dispatch, without a real network path.

Author RED on unchanged UI1e165297: actual exit1, 14 scenario groups, 12 FAIL and two
onboard PASS; evidence pickup-label/author-red2. Independent RED: same 14/12/2 and exit1,
evidence pickup-label/independent-red-MaT3A8. Accepted/arriving in RU and BA displayed
trip137 as pickup137, polled trip211 as pickup211, and absent trip estimate as NaN pickup.
These failures are attributed to the actual header, not setup or request guards.

After the narrow source fix d16ba689, unchanged test:
- Author GREEN: all14 groups PASS, exit0; pickup-label/author-green1.
- Independent GREEN: all14 groups PASS, exit0; pickup-label/independent-green-rcrOes.
- Both retained resource probe1/0, six exact synthetic language profile updates, zero
  order mutations, cumulative forbidden attempts0, no remaining owned bundle files or
  live application timers. React18.3.1/jsdom31.0.1; no hook/header/TrackingView substitute.
- Poll assertions use actual screen polling callbacks and synthetic allowed responses,
  not live timing/network or real map/browser verification. Missing numeric fields are
  malformed-response defensive controls, not a changed backend schema contract.

Reproduce mounted gate from repository root with separately owned existing paths:

```sh
TEST_WORKSPACE_ROOT=ABSOLUTE_OWNED_WORKSPACE \
TMPDIR=ABSOLUTE_OWNED_WORKSPACE/OWNED_TEMP \
TEST_JSDOM_MODULE_PATH=EXISTING_JSDOM_MODULE_PATH \
node webapp/tests/taxi-pickup-label-mounted-test.mjs
```

Full existing PWA gate on exact clean d16ba689: npm run check, 158 suites / 2956 printed
checks, actual exit0 (48 seconds by recorded UTC start/end), evidence pickup-label/full-check1.
Build on same exact clean source: npm run build (tsc + vite), actual exit0 (20 seconds
by recorded UTC start/end), explicit VITE_API_BASE=http://127.0.0.1:19119, evidence
pickup-label/build1. TMPDIR/npm cache/logs all workspace-owned. Existing large-chunk and
npm update notices retained; no dependency update or deployment performed.

Intermittent pre-shell ENOSPC paused work between gates. Failed launcher invocations
were not counted as tests; no cache/evidence deletion or sandbox bypass. Already running
check handle was awaited to its actual exit; build started only on a later authorized
wake and confirmed resource window. Source/independent evidence remained unchanged.

Visual browser QA NOT_RUN; this package did not rerun the previously blocked Chromium
launcher. No claim of screenshots, real map, cross-tab behavior, Android, native BA
linguistic review, courier stale ETA, traffic-aware dispatch or durable recovery.
Existing arriving title still says driver is coming while progress indicates arrival;
that broader phase-copy issue is explicitly outside this numeric-claim correction.
Migration/storage branches and ranking are untouched. No main merge or production deploy.
