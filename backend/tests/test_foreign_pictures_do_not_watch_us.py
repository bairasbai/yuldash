# -*- coding: utf-8 -*-
"""Чужая картинка в приложении = слежка за всеми, кто её увидел.

Аудит 2026-08-12, волна 39. Правило «медиа только с нашего хранилища» завели ещё в августе
для фото профиля: чужая ссылка подгружается у КАЖДОГО, кто видит карточку, а хозяин того
сервера собирает IP, город и время просмотра наших людей. Для приложения, чей продукт —
доверие, это дороже, чем кажется.

Правило применили к аватару, голосовым в чате, документам и фото-доказательствам. И не
применили к КАРТИНКЕ РЕКЛАМНОГО ОБЪЯВЛЕНИЯ — а её видят вообще все, и ставит её админ
по ссылке, которую прислал партнёр. «Вставь вот эту картинку» выглядит обычной просьбой.

Теперь правило живёт одной точкой — `services.guard_own_media_url`, и аватар зовёт её же.
"""
import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Ad, UserRole


def _ad_body(image: str):
    return {
        "partner_name": "Партнёр", "partner_contact": "+79990000000",
        "title": "Заголовок", "text": "Текст", "button": "Открыть",
        "target": "https://example.test", "image_url": image, "erid": "erid-1",
        "plan": "standard", "placements": "profile", "cities": "Сибай", "priority": 1,
    }


@pytest.fixture
def admin(user_factory):
    return user_factory("Админ картинок", role=UserRole.admin)


def test_чужая_картинка_в_рекламу_не_проходит(client, admin):
    """Ссылка на посторонний сервер = его владелец увидит всех, кто открыл приложение."""
    r = client.post("/admin/ads", headers=admin["auth"],
                    json=_ad_body("https://tracker.example/pixel.jpg"))
    assert r.status_code == 422, r.text
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"], detail


def test_своя_картинка_проходит(client, admin):
    """Защита не должна мешать работе: загруженная в приложении картинка принимается."""
    r = client.post("/admin/ads", headers=admin["auth"], json=_ad_body("/media/ads/banner.jpg"))
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        ad = s.exec(select(Ad).where(Ad.id == r.json()["id"])).first()
    assert ad.image_url == "/media/ads/banner.jpg"


def test_правку_объявления_тоже_нельзя_подменить_чужой_ссылкой(client, admin):
    """Вторая дверь: объявление создали чистым, а картинку подменили при редактировании."""
    created = client.post("/admin/ads", headers=admin["auth"], json=_ad_body("/media/ads/ok.jpg"))
    assert created.status_code == 200, created.text
    ad_id = created.json()["id"]

    bad = client.post(f"/admin/ads/{ad_id}", headers=admin["auth"],
                      json=_ad_body("https://tracker.example/pixel.jpg"))
    assert bad.status_code == 422, bad.text
    with Session(engine) as s:
        assert s.get(Ad, ad_id).image_url == "/media/ads/ok.jpg"   # старая картинка цела


def test_фото_профиля_живёт_по_тому_же_правилу(client, user_factory):
    """Регресс: аватар теперь зовёт общий помощник — поведение обязано остаться прежним."""
    person = user_factory("Человек с аватаром")
    bad = client.post("/me/update", headers=person["auth"],
                      json={"avatar_url": "https://tracker.example/me.jpg"})
    assert bad.status_code == 422, bad.text
    ok = client.post("/me/update", headers=person["auth"], json={"avatar_url": ""})
    assert ok.status_code == 200, ok.text
