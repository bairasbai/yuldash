"""Ключ и отпечаток одной даты серии попуток."""
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import HTTPException
from sqlalchemy import func
from sqlalchemy.exc import SQLAlchemyError
from sqlmodel import Session, select

from . import idempotency
from .errors import herr
from .flood import TOO_MANY_RIDES
from .idempotency import Command, CommandInput, Scope
from .logs import log
from .models import CommandReceipt
from .schemas import RideSeriesIn, RideSeriesItem, RideSeriesOut
from .timeutil import client_dt_to_utc


MAX_SERIES_DATES = 10
MAX_BASE_KEY_LENGTH = 115
DATE_KEY_SUFFIX_LENGTH = 13


REFUSALS = {
    "time_passed": (400, "Время выезда уже прошло", "Сығыу ваҡыты үтеп киткән"),
    "rest_block": (403, "После смены доступна одна попутка домой.", "Сменанан һуң өйгә бер юлдаш сәфәре мөмкин."),
    "too_many_rides": (429, *TOO_MANY_RIDES),
    "series_details_invalid": (
        400, "Проверь маршрут, остановки и цену поездки.", "Юлды, туҡталыштарҙы һәм сәфәр хаҡын тикшер.",
    ),
    "series_publishing_forbidden": (
        403, "Сейчас эту поездку нельзя опубликовать. Проверь условия в кабинете.",
        "Әле был сәфәрҙе иғлан итеп булмай. Кабинеттағы шарттарҙы тикшер.",
    ),
}
_CLOSED = ("Действие закрыто без выполнения.", "Ғәмәл башҡарылмайынса ябылды.")
_UNKNOWN = ("Результат пока неизвестен. Проверь его ещё раз.", "Һөҙөмтә әлегә билдәһеҙ. Тағы бер тапҡыр тикшер.")


def localize_series_receipt(view: dict) -> dict:
    if view["state"] == "rejected" and view["scope"] == Scope.RIDE_CREATE:
        refusal = REFUSALS.get(view["result_code"])
        if refusal is not None:
            _, ru, ba = refusal
            return {**view, "ru": ru, "ba": ba}
    return view


@dataclass(frozen=True)
class SeriesDate:
    departure: datetime
    command: Command


@dataclass(frozen=True)
class SeriesCommand:
    base_key: str
    common_hash: str
    dates: list[SeriesDate]


def series_command(body: RideSeriesIn, keyed: CommandInput | None) -> SeriesCommand:
    if keyed is None:
        raise herr(422, "Не получилось распознать серию поездок.", "Сәфәрҙәр теҙмәһен танып булманы.",
                   code="series_key_required")
    if len(keyed.key) > MAX_BASE_KEY_LENGTH:
        raise herr(422, "Не получилось распознать действие. Обнови приложение.",
                   "Ғәмәлде танып булманы. Ҡушымтаны яңырт.", code="idempotency_key_invalid")
    if body.ride.recurrence != "none":
        raise herr(400, "Выбери одно: повтор или несколько дат.", "Бер вариантты һайла: ҡабатлау йәки бер нисә көн.",
                   code="series_with_recurrence")
    if len(body.departures) > MAX_SERIES_DATES:
        raise herr(400, "За один раз — не больше 10 дат.", "Бер юлы 10 көнгә тиклем һайлап була.",
                   code="series_too_many")

    try:
        departures = sorted({client_dt_to_utc(date).replace(second=0, microsecond=0) for date in body.departures})
    except (OverflowError, ValueError):
        raise herr(422, "Проверь даты выезда.", "Сығыу көндәрен тикшер.", code="series_departure_invalid") from None

    ride = (keyed.wire_body["ride"] if keyed.wire_body is not None
            else body.ride.model_dump(mode="json", exclude_unset=True))
    common = {"ride": {name: value for name, value in ride.items() if name != "depart_at"}}
    shared = CommandInput(keyed.key, common).command(Scope.RIDE_CREATE, None, common)
    result = []
    for date in departures:
        utc_date = date.replace(tzinfo=timezone.utc)
        iso = utc_date.isoformat(timespec="seconds").replace("+00:00", "Z")
        suffix = iso[:16].replace("-", "").replace("T", "").replace(":", "")
        date_body = {**common, "depart_at": iso}
        command = CommandInput(f"{keyed.key}-{suffix}", date_body).command(Scope.RIDE_CREATE, None, date_body)
        result.append(SeriesDate(utc_date, command))
    return SeriesCommand(keyed.key, shared.payload_hash, result)


def refusal(code: str) -> HTTPException:
    status, ru, ba = REFUSALS[code]
    return herr(status, ru, ba, code=code)


def has_published_date(session: Session, owner_id: int, base_key: str) -> bool:
    """Квитанции прошлых дат сохраняются после удаления уведомлений и рейсов."""
    return session.exec(select(CommandReceipt.id).where(
        CommandReceipt.user_id == owner_id,
        CommandReceipt.scope == Scope.RIDE_CREATE.value,
        CommandReceipt.state == "applied",
        CommandReceipt.request_key.startswith(base_key + "-", autoescape=True),
        func.length(CommandReceipt.request_key) == len(base_key) + DATE_KEY_SUFFIX_LENGTH,
    ).limit(1)).first() is not None


def _known_result(date: SeriesDate, intent: dict) -> RideSeriesItem | None:
    state = intent["state"]
    code = intent["result_code"]
    if state == "applied":
        return RideSeriesItem(depart_at=date.departure, outcome="applied", ride_id=intent["ref_id"],
                              already=True, code=code)
    if state == "closed_no_effect":
        code = "idempotency_closed_no_effect"
        ru, ba = _CLOSED
    elif state == "rejected":
        _, ru, ba = REFUSALS.get(code, REFUSALS["series_details_invalid"])
    else:
        return None
    return RideSeriesItem(depart_at=date.departure, outcome="rejected", already=True, code=code, ru=ru, ba=ba)


def _unknown_result(date: SeriesDate) -> RideSeriesItem:
    ru, ba = _UNKNOWN
    return RideSeriesItem(depart_at=date.departure, outcome="unknown", code="series_outcome_unknown", ru=ru, ba=ba)


def publish_dates(
    session: Session, owner_id: int, series: SeriesCommand,
    prepare_date: Callable[[Session, SeriesDate], Callable[[], idempotency.Applied]],
) -> RideSeriesOut:
    """У каждой даты свой commit, ключ, результат и сверка в новой сессии."""
    bind = session.get_bind()

    def read(date: SeriesDate) -> RideSeriesItem | None:
        try:
            with Session(bind) as inspection:
                intent = idempotency.read_matching_intent(inspection, owner_id, date.command)
        except SQLAlchemyError as error:
            log.warning("[RIDE_SERIES] failed to read receipt: %s", type(error).__name__)
            return _unknown_result(date)
        return _known_result(date, intent)

    # Сверить весь запрос до первой новой публикации. Изменённое ранее
    # сохранённое намерение отклоняет запрос целиком.
    prior = [read(date) for date in series.dates]
    if any(item is None for item in prior):
        try:
            idempotency.bind_ride_series(session, owner_id, series.base_key, series.common_hash)
        except SQLAlchemyError as error:
            log.warning("[RIDE_SERIES] failed to bind common fields: %s", type(error).__name__)
            prior = [item or _unknown_result(date) for date, item in zip(series.dates, prior)]
    items = []
    for date, known in zip(series.dates, prior):
        if known is not None:
            items.append(known)
            continue
        try:
            with Session(bind) as publication:
                apply = prepare_date(publication, date)
                response = idempotency.execute(
                    publication, owner_id, date.command, apply,
                    replay_response=lambda value: {"id": value["id"], "already": True},
                    rejected_response=lambda code: refusal(code) if code in REFUSALS else None,
                    final_refusals=REFUSALS,
                )
            item = RideSeriesItem(depart_at=date.departure, outcome="applied", ride_id=response["id"],
                                  already=response["already"])
        except HTTPException as error:
            if isinstance(error.detail, dict) and error.detail.get("code") == "idempotency_key_reused":
                raise
            item = read(date) or _unknown_result(date)
        except Exception as error:
            log.warning("[RIDE_SERIES] checking outcome after %s", type(error).__name__)
            item = read(date) or _unknown_result(date)
        items.append(item)
    return RideSeriesOut(
        published=sum(item.outcome == "applied" for item in items),
        failed=sum(item.outcome == "rejected" for item in items),
        unresolved=sum(item.outcome == "unknown" for item in items),
        items=items,
    )
