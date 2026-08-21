"""Уйти из сервиса нельзя было, пока чужой человек не нажмёт кнопку (волна 174).

Право забрать свои данные и уйти — закон (152-ФЗ), а не любезность. У удаления аккаунта стоят
честные гейты: не уходи с неоплаченной комиссией, посреди поездки, с чужой посылкой в руках.
Все они правильные — человек не должен исчезать, бросив другого на полдороге.

Но одна дверь оказалась заперта снаружи. Посылка в статусе «курьер везёт обратно» отменяться
не может — она уже в возврате. А курьер мог просто пропасть: заболел, уехал на вахту, удалил
приложение. Проба: сорок дней в возврате, курьер молчит — отправитель не может ни отменить
заявку, ни удалить аккаунт. Единственное, что говорит приложение: «дождись доставки».

Волной 162 я сам расширил дорогу в этот статус: отмена доставки в пути стала переводить
её в «везу обратно». Значит и вероятность застрять выросла — это моя правка, и ловить её
надо было сразу.

Теперь дело, которое давно не двигалось (две недели — доставка «по пути» живёт дни), перестаёт
запирать человека. Живые дела держат как раньше: там вторая сторона ждёт и рассчитывает.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import (InstantOrder, InstantOrderStatus,
                        ParcelDelivery, UserRole)
from app.timeutil import utcnow

from test_api import _ride


def _посылка(user_factory, метка: str, статус: str, дней_назад: int):
    отправитель = user_factory(метка + "Отправитель")
    курьер = user_factory(метка + "Курьер", role=UserRole.driver)
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=отправитель["id"], courier_id=курьер["id"],
                           status=статус, delivery_type="courier",
                           from_city="Акъяр", to_city="Сибай",
                           accepted_at=utcnow() - timedelta(days=дней_назад))
        s.add(p)
        s.commit()
    return отправитель, курьер


def test_заброшенный_возврат_не_запирает_человека(client, user_factory):
    """Главное: право уйти не может зависеть от того, нажмёт ли кто-то другой кнопку."""
    отправитель, _ = _посылка(user_factory, "Заперт", "returning",
                              settings.account_delete_stale_days + 26)

    отмена = client.post("/parcels/1/cancel", headers=отправитель["auth"])
    удаление = client.post("/me/delete", headers=отправитель["auth"])

    assert удаление.status_code == 200, (
        f"посылка давно в возврате, курьер молчит — и человек не может уйти из сервиса "
        f"(ответ {удаление.status_code}, отмена дала {отмена.status_code}). Право забрать "
        "свои данные оказалось в руках у другого человека"
    )


def test_живая_доставка_по_прежнему_держит(client, user_factory):
    """Обратная сторона: вторая сторона ждёт коробку прямо сейчас — исчезать нельзя."""
    отправитель, _ = _посылка(user_factory, "Живая", "in_transit", 1)

    удаление = client.post("/me/delete", headers=отправитель["auth"])

    assert удаление.status_code == 409, (
        "человек ушёл, бросив курьера с посылкой в дороге: заявка исчезнет вместе с ним, "
        "и коробка останется ничьей"
    )
    assert "ba" in удаление.text, "отказ не на двух языках"


def test_курьер_с_заброшенной_посылкой_тоже_может_уйти(client, user_factory):
    """Симметрия: заперт был не только отправитель."""
    _, курьер = _посылка(user_factory, "КурьерЗаперт", "returning",
                         settings.account_delete_stale_days + 26)

    удаление = client.post("/me/delete", headers=курьер["auth"])

    assert удаление.status_code == 200, (
        f"курьер держит коробку сорок дней, отправитель молчит — и он тоже заперт "
        f"(ответ {удаление.status_code})"
    )


def test_заброшенный_такси_заказ_не_запирает(client, user_factory):
    """Та же болезнь у такси: заказ, который никто не закрыл, висел бы вечно."""
    пассажир = user_factory("ЗабытыйЗаказПассажир")
    водитель = user_factory("ЗабытыйЗаказВодитель", role=UserRole.driver)
    with Session(engine) as s:
        s.add(InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                           status=InstantOrderStatus.accepted, price_estimate=300,
                           accepted_at=utcnow() - timedelta(days=30),
                           created_at=utcnow() - timedelta(days=30),
                           from_lat=52.5, from_lng=58.3, to_lat=52.9, to_lng=58.6))
        s.commit()

    удаление = client.post("/me/delete", headers=пассажир["auth"])

    assert удаление.status_code == 200, (
        f"заказ месячной давности никто не закрыл, и человек заперт навсегда "
        f"(ответ {удаление.status_code})"
    )


def test_свежий_такси_заказ_держит(client, user_factory):
    """Обратная сторона: водитель уже едет за человеком — исчезать нельзя."""
    пассажир = user_factory("СвежийЗаказПассажир")
    водитель = user_factory("СвежийЗаказВодитель", role=UserRole.driver)
    with Session(engine) as s:
        s.add(InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                           status=InstantOrderStatus.accepted, price_estimate=300,
                           accepted_at=utcnow(), created_at=utcnow(),
                           from_lat=52.5, from_lng=58.3, to_lat=52.9, to_lng=58.6))
        s.commit()

    удаление = client.post("/me/delete", headers=пассажир["auth"])

    assert удаление.status_code == 409, (
        "пассажир исчез, пока водитель за ним ехал — заказ пропал у того прямо в дороге"
    )


def test_долг_держит_независимо_от_срока(client, user_factory):
    """Контроль: срок давности — про чужое бездействие, а не про свои деньги.

    Долг не «застревает» по вине второй стороны: его платит сам человек, когда захочет.
    Заброшенным он не становится никогда.
    """
    from app.models import CommissionDebt, DebtStatus
    водитель = user_factory("ДолжникУходящий", role=UserRole.driver)
    пассажир = user_factory("ДолжникПассажир")
    with Session(engine) as s:
        заказ = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                             status=InstantOrderStatus.done, price_estimate=500,
                             price_final=500, done_at=utcnow() - timedelta(days=200),
                             paid=True, payment_method="cash")
        s.add(заказ)
        s.commit()
        s.refresh(заказ)
        s.add(CommissionDebt(driver_id=водитель["id"], order_id=заказ.id, week="2026-W10",
                             amount_kop=5000, status=DebtStatus.unpaid,
                             created_at=utcnow() - timedelta(days=200),
                             due_at=utcnow() - timedelta(days=190)))
        s.commit()

    удаление = client.post("/me/delete", headers=водитель["auth"])

    assert удаление.status_code == 409, (
        "водитель ушёл с неоплаченной комиссией двухсотдневной давности: долг исчез вместе с ним"
    )


def test_живая_бронь_попутки_держит(client, user_factory):
    """Контроль: договорённость с попутчиком — тоже чужое ожидание."""
    водитель = user_factory("БроньВодитель174", role=UserRole.driver)
    пассажир = user_factory("БроньПассажир174")
    ride_id = _ride(client, водитель, comment="завтра в Уфу")
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])

    удаление = client.post("/me/delete", headers=пассажир["auth"])

    assert удаление.status_code == 409, (
        "пассажир исчез с подтверждённой бронью: водитель приедет к назначенному месту зря"
    )
