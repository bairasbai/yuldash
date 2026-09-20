"""Legacy complete_ride inbox entries resolve only to an unambiguous personal booking."""
import pytest
from sqlalchemy import event
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Notification, UserRole
from test_api import _ride


def _setup(client, user_factory):
    driver = user_factory("LegacyCompleteDriver", role=UserRole.driver)
    owner = user_factory("LegacyCompleteOwner")
    ride = _ride(client, driver, seats=4)
    return owner, ride


def _booking(user, ride, status="done"):
    with Session(engine) as session:
        row = Booking(passenger_id=user["id"], ride_id=ride, status=BookingStatus(status))
        session.add(row)
        session.commit()
        session.refresh(row)
        return row.id


def _notice(user, ride, **changes):
    values = dict(user_id=user["id"], type="ride", ref_kind="ride", ref_id=ride,
                  title_ru="Поездка завершена", title_ba="Сәфәр тамамланды",
                  body_ru="Уфа → Сибай: спасибо, что ехали вместе! Оцени поездку.",
                  body_ba="Уфа → Сибай: бергә барғаныңа рәхмәт! Сәфәрҙе баһала.")
    values.update(changes)
    with Session(engine) as session:
        row = Notification(**values)
        session.add(row)
        session.commit()
        session.refresh(row)
        return row.id


def _inbox(client, user):
    result = client.get("/notifications", headers=user["auth"])
    assert result.status_code == 200, result.text
    return result.json()


def test_personal_completed_booking_enriches_output_without_writing(client, user_factory):
    owner, ride = _setup(client, user_factory)
    bid = _booking(owner, ride)
    other = user_factory("OtherLegacyPassenger")
    _booking(other, ride)
    nid = _notice(owner, ride)
    other_nid = _notice(other, ride)
    for _ in range(2):
        output = _inbox(client, owner)
        assert output["unread"] == 1
        assert len(output["items"]) == 1
        row = output["items"][0]
        assert row["id"] == nid and row["id"] != other_nid
        assert (row["ref_kind"], row["ref_id"]) == ("booking_done", bid)
        assert row["read"] is False
    with Session(engine) as session:
        row = session.get(Notification, nid)
        assert (row.ref_kind, row.ref_id, row.read_at) == ("ride", ride, None)
    assert client.post("/notifications/read", headers=owner["auth"], json={"id": nid}).status_code == 200
    output = _inbox(client, owner)
    assert output["unread"] == 0 and output["items"][0]["read"] is True


@pytest.mark.parametrize("case", ["duplicate", "foreign", "pending", "confirmed", "onboard", "cancelled", "missing_ride"])
def test_missing_or_ambiguous_personal_completion_is_unchanged(client, user_factory, case):
    owner, ride = _setup(client, user_factory)
    if case == "duplicate":
        _booking(owner, ride)
        _booking(owner, ride)
    elif case == "foreign":
        _booking(user_factory("ForeignCompletedOwner"), ride)
    elif case != "missing_ride":
        _booking(owner, ride, case)
    else:
        ride = 999999999
    _notice(owner, ride)
    row = _inbox(client, owner)["items"][0]
    assert (row["ref_kind"], row["ref_id"]) == ("ride", ride)


@pytest.mark.parametrize("changes", [
    {"type": "route_watch"}, {"type": "system"},
    {"title_ru": "Водитель выехал"}, {"title_ba": "Башҡа хәбәр"},
    {"ref_kind": "booking"}, {"ref_id": None}, {"ref_id": -1},
])
def test_other_notification_signatures_are_unchanged(client, user_factory, changes):
    owner, ride = _setup(client, user_factory)
    _booking(owner, ride)
    _notice(owner, ride, **changes)
    row = _inbox(client, owner)["items"][0]
    assert (row["ref_kind"], row["ref_id"]) == (changes.get("ref_kind", "ride"), changes.get("ref_id", ride))


def test_ride_id_is_not_interpreted_as_booking_id(client, user_factory):
    owner, ride = _setup(client, user_factory)
    foreign = user_factory("CollisionOwner")
    personal = 10_000_000 + ride
    # A different entity may have exactly the notification's numeric reference.
    with Session(engine) as session:
        collision = session.get(Booking, ride)
        if collision is None:
            collision = Booking(id=ride, passenger_id=foreign["id"],
                                ride_id=ride, status=BookingStatus.done)
            session.add(collision)
        assert collision.id == ride and collision.passenger_id != owner["id"]
        assert personal != ride
        session.add(Booking(id=personal, passenger_id=owner["id"], ride_id=ride, status=BookingStatus.done))
        session.commit()
    _notice(owner, ride)
    row = _inbox(client, owner)["items"][0]
    assert (row["ref_kind"], row["ref_id"]) == ("booking_done", personal)


def test_legacy_resolution_batches_a_full_page(client, user_factory):
    owner, ride = _setup(client, user_factory)
    personal = _booking(owner, ride)
    for _ in range(20):
        _notice(owner, ride)
    selects = []

    def record(conn, cursor, statement, parameters, context, executemany):
        sql = statement.lower()
        if sql.lstrip().startswith("select") and "from booking" in sql:
            selects.append(statement)

    event.listen(engine, "before_cursor_execute", record)
    try:
        output = _inbox(client, owner)
    finally:
        event.remove(engine, "before_cursor_execute", record)
    assert len(output["items"]) == 20
    assert all((n["ref_kind"], n["ref_id"]) == ("booking_done", personal) for n in output["items"])
    assert len(selects) == 1, "Resolve the page in one booking query, not one query per notification"
