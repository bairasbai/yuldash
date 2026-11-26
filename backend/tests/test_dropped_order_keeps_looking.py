# -*- coding: utf-8 -*-
"""Брошенный водителем заказ ищет машину дальше, а не умирает (2026-08-29).

БЫЛО. Водитель принимал заказ и отменял — заказ уходил в `cancelled` навсегда. Пассажиру
при этом приходил бодрый пуш «Водитель отменил заказ. Ищем другого?», а экран приложения
отвечал прямо противоположное: «Попробуй заказать снова». Никто ничего не искал. Женщина
с ребёнком у подъезда в мороз вбивала адреса заново, теряя и зафиксированную цену, и очередь.
Обещание в интерфейсе и поведение кода разъехались — та же болезнь, что уже в `lessons.md`.

СТАЛО. Заказ сам возвращается в поиск: те же адреса, тот же класс, та же цена, тот же
промокод. Бросивший попадает в «уже предлагали», чтобы круг не вернул заказ ему же.

ГЛАВНАЯ ЛОВУШКА, которую здесь стерегут. Наказание за брошенные заказы (пауза офферов)
читалось прямо с заказа: `status == cancelled AND cancel_by == 'driver'`. У переназначенного
заказа эти поля перезаписывает СЛЕДУЮЩИЙ водитель — почини мы переназначение наивно, и
страйки исчезли бы: бросай сколько хочешь. Поэтому поступок пишется отдельным событием
(`DriverCancel`), и тест ниже это стережёт.

Три границы переназначения — каждая про человека, а не про технику:
  • из `onboard` не возвращаем: пассажир уже в машине, «тот же заказ» от старой точки А — ложь;
  • `no_show` не возвращаем: пассажира на месте нет, следующий приедет к пустому подъезду;
  • не больше `taxi_reassign_limit` кругов: заказ, который перекидывают, честнее закрыть.
"""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app import instant_service as isv, taxi_worker as tw
from app.config import settings
from app.db import engine
from app.models import DriverCancel, InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _taxi_on():
    prev = settings.taxi_enabled
    settings.taxi_enabled = True
    yield
    settings.taxi_enabled = prev


from test_money_rules import (  # noqa: E402,F401 — общие помощники живого прогона
    fake_redis, _db_order, _driver_online, _heartbeat, _order_body, _shift,
)


def _accepted(client, user_factory, dname, pname, **extra):
    """Заказ принят живым водителем: полный путь, без посева в обход матчера."""
    d = _driver_online(client, user_factory, dname)
    _heartbeat(client, d)
    pax = user_factory(pname)
    order = client.post("/instant/orders", headers=pax["auth"],
                        json=_order_body(**extra)).json()
    assert order["status"] == "offered", order
    r = client.post(f"/instant/orders/{order['id']}/accept", headers=d["auth"])
    assert r.status_code == 200, r.text
    return d, pax, order["id"]


def _отменил_водитель(client, d, oid, reason=""):
    r = client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"],
                    json={"reason": reason} if reason else {})
    assert r.status_code == 200, r.text
    return r.json()


def _события(driver_id: int) -> list:
    with Session(engine) as s:
        return list(s.exec(select(DriverCancel).where(DriverCancel.driver_id == driver_id)).all())


# ==================== 1. Заказ не умирает ====================
def test_a_dropped_order_goes_to_the_next_driver(client, user_factory, fake_redis):
    """Водитель бросил принятый заказ — заказ уходит следующему, а не закрывается.

    Второй водитель на линии здесь обязателен: это и есть проверяемый сценарий. Без него
    тест доказывал бы только «заказ не похоронили», но не «человек получил машину».
    """
    d, pax, oid = _accepted(client, user_factory, "БросилВодитель", "ЖдётПассажир")
    сосед = _driver_online(client, user_factory, "СоседнийВодитель")
    _heartbeat(client, сосед)

    assert _отменил_водитель(client, d, oid) == {
        "id": oid, "released": True, "status": "cancelled", "role": "driver", "cancel_by": "driver",
    }

    o = _db_order(oid)
    assert o.status != S.cancelled, "заказ похоронили — человек начинает всё заново"
    assert o.status == S.offered, f"заказ не предложили следующему: {o.status}"
    assert o.current_offer_driver_id == сосед["id"], "предложили не тому, кто рядом"
    assert o.driver_id is None, "бросивший всё ещё числится водителем заказа"
    assert o.reassigns == 1

    passenger_view = client.get(f"/instant/orders/{oid}", headers=pax["auth"])
    assert passenger_view.status_code == 200
    assert passenger_view.json()["id"] == oid
    assert passenger_view.json()["status"] in {"searching", "offered"}


def test_reassignment_has_no_committed_cancelled_snapshot(client, user_factory, fake_redis):
    """Чтение из отдельной сессии на каждой фиксации перехода."""
    driver, _, oid = _accepted(client, user_factory, "АтомВодитель", "АтомПассажир")
    replacement = _driver_online(client, user_factory, "АтомЗапасной")
    _heartbeat(client, replacement)
    seen = []

    def record(_):
        with Session(engine) as reader:
            order = reader.get(InstantOrder, oid)
            strike = reader.exec(select(DriverCancel).where(DriverCancel.order_id == oid)).first()
            seen.append((order.status, order.driver_id, strike is not None))

    event.listen(Session, "after_commit", record)
    try:
        assert _отменил_водитель(client, driver, oid) == {
            "id": oid, "released": True, "status": "cancelled", "role": "driver", "cancel_by": "driver",
        }
    finally:
        event.remove(Session, "after_commit", record)

    assert seen[0] == (S.searching, None, True)
    assert all(status != S.cancelled and assigned is None and strike for status, assigned, strike in seen)
    assert _db_order(oid).current_offer_driver_id == replacement["id"]


def test_failed_strike_does_not_publish_reassignment(client, user_factory, fake_redis):
    driver, _, oid = _accepted(client, user_factory, "ОшибкаСобытияВод", "ОшибкаСобытияПасс")

    def fail_strike(session, *_):
        if any(isinstance(row, DriverCancel) for row in session.new):
            raise RuntimeError("synthetic strike storage failure")

    with Session(engine) as writer:
        event.listen(writer, "before_flush", fail_strike)
        with pytest.raises(RuntimeError, match="synthetic strike storage failure"):
            isv.cancel_order(writer, oid, isv.Actor.driver, driver["id"])
        writer.rollback()

    order = _db_order(oid)
    assert order.status == S.accepted and order.driver_id == driver["id"] and order.reassigns == 0
    assert not _события(driver["id"])


@pytest.mark.parametrize("candidate_available", [True, False])
def test_passenger_can_cancel_while_replacement_is_selected(
    client, user_factory, fake_redis, monkeypatch, candidate_available,
):
    driver, passenger, oid = _accepted(client, user_factory, "УшёлВодитель", "СамОтменилПассажир")
    replacement = _driver_online(client, user_factory, "ПозднийВодитель")
    _heartbeat(client, replacement)
    effects = []

    def select_candidate(*args, **kwargs):
        with Session(engine) as writer:
            cancelled = isv.cancel_order(writer, oid, isv.Actor.passenger, passenger["id"])
            assert cancelled.status == S.cancelled
        return [replacement["id"]] if candidate_available else []

    monkeypatch.setattr(isv, "candidates", select_candidate)
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("expiry"))

    assert _отменил_водитель(client, driver, oid) == {
        "id": oid, "released": True, "status": "cancelled", "role": "driver", "cancel_by": "driver",
    }

    order = _db_order(oid)
    assert order.status == S.cancelled and order.cancel_by == "passenger"
    assert order.current_offer_driver_id is None and order.reassigns == 1
    assert effects == []


@pytest.mark.parametrize("candidate_available", [True, False])
def test_previous_search_does_not_finish_a_new_search(
    client, user_factory, fake_redis, monkeypatch, candidate_available,
):
    driver, _, oid = _accepted(client, user_factory, "ВодительСтарогоПоиска", "ПассажирНовогоПоиска")
    replacement = _driver_online(client, user_factory, "КандидатСтарогоПоиска")
    _heartbeat(client, replacement)

    def restart_search(*args, **kwargs):
        with Session(engine) as writer:
            current = writer.get(InstantOrder, oid)
            current.searching_at += timedelta(seconds=1)
            writer.add(current)
            writer.commit()
        return [replacement["id"]] if candidate_available else []

    monkeypatch.setattr(isv, "candidates", restart_search)

    assert _отменил_водитель(client, driver, oid) == {
        "id": oid, "released": True, "status": "cancelled", "role": "driver", "cancel_by": "driver",
    }
    current = _db_order(oid)
    assert current.status == S.searching and current.current_offer_driver_id is None


@pytest.mark.parametrize("outcome", ["offer", "expiry", "passenger_cancel"])
def test_worker_recovers_interrupted_driver_reassignment(
    client, user_factory, fake_redis, monkeypatch, outcome,
):
    driver, passenger, oid = _accepted(client, user_factory, "УшёлПередСбоем", "ОсталсяПриСбое")
    if outcome == "offer":
        replacement = _driver_online(client, user_factory, "ЗаменаПослеСбоя")
        _heartbeat(client, replacement)

    def stop_after_commit(*args):
        raise RuntimeError("synthetic interruption after commit")

    effects = []
    monkeypatch.setattr(isv, "_continue_after_driver_cancel", stop_after_commit)
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("expiry"))

    with Session(engine) as original:
        with pytest.raises(RuntimeError, match="synthetic interruption"):
            isv.cancel_order(original, oid, isv.Actor.driver, driver["id"])
    assert _db_order(oid).status == S.searching and len(_события(driver["id"])) == 1
    if outcome == "passenger_cancel":
        with Session(engine) as later:
            isv.cancel_order(later, oid, isv.Actor.passenger, passenger["id"])

    with Session(engine) as worker:
        dry = tw.resume_driver_reassignments(worker, dry_run=True)
    assert (oid in dry) == (outcome != "passenger_cancel")
    assert effects == []
    with Session(engine) as worker:
        completed = tw.resume_driver_reassignments(worker)
    with Session(engine) as repeated_worker:
        assert oid not in tw.resume_driver_reassignments(repeated_worker)

    current = _db_order(oid)
    if outcome == "offer":
        assert current.status == S.offered and current.current_offer_driver_id == replacement["id"]
        assert oid in completed and effects == ["offer"]
    elif outcome == "expiry":
        assert current.status == S.expired and oid in completed and effects == ["expiry"]
    else:
        assert current.status == S.cancelled and current.cancel_by == "passenger"
        assert oid not in completed and effects == []
    assert len(_события(driver["id"])) == 1


def test_full_worker_recovers_fresh_reassignment_but_closes_expired_one(
    client, user_factory, fake_redis, monkeypatch,
):
    old_driver, _, old_id = _accepted(client, user_factory, "ЗависшийСтарыйВод", "ЗависшийСтарыйПасс")
    fresh_driver, _, fresh_id = _accepted(client, user_factory, "СвежийВод", "СвежийПасс")
    replacement = _driver_online(client, user_factory, "ГотоваяЗамена")
    _heartbeat(client, replacement)

    def stop_after_commit(*args):
        raise RuntimeError("synthetic interruption")

    monkeypatch.setattr(isv, "_continue_after_driver_cancel", stop_after_commit)
    for driver, oid in ((old_driver, old_id), (fresh_driver, fresh_id)):
        with Session(engine) as incoming:
            with pytest.raises(RuntimeError, match="synthetic interruption"):
                isv.cancel_order(incoming, oid, isv.Actor.driver, driver["id"])

    worker_time = utcnow() + timedelta(seconds=1)
    cutoff = worker_time - timedelta(hours=settings.order_stuck_hours)
    with Session(engine) as writer:
        old = writer.get(InstantOrder, old_id)
        old.created_at = old.offered_at = old.searching_at = cutoff
        strike = writer.exec(select(DriverCancel).where(DriverCancel.order_id == old_id)).one()
        strike.at = cutoff
        writer.add(old)
        writer.add(strike)
        writer.commit()

    sent = []
    monkeypatch.setattr(tw, "utcnow", lambda: worker_time)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [replacement["id"]])
    monkeypatch.setattr(isv, "_push_offer", lambda session, order, driver_id: sent.append((order.id, driver_id)))
    monkeypatch.setattr(tw, "push_notification", lambda *args, **kwargs: None)
    monkeypatch.setattr(isv, "_notify_order_shares", lambda *args, **kwargs: None)

    with Session(engine) as worker:
        preview = tw.run_once(worker, dry_run=True)
    assert old_id in preview["stuck_closed"] and old_id not in preview["reassignments_resumed"]
    assert fresh_id in preview["reassignments_resumed"]
    assert sent == []

    with Session(engine) as worker:
        result = tw.run_once(worker)
    assert old_id in result["stuck_closed"] and old_id not in result["reassignments_resumed"]
    old = _db_order(old_id)
    assert old.status == S.expired and old.cancel_reason == "stuck_timeout"
    assert fresh_id in result["reassignments_resumed"]
    assert _db_order(fresh_id).current_offer_driver_id == replacement["id"]
    assert sent == [(fresh_id, replacement["id"])]


def test_with_nobody_left_the_order_waits_instead_of_dying(client, user_factory, fake_redis):
    """Свободных машин больше нет — заказ уходит в ожидание, а не в «отменён».

    Разница для человека огромная: «ищем дальше» оставляет ему очередь и цену, «отменён»
    отправляет вбивать адреса заново. Дальше работает обычная очередь «подожду машину».
    """
    d, pax, oid = _accepted(client, user_factory, "ЕдинственныйВод", "ОдинокийПассажир")
    _отменил_водитель(client, d, oid)

    o = _db_order(oid)
    assert o.status != S.cancelled, "единственная машина отменила — и заказ похоронили"
    assert o.status in (S.searching, S.offered, S.expired), o.status
    assert o.reassigns == 1


def test_the_passenger_keeps_his_price_and_his_route(client, user_factory, fake_redis):
    """Возвращаем ТОТ ЖЕ заказ: адреса, класс и цена не меняются на ровном месте."""
    d, pax, oid = _accepted(client, user_factory, "БросилЦена", "ЦенаПассажир")
    было = _db_order(oid)
    цена, откуда, куда, класс = (было.price_estimate, было.from_lat, было.to_lat, было.category)

    _отменил_водитель(client, d, oid)

    стало = _db_order(oid)
    assert стало.price_estimate == цена, "цена уехала, хотя заказ тот же"
    assert (стало.from_lat, стало.to_lat) == (откуда, куда)
    assert стало.category == класс


def test_the_one_who_dropped_it_is_not_offered_it_again(client, user_factory, fake_redis):
    """Бросивший не получает этот же заказ обратно через минуту.

    Круг подбора начинается заново, и без пометки «этому уже предлагали» заказ вернулся бы
    ровно тому, кто от него только что отказался, — а пассажир увидел бы ту же машину.
    """
    d, pax, oid = _accepted(client, user_factory, "БросилИЖдёт", "ОпятьПассажир")
    сосед = _driver_online(client, user_factory, "ЗапаснойВодитель")
    _heartbeat(client, сосед)

    _отменил_водитель(client, d, oid)

    o = _db_order(oid)
    assert o.current_offer_driver_id != d["id"], "заказ вернулся тому, кто его бросил"
    assert int(d["id"]) in isv._tried_set(isv._redis(), oid)


# ==================== 2. Наказание не потерялось ====================
def test_the_strike_survives_the_reassignment(client, user_factory, fake_redis):
    """Главное: заказ ушёл дальше, а поступок водителя записан.

    Наивная починка стёрла бы след — `driver_id` и `cancelled_at` на заказе перезапишет
    следующий водитель, — и бросать заказы стало бы бесплатно.
    """
    d, pax, oid = _accepted(client, user_factory, "СтрайкВодитель", "СтрайкПассажир")
    _отменил_водитель(client, d, oid)

    события = _события(d["id"])
    assert len(события) == 1, "поступок не записан — наказание потерялось"
    assert события[0].order_id == oid and события[0].no_show is False

    with Session(engine) as s:
        strikes = isv.driver_cancel_times(s, d["id"], utcnow() - timedelta(days=7))
    assert len(strikes) == 1, "счётчик страйков не видит брошенный заказ"


def test_repeated_drops_still_pause_the_driver(client, user_factory, fake_redis):
    """Несколько брошенных заказов подряд по-прежнему ставят офферы на паузу."""
    d = _driver_online(client, user_factory, "ПаузаПослеБросков")
    _heartbeat(client, d)
    for i in range(int(settings.driver_cancel_limit)):
        pax = user_factory(f"ПаузаПас{i}")
        order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
        if order.get("status") != "offered":
            continue
        client.post(f"/instant/orders/{order['id']}/accept", headers=d["auth"])
        client.post(f"/instant/orders/{order['id']}/cancel", headers=d["auth"], json={})

    with Session(engine) as s:
        assert isv.driver_pause_until(s, d["id"]) is not None, (
            "водитель бросил лимит заказов и не получил паузу"
        )


# ==================== 3. Границы ====================
def test_a_passenger_already_in_the_car_is_not_re_searched(client, user_factory, fake_redis):
    """Из «пассажир в машине» заказ не возвращаем: половина дороги позади.

    Такое высаживание посреди пути — отдельный разговор со своей ценой, а не работа матчера:
    «тот же заказ» от старой точки А был бы неправдой.
    """
    d, pax, oid = _accepted(client, user_factory, "ВысадилВодитель", "ВысаженПассажир")
    for path in ("arrived", "onboard"):
        assert client.post(f"/instant/orders/{oid}/{path}", headers=d["auth"]).status_code == 200

    _отменил_водитель(client, d, oid)
    o = _db_order(oid)
    assert o.status == S.cancelled, "заказ ушёл в поиск, хотя пассажир был в машине"
    assert o.reassigns == 0


def test_no_show_does_not_send_anyone_to_an_empty_porch(client, user_factory, fake_redis):
    """«Пассажир не вышел» — не переназначаем: следующий приехал бы к пустому подъезду."""
    d, pax, oid = _accepted(client, user_factory, "НеВышелВодитель", "НеВышелПассажир")
    assert client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"]).status_code == 200
    # Отматываем ожидание назад, чтобы «пассажир не вышел» стал доступен по таймингу.
    _shift(oid, waiting_started_at=utcnow() - timedelta(minutes=30),
           arriving_at=utcnow() - timedelta(minutes=30))

    r = client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"],
                    json={"reason": isv.NO_SHOW_REASON})
    assert r.status_code == 200, r.text
    result = r.json()
    assert result["id"] == oid and result["status"] == "cancelled"
    assert result["role"] == "driver" and result["no_show"] is True
    assert result["cancel_fee_kop"] > 0 and "released" not in result


    o = _db_order(oid)
    assert o.status == S.cancelled and o.no_show is True
    assert o.reassigns == 0, "поехали искать машину человеку, которого нет на месте"
    # И это по-прежнему НЕ страйк водителю: он всё сделал правильно.
    with Session(engine) as s:
        assert isv.driver_cancel_times(s, d["id"], utcnow() - timedelta(days=7)) == []


@pytest.mark.parametrize("first_reason,second_reason", [
    ("driver_cancel", isv.NO_SHOW_REASON),
    (isv.NO_SHOW_REASON, "driver_cancel"),
])
def test_second_cancel_reason_cannot_change_the_already_committed_driver_decision(
    client, user_factory, fake_redis, first_reason, second_reason,
):
    old, pax, oid = _accepted(client, user_factory, "FirstCancelDriver", "CancelChoicePassenger")
    replacement = _driver_online(client, user_factory, "ReplacementCancelDriver")
    _heartbeat(client, replacement)
    assert client.post(f"/instant/orders/{oid}/arrived", headers=old["auth"]).status_code == 200
    _shift(oid, waiting_started_at=utcnow() - timedelta(minutes=30),
           arriving_at=utcnow() - timedelta(minutes=30))
    first = _отменил_водитель(client, old, oid, first_reason)

    if first_reason == "driver_cancel":
        assert first["released"] is True
        accepted = client.post(f"/instant/orders/{oid}/accept", headers=replacement["auth"])
        assert accepted.status_code == 200, accepted.text
    else:
        assert first["no_show"] is True and first["cancel_fee_kop"] > 0
    before = _db_order(oid)

    # Model a second tab's different click after the first request committed. This
    # is not a network/genuine concurrency test; it checks the persisted effects.
    other = client.post(f"/instant/orders/{oid}/cancel", headers=old["auth"],
                        json={"reason": second_reason})
    after = _db_order(oid)
    events = _события(old["id"])
    assert len(events) == 1 and events[0].order_id == oid and events[0].reason == first_reason
    assert after.status == before.status and after.driver_id == before.driver_id
    assert after.cancel_fee_kop == before.cancel_fee_kop and after.no_show == before.no_show
    assert after.reassigns == before.reassigns
    if first_reason == "driver_cancel":
        assert other.status_code == 403
        assert after.driver_id == replacement["id"] and after.cancel_fee_kop == 0
    else:
        assert other.status_code == 200, other.text
        assert other.json()["no_show"] is True
        assert other.json()["cancel_fee_kop"] == first["cancel_fee_kop"]


def test_an_order_does_not_bounce_forever(client, user_factory, fake_redis):
    """Круги переназначения кончаются: заказ честнее закрыть, чем держать в поиске час."""
    d, pax, oid = _accepted(client, user_factory, "КругПервый", "КругПассажир")
    _shift(oid, reassigns=int(settings.taxi_reassign_limit))

    _отменил_водитель(client, d, oid)
    o = _db_order(oid)
    assert o.status == S.cancelled, "заказ скачет по кругу сверх предела"
    assert o.reassigns == int(settings.taxi_reassign_limit)


def test_a_passenger_cancel_is_still_just_a_cancel(client, user_factory, fake_redis):
    """Пассажир отменил сам — никакого поиска, заказ закрыт. Не сломали обычный путь."""
    d, pax, oid = _accepted(client, user_factory, "ПассОтменилВод", "ПассОтменилСам")
    r = client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"], json={})
    assert r.status_code == 200, r.text

    o = _db_order(oid)
    assert o.status == S.cancelled and o.reassigns == 0
    assert _события(d["id"]) == [], "пассажирскую отмену записали как поступок водителя"
