"""«Везу обратно» — не вечный статус (независимая проверка аудита 2026-08-07).

Из `returning` вели только две двери, и обе ручные: курьер жмёт «Вернул отправителю» либо
вмешивается админ. Курьер пропал на обратном пути — заявка висит, пока Александр не разберёт
её руками. Отправителю отмена в этом статусе запрещена, а ночная чистка закрывала только
`created`.

Для `in_transit` отсутствие автоматики решено осознанно и записано в коде: посылка у человека
в руках, закрывать её по таймеру вредно. Про `returning` просто не подумали — а там посылка
едет НАЗАД, отправитель её уже не ждёт, и держать заявку живой смысла нет.

Заодно: `returned` — такой же терминальный статус, как `delivered` и `canceled`
(`parcels._FINAL_STATUSES`), но в правило ночной чистки его забыли добавить. Возвращённые
доставки копились в базе вечно.
"""
from datetime import timedelta

from sqlmodel import Session

from app.cleanup import _rules, close_stale_parcels
from app.config import settings
from app.db import engine
from app.models import ParcelDelivery
from app.timeutil import utcnow


def _parcel(status: str, age_days: int, sender_id: int) -> int:
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=sender_id, status=status,
                           from_city="Баймак", to_city="Сибай",
                           created_at=utcnow() - timedelta(days=age_days))
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def test_stuck_returning_parcel_is_closed(user_factory):
    """Курьер пропал на обратном пути — заявка не должна висеть до вмешательства человека."""
    sender = user_factory("Отправитель")
    old = settings.parcel_open_days
    pid = _parcel("returning", age_days=old + 5, sender_id=sender["id"])

    close_stale_parcels()

    with Session(engine) as s:
        assert s.get(ParcelDelivery, pid).status == "returned", (
            "доставка застряла в «везу обратно» — из этого статуса нет автоматического выхода"
        )


def test_fresh_returning_parcel_is_left_alone(user_factory):
    """Свежий возврат трогать нельзя — курьер прямо сейчас в пути к отправителю."""
    sender = user_factory("Отправитель2")
    pid = _parcel("returning", age_days=0, sender_id=sender["id"])

    close_stale_parcels()

    with Session(engine) as s:
        assert s.get(ParcelDelivery, pid).status == "returning"


def test_in_transit_is_still_left_alone(user_factory):
    """Проверка на месте: «везу получателю» автоматика по-прежнему НЕ трогает —
    посылка у человека в руках, и это решено осознанно."""
    sender = user_factory("Отправитель3")
    pid = _parcel("in_transit", age_days=settings.parcel_open_days + 5, sender_id=sender["id"])

    close_stale_parcels()

    with Session(engine) as s:
        assert s.get(ParcelDelivery, pid).status == "in_transit"


def test_cleanup_rule_covers_returned():
    """Возвращённые доставки должны попадать под ночную чистку наравне с остальными."""
    rule = next(r for r in _rules(utcnow()) if r[1] == "parceldelivery")
    where = rule[2]
    for status in ("delivered", "canceled", "returned"):
        assert f"'{status}'" in where, (
            f"статус «{status}» терминальный, но под чистку не попадает — такие строки копятся вечно"
        )
