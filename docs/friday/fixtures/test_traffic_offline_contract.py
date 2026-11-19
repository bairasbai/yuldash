"""Synthetic reference-model acceptance only, NOT actual app/provider compatibility.

All IDs, times, durations and thresholds are fictional. No network, wall clock,
provider implementation, production rank, arrival countdown or concurrency proof.
"""
from dataclasses import dataclass, replace
import math
import unittest


@dataclass(frozen=True)
class Context:
    request: str = 'request-A'
    owner: str = 'owner-A'
    order: str = 'order-A'
    candidate: str = 'candidate-A'
    vehicle: str = 'vehicle-A'
    purpose: str = 'taxi_pickup'
    origin: str = 'synthetic-origin-A'
    pickup: str = 'synthetic-pickup-A'
    profile: str = 'driving'
    departure: float = 100.0
    position_observed: float = 95.0


@dataclass(frozen=True)
class Sample:
    context: Context
    duration: float = 240.0
    fetched: float = 100.0
    source: str = 'synthetic-road'
    status: str = 'route'
    traffic_mode: str = 'unknown'
    traffic_observed: float | None = None
    cache_inserted: float | None = None
    cache_expires: float | None = None
    zero_colocation_supported: bool = False


@dataclass(frozen=True)
class Limits:
    route_age: float
    position_age: float
    traffic_age: float


@dataclass(frozen=True)
class Assessment:
    usable: bool
    reason: str
    traffic_fresh: bool = False


@dataclass(frozen=True)
class Display:
    label: str
    seconds: float | None
    traffic_fresh: bool = False


@dataclass(frozen=True)
class CohortDecision:
    outcome: str
    order: tuple[str, ...] = ()
    scope: str = 'provided-shortlist-only'


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def valid_context(context):
    names = ('request', 'owner', 'order', 'candidate', 'vehicle', 'purpose',
             'origin', 'pickup', 'profile')
    return (type(context) is Context
            and all(type(getattr(context, field)) is str and getattr(context, field)
                    for field in names)
            and finite(context.departure) and finite(context.position_observed))


def assess(sample, captured, now, limits):
    """Lookup validity against captured context, NOT current publication authority."""
    if (type(sample) is not Sample or not valid_context(captured)
            or not valid_context(sample.context) or type(limits) is not Limits
            or not finite(now) or any(not finite(v) or v < 0 for v in
                                      (limits.route_age, limits.position_age, limits.traffic_age))):
        return Assessment(False, 'invalid_metadata')
    if sample.context != captured or captured.purpose != 'taxi_pickup':
        return Assessment(False, 'context_mismatch')
    if sample.status != 'route' or sample.source != 'synthetic-road':
        return Assessment(False, 'no_comparable_road_route')
    if (not finite(sample.duration) or sample.duration < 0
            or (sample.duration == 0 and sample.zero_colocation_supported is not True)):
        return Assessment(False, 'invalid_duration')
    timestamps = [sample.fetched, captured.position_observed, captured.departure]
    if any(not finite(v) or v > now for v in timestamps):
        return Assessment(False, 'invalid_timestamp')
    if now - sample.fetched > limits.route_age:
        return Assessment(False, 'stale_route')
    if now - captured.position_observed > limits.position_age:
        return Assessment(False, 'stale_position')
    inserted, expires = sample.cache_inserted, sample.cache_expires
    if (inserted is None) != (expires is None):
        return Assessment(False, 'invalid_cache_metadata')
    if inserted is not None:
        if (not finite(inserted) or not finite(expires) or inserted < sample.fetched
                or inserted > now or expires < inserted):
            return Assessment(False, 'invalid_cache_metadata')
        if now >= expires:
            return Assessment(False, 'expired_cache')
    observed = sample.traffic_observed
    if sample.traffic_mode not in ('realtime', 'forecast', 'disabled', 'unknown'):
        return Assessment(False, 'invalid_traffic_metadata')
    if observed is not None and (not finite(observed) or observed > sample.fetched):
        return Assessment(False, 'invalid_traffic_metadata')
    traffic_fresh = (sample.traffic_mode == 'realtime' and observed is not None
                     and now - observed <= limits.traffic_age)
    return Assessment(True, 'valid_road_estimate', traffic_fresh)


def publication_current(captured, current):
    """Pure equality checkpoint, not an atomic/concurrent application guard."""
    return valid_context(captured) and valid_context(current) and captured == current


def pickup_display(sample, captured, current, now, limits):
    result = assess(sample, captured, now, limits)
    if not result.usable or not publication_current(captured, current):
        return Display('pickup_estimate_unavailable', None)
    return Display('pickup_estimate_seconds', sample.duration, result.traffic_fresh)


def display_invariant(display, original, captured, current, now, limits):
    """Independent output checks use original provenance, not a wrapper's replaced sample."""
    if display.seconds is None:
        return not display.traffic_fresh
    return (display.label == 'pickup_estimate_seconds'
            and captured.purpose == 'taxi_pickup'
            and original.context == captured == current
            and display.seconds == original.duration
            and finite(original.fetched) and original.fetched <= now
            and now - original.fetched <= limits.route_age
            and assess(original, captured, now, limits).usable
            and (not display.traffic_fresh
                 or assess(original, captured, now, limits).traffic_fresh))


def cache_read(sample):
    return sample  # Immutable sample; no read-time timestamp argument.


def compare_cohort(samples, contexts, quality, now, limits):
    """Reference comparison only; no selected partial/rating/fallback policy."""
    if (not samples or len(samples) != len(contexts) or len(quality) != len(samples)
            or any(q != quality[0] for q in quality)):
        return CohortDecision('UNDECIDED')
    if any(not assess(s, c, now, limits).usable for s, c in zip(samples, contexts)):
        return CohortDecision('UNDECIDED')
    # A cohort must represent the same query/departure/road source conditions.
    common = lambda c: (c.request, c.owner, c.order, c.purpose, c.pickup, c.profile, c.departure)
    if any(common(c) != common(contexts[0]) for c in contexts):
        return CohortDecision('UNDECIDED')
    if any((s.traffic_mode, s.traffic_observed) !=
           (samples[0].traffic_mode, samples[0].traffic_observed) for s in samples):
        return CohortDecision('UNDECIDED')
    ids = [c.candidate for c in contexts]
    durations = [s.duration for s in samples]
    if len(set(ids)) != len(ids) or len(set(durations)) != len(durations):
        return CohortDecision('UNDECIDED')  # Tie policy deliberately not selected.
    return CohortDecision('COMPLETE_ROAD_COMPARISON', tuple(
        candidate for _, candidate in sorted(zip(durations, ids))))


class OfflineContract(unittest.TestCase):
    def setUp(self):
        self.context = Context()
        self.sample = Sample(self.context)
        self.now = 110.0
        self.limits = Limits(60.0, 30.0, 20.0)

    def display(self, sample=None, current=None, now=None):
        return pickup_display(sample or self.sample, self.context,
                              current or self.context, self.now if now is None else now, self.limits)

    def test_01_three_labels_and_values_separate(self):
        values = {'search_wait_seconds': 600, 'pickup_estimate_seconds': self.display().seconds,
                  'trip_estimate_seconds': 1500}
        self.assertEqual(values, {'search_wait_seconds': 600, 'pickup_estimate_seconds': 240,
                                  'trip_estimate_seconds': 1500})
        self.assertEqual(self.display(now=115).seconds, 240)  # Not a countdown.

    def test_02_trip_copy_negative_control(self):
        bad = Display('pickup_estimate_seconds', 1500)
        self.assertFalse(display_invariant(bad, self.sample, self.context, self.context, self.now, self.limits))
        trip = replace(self.context, purpose='trip')
        self.assertIsNone(pickup_display(Sample(trip, 1500), trip, trip, self.now, self.limits).seconds)

    def test_03_search_wait_not_pickup(self):
        search = replace(self.context, purpose='search_wait')
        self.assertIsNone(pickup_display(Sample(search, 600), search, search, self.now, self.limits).seconds)

    def test_04_valid_matching_estimate(self):
        self.assertEqual(self.display(), Display('pickup_estimate_seconds', 240.0, False))

    def test_05_age_boundary_and_expiry(self):
        limits = Limits(10, 30, 20)
        self.assertTrue(assess(self.sample, self.context, 110, limits).usable)
        self.assertFalse(assess(self.sample, self.context, 110.001, limits).usable)
        self.assertIsNone(self.display(now=161).seconds)

    def test_06_cache_read_preserves_original_timestamps(self):
        cached = replace(self.sample, cache_inserted=102, cache_expires=170)
        read = cache_read(cached)
        self.assertIs(read, cached)
        self.assertEqual((read.fetched, read.traffic_observed, read.cache_inserted, read.cache_expires),
                         (100, None, 102, 170))

    def test_07_cache_rejuvenation_negative_control(self):
        original = replace(self.sample, fetched=20)
        for _ in range(3):
            self.assertFalse(assess(cache_read(original), self.context, self.now, self.limits).usable)
        bad_wrapper = replace(original, fetched=self.now)
        bad_output = self.display(bad_wrapper)
        self.assertEqual(bad_output.seconds, 240)
        self.assertFalse(display_invariant(bad_output, original, self.context, self.context, self.now, self.limits))

    def test_08_fresh_http_does_not_prove_traffic_age(self):
        bad_live_claim = Display('pickup_estimate_seconds', 240, True)
        self.assertFalse(display_invariant(bad_live_claim, self.sample, self.context,
                                           self.context, self.now, self.limits))
        for mode in ('unknown', 'realtime', 'forecast', 'disabled'):
            with self.subTest(mode=mode):
                result = self.display(replace(self.sample, fetched=110, traffic_mode=mode))
                self.assertEqual(result.seconds, 240)
                self.assertFalse(result.traffic_fresh)
        known = replace(self.sample, traffic_mode='realtime', traffic_observed=95)
        self.assertTrue(self.display(known).traffic_fresh)
        self.assertTrue(display_invariant(self.display(known), known, self.context,
                                          self.context, self.now, self.limits))
        self.assertFalse(self.display(replace(known, traffic_observed=80)).traffic_fresh)
        stale_known = replace(known, traffic_observed=80)
        self.assertFalse(display_invariant(bad_live_claim, stale_known, self.context,
                                           self.context, self.now, self.limits))

    def test_09_stale_position(self):
        stale = replace(self.context, position_observed=70)
        self.assertEqual(assess(Sample(stale), stale, self.now, self.limits).reason, 'stale_position')

    def test_10_lookup_valid_but_position_publication_obsolete(self):
        self.assertTrue(assess(self.sample, self.context, self.now, self.limits).usable)
        changed = replace(self.context, origin='synthetic-origin-B', position_observed=109)
        self.assertFalse(publication_current(self.context, changed))
        self.assertIsNone(self.display(current=changed).seconds)

    def test_11_route_context_mismatch(self):
        for field, value in [('pickup', 'other'), ('profile', 'walking'), ('departure', 99.0), ('purpose', 'trip')]:
            with self.subTest(field=field):
                changed = replace(self.context, **{field: value})
                self.assertFalse(assess(self.sample, changed, self.now, self.limits).usable)

    def test_12_publication_identity_changes(self):
        for field in ('request', 'owner', 'order', 'candidate', 'vehicle'):
            with self.subTest(field=field):
                self.assertIsNone(self.display(current=replace(self.context, **{field: 'new-synthetic'})).seconds)

    def test_13_invalid_duration_and_metadata(self):
        for bad in (math.nan, math.inf, -1, '240', True):
            with self.subTest(bad=bad):
                self.assertIsNone(self.display(replace(self.sample, duration=bad)).seconds)
        for bad in (math.nan, math.inf, '100', True, 111):
            with self.subTest(timestamp=bad):
                self.assertIsNone(self.display(replace(self.sample, fetched=bad)).seconds)
        self.assertFalse(assess(self.sample, self.context, self.now, Limits(-1, 30, 20)).usable)
        for field in ('departure', 'position_observed'):
            future = replace(self.context, **{field: 111})
            self.assertFalse(assess(Sample(future), future, self.now, self.limits).usable)
        for observed in (math.nan, '90', 111):
            self.assertIsNone(self.display(replace(self.sample, traffic_observed=observed)).seconds)

    def test_14_zero_requires_explicit_semantics(self):
        self.assertIsNone(self.display(replace(self.sample, duration=0)).seconds)
        self.assertEqual(self.display(replace(self.sample, duration=0, zero_colocation_supported=True)).seconds, 0)

    def cohort(self):
        b = replace(self.context, candidate='candidate-B', vehicle='vehicle-B', origin='origin-B')
        return [Sample(self.context, 720), Sample(b, 240)], [self.context, b]

    def test_15_complete_equal_quality_reverses_geodistance(self):
        samples, contexts = self.cohort()
        geometric = min([(1, 'candidate-A'), (2, 'candidate-B')])[1]
        result = compare_cohort(samples, contexts, [(5, 0), (5, 0)], self.now, self.limits)
        self.assertEqual(geometric, 'candidate-A')
        self.assertEqual(result.order, ('candidate-B', 'candidate-A'))
        self.assertEqual(result.outcome, 'COMPLETE_ROAD_COMPARISON')

    def test_16_mixed_or_unequal_cohort_has_no_fallback(self):
        samples, contexts = self.cohort()
        variants = [replace(samples[1], fetched=20), replace(samples[1], status='missing'),
                    replace(samples[1], source='geometric-proxy')]
        for bad in variants:
            self.assertEqual(compare_cohort([samples[0], bad], contexts, [1, 1], self.now, self.limits),
                             CohortDecision('UNDECIDED'))
        self.assertEqual(compare_cohort(samples, contexts, [1, 2], self.now, self.limits).order, ())
        self.assertEqual(compare_cohort(samples, contexts, [1, 2], self.now, self.limits).outcome, 'UNDECIDED')

    def test_17_no_routes_or_cache_expired(self):
        samples, contexts = self.cohort()
        for status in ('unavailable', 'unreachable'):
            unavailable = [replace(s, status=status) for s in samples]
            self.assertEqual(compare_cohort(unavailable, contexts, [1, 1], self.now, self.limits),
                             CohortDecision('UNDECIDED'))
        for inserted, expires in ((102, 110), (None, 170), (111, 170), (102, math.nan)):
            self.assertIsNone(self.display(replace(self.sample, cache_inserted=inserted, cache_expires=expires)).seconds)
        self.assertEqual(self.display(replace(self.sample, cache_inserted=102, cache_expires=110.001)).seconds, 240)

    def test_18_shortlist_does_not_claim_global_optimum(self):
        samples, contexts = self.cohort()
        result = compare_cohort(samples, contexts, [1, 1], self.now, self.limits)
        omitted = Sample(replace(self.context, candidate='outside-shortlist'), 60)
        self.assertLess(omitted.duration, 240)
        self.assertEqual(result.scope, 'provided-shortlist-only')
        self.assertNotIn(omitted.context.candidate, result.order)


if __name__ == '__main__':
    unittest.main()
