"""Поездки теста не остаются в общей ленте после него (CI PR212, 24.10.2026).

На PostgreSQL весь набор идёт одним процессом в одной базе, и лента (`GET /rides`, первая страница —
200 поездок, поднятые Boost — первыми) общая для всех тестов. Тест, оставивший живые поднятые поездки
или поездки «через 10 минут», вытесняет с первой страницы поездки следующих тестов — и те падают не
по своей вине (`test_health_hint_stays_private`: «выборка сузилась до больничных»).

Подключение в модуле теста: `from feed_hygiene import rides_do_not_leak_into_the_feed  # noqa: F401`.
После каждого теста модуля живые поездки, которых до него не было, снимаются, а поднятие на них
гасится. Чужие поездки (живые до теста) не трогаем. Сравниваем множества, а не «id больше прежнего
максимума»: в общей базе есть поездки с вручную заданным огромным id (9 210 303), и граница по id
молча не снимала ничего.
"""
import pytest
from sqlalchemy import update
from sqlmodel import Session, select

from app.db import engine
from app.models import Ride, RideStatus


def _alive_ids(s: Session) -> set:
    return set(s.exec(select(Ride.id).where(Ride.status == RideStatus.active)).all())


@pytest.fixture(autouse=True)
def rides_do_not_leak_into_the_feed():
    with Session(engine) as s:
        before = _alive_ids(s)
    yield
    with Session(engine) as s:
        leaked = sorted(_alive_ids(s) - before)
        if leaked:
            s.execute(update(Ride).where(Ride.id.in_(leaked))
                      .values(status=RideStatus.cancelled, boosted_until=None))
            s.commit()
