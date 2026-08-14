"""Оповещение «на твоём маршруте» не должно выдавать то, что скрыто в ленте.

История. «Карауль маршрут» — подписка: появилась поездка или пассажир на твоём направлении,
приходит пуш. Удобно, и потому опасно: пуш приходит сам, раньше любой карточки, и содержит
главное — кто-то едет отсюда туда в такой-то день.

Что нашли (аудит 2026-08-08, волна 77). Оповещение о ПОЕЗДКЕ знало два правила: не писать
тому, с кем у водителя блокировка, и не рассказывать про поездку «только для своих» тем,
кто не в круге доверия. Зеркальное оповещение о ЗАЯВКЕ не знало ни одного, хотя в описании
у него было написано «те же правила».

По-человечески это значило вот что. Гульнара заблокировала водителя и оставила заявку
«Баймак → Сибай, завтра». Он караулит это направление — и получает пуш: она едет, вот куда,
вот когда. В ленте заявка от него спрятана, а оповещение всё рассказало.

Вторая половина: заявку «только для своих» человек помечает сам, чтобы её видел лишь круг
доверия. Пуш о ней уходил любому, кто караулит направление.

Обратная сторона: обычный водитель обязан получать такие оповещения — ради них подписка
и существует.
"""
from __future__ import annotations

import pytest

from app.models import UserRole


@pytest.fixture
def caught_pushes(monkeypatch):
    """Ловим адресатов пушей: проверяем, кто узнал, а не как выглядит текст."""
    import app.services as svc
    sent: list[int] = []
    monkeypatch.setattr(svc, "send_push",
                        lambda s, uid, title, body, data=None: sent.append(uid))
    return sent


def _watch(client, who, kind: str):
    r = client.post("/route-watch", headers=who["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "direction": "forward", "watch_kind": kind,
    })
    assert r.status_code == 200, r.text


def _block(client, who, whom_id: int):
    r = client.post("/blocks", headers=who["auth"], json={"blocked_user_id": whom_id})
    assert r.status_code in (200, 201), r.text


def _request(client, who, **extra):
    r = client.post("/requests", headers=who["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats": 1, **extra,
    })
    assert r.status_code == 200, r.text
    return r.json()


def _ride(client, who, **extra):
    r = client.post("/rides", headers=who["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats_total": 3, "price": 300, **extra,
    })
    assert r.status_code == 200, r.text
    return r.json()


def test_заблокированный_не_узнает_о_заявке(client, user_factory, caught_pushes):
    hidden_from = user_factory("КараулРустам", role=UserRole.driver)
    ordinary = user_factory("КараулИльдар", role=UserRole.driver)
    gulnara = user_factory("КараулГульнара")

    _watch(client, hidden_from, "requests")
    _watch(client, ordinary, "requests")
    _block(client, gulnara, hidden_from["id"])

    _request(client, gulnara)

    assert hidden_from["id"] not in caught_pushes, \
        "человек, от которого прячутся, узнал из пуша, что она собралась ехать"
    assert ordinary["id"] in caught_pushes, "обычный водитель не получил оповещение — подписка бесполезна"


def test_заявка_только_для_своих_не_уходит_чужим(client, user_factory, caught_pushes):
    stranger = user_factory("ЧужойВодитель", role=UserRole.driver)
    _watch(client, stranger, "requests")

    _request(client, user_factory("СвоиПассажир"), only_trusted=True)

    assert stranger["id"] not in caught_pushes, \
        "заявку «только для своих» пуш раскрыл тому, кто не в круге доверия"


def test_поездка_живёт_по_тем_же_правилам(client, user_factory, caught_pushes):
    """Сторож на расхождение: у поездок эти правила были с самого начала, и потерять
    их при переезде на общую функцию нельзя."""
    hidden_from = user_factory("КараулПассажирка")
    ordinary = user_factory("КараулСосед")
    driver = user_factory("КараулВодитель", role=UserRole.driver)

    _watch(client, hidden_from, "rides")
    _watch(client, ordinary, "rides")
    _block(client, hidden_from, driver["id"])

    _ride(client, driver)

    assert hidden_from["id"] not in caught_pushes, "пуш о поездке пришёл тому, кто заблокировал водителя"
    assert ordinary["id"] in caught_pushes, "обычный подписчик не узнал о поездке"
