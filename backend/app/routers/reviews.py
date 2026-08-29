"""Отзывы о приложении: пользователь оставляет → модерация админом → лендинг.

`POST /reviews`                      — оставить отзыв (auth), сохраняется неопубликованным.
`GET  /reviews/mine`                 — свои отзывы (auth).
`GET  /reviews/public`              — одобренные отзывы для лендинга (без auth).
`GET  /admin/reviews/pending`        — ожидающие модерации (admin).
`POST /admin/reviews/{id}/publish`   — одобрить/снять с публикации (admin).

Модерация ТЕКСТОВЫХ отзывов о поездке (Rating.text) — тот же паттерн:
`GET  /admin/ratings/pending`        — тексты, ждущие модерации (admin).
`POST /admin/ratings/{id}/publish`   — одобрить/снять текст (admin).
"""
from datetime import datetime
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..antifraud import moderate_open_text
from ..db import get_session
from ..config import settings
from ..errors import herr
from ..flood import TOO_FAST_CREATING, guard_burst
from ..logs import admin_action
from ..models import AppReview, Rating, User, UserRole
from ..security import current_user

router = APIRouter(tags=["reviews"])


class AppReviewIn(BaseModel):
    stars: int = 5
    text: str = Field("", max_length=600)
    city: str = Field("", max_length=60)


class PublicReviewOut(BaseModel):
    name: str
    city: str = ""
    stars: int
    text: str
    created_at: datetime


class ReviewPublishIn(BaseModel):
    published: bool = True


@router.post("/reviews", response_model=AppReview)
def create_review(body: AppReviewIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Оставить отзыв о приложении. На лендинг попадёт после модерации (published)."""
    # Потолок на темп: отзывы о приложении своего ограничения не имели вовсе, и один человек
    # клал в очередь модерации пятнадцать штук подряд (проба роя, волна 152). Настоящие отзывы
    # соседей тонут в мусоре, а админ перестаёт открывать очередь — то есть страдают как раз
    # те, ради кого она заведена.
    guard_burst(session, AppReview.id, AppReview.created_at, AppReview.user_id == user.id,
                per_minute=settings.flood_create_per_minute,
                ru=TOO_FAST_CREATING[0], ba=TOO_FAST_CREATING[1])
    text = (body.text or "").strip()
    if len(text) < 10:
        raise herr(400, "Отзыв слишком короткий", "Баһа артыҡ ҡыҫҡа")
    moderate_open_text(text, getattr(user, "id", None), place="review", session=session)   # отзыв публичный — телефон и грубость помечаем
    if len(text) > 600:
        raise herr(400, "Отзыв слишком длинный", "Баһа артыҡ оҙон")
    # Город — тоже открытое поле, и его тоже проверяем (аудит 2026-08-08, волна 152).
    #
    # Проба роя: `city = "Сибай тел 89991112233"` прошёл насквозь и оказался на публичном
    # лендинге — без входа, без метки админу. То есть бесплатная рекламная строка с чужим
    # телефоном на сайте Юлдаша, о которой никто не знает: проверку навесили на текст отзыва
    # и забыли на соседнее поле. Ровно то же было с именем в профиле (волна 2026-08-07).
    город = (body.city or "").strip()[:60]
    moderate_open_text(город, getattr(user, "id", None), place="review_city", session=session)
    review = AppReview(
        user_id=user.id,
        name=(user.name or "").strip(),
        city=город,
        stars=max(1, min(5, body.stars)),
        text=text,
        published=False,
    )
    session.add(review)
    session.commit()
    session.refresh(review)
    return review


@router.get("/reviews/mine", response_model=List[AppReview])
def my_reviews(limit: int = 100, offset: int = 0,
               user: User = Depends(current_user), session: Session = Depends(get_session)):
    limit = max(1, min(limit, 100))
    offset = max(0, offset)
    return session.exec(
        select(AppReview).where(AppReview.user_id == user.id)
        .order_by(AppReview.created_at.desc()).offset(offset).limit(limit)
    ).all()


@router.get("/reviews/public", response_model=List[PublicReviewOut])
def public_reviews(limit: int = 12, session: Session = Depends(get_session)):
    """Публичные отзывы для лендинга — только одобренные, новые сверху."""
    limit = max(1, min(50, limit))
    rows = session.exec(
        select(AppReview)
        .where(AppReview.published == True)  # noqa: E712
        .order_by(AppReview.created_at.desc())
        .limit(limit)
    ).all()
    return [
        PublicReviewOut(name=r.name or "Аноним", city=r.city, stars=r.stars, text=r.text, created_at=r.created_at)
        for r in rows
    ]


@router.get("/admin/reviews/pending", response_model=List[AppReview])
def pending_reviews(limit: int = 200, offset: int = 0,
                    user: User = Depends(current_user), session: Session = Depends(get_session)):
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    limit = max(1, min(limit, 500))
    offset = max(0, offset)
    return session.exec(
        select(AppReview).where(AppReview.published == False)  # noqa: E712
        .order_by(AppReview.created_at.desc()).offset(offset).limit(limit)
    ).all()


@router.post("/admin/reviews/{review_id}/publish", response_model=AppReview)
def publish_review(review_id: int, body: ReviewPublishIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    review = session.get(AppReview, review_id)
    if not review:
        raise herr(404, "Отзыв не найден", "Баһа табылманы")
    review.published = body.published
    session.add(review)
    session.commit()
    session.refresh(review)
    admin_action(user.id, "review.publish", review_id=review_id)
    return review


# ---------------- Модерация текстовых отзывов о поездке (Rating.text) ----------------
class PendingRatingOut(BaseModel):
    id: int
    author: str = ""                         # кто оставил (для админа; в публичном профиле тоже без телефона)
    ratee_id: int                            # кому адресован (водитель/пассажир)
    # Имя того, О КОМ отзыв. Без него админ модерировал текст вслепую: видел «нахамил и вёз
    # молча», но не знал, чей это профиль и кому прилетит публикация. Телефоны не отдаём —
    # для решения «публиковать или нет» достаточно имени.
    ratee: str = ""
    stars: int
    text: str
    created_at: datetime


@router.get("/admin/ratings/pending", response_model=List[PendingRatingOut])
def pending_ratings(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Текстовые отзывы, ждущие модерации: есть текст, но ещё не опубликован."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    rows = session.exec(
        select(Rating)
        .where(Rating.text_published == False, Rating.text != "")  # noqa: E712
        .order_by(Rating.created_at.desc())
    ).all()
    # Одним запросом обе стороны: и кто написал, и о ком — иначе на каждый отзыв уходило бы
    # по два похода в базу, а очередь модерации может быть длинной.
    people_ids = {r.rater_id for r in rows} | {r.ratee_id for r in rows}
    people = {u.id: u for u in session.exec(select(User).where(User.id.in_(people_ids))).all()} if people_ids else {}

    def _name(uid: int, fallback: str) -> str:
        u = people.get(uid)
        return ((u.name if u else "") or "").strip() or fallback

    return [
        PendingRatingOut(
            id=r.id, author=_name(r.rater_id, "Аноним"),
            ratee_id=r.ratee_id, ratee=_name(r.ratee_id, "Пользователь"),
            stars=r.stars, text=r.text, created_at=r.created_at,
        )
        for r in rows
    ]


class RatingPublishIn(BaseModel):
    published: bool = True


@router.post("/admin/ratings/{rating_id}/publish", response_model=PendingRatingOut)
def publish_rating(rating_id: int, body: RatingPublishIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Одобрить текстовый отзыв к показу в публичном профиле (или снять)."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    r = session.get(Rating, rating_id)
    if not r:
        raise herr(404, "Отзыв не найден", "Баһа табылманы")
    if not (r.text or "").strip():
        raise HTTPException(400, "У оценки нет текста для модерации")
    r.text_published = body.published
    session.add(r)
    session.commit()
    session.refresh(r)
    author = session.get(User, r.rater_id)
    ratee = session.get(User, r.ratee_id)
    admin_action(user.id, "rating.publish", rating_id=rating_id)
    return PendingRatingOut(
        id=r.id, author=((author.name if author else "") or "Аноним"),
        ratee_id=r.ratee_id, ratee=((ratee.name if ratee else "") or "Пользователь"),
        stars=r.stars, text=r.text, created_at=r.created_at,
    )


class RatingExcludeIn(BaseModel):
    excluded: bool = True


@router.post("/admin/ratings/{rating_id}/exclude")
def exclude_rating(rating_id: int, body: RatingExcludeIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Щит рейтинга» (Справедливость): админ помечает спорную/накрученную оценку снятой — она
    перестаёт влиять на средний рейтинг и число «N оценок» (или возвращает обратно, excluded=false).
    Защита оболганного: одна месть-оценка не должна рушить рейтинг честного."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    r = session.get(Rating, rating_id)
    if not r:
        raise herr(404, "Оценка не найдена", "Баһа табылманы")
    r.excluded = body.excluded
    session.add(r)
    session.flush()
    # Пересчёт DriverProfile.rating — как в _exclude_linked_ratings (safety_logic): без него
    # при cnt=0 карточка падала бы на устаревший prof.rating вместо нейтрального сида.
    # Считаем ВОДИТЕЛЬСКИМ баллом (волна 194): это число читает matcher, а он решает, кому
    # дать заказ. Общий балл человека тут не годится — в нём и его роли пассажира.
    from ..models import DriverProfile
    from ..services import driver_rating
    avg, cnt = driver_rating(session, r.ratee_id)
    prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == r.ratee_id)).first()
    if prof:
        prof.rating = round(avg, 1) if cnt > 0 else 5.0
        session.add(prof)
    session.commit()
    admin_action(user.id, "rating.exclude", rating_id=rating_id)
    return {"id": rating_id, "excluded": r.excluded}
