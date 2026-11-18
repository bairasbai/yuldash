# Wait marker private-storage comparison: inactive plan

Base: local c354766d538c1f85be411a98e143d0b312b29114, published compound
1513a91637636913ea8371c9ee851c7391305ffb. This document requests independent
review; it is not permission to change application models, migrations or behavior.

## Goal and non-goals

Compare the least-risk private storage boundary for a possible compound observation.
Do not select INSERT_ONLY versus ACCEPTED_CREATE rotation, strict/legacy client
policy, scheduled-order inclusion, trusted initialization or activation. Android
remains NOT_RUN; do not resume the interrupted toolchain download. The completed
SQLite reference proves artificial traces, not production compatibility or PG locks.

## Source evidence and privacy risk

`backend/app/routers/auth.py` `_complete_login` line336 and `/me` line1060 expose
`user.model_dump()`. An ordinary added User field changes these responses even while
NULL; a non-NULL passenger marker becomes unwanted correlation data. InstantOrder
uses explicit `order_payload` (`instant_service.py:4411/4495`), but that is not a
universal guarantee against other serialization or logging paths.

`backend/app/db.py` creates engines with echo=False and hide_parameters=True. Its own
comment correctly warns that driver/server exception text may still include values.
Therefore neither engine options nor Field(exclude=True) establish complete log
confidentiality. The init_db ProgrammingError warning interpolates the exception;
this existing boundary must not be described as universally redacted logging.

Migration inventory by AST found one head, co_instant_matching_resume after cn.
Existing-column skip in co supports bootstrap, not correction of wrong types/defaults.
init_db create_all and SQLite additive-column helper are distinct schema sources;
neither is a substitute for a production migration or proof of identical catalogs.

## Candidates, not selected physical schema

A. Nullable User instant-taxi marker with Field(exclude=True), plus an order marker.
Its small schema diff is attractive, but all broad User serialization and diagnostic
paths must be audited. Exclude governs supported serialization, not repr, __dict__,
raw SQL, exception strings or explicit inclusion by future code. No generic claim
that this field cannot reach logs is justified.

B. Separate closed scope storage, with no relationship automatically attached to User
and no automatic export into login/me/order DTOs. A possible key is a user reference
plus an explicit instant-taxi domain; this is an experimental shape, not a decision
about scheduled orders, all user activities, or authorization. It reduces accidental
User dump exposure, but adds table ownership, FK/delete semantics, lock ordering,
missing-row creation races and migration requirements. It is not access control or
encryption and raw queries/errors can still expose a marker.

Prefer investigating B first for lower accidental DTO exposure. Do not implement B
merely because its standalone serializer looks safe. Both candidates need a separate
private order-component decision and a complete future writer protocol. No mode/version
column, defaults, backfill, generator, GET initialization, producer or consumer is
included in this step. NULL/absent rows do not select a legacy or strict policy.

## Proposed next bounded evidence step, pending independent review

One standalone test-only comparison, no app imports/conftest/global mapping changes:
use installed SQLModel/SQLAlchemy on local fixture mappings to compare default dump,
exclude=True dump/JSON, repr and explicit dictionary access for NULL and sentinel.
A closed-table fixture must not put a relationship/property on the public User object.
Use a synthetic sentinel, never real user data. Assert the exact boundaries rather
than accepting any output; any unexpected disclosure is candidate failure, not silent
redaction. These are framework/fixture checks, NOT actual login/me HTTP acceptance.

Disposable workspace SQLite only: populated legacy table, additive closed table,
existing insert/read unaffected, no lazy initialization on read, own FK enforcement,
caller rollback and data-preserving removal only of the empty experimental table.
A populated-table removal must be labelled destructive and must NOT be a successful
rollback recommendation. No application DDL, schema rebuild, PG, migration files,
network or existing runner changes. Actual app login/me/order sentinel tests and
PG source-specific catalog/transaction tests remain mandatory before model selection.

Migration order proposal: schema expansion before a new model can select new columns;
old binaries on expanded schema need actual compatibility tests. Reverse order requires
stopping code that uses new storage before removing it. Once any marker is initialized,
removing storage loses provenance; drain alone does not remove persisted stale requests,
queues or restored snapshots. No automatic downgrade or safe reactivation claim.

## Gates

Freeze the test contract after independent review, then sole-writer implementation,
static review and distinct author/independent short executions with actual exit and
owned tempfile cleanup. All TMPDIR/cache/logs under workspace. If ordinary launcher
fails pre-shell ENOSPC, stop retries; do not bypass sandbox or delete retained evidence.
No application FULL or Android PASS can be inferred. Before any later product code:
actual DTO/privacy tests, complete log-path handling, schema/head reread, FK account
ownership choice, all-writer lock graph, client policy and rotation decision required.

## Formal review and narrowed executable contract

Independent journal review of 08b56bf grants GO only to one standalone test file.
The serialization candidates are non-table SQLModel DTO classes, not application ORM
mappings or Column/Field integration. SQLModel.metadata table keys and object identities
must remain unchanged, without clearing global metadata. SQLite DDL is separate stdlib
sqlite3 fixture code. No actual login/me, account-delete or migration acceptance follows.

Required evidence: ordinary default-field leak positive control; excluded NULL and
synthetic unique sentinel in model_dump/model_dump_json; repr, str and __dict__ explicit
exposure boundaries. Closed-storage public DTO has no marker/property/relationship;
private entity dump/repr and direct SQL remain readable. Nested serialization is not
claimed unless explicitly exercised. Nothing here authorizes marker logging in the app.

SQLite FK ON and actual orphan/duplicate attribution; legacy values/counts unchanged
by additive setup and rollback; no read initialization; fixture cascade, rollback and
other-owner isolation. Empty private-table removal requires zero rows and no fixture
dependencies; populated removal is a test-local preflight rejection with no DROP.
This is not a production downgrade guard. Author and independent runs need separate
actual exits and owned-file cleanup; preserve all original failures. No dependency
install: observed SQLModel0.0.44, Pydantic2.14.3, SQLAlchemy2.0.58. Android NOT_RUN.

## Executed standalone evidence

Executable freeze 94e466ca4596688b88730f20e96cfbca4faa8842; test blob
b2cc998fe5bf767d1ca57c59f2b0ef2cf8d42e49. One new standalone test file;
application, models, migrations and existing runners unchanged. Independent static
review found no P1/P2 within this narrow scope before the first execution.

Author: 17 passed, 1.39s, actual exit0; workspace evidence
`yuldash-validation/private-storage-author/run1`.
Independent: 17 passed, 1.35s, actual exit0; evidence
`yuldash-validation/private-storage-independent-5BT44S`.
Both printed SQLModel0.0.44, Pydantic2.14.3 and SQLAlchemy2.0.58. Independent before/after
SHA identical, tree clean. Both confirmed no remaining owned DB files/sidecars.
Ruff, py_compile and diff checks passed. No test failure occurred in this scoped run;
earlier intermittent pre-shell ENOSPC is an environment blocker, not a test result.

Command (different owned workspace paths for each run):

```sh
TMPDIR=OWNED_WORKSPACE_TMP PYTHONPYCACHEPREFIX=OWNED_WORKSPACE_PYCACHE \
  /tmp/taxi-next-venv/bin/python -m pytest --noconftest -p no:cacheprovider \
  backend/tests/test_taxi_wait_private_storage_reference.py -q -s \
  --basetemp OWNED_WORKSPACE_PYTEST
```

Observed result: exclude=True omitted the synthetic field from DTO dump/JSON, including
explicit include, while repr/str/__dict__ retained it. The separate public DTO had no
marker member; the private DTO and direct SQL remained readable. Fixture FK, PK,
cascade/rollback, other-owner isolation, transactional DDL rollback and guarded empty
removal behaved as asserted. SQLite VARCHAR(64) is merely fixture spelling, not a
validated marker-length constraint or proposed production type. BEGIN IMMEDIATE was
explicit in the tested destructive preflight callers; in_transaction alone is not a
universal proof of acquiring a SQLite writer lock.

Not checked: nested serialization, actual application login/me/order responses,
application ORM mappings, real logging or exception scrubbing, authenticated account
deletion, PostgreSQL, schema-source parity, migration rollout/downgrade, Android or
browser UI, performance, cross-process concurrency, all-writer fencing or restored-pair
safety. No FULL was required or represented as run for this standalone fixture.
This supports investigating closed storage for lower incidental DTO exposure, not
selection or activation of a physical schema. Root's prohibition on fields in login/me
and logs remains an actual-product acceptance gate. Rotation, legacy client policy and
durable continuation remain unresolved; Android remains NOT_RUN.
