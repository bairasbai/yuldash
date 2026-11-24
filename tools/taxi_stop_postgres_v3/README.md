# TF09 V3 Passenger waypoint PostgreSQL 16 QA

Test-only. The witness is for the exact server base `ee248950915c5d155ae2b135d4273cc443ca6d1d`,
not a migration, release, deployment, price change, or request to use real data.

The attested runner and guard come from the previously reviewed PostgreSQL workflow in
PR #392, with the same digest-pinned PostgreSQL 16 service, unpublished Unix socket,
reviewed no-egress guard and fresh database checks. The workflow guard allows one
same-repository draft from the specific QA head and base refs above; only these three
QA paths may differ from the exact base. The database, accounts, tokens, coordinates,
price input, notices, and wait timer exist only for this synthetic run. The quote is
substituted at the routing boundary, not the database boundary.

Each of three cases uses its own synthetic trip and two authenticated mounted API clients
plus an independent autocommit database observer. The fixture flushes the winner's
physical UPDATE and holds its real COMMIT, observes the other writer blocked on it
using PostgreSQL `pg_blocking_pids` from the distinct observer connection, confirms
the observer still sees the pre-commit row, releases COMMIT, and verifies both outcomes.
It does not monkeypatch PostgreSQL locks, sessions, or commit results.

1. Passenger removes the item the driver has selected in the same revision. Passenger
   commits first; a stale driver request, and then one with the new revision but old
   removed ID, may not bind the literally identical sibling.
2. Driver binds the chosen item first, and the passenger write holding the older
   snapshot fails. A fresh v0 request cannot tell identical items apart; a v1 request
   cannot drop the chosen item or impersonate it with an indistinguishable copy. A
   fresh v1 request can delete the other identical occurrence.
3. With a separate trip and the same driver's-first race, a fresh v1 request can
   replace only the other identical occurrence. It gets a distinct server-minted ID.

The two driver-first cases check exact duration/identity persistence across the accepted
edit, a denied replay of that edit, and resume/retry exactly once at synthetic 5m30:
700 prior kopecks plus 1000 new kopecks in each separate trip. No real rate is assumed.
The step emits exact fixed phase markers, not HTTP bodies or database data. PostgreSQL
execution is separate from local static/source checks and does not validate client UI.
