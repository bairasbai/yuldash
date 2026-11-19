# Offline acceptance/design: taxi pickup routing and honest ETA

Status: **DOCS ONLY / no activation**, 2026-11-19. PG readiness and schema foundation remain HOLD. This is a separately reviewed handoff, not a new provider, ranking algorithm, trial activation, or permission to contact a vendor.

## 1. Exact source boundary and existing work

Read-only inventory pinned to `ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8`, the commit returned by current GitHub code search. This is not a claim about deployed code, every branch, or latest HEAD indefinitely. Individual source blobs were verified, not a full checkout.

- [pricing.py](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/app/pricing.py#L27-L193), blob `8d7f385b38cc5ce1bb9e27ee578cb6013318161d`: `route_metrics` already requests driving routes with `traffic=enabled`, parses distance/duration and traffic type, applies bounded timeout, and falls back to haversine/average speed. Reuse its transport/parsing knowledge; do not build a duplicate unexamined provider.
- [instant_service.py rank/candidates](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/app/instant_service.py#L3026-L3077), blob `2b11cfa9ab8d8f3df22716ee832a82089016775a`: current candidate search returns the first nonempty eligible GEO ring; ranking uses inverse straight-line distance plus rating/penalty/priority. No routing call in this inspected rank/candidates path.
- [pricing use and separate pickup fields](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/app/instant_service.py#L1287-L1320): passenger A→B duration is not driver→pickup duration. Nearby pickup estimates and offer estimates use geometric/speed approximations. `route_through` sums legs and does not preserve all traffic provenance.
- [config.py](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/app/config.py#L698-L705), blob `e3c887e8c9ab4e2ce4bf02902c4465cfb481826f`: source defaults external timeout 1.5s and route cache 300s. Not evidence of deployed settings or cache licensing.
- [courier.py](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/app/routers/courier.py#L700-L710), blob `41b7ce55b93c476900194f960d45a4f6dd629846`: route_metrics is used for quote distance. This is not proof of courier live tracking ETA freshness or courier ranking.
- [existing dispatch TZ §2](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/docs/tz/TZ-dispatch-2026-09.md#L90-L143), blob `d81740a935df7ddab43fc7d563daa1695d402c0e`: already proposes an OSRM pickup interface, top-8, equivalent-distance ranking and geometric fallback. It explicitly excludes Yandex activation in that package. On the pinned commit, a fetch of `backend/app/road_eta.py` returned 404, and search found the name only in the plan. This is scoped evidence of an unimplemented planned path, not a repository-wide proof of absence. OSRM road awareness is not traffic awareness.

Conclusion: there is an existing traffic-aware **pricing route** implementation, but the inspected taxi rank does not use it. No new dispatcher is activated here.

## 2. Keep three different clocks/meanings

1. Search/wait duration: how long the system has searched or the passenger agreed to wait. It does not predict arrival.
2. Pickup estimate: current assigned driver or synthetic candidate → pickup point.
3. Trip/delivery estimate: pickup/current leg → destination, possibly with stops.

Never copy `order.eta_min` into a pickup timer or courier remaining-arrival countdown merely because it is available. Existing PWA honest-pickup fix remains separate. Courier acceptance must inspect its actual tracking endpoint/state before changing UI; quote distance alone is insufficient.

## 3. Proposed unconnected provider contract

Design only; names below are not existing app API fields.

Input: immutable request ID, purpose (`taxi_pickup` only in this slice), order/ownership snapshot identifier, pickup point, bounded candidate list of opaque fixture IDs with driver position and position-observed timestamp, explicit requested departure time, clock/budget injected. No network under database locks. No names, phones, tokens or exact coordinates in logs.

Output per candidate: status (`fresh_route | stale | unavailable | unreachable | invalid`), duration seconds/distance metres only where valid, provider/source, traffic mode (`realtime | forecast | disabled | unknown`), route identity (origin/destination/profile/departure context), actual fetch time, provider observation time if supplied, cache insertion/expiry if applicable, position timestamp. Absence of provider traffic age remains unknown, not fabricated.

A returned estimate is usable only for the same captured candidate/position/pickup/purpose and admitted ownership. Recheck current eligibility, vehicle handover permissions and generation before publication; a route response does not reserve a driver. Driver/car association is not assumed permanent.

Reuse adapter parsing/transport patterns from pricing, but do not pass its fallback RouteMetrics as confirmed traffic data. Existing object lacks observation/expiry metadata; reading it now cannot refresh an old sample's age. A new wrapper must not label cached data freshly sampled. Preserve current pricing behavior untouched.

## 4. Freshness/failure policy proposal (not selected runtime policy)

- Offline fixtures inject clock and explicit maximum route age/position age. Production thresholds remain an explicit product/config decision; source cache TTL300s is not automatically an acceptable pickup threshold.
- Freshness requires known fetch/cache age plus matching position/departure context. Traffic source freshness may remain unknown even after a fresh HTTP response. Missing evidence cannot produce a “live traffic” claim.
- Expired/missing/invalid/mismatched route: no numeric pickup countdown. Honest status such as “ETA unavailable” needs RU/BA copy review before UI implementation. Reusing last valid value must be explicitly marked stale and must not keep counting toward a promised arrival.
- Timeout, 429, 5xx, malformed/missing cells, NaN/Infinity/negative values: distinguish reason; no retry storm and no unbounded wait. Zero duration is a separate valid co-location case only when provider semantics support it.
- **Do not mix fresh road seconds and geometric proxy seconds as though equally measured.** For offline comparison evaluate (a) complete valid shortlist route ordering and (b) explicit cohort-level fallback to existing rank. Partial cohort policy, unreachable handling, rating/priority tradeoffs and deterministic ties require independent decision before integration. Neither option is implemented here.
- First-nonempty GEO ring/top-N can exclude a road-faster driver outside the shortlist. Report shortlist coverage; no “globally fastest driver” claim.
- Source code caching is not proof of contractual permission. Default this experiment to no stored provider responses unless licensing allows it; synthetic in-memory cache cases are still useful for tests.

## 5. Concrete offline acceptance traces

All values below are fictional fixture values, not requests to a routing service. No app import, live GPS, auth token, vendor key, customer address or network is needed.

| Trace | Expected acceptance property |
|---|---|
| Equal eligible/rating/priority: A straight1km, road12min; B straight2km, road4min | Road-comparison candidate prefers B; baseline geometric preference is recorded separately. Not a new production scoring policy. |
| B position changes after response / ownership or car access changes | Old response is not published as current; no reservation or side effect. |
| Cached B fetched100s ago, max_age60s; wrapper read now | Stale, not rejuvenated. No false numeric countdown. |
| Fresh fetch but traffic mode unknown / provider age absent | Road estimate may be known; “current traffic freshness confirmed” is not. |
| Missing/expired route for one or all candidates | Explicit partial-cohort policy outcome; no silent fabricated duration or success timer. |
| Timeout/429/invalid shape/unreachable/NaN/Infinity/negative | Classified failure with bounded work; no provider data in logs. |
| Same position but pickup/departure/profile differs | Cache/context miss; not reuse by driver ID alone. |
| Search waited10min, pickup4min, trip25min | Three values remain separate; search time/trip duration never becomes pickup ETA. |
| Courier quote distance exists but no current-leg route observation | Tracking cannot claim a fresh delivery countdown; separate courier API audit required. |
| Candidate beyond first GEO ring has fastest road route | Records shortlist limitation, not falsely declares global optimum. |
| Cancellation/reoffer during lookup; stale response arrives last | Discard obsolete publication, no automatic mutation retry. |

These are acceptance requirements, not test execution results. Next implementable step is an unconnected synthetic provider/consumer contract with network-deny controls, not a real vendor trial or runtime ranking change.

## 6. Trial planning and license gates

Root previously opened and checked official [matrix prices](https://yandex.ru/dev/tariffs/doc/ru/matrix/prices/), [product FAQ](https://yandex.ru/maps-api/products/distancematrix-api#faq) and [terms](https://yandex.ru/dev/tariffs/doc/ru/matrix/terms/index). This handoff inherits that dated research; no fresh vendor call/contact or legal approval is claimed here.

Planning envelope only: potential trial requiring separate manager agreement, up to7 days and100 matrix elements/day without minimum payment. Five public pickup points × eight fictional candidate positions × two departure-time scenarios = **80 elements**, not80 requests. Route direction must be candidate→pickup. Separate departure contexts may require separate requests; provider batching/time support must be confirmed before any real run. The remaining20 elements are a ceiling margin, not authorization to retry. Stop before any quota uncertainty; no automatic paid overage.

Prices inherited from that check: Table2 20,800 RUB/30days at1000 elements/day; excess390 RUB/1000. 200 trips/day×8 candidates=1600/day in an ideal single-attempt model, before retries/requotes/shortlist expansion. Over30 identical days, excess600/day→18,000 elements; **7,020 RUB is linear arithmetic only**, not an invoice. Daily charging/rounding and plan conditions must be applied before budgeting a purchase.

Open gates, no answer presumed:
- Terms §2.1.1 caching wording concerns Table1; do not generalize to Matrix Table2.
- §2.2.4 requires a check of permitted derived ranking/presentation. Is server-only candidate ranking allowed, and with which attribution/display conditions? This is a question, not a legal conclusion.
- Are traffic/departure-time scenarios and element accounting supported in the desired mode?
- Are result caching/retention, synthetic/public-coordinate test use, coverage and throttling explicitly permitted?
- Before real driver coordinates: lawful data-sharing scope, privacy notices/retention and vendor conditions require separate approval.
- No trial registration, manager message, key provisioning, spend, service call or activation is authorized by this document.

## 7. Delivery and blockers

Docs-only artifact. No changes to ranking, routing, prices, admission, models, migrations, PWA/Android, vendor settings or deployment. Android and visual QA NOT_RUN; no PG or full integration suite was run for this design. No code was executed against real routing.

Before runtime work: choose shortlist/partial-result/rating policy; prove freshness provenance and stale UI behavior; review concurrency/all-writer boundaries; resolve licensing/quotas/privacy; test actual endpoint/provider integration with denied external dispatch first; then separately approve any limited external trial. Durable search/episode protocol is still incomplete and not activated by this track.
