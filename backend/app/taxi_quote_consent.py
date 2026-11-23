"""Opt-in consent to an immediate taxi's initial fare, not versioned lifecycle rules.

Redis holds the quote for the configured TTL. SQL tracks whether its owner can still
confirm it. Accepted fare metadata lives with the order.
No raw route, address, recipient or phone is copied into cache/consent metadata.
"""
import hashlib
import json
from contextlib import contextmanager
from datetime import datetime, timedelta
from uuid import UUID, uuid4

from redis.exceptions import RedisError, WatchError
from sqlalchemy import or_, update
from sqlalchemy.exc import SQLAlchemyError
from sqlmodel import Session, select

from . import car_class, price_freeze, promo_ride
from .config import settings
from .errors import herr
from .models import InstantOrder, PromoCode, PromoRedemption, Tariff, TaxiConsentAttempt
from .timeutil import utcnow

SCHEMA = "taxi-initial-consent-v1"
_PREFIX = "taxi:initial-quote:v1:"
_META = ("night_k", "pickup_k", "weather_k", "dynamic_k", "pricing_cap_k", "pricing_version")


def result_unavailable():
    error = herr(503, "Не получилось проверить заказ. Попробуй ещё раз.",
                 "Заказды тикшереп булманы. Яңынан һынап ҡара.",
                 code="taxi_consent_result_unavailable")
    error.headers = {"Cache-Control": "no-store"}
    return error


def read_result(session: Session, owner: int, request_key: str) -> dict:
    """Positive evidence only. A missing MVCC row is NEVER a final negative result.

    No order body/PII projection, reconciliation, Redis, user lock or domain mutation.
    Deletion of the order deletes its metadata under the existing retention policy.
    """
    result = {"schema": "taxi-consent-result-v1", "state": "unknown"}
    try:
        row = session.exec(select(
            InstantOrder.id, InstantOrder.consent_quote_id, InstantOrder.consent_snapshot_json,
        ).where(InstantOrder.passenger_id == owner,
                InstantOrder.consent_request_key == request_key)).one_or_none()
        if row is None:
            return result
        order_id, quote_id, raw = row
        if not isinstance(quote_id, str) or str(UUID(quote_id)) != quote_id or UUID(quote_id).version != 4:
            raise ValueError("Invalid consent quote")
        snapshots = json.loads(raw)
        quoted, accepted = snapshots["quote"], snapshots["accepted"]
        for snapshot in (quoted, accepted):
            if (snapshot["schema"] != SCHEMA or snapshot["request_key"] != request_key
                    or snapshot["quote_id"] != quote_id):
                raise ValueError("Invalid consent identity")
            amounts = [snapshot[k] for k in ("price_kop", "promo_discount_kop", "payable_kop")]
            if any(type(n) is not int or n < 0 for n in amounts):
                raise ValueError("Invalid consent amount")
            price, promo, payable = amounts
            parts = snapshot["breakdown"]
            if (set(parts) != {k + "_kop" for k in ("ride_price", "pickup_fee", "options_fee", "weather_fee")}
                    or any(type(n) is not int or n < 0 for n in parts.values())
                    or sum(parts.values()) != price or price - promo != payable):
                raise ValueError("Invalid consent breakdown")
        if (accepted["payable_kop"] > quoted["payable_kop"]
                or accepted["promo_discount_kop"] != quoted["promo_discount_kop"]):
            raise ValueError("Invalid accepted consent")
    except (SQLAlchemyError, ValueError, TypeError, KeyError, AttributeError):
        # Never send SQL parameters, snapshot values or the correlator to logs/errors.
        raise result_unavailable() from None
    return {**result, "state": "committed", "order_id": order_id,
            "initial_payable_kop": accepted["payable_kop"], "next": "fetch_order"}


def locked_attempt(session: Session, owner: int, key: str) -> TaxiConsentAttempt | None:
    # The caller already holds the same owner lock as confirm; row lock also fences GC.
    return session.exec(select(TaxiConsentAttempt).where(
        TaxiConsentAttempt.owner_id == owner, TaxiConsentAttempt.request_key == key,
    ).with_for_update().execution_options(populate_existing=True)).one_or_none()


def admit_attempt(session: Session, owner: int, key: str, quote_id: str) -> TaxiConsentAttempt:
    row = locked_attempt(session, owner, key)
    # No guessing/backfill/cache fallback for old, closed, deleted or unissued records.
    if row is None or row.state != "issued" or row.quote_id != quote_id or row.expires_at <= utcnow():
        raise conflict()
    return row


def close_attempt(session: Session, owner: int, key: str) -> dict:
    from .security import lock_refresh_user
    try:
        lock_refresh_user(session, owner)
        result = read_result(session, owner, key)
        if result["state"] == "committed":
            return result  # Never change/cancel a committed order or its replay.
        row = locked_attempt(session, owner, key)
        result = {"schema": "taxi-consent-close-v1", "state": "unknown",
                  "history": "unverified", "admission": "blocked"}
        if row is None:
            # This is NOT proof of history. Mandatory SQL admission denies the old pair,
            # including after GC; issuing a fresh quote never reconstructs this pair.
            return result
        if row.state == "committed":
            return {**result, "state": "retired", "history": "created_unavailable"}
        row.state = "closed"
        session.add(row)
        session.commit()  # Only the successful commit permits a closed/not_created answer.
        return {**result, "state": "closed", "history": "not_created"}
    except SQLAlchemyError:
        raise result_unavailable() from None
    finally:
        if session.in_transaction():
            session.rollback()


def canonical(value) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def digest(value) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def conflict(code="taxi_quote_refresh_required"):
    return herr(409, "Цена требует нового подтверждения. Обнови расчёт и подтверди его.",
                "Хаҡты яңынан раҫларға кәрәк. Иҫәпте яңырт та уны раҫла.", code=code)


def unavailable():
    return herr(503, "Не получилось закрепить цену. Попробуй ещё раз.",
                "Хаҡты беркетеп булманы. Яңынан һынап ҡара.", code="taxi_quote_unavailable")


def request_digest(body) -> str:
    # Request values ONLY: replay must survive changed payment flags, price and time.
    return digest(body.model_dump(mode="json", exclude={"request_key"}))


def route_digest(body, owner: int, quote_id: str) -> str:
    def point(lat, lng):
        return [float(lat) or 0.0, float(lng) or 0.0]
    return digest({
        "owner": owner, "quote_id": quote_id,  # random salt, no reusable geography hash
        "from": point(body.from_lat, body.from_lng), "to": point(body.to_lat, body.to_lng),
        "stops": [point(w.lat, w.lng) for w in body.waypoints],
        "category": body.category, "options": car_class.dump_options(body.options),
        "round_trip": body.round_trip, "return_wait_min": body.return_wait_min,
        "scheduled_at": None,
    })


def snapshot(est: dict) -> dict:
    out = {k: est[k] for k in (*price_freeze.FROZEN_KEYS, *_META) if k in est}
    breakdown(out)  # validate whole calculation, never combine partial snapshots
    return out


def breakdown(est: dict) -> dict:
    parts = {k + "_kop": int(est.get(k, 0)) * 100
             for k in ("ride_price", "pickup_fee", "options_fee", "weather_fee")}
    if any(v < 0 for v in parts.values()) or sum(parts.values()) != int(est["price"]) * 100:
        raise unavailable()
    return parts


def _promo_config(promo: PromoCode) -> str:
    return digest({
        "id": promo.id, "kind": promo.kind, "active": promo.active,
        "valid_from": promo.valid_from.isoformat() if promo.valid_from else None,
        "valid_until": promo.valid_until.isoformat() if promo.valid_until else None,
        "perk_value": promo.perk_value,
        "cap_rub": settings.promo_ride_max_discount_rub,
        "cap_share": settings.promo_ride_max_price_share,
    })


def promo_state(session: Session, owner: int, est: dict) -> dict:
    red, promo = promo_ride.available(session, owner)
    amount = (promo_ride.cap_for_price(red.discount_kop, promo_ride.discountable_rub_from_estimate(est))
              if red is not None else 0)
    if not amount:
        return {"discount_kop": 0}
    return {
        "discount_kop": amount, "redemption_id": red.id, "promo_id": promo.id,
        "granted_kop": red.discount_kop, "used_order_id": red.used_order_id,
        "used_at": red.used_at.isoformat() if red.used_at else None,
        "config_digest": _promo_config(promo),
    }


def pricing_inputs(session: Session, est: dict, body) -> str:
    tariff = session.get(Tariff, est["tariff_id"])
    return digest({
        "calculation": snapshot(est),
        "tariff": tariff.model_dump(mode="json") if tariff else None,
        "options": [(code, car_class.option_price_rub(code))
                    for code in car_class.parse_options(car_class.dump_options(body.options))],
        "round_trip_discount": settings.round_trip_discount_percent,
        "round_trip_max_wait_hours": settings.round_trip_max_wait_hours,
        "surge_cap": settings.surge_max_k,
    })


def public_snapshot(data: dict) -> dict:
    est = data["estimate"]
    price = int(est["price"]) * 100
    discount = data["promo"]["discount_kop"]
    return {
        "schema": SCHEMA, "quote_id": data["quote_id"], "request_key": data["request_key"],
        "snapshot_digest": data["snapshot_digest"], "pricing_inputs_digest": data["pricing_inputs_digest"],
        "expires_at": data["expires_at"], "price_kop": price, "promo_discount_kop": discount,
        "payable_kop": price - discount, "breakdown": breakdown(est),
        "distance_km": est["distance_km"], "eta_min": est["eta_min"],
        "round_trip": est["round_trip"],
    }


def issue(session: Session, redis, owner: int, body, est: dict) -> dict:
    if est.get("pickup_pending"):
        raise herr(409, "Цена подачи ещё не известна. Обнови расчёт позже.",
                   "Машина килеү хаҡы әлегә билдәле түгел. Иҫәпте һуңыраҡ яңырт.",
                   code="taxi_quote_price_pending")
    ttl = price_freeze.lock_seconds()
    if redis is None or ttl <= 0:
        raise unavailable()
    quote_id, key = str(uuid4()), str(uuid4())
    data = {
        "schema": SCHEMA, "quote_id": quote_id, "request_key": key, "owner": owner,
        "route_digest": route_digest(body, owner, quote_id),
        "expires_at": (utcnow() + timedelta(seconds=ttl)).isoformat(),
        "estimate": snapshot(est), "promo": promo_state(session, owner, est),
        "pricing_inputs_digest": pricing_inputs(session, est, body),
    }
    data["snapshot_digest"] = digest(data)
    from .security import lock_refresh_user
    try:
        lock_refresh_user(session, owner)
        session.add(TaxiConsentAttempt(owner_id=owner, request_key=key, quote_id=quote_id,
                                       expires_at=datetime.fromisoformat(data["expires_at"])))
        session.flush()
        if not redis.set(_PREFIX + str(owner) + ":" + quote_id, canonical(data), ex=ttl, nx=True):
            raise unavailable()
        session.commit()
    except (RedisError, SQLAlchemyError):
        session.rollback()
        raise unavailable() from None
    except Exception:
        session.rollback()
        raise
    return public_snapshot(data)


@contextmanager
def admission(redis, owner: int, body):
    """Watch BEFORE validating GET. Only a byte-identical, unexpired pair can be removed."""
    if redis is None:
        raise conflict()
    key = _PREFIX + str(owner) + ":" + str(body.quote_id)
    try:
        with redis.pipeline() as pipe:
            pipe.watch(key)
            raw = pipe.get(key)
            if not raw:
                raise conflict()
            try:
                data = json.loads(raw)
                expected_digest = data.pop("snapshot_digest")
                valid = (
                    expected_digest == digest(data) and data["schema"] == SCHEMA
                    and data["owner"] == owner and data["quote_id"] == str(body.quote_id)
                    and data["request_key"] == str(body.request_key)
                    and data["route_digest"] == route_digest(body, owner, str(body.quote_id))
                    and data["expires_at"] > utcnow().isoformat()
                    and not data["estimate"].get("pickup_pending")
                )
                data["snapshot_digest"] = expected_digest
            except (ValueError, TypeError, KeyError, AttributeError):
                raise conflict() from None
            if not valid:
                raise conflict()
            yield data
            # Used by caller only after all read-only price/promo/eligibility checks.
            if data["expires_at"] <= utcnow().isoformat():
                raise conflict()
            pipe.multi()
            pipe.delete(key)
            result = pipe.execute()
            if result != [1]:
                raise conflict()
    except WatchError:
        raise conflict() from None
    except RedisError:
        raise unavailable() from None


def claim_promo(session: Session, owner: int, order: InstantOrder, expected: dict) -> None:
    """No commit or rollback here. Caller commits order/claim/old-wait/receipt once.

    Separate from legacy consume(), whose fallback and commit semantics stay unchanged.
    CAS precedes the old-order lock, matching legacy redemption -> old-order lock order.
    """
    amount = expected["discount_kop"]
    if not amount:
        if promo_state(session, owner, {"price": order.price_estimate,
                                      "pickup_fee": order.pickup_fee_kop // 100,
                                      "options_fee": order.options_fee_kop // 100,
                                      "weather_fee": order.weather_fee_kop // 100}) != expected:
            raise conflict()
        return
    old_id = expected["used_order_id"]
    old_time = expected["used_at"]
    from datetime import datetime
    before_time = datetime.fromisoformat(old_time) if old_time else None
    now = utcnow()
    campaign = select(PromoCode.id).where(
        PromoCode.id == expected["promo_id"], PromoCode.active == True,  # noqa: E712
        PromoCode.kind == promo_ride.KIND,
        or_(PromoCode.valid_from.is_(None), PromoCode.valid_from <= now),
        or_(PromoCode.valid_until.is_(None), PromoCode.valid_until > now),
    ).exists()
    result = session.execute(update(PromoRedemption).where(
        PromoRedemption.id == expected["redemption_id"], PromoRedemption.user_id == owner,
        PromoRedemption.promo_id == expected["promo_id"],
        PromoRedemption.discount_kop == expected["granted_kop"],
        PromoRedemption.used_order_id.is_(None) if old_id is None else PromoRedemption.used_order_id == old_id,
        PromoRedemption.used_at.is_(None) if before_time is None else PromoRedemption.used_at == before_time,
        campaign,
    ).values(used_order_id=order.id, used_at=now).execution_options(synchronize_session=False))
    if result.rowcount != 1:
        raise conflict()
    promo = session.exec(select(PromoCode).where(PromoCode.id == expected["promo_id"])
                         .with_for_update().execution_options(populate_existing=True)).first()
    if (promo is None or not promo.active or not promo_ride.in_window(promo)
            or _promo_config(promo) != expected["config_digest"]
            or promo_ride.cap_for_price(expected["granted_kop"], promo_ride.discountable_rub(order)) != amount):
        raise conflict()
    if old_id is not None:
        cleared = session.execute(update(InstantOrder).where(
            InstantOrder.id == old_id, InstantOrder.status.in_(promo_ride._DEAD),
        ).values(promo_discount_kop=0).execution_options(synchronize_session=False))
        if cleared.rowcount != 1:
            raise conflict()
    order.promo_discount_kop = amount
    session.add(order)
