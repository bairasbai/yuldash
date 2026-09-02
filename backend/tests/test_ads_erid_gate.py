"""Реклама без маркировки (erid) в эфир не идёт.

Закон о рекламе требует erid у каждого рекламного показа; за показ без маркировки штраф
на юрлицо до 500 000 ₽. До этой волны поле erid было просто необязательным: форма админки
его требовала, но прямой запрос обходил форму, а уже опубликованное объявление могло висеть
с пустым номером сколько угодно.

Здесь проверяется вся защита:
  • объявление без erid не попадает в публичную выдачу;
  • одобрить такое объявление нельзя — сервер отказывает, а не полагается на внимательность;
  • переходный период (ERID_GRACE_UNTIL) не гасит объявления партнёров в день выкатки;
  • админ видит, у каких объявлений маркировки нет, раньше, чем это увидит проверяющий.
"""

from datetime import timedelta

import pytest

from app.config import settings
from app.models import UserRole
from app.timeutil import utcnow


def _ad_payload(**overrides):
    body = {
        "partner_name": "Partner",
        "partner_contact": "+79990000000",
        "title": "Title",
        "text": "Text",
        "button": "Open",
        "target": "https://example.test",
        "image_url": "/media/ads/test-banner.jpg",
        "erid": "erid-test",
        "plan": "standard",
        "placements": "map,profile",
        "cities": "Ufa",
        "priority": 1,
        "starts_at": (utcnow() - timedelta(minutes=1)).isoformat(),
        "ends_at": (utcnow() + timedelta(days=1)).isoformat(),
    }
    body.update(overrides)
    return body


@pytest.fixture
def ads_bin(client):
    """Корзина: созданные объявления удаляются после теста.

    База у тестов общая и между ними не сбрасывается. Оставленное активное объявление
    ломает соседей — те, что проверяют «лента пуста» и лимит founder-слотов. Ловили это
    вживую: сами по себе тесты проходили, а в общем прогоне падали два чужих.
    """
    created = []
    yield created
    for ad_id, headers in created:
        client.delete(f"/admin/ads/{ad_id}", headers=headers)


def _make_ad(client, admin, ads_bin, **overrides):
    ad = client.post("/admin/ads", headers=admin["auth"], json=_ad_payload(**overrides)).json()
    ads_bin.append((ad["id"], admin["auth"]))
    return ad


def _make_active_ad(client, admin, ads_bin, **overrides):
    ad = _make_ad(client, admin, ads_bin, **overrides)
    client.post(f"/admin/ads/{ad['id']}/status", headers=admin["auth"], json={"status": "active"})
    return ad


def test_ad_without_erid_is_hidden_from_public_feed(client, user_factory, ads_bin):
    """Пустой erid → объявления нет в выдаче, даже когда оно активно и в своём периоде."""
    admin = user_factory("EridGateAdmin1", role=UserRole.admin)
    marked = _make_active_ad(client, admin, ads_bin, title="Marked", erid="2Vtzq-real")
    unmarked = _make_active_ad(client, admin, ads_bin, title="Unmarked", erid="")

    ids = [row["id"] for row in client.get("/ads", params={"city": "Ufa"}).json()]
    assert str(marked["id"]) in ids
    assert str(unmarked["id"]) not in ids


def test_erid_of_spaces_counts_as_missing(client, user_factory, ads_bin):
    """Пробелы вместо номера — это отсутствие маркировки, а не маркировка."""
    admin = user_factory("EridGateAdmin2", role=UserRole.admin)
    blank = _make_active_ad(client, admin, ads_bin, title="Blank", erid="   ")

    ids = [row["id"] for row in client.get("/ads", params={"city": "Ufa"}).json()]
    assert str(blank["id"]) not in ids


def test_approve_without_erid_is_refused(client, user_factory, ads_bin):
    """Одобрение без номера отклоняется сервером: форму можно обойти прямым запросом."""
    admin = user_factory("EridGateAdmin3", role=UserRole.admin)
    ad = _make_ad(client, admin, ads_bin, erid="")

    refused = client.post(f"/admin/ads/{ad['id']}/approve", headers=admin["auth"], json={"erid": ""})
    assert refused.status_code == 422

    approved = client.post(
        f"/admin/ads/{ad['id']}/approve", headers=admin["auth"], json={"erid": "2Vtzq-assigned"}
    )
    assert approved.status_code == 200, approved.text
    assert approved.json()["erid"] == "2Vtzq-assigned"


def test_grace_period_keeps_unmarked_ad_visible(client, user_factory, monkeypatch, ads_bin):
    """Переходный период: объявление партнёра не гаснет в день выкатки правила."""
    admin = user_factory("EridGateAdmin4", role=UserRole.admin)
    unmarked = _make_active_ad(client, admin, ads_bin, title="Legacy", erid="")

    monkeypatch.setattr(settings, "erid_grace_until", (utcnow() + timedelta(days=7)).isoformat())
    ids = [row["id"] for row in client.get("/ads", params={"city": "Ufa"}).json()]
    assert str(unmarked["id"]) in ids

    # Период кончился — объявление исчезает само, без ручного вмешательства.
    monkeypatch.setattr(settings, "erid_grace_until", (utcnow() - timedelta(days=1)).isoformat())
    ids_after = [row["id"] for row in client.get("/ads", params={"city": "Ufa"}).json()]
    assert str(unmarked["id"]) not in ids_after


def test_broken_grace_date_does_not_break_the_feed(client, user_factory, monkeypatch, ads_bin):
    """Кривая дата в .env не должна ронять выдачу рекламы — просто нет переходного периода."""
    admin = user_factory("EridGateAdmin5", role=UserRole.admin)
    marked = _make_active_ad(client, admin, ads_bin, title="Marked", erid="2Vtzq-ok")
    unmarked = _make_active_ad(client, admin, ads_bin, title="Unmarked", erid="")

    monkeypatch.setattr(settings, "erid_grace_until", "не-дата")
    rows = client.get("/ads", params={"city": "Ufa"})
    assert rows.status_code == 200
    ids = [row["id"] for row in rows.json()]
    assert str(marked["id"]) in ids
    assert str(unmarked["id"]) not in ids


def test_admin_sees_which_ads_have_no_erid(client, user_factory, ads_bin):
    """Админ должен узнать о пропавшей маркировке раньше проверяющего."""
    admin = user_factory("EridGateAdmin6", role=UserRole.admin)
    unmarked = _make_active_ad(client, admin, ads_bin, title="NoErid", erid="")

    items = client.get("/admin/ads", headers=admin["auth"]).json()["items"]
    row = next(r for r in items if r["id"] == str(unmarked["id"]))
    assert row["erid_missing"] is True
    assert row["live"] is False        # из выдачи уже скрыто
