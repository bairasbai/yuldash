"""Транзакционная защита команд попутки при потере ответа."""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum
from hashlib import sha256
import json
import re
from typing import Annotated, Any

from fastapi import Depends, Header, HTTPException, Request
from fastapi.encoders import jsonable_encoder
from sqlalchemy import event, text
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from .command_fingerprint import KEY_PREFIX, parse_body, wire_fingerprint
from .errors import herr
from .logs import log
from .meeting_points import booking_public_dto
from .models import Booking, BookingStatus, CommandReceipt, Ride, RideStatus, User, UserRole
from .services import is_blocked, ride_out
from .timeutil import utcnow


REPLAY_HOURS = 24
_KEY = re.compile(r"[A-Za-z0-9_-]{16,128}")
IdempotencyKey = Annotated[str | None, Header(alias="Idempotency-Key")]


class Scope(str, Enum):
    RIDE_CREATE = "ride.create"
    RIDE_CANCEL = "ride.cancel"
    RIDE_COMPLETE = "ride.complete"
    BOOKING_CREATE = "booking.create"
    BOOKING_CONFIRM = "booking.confirm"
    BOOKING_CANCEL = "booking.cancel"
    BOOKING_NO_SHOW = "booking.no_show"
    BOOKING_DRIVER_STATUS = "booking.driver_status"
    BOOKING_TRIP_STATUS = "booking.trip_status"

    @property
    def ref_kind(self) -> str:
        return self.value.split(".", 1)[0]


def _invalid_key() -> HTTPException:
    return herr(422, "Не получилось распознать действие. Обнови приложение.",
                "Ғәмәлде танып булманы. Ҡушымтаны яңырт.", code="idempotency_key_invalid")


def validate_key(key: str) -> str:
    if not _KEY.fullmatch(key):
        raise _invalid_key()
    return key


def fingerprint(scope: Scope, target_id: int | None, body: Any) -> str:
    payload = {"scope": scope.value, "target_id": target_id,
               "body": jsonable_encoder(body, exclude_unset=True)}
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return sha256(canonical.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Command:
    key: str
    scope: Scope
    target_id: int | None
    payload_hash: str

    @classmethod
    def from_body(cls, key: str, scope: Scope, target_id: int | None, body: Any = None) -> "Command":
        return cls(validate_key(key), scope, target_id, fingerprint(scope, target_id, body))


def _invalid_payload() -> HTTPException:
    return herr(422, "Не получилось прочитать данные действия.", "Ғәмәл мәғлүмәттәрен уҡып булманы.",
                code="idempotency_payload_invalid")


@dataclass(frozen=True)
class CommandInput:
    key: str
    wire_body: Any = None

    def command(self, scope: Scope, target_id: int | None, typed_body: Any) -> Command:
        if not self.key.startswith(KEY_PREFIX):
            return Command.from_body(self.key, scope, target_id, typed_body)
        try:
            return Command(self.key, scope, target_id, wire_fingerprint(scope.value, target_id, self.wire_body))
        except ValueError:
            raise _invalid_payload() from None


async def command_input(request: Request, idempotency_key: IdempotencyKey = None) -> CommandInput | None:
    if idempotency_key is None:
        return None
    key = validate_key(idempotency_key)
    if not key.startswith(KEY_PREFIX):
        return CommandInput(key)
    try:
        return CommandInput(key, parse_body(await request.body()))
    except ValueError:
        raise _invalid_payload() from None


KeyedBody = Annotated[CommandInput | None, Depends(command_input)]


async def require_wire_command(value: KeyedBody) -> CommandInput:
    if value is None or not value.key.startswith(KEY_PREFIX):
        raise _invalid_key()
    return value


async def require_wire_command_without_body(value: Annotated[CommandInput, Depends(require_wire_command)]) -> None:
    if value.wire_body is not None:
        raise _invalid_payload()


@dataclass(frozen=True)
class Applied:
    ref_id: int
    ref_created_at: datetime
    response: Any
    after_commit: Callable[[], None] | None = None


def _lock(session: Session, owner_id: int, key: str) -> None:
    dialect = session.get_bind().dialect.name
    if dialect == "sqlite":
        # current_user уже читал через эту сессию. Начать настоящую внешнюю транзакцию
        # до SELECT/SAVEPOINT, иначе RELEASE может сам сохранить изменения SQLite.
        session.rollback()
        session.connection().exec_driver_sql("BEGIN IMMEDIATE")
    elif dialect == "postgresql":
        digest = sha256(f"yuldash:command:{owner_id}:{key}".encode("ascii")).digest()
        lock_id = int.from_bytes(digest[:8], byteorder="big", signed=True)
        session.execute(text("SELECT pg_advisory_xact_lock(:lock_id)"), {"lock_id": lock_id})
    else:
        raise RuntimeError("Unsupported command receipt database")


def _find(session: Session, owner_id: int, key: str) -> CommandReceipt | None:
    return session.exec(select(CommandReceipt).where(
        CommandReceipt.user_id == owner_id, CommandReceipt.request_key == key,
    ).execution_options(populate_existing=True)).one_or_none()


def _match(receipt: CommandReceipt, command: Command) -> None:
    if (receipt.scope, receipt.target_id, receipt.payload_hash) != (
        command.scope.value, command.target_id, command.payload_hash,
    ):
        raise herr(409, "Данные этого действия изменились. Сначала проверь прошлый результат.",
                   "Был ғәмәлдең мәғлүмәттәре үҙгәрҙе. Тәүҙә элекке һөҙөмтәне тикшер.",
                   code="idempotency_key_reused")


def _denied() -> HTTPException:
    return herr(403, "Результат сейчас недоступен.", "Һөҙөмтә хәҙер асыҡ түгел.")


def _current(session: Session, owner_id: int, receipt: CommandReceipt) -> dict | None:
    if receipt.ref_kind == "ride":
        ride = session.get(Ride, receipt.ref_id, populate_existing=True)
        if ride is None or ride.created_at != receipt.ref_created_at:
            return None
        if ride.driver_id != owner_id:
            actor = session.get(User, owner_id, populate_existing=True)
            if actor is None or actor.role != UserRole.admin or receipt.scope not in (
                Scope.RIDE_CANCEL.value, Scope.RIDE_COMPLETE.value,
            ):
                raise _denied()
        return ride_command_dto(session, ride)

    booking = session.get(Booking, receipt.ref_id, populate_existing=True)
    if booking is None or booking.created_at != receipt.ref_created_at:
        return None
    ride = session.get(Ride, booking.ride_id, populate_existing=True)
    if ride is None:
        return None
    if owner_id not in (booking.passenger_id, ride.driver_id) or is_blocked(session, booking.passenger_id, ride.driver_id):
        raise _denied()
    return booking_command_dto(session, booking, ride, owner_id)


def ride_command_dto(session: Session, ride: Ride) -> dict:
    from .ride_stops import clean_waypoints, split_raw

    current = ride_out(ride, session).model_dump(mode="json")
    current["waypoints"] = clean_waypoints(ride)
    stops = set(split_raw(current["waypoints"]))
    current["stop_prices"] = [entry for entry in current["stop_prices"] if entry["stop"] in stops]
    if ride.status != RideStatus.active:
        current.update(pickup_lat=None, pickup_lng=None, pickup="", comment="", receiver_name=None)
    return current


def booking_command_dto(session: Session, booking: Booking, ride: Ride, owner_id: int) -> dict:
    current = booking_public_dto(session, booking, ride, owner_id)
    if booking.status not in (BookingStatus.confirmed, BookingStatus.onboard) or owner_id != booking.passenger_id:
        current["boarding_code"] = ""
    if booking.status in (BookingStatus.cancelled, BookingStatus.done):
        current.update(
            pickup_lat=None, pickup_lng=None, pickup_note="", pickup_landmark_ru="", pickup_landmark_ba="",
            pickup_source="", dropoff_lat=None, dropoff_lng=None, dropoff_note="", dropoff_landmark_ru="",
            dropoff_landmark_ba="", dropoff_source="", meeting_proposal=None, meeting_proposal_pending=False,
        )
    return jsonable_encoder(current)


def booking_applied(session: Session, booking: Booking, ride: Ride, owner_id: int, *,
                    after_commit: Callable[[], None] | None = None) -> Applied:
    return Applied(booking.id, booking.created_at, booking_command_dto(session, booking, ride, owner_id), after_commit)


def _view(session: Session, owner_id: int, receipt: CommandReceipt | None) -> dict:
    if receipt is None:
        return dict(state="unseen", scope=None, ref_kind=None, ref_id=None, result_code=None, current=None)
    current = _current(session, owner_id, receipt) if receipt.state == "applied" else None
    result_code = receipt.result_code
    if receipt.state == "applied" and current is None:
        result_code = "idempotency_result_gone"
    view = dict(state=receipt.state, scope=receipt.scope, ref_kind=receipt.ref_kind,
                ref_id=receipt.ref_id, result_code=result_code, current=current)
    if receipt.state == "rejected" and receipt.result_code == "booking_traveler_conflict":
        booking_id = _visible_conflict_booking_id(session, owner_id, receipt)
        if booking_id is not None:
            view["booking_id"] = booking_id
    return view


def _visible_conflict_booking_id(session: Session, owner_id: int, receipt: CommandReceipt) -> int | None:
    if receipt.conflict_booking_id is None:
        return None
    booking = session.get(Booking, receipt.conflict_booking_id, populate_existing=True)
    if (booking is None or booking.created_at != receipt.conflict_booking_created_at
            or booking.passenger_id != owner_id or booking.ride_id != receipt.target_id
            or booking.status not in (BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard)):
        return None
    ride = session.get(Ride, booking.ride_id, populate_existing=True)
    if ride is None or is_blocked(session, owner_id, ride.driver_id):
        return None
    return booking.id


def read_intent(session: Session, owner_id: int, key: str) -> dict:
    return _view(session, owner_id, _find(session, owner_id, validate_key(key)))


GUARDIAN_PHONE_INVALID = (400, "Проверь номер телефона взрослого.", "Оло кешенең телефон номерын тикшер.")
TRAVELER_WOMEN_ONLY = (
    409, "В поездку «Только женщины» можно бронировать место только для себя.",
    "«Тик ҡатын-ҡыҙ» сәфәренә урынды үҙең өсөн генә бронларға була.",
)
TRAVELER_NO_MINORS = (
    409, "Водитель не берёт пассажиров младше 18 без сопровождения взрослого. Поищи другую поездку.",
    "Водитель 18-ҙән кесе юлсыларҙы оло кеше оҙатыуынан башҡа алмай. Башҡа сәфәр эҙлә.",
)
TRAVELER_CONFLICT = (
    409, "У тебя уже есть бронь с другими данными пассажира. Открой её или отмени и забронируй заново.",
    "Һинең юлсы мәғлүмәттәре икенсе булған бронең бар. Уны ас йәки кире ал да яңынан бронла.",
)


class BookingTravelerConflict(HTTPException):
    def __init__(self, booking: Booking):
        status, ru, ba = TRAVELER_CONFLICT
        super().__init__(status, dict(ru=ru, ba=ba, code="booking_traveler_conflict", booking_id=booking.id))
        self.booking_id = booking.id
        self.booking_created_at = booking.created_at


_FINAL_REFUSALS = {
    "guardian_phone_invalid": GUARDIAN_PHONE_INVALID,
    "booking_traveler_women_only": TRAVELER_WOMEN_ONLY,
    "booking_traveler_no_minors": TRAVELER_NO_MINORS,
    "booking_traveler_conflict": TRAVELER_CONFLICT,
    "ride_terms_changed": (409, "Условия поездки изменились.", "Сәфәр шарттары үҙгәрҙе."),
    "price_changed": (409, "Цена поездки изменилась.", "Сәфәр хаҡы үҙгәрҙе."),
    "seats_not_enough": (400, "На этом участке не хватило мест.", "Был участкала урындар етмәне."),
    "front_seat_taken": (409, "Переднее место уже занято", "Алғы урын инде алынған"),
    "cancel_terms_changed": (409, "Условия отмены изменились.", "Кире алыу шарттары үҙгәрҙе."),
    "driver_marks_boarding": (409, "Посадку отмечает водитель.", "Ултырыуҙы йөрөтөүсе билдәләй."),
    "booking_cannot_confirm": (400, "Эту бронь уже нельзя подтвердить", "Был бронде инде раҫлап булмай"),
    "onboard_no_cancel": (409, "Водитель отметил посадку. Не садился — сообщи о проблеме.",
                          "Йөрөтөүсе ултырыуҙы билдәләне. Ултырмаған булһаң — проблема тураһында хәбәр ит."),
    "passenger_onboard": (409, "Пассажир уже отмечен в машине.", "Пассажир машинала тип билдәләнгән инде."),
    "no_show_needs_arriving": (409, "Сначала отметь «Подъезжаю».", "Тәүҙә «Яҡынлашам» тип билдәлә."),
    "no_show_too_early": (409, "Пассажир ещё может подойти.", "Пассажир әле килеп етеүе мөмкин."),
    "no_show_requires_confirmed": (409, "Неявку можно отметить только по подтверждённой брони",
                                   "Килмәүҙе тик раҫланған бронь буйынса ғына билдәләп була"),
    "ride_already_completed": (400, "Поездка уже завершена", "Сәфәр инде тамамланған"),
    "ride_cancelled": (400, "Поездка отменена — завершать нечего", "Сәфәр кире алынған — тамамлар нәмә юҡ"),
    "trip_not_started": (409, "Поездка ещё не началась — завершить можно после времени выезда",
                         "Сәфәр әле башланмаған — сығыу ваҡытынан һуң тамамлап була"),
    "trip_inactive": (409, "Поездка не активна", "Сәфәр актив түгел"),
    "driver_status_invalid": (400, "Недопустимый статус", "Ярамаған хәл"),
    "boarding_requires_confirmed": (409, "Посадку можно отметить только у подтверждённого пассажира",
                                    "Ултырыуҙы тик раҫланған пассажирға ғына билдәләп була"),
    "boarding_too_early": (409, "Пока рано отмечать посадку.", "Ултырыуҙы билдәләргә әле иртә."),
    "departed_too_early": (409, "Пока рано отмечать выезд.", "Юлға сығыуҙы билдәләргә әле иртә."),
    "arriving_too_early": (409, "Пока рано отмечать прибытие.", "Яҡынлашыуҙы билдәләргә әле иртә."),
    "arriving_too_far": (409, "Машина ещё далеко от места встречи.", "Машина осрашыу урынынан әле алыҫ."),
    "trip_status_invalid": (400, "Недопустимый статус поездки", "Ярамаған сәфәр хәле"),
    "arrived_requires_onboard": (409, "«Доехал» можно отметить в пути — после отметки посадки.",
                                 "«Барып еттем» тип ултырыуҙы билдәләгәндән һуң әйтеп була."),
    "done_requires_onboard": (409, "Поездку завершит водитель после посадки",
                              "Сәфәрҙе ултырғандан һуң йөрөтөүсе тамамлар"),
    "boarding_undo_open": (409, "Водитель только что отметил посадку. Подожди немного.",
                          "Йөрөтөүсе әле генә ултырыуҙы билдәләне. Бер аҙ көт."),
}


def _final_refusal(error: HTTPException) -> str | None:
    code = error.detail.get("code") if isinstance(error.detail, dict) else None
    saved = _FINAL_REFUSALS.get(code)
    return code if saved and error.status_code == saved[0] else None


def _replay(session: Session, owner_id: int, command: Command, receipt: CommandReceipt,
            rejected_response: Callable[[str], HTTPException | None] | None) -> dict:
    _match(receipt, command)
    view = _view(session, owner_id, receipt)
    if utcnow() - receipt.created_at >= timedelta(hours=REPLAY_HOURS):
        raise herr(409, "Прямой повтор устарел. Проверь сохранённый результат.",
                   "Туранан-тура ҡабатлау иҫкерҙе. Һаҡланған һөҙөмтәне тикшер.",
                   code="idempotency_key_expired", intent_path=f"/command-intents/{command.key}")
    if receipt.state == "rejected":
        if rejected_response is not None:
            response = rejected_response(receipt.result_code)
            if response is not None:
                raise response
        status, ru, ba = _FINAL_REFUSALS[receipt.result_code]
        extra = {"booking_id": view["booking_id"]} if "booking_id" in view else {}
        raise herr(status, ru, ba, code=receipt.result_code, **extra)
    if receipt.state == "closed_no_effect":
        raise herr(409, "Действие закрыто без выполнения.", "Ғәмәл башҡарылмайынса ябылды.",
                   code="idempotency_closed_no_effect")
    if view["current"] is None:
        raise herr(409, "Результат больше недоступен. Повтор не создаст новую запись.",
                   "Һөҙөмтә бүтән асыҡ түгел. Ҡабатлау яңы яҙма яһамаҫ.", code="idempotency_result_gone")
    return view["current"]


def _save(session: Session, owner_id: int, command: Command, state: str, *,
          ref_id: int | None = None, ref_created_at: datetime | None = None,
          result_code: str | None = None, conflict: BookingTravelerConflict | None = None) -> CommandReceipt:
    receipt = CommandReceipt(user_id=owner_id, request_key=command.key, scope=command.scope.value,
                             target_id=command.target_id, payload_hash=command.payload_hash, state=state,
                             ref_kind=command.scope.ref_kind if ref_id is not None else None,
                             ref_id=ref_id, ref_created_at=ref_created_at, result_code=result_code,
                             conflict_booking_id=conflict.booking_id if conflict else None,
                             conflict_booking_created_at=conflict.booking_created_at if conflict else None)
    session.add(receipt)
    session.flush()
    return receipt


def _winner(session: Session, owner_id: int, command: Command, error: IntegrityError) -> CommandReceipt:
    session.rollback()
    constraint = getattr(getattr(error.orig, "diag", None), "constraint_name", None)
    sqlite_constraint = getattr(error.orig, "sqlite_errorname", None) == "SQLITE_CONSTRAINT_UNIQUE" and (
        str(error.orig) == "UNIQUE constraint failed: commandreceipt.user_id, commandreceipt.request_key"
    )
    if constraint != "uq_command_receipt_owner_key" and not sqlite_constraint:
        raise error
    _lock(session, owner_id, command.key)
    receipt = _find(session, owner_id, command.key)
    if receipt is None:
        raise error
    return receipt


def execute(session: Session, owner_id: int, command: Command, apply: Callable[[], Applied], *,
            replay_response: Callable[[dict], Any] | None = None, prepare: Callable[[], None] | None = None,
            rejected_response: Callable[[str], HTTPException | None] | None = None) -> Any:
    """apply пишет во вложенной транзакции. Только execute завершает внешнюю."""
    def replay(receipt: CommandReceipt) -> Any:
        current = _replay(session, owner_id, command, receipt, rejected_response)
        return replay_response(current) if replay_response is not None else current

    try:
        _lock(session, owner_id, command.key)
        receipt = _find(session, owner_id, command.key)
        if receipt is not None:
            return replay(receipt)
        if prepare is not None:
            # Медленная подготовка без записи. За это время ключ мог исполниться или закрыться.
            session.rollback()
            prepare()
            _lock(session, owner_id, command.key)
            receipt = _find(session, owner_id, command.key)
            if receipt is not None:
                return replay(receipt)
        try:
            try:
                with session.begin_nested() as domain:
                    def protect_transaction(active_session):
                        nested = active_session.get_nested_transaction()
                        if nested is None or nested is domain:
                            raise RuntimeError("Command mutator attempted to commit its transaction")

                    event.listen(session, "before_commit", protect_transaction)
                    try:
                        applied = apply()
                        if not domain.is_active:
                            raise RuntimeError("Command mutator rolled back its transaction")
                    finally:
                        event.remove(session, "before_commit", protect_transaction)
                    _save(session, owner_id, command, "applied",
                          ref_id=applied.ref_id, ref_created_at=applied.ref_created_at)
            except HTTPException as error:
                code = _final_refusal(error)
                if code is not None:
                    conflict = error if isinstance(error, BookingTravelerConflict) else None
                    _save(session, owner_id, command, "rejected", result_code=code, conflict=conflict)
                    session.commit()
                raise
            session.commit()
        except IntegrityError as error:
            return replay(_winner(session, owner_id, command, error))
    finally:
        if session.in_transaction():
            session.rollback()

    if applied.after_commit is not None:
        try:
            applied.after_commit()
        except Exception as error:  # уведомление не меняет уже сохранённый результат
            session.rollback()
            log.warning("[COMMAND] Post-commit action failed: scope=%s error=%s",
                        command.scope.value, type(error).__name__)
    return applied.response


def close_intent(session: Session, owner_id: int, command: Command) -> dict:
    try:
        _lock(session, owner_id, command.key)
        receipt = _find(session, owner_id, command.key)
        if receipt is None:
            try:
                receipt = _save(session, owner_id, command, "closed_no_effect", result_code="idempotency_closed_no_effect")
                view = _view(session, owner_id, receipt)
                session.commit()
                return view
            except IntegrityError as error:
                receipt = _winner(session, owner_id, command, error)
        _match(receipt, command)
        return _view(session, owner_id, receipt)
    finally:
        if session.in_transaction():
            session.rollback()
