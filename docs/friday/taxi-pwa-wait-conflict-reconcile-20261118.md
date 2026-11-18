# PWA /wait conflict reconciliation: formal pre-code plan

Status: docs-only for journal review. NO implementation GO. Baseline clean local `a22a061`, published remote `64b824a`. Only PWA; Android, backend, schema, account policy, episode protocol and pending producer/consumer remain unchanged. Apply repository AGENTS, [reliability](../agent-reliability.md) and orchestration single-writer rules. React best-practices skill read: use existing React18, stable primitive hook dependencies, refs for transient ownership, event-driven requests, no new framework/library.

## 1. User value and boundaries

An exact rejected `/wait` must not leave a false-success view. After HTTP409 with detail.code `wait_snapshot_conflict`, perform one logical fresh order GET owned by the same mounted action/account/order; show fresh state or an explicit refresh failure. Never replay the mutating POST automatically. A manual READ retry is a different action from a new deliberate wait click.

This is error reconciliation, not a durable intent producer or an episode token. It does not prevent delayed-before-first-read tokenless requests, NULL/future/NULL ABA, same-deadline ABA, old-order live-admission issues, or lost-ACK ambiguity. Fresh GET is authoritative for its response, not for all future time. The backend GET can reconcile offers; the client does not create pending.

## 2. Exact inspected source contract

- `webapp/src/api/client.ts` ApiError has status/message/code. request awaits reconcileSession, checks supplied `_generation` before fetch and after response, retries401 only by existing auth policy. Fetch AbortSignal is forwarded. Its catch converts fetch abort/network errors to ApiError0, so abort alone is not sufficient ownership proof; caller must ignore stale outcomes explicitly. No general client.ts change is proposed.
- `api/instant.ts:fetchInstantOrder(id,signal?)` uses apiGet; `waitForDriver(id)` bodyless apiPost. Both need optional captured account-generation options without breaking current call sites. A refresh uses cache no-store. This is authentication generation, NOT search_revision/episode.
- `TaxiTripActions.tsx:WaitForCarCard` has inFlight/busy/local waitUntil; success calls onWaiting; catch currently generic. wait status derives from serverMs(deadline)>Date.now. Error does not presently refresh.
- `InstantOrderScreen.tsx` refreshOrder is void, swallows errors and unconditionally then(setOrder). Polling independently fetches every3.5s with only an effect alive guard. TrackingView passes onWaiting=onRefresh. Reusing this callback unchanged would hide reconciliation failure and allow a pre-conflict poll to overwrite the refreshed view.

## 3. Allowed ownership/files after GO

Sole author owns:
1. `webapp/src/api/instant.ts`: backward-compatible optional signal/captured generation for wait/get, no request payload change, cache no-store for reconciliation.
2. `webapp/src/components/TaxiTripActions.tsx`: narrow WaitForCarCard operation/error/read-retry UI; no unrelated taxi/delivery panels rewritten.
3. `webapp/src/screens/InstantOrderScreen.tsx`: narrow tracking-order read ownership fence and awaited conflict-read callback threaded through TrackingView. No global polling framework.
4. Optional small `webapp/src/utils/waitConflictReconcile.ts`: exact conflict predicate and operation/read-owner coordinator only if it avoids duplicating gate semantics and is executable independently. No global singleton or server revision ordering.
5. New `webapp/tests/wait-conflict-reconcile-test.mjs` (plus an isolated fixture module/HTML only if required for mounted verification, explicitly owned before writing).
6. `webapp/tests/run.mjs`: register the fixed new suite, unchanged existing suite semantics/counting.
7. `webapp/tests/taxi-wait-cancel-test.mjs`: narrow existing hook-stub/import/prop signature adaptation only; preserve its cancellation assertions, never present it as mounted lifecycle proof.
8. This plan/evidence doc until ownership transferred to coordinator.

No backend/client.ts generic transport redesign, Android, mobile/, package version/dependency/lockfile, generated credentials or production settings edits. Parent coordinates independent review, builds, actual browser QA and publication. Narrow scope extensions require explanation/review before edits.

## 4. Action state and error disposition

At an explicit wait click capture order ID, account generation, per-mount/operation identity and an AbortController. Reject duplicate taps synchronously via inFlight ref, not merely state rendering. Retain existing success behavior, but only the still-owned result can update deadline/note or invoke callbacks.

Only `error instanceof ApiError && status===409 && code==='wait_snapshot_conflict'` enters reconciliation. It is still a failed mutation; do not call success onWaiting or write a waiting-success note. Invalidate older tracking reads BEFORE the reconciliation GET, keep busy while the GET settles, call one awaited fresh GET under captured generation/signal.

Successful GET:
- Validate response.id matches captured order; only same live mount/action/account/order may apply it. Parent applies via guarded state update, not blind setOrder.
- Update card deadline from the returned order. If deadline is NULL/past, do not show waiting. If future, it is existing server state, not success of the rejected POST. Accepted/cancelled/done render existing tracking/final views and may unmount the card.
- Show a bilingual neutral conflict/refreshed explanation when card remains mounted, not a success toast. Require a new user click for another /wait. Do not automatically create a new order or return to compose.

Failed reconciliation GET:
- Show bilingual “state could not be refreshed” and an explicit READ-only retry action. Mark the local state unconfirmed; do not continue asserting stale waiting success or automatically re-enable a wait mutation based on stale data.
- While unconfirmed, mutation buttons in this card (wait/stop-wait/order-again) must not act on stale state. Offer refresh plus ordinary safe navigation; no implicit cancel or new-order mutation. Exact visual arrangement is reviewed with browser QA.
- Manual retry performs one owned GET, not POST. On success leave unconfirmed state and display fresh order; another wait requires another explicit click.
- Unrelated409, generic errors and normal auth/IDOR failures retain existing failure behavior and do not enter this retry flow. If the reconciliation GET returns401/403/404, do not expose payload or convert it to success. Keep truthful read error/normal auth handling; no fabricated order closure.

All paths release busy only for the operation that owns it. Unmount/order replacement/account change invalidates operation and aborts fetch; late errors/results are ignored even if transport converts abort to ApiError0. Do not let an old finally clear a newer operation's busy/ref.

## 5. Tracking read fence, limited scope

A conflict-specific GET must not be overwritten by a poll or ordinary refresh started before it. Use a screen-local tracking read epoch/token bound to account generation and current order ID. Starting conflict reconciliation advances the epoch and invalidates existing poll/refresh responses. During conflict read, do not admit a background poll that can race the reconciliation result; resume after completion under the new epoch. Older responses are rejected by epoch and ID checks.

Apply this gate only to the tracking poll and explicit tracking refresh that write the same order, plus the new awaited conflict callback. Do not rewrite boot/geolocation/quote/nearby-driver polling. Screen switch/unmount invalidates the gate. Existing other callbacks may still update their explicitly owned state; avoid claiming globally ordered server snapshots. Never order status updates by search_revision: multiple statuses share one revision.

Use primitive effect dependencies. Preserve polling cleanup and intervals; avoid an effect firing another mutation. Functional setOrder can verify the current order ID at commit, combined with account/operation checks. Callback identity changes must not accidentally reset an active user operation. In-flight owner refs need cleanup on replacement/unmount; cancellation is not proof of server rollback.

## 6. Executable matrix

Transport/coordinator:
- exact409/code -> one POST then one logical GET under same captured generation; no second POST even after refresh failure;
- unrelated409/missing code/network error -> no conflict GET;
- same-account token refresh permitted by existing transport, account generation change between POST/GET prevents a new-account request/application;
- GET cache no-store and AbortSignal propagation; request shape bodyless unchanged; id mismatch rejected;
- explicit read retry increments GET only; rapid taps/read-retries dedup; old finalizer cannot release newer busy.

Real component/lifecycle:
- fresh NULL/future/accepted/cancelled/done, no success message for conflict;
- GET timeout/401/403/404, unconfirmed state/read-only retry; click retry never POST;
- unmount, different order, logout/account switch while POST or GET held; no late setter/callback;
- held pre-conflict poll returns after refreshed order: ignored; poll resumes without duplicate intervals or overwriting by old epoch;
- success path remains normal; RU/BA plus light/dark layouts/loading/error states and accessibility role/button disabled state.

Unit coordinator and mocked hook tests can prove branch/ownership logic but are NOT real React lifecycle proof. Run existing check/build and independent review; preserve first failures and classify source vs test faults rather than loosening assertions.

## 7. Mounted runtime/tool feasibility and safe browser gate

Read-only inventory: actual react/react-dom packages and Vite/esbuild exist in webapp/node_modules; `/usr/bin/chromium` exists. No jsdom/Playwright package is in the inspected webapp dependency tree, but the coordinator verified existing `/tmp/yuldash-ui-test/node_modules/jsdom` and `playwright-core` plus `/usr/bin/chromium`. These permit actual mounted tests without adding dependencies. Existing component tests often mock hooks/JSX or static render; do not relabel those as mounted lifecycle evidence. No package/browser was installed or launched for this plan.

Preferred mounted proof: parent-coordinated Chromium/cloud browser against a localhost-only harness that mounts the real React18 component/screen with controlled deferred transports. Reuse existing tools/harness where possible; a browser HTML fixture may mount compiled actual modules without a new test dependency. Bundle compile alone is not mount evidence. Use the existing Playwright-core/Chromium transport with real ReactDOM createRoot, LangProvider and MemoryRouter; the screen integration additionally uses actual AuthProvider/RequireAuth and controlled synthetic API responses. Test both affected card and screen ownership boundary. Do not replace React hooks. Parent/independent reviewer runs the isolated browser gate, with request allowlist installed before navigation and serviceWorkers blocked.

Before browser navigation/build for QA: explicit localhost VITE_API_BASE (never default production), verified isolated mock/dev backend, synthetic session/device state and network-deny allowing only the declared local resources. Prevent real notifications/auth/service-worker endpoints; synthetic credentials only. No production requests, real user data or secret reads. Browser QA verifies the actual served bundle/source identity. Chromium executable presence alone does not guarantee launch permissions or successful mount; if blocked, report that exact gate, do not bypass restrictions or claim ready.

Commands after GO/coordination: focused registered Node suite, `npm run check`, `npm run build` with explicit safe QA configuration for served artifact. Inspect actual scripts (check and build exist; no invented lint script). No Android build claims. Backend FULL or another heavy suite is not launched by this author.

## 8. Review/activation boundaries

Journal reviews UI failure contract, one-read ownership, same-session fencing and poll epoch before source edits. Then incremental code/tests freeze, author checks, independent static/runtime/browser review, parent verified separate-branch publication. No main/production rollout. Native BA linguistic QA is separate from both-language assertions. This package does not satisfy durable matching intent ownership or episode provenance; producer/consumer stay off and four backend negative diagnostics remain valid.

## 9. Final review clarifications: publication provenance and precise failure UI

Source audit found EVERY tracking-order publication entrance in InstantOrderScreen: boot fetchMyOrders callback (around208), tracking interval callback256–257, refreshOrder283, ComposeView onOrdered callback342 (actual createInstantOrder await527–529), and backToCompose clearing269. These are not all currently fenced. AuthProvider subscribes session changes and normally sets loading/guest, while RequireAuth hides children for loading/unavailable/guest. This is source-intended unmount, but React batching/fast responses do not guarantee every swap commits an intermediate unmount; login may also change an already-authed user. Do NOT rely on guaranteed unmount. A stale displayed order cannot capture the new account generation at click and become newly authorized.

Proposed narrow ownership boundary in this SAME screen file: outer actual useAuth consumer renders the existing screen body as an owner-keyed child using `(user.id, accountGeneration)`. The child captures that rendered owner; all boot and create-result publication checks it after await, and only orders received under that owner enter tracking. This is a local screen lifetime boundary, not a global AuthProvider/RequireAuth edit. Render only authed confirmed owner; a generation mismatch also blocks event admission immediately, before React effect cleanup. Actual providers must be mounted in integration tests, including authed-A -> authed-B without an intermediate guest screen and same-user/new-generation replacement.

Boot/creation scope justification: merely fixing the two tracking GETs leaves old boot/create callbacks able to install foreign/unowned order data, invalidating same-account claims. Guard these publication entrances with captured owner and mount/operation without refactoring geolocation/quote/nearby polling. `fetchMyOrders` may therefore also gain backward-compatible optional captured-generation options in api/instant.ts. For ComposeView, the parent onOrdered closure belongs to the rendered owner and checks it before accepting the result; do not recapture a current generation after await. Any create transport signature change beyond needed owner fencing must be reported before implementation.

Both wait POST and conflict GET must pass the actual captured `_generation` to transport, not merely compare it in UI. Same-generation access refresh keeps working;401 must not adopt a new session. Check operation ownership after EVERY await and before each callback/setter/finally; an old finalizer cannot clear newer busy. Abort only controllers owned by this wait/reconcile action; do not cancel unrelated screen operations or claim server rollback.

Screen publication fence: while reconciliation is pending, suppress ALL tracking-read publication, including polls/manual refreshes started later as well as earlier responses. On successful apply, advance/rebase the epoch before polling resumes. On failed reconciliation keep the screen explicitly unconfirmed; background polling must not silently declare it confirmed or re-enable mutations. Only the explicit READ retry's successful owned response leaves unconfirmed state. The local request token is not a server version and does not prove global status ordering.

Exact failure UI policy for review: screen/TrackingView owns reconciliation state `{idle,loading,unconfirmed,denied}` for the captured order and holds the read-retry callback independently of card lifecycle. Loading keeps actions busy, with neutral refresh text. Network/5xx read failure shows an unconfirmed-state banner and READ retry, suppresses waiting-success wording, disables wait/stop/order-again mutations until confirmed.403/404 read failure uses denied state and hides the stale private tracking details/actions rather than rendering them as currently authorized; show only bilingual unavailable/read-retry/navigation UI.401 follows existing auth teardown, with no stale private order publication. Do not forge waitNULL or cancelled state on failure. A failed GET is not a successful /wait and must not call onWaiting.

Mounted acceptance must hold POST/GET/poll responses, switch real provider owner, unmount/remount, and exercise exact error policies. Existing jsdom can run real React lifecycle/headless assertions with controlled fetch; browser Chromium covers rendered loading/error/disabled behavior and RU/BA/light/dark. Tests run only against explicit localhost API base, synthetic sessions/device state, network allowlist BEFORE navigation, and serviceWorkers block. No new dependencies, production defaults or hook-mock lifecycle claims.

### Local deadline display timing

Current card checks Date.now only when rendering, so failed polls do not guarantee a future deadline indicator expires. For the confirmed future-wait display, propose one card-owned bounded timeout: recompute remaining time against serverMs(deadline), schedule at min(remaining+small boundary margin,30000ms), re-evaluate on wake and re-arm only while confirmed/future. Clean it on deadline/order/owner changes and unmount; no global clock or polling service. Unconfirmed/denied states schedule no waiting-success timer and make no waiting assertion. Handle browser suspension by rechecking actual Date.now on wake; this is display timing, not a server epoch or permission to mutate. Tests cover deadline passage without polling renders, replaced deadlines, unmount cleanup and suspended/shifted wall clock without claiming instantaneous correction during suspension. Implementation/timer choice remains part of final journal review.

## 10. Implemented and verified, 2026-11-18

Runtime/source freeze: `be3851115a72abea5d65a68ecbd854bdaada4d7a`.
Final test-fixture freeze: `84cbda35d584fc283b107269896f5ee3e969db88`.
The latter changes only the old bootstrap/sheet fixture adapters, not runtime.

Implemented PWA-only owned wait POST / exact-conflict read reconciliation, reactive
account-generation screen ownership, read-publication fencing, explicit unconfirmed
and denied views, read-only retry, and bounded confirmed-wait display timing.
No backend, Android, generic HTTP client, schema, producer, consumer, or cutover change.

### Evidence and failures retained

- Independent actual React 18.3.1 / jsdom 31.0.1 mounted gate: 23 scenario groups,
  actual exit 0; adapter, existing wait/cancel regression, and TypeScript each exit 0.
  Evidence: `/tmp/taxi-pwa-wait-independent-Nt30ae` (SHA before/after unchanged).
- Real providers and screen/card are used, not mocked hooks. Fetch is replaced before
  module import, API base is explicit localhost, forbidden attempts are counted and
  asserted zero. No original network transport is called.
- Mounted checks include exact 409/no false success, failed read/retry, 403/404,
  actual auth.login account and same-user-generation changes, held POST/GET/poll/boot,
  401 auth teardown, terminal responses, language rerender, StrictMode, same-batch
  compose transition, stale finally, and deadline/NULL updates.
- A captured real ComposeView callback plus source linkage proves the stale callback
  publication boundary. It is **not** a full HTTP create-order round trip.
- Initial mounted hangs were test-owned React MessagePorts; interrupted exits 130 are
  retained. Final runner closes its own channels after unmount, checks no live timers
  or intervals, and exits naturally. No forced success exit is used.
- The stale waiting-success message was reproduced RED (`mounted-note-red1`, exit 1).
  A second RED (`mounted-author10`, exit 1) showed fresh GET NULL did not replace a
  local POST future deadline when the previous prop was also NULL. Both are corrected
  and independently GREEN. StrictMode was an audit-found issue with actual GREEN,
  not a claimed executed baseline RED. Intermediate fixture failures remain in logs.
- First full `npm run check` on be38511: actual exit 1, two old suites failed to
  bundle because their React/client stubs lacked new exports. No assertions were
  removed. Narrow correction of both fixtures was independently reviewed and run
  (`/tmp/taxi-pwa-wait-independent-fixtures-i1i2Jh`), runtime diff empty.
- New full `npm run check` on 84cbda3: **158 suites, 2956 printed checks, actual exit 0**.
  `/tmp/taxi-pwa-wait-full-check2.log`, `.exit`, `.sha`; original check1 FAIL retained.
- Final `VITE_API_BASE=http://127.0.0.1:19119 npm run build` on 84cbda3: actual exit 0.
  `/tmp/taxi-pwa-wait-build2.log`, `.exit`, `.sha`. Existing chunk-size advisory and npm
  environment/update notices are not hidden. This builds artifacts, not a deployment.
- Independent final review found no remaining P1/P2 within this narrow package.
  Diff whitespace check passed; scoped private-key/GitHub/AWS token pattern scan had
  zero hits (not a universal secrets audit). Dependency manifests/lockfiles unchanged.

Standalone mounted gate (outside the dependency-free normal check runner):

```sh
TEST_JSDOM_MODULE_PATH=/absolute/path/to/available/jsdom \
  node webapp/tests/wait-conflict-mounted-test.mjs
```

Missing explicit module path reports NOT_RUN with exit 2. No dependency installation
is performed by the test. The available local jsdom installation was used for both
actual author and independent runs.

### Explicit unverified and protocol boundaries

Visual/browser QA: **NOT_RUN**. Normal Chromium launch failed before navigation due
to Unix-socket permission denial; a permitted launch with owned writable profile
also failed. No sandbox workaround or production page was used. React/jsdom evidence
is not a screenshot/layout/browser acceptance claim. BA copy has no native-language QA.

The jsdom fixture has no BroadcastChannel cross-tab capability. Cross-tab delivery,
clock jumps/suspension, a genuine timeout, and successful same-generation 401 refresh
were not separately proven. Network rejection is not mislabeled as timeout. Existing
transport refresh may create multiple wire requests for one logical application GET.
GET itself can reconcile backend state and is not promised to be a pure read.

No global server-version ordering, durable recovery, full episode isolation, old
bodyless-request rejection, ABA protection, or account-deletion continuation is claimed.
The backend's before-first-read, same-tuple/NULL ABA, other-live-order, and same-deadline
boundaries remain. Producer/consumer and cutover remain off. No main merge or production
rollout is authorized by these results.
