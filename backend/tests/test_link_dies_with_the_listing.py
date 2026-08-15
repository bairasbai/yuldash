"""Ссылка на поездку должна умирать вместе с объявлением.

История. Ильдар опубликовал рейс Баймак → Сибай и кинул ссылку в сельский чат — так тут
и делают, ссылка расходится по десятку телефонов за минуту. Через день его сняли с линии:
несколько подтверждённых жалоб, пауза «Справедливости».

В приложении объявление сразу исчезло — лента спрашивает общую точку видимости, и та
знает про паузу. А публичная страница по прямой ссылке решала сама и отставала на одно
правило: она проверяла только «поездка активна» и «не для своих» (аудит 2026-08-08, волна 112).

Что из этого выходило. Ссылка в чате продолжала работать и собирать людей. Пассажир открывал
её, видел живое объявление с ценой и комментарием, бронировал — и упирался в подтверждение,
которого не будет: принять бронь снятому с линии закрыто. Он ждал, водитель выглядел как
«не отвечает», а объявления, на которое он смотрел, для всех остальных уже не существовало.

Обратная сторона важна не меньше: у обычного водителя ссылка обязана работать. Это главный
способ, которым поездка расходится по деревне, — сломать его значит сломать продукт.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import SafetyProfile, UserRole
from app.timeutil import utcnow

from test_api import _ride


def _снять_с_линии(user_id: int, дней: int = 1) -> None:
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=дней)))
        s.commit()


@pytest.fixture
def рейс_в_чате(client, user_factory):
    """Водитель опубликовал рейс и уже разослал ссылку."""
    driver = user_factory("СсылкаИльдар", role=UserRole.driver)
    ride_id = _ride(client, driver, comment="еду утром, есть места")
    assert client.get(f"/r/{ride_id}/preview").status_code == 200, "ссылка не работала с самого начала"
    return driver, ride_id


def test_у_снятого_с_линии_ссылка_перестаёт_открываться(client, рейс_в_чате):
    driver, ride_id = рейс_в_чате

    _снять_с_линии(driver["id"])

    assert client.get(f"/r/{ride_id}/preview").status_code == 404, (
        "ссылка в сельском чате продолжает собирать пассажиров на рейс, которого в приложении "
        "уже нет: они придут к машине, которая никуда не едет"
    )
    assert client.get(f"/r/{ride_id}").status_code == 404, "страница по ссылке всё ещё открывается"


def test_из_ленты_такая_поездка_тоже_пропала(client, рейс_в_чате, user_factory):
    """Сверяем две двери: ссылка должна молчать ровно тогда же, когда молчит лента."""
    driver, ride_id = рейс_в_чате
    сосед = user_factory("СсылкаСосед")

    _снять_с_линии(driver["id"])

    лента = client.get("/rides", headers=сосед["auth"]).json()
    в_ленте = sum(1 for r in лента if r["id"] == ride_id)
    по_ссылке = client.get(f"/r/{ride_id}/preview").status_code == 200
    assert not в_ленте and not по_ссылке, (
        f"двери разошлись: в ленте видна {в_ленте} раз, по ссылке открывается — {по_ссылке}"
    )


def test_у_обычного_водителя_ссылка_работает(client, рейс_в_чате):
    """Обратная сторона: так поездка и расходится по деревне. Сломать это — сломать продукт."""
    _, ride_id = рейс_в_чате

    r = client.get(f"/r/{ride_id}/preview")

    assert r.status_code == 200, r.text
    assert r.json()["from_city"] and r.json()["price"] >= 0


def test_поездка_только_для_своих_по_ссылке_не_раскрывается(client, user_factory):
    """Правило было и раньше — проверяем, что новая проверка его не потеряла."""
    driver = user_factory("СсылкаЗакрытый", role=UserRole.driver)
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-09-01T10:00:00",
        "seats_total": 3, "price": 300, "only_trusted": True,
    })
    assert r.status_code == 200, r.text

    assert client.get(f"/r/{r.json()['id']}/preview").status_code == 404


def test_ссылка_спрашивает_общую_точку_видимости():
    """Сторож: своя проверка в этой ручке снова начнёт отставать от ленты.

    Правил про «кому показывать поездку» четыре, и они растут. Пока ссылка спрашивает общую
    точку, новое правило приходит в неё само; напишешь тут свой набор условий — и следующее
    правило снова доедет не до всех дверей.
    """
    from pathlib import Path

    src = (Path(__file__).resolve().parents[1] / "app" / "routers" / "share.py").read_text(encoding="utf-8")
    block = src[src.index("def _shareable("):]
    block = block[: block.index("\n\n\n")]
    assert "visible_rides(" in block, (
        "публичная витрина снова решает сама, кого показывать. Спроси visible_rides — ту же "
        "точку, что и лента, иначе следующее правило видимости опять доедет не до всех дверей."
    )
