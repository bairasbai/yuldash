# Populated PostgreSQL migration witness for TF09

Local QA fixture only. Do not run this against an existing or developer database.
No workflow, deployment, tariff, feature activation, or other product change.

The predecessor is exact `f8410ad7a538be4fdd64c65e29a2af8e932501d2`
(`dc_merge_taxi_consent_receipts`). The candidate is exact
`fb96e82a06767ee80e6a38a9d580499b18d7f8b4` (`dd_taxi_stop_control`).
Materialize **both source trees** in separate folders, not the candidate
with two target revisions: the historical root creates current metadata
when bootstrapping an empty database and would pre-create the new columns.

Use only the existing independent #384/#392 containment model: attested,
job-owned PostgreSQL 16 Unix socket; exact guard revision
`eeed56a2559bb31b991e6978eee99e44b980e07e`, guard file SHA-256
`673b6f7393df981e2e17f3194888044cd98d5e449d74fceab4af0688845ddf69`
for `sitecustomize.py` and
`a9a84d1a70be93c20cff3dc5010f7c3752356a0bbaa99cc0aec460eff37705ab`
for `verify.py`. An independent outer runner must pin the Docker image
digest (see #392), run `service_provenance`, complete both negative
controls, verify the initially empty public schema, and remove their
marker before provisioning. Use one `disposable-*/` root with a private
`home`, the `old/backend` and `candidate/backend` source trees, and
separate allowlisted child environments with the pinned guard directory
and respective backend directory in `PYTHONPATH`. Use the same synthetic
database throughout; feature and pricing provider admission are both off.
Suppress raw dependency, migration, and witness outputs; surface only fixed
phase markers after checking each child exit status and guard marker.

1. In `old/backend`, migrate to `dc_merge_taxi_consent_receipts` with the
   old code. Run `witness.py seed-old`: assert new columns absent and open
   a legacy wait via its mounted API on a synthetic onboard order.
2. In `candidate/backend`, upgrade the **same** nonempty database exactly
   to `dd_taxi_stop_control`; run `witness.py check-upgraded` in a fresh
   process. Check all four nullable/no-default columns; both authenticated
   order cards still show waiting. Edit an independent future point via
   the old waypoint endpoint and confirm clock, accrued fee, price, and
   four NULL identities do not change. The driver closes the original wait
   once through the old endpoint; after a later read, the accrued synthetic
   wait remains 1,700 kopecks (700 recorded before, plus 1,000 for two paid
   minutes). Only fixed synthetic routing/notification stubs are used.

This does not test mixed running old/new writers, rollback with active
TF09, production deployment, client UI, live routing/fees, or cross-version
auth-token continuity. Never turn on admission while a legacy writer can
update an explicit wait. A clean-schema or independent SQLite success is
not populated PostgreSQL evidence.
