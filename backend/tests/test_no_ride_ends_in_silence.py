# -*- coding: utf-8 -*-
"""Из поездки всегда есть выход (аудит сценариев 2026-08-30, вторая часть нашей доли).

Три двери, которых не было. Все три — про одно: человек сделал шаг, а дальше тишина.

1. «В пути» без конца. Закрыть поездку может только водитель, и когда он этого не делает
   (сел телефон, отвлёкся, удалил приложение), пассажир заперт: ни оценить, ни заказать
   новую машину. Ночная уборка приберёт заказ через 12 часов — это не выход, а срок.

2. Смена адреса ждала слова водителя, и пассажир об этом не знал: нажал «Спросить
   водителя», шторка закрылась — и всё. Ни ожидания на экране, ни возможности передумать.

3. Оценка жила ТОЛЬКО на свежем финальном экране. Закрыл его — и оценить поездку было
   негде, хотя окно оценки открыто 60 дней.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, Rating, User
from app.timeutil import utcnow


def _сессия() -> Session:
    return Session(engine, expire_on_commit=False)


def _поездка(session: Session, пассажир: int, водитель: int, *, минут_назад: int,
             eta: float = 10.0) -> InstantOrder:
    """Заказ в статусе «в пути», посаженный N минут назад."""
    o = InstantOrder(
        passenger_id=пассажир, driver_id=водитель, status=S.onboard,
        from_lat=54.7, from_lng=55.9, to_lat=54.8, to_lng=56.0,
        from_text="Уфа", to_text="Дёма", price_estimate=300, eta_min=eta,
        onboard_at=utcnow() - timedelta(minutes=минут_назад),
    )
    session.add(o)
    session.commit()
    session.refresh(o)
    return o


# ==================== 1. «В пути» без конца ====================
def test_the_passenger_cannot_close_a_ride_that_is_still_going(client, user_factory):
    """Посреди дороги кнопки нет: расчётное время ещё не вышло."""
    п = user_factory("ЕдетСейчас")
    в = user_factory("ВезётСейчас")
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=5, eta=30.0)
        assert isv.passenger_may_close(o) is False
    r = client.post(f"/instant/orders/{o.id}/passenger-done", headers=п["auth"])
    assert r.status_code == 409, r.text


def test_the_passenger_can_close_a_ride_the_driver_forgot(client, user_factory):
    """Едем сильно дольше расчётного — пассажир закрывает поездку сам."""
    п = user_factory("ДоехалДавно")
    в = user_factory("ЗабылЗавершить")
    порог = settings.taxi_passenger_close_slack_min
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=порог + 20, eta=10.0)
        assert isv.passenger_may_close(o) is True

    r = client.post(f"/instant/orders/{o.id}/passenger-done", headers=п["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "done"

    with _сессия() as s:
        свежий = s.get(InstantOrder, o.id)
        assert свежий.status == S.done
        assert свежий.done_at is not None, "поездка без done_at выпадет из заработка водителя"
        assert свежий.price_final is not None, "цену обязаны зафиксировать, как при обычном завершении"


def test_closing_twice_is_not_an_error(client, user_factory):
    """Двойной тап по слабой связи — не ошибка: поездка уже закрыта, значит цель достигнута."""
    п = user_factory("ЖмётДважды")
    в = user_factory("ВодительДважды")
    порог = settings.taxi_passenger_close_slack_min
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=порог + 20, eta=5.0)
    заголовки = п["auth"]
    assert client.post(f"/instant/orders/{o.id}/passenger-done", headers=заголовки).status_code == 200
    второй = client.post(f"/instant/orders/{o.id}/passenger-done", headers=заголовки)
    assert второй.status_code == 200, второй.text
    assert второй.json()["status"] == "done"


def test_a_stranger_cannot_close_someone_elses_ride(client, user_factory):
    """Закрыть чужую поездку нельзя — это не только чужие деньги, но и чужая комиссия."""
    п = user_factory("СвойПассажир")
    в = user_factory("СвойВодитель")
    чужой = user_factory("Посторонний")
    порог = settings.taxi_passenger_close_slack_min
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=порог + 30, eta=5.0)
    r = client.post(f"/instant/orders/{o.id}/passenger-done", headers=чужой["auth"])
    assert r.status_code == 403, r.text


def test_a_long_ride_is_not_cut_short_by_a_flat_timer(client, user_factory):
    """Междугородняя поездка на четыре часа не закрывается через час.

    Порог считаем от расчётного времени, а не абсолютной цифрой: час для поездки по селу —
    это давно приехали, а для Уфа→Сибай — середина пути.
    """
    п = user_factory("ЕдетДалеко")
    в = user_factory("ВезётДалеко")
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=90, eta=240.0)
        assert isv.passenger_may_close(o) is False, "дальнюю поездку срезали на середине"


# ==================== 2. Смена адреса: ожидание видно и снимается ====================
def test_the_passenger_sees_that_the_question_is_pending(client, user_factory):
    """Витрина говорит, ЧТО спросили и КОГДА — иначе «ждём» через полчаса читается как сбой."""
    п = user_factory("СпросилАдрес")
    в = user_factory("ДумаетНадАдресом")
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=5, eta=30.0)
        isv.offer_destination_to_driver(s, o, (55.0, 56.5), "Сибай",
                                        {"price": 4200, "ask_reason": "zone"})
    r = client.get(f"/instant/orders/{o.id}", headers=п["auth"])
    assert r.status_code == 200, r.text
    блок = r.json()["pending_destination"]
    assert блок is not None
    assert блок["to_text"] == "Сибай"
    assert блок["price"] == 4200
    assert блок["asked_at"], "без времени вопроса счётчик ожидания нечем кормить"


def test_the_passenger_can_take_the_question_back(client, user_factory):
    """Передумал — вопрос снимается, поездка идёт по старому адресу и не рвётся."""
    п = user_factory("Передумал")
    в = user_factory("НеУспелОтветить")
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=5, eta=30.0)
        старый_адрес = o.to_text
        isv.offer_destination_to_driver(s, o, (55.0, 56.5), "Сибай",
                                        {"price": 4200, "ask_reason": "zone"})

    r = client.post(f"/instant/orders/{o.id}/destination/withdraw", headers=п["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["order"]["pending_destination"] is None

    with _сессия() as s:
        свежий = s.get(InstantOrder, o.id)
        assert свежий.status == S.onboard, "снятие вопроса не должно обрывать поездку"
        assert свежий.to_text == старый_адрес, "адрес обязан остаться прежним"
        assert свежий.pending_to_lat is None


def test_withdrawing_nothing_is_not_an_error(client, user_factory):
    """Водитель успел ответить, пока летел запрос. Цель достигнута — ожидания нет."""
    п = user_factory("ОпоздалОтозвать")
    в = user_factory("УспелОтветить")
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=5, eta=30.0)
    r = client.post(f"/instant/orders/{o.id}/destination/withdraw", headers=п["auth"])
    assert r.status_code == 200, r.text


def test_only_the_passenger_takes_back_the_question(client, user_factory):
    """Снять вопрос может тот, кто его задал: у водителя для этого свой «не могу»."""
    п = user_factory("ХозяинМаршрута")
    в = user_factory("ЗаРулём")
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=5, eta=30.0)
        isv.offer_destination_to_driver(s, o, (55.0, 56.5), "Сибай",
                                        {"price": 4200, "ask_reason": "zone"})
    r = client.post(f"/instant/orders/{o.id}/destination/withdraw", headers=в["auth"])
    assert r.status_code == 404, r.text


# ==================== 3. Оценить можно не только «прямо сейчас» ====================
def test_a_finished_ride_stays_rateable_from_history(client, user_factory):
    """Через неделю после поездки звёзды всё ещё доступны — окно открыто 60 дней."""
    п = user_factory("ВспомнилПозже")
    в = user_factory("ХорошийВодитель")
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=60, eta=10.0)
        o.status = S.done
        o.done_at = utcnow() - timedelta(days=7)
        s.add(o)
        s.commit()
    r = client.get(f"/instant/orders/{o.id}", headers=п["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["can_rate"] is True
    assert r.json()["my_stars"] == 0


def test_the_history_remembers_the_stars_you_gave(client, user_factory):
    """Уже поставленная оценка возвращается витриной: пустые звёзды читаются как «не сохранилось»."""
    п = user_factory("ОценилВчера")
    в = user_factory("ОценённыйВодитель")
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=60, eta=10.0)
        o.status = S.done
        o.done_at = utcnow() - timedelta(days=1)
        s.add(o)
        s.commit()

    оценка = client.post(f"/instant/orders/{o.id}/rate", headers=п["auth"], json={"stars": 5})
    assert оценка.status_code == 200, оценка.text

    r = client.get(f"/instant/orders/{o.id}", headers=п["auth"])
    assert r.json()["my_stars"] == 5
    assert r.json()["can_rate"] is True, "передумать и переставить оценку можно"


def test_an_ancient_ride_is_closed_for_rating(client, user_factory):
    """Поездка старше окна оценки кнопку не показывает — иначе тап упрётся в отказ сервера."""
    п = user_factory("ОченьДавноЕздил")
    в = user_factory("ЗабытыйВодитель")
    from app.rating_service import RATING_WINDOW_DAYS
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=60, eta=10.0)
        o.status = S.done
        o.done_at = utcnow() - timedelta(days=RATING_WINDOW_DAYS + 1)
        s.add(o)
        s.commit()
    r = client.get(f"/instant/orders/{o.id}", headers=п["auth"])
    assert r.json()["can_rate"] is False


def test_an_unfinished_ride_has_no_stars_yet(client, user_factory):
    """Оценивать нечего, пока едем: кнопка появится, когда поездка закроется."""
    п = user_factory("ЕщёВПути")
    в = user_factory("ЕщёВезёт")
    with _сессия() as s:
        o = _поездка(s, п["id"], в["id"], минут_назад=5, eta=30.0)
    r = client.get(f"/instant/orders/{o.id}", headers=п["auth"])
    assert r.json()["can_rate"] is False
    assert r.json()["my_stars"] == 0
