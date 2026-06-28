"""Отзывы о приложении: пользователь оставляет → модерация админом → лендинг.

`POST /reviews`                      — оставить отзыв (auth), сохраняется неопубликованным.
`GET  /reviews/mine`                 — свои отзывы (auth).
`GET  /reviews/public`              — одобренные отзывы для лендинга (без auth).
`GET  /admin/reviews/pending`        — ожидающие модерации (admin).
`POST /admin/reviews/{id}/publish`   — одобрить/снять с публикации (admin).
"""
from datetime import datetime
from typing import List

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import AppReview, User, UserRole
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
def my_reviews(user: User = Depends(current_user), session: Session = Depends(get_session)):
    return session.exec(
        select(AppReview).where(AppReview.user_id == user.id).order_by(AppReview.created_at.desc())
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
def pending_reviews(user: User = Depends(current_user), session: Session = Depends(get_session)):
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    return session.exec(
        select(AppReview).where(AppReview.published == False).order_by(AppReview.created_at.desc())  # noqa: E712
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
