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

from ..db import get_session
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
    text = (body.text or "").strip()
    if len(text) < 10:
        raise HTTPException(400, "Отзыв слишком короткий")
    if len(text) > 600:
        raise HTTPException(400, "Отзыв слишком длинный")
    review = AppReview(
        user_id=user.id,
        name=(user.name or "").strip(),
        city=(body.city or "").strip()[:60],
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
        raise HTTPException(404, "Отзыв не найден")
    review.published = body.published
    session.add(review)
    session.commit()
    session.refresh(review)
    return review


# ---------------- Модерация текстовых отзывов о поездке (Rating.text) ----------------
class PendingRatingOut(BaseModel):
    id: int
    author: str = ""                         # кто оставил (для админа; в публичном профиле тоже без телефона)
    ratee_id: int                            # кому адресован (водитель/пассажир)
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
    author_ids = {r.rater_id for r in rows}
    authors = {a.id: a for a in session.exec(select(User).where(User.id.in_(author_ids))).all()} if author_ids else {}
    return [
        PendingRatingOut(
            id=r.id, author=((authors.get(r.rater_id).name if authors.get(r.rater_id) else "") or "Аноним"),
            ratee_id=r.ratee_id, stars=r.stars, text=r.text, created_at=r.created_at,
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
        raise HTTPException(404, "Отзыв не найден")
    if not (r.text or "").strip():
        raise HTTPException(400, "У оценки нет текста для модерации")
    r.text_published = body.published
    session.add(r)
    session.commit()
    session.refresh(r)
    author = session.get(User, r.rater_id)
    return PendingRatingOut(
        id=r.id, author=((author.name if author else "") or "Аноним"),
        ratee_id=r.ratee_id, stars=r.stars, text=r.text, created_at=r.created_at,
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
        raise HTTPException(404, "Оценка не найдена")
    r.excluded = body.excluded
    session.add(r)
    session.commit()
    return {"id": rating_id, "excluded": r.excluded}
