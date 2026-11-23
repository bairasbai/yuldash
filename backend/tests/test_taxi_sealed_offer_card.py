"""Real sealed successor card observations, synthetic DB only."""
from dataclasses import FrozenInstanceError
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import event, insert

from app import instant_service as isv, taxi_marked_offer_card as current_only
from app import taxi_retirement_agreement_seal as seal, taxi_route_edit_outcome as outcome
from app import taxi_route_resume as resume, taxi_sealed_offer_card as cards
from app.models import LedgerEntry, LedgerKind, Payment
from app.timeutil import utcnow
from test_taxi_offered_waypoint_preview import _mutate, _STOP, _unexpected
from test_taxi_offered_waypoint_retire import _clock, _wait_db, healthy, route, testdb  # noqa: F401
from test_taxi_sealed_resume_claim import near_deadline, retired  # noqa: F401
from test_taxi_sealed_resume_offer import _claim, _store


def _params(data, **extras):
    _, owner, oid, selected, kwargs, _ = data
    return dict(key=kwargs["key"], owner_id=owner, order_id=oid, driver_id=selected) | extras


def _bind(data, **extras):
    with data[0].connect() as conn:
        return cards.observe_sealed_offer_card(conn, **_params(data, **extras))


def _matches(data, proof, **extras):
    with data[0].connect() as conn:
        return cards.matches_sealed_offer_card(conn, **_params(data, **extras), proof=proof)


@pytest.fixture
def offered(request):
    data = request.getfixturevalue("retired")
    assert _bind(data) is None
    assert _store(data, _claim(data))
    return data


def test_real_retire_claim_offer_card_one_read_each_and_no_refresh(request, monkeypatch):
    data = request.getfixturevalue("offered")
    db, _, oid, _, _, _ = data
    before = resume.observe(db, oid)
    state = resume._state(before)
    queries = []

    def witness(conn, cursor, sql, parameters, context, executemany):
        queries.append(sql)

    event.listen(db, "before_cursor_execute", witness)
    for name in ("committed_evidence", "stage_committed", "purge_expired"):
        monkeypatch.setattr(outcome, name, _unexpected)
    try:
        proof = _bind(data)
        assert type(proof) is cards.SealedOfferCardProof and _matches(data, proof)
        assert _bind(data) == proof  # same offer, no extra clock in digest
        with db.connect() as conn:
            observation = cards._read_sealed_offer(conn, **_params(data))
    finally:
        event.remove(db, "before_cursor_execute", witness)
    assert len(queries) == 4
    for query in queries:
        assert query.startswith("SELECT") and "strftime" in query and "payment." in query and "ledgerentry." in query
        assert "taxirouteeditoutcome" not in query
    assert observation._proof == proof
    assert dict(zip(current_only._FIELDS, observation._values, strict=True)).items() >= state.items()
    assert resume.observe(db, oid) == before
    assert not hasattr(proof, "__dict__") and len(proof._digest) == 32
    assert not hasattr(observation, "__dict__")
    for label in ("SYNTHETIC", state["route_resume_id"], state[resume._SEAL].hex()):
        assert label not in repr(proof) and label not in repr(observation)
    assert proof._digest.hex() not in repr(proof)
    with pytest.raises(FrozenInstanceError):
        proof._digest = b"x"


def test_actual_receipt_purge_leaves_same_private_card_offered_without_renewal(request):
    data = request.getfixturevalue("offered")
    db, owner, oid, _, kwargs, _ = data
    before = resume.observe(db, oid)
    proof = _bind(data)
    assert proof
    ids = dict(owner_id=owner, order_id=oid, attempt_id=kwargs["attempt_id"], binding=kwargs["agreement"].binding)
    assert outcome.committed_evidence(db, **ids, now=kwargs["receipt_expires_at"]-timedelta(microseconds=1)) is (
        outcome.CommittedEvidence.COMMITTED
    )
    _wait_db(db, kwargs["receipt_expires_at"])
    with db.begin() as conn:
        conn.exec_driver_sql("BEGIN IMMEDIATE")
        assert outcome.purge_expired(conn, cutoff=_clock(conn), limit=1) == 1
    assert outcome.committed_evidence(db, **ids, now=utcnow()) is outcome.CommittedEvidence.UNKNOWN
    assert _bind(data) == proof and _matches(data, proof)
    assert resume.observe(db, oid) == before


@pytest.mark.parametrize("change", ("route", "fare", "generation", "wrong_valid_seal", "partial", "raw_text"))
def test_tamper_before_first_card_does_not_acquire_proof(request, change):
    data = request.getfixturevalue("offered")
    db, owner, oid, _, kw, _ = data
    base = resume._state(resume.observe(db, oid))
    new_marker = uuid4().hex
    if change == "wrong_valid_seal":
        genuine = seal.bind_retirement_agreement(
            kw["key"], owner_id=owner, order_id=oid, old_source=kw["preview"]._source,
            preview=kw["preview"], agreement=kw["agreement"], marker_id=new_marker,
            retired_at=base["route_resume_retired_at"], deadline_at=base["route_resume_deadline_at"],
        )
        assert genuine and genuine._digest != base[resume._SEAL]
        edited = {resume._SEAL: genuine._digest}
    else:
        edited = {
            "route": {"waypoints_json": isv.dump_waypoints([_STOP | {"text": "SYNTHETIC EDIT", "done": False}])},
            "fare": {"ride_price": base["ride_price"]+10, "price_estimate": base["price_estimate"]+10},
            "generation": {"route_resume_id": new_marker},
            "partial": {resume._SEAL: None}, "raw_text": {},
        }[change]
    if change == "raw_text":
        with db.begin() as conn:
            conn.exec_driver_sql("UPDATE instantorder SET route_resume_agreement_seal=? WHERE id=?", ("z"*32, oid))
    else:
        _mutate(db, oid, **edited)
    altered = resume.observe(db, oid)
    assert _bind(data) is None and resume.observe(db, oid) == altered
    # Ordinary/unsealed lab appearance cannot substitute. A coherent stale
    # route does not stop the previous unsealed current-only observation.
    if change in ("route", "fare"):
        _mutate(db, oid, **{resume._SEAL: None})
        with db.connect() as conn:
            unsealed = current_only.bind_current_card(conn, **_params(data))
        assert unsealed and not _matches(data, unsealed)


def test_wrong_passenger_order_driver_previous_key_or_old_domains_refused(request):
    data = request.getfixturevalue("offered")
    db, owner, oid, selected, kwargs, _ = data
    before = resume.observe(db, oid)
    current = resume._state(before)
    proof = _bind(data)
    assert proof
    for wrong in (
        {"owner_id": selected}, {"order_id": oid+100}, {"driver_id": owner},
        {"driver_id": selected+100}, {"driver_id": current["route_resume_previous_driver_id"]},
        {"driver_id": True}, {"key": b"x"*32},
    ):
        assert _bind(data, **wrong) is None and not _matches(data, proof, **wrong)
    with db.connect() as conn:
        assert current_only.bind_current_card(conn, **_params(data)) is None
    ordinary = isv.offer_card_precondition(tuple(current[name] for name in isv._ACCEPT_OFFER_FIELDS),
                                           None, selected, utcnow())
    assert ordinary is not None and not _matches(data, ordinary)
    assert not _matches(data, cards.SealedOfferCardProof(bytes.fromhex(ordinary[3:])))
    assert not _matches(data, seal.RetirementAgreementSeal(current[resume._SEAL]))
    assert not _matches(data, current_only.MarkedOfferCardProof(proof._digest))
    assert not _matches(data, cards.SealedOfferCardProof(current[resume._SEAL]))
    assert resume.observe(db, oid) == before


@pytest.mark.parametrize("kind", ("pending_payment", "settled_payment", "ledger"))
def test_active_or_existing_settlement_blocks_even_original_proof(request, kind):
    data = request.getfixturevalue("offered")
    db, owner, oid, selected, _, _ = data
    before = resume.observe(db, oid)
    proof = _bind(data)
    assert proof
    if kind == "ledger":
        with db.begin() as conn:
            conn.execute(insert(LedgerEntry).values(driver_id=selected, order_id=oid, kind=LedgerKind.earn))
    else:
        with db.begin() as conn:
            conn.execute(insert(Payment).values(user_id=owner, order_id=oid, purpose="ride",
                                                status="pending" if kind == "pending_payment" else "succeeded"))
    assert _bind(data) is None and not _matches(data, proof)
    assert resume.observe(db, oid) == before


@pytest.mark.parametrize("boundary", ("offer", "absolute"))
def test_actual_clock_rejects_both_deadlines_and_never_renews(request, boundary):
    data = request.getfixturevalue("retired")
    if boundary == "absolute":
        data = (*data[:-1], request.getfixturevalue("near_deadline"))
    db, _, oid, _, _, _ = data
    won = _claim(data, lease_seconds=120)
    assert _store(data, won, offer_seconds=120 if boundary == "absolute" else 1)
    original = resume.observe(db, oid)
    state = resume._state(original)
    expiry = state["offer_expires_at"]
    if boundary == "absolute":
        assert expiry == state["route_resume_deadline_at"]
    else:
        assert expiry < state["route_resume_deadline_at"]
    proof = _bind(data)
    assert proof
    _wait_db(db, expiry)
    assert _bind(data) is None and not _matches(data, proof)
    assert resume.observe(db, oid) == original


def test_previous_recipient_cannot_observe_even_if_stale_row_points_to_them(request):
    data = request.getfixturevalue("offered")
    db, _, oid, _, _, _ = data
    prior = resume._state(resume.observe(db, oid))["route_resume_previous_driver_id"]
    _mutate(db, oid, current_offer_driver_id=prior)
    before = resume.observe(db, oid)
    assert _bind(data, driver_id=prior) is None
    assert resume.observe(db, oid) == before


def test_previous_proof_cannot_cross_a_distinct_genuinely_signed_generation(request):
    data = request.getfixturevalue("offered")
    db, owner, oid, _, kw, _ = data
    original = _bind(data)
    assert original
    current = resume._state(resume.observe(db, oid))
    next_marker = uuid4().hex
    signed = seal.bind_retirement_agreement(
        kw["key"], owner_id=owner, order_id=oid, old_source=kw["preview"]._source,
        preview=kw["preview"], agreement=kw["agreement"], marker_id=next_marker,
        retired_at=current["route_resume_retired_at"], deadline_at=current["route_resume_deadline_at"],
    )
    assert signed
    # Synthetic observation of a distinct correctly signed generation; this
    # helper never rotates, writes or reissues. Verify exact proof separation.
    _mutate(db, oid, route_resume_id=next_marker, **{resume._SEAL: signed._digest})
    replacement = _bind(data)
    assert replacement and replacement != original and _matches(data, replacement)
    assert not _matches(data, original)
