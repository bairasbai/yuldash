"""Рассылка «карауль маршрут» уважает чёрный список и «только для своих» (аудит 2026-08-07).

Подписка «сообщи, когда появится поездка Баймак → Сибай» рассылала пуш всем, чей маршрут
совпал, — мимо обеих защит:

* **чёрный список.** Человек, которого водитель заблокировал, получал уведомление о его
  поездке. Блокировка нужна ровно для того, чтобы этот человек тебя не находил.
* **«только для своих».** Закрытую поездку лента прячет, прямая ссылка отдаёт «не найдено»,
  забронировать её теперь тоже нельзя — а пуш о ней приходил кому угодно. Само существование
  поездки — это информация: в Баймаке по одному оповещению узнают, кто, куда и когда едет.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import Block, Notification, Ride, RideStatus, RouteWatch, UserRole
from app.services import notify_route_watchers
from app.timeutil import utcnow


def _watch(user_id: int) -> None:
    with Session(engine) as s:
        s.add(RouteWatch(user_id=user_id, from_city="Баймак", to_city="Сибай",
                         expires_at=utcnow() + timedelta(days=7), watch_kind="both"))
        s.commit()


def _ride(driver_id: int, only_trusted: bool = False) -> Ride:
    with Session(engine) as s:
        r = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                 depart_at=utcnow() + timedelta(hours=5), seats=4, seats_left=4, price=300,
                 status=RideStatus.active, only_trusted=only_trusted)
        s.add(r)
        s.commit()
        s.refresh(r)
        return r


def _notified(user_id: int) -> int:
    with Session(engine) as s:
        return len(s.exec(select(Notification).where(
            Notification.user_id == user_id, Notification.type == "route_watch")).all())


def test_blocked_user_is_not_notified(user_factory):
    drv = user_factory("Водитель", role=UserRole.driver)
    stalker = user_factory("Заблокированный")
    _watch(stalker["id"])
    with Session(engine) as s:
        s.add(Block(user_id=drv["id"], blocked_user_id=stalker["id"]))
        s.commit()

    ride = _ride(drv["id"])
    with Session(engine) as s:
        notify_route_watchers(s, ride)

    assert _notified(stalker["id"]) == 0, (
        "заблокированный получил уведомление о поездке того, кто его заблокировал"
    )


def test_trusted_only_ride_is_not_announced_to_outsiders(user_factory):
    drv = user_factory("Водитель2", role=UserRole.driver)
    outsider = user_factory("Посторонний")
    _watch(outsider["id"])

    ride = _ride(drv["id"], only_trusted=True)
    with Session(engine) as s:
        notify_route_watchers(s, ride)

    assert _notified(outsider["id"]) == 0, (
        "о закрытой поездке «только для своих» узнал посторонний"
    )


def test_ordinary_watcher_still_gets_the_push(user_factory):
    """Обратная сторона: обычная подписка обязана работать как раньше."""
    drv = user_factory("Водитель3", role=UserRole.driver)
    neighbour = user_factory("Сосед")
    _watch(neighbour["id"])

    ride = _ride(drv["id"])
    with Session(engine) as s:
        # Проверяем именно СВОЕГО подписчика, а не общее число: подписки соседних тестов
        # караулят тот же маршрут и на эту поездку тоже сработают — это верно, они не
        # заблокированы этим водителем и поездка открытая.
        assert notify_route_watchers(s, ride) >= 1

    assert _notified(neighbour["id"]) == 1
