"""Отмена оставляла курьера с чужой коробкой и без единого пути в приложении (волна 162).

Курьер забрал посылку и выехал. Отправитель передумал и нажал «Отменить» — он имеет на это
полное право: планы меняются, человек за это не наказан. Заказ уходил в «отменён», и на этом
приложение считало дело закрытым.

А коробка в этот момент физически едет в машине. Курьеру надо её кому-то отдать — и ни одна
кнопка ему этого не даёт: возврат отвечает «возврат доступен, пока посылка у тебя» (она у него
и есть, просто статус уже другой), а единственный оставшийся путь — открыть спор. То есть
чтобы вернуть человеку его же вещь, надо завести против него конфликт с разбором у админа.

Теперь отмена в пути переводит доставку в «везу обратно»: дело остаётся живым, отправитель
знает, что коробка едет к нему, курьер закрывает её обычной кнопкой «вернул». Компенсация
за бензин фиксируется ровно так же, как раньше.

Отмена ДО того, как курьер забрал коробку, работает по-прежнему: там возвращать нечего.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import CourierApplication, CourierProfile, ParcelDelivery, UserRole
from app.timeutil import utcnow


@pytest.fixture
def доставка_включена(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True)


def _курьер(user_factory, метка: str):
    человек = user_factory(метка, role=UserRole.driver)
    with Session(engine) as s:
        заявка = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == человек["id"])).first()
        if заявка is None:
            заявка = CourierApplication(user_id=человек["id"])
        заявка.status = "approved"
        заявка.reviewed_at = utcnow()
        s.add(заявка)
        if s.exec(select(CourierProfile).where(
                CourierProfile.user_id == человек["id"])).first() is None:
            s.add(CourierProfile(user_id=человек["id"]))
        s.commit()
    return человек


def _доставка(user_factory, метка: str, статус: str):
    курьер = _курьер(user_factory, метка + "Курьер")
    отправитель = user_factory(метка + "Отправитель")
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=отправитель["id"], courier_id=курьер["id"],
                           status=статус, delivery_type="courier",
                           from_city="Акъяр", to_city="Сибай", accepted_at=utcnow())
        s.add(p)
        s.commit()
        s.refresh(p)
        pid = p.id
    return курьер, отправитель, pid


def test_отменённая_в_пути_едет_обратно(client, user_factory, доставка_включена):
    """Главное: у коробки, которая уже в машине, должна остаться дорога домой."""
    курьер, отправитель, pid = _доставка(user_factory, "ВПути", "in_transit")

    отмена = client.post(f"/parcels/{pid}/cancel", headers=отправитель["auth"])

    assert отмена.status_code == 200, f"отмена не прошла: {отмена.text[:150]}"
    with Session(engine) as s:
        посылка = s.get(ParcelDelivery, pid)
    assert посылка.status == "returning", (
        f"заказ закрыт как «{посылка.status}», а коробка едет в машине курьера: отдать её "
        "обратно нечем, кроме спора против человека, который просто передумал"
    )


def test_курьер_закрывает_возврат_обычной_кнопкой(client, user_factory, доставка_включена):
    """Дорога домой должна доходить до конца, а не упираться в следующий отказ."""
    курьер, отправитель, pid = _доставка(user_factory, "Домой", "in_transit")
    client.post(f"/parcels/{pid}/cancel", headers=отправитель["auth"])

    вернул = client.post(f"/parcels/{pid}/return-done", headers=курьер["auth"])

    assert вернул.status_code == 200, (
        f"курьер привёз коробку назад, а закрыть дело не может: {вернул.text[:150]}"
    )
    with Session(engine) as s:
        посылка = s.get(ParcelDelivery, pid)
    assert посылка.status == "returned"
    assert посылка.commission_kop == 0, "за несостоявшуюся доставку комиссию брать не за что"


def test_компенсация_за_бензин_осталась(client, user_factory, доставка_включена):
    """Курьер проехал сорок километров — это не должно пропасть из-за смены статуса."""
    курьер, отправитель, pid = _доставка(user_factory, "Бензин", "in_transit")

    client.post(f"/parcels/{pid}/cancel", headers=отправитель["auth"])

    with Session(engine) as s:
        посылка = s.get(ParcelDelivery, pid)
    assert посылка.cancel_fee_kop == settings.courier_cancel_fee_kop, (
        f"компенсация за отмену в пути потерялась: {посылка.cancel_fee_kop} коп"
    )


def test_отмена_до_получения_коробки_просто_закрывает(client, user_factory, доставка_включена):
    """Обратная сторона: курьер ещё не забрал посылку — возвращать нечего."""
    курьер, отправитель, pid = _доставка(user_factory, "ДоЗабора", "accepted")

    client.post(f"/parcels/{pid}/cancel", headers=отправитель["auth"])

    with Session(engine) as s:
        посылка = s.get(ParcelDelivery, pid)
    assert посылка.status == "canceled", (
        f"заказ ушёл в «{посылка.status}» вместо отмены: курьер коробку даже не забирал, "
        "а его отправили везти её обратно"
    )


def test_отмена_без_курьера_закрывает_заявку(client, user_factory, доставка_включена):
    """Обратная сторона: заявку, которую никто не взял, отменяют без всяких возвратов."""
    отправитель = user_factory("СамОтменил")
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=отправитель["id"], status="created",
                           delivery_type="courier", from_city="Акъяр", to_city="Сибай")
        s.add(p)
        s.commit()
        s.refresh(p)
        pid = p.id

    client.post(f"/parcels/{pid}/cancel", headers=отправитель["auth"])

    with Session(engine) as s:
        посылка = s.get(ParcelDelivery, pid)
    assert посылка.status == "canceled"
    assert посылка.cancel_fee_kop == 0, "компенсацию начислили там, где никто никуда не ехал"
