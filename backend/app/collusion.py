"""Детект накрутки доверия сговором (Sybil): несколько учёток гоняют взаимные брони/оценки/
инвайты, чтобы поднять друг другу trips/rating. КОНСЕРВАТИВНО — только СИГНАЛ админу на ручной
разбор, без авто-наказаний: ложное срабатывание не должно бить по честным «между своими»
(двое реально часто ездят вместе — это нормально, решает человек).

Вычислимые из схемы сигналы: взаимный реферал (A ввёл код B и B — код A); взаимные 5★
(distinct брони в обе стороны); много завершённых броней ровно между той же парой.
Телефон/устройство как сигнал недоступны (unique-констрейнты на phone/token) — их тут нет.
"""
from collections import defaultdict

from sqlmodel import Session, select

from .models import Booking, BookingStatus, Rating, Ride, User


def _pair(a: int, b: int) -> tuple[int, int]:
    """Неориентированный ключ пары (меньший id первым) — чтобы A/B и B/A совпадали."""
    return (a, b) if a <= b else (b, a)


def reciprocal_invites(session: Session) -> set[tuple[int, int]]:
    """Пары со взаимным рефералом: A ввёл код B И B ввёл код A. У честных так не бывает —
    код вводят один раз, обычно ДО появления «своих» в приложении."""
    rows = session.exec(select(User.id, User.referred_by).where(User.referred_by != None)).all()  # noqa: E711
    ref = {uid: rby for uid, rby in rows}
    out: set[tuple[int, int]] = set()
    for uid, rby in ref.items():
        if ref.get(rby) == uid:
            out.add(_pair(uid, rby))
    return out


def mutual_high_ratings(session: Session, min_each: int = 2) -> dict[tuple[int, int], int]:
    """Пары со взаимными высокими оценками (≥5★ в ОБЕ стороны), по distinct броням.
    Возвращает пара→min(число в одну сторону, в другую). Так накручивают средний сговором."""
    rows = session.exec(
        select(Rating.rater_id, Rating.ratee_id, Rating.booking_id)
        .where(Rating.stars >= 5, Rating.excluded == False)  # noqa: E712
    ).all()
    by_dir: dict[tuple[int, int], set] = defaultdict(set)
    for rater, ratee, bid in rows:
        by_dir[(rater, ratee)].add(bid)
    out: dict[tuple[int, int], int] = {}
    for (a, b), a_books in by_dir.items():
        key = _pair(a, b)
        if key in out:
            continue
        b_books = by_dir.get((b, a), set())
        if len(a_books) >= min_each and len(b_books) >= min_each:
            out[key] = min(len(a_books), len(b_books))
    return out


def pair_trip_counts(session: Session, min_trips: int = 4) -> dict[tuple[int, int], int]:
    """Пары с большим числом завершённых броней РОВНО между собой (в любую сторону). Booking
    переводится в done «на доверии» без сверки — вдвоём дёшево накрутить trips/Надёжность."""
    rows = session.exec(
        select(Booking.passenger_id, Ride.driver_id)
        .join(Ride, Ride.id == Booking.ride_id)
        .where(Booking.status == BookingStatus.done)
    ).all()
    counts: dict[tuple[int, int], int] = defaultdict(int)
    for pax, drv in rows:
        if pax and drv and pax != drv:
            counts[_pair(pax, drv)] += 1
    return {k: c for k, c in counts.items() if c >= min_trips}


def find_suspects(session: Session, limit: int = 100) -> list[dict]:
    """Подозрительные пары по трём сигналам, отсортированные по числу РАЗНЫХ сигналов
    (несколько сигналов на пару = убедительнее). Только для ручного разбора админом —
    авто-действий нет; накрученное админ снимает готовыми ручками (Rating.excluded / инцидент)."""
    inv = reciprocal_invites(session)
    rat = mutual_high_ratings(session)
    trp = pair_trip_counts(session)
    keys = set(inv) | set(rat) | set(trp)
    ids = {u for k in keys for u in k}
    names: dict[int, str] = {}
    if ids:
        names = dict(session.exec(select(User.id, User.name).where(User.id.in_(ids))).all())
    suspects: list[dict] = []
    for key in keys:
        signals: list[str] = []
        if key in inv:
            signals.append("reciprocal_invite")
        if key in rat:
            signals.append(f"mutual_5star×{rat[key]}")
        if key in trp:
            signals.append(f"pair_trips×{trp[key]}")
        a, b = key
        suspects.append({
            "pair": [a, b],
            "names": [names.get(a, "—"), names.get(b, "—")],
            "signals": signals,
            "score": len(signals),
        })
    suspects.sort(key=lambda s: (-s["score"], s["pair"]))
    return suspects[:limit]
