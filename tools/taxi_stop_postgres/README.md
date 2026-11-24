# TF09 PostgreSQL witness (provisional, not a workflow)

This fixtures-only branch delta must not be run against live or developer
databases. It requires the isolated Linux x64 PostgreSQL 16 job service used
by the approved #385 QA. No workflow, install, dispatch, application flag
change, or network activity is added by this patch. The fault injection
holds a real SQL UPDATE between flush and its physical COMMIT; it does not
mock a lock, conflict, database return, or commit. Both writes traverse the
mounted FastAPI router with independently issued synthetic auth sessions.
Route outputs and notifications alone use deterministic test substitutions.

## Independent artifact and runner guard

1. First review and publish the product base separately. For this provisional
   candidate the product tree is `95206f9ee5d8eace35d34ac428d45e4b1f520cc4`.
   Record its actual commit SHA, then independently freeze a dedicated draft QA
   workflow against precisely that **nondefault** base. Use one same-repo
   allowlisted head branch and one `pull_request: opened` event only, matching
   repo/base-ref/base-SHA/head-ref/draft. Do not open any PR before this guard
   is verified against the default-branch workflows; never mark it ready.
2. Before any execution, pin the standard checkout/setup actions and the
   official PG16 image digest exactly as approved in #385
   (`eeed56a2559bb31b991e6978eee99e44b980e07e`). Pin product/base tree,
   verify a single direct parent and only the reviewed QA paths, and verify
   SHA-256 of all executed helper files. Verify the workflow bytes as part of
   the frozen QA tree outside the workflow to avoid a self-hash. No secrets or
   persisted checkout credentials. Only normal package/bootstrap fetches
   allowed before the test; no production or provider access during it.
3. Keep PG unprivileged, no host network or published TCP. Reuse #385's exact
   pinned `service_provenance` and `sitecustomize.py` guards (do not replace
   their attestation with case-supplied environment values alone): validate the
   job-owned container ID/image/mount and exact private Unix socket inode on
   both sides. Set the guarded child environment using `env -i` or a Python
   subprocess allowlist; forward only CI identity, fixed fixture settings and
   the attested socket. Confirm the outbound socket and rejected libpq DSN
   negative controls and reset their marker file before testing.
4. Materialize the reviewed candidate backend into a private
   `disposable-*/candidate/backend` under the job temp dir. The common #385
   guard must load via `PYTHONPATH` before migrations or fixture imports.
   Refuse any caller DSN/current .env; fixed disposable settings are validated
   by the witness. Before migration require the attested job-owned public
   schema is empty; apply the candidate Alembic chain using the guarded
   subprocess and no raw output. The witness confirms PG16, Unix socket, exact
   DB/role/schema, expected Alembic revision, and zero orders.
5. Execute the witness with nonoptimized Python, fixed configuration
   `TAXI_STOP_CONTROL_ENABLED=true` for the **disposable** process only,
   no providers, and finite timeouts. Preserve only fixed status lines (no SQL
   parameters, request strings, usernames, coordinates, session data, or DB
   dump). Destroy the temporary job and files on completion. Run only after
   independent review and authorization; no initial stale or alternate test.

Expected fixed status labels are `passenger-first:pass`,
`driver-first:pass`, and `tf09-postgresql-witness:pass`.
This patch has not been run on PostgreSQL here. Passing the witness is exact
backend concurrency evidence, not production rollout, presence/GPS, client
or payment acceptance.
