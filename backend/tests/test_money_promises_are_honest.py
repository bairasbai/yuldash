"""Обещания про деньги расходились с тем, что человек получал на самом деле.

Три находки роя, все про честность цифр (аудит 2026-08-08, волна 153).

**Сообщение о бонусе врало при полном кошельке.** У бесплатных поднятий есть потолок. Человеку
с 18 поднятиями код «на 20» добавлял два, а сообщение обещало двадцать. По прайсу поднятия —
50 ₽ штука, то есть разница в 900 ₽, и водитель узнал бы о ней, только пересчитав вручную.

**Бесплатное поднятие сгорало на отменённой поездке.** Платный путь проверял, жива ли поездка,
бесплатный — нет. Бонус, заработанный за приведённого друга, уходил на объявление, которого
никто не увидит.

**Сверка показывала доход и молчала про расход.** Единственный денежный отчёт считал комиссию,
но не показывал, сколько платформа доплатила водителям за промо-скидки. По нему кампания
«300 ₽ каждому» выглядела бесплатной — хотя каждую такую скидку оплачиваем мы.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.ledger import reconcile
from app.models import LedgerEntry, LedgerKind, Ride, RideStatus, User, UserRole
from app.routers.referral import MAX_REFERRAL_CREDITS
from app.timeutil import utcnow

from test_api import _ride


def _кампания(client, админ, code: str, perk: int) -> int:
    r = client.post("/admin/promo", headers=админ["auth"], json={
        "code": code, "title": "Поднятия", "kind": "boost", "perk_value": perk,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def test_сообщение_о_бонусе_не_обещает_лишнего(client, user_factory):
    """Главное: человеку называют то число, которое он реально получил."""
    админ = user_factory("ЧестьАдмин", role=UserRole.admin)
    водитель = user_factory("ЧестьВодитель", role=UserRole.driver)
    _кампания(client, админ, "BONUS20", 20)
    with Session(engine) as s:                       # кошелёк почти полон
        u = s.get(User, водитель["id"])
        u.referral_credits = MAX_REFERRAL_CREDITS - 2
        s.add(u)
        s.commit()

    r = client.post("/promo/apply", headers=водитель["auth"], json={"code": "BONUS20"})

    assert r.status_code == 200, r.text
    текст = r.json()["message_ru"]
    assert "20 бесплатных" not in текст, (
        f"обещали двадцать поднятий, а влезло два: {текст!r}. По прайсу это разница в 900 ₽, "
        "и водитель узнает о ней, только пересчитав вручную"
    )
    with Session(engine) as s:
        assert s.get(User, водитель["id"]).referral_credits == MAX_REFERRAL_CREDITS


def test_обычное_начисление_называет_число(client, user_factory):
    """Обратная сторона: когда всё влезло, человек должен видеть, сколько ему дали."""
    админ = user_factory("ЧестьАдмин2", role=UserRole.admin)
    водитель = user_factory("ЧестьВодитель2", role=UserRole.driver)
    _кампания(client, админ, "BONUS5", 5)

    r = client.post("/promo/apply", headers=водитель["auth"], json={"code": "BONUS5"})

    assert "5 бесплатных" in r.json()["message_ru"], r.json()["message_ru"]


def test_бонус_не_сгорает_на_отменённой_поездке(client, user_factory):
    """Награду за приведённого друга нельзя потратить на объявление, которого не увидят."""
    водитель = user_factory("ЧестьБонусВодитель", role=UserRole.driver)
    ride_id = _ride(client, водитель, comment="отменю")
    with Session(engine) as s:
        u = s.get(User, водитель["id"])
        u.referral_credits = 3
        r = s.get(Ride, ride_id)
        r.status = RideStatus.cancelled
        s.add(u)
        s.add(r)
        s.commit()

    ответ = client.post("/boost/free", headers=водитель["auth"], json={"ride_id": ride_id})

    assert ответ.status_code == 400, (
        f"бонус потрачен на отменённую поездку (ответ {ответ.status_code}): человек лишился "
        "награды за друга впустую"
    )
    with Session(engine) as s:
        assert s.get(User, водитель["id"]).referral_credits == 3, "бонус всё-таки списали"


def test_бесплатное_поднятие_живой_поездки_работает(client, user_factory):
    """Обратная сторона: за что бонус давали, то он и должен делать."""
    водитель = user_factory("ЧестьБонусВодитель2", role=UserRole.driver)
    ride_id = _ride(client, водитель, comment="подниму")
    with Session(engine) as s:
        u = s.get(User, водитель["id"])
        u.referral_credits = 1
        s.add(u)
        s.commit()

    ответ = client.post("/boost/free", headers=водитель["auth"], json={"ride_id": ride_id})

    assert ответ.status_code == 200, f"бесплатное поднятие не сработало: {ответ.text[:120]}"
    with Session(engine) as s:
        assert s.get(Ride, ride_id).boosted_until is not None, "поездка не поднялась"


def test_сверка_показывает_расход_по_кампаниям(client, user_factory):
    """Отчёт, который показывает только доход, врёт о прибыльности кампании."""
    водитель = user_factory("ЧестьСверка", role=UserRole.driver)
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=водитель["id"], kind=LedgerKind.adj, amount_kop=28140,
                          note="promo:1"))
        s.add(LedgerEntry(driver_id=водитель["id"], kind=LedgerKind.fee, amount_kop=-1860,
                          note="комиссия"))
        s.commit()

    with Session(engine) as s:
        отчёт = reconcile(s, utcnow() - timedelta(days=1), utcnow())

    assert "promo_comp_kop" in отчёт, (
        f"в отчёте нет расхода по промокодам: {list(отчёт)}. По нему кампания «300 ₽ каждому» "
        "выглядит бесплатной"
    )
    assert отчёт["promo_comp_kop"] >= 28140, отчёт["promo_comp_kop"]
    assert отчёт["platform_net_kop"] == отчёт["fee_kop"] - отчёт["promo_comp_kop"], (
        "итог не сходится: доход минус расход должен быть виден одним числом"
    )
