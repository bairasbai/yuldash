# /wait initial-snapshot CAS: formal pre-code plan

**Status: concrete contract for final journal GO. Parent selected the conflict contract below; this docs commit alone is NOT runtime implementation GO.**

Baseline: local `f80b066`, published terminal package remote `61273ce16`. This docs-only branch starts at a clean baseline. No runtime, tests, model, schema, clients or runner change is included. Apply [agent reliability](../agent-reliability.md) and repository AGENTS. Earlier diagnostic outcomes are evidence of limitations, not proof of this proposed implementation.

## 1. Goal and strict separation

A. Potential narrow server fix: reject a /wait write if its initially observed authoritative row changed before its conditional write. It protects only the interval AFTER initial server read, for changes visible in its exact captured tuple.

B. Full client-observed wait episode: a separate API/model/all-writer/client rollout that distinguishes an old client request from a new user decision. Bodyless legacy requests delayed before their first server read cannot be distinguished by A.

C. Cross-order admission: whether an old order may requeue while a newer live order exists is a separate business rule, requiring User->Order lock-order review. It is NOT part of A and must not be silently added to /wait or account deletion.

No producer/consumer/cutover, account409, generation arithmetic/MAX extension, global expiration policy, scheduler, or deployment is authorized here. Durable continuation is not ready merely because A is implemented.

## 2. A: exact initial snapshot and write contract for review

Minimal semantic tuple: `(id, passenger_id, status, search_revision, wait_until)`.

- Capture it in ONE authoritative scalar SQL row before any new owner lock wait; do not use an existing ORM identity map. The snapshot SELECT itself has no FOR UPDATE. Authorization evaluates its passenger and status. Authenticated User/session checks continue unchanged.
- The SQL row carries typed wait_until and, on SQLite, raw `CAST(wait_until AS TEXT)` plus `typeof(wait_until)` from the SAME row. This auxiliary representation is part of exact-storage comparison, not another episode identifier. PostgreSQL retains its ordinary typed nullable timestamp comparison.
- NULL is captured and compared NULL-safely. SQLite nonNULL supported storage must be decodable timestamp TEXT; raw comparison uses explicit String-typed bind, BINARY collation and captured storage class. Do not normalize or replace the original stored spelling. Decodable noncanonical TEXT must not cause a false conflict. Unsupported/undecodable storage needs a reviewed fail-closed existing-error treatment, not automatic repair or silent admission.
- id identifies the row, passenger_id preserves the authorized owner, exact status prevents adopting a different eligible state, search_revision detects an intervening matching restart when it changes, wait_until detects observed renewal/closure. Comparing pending3 is intentionally excluded: it is not an epoch and dirty/partial/allNULL pending must not decide /wait freshness.
- No revision validation/rotation/increment is introduced by A. Current NULL/MAX/value representation behavior must be tested and preserved, not described as newly unbounded. Search lifecycle/token fields are not required for this limited wait-observation contract; adding them needs an explicit revised claim and matrix.
- Keep the initial tuple immutable. A later fresh read/lock must never replace it and thereby adopt a cleared or renewed queue. PostgreSQL final conditional UPDATE obtains row locking naturally; a preliminary FOR UPDATE is unnecessary for A unless its exact purpose is separately justified. SQLite needs actual UPDATE/CAS, not a fictitious FOR UPDATE guarantee.
- Final UPDATE predicates include the exact initial tuple and existing own expired/searching eligibility. SET only the new wait deadline. It does not create, clear or transfer matching pending. Use explicit synchronize_session='fetch' plus RETURNING matched IDs, consume and close the result. Do not use evaluate fallback or infer success from stale local state. Precisely one matched ID is the successful write; zero is the conflict disposition below.
- BEFORE initial SQL, inspect only the target identity-map entry passively (identity key from authenticated order ID, no Session.get or lazy loading). Reject visible objects scheduled for deletion or already deleted, and semantic dirty histories on id, passenger_id, status, search_revision or wait_until. PK id history is mandatory because a dirty primary key can still occupy the old identity-map key. Use history.has_changes: same-value assignment with clean semantic history is allowed. An already-flushed deleted object absent from the identity map is not discovered through private registries: the authoritative missing-row SQL produces404. Missing/unloaded fields do not require loads; authoritative SQL supplies the tuple. Pending3 is neither a source field nor an epoch and must not be included in this rejection rule.
- The precondition failure raises the selected bilingual409/code below with NO SQL, flush or commit. This is a defined internal caller contract, not a generic dirty-object adoption policy. The ordinary request-scoped clean path remains supported. Preserve unrelated dirty fields/histories; previous auth/query autoflush behavior is unchanged, but initial capture and final write execute under no_autoflush.
- Authoritative scalar capture must not refresh/expire/adopt any ORM target or its pending histories. Final full-five-tuple CAS success uses explicit fetch plus fully consumed/closed RETURNING IDs, then authoritatively synchronizes only the matched target wait_until as committed deadline, clearing its wait history before the ordinary commit. This prevents stale clean ORM wait from overwriting the winning deadline during flush. Preserve previous owner synchronization semantics and unrelated histories. An absent identity-map object must not be loaded for synchronization; normal successful payload/refresh may load it afterward.
- CAS zero raises the SAME409/code before commit and without winner synchronization. Do not commit caller-dirty fields on conflict. Do not perform whole-session rollback inside the helper: ordinary HTTP get_session close releases/rolls back its transaction; a direct caller MUST explicitly rollback after the exception before reuse. Tests must prove both paths and no partial write. No claim that a helper exception itself rolls back earlier caller work.
- SQLite invalid decoded/non-TEXT supported-storage violations fail closed with an explicit DB/Python error (exact error contract verified by tests), not a fabricated conflict409 or automatic normalization. OperationalError, BUSY and BUSY_SNAPSHOT are not caught/remapped to409; preserve their actual error identity. They do not authorize a refresh-and-retry against a different snapshot.
- Request deadline remains an application-clock calculation plus existing wait duration. With a single atomic UPDATE that may wait on another writer, that deadline may have been computed BEFORE the wait. A does NOT promise a full duration measured after lock acquisition. Changing clock timing or worker deadline policy requires separate review. A guarded lock-then-write alternative would need to preserve the original snapshot and SQLite semantics, not silently refresh/adopt.
- Preserve existing response serialization and successful commit/refresh path. No notification or network side effect is added.

## 3. Selected conflict disposition for final review

Parent selected HTTP409 with stable detail code `wait_snapshot_conflict`. Exact bilingual advice proposed for this contract: RU «Заказ изменился. Обнови экран и попробуй снова.» / BA «Заказ үҙгәрҙе. Экранды яңырт та тағы ҡабатла.» Use the existing bilingual error envelope with the stable code, preserving language selection; do not replace a real conflict with200/no-op or ok:false200.

The same error covers passive precondition failure and a zero-row full-tuple CAS. Precondition failure has no SQL/commit; CAS failure has no commit and leaves transaction rollback to request close or explicit direct-caller rollback. Auth/IDOR/not-found/ineligible-status responses retain their existing distinct behavior; do not label those as snapshot conflicts.

Current clients handle409 only as failure display, not state reconciliation: PWA shows generic retry and discards server detail; Android displays ApiException.message. Neither has wait-specific refresh on409. Therefore the chosen server error is not proof of satisfactory UX or coordinated client adaptation. A separate client package should refresh on this specific conflict, display fresh state and require a new user action rather than auto-resubmitting /wait.

No change to account deletion/error policy. No auto retry, repair commit, pending producer or alternative-order selection after CAS loss. No whole rollback inside the helper, no claimed recovery from arbitrary DB errors.

## 4. Mandatory actual controls and evidence

Before runtime change, preserve current strict diagnostic tests and logs of worker closure and authenticated order-again between read and write. The existing router-clock barrier is after real authorization/read and is valid historical RED evidence. Candidate protection assertions use distinct names or an explicit documented expectation change after implementation; old successful-gap evidence is not relabeled as protection. For candidate A, add desired strict protection assertions with authentic endpoint/auth, real worker/router/service/SQL/commit, controlled clock/barrier only. Do not replace handlers, SQL CAS or commit.

Positive and conflict matrix:
- Unchanged expired/searching snapshot admits a wait using existing deadline rules; caller IDs/authorization/status failures remain intact.
- Actual worker or actual order-again changes a captured nonNULL deadline to NULL: old request gets the exact reviewed conflict; no reopened wait. Reverse order preserves existing worker fresh deadline check semantics.
- NULL, canonical and noncanonical SQLite timestamp TEXT with exact raw BINARY String controls; raw representation changed while typed datetime equal must be distinguishable if claiming exact storage CAS.
- Different physical connections, PostgreSQL distinct PIDs/pg_blocking_pids both orders, ordinary RC isolation, independent whole owner/wait/pending observations before and after commit. Honest SQLite BUSY/BUSY_SNAPSHOT and rollback behavior, no concurrency claim from serial tests. A real read-before-write transaction may fail to upgrade its snapshot; do not promise every conflict becomes409 or that waiting always succeeds. Preserve actual DB exception attribution and do not silently retry by refreshing/adopting a different snapshot.
- Real rollback and deferred commit refusal preserve owner/wait/pending and earlier same-transaction related writes appropriately. Success fetch synchronization and no-match identity map controls on both dialects; no automatic full refresh of unrelated dirty fields.
- Existing MAX/NULL generation boundaries without new increments or adapter changes. No generation-ABA solution claimed.

Required NEGATIVE diagnostics (strict observed outcomes, not allow-any or xfail):
1. Saved old bodyless request delayed until AFTER closure and before its FIRST server read is indistinguishable from a genuinely new wait request. Under unchanged admission policy it can be accepted; do not claim A blocks it.
2. NULL -> future -> NULL while status/revision/passenger unchanged restores A's tuple: delayed NULL snapshot can pass (ABA).
3. Old expired waitNULL before order-again is excluded from abandonment UPDATE; a new/live order can exist while the old snapshot is unchanged. A can still admit old /wait. This is a C-policy gap, not repaired by pending3 comparison.
4. Same-deadline writes with equal controlled clock values can produce the identical stored tuple. Wall-clock precision is not an epoch/idempotency token.

If any actual outcome differs, preserve its first log and investigate; never choose whichever behavior passes. These diagnostics bound A and remain blockers for full episode provenance/consumer activation.

## 5. Proposed ownership and go/no-go

After formal review only: one runtime author for the narrow router/helper edit, separate test author(s), parent runner/docs/publication. New isolated fixed test mode if necessary; existing guards and baseline suites remain. Static -> author scoped -> independent scoped/PG -> FULL at a frozen source. No runtime code or test execution follows from this docs commit alone.

Final review gates: verify the exact selected409 envelope/code and passive precondition/authoritative success sync, SQLite explicit error attribution, deadline calculation timing, and confirmation that A's four remaining counterexamples are acceptable as a LIMITED intermediate step. Otherwise pursue B/C design first. No deployment/fleet audit or stale-client protection is inferred from repository tests.

## Addendum: exact writer inventory and current client handling (read-only)

### Exhaustive explicit `wait_until` references in inspected backend Python

A repository search of `backend/app` plus non-test backend Python found these explicit mutation sites (not a claim about external SQL/manual writes):

| Site | Change | Revision / episode consequence |
|---|---|---|
| `models.py:1229`; migration `o_gaps_taxi_courier.py:38,67` | nullable datetime default None; nullable column/index introduction | Creation is absence, not a new episode identity. No migration backfill/epoch. |
| `routers/instant.py:962` `/wait` | eligible own expired/searching -> application-now + configured minutes | No revision/pending/token update. Repeated requests can renew deadline; equal clock inputs can reproduce exactly equal deadlines. |
| `routers/instant.py:455–462` create-order abandonment | own expired and nonNULL wait -> NULL, pending3 NULL | Revision/status unchanged. Old expired NULL wait is not even matched, so another order may be created with no mutation at all to the old owner snapshot. |
| `instant_service.py:3135–3138` cancel | successful source-status CAS sets wait NULL plus terminal pending clear | Owner cancelled; original revision unchanged at cancellation commit. Driver reassign later is a distinct search transition. Already terminal without wait early return performs no repair. |
| `taxi_worker.py:126–135` close_stuck_orders | chosen closure removes wait and pending | Can leave status expired when no driver; no new revision. Worker fresh-lock recheck is current behavior. |
| `taxi_worker.py:249–254` finish_expired_waits | due expired/searching -> expired, wait NULL and pending NULL | Revision unchanged; existing expired_at retained. Same expired status plus already-set expired_at can have no other useful closure marker. |

Read-only/nonmutating references: worker retry scan at183–184 and finish scan/recheck230–247, cancellation queue admission3120–3128, payload serialization4623. `retry_waiting_orders` calls start_matching and writes retry_count, but does not itself clear/set wait. No additional explicit backend Python writer was found.

Other important **no-change** paths inspected: accept/done/finish_early clear matching pending but do not set wait; start/advance/reassign do not change wait themselves; `_expire_no_drivers` retains it; raw cleanup accepted/onboard/scheduled SET lists do not alter wait; scheduled expiry therefore can retain future queue eligibility. Do not silently add these paths to the wait-closure policy.

### Concrete ABA/non-observation counterexamples

1. Initial old owner `(expired, revision R, wait NULL)`. A request reads it. Order-again creates/returns another live order, but its queue UPDATE excludes NULL wait. The old tuple is byte-for-byte unchanged. A subsequent exact old-row CAS can succeed. This is not repaired by comparing more existing pending fields; those may already all be NULL too.
2. `(expired,R,NULL)` -> successful `/wait` future deadline -> order-again clears back to `(expired,R,NULL)`. Original delayed NULL-snapshot request sees an ABA. Pending may remain NULL throughout. No revision changes in these actions.
3. Two `/wait` requests with the same supplied/frozen clock granularity produce the same deadline. Intervening same-deadline renewal is invisible to wait-value comparison. A realistic database preserves microseconds, but the protocol cannot rely on wall-clock uniqueness, clock monotonicity or lack of retries.
4. Capturing nonNULL deadline and then closure to NULL detects the already-reproduced worker/order-again race, but does not imply cases1–3 are protected. A full-row snapshot is still not an epoch.

### Client source findings, not a UI runtime result

**PWA**
- `api/instant.ts:848–864`: WaitResult has ok, wait_until, wait_minutes, optional order. `waitForDriver(orderId)` sends a bodyless POST, no observed owner/episode or idempotency token.
- `components/TaxiTripActions.tsx:730–779` WaitForCarCard has synchronous inFlight ref plus busy. It clears note, awaits request, on success unconditionally stores `result.wait_until`, displays a successful waiting message and calls onWaiting. It does NOT inspect result.ok to reject a 200 no-op.
- Catch handles all failures identically with bilingual generic retry text; it does not inspect ApiError.status/code, refresh the order, or specifically reconcile a409. Finally releases inFlight/busy. `screens/InstantOrderScreen.tsx:1486` wires onWaiting to onRefresh, so refresh occurs on success only in this callback.
- `api/client.ts:441–456` non-2xx response is decoded into ApiError(status,message,code); the card discards that detail. Only401 has explicit auth refresh/retry in this transport path; no automatic409 replay observed. Buttons use disabled busy; waiting is derived from local deadline synchronized by order.wait_until effect.

**Android**
- `data/ApiClient.kt:4967–4975`: waitForDriver sends empty JSONObject, auth=true, no per-wait observed token/idempotency parameter. On a successful HTTP response it reads waitMinutes and optional order; does not test an ok=false semantic response.
- `InstantOrderScreen.kt:4937–4946,4992–5005`: busy guards tap; coroutine sets busy=true/err=null. onSuccess sets local waitMinutes (or -1 fallback) and passes response.order to onOrderUpdated. onFailure displays ApiException.message or bilingual generic error. No wait-specific409 refresh is present. busy=false after normal Result completion; this is not a new cancellation/finally guarantee.
- Local waitMinutes is remember(order.id), initialized from nonblank waitUntil; this path does not establish that later same-ID closure automatically resets that local flag. Do not claim UI state reconciliation merely from server409.
- `ApiClient.kt:3567+` common transport returns Result.failure(ApiException) for HTTP errors, does not network-backoff retry HTTP409. Network retries distinguish never-sent connect failures from uncertain mutation reads; this is not per-episode idempotency. Auth session-generation fencing is unrelated to wait/search episode freshness.

Only repository source was read; Android/PWA builds, native interaction and browser runtime were NOT executed. No mobile/ legacy Flutter files or secret/config files were opened.

### Original read-only tuple/response assessment (selected contract supersedes open choices)

For a deliberately limited **initial-server-snapshot -> conditional-write** claim, minimal meaningful tuple is `(id, passenger_id, exact status, search_revision, exact stored wait_until)`:
- id/owner ensure the same authorized row;
- exact status prevents adopting a different eligible state as the old state;
- revision detects intervening matching restarts where that revision actually changes;
- raw nullable wait deadline detects closure/renewal of the observed deadline without SQLite rebinding artifacts.

At the final write also retain current eligibility semantics, not merely captured authorization. Additional search lifecycle/token/offer fields may conservatively detect other owner changes, but require a stated reason; they do not fix episode ABA and pending3 must not be portrayed as an epoch. A proposed tuple must be tested against each writer inventory above. No generation increment is required by this limited CAS, so it should preserve current MAX behavior rather than claim expanded range.

Prefer a genuine **409 conflict**, with bilingual message and stable code, over a fake200 no-op or `ok:false` 200: both current clients treat HTTP success as waiting success and ignore the proposed semantic failure. However, current409 handling is only an error display: PWA suppresses server-specific advice, Android displays it, neither automatically refreshes here. Therefore "client-compatible transport failure" is the most that source supports, not satisfactory UX or handled race. A follow-up client change should refresh once on this specific conflict, present the fresh state, and require a new explicit user decision before a mutating retry. No background auto-retry of /wait.

If the product demands rejecting old-row NULL cases after order-again, add a separately reviewed cross-order admission check under User->Order serialization or a true episode token. A live-order restriction changes behavior and is NOT implied by narrow tuple CAS. It must not be smuggled into account deletion or become an account restriction. Full rejection of delayed-before-first-read requests still requires client-observed intent/episode provenance and coordinated optional/mandatory rollout.

## Post-RED aware SQLite clarification

Author source3f85c54 produced36PASS1FAIL: valid SQLite TEXT with an offset decoded as datetime but was newly rejected by a naive-only check. Journal approved preserving existing overwrite behavior: SQLite decodable datetime TEXT permits offsets without normalization; initial raw spelling+BINARY String CAS remains authoritative. PostgreSQL physical naive timestamp behavior remains unchanged. Test +offset/-offset success, same-instant different raw spelling loser, raw storage before/after rollback, and historical f80b066 endpoint baseline separately. Undecodable/nonTEXT storage remains explicit DB/Python error, not409. BA conflict advice is a draft requiring native linguistic verification; code/string assertions are not linguistic QA.

## Implementation and author evidence (not final acceptance)

Journal approved the narrow runtime contract after adding passive dirty PK `id` history to the source-field guard. Runtime checkpoint `3f85c54` adds an authoritative five-field scalar read, exact nullable storage-aware CAS, explicit fetch/RETURNING and winner-only wait synchronization. Request close rolls back an uncommitted conflict; direct callers must roll back. The endpoint does not adopt a fresh queue after a failed CAS and does not translate database BUSY/serialization errors into the new conflict response. New server code is not a new episode token, client UX integration or activation of matching recovery.

The single runtime file is `backend/app/routers/instant.py`. Account, models, migrations, generation limits, matching producer/consumer, wait writer policies and clients are unchanged. Existing protected-after-read tests are explicitly renamed/revised from the two earlier known-gap expectations; their original commits and logs remain historical RED evidence, not rewritten protection claims. Four distinct remaining negative controls cover before-first-read bodyless replay, NULL ABA, unchanged old order alongside another live order, and identical-deadline renewal.

Preserved author failures:
- `/tmp/taxi-wait-cas-author-sqlite1.log/.exit`: 36 PASS / 1 FAIL, exit1. The candidate incorrectly rejected a decodable SQLite timestamp carrying an offset by copying a naive-datetime restriction from a different contract. Captured wait is compared as exact TEXT, not used in datetime arithmetic. Journal approved removing only the SQLite timezone prohibition; PostgreSQL typed behavior remains unchanged and no UTC normalization/repair was introduced.
- `/tmp/taxi-wait-cas-author-sqlite2.log/.exit`: 47 PASS / 3 PG SKIP / 1 FAIL, exit1. The binding assertion inspected the cached SQL template's original bind value rather than the actual execution parameters. The test now verifies actual `compiled_parameters` plus the associated String bind type and emitted BINARY expression. This was a test-observation correction, not weakening the exact-storage predicate.

Corrected freeze `01ea7ee` and test-only strengthening `1bddc7ff02c3508adf588ce936db61ae572803eb`:
- Author SQLite: **48 PASS / 3 PG SKIP**, 3.38 seconds, exit0, `/tmp/taxi-wait-cas-author-sqlite3.log/.exit`.
- Author guarded disposable PostgreSQL: **38 PASS / 13 SQLite-only SKIP**, 4.48 seconds, exit0, `/tmp/taxi-wait-cas-author-pg1.log/.exit/.sha`. Own temporary cluster paths absent after runner cleanup.
- `ruff check app/ --select F`, runner `bash -n`, and `git diff --check`: PASS. Unknown runner mode and arbitrary extra arguments reject before bootstrap; only a new fixed `wait-snapshot-cas` suite was added.

Controls include actual authenticated success/error responses; dirty PK/source guard with no SQL; all three dirty pending fields remaining unrelated; same-value histories; unloaded/expired identity-map values; scheduled deletion versus flushed-deleted absence; unchanged/changed canonical and noncanonical raw timestamps; both offset signs; exact SQLite BUSY(5) versus a separate real WAL BUSY_SNAPSHOT(517); no catch-all accepted outcome; and preserved full owner/pending snapshots. A source-distinct old handler is embedded from `f80b066` and executed with real Session/commit for the offset baseline. Coordinator verified AST equality with that historical function after removing only the route decorator. This is not an old HTTP server test.

The three PG-specific cases prove both actual worker/wait lock orders with distinct backend PIDs and observed blocking, and a real deferred FK COMMIT refusal with exact SQLSTATE23503 and owned constraint name. The successful wait UPDATE is observed before failed COMMIT; rollback restores the complete order and related temporary rows. Postcommit comparisons cover the full captured row except precisely permitted changes, not just wait/pending. These are finite controlled interleavings, not a complete writer/deadlock proof.

The new409 contains RU/BA text and `wait_snapshot_conflict`; exact server envelope tests pass. Bashkir wording still requires native-language review; no Android/PWA build or UI execution is claimed. Current clients display an error but do not perform the desired explicit refresh/reconciliation flow.

Independent execution and a NEW completed FULL are required before publication. The prior terminal FULL2 does not validate this changed endpoint. Producer/consumer/cutover, ABA and old-request provenance remain activation blockers; no main merge or production changes are authorized by this package.

## Independent acceptance before FULL

Independent reviewer accepted the narrow package with no remaining P0/P1/P2 blocker after test-only full-row assertion strengthening. Real PG on source/test freeze `1bddc7f`: new wait suite **38 PASS / 13 dialect SKIP**, 5.01s; unchanged terminal suite **126 PASS**, 7.70s; unchanged default regression **255 PASS**, 16.24s. Expanded SQLite **573 PASS / 41 PG/dialect SKIP**, 13.14s. All actual exit0; skips are not passes. Logs `/tmp/taxi-wait-cas-independent-{pg1,terminal-pg1,regression-pg1,expanded-sqlite1}.{log,exit,sha}`. The SQLite run used docs-only `49eddb1`; independent diff confirmed runtime/tests/runner byte-unchanged from `1bddc7f`.

Own SQLite TMP removed, guarded PG cluster directories/processes absent. Independent added-diff heuristic secret scan across six changed paths reported zero matching credential/private-key patterns (`/tmp/taxi-wait-cas-independent-secrets-diff.json`); this is not a comprehensive secret audit. The reviewer did not write files or run FULL. Amio explicitly released its completed expanded JS window and agreed the following new FULL slot.

## Interrupted first FULL, not a completed result

FULL1 on `a140960d307a5dbfed0a9b0f17c9ba376d3a41b8` started at19:11:22UTC. After an aborted waiting call its session24641 was unavailable (`Unknown process id`). At19:31:42UTC the log was frozen at48%, last modified19:18:33UTC, and no `.exit` existed. This is interrupted/unknown, neither PASS nor a completed failing suite. Cause is not established. Evidence `/tmp/taxi-wait-cas-full1.{log,sha,started,tmpdir,interrupted.txt}` and original own TMP are retained. Sandbox-local process listing is not a claim about the entire host. Parent explicitly authorized a NEW separate FULL2 after resource coordination; no older paused publication/run is retried.

## Completed FULL2 and publication scope

The separately authorized NEW FULL2 on `0c57ebc` completed with **8407 PASS / 872 SKIP / 824 warnings**, **879.11 seconds (14:39)**, actual exit0. Evidence `/tmp/taxi-wait-cas-full2.{log,exit,sha,started,tmpdir}`. The runtime/tests/runner remained byte-identical to independently accepted `1bddc7f`; intervening commits changed only this document. Own FULL2 TMP was removed after exit0 with exact recorded-prefix/no-symlink validation, and disposable PG cluster paths were absent. FULL1 remains interrupted/unknown with its own evidence and TMP retained; it is not counted as PASS or falsely reported cleaned. Amio received actual resource handoff through followup after completion.

This verifies the separate backend package: a changed initial five-field server snapshot is not silently adopted by `/wait`; a genuine CAS conflict returns the reviewed bilingual409/code rather than false success. It does not detect all episode closures, reject a saved bodyless request arriving before its first read, solve same-value ABA, prohibit old-order requeue alongside another live order, reconcile either client UI, or make the selected account-deletion search continuation durable. Pending producer/consumer, full episode/cutover protocol and production activation remain off/unapproved. Published code belongs only in the new separate branch, never main or production without separate permission.
