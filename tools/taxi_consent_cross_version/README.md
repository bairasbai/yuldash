# Taxi consent: populated PostgreSQL upgrade & binary rollback witness

QA only. Pins the actual older binary `662eed80295476e31c4c4d045b5ed1f2384c60dd`
and the exact #380 integrated binary `f8410ad7a538be4fdd64c65e29a2af8e932501d2`.
Each Alembic and application phase is a new process using its own byte-verified
snapshot of `backend/app`, `backend/alembic` and the config/requirements files
from the commit, not a downgrade of the new executable to fabricate old state.
The harness consumes local Git objects only, without lazy fetch; it does not
treat whatever is in the worktree as either binary.

1. The old Alembic runner migrates to `db_chat_message_receipt`. The old
   ORM commits a legacy order and a chat referencing it before upgrade.
2. The integrated Alembic runner upgrades that same DB to
   `dc_merge_taxi_consent_receipts`. The legacy reference survives with
   all four consent columns NULL. The integrated ORM then seeds a synthetic
   committed attempt and receipt. This does not exercise actual pricing/payment.
3. Run the old API router (authorized + foreign read) and ORM (legacy INSERT
   and unrelated UPDATE) against the newer schema. The old recovery endpoint
   remains unavailable. Neither the retained attempt nor the receipt changes.
4. Start the integrated API router with its default-off admission. Verify owner
   result/replay/close (typed receipt, no-store), foreign unknown and coded
   new-confirm 503. Confirm committed receipt+attempt unchanged and no extra order.

No Alembic downgrade, startup/lifespan, workers, provider/Redis, dispatch,
or real data. No DB persisted/uploaded as evidence. Success emits only fixed
phase/mode labels; failure emits a fixed phase and bounded exit/timeout code.
No full SQL, errors, request traces, credentials or snapshots are printed or
uploaded. The guard rejects Python IP/DNS and ordinary libpq connections;
it is a boundary for these fixtures, not a general network sandbox or an
authorization to execute untrusted checkout code.

## Smallest isolated CI lane (authored; not published or run)

Use ONE dedicated GitHub-hosted Ubuntu job, max 12 minutes, in a separately
reviewed `pull_request` workflow. Before enabling it, pin exact new QA branch
and exact non-default subject base, require same repo + draft, and gate for only
that pair. No push/dispatch, PR-target workflow, secrets, environment,
production, external review/hosting, self hosted runner, or artifact upload.
Use `permissions: {contents: read}`, checkout with `persist-credentials: false`,
and do not broaden the existing shared jobs. A new draft will also trigger
the existing 6-job CI; do not represent this as only one billable job. Check
current event/deployment policy before any separately authorized PR.

Use existing CI Python 3.12 / requirements-dev.txt and the existing official
`postgres:16` service precedent, pinned to the [published linux/amd64
manifest](https://github.com/docker-library/repo-info/blob/d0792a986b5b7826ad47d6e64aef531a6eb277e9/repos/postgres/remote/16.md#L26-L35).
Run only on the X64 Linux hosted runner. The shell step must take `QA_SERVICE_CONTAINER` directly from the
GitHub `job.services.postgres.id` context, not from a user/PR input.

```yaml
services:
  postgres:
    image: postgres@sha256:72d22ce26d04fe56a5384e9f6b026152d1cf05db602a7a3524a3ed337d5f5752
    env:
      POSTGRES_USER: taxi_cross_version_qa
      POSTGRES_DB: taxi_cross_version_qa
      # A generated/throwaway fixture value, not a repository secret; never passed to app.
      POSTGRES_PASSWORD: disposable_ci_socket_only
      POSTGRES_INITDB_ARGS: --auth-local=trust --auth-host=scram-sha-256
    volumes:
      - /tmp/yuldash-taxi-cross-version-pg:/run/postgresql
    # Intentionally no ports; no host/data volume and no real PostgreSQL DSN.
    options: >-
      --health-cmd "pg_isready -h /run/postgresql -U taxi_cross_version_qa -d taxi_cross_version_qa"
      --health-interval 5s
      --health-timeout 3s
      --health-retries 12
```

1. Use normal approved official Actions for checkout and Python setup. From the
   QA source checkout, fetch shallow exact objects from this public GitHub
   repository only (no credentials): `git fetch --no-tags --depth=1
   https://github.com/bairasbai/yuldash.git <old full SHA> <integrated full SHA>`.
   Avoid a partial/sparse blob fetch: any missing pinned runtime asset fails.
   Verify both full commit IDs; compare the QA branch delta to the reviewed
   harness/workflow paths, never run candidate app from an unpinned ref.
2. Verify that the old/new requirement manifests still match exactly (they
   match for these pins); install those dependencies separately in the runner
   using the repo's existing official setup. No external provider secrets.
3. Run once from the QA checkout:
   `python tools/taxi_consent_cross_version/verify.py --readonly-objects "$PWD" postgresql`.
   The test checks the job-owned container, local daemon, health, no published
   port, non-host network and exact path/inode identity across Docker and host.
   Before Alembic, the connection additionally checks fixed role/database,
   Unix-socket transport, PG major 16, public schema and no public objects.
   If the runner cannot expose that mount/socket (including auth/permission),
   fail the job, do not fall back to a TCP DSN.
4. Require all six phase passes and final `cross-version-postgresql:pass`,
   actual zero exit for this exact SHA, and no external-preview/deployment.
   Ephemeral runner/service is torn down by GitHub, not by a fixture DROP.

Local reproducibility: with exact objects and existing Python environment,
run the same command ending in `sqlite` for portability, with `TMPDIR` set to
an existing absolute non-symlink directory you own. It proves
cross-version SQLite and the fail-closed negative connection guards, not
service init or real PostgreSQL. The job still needs its first actual dedicated
PostgreSQL run and independent review. It does not prove rollout under live app
workers/old+new concurrent writes or unbounded production DDL effects. Disable
new admissions and retain the compatible read/replay/close binary for drain;
a rollback to the old binary alone preserves data but hides recovery until code
is restored. Never downgrade a schema carrying committed receipts.

References: [integrated workflow](https://github.com/bairasbai/yuldash/blob/f8410ad7a538be4fdd64c65e29a2af8e932501d2/.github/workflows/ci.yml),
[GitHub service containers](https://docs.github.com/en/actions/tutorials/use-containerized-services/use-docker-service-containers),
[PostgreSQL image entrypoint](https://github.com/docker-library/postgres/blob/master/docker-entrypoint.sh).

### Optional active-order restart witness

Append `--active-drain` to the same guarded command. The existing six phases stay
unchanged. Two additional processes use the exact same pinned newer source and
the same disposable database. The first loads admission ON, obtains a real
quote, and commits through the mounted confirm route. A synthetic interruption
after commit yields a TestClient HTTP 500 before first dispatch; this does not
simulate a socket or network loss. The second
loads admission OFF (ordinary taxi and the worker stay ON), recovers the exact
order through authenticated result, replay and close, and rejects a new request.
The registered dispatch task runs twice: the same order retains its committed
fare, advances one round, and emits exactly one captured offer before its
original search deadline.

This uses in-memory Redis per process, synthetic routing/weather and a captured
notification boundary. It does not claim a real Redis restart, full service
lifespan, mixed-fleet or provider coverage. The default CI command does not
enable the optional phases. PostgreSQL still requires the same job-owned socket
and empty database checks as the original matrix.
