# -*- coding: utf-8 -*-
"""Модерация отзыва: админ должен видеть, О КОМ текст.

Очередь модерации отдавала автора отзыва и голый `ratee_id` — число. Админ читал
«нахамил и вёз молча», но не знал, чей это профиль и кому прилетит публикация.
Решение «публиковать или нет» без этого принимать нельзя.
"""
from sqlmodel import Session

from app import models as M
from app.db import engine
from app.models import UserRole


def _rating(rater_id: int, ratee_id: int, text: str) -> int:
    with Session(engine) as s:
        r = M.Rating(rater_id=rater_id, ratee_id=ratee_id, stars=2, text=text, text_published=False)
        s.add(r); s.commit(); s.refresh(r)
        return r.id


def test_pending_shows_who_the_review_is_about(client, user_factory):
    author = user_factory(name="Гүзәл")
    driver = user_factory(name="Ринат", role=UserRole.driver)
    admin = user_factory(name="Админ-модератор", role=UserRole.admin)
    rid = _rating(author["id"], driver["id"], "Вёз молча, музыку не убавил")
    rows = client.get("/admin/ratings/pending", headers=admin["auth"]).json()
    row = next(x for x in rows if x["id"] == rid)
    assert row["author"] == "Гүзәл"
    assert row["ratee"] == "Ринат"          # главное: видно, чей профиль под ударом
    assert row["ratee_id"] == driver["id"]


def test_publish_answers_with_the_same_names(client, user_factory):
    """Ответ на публикацию — та же карточка: экран не должен терять имя после нажатия."""
    author = user_factory(name="Айгуль")
    driver = user_factory(name="Салават", role=UserRole.driver)
    admin = user_factory(name="Админ-модератор2", role=UserRole.admin)
    rid = _rating(author["id"], driver["id"], "Помог с сумками")
    r = client.post(f"/admin/ratings/{rid}/publish", headers=admin["auth"], json={"published": True})
    assert r.status_code == 200, r.text
    assert r.json()["ratee"] == "Салават" and r.json()["author"] == "Айгуль"


def test_nameless_user_does_not_break_the_queue(client, user_factory):
    """У человека может не быть имени — очередь обязана пережить это, а не упасть."""
    admin = user_factory(name="Админ-модератор3", role=UserRole.admin)
    with Session(engine) as s:
        a = M.User(phone="tg-mod-a", name="", verified=True)
        b = M.User(phone="tg-mod-b", name="", verified=True)
        s.add(a); s.add(b); s.commit(); s.refresh(a); s.refresh(b)
        ids = (a.id, b.id)
    rid = _rating(ids[0], ids[1], "Без имени")
    rows = client.get("/admin/ratings/pending", headers=admin["auth"]).json()
    row = next(x for x in rows if x["id"] == rid)
    assert row["author"] == "Аноним" and row["ratee"] == "Пользователь"
