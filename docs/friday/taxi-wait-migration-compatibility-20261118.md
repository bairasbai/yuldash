# Wait intent migration: compatibility comparison and recommendation

DRAFT for independent review. Base local5018de2 / remote09985c3. This document does
not change behavior, schema, clients or activation flags. User goal remains durable
automatic matching after account deletion; this narrower intent problem is a blocker,
not a substitute for that goal. See the actual wait-CAS and test-only episode reports.

## 1. What compatibility can and cannot mean

Additive nullable storage and dormant code can preserve existing callers before any
activation. An optional observed marker can protect updated requests, but the system
remains vulnerable through the unchanged bodyless endpoint and old writers. Universal
stale-intent rejection with unchanged tokenless acceptance is impossible: the old and
fresh request can have identical method/path/body/auth and current server state.

Therefore distinguish three claims:
- **Structural/additive compatibility:** old readers/writers still operate while all
  new enforcement is inactive. This alone provides no episode protection.
- **Mixed-client functional compatibility:** legacy requests retain current behavior;
  only token-aware requests receive a limited stronger contract, and bypass is explicit.
- **Strict protection:** every mutation path for a protected scope requires the client
  observation and all semantic writers invalidate it. Old requests cannot silently
  fall back. Activation necessarily needs an explicit legacy upgrade/refusal policy.
No current design may call all three simultaneously achieved.

## 2. Abandoning an old intention is not banning a new one

Current create-order code (`routers/instant.py:449–473`) locks the passenger on PG,
clears old expired NONNULL waits, and commits this cleanup even when it returns an
already-live order. Old expired NULL rows are excluded. Current `/wait` permits an
eligible old row regardless of another live order. We do not silently change that
admission rule.

The target distinction is:
1. GET/old observation -> a committed order-again/abandonment -> saved old `/wait`
   must lose under a future strict protocol.
2. Committed abandonment -> fresh GET -> a NEW explicit `/wait` on the old eligible
   order may still succeed under the current same-order policy.

The new observation must be made available without automatically replaying the old
mutation. Fetching a current token on behalf of an old queued command and retrying it
would erase its original provenance. A token is not an authorization grant.

## 3. Candidate comparison

### A. Per-order mutation marker; touch all prior relevant orders

Add a marker to each order. Renewal/closure rotates it in the same transaction.
Order-again invalidates previous order markers including NULL rows, rather than just
extending today's nonNULL cleanup SET. Fresh GET of a prior eligible order yields the
new marker and does not imply a ban on deliberate requeue.

Advantages: one-row capability for ordinary wait; closure stays order-local; no extra
passenger marker in every wait request. Risks: the abandonment touched-row set changes,
requires a definition of relevant past/future-eligible rows, and has potentially large
fan-out, locks and scan cost. There is no proven bound on prior eligible orders.
Old ORM snapshots, bulk RETURNING synchronization, rollback and every revival writer
need testing. Touching only today's nonNULL rows is a proven insufficient shortcut.

### B. Passenger abandonment marker plus per-order mutation marker

A client observation contains `(protocol, order identity, passenger abandonment
marker, per-order mutation marker)`. Renewals/closures rotate the per-order marker;
an accepted abandonment transaction rotates the passenger marker even when the old
order itself has NULL wait and is not touched. Fresh GET returns the new pair, so a
new deliberate wait remains distinguishable from the saved old command. This marker
is separate from auth session generation and does not log the passenger out.

Advantages: old-observation invalidation does not require rewriting all historical
NULL-order rows. It matches the cross-order nature of “order again” while allowing
order-local closures. It does NOT eliminate today's existing bulk nonNULL cleanup or
prove bounded latency for the whole create transaction.

Costs: a new cross-row ownership protocol. PostgreSQL waiter must acquire the agreed
passenger-before-order locks before authoritative validation and retain them through
outcome commit; a scalar EXISTS evaluated before a lock wait is not sufficient proof.
SQLite needs a real writer admission mechanism before fresh reads, not FOR UPDATE.
A joined authoritative read must publish a consistent pair; the POST carries that
captured pair unchanged. Account deletion, workers, driver/passenger lock interactions,
ORM dirty state, crash/rollback and restore/old-writer behavior require separate review.
Whether storage is on User or a dedicated owner table is NOT selected here.

### C. Reject waiting on an old order whenever another live order exists

This is a new admission/product restriction, not an equivalent implementation of A/B.
It rejects even a fresh deliberate wait. Do not select it as an invisible optimization
or describe it as a token freshness fix.

## 4. Recommendation (design direction, not implementation approval)

Prefer **B as the next candidate to verify**, with A retained as the simpler reference
alternative. A marker for a passenger's abandonment decision expresses cross-order
invalidation directly and avoids an unproven historical-row fan-out for NULL rows.
Keep per-order marker for renewal/closure so a worker need not rotate a passenger's
whole scope merely to finish one wait. Do not reuse auth generation/search revision,
claim command idempotency, or choose C.

Before choosing a physical schema, test both A/B with the SAME traces and negative
controls, including saved old observation, fresh requeue, returning an existing live
order, failed create transaction, concurrent wait/create both orders, and noncooperative
writers. A replayed create command may repeat abandonment/rotation; current create has
no supplied command identity. Define whether this may invalidate newer observations,
or whether a future command receipt is required. Do not silently label every retry a
new human intention. This is a contract point, not solved by UUID alone.

## 5. Staged legacy-client strategy, all enforcement OFF

1. Inventory actual client contracts and server writers. PWA now handles the specific
   snapshot409 through an owned fresh read; Android currently only displays the error.
   Neither sends an episode marker. A source review is not fleet telemetry.
2. Prepare additive protocol advertisements/read DTO fields and clients which retain
   the exact observed pair through each explicit action, reject malformed/unknown
   versions and never auto-rebase a queued command. Missing capability is a distinct
   legacy state, not permission to invent a current marker server-side.
3. Deploy all-writer-aware binaries with enforcement inactive. Verify old worker/jobs,
   persisted requests, restore snapshots, DB migrations and rollback compatibility.
   Stopping live processes does not remove durable stale requests or old binaries.
4. Observe actual adoption before choosing a strict boundary. PWA cached bundles and
   service workers may keep old JavaScript active; Android versions may remain installed
   indefinitely. No assumed TTL/version percentage is asserted without fleet evidence.
5. Future explicit activation choices: keep legacy orders/scopes weaker, or protect a
   chosen scope with an approved minimum-client/refusal UX. Both old and new endpoints
   must obey that scope; a new /v2 route alone is not a fence. Unsupported old clients
   must not receive false success or silently mutate a strict scope.
6. Unknown/malformed stored mode fails closed in a strict protocol but is NOT repaired
   by normalizing legacy rows blindly. Downgrade/rollback after activation preserves
   commitments and marker semantics; dropping columns is not a safe rollback plan.

Legacy strategy is a planned compatibility window, not a promise of universal safety
within it. No old-client refusal, account restriction or mandatory token is introduced
by this package. Runtime activation requires a later explicit product/rollout decision.

## 6. Next independent evidence gates

- Actual all-writer matrix for a two-marker scope, including get/create/wait/closure,
  User→Order graph and all raw/ORM paths. No lock inversion introduced opportunistically.
- Own disposable PG/SQLite reference comparisons before actual schema; actual backend
  HTTP tests only after the agreed model/API contract, not fixture tests mislabeled API.
- Old/new client-server and rolling-back versions matrix; persisted command never adopts
  a newly fetched marker; forced reuse/restored old epoch remains an explicit hazard.
- Android UX409 can be a separate beneficial client slice WITHOUT any episode protocol
  or behavior activation. Its scope must be independently reviewed against native
  session fencing, coroutine/Compose lifecycle and available executable test harness.

## 7. Independent comparison findings

An independent source review recommends the same compound-marker direction, while
retaining row-only as a reference alternative, and adds an explicit unselected boundary:
**rotate only on actual INSERT versus rotate on accepted order-again including the
existing-order return**. Current cleanup precedes that return. A lost-response retry
may take the existing-order branch, so the choice must not be smuggled into a schema
implementation. Neither boundary is activated or finalized here.

Client rollout evidence: PWA `vite.config.ts` uses `registerType: "prompt"` and the
UpdateBanner permits deferral. A new deploy does not prove every open tab/cached app
shell updated. No forced reload during a ride or silently mandatory Android version
is recommended. Fleet adoption remains unknown, not inferred from repository source.

## 8. Parallel Android409 preparation: source-backed, NOT implemented

Independent read-only Android audit at base5018de2 found an existing native harness,
not a reason to translate the behavior to Python/JS and claim Android proof:

- `data/ApiClient.kt:4967–4974` waitForDriver sends empty JSON and lacks an explicit
  expectedGeneration parameter. `3004` getInstantOrder accepts expectedGeneration,
  but does not request freshRead. `130–141` exposes sessionChanges and generation
  helpers; `5508` ApiException.detailCode is available. Existing transport generation
  fencing and401/network retry must be preserved, not replaced.
- `InstantOrderScreen.kt:1494–1528` already has reactive generation/key and screen
  publisher ownership. Polling1675–1697 checks owner/order but lacks a reconciliation
  publication barrier. Existing isCurrentOrder1595–1598 excludes expired, so blindly
  reusing it would reject the exact wait-card case. Card4937–5005 keeps waitMinutes
  from initial state, uses it as waiting, and displays failure without fresh GET.
- `InstantOrderOwnerBoundaryTest.kt` uses real screen/API, MockWebServer and lifecycle;
  renderNativeMap=false isolates MapKit only. ActiveTripPollingSessionTest,
  ActiveTripSessionChainTest, ApiClientLateMutationSessionTest and
  ApiClientRefreshSessionBoundaryTest are reusable regression gates. The existing
  endpoint contract is in ApiClientEndpointContractTest. Gradle declares JUnit,
  Robolectric4.14.1, Compose tests and MockWebServer.

Prepared narrow contract for a later formal implementation review:
1. Capture `(generation, screen publisher identity, orderId, operationId)` from the
   displayed owner. Validate before transport and after every suspend/finally.
2. Add backward-compatible optional expectedGeneration to waitForDriver and a narrow
   fresh-read option using existing transport facilities. Do not refactor global auth.
3. Exact409 plus wait_snapshot_conflict starts one logical GET; no automatic POST,
   no successful-wait message. Internal transport refresh/retry may use multiple wires.
4. Parent-owned Idle/Reconciling/Unconfirmed/Denied state survives card removal. A
   successful same-ID GET publishes with read-epoch rebase. Failed reads cannot be
   overridden by poll publication; explicit retry only GET.403/404 hide old private
   tracking state;401 follows existing auth teardown.
5. Audit all touched parent publication entrances, including expired-card retry and
   onOrderUpdated, without pretending the whole navigation graph becomes fenced.
6. Waiting derives from confirmed finite server deadline, not persistent waitMinutes.
   Use a bounded lifecycle-aware deadline wake and tie success text to its deadline.
   Cancellation of a coroutine does not prove blocking HTTP work stopped; late result
   ownership checks remain required.

Candidate owned files for review, not authorized changes by this document:
TaxiWaitReconcileController.kt plus its unit tests, InstantWaitConflictReconcileTest.kt,
and narrow ApiClient.kt/InstantOrderScreen.kt integration. MainActivity is excluded.
Tests must include exact/unrelated errors; fresh NULL/future/terminal;403/404/401/network;
explicit GET-only retry; A→B and same-user-generation; held POST/GET/poll; old finally;
double tap; unconfirmed barrier; expiry and old-success→NULL→new-future; actual transport
captured-generation and fresh-read headers. Real Compose/HTTP evidence is required
before claiming mounted/lifecycle behavior, not only a standalone state machine.

### Current execution blocker and safe disposition

The read-only environment checks found Java but no kotlinc/adb in PATH, no ready SDK
or complete Gradle/Kotlin/Robolectric caches in checked standard locations. The prior
partial Gradle download remains partial; it was not restarted. Available environment
inventory reported the user desktop offline. No Gradle build, dependency install,
emulator, secret/config file read or paused operation was attempted in this task.
These are observed tooling limits, not proof that no suitable toolchain exists anywhere.

Accordingly Android tests/build/UI are **NOT_RUN**, and no unverified controller or
screen change is added. The useful independent preparation is the exact native contract,
identified existing harness and acceptance matrix. Next permissible step is to verify
an available approved Android toolchain and independently freeze the contract before
source implementation. Do not mark this UX or durable recovery fixed from this plan.

## 9. Formal review constraints on the recommendation

Journal independently supports B as a candidate for comparison, not schema/runtime
selection. An authoritative joined GET gives one SQL snapshot, not a reservation or
promise of freshness until POST. POST must validate the immutable pair under the agreed
locks; a stale GET should cause a safe conflict. All relevant B writers, including
create and account-deletion interactions, need the same reviewed lock graph. A SQLite
reference global writer lock would not prove production performance.

The abandonment scope must be explicitly **instant-taxi passenger intent**, not silently
rideshare, courier or every scheduled/parallel order. Which scheduled/other taxi orders
share that scope remains a design decision. Per-order closure should not rotate the
whole passenger scope incidentally. Compare A and B under the SAME named INSERT/return
policy in each experiment; neither gets a convenient different policy to look safer.

Do not lazily initialize NULL markers during GET and call that trusted provenance.
A trusted legacy transition, persisted input/rollback contract and forced restoration
of an old PAIR need separate controls. Changing one fresh component in a particular
trace is not a proof about all historical/restored inputs.

The pair would be exposed only where the authenticated passenger owner needs it for
wait, not broadly to driver participants, public payloads or logs as a stable tracking
identifier. It is not a credential, but passenger-wide correlation and DTO privacy need
review. No current payload field is added by this document.

## 10. Proposed reversible next step: comparative SQL reference, not server wiring

Root requests a testable compound-marker step while Android remains NOT_RUN. Proposed
new single standalone file `backend/tests/test_taxi_wait_compound_reference.py`, stdlib
sqlite3/pytest with `--noconftest`, no application imports, existing runner or model
change. Own temporary WAL database only. Formal independent GO required before code.

Represent artificial instant-taxi orders and passenger intent scope with synthetic
markers. Test A and B under the SAME explicit policy parameter each time:
`INSERT_ONLY` versus `ACCEPTED_CREATE_INCLUDING_EXISTING_RETURN`. This compares effects;
it does not choose which policy the product should use. An operation failing before
commit cannot durably invalidate anything. Calling the modeled accepted-create event
is not claimed to be the real create-order handler or auth admission.

A rotates the complete set of this fixture's prior instant-taxi order markers (including
NULL waits), with no silent LIMIT/window. B rotates the fixture passenger instant-taxi
marker and leaves old per-order markers untouched. Both preserve modeled legacy deadline
cleanup of old expired nonNULL waits. Existing return versus actual new-row insertion
and marker change occur in one caller-owned transaction. This does not claim real
historical-row cardinality, performance or production ORM behavior. Other passengers
and non-taxi scope controls must remain unchanged.

A B-observation comes from one joined SQL SELECT and includes both current markers,
order/passenger identity and state. GET observation is not a reservation. Explicit wait
carries the same captured observation through lock contention, validates after a real
SQLite writer admission, and updates deadline+row marker conditionally. No copying the
new scope marker into the old request. Closure uses its captured row owner contract;
worker-like order-local closure must not rotate another order's passenger scope.

Required exact traces:
- Saved observation then modeled committed create/abandonment rejects; fresh joined
  observation of the old eligible order can deliberately wait, even with another live
  fixture order. This explicitly avoids adding C's admission ban.
- Existing-return behavior matches the named policy for BOTH A/B: INSERT_ONLY does not
  invalidate when no row is inserted; ACCEPTED_CREATE does. Preserve the negative
  consequence, not allow-any outcomes. Modeled lost-create-response retry can invalidate
  a newer observation under the latter policy. No command receipt/identity is invented.
- Atomic rollback of new row, cleanup, marker updates and unrelated caller work; fresh
  independent observer before/after commit. Exact SQL constraint error after mutation
  plus explicit rollback, not a fabricated COMMIT-refusal/network-crash claim.
- Two physical SQLite connections: exact BUSY while one writer holds BEGIN IMMEDIATE;
  after explicit commit release the same old observation loses, after rollback it can
  win. Both create-first and wait-first serial outcomes fixed by the named policy.
  Do not call this PostgreSQL User→Order lock proof or performance evidence.
- Per-order closure/same-deadline renewal changes the row component, not whole passenger
  scope; stale pairs lose while another order's observation remains valid if its scope
  did not change. Wrong passenger/id/status and malformed/missing components do not
  mutate or rotate; scope/version stored policy cannot be downgraded by a request.
- Noncooperative create that rotates neither relevant marker still admits stale wait;
  a partial A invalidation skipping NULL rows still fails; replacing only a new order's
  marker does not fence old rows. These are explicit negative controls.
- Force restoration of the old PAIR after intervening activity and demonstrate stale
  acceptance. A single newer component rejects the old pair, but is not a general
  backup/history guarantee. No lazy GET initialization or raw-marker normalization.

Only owned temporary files/sidecars, connections closed in finally, unique logs and
retained first failures. Synthetic non-reuse bookkeeping is fixture-only. Source diff
must show no application/model/Android/PWA/migration changes. Author plus independent
static/execution acceptance before publication; no inherited FULL or PG claim. Actual
HTTP, deployed all-writer fencing, physical schema and strict activation remain later.

For Android the concrete prerequisite list is: an available approved JDK/Gradle wrapper
installation, Android SDK compatible with the checked-in project, required dependencies
and Robolectric runtime artifacts, plus the existing native test harness on an available
execution environment. Versions must follow the project, not be changed to make a test
run. Confirm no competing Gradle/emulator owner first. The interrupted download and
paused native operations are not resumed by this plan.

## 11. Pre-code clarification: cleanup closure is not abandonment rotation

Formal journal GO is limited to the single test file and the exact conditions in§10.
Independent reviewer identified an important refinement before code: preserving legacy
NONNULL→NULL deadline cleanup is itself an order-local closure and must rotate THAT
row marker in BOTH variants and under BOTH create policies. Thus B leaves per-order
markers untouched only for rows not actually closed/renewed; it must not preserve an
old marker on a changed NONNULL deadline. INSERT_ONLY controls the separate passenger/
all-prior abandonment invalidation, not whether real row closure gets its marker.

Use old NULL rows to isolate the cross-order existing-return policy: INSERT_ONLY leaves
that old observation valid, ACCEPTED_CREATE invalidates it. Separately use old NONNULL
rows and assert closure invalidation under both policies, including existing return.
Under INSERT_ONLY existing return the B passenger marker stays unchanged while the
closed row marker rotates. Under an abandonment event B may rotate both scope marker
and markers of actually closed rows. A may combine closure and abandonment into one
fresh final row marker per affected row. Compare semantic outcomes, not synthetic marker
identity or number of allocator calls. No policy is selected for the real endpoint.

Fixtures for another passenger and non-taxi domain remain untouched; scheduled is
explicitly excluded/unsupported, not accidentally assigned an intent policy. Read-only
capture must not initialize NULL markers. Fresh observers, exact errors/rollback,
restored-pair negatives and no FULL/PG/runtime claims remain mandatory.

A/B variant also comes from authoritative fixture configuration, not a request switch.
The B request must carry both correctly shaped components; presenting A's shape cannot
omit the passenger component. Define and test missing/malformed scope/version/marker
components for each variant without mutation or snapshot adoption.

### Exact checked-in Android toolchain inputs (read-only)

`android/gradle/wrapper/gradle-wrapper.properties` requests Gradle8.13.
`android/build.gradle.kts` declares AGP8.13.2 and Kotlin/Compose plugins2.3.0.
`android/app/build.gradle.kts` uses compileSdk36/targetSdk36, minSdk26 and JVM17
source/target. Installed Java reports OpenJDK21.0.13; that observation is not a verified
compatible native build. Provision/availability must honor the checked-in versions and
SDK36 artifacts, without silently changing plugins/SDK/JVM configuration.

A read-only capacity check also found `/tmp` tmpfs full while the workspace had free
space. No unrelated files or retained evidence were deleted. New reference-test owned
temporary roots/logs will use the workspace; this environmental choice does not change
the tested SQLite protocol. Native build remains NOT_RUN; old partial download remains
untouched.

## 12. Executed compound reference comparison and recommendation status

Executable freeze: `da349243de846bac5f2a3eaddf764afa8303a0d7`.
Only this document and new `backend/tests/test_taxi_wait_compound_reference.py` change
relative to local5018de2. No application, Android/PWA, schema, migration or existing
runner changes. Android remains NOT_RUN; no native source was published or activated.

The first attempted author write/static was blocked before shell by launcher ENOSPC
at `/tmp/.git`. After a root-approved ordinary probe, WIP was saved and static checks
passed; another ordinary read hit the same environmental blocker and commands stopped.
No test PASS was inferred from either interruption. Later one explicitly approved
ordinary probe and minimal scoped run worked using workspace-only temporary storage.
No unrelated files or sole retained artifacts were deleted to make room, and no paused
native download or operation was resumed.

First executed WIP run: **22 passed, 78 failed, actual exit1, 2.38s**. All78 failures
were the observer fixture's common `ORDER BY 1,2` on the one-column configuration table,
before those protocol assertions. Original evidence is retained under
`yuldash-validation/compound/probe-author1`. The correction gives each of four fixed
fixture tables its explicit sort keys; no assertion was weakened. The preplanned
contention matrix was also completed with insert=False/True for both policies and both
writer orders. Initial WIP had100 cases, final matrix has116; not a relabeling of the
old run. Static Ruff, py_compile and diff checks passed, then independent source review
approved the frozen file before rerun.

Author rerun: **116 passed, 2.79s, actual exit0**; evidence
`yuldash-validation/compound/author2`.
Independent rerun: **116 passed, 3.10s, actual exit0**; evidence
`yuldash-validation/compound/independent-DjWphP`, SHA before/after identical and tree clean.
Both verified zero remaining owned DB files/sidecars. All new logs, TMPDIR, pycache and
pytest basetemp were in owned workspace paths, not the full `/tmp` volume.

Command from repository root (paths must name separately owned directories):

```sh
TMPDIR=OWNED_WORKSPACE_TMP PYTHONPYCACHEPREFIX=OWNED_WORKSPACE_PYCACHE \
  /tmp/taxi-next-venv/bin/python -m pytest --noconftest -p no:cacheprovider \
  backend/tests/test_taxi_wait_compound_reference.py -q \
  --basetemp OWNED_WORKSPACE_PYTEST
```

This run deliberately does not import application conftest or reuse the earlier
reference fixture. No PostgreSQL, application FULL, native build or actual HTTP handler
run is claimed for this package. Evidence proves the artificial SQL transaction traces,
not operational performance, global deadlock freedom or deployment compatibility.

### What the comparison actually supports

- Under either named policy A/B give the specified same semantic outcomes; marker
  byte values/allocator call counts are not compared as product behavior.
- Both invalidate actually closed NONNULL rows, including INSERT_ONLY existing-return.
  The policy difference is isolated on old NULL observations.
- ACCEPTED_CREATE invalidates old NULL observations even on an existing return; its
  modeled lost-response create retry can also invalidate a newer observation. Under
  INSERT_ONLY the existing-return old NULL observation stays usable. Neither is
  secretly selected for the real product.
- A fresh observed old eligible order can still deliberately wait despite another live
  fixture order. No cross-order admission prohibition was added.
- Scoped passenger/domain controls, caller rollback, independent visibility and exact
  SQLite BUSY with immutable observations are checked. This is not PG User→Order proof.
- Noncooperative writers, partial NULL-skipping invalidation and forced old-pair
  restoration remain successful stale-write negative controls. Passing them proves
  limitations rather than universal safety.

Independent final review found no P1/P2 in the stated reference scope. Important limits:
A/B configuration is fixed per fixture; live A→B rollout is NOT tested. The helper's
NULL-deadline mutation proves per-order component rotation without scope rotation,
NOT an independently implemented real worker closure under a changed passenger epoch.
The fixture marker allocator is not atomic committed history or a production registry.
Modeled response loss is not a network/process-kill experiment or receipt replay.

Recommendation remains B as the next candidate for actual lock/DTO/writer design, not
an approved schema or activation. Choosing INSERT versus accepted-return semantics,
legacy-client transition/refusal, trusted NULL adoption/restore and complete writer
fencing remains necessary. Durable account-deletion continuation is still unfinished.
