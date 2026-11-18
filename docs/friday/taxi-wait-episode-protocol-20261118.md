# Client-observed wait episode: migration design and isolated reference experiment

Status: DRAFT for independent review. Base local `2976660`, published PWA
`6464d1cbee39954c8013a34bd4f15a93698c4000`. No application/schema/client change or
activation. Follow AGENTS and agent-reliability. This is not the durable feature.

## 1. Evidence and distinction

Current `/wait` compares an authoritative server-read five-field snapshot. Its real
negative tests in `backend/tests/test_taxi_wait_snapshot_cas.py` preserve accepted
bodyless-before-first-read, NULL ABA, unchanged old order beside a newer order, and
same-deadline scenarios. The new PWA reconciles a recognized conflict; it cannot
supply identity which the current bodyless request does not contain. Android still
sends an empty JSON object. Existing account/session generation is not a wait epoch.

Two authenticated bodyless requests with the same order/path/current row, one saved
before closure and one representing a fresh decision after closure, are observationally
identical to the server. No additional server read, timestamp precision, pending tuple,
or user lock can reconstruct the missing client observation. Rejecting one but not the
other requires more request information or a changed admission/product rule.

## 2. Separate protocols, not an optional-token downgrade

Candidate direction: a versioned, client-observed **wait mutation marker**, separate
from search_revision, tried lifecycle and pending3. The marker names an observed state
for the next explicit wait decision, not an authorization credential or a reservation.
A schema representation and production algorithm are NOT selected by this document.
A random opaque 128-bit marker is a candidate with practical uniqueness, not a proof
of mathematical non-reuse. A capped BIGINT revision cannot promise endless progress;
resetting it or deriving marker from a deadline reintroduces ABA.

- Legacy mode retains the present semantics and their known gaps. Optional token
  checking on only new requests does not protect against old bodyless requests.
- Strict mode requires the exact observed marker, order identity and ordinary auth/
  eligibility. Missing/unknown version/malformed marker must not silently fall back
  to legacy; migration must resolve old-client behavior explicitly before activation.
- Successful strict mutation rotates marker in the SAME transaction as deadline and
  other owned state changes, even if the calculated deadline equals its old value.
- A closure which invalidates a prior wait decision must rotate even for NULL→NULL.
  Existing create-order abandonment excludes old NULL waits; merely adding a marker
  to its current SET list would NOT cover the unchanged-old-order counterexample.
- A losing/malformed/stale request does not rotate, mutate, commit unrelated work,
  send effects, repair or reread-and-adopt. Fresh GET plus a new explicit user action
  can supply a new marker. A captured command keeps its old marker across retries.
- Lost response after commit is uncertain to the client. Repeating the same marker
  loses CAS and reconciles; this is not exactly-once command outcome replay. A future
  idempotency record is separate and may not reinterpret the old command as a new one.
- Ordinary pending cooldown/claim changes are not new user episodes. Pending3 is not
  a marker. Broadly rotating for every claim would add avoidable false conflicts.

A marker does not decide whether a FRESH wait on an old order is allowed while a
newer order exists. That requires separately reviewed cross-order admission and lock
order. It also does not solve driver offer-tokenless accept/decline or search MAX.

## 3. All-writer/cutover obligations and current blockers

The actual Python inventory already recorded in the snapshot-CAS plan must be renewed
at implementation freeze. Direct SQL, workers, restored backups, queues, old processes
and raw operational jobs are part of the eventual writer boundary, not covered by rg.
Current sources include router `/wait` and old-queue abandonment, cancellation, stuck
closure and expired-wait closure. Start/advance/reassign, accepted/done, scheduled
expiry and `_expire_no_drivers` have different wait disposition today; do not silently
reclassify their product semantics to make a universal epoch claim.

Proposed staged rollout, NOT an executable runbook:
1. Add nullable storage only after representation/model/constraint review; no default,
   eager initialization, API exposure or backfill masquerading as trusted provenance.
2. Deploy all-writer-aware binaries while strict mode stays OFF. Verify every relevant
   old/new writer, runtime rollback behavior, private SQL job and restore path. SQL
   triggers that indiscriminately rotate are not assumed compatible with ORM/CAS.
3. Introduce token-aware client read/request handling without automatic mutation retry.
   Explicitly inventory PWA cached assets/service workers and old Android versions.
4. Before strict activation, establish a trusted boundary for each eligible order:
   old marker inputs cannot become current, old binaries cannot write, and tokenless
   requests cannot enter strict mode. Draining live requests alone does not drain
   persisted old requests/queues/backups. Unknown/malformed history is not repaired
   by a random backfill without a transition/replay contract.
5. Choose migration policy explicitly: legacy orders remain weaker; or old clients are
   refused for strict orders with approved UX/version policy; or legacy mutations are
   disabled after a product-approved upgrade boundary. None is silently selected here.
6. Downgrade after activation cannot drop marker/strict-mode semantics and pretend old
   binaries are safe. Rollback needs a supported reader/writer protocol and treatment
   of pending commitments, not just reversible DDL.

Unresolved product choices: fresh requeue after closure/abandonment; renewed decision
while another live order exists; acceptable old-client upgrade/refusal policy. No new
account-deletion restriction or hidden 409 is introduced by this research.

## 4. Proposed first executable slice: test-only SQLite reference traces

Subject to independent GO, add ONE standalone test file with stdlib sqlite3 and pytest,
run with `--noconftest`, no app imports. It creates only owned temporary files and a
minimal explicitly artificial order table. No application model, migration, endpoint,
runner, auth or runtime helper is altered. This is storage/reference-protocol evidence,
NOT an actual API implementation or PostgreSQL proof.

Use fixed synthetic distinct markers, explicitly supplied fresh replacements with
non-reuse validation in the reference fixture; do not claim a UUID collision proof.
Strict operations require `(id, passenger, mode, captured marker, allowed status)` and
rotate with their owned update. Controls compare:
- Legacy bodyless saved request and genuinely fresh request after identical closure:
  both accepted under the same current policy (indistinguishability, not a fix).
- Strict old observation rejected after NULL→NULL closure and NULL→future→NULL ABA.
- Same-deadline successful renewal still changes marker; second old marker loses.
- Missing token in strict mode rejected, legacy remains weaker; unknown mode rejected.
- Noncooperative writer preserves marker while closing: stale request is accepted,
  an explicit negative control proving the all-writer prerequisite.
- Fresh observed marker after closure can succeed under unchanged same-order policy;
  another live order remains a separate negative admission boundary.
- Successful commit followed by modeled response loss: old marker retry loses, no
  second renewal; label Python/model loss, not process-kill or network proof.
- Caller rollback restores marker+deadline and unrelated caller write atomically;
  independent physical connection before/after commit proves visibility.
- Two actual SQLite connections in WAL, BEGIN IMMEDIATE before read for cooperative
  mutation; bounded timeout/observed SQLITE_BUSY while first writer holds transaction,
  then retry only in the TEST harness with SAME immutable original marker to prove CAS
  loss after commit. This is not automatic runtime retry. First rollback permits the
  old marker. No claim SQLite FOR UPDATE exists or universal wait success.

All connections close in finally; owned temporary file directory is removed only after
workers/connections finish. No child processes/network/PG/shared production database.
Exact outcomes, no catch-any/xfail/allow-any-success assertions. Preserve first failures.
Independent review/run and source unchanged proof required before separate publication.

## 5. Limits

The experiment intentionally does not choose actual schema or activate strict mode,
backfill existing orders, modify old clients, enforce all production writers, solve
negative-history ABA, cross-order admission, delivery, or durable search. Its value is
an executable separation of what marker+cooperative writers prove from what they do
not. Actual application integration needs a later independent protocol review and
real HTTP/PG/SQLite tests, including existing pending transfers and deletion races.

## 6. Refreshed source inventory and alternatives (read-only)

At base2976660 the two independent source readers found the same direct Python writers:
`routers/instant.py:945–1026` wait capture/CAS; `449–473` old-queue abandonment;
`instant_service.py:3120–3160` cancel; `taxi_worker.py:114–136` stuck closure and
`225–254` expired-wait closure. Retry at174–205 starts matching within the existing
wait. `_expire_no_drivers` at3523–3543 retains wait. `cleanup.py:821–842` does not
change wait. Model1227–1229 has only a nullable indexed deadline. This is a repository
inventory, not proof that deployed/manual/old writers do not exist.

PWA `api/instant.ts:862` remains bodyless despite account fencing/reconciliation.
Android `ApiClient.kt:4967–4974` sends an empty JSONObject; its screen4997–5004 displays
failure without a wait-conflict GET. Existing Android local minutes are not a proof
of deadline reconciliation. Native runtime was not built or exercised in this review.

For old expired NULL beside order-again, distinguish three alternatives:
A. Deliberately invalidate prior order markers, including NULL→NULL, as part of the
new-order transaction. This changes the touched-row set and needs scalability,
locking, ORM, rollback and policy review, not merely an extra SET expression.
B. A passenger-intent epoch shared by prior orders invalidates their capabilities
without rewriting every order, but adds cross-order ownership/lock and account
lifecycle semantics; account auth generation is not a substitute.
C. A live-order admission guard can reject even a fresh deliberate request on an old
order. That is a separate product restriction, not a transparent substitute for A/B.
None is selected for runtime. The reference experiment includes an unchanged-old-row
control: a row marker remains valid if order-again does not touch its scope. Rotating
only an unrelated newer row cannot invalidate it. Marker success alone must not be
reported as solving this counterexample.

Search revision remains bounded and independent; signed/invalid historical generations
and durable matching need their own protocol. UUID is practical collision resistance,
not a proof that persisted old queues/snapshots/restores can never replay.

## 7. Independent pre-code GO conditions

Journal reviewed6de576c and approved exactly the standalone reference test scope,
not runtime. Add a forced-marker-reuse trace: deliberately bypass fixture freshness
validation, restore a previously issued marker and demonstrate stale acceptance.
This is a negative control, not a claim that a test registry prevents production UUID
collision/replay. Fixture marker history is not a proposed unbounded runtime registry.

Protocol mode comes from the stored row/authoritative fixture policy. A caller cannot
select legacy mode for a strict row. Missing/malformed token, unknown mode/version,
wrong owner and ineligible status have exact no-mutation/no-rotation outcomes; no
fallback and no opportunistic snapshot adoption. The closure reference operation also
uses captured owner/marker predicates and cannot overwrite a newer marker without CAS.

Two-connection evidence must distinguish first commit (same old marker then CAS zero)
from first rollback (same old marker then success). Require exact SQLITE_BUSY code,
not arbitrary OperationalError. Tests own their explicit release and new transaction;
this harness retry is not permission for a runtime retry loop. Precommit independent
observer sees the old tuple; postcommit sees the new tuple; caller rollback includes
unrelated writes. Files and sidecars are removed after connections close in finally.
No receipt store, migration, production history registry or A/B/C policy is selected.

## 8. Executed reference package and independent acceptance

Frozen executable source: `2b38963df426ced4c7460e1b56b93f8dd5ff17c8`.
Only new `backend/tests/test_taxi_wait_episode_reference.py` and this document changed
relative to local2976660. Application, client, model, migrations and existing runners
are unchanged. No PostgreSQL or whole-application FULL was run for this isolated
reference package; the previous PWA/backend results are not claimed as its coverage.

Actual author command, from repository root:

```sh
/tmp/taxi-next-venv/bin/python -m pytest --noconftest -p no:cacheprovider \
  backend/tests/test_taxi_wait_episode_reference.py -q --basetemp OWNED_TEMP_PATH
```

Author2: **40 passed, 0.27 s, actual exit 0**. Evidence:
`/tmp/taxi-wait-episode-author2.{log,exit,sha,path,cleanup}`.
Independent: **40 passed, 0.21 s, actual exit 0**, frozen source unchanged, tree clean;
evidence `/tmp/taxi-wait-episode-independent-mFzCtS`. Both runs verified no owned DB
files remained after fixture teardown. SQLite reported version3.53.1 in this environment.
Ruff, py_compile and whitespace checks passed. No external network or app import is
part of the test file, and pytest ran with application conftest loading disabled.

Preserved first failure: author1 had **collection ERROR, exit 2, zero executed tests**
because pytest reserves the parametrization name `request`. The correction changes
only three test lines to `command`; helper logic is unchanged. The exact diff was
independently reviewed before repeat. A prior style-only I001 was corrected before
execution; neither failure is recast as a passing test run.

The executed traces demonstrate the selected reference predicates and real SQLite
transaction/visibility/locking behavior, including exact SQLITE_BUSY, CAS-zero after
first commit, same old captured marker success after first rollback, and unrelated
caller-work rollback. A real NOTNULL SQL failure after mutation needs explicit caller
rollback; it is not mislabeled as a deferred COMMIT failure. All observer SELECTs use
fresh autocommit snapshots. The fixture allocator deliberately burns replacement
markers even on rollback: this Python set is NOT atomic committed DB history or a
proposed production registry.

Strict controls rotate marker for NULL→NULL closure and same-deadline renewal, reject
missing/malformed/stale/version/owner/status mismatch, and preserve losing state.
Negative controls actually retain acceptance for legacy indistinguishable requests,
noncooperative closure, unchanged old NULL order beside another live order, and forced
historical marker reuse. These passing diagnostics prove limitations, not fixes.
Modeled lost response discards a result only after known commit; stale retry loses and
a read observes current state, without receipts, attribution or exactly-once claims.

Independent source and execution review found no P1/P2 within this **test-only** scope.
The following remain decisions/blockers rather than silently implemented behavior:
legacy-client refusal/upgrade policy, all-writer enforcement and restore trust boundary,
NULL-order abandonment scope (A/B/C), fresh cross-order admission, actual schema/model
representation and application/API/PG integration, and durable pending producer/consumer.
No new account restriction, migration, main merge, deployment or activation occurred.
