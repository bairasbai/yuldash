"""Все двери к оценке играют по одним правилам.

Оценить у нас можно три вещи: попутку, такси и доставку. Волна 57 свела попутку и такси,
но третья дверь осталась в стороне, и в ней были ровно те же два пробела (проверено запросами,
аудит 2026-08-13, волна 58):

  • отзыв о курьере не проходил модерацию — телефон и грубость в публичном отзыве не попадали
    даже в счётчик, то есть админ узнавал о них только случайно;
  • срока на оценку не было: доставку 400-дневной давности можно было оценить сегодня.

Отдельно здесь стоит сторож на класс. Он читает исходники и требует, чтобы КАЖДОЕ место,
где создаётся оценка, стояло рядом со сроком и модерацией. Появится четвёртая дверь — она
попадёт под правило автоматически, а не через полгода и ещё одну волну аудита.
"""
from __future__ import annotations

from datetime import timedelta
from pathlib import Path

from sqlmodel import Session, select

from app.db import engine
from app.models import ParcelDelivery, Rating, TextFlag, UserRole
from app.rating_service import RATING_WINDOW_DAYS
from app.timeutil import utcnow
from conftest import upload_doc

WITH_PHONE = "Курьер нахамил, звони мне 89991112233"


def _courier(client, user_factory, tag):
    u = user_factory(tag, role=UserRole.passenger)
    selfie = upload_doc(client, u["auth"])
    assert client.post("/courier/apply", headers=u["auth"], json={
        "transport": "car", "full_name": "Курьер Курьеров", "car_plate": "А111АА102",
        "selfie_url": selfie, "rules_accepted": True,
    }).status_code == 200
    return u


def _delivered(session: Session, sender_id: int, courier_id: int, days_ago: int = 1) -> int:
    p = ParcelDelivery(sender_id=sender_id, courier_id=courier_id, from_city="Баймак",
                       to_city="Сибай", size="small", description="документы",
                       receiver_name="Гөлнара", receiver_phone="+79995550001",
                       status="delivered", rules_accepted=True)
    p.created_at = utcnow() - timedelta(days=days_ago)
    session.add(p)
    session.commit()
    session.refresh(p)
    return p.id


def test_телефон_в_отзыве_о_курьере_доходит_до_админа(client, user_factory):
    sender = user_factory("ParcelRateSender", role=UserRole.passenger)
    cour = _courier(client, user_factory, "ParcelRateCourier")
    with Session(engine) as s:
        pid = _delivered(s, sender["id"], cour["id"])
        before = len(s.exec(select(TextFlag.id).where(TextFlag.user_id == sender["id"])).all())

    assert client.post(f"/parcels/{pid}/rate", headers=sender["auth"],
                       json={"stars": 1, "text": WITH_PHONE}).status_code == 200

    with Session(engine) as s:
        after = len(s.exec(select(TextFlag.id).where(TextFlag.user_id == sender["id"])).all())
        assert after > before
        row = s.exec(select(Rating).where(Rating.parcel_id == pid)).first()
        assert row.text == WITH_PHONE          # текст не режем и оценку не рвём
        assert row.text_published is False     # публикуется только после решения админа


def test_доставку_годичной_давности_оценить_нельзя(client, user_factory):
    sender = user_factory("ParcelOldSender", role=UserRole.passenger)
    cour = _courier(client, user_factory, "ParcelOldCourier")
    with Session(engine) as s:
        pid = _delivered(s, sender["id"], cour["id"], days_ago=RATING_WINDOW_DAYS + 10)

    r = client.post(f"/parcels/{pid}/rate", headers=sender["auth"], json={"stars": 1})
    assert r.status_code == 409
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"]


def test_свежую_доставку_оценить_можно(client, user_factory):
    """Страховка от перестраховки: обычная оценка после вручения работает как прежде."""
    sender = user_factory("ParcelFreshSender", role=UserRole.passenger)
    cour = _courier(client, user_factory, "ParcelFreshCourier")
    with Session(engine) as s:
        pid = _delivered(s, sender["id"], cour["id"])

    r = client.post(f"/parcels/{pid}/rate", headers=sender["auth"],
                    json={"stars": 5, "text": "Спасибо, всё в целости"})
    assert r.status_code == 200
    assert r.json()["count"] >= 1


def test_каждая_дверь_к_оценке_под_общим_правилом():
    """Сторож на класс. Новая оценка чего угодно (посылка, курс, магазин…) обязана стоять
    рядом со сроком и модерацией — иначе повторится ровно то, что чинили две волны подряд."""
    app_dir = Path(__file__).resolve().parents[1] / "app"
    offenders: list[str] = []
    for path in sorted(app_dir.rglob("*.py")):
        if path.name == "models.py":
            continue
        src = path.read_text(encoding="utf-8")
        if "Rating(" not in src:
            continue
        # Оценка создаётся здесь → в этом же файле должны быть ВЫЗОВЫ обоих правил.
        # Ищем со скобкой: импорт пишется без неё, и файл с одним лишь импортом сторож
        # обязан ловить — иначе достаточно убрать вызов, оставив строку `from … import`
        # (проверено мутацией: первая версия сторожа этого не замечала).
        if "guard_rating_window(" not in src:
            offenders.append(f"{path.name}: создаёт оценку без срока (guard_rating_window)")
        if "moderate_open_text(" not in src and "apply_rating(" not in src:
            offenders.append(f"{path.name}: создаёт оценку без модерации текста")
    assert offenders == [], (
        "эти места ставят оценку в обход общих правил: " + "; ".join(offenders) +
        ". Зови rating_service.apply_rating или поставь рядом guard_rating_window + "
        "moderate_open_text (аудит, волны 57–58)."
    )
