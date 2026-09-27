"""QA-B06-003: real process TZ must not distort UTC GPS intervals.

Only antifraud.utcnow is controlled; libc timestamp conversion stays real. TZ is
restored after every change. These tests do not require a DB or external Redis.
"""
import os
import time
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from itertools import permutations

import fakeredis
import pytest

from app import antifraud as af, instant_service as isv


ZONES = ["UTC", "America/Chicago", "Asia/Yekaterinburg"]
ZONE_IDS = ["utc", "chicago", "ufa"]
pytestmark = pytest.mark.skipif(not hasattr(time, "tzset"), reason="requires POSIX tzset")


@contextmanager
def process_tz(zone):
    previous = os.environ.get("TZ")
    os.environ["TZ"] = zone
    time.tzset()
    try:
        yield
    finally:
        if previous is None:
            os.environ.pop("TZ", None)
        else:
            os.environ["TZ"] = previous
        time.tzset()


@pytest.fixture
def clock(monkeypatch):
    current = [datetime(2026, 9, 27, 12, 0, 0)]
    monkeypatch.setattr(af, "utcnow", lambda: current[0])
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(isv, "_redis", lambda: redis)
    return current, redis


def guard(kind, redis):
    if kind == "presence":
        return lambda lat: af.teleport_filter(redis, 42, lat, 58.0)
    tracker = af.TrackGuard(42)
    return lambda lat: tracker.ok(lat, 58.0)


@pytest.mark.parametrize("zone", ZONES, ids=ZONE_IDS)
@pytest.mark.parametrize("kind", ["presence", "track"])
def test_same_zone_normal_clock_control(clock, zone, kind):
    current, redis = clock
    accept = guard(kind, redis)
    with process_tz(zone):
        assert accept(52.0)
        current[0] += timedelta(seconds=10)
        assert accept(52.0014)  # 56 km/h
        current[0] += timedelta(seconds=10)
        assert not accept(53.0)  # about 111 km in ten seconds


@pytest.mark.parametrize("zone", ZONES, ids=ZONE_IDS)
@pytest.mark.parametrize("kind", ["presence", "track"])
@pytest.mark.parametrize("start, distance, expected", [
    (datetime(2026, 3, 8, 2, 59, 55), 0.0014, True),
    (datetime(2026, 11, 1, 1, 59, 55), 1.0, False),
], ids=["spring-honest", "autumn-teleport"])
def test_dst_wall_clock_conversion_does_not_change_decision(
        clock, zone, kind, start, distance, expected):
    # Inputs are naive UTC, not Chicago wall time; their real interval is ten
    # seconds even when libc interprets those calendar fields across local DST.
    current, redis = clock
    current[0] = start
    accept = guard(kind, redis)
    with process_tz(zone):
        assert accept(52.0)
        current[0] += timedelta(seconds=10)
        assert accept(52.0 + distance) is expected


@pytest.mark.parametrize("first_zone, second_zone", list(permutations(ZONES, 2)))
@pytest.mark.parametrize("distance, expected", [(0.0014, True), (1.0, False)],
                         ids=["honest", "teleport"])
def test_shared_anchor_across_worker_timezones(clock, first_zone, second_zone, distance, expected):
    current, redis = clock
    with process_tz(first_zone):
        assert af.teleport_filter(redis, 42, 52.0, 58.0)
    current[0] += timedelta(seconds=10)
    with process_tz(second_zone):
        assert af.teleport_filter(redis, 42, 52.0 + distance, 58.0) is expected


@pytest.mark.parametrize("zone", ZONES, ids=ZONE_IDS)
def test_track_anchor_is_epoch_utc(clock, zone):
    current, _ = clock
    tracker = af.TrackGuard(42)
    with process_tz(zone):
        assert tracker.ok(52.0, 58.0)
    assert tracker._anchor[2] == current[0].replace(tzinfo=timezone.utc).timestamp()


@pytest.mark.parametrize("legacy_zone", ZONES, ids=ZONE_IDS)
def test_legacy_worker_anchor_cannot_contaminate_utc_generation(clock, legacy_zone):
    current, redis = clock
    with process_tz(legacy_zone):
        legacy = f"52.0,58.0,{current[0].timestamp()}"
    redis.set("af:pt:42", legacy, ex=3600)
    current[0] += timedelta(seconds=10)
    with process_tz("UTC"):
        assert af.teleport_filter(redis, 42, 52.0014, 58.0)
        assert redis.get("af:pt:42") == legacy, "new worker must not share the old time convention"
        # An old worker can still be serving traffic during rolling restart.
        redis.set("af:pt:42", "53.0,58.0,0", ex=3600)
        current[0] += timedelta(seconds=10)
        assert af.teleport_filter(redis, 42, 52.0028, 58.0)
        current[0] += timedelta(seconds=10)
        assert not af.teleport_filter(redis, 42, 53.0, 58.0)
