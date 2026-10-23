"""Профиль подписывает человека по тому, что он делает (просьба Александра 23.10.2026).

Один аккаунт может и возить, и ездить. Подпись в шапке профиля бралась только из роли на сервере,
а роль от публикации поездок не меняется: человек, который возит соседей каждую неделю, был
«Пассажир». `/me` теперь говорит, публиковал ли он поездки (`drives`) и бронировал ли чужие (`books`).
"""
from app.models import UserRole

from test_flows import _book, _publish


def _me(client, who):
    r = client.get("/me", headers=who["auth"])
    assert r.status_code == 200, r.text
    return r.json()


def test_new_account_neither_drives_nor_books(client, user_factory):
    me = _me(client, user_factory("Профиль Новичок"))
    assert me["drives"] is False and me["books"] is False


def test_publishing_makes_a_driver_and_booking_makes_a_passenger(client, user_factory):
    both = user_factory("Профиль Оба")
    other = user_factory("Профиль Водитель", role=UserRole.driver)

    _publish(client, both, frm="Сибай", to="Баймак")
    me = _me(client, both)
    assert me["drives"] is True and me["books"] is False

    _book(client, both, _publish(client, other)["id"])
    me = _me(client, both)
    assert me["drives"] is True and me["books"] is True
