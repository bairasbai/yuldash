# -*- coding: utf-8 -*-
"""Решение, за которое человек заплатил, обязано остаться ЗАПИСЬЮ, а не только пуш-уведомлением.

Аудит 2026-08-12, волна 24. Кафе платит за подписку и ждёт «одобрено». Магазин платит
за рекламу и ждёт «прошла модерацию». Раньше такое решение уходило голым пушем: он живёт
несколько секунд, не доходит при выключенном телефоне и устаревшем токене, и его смахивают
вместе с десятком других. В Центре уведомлений следа не оставалось — человек звонил
и спрашивал «а что с моей заявкой», потому что проверить было негде.

Второе, что чинит волна: такие сообщения приходили ТОЛЬКО по-русски, хотя правило проекта —
две надписи на двух языках всегда.

Тесты написаны от человека: «бизнес заплатил и ждёт решения», «магазин ждёт свою рекламу»,
«человек ищет забытую в машине вещь». Проверяется то, что человек увидит: запись есть,
на обоих языках, и по ней есть куда пойти.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Ad, UserRole


def _notes(client, u) -> list:
    r = client.get("/notifications", headers=u["auth"])
    assert r.status_code == 200, r.text
    return r.json()["items"]


def _find(items: list, ru_part: str) -> dict:
    """Запись по куску русского заголовка. Нет — значит человек ничего не увидит."""
    match = [n for n in items if ru_part.lower() in (n.get("title_ru") or "").lower()]
    assert match, f"в Центре уведомлений нет записи «{ru_part}»: {[n.get('title_ru') for n in items]}"
    return match[0]


def _both_languages(n: dict) -> None:
    """Оба языка заполнены и различны: «RU · BA» одной строкой — это НЕ двуязычие (урок волны 20)."""
    assert (n.get("title_ru") or "").strip(), f"пустой русский заголовок: {n}"
    assert (n.get("title_ba") or "").strip(), f"пустой башкирский заголовок: {n}"
    assert (n.get("body_ru") or "").strip() and (n.get("body_ba") or "").strip(), n
    assert n["title_ru"] != n["title_ba"], f"башкирский совпадает с русским — перевода нет: {n}"
    for field in ("title_ru", "title_ba", "body_ru", "body_ba"):
        assert " · " not in (n.get(field) or ""), f"два языка склеены в одну строку: {n[field]}"


def _partner(client, user_factory, name="Чак-чак кафе"):
    owner = user_factory(name="Владелец кафе")
    admin = user_factory(name="Админ витрины", role=UserRole.admin)
    r = client.post("/partner", headers=owner["auth"], json={"name": name, "city": "Уфа"})
    assert r.status_code == 200, r.text
    return owner, admin, r.json()["id"]


# --------------------------- бизнес: за проверку заплачено ---------------------------

def test_бизнес_узнаёт_решение_записью_а_не_только_пушем(client, user_factory):
    """Кафе подало карточку и ждёт. Одобрили — это должно быть видно в приложении завтра тоже."""
    owner, admin, pid = _partner(client, user_factory)
    assert client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"]).status_code == 200

    n = _find(_notes(client, owner), "Бизнес одобрен")
    _both_languages(n)
    assert n["ref_kind"] == "partner" and n["ref_id"] == pid   # есть куда пойти дальше


def test_отказ_бизнесу_приходит_с_причиной_и_на_двух_языках(client, user_factory):
    """Отказ без причины — тупик: человек не знает, что исправлять."""
    owner, admin, pid = _partner(client, user_factory, name="Шиномонтаж у трассы")
    r = client.post(f"/admin/partners/{pid}/reject", headers=admin["auth"],
                    json={"reason": "Нет телефона в карточке"})
    assert r.status_code == 200, r.text

    n = _find(_notes(client, owner), "Бизнес отклонён")
    _both_languages(n)
    assert "телефон" in n["body_ru"].lower()
    assert n["ref_kind"] == "partner"


# --------------------------- реклама: деньги вперёд ---------------------------

def _ad(owner_id: int, title="Пекарня «Тандыр»") -> int:
    with Session(engine) as s:
        ad = Ad(owner_id=owner_id, title=title, text="текст", status="pending", package="city")
        s.add(ad)
        s.commit()
        s.refresh(ad)
        return ad.id


def test_реклама_одобрена_и_человек_видит_это_в_приложении(client, user_factory):
    owner = user_factory(name="Пекарь")
    admin = user_factory(name="Админ рекламы", role=UserRole.admin)
    ad_id = _ad(owner["id"])

    r = client.post(f"/admin/ads/{ad_id}/approve", headers=admin["auth"], json={})
    assert r.status_code == 200, r.text

    n = _find(_notes(client, owner), "Реклама одобрена")
    _both_languages(n)
    assert n["ref_kind"] == "ad" and n["ref_id"] == ad_id


def test_реклама_отклонена_причина_видна_обоим_языкам(client, user_factory):
    owner = user_factory(name="Пекарь 2")
    admin = user_factory(name="Админ рекламы 2", role=UserRole.admin)
    ad_id = _ad(owner["id"], title="Шашлычная у моста")

    r = client.post(f"/admin/ads/{ad_id}/reject", headers=admin["auth"],
                    json={"reason": "Нет пометки о рекламе"})
    assert r.status_code == 200, r.text

    n = _find(_notes(client, owner), "Реклама отклонена")
    _both_languages(n)
    assert n["ref_kind"] == "ad"


# --------------------------- вещь, забытая в машине ---------------------------

def test_забытая_вещь_остаётся_записью_а_не_исчезает_с_пушем(client, user_factory):
    """Человек ищет свою вещь и вернётся к этому сообщению завтра — пуш к тому времени смахнут."""
    from app.models import InstantOrder, InstantOrderStatus

    pax = user_factory(name="Пассажир с сумкой")
    drv = user_factory(name="Таксист с находкой", role=UserRole.driver)
    with Session(engine) as s:
        # Завершённая поездка заводится прямо в базе: волна про уведомление, а не про подбор.
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         status=InstantOrderStatus.done,
                         from_lat=53.0, from_lng=58.0, to_lat=53.1, to_lng=58.1)
        s.add(o)
        s.commit()
        s.refresh(o)
        order_id = o.id

    r = client.post(f"/instant/orders/{order_id}/lost-item", headers=pax["auth"])
    assert r.status_code == 200, r.text

    n = _find(_notes(client, drv), "Забытая вещь")
    _both_languages(n)
    assert n["ref_kind"] == "instant" and n["ref_id"] == order_id
