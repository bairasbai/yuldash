# -*- coding: utf-8 -*-
"""Просроченные документы закрывают и доставку, а не только такси (2026-08-29).

БЫЛО. У таксиста истекало ОСАГО или разрешение — гейт честно закрывал ему такси. И он на
ТОЙ ЖЕ машине шёл возить посылки: в приёме доставки проверки документов не было вообще.
Полис у машины один, дорога одна, и «страховки нет, но коробки вози» не выдерживает ни
здравого смысла, ни первого же ДТП с чужим грузом в багажнике.

Зеркальная дыра к той, что чинили в этот же день с усталостью: лимит смены считался только
по такси, и водитель «отдыхал», развозя посылки.

ГРАНИЦА — ровно по тому, что мы знаем.

Курьеру, который никогда не подавался в такси, ОСАГО никто не показывал: заявка курьера
просит селфи, ФИО и госномер. Требовать полис задним числом — значит менять условия входа
для уже принятых людей, и это решение Александра, а не гейта. Такой курьер работает как
работал, и тест ниже это стережёт.

А про таксиста мы знаем точно: срок кончился, дату он вписал сам. Закрывать на это глаза,
пока он возит чужие вещи, нельзя.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import TaxiApplication, TaxiApplicationStatus, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _modes_on():
    было = settings.taxi_enabled, settings.courier_enabled
    settings.taxi_enabled, settings.courier_enabled = True, True
    yield
    settings.taxi_enabled, settings.courier_enabled = было


from test_courier_c4 import _make_courier, _order  # noqa: E402,F401 — общие помощники


def _просрочить(user_id: int, дней_назад: int = 1):
    """Одобренная заявка таксиста с истёкшим ОСАГО."""
    with Session(engine) as s:
        app = s.exec(select(TaxiApplication).where(TaxiApplication.user_id == user_id)).first()
        if app is None:
            app = TaxiApplication(user_id=user_id, status=TaxiApplicationStatus.approved)
        app.status = TaxiApplicationStatus.approved
        app.osago_until = (utcnow() - timedelta(days=дней_назад)).date()
        s.add(app)
        s.commit()


# ==================== 1. Дыра закрыта ====================
def test_a_courier_with_expired_osago_cannot_take_a_new_parcel(client, user_factory):
    """ОСАГО кончилось → доставку не берём. Машина и дорога те же, что у такси."""
    courier = _make_courier(client, user_factory, name="ПросроченныйПолис")
    sender = user_factory("ОтправительПолис")
    pid = _order(client, sender).json()["id"]

    _просрочить(courier["id"])

    r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert r.status_code == 403, f"взял доставку без действующего ОСАГО: {r.status_code}"
    assert "ОСАГО" in r.text or "документ" in r.text.lower()


def test_the_refusal_names_both_doors(client, user_factory):
    """Текст отказа говорит про такси И доставку: иначе человек упрётся и не поймёт.

    Раньше он обещал «обнови — и такси снова откроется», и про доставку молчал, потому что
    доставка и так работала. После починки молчание превратилось бы в неправду.
    """
    from app import taxi as taxi_mod

    assert "доставк" in taxi_mod.MSG_DOCS_EXPIRED["ru"].lower(), (
        "отказ не называет доставку, хотя теперь закрывает и её"
    )
    assert "илтеү" in taxi_mod.MSG_DOCS_EXPIRED["ba"].lower(), "башкирский текст не обновлён"
    # И по-прежнему честно про попутку: она разрешения не требует и остаётся открытой.
    assert "опутка" in taxi_mod.MSG_DOCS_EXPIRED["ru"]


# ==================== 2. Границу не перешли ====================
def test_a_plain_courier_is_not_asked_for_papers_he_never_gave(client, user_factory):
    """Курьер без заявки в такси работает как работал: ОСАГО у него никто не спрашивал.

    Требовать полис задним числом — это менять условия входа для уже принятых людей.
    Такое решение принимает Александр, а не гейт.
    """
    courier = _make_courier(client, user_factory, name="ПростоКурьер")
    sender = user_factory("ОтправительПростой")
    pid = _order(client, sender).json()["id"]

    with Session(engine) as s:      # заявки в такси у него нет вовсе
        s.exec(select(TaxiApplication).where(TaxiApplication.user_id == courier["id"])).all()

    r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert r.status_code == 200, f"курьеру закрыли доставку за документы, которых не просили: {r.text}"


def test_valid_papers_do_not_block_anything(client, user_factory):
    """Документы в порядке — доставка открыта. Гейт не должен ловить здоровых."""
    courier = _make_courier(client, user_factory, name="ДокументыВПорядке")
    sender = user_factory("ОтправительПорядок")
    pid = _order(client, sender).json()["id"]

    with Session(engine) as s:
        app = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == courier["id"])).first()
        if app is not None:
            app.status = TaxiApplicationStatus.approved
            app.osago_until = (utcnow() + timedelta(days=180)).date()
            s.add(app)
            s.commit()

    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200


def test_the_parcel_already_in_the_car_is_still_delivered(client, user_factory):
    """Гейт стоит на ПРИЁМЕ. Ту посылку, что уже в машине, надо довезти.

    Бросить чужую вещь посреди дороги хуже, чем дать доработать рейс: отправитель ни в чём
    не виноват, а посылка физически у курьера.
    """
    courier = _make_courier(client, user_factory, name="УжеВезётПросрочка")
    sender = user_factory("ОтправительВПути")
    pid = _order(client, sender).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200

    _просрочить(courier["id"])      # полис кончился, пока он был в рейсе

    r = client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    assert r.status_code == 200, f"довезти начатую доставку не дали: {r.text}"
