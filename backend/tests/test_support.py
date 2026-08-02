# -*- coding: utf-8 -*-
"""Поддержка внутри приложения (тикеты): создание/чтение своего, 404 на чужой,
ответ админа виден пользователю + счётчик непрочитанных, закрытие/переоткрытие."""
from app.models import UserRole


def _create(client, u, subject="Проблема", body="Не приходит смс"):
    r = client.post("/support/tickets", headers=u["auth"], json={"subject": subject, "body": body})
    assert r.status_code == 200, r.text
    return r.json()


def test_create_and_read_own_ticket(client, user_factory):
    u = user_factory("Sup1")
    t = _create(client, u, body="Здравствуйте, вопрос по оплате")
    assert t["status"] == "open"
    assert len(t["messages"]) == 1
    assert t["messages"][0]["sender"] == "user"
    assert t["messages"][0]["body"] == "Здравствуйте, вопрос по оплате"

    # список: свежие сверху, превью последнего сообщения, ждём ответа поддержки → unread=0 пока
    lst = client.get("/support/tickets", headers=u["auth"]).json()
    assert lst["unread"] == 0
    assert any(it["id"] == t["id"] and it["last_sender"] == "user" for it in lst["items"])

    # тред своего тикета
    thread = client.get(f"/support/tickets/{t['id']}", headers=u["auth"]).json()
    assert thread["id"] == t["id"] and len(thread["messages"]) == 1


def test_foreign_ticket_is_404(client, user_factory):
    owner = user_factory("Owner")
    stranger = user_factory("Stranger")
    t = _create(client, owner)
    # чужой не видит тред и не может писать/закрывать — 404 (не раскрываем существование)
    assert client.get(f"/support/tickets/{t['id']}", headers=stranger["auth"]).status_code == 404
    assert client.post(f"/support/tickets/{t['id']}/messages", headers=stranger["auth"],
                       json={"body": "взлом"}).status_code == 404
    assert client.post(f"/support/tickets/{t['id']}/close", headers=stranger["auth"]).status_code == 404


def test_admin_reply_visible_to_user_and_unread(client, user_factory):
    u = user_factory("SupUser")
    admin = user_factory("Admin", role=UserRole.admin)
    t = _create(client, u, body="Вопрос по поездке")

    # админ видит открытый тикет в списке
    ao = client.get("/admin/support/tickets?status=open", headers=admin["auth"]).json()
    assert any(it["id"] == t["id"] for it in ao)

    # ответ поддержки
    r = client.post(f"/admin/support/tickets/{t['id']}/reply", headers=admin["auth"],
                    json={"body": "Здравствуйте! Уже помогаем."})
    assert r.status_code == 200, r.text
    assert r.json()["messages"][-1]["sender"] == "admin"

    # пользователь видит ответ и счётчик непрочитанных = 1 (последней написала поддержка)
    lst = client.get("/support/tickets", headers=u["auth"]).json()
    assert lst["unread"] == 1
    item = next(it for it in lst["items"] if it["id"] == t["id"])
    assert item["last_sender"] == "admin" and item["unread"] is True

    thread = client.get(f"/support/tickets/{t['id']}", headers=u["auth"]).json()
    assert thread["messages"][-1]["body"] == "Здравствуйте! Уже помогаем."

    # пользователь ответил → тикет больше не «ждёт пользователя» (unread=0)
    client.post(f"/support/tickets/{t['id']}/messages", headers=u["auth"], json={"body": "Спасибо!"})
    assert client.get("/support/tickets", headers=u["auth"]).json()["unread"] == 0


def test_close_and_reopen(client, user_factory):
    u = user_factory("SupClose")
    t = _create(client, u)
    # закрыть
    r = client.post(f"/support/tickets/{t['id']}/close", headers=u["auth"])
    assert r.status_code == 200 and r.json()["status"] == "closed"
    # новое сообщение переоткрывает
    r = client.post(f"/support/tickets/{t['id']}/messages", headers=u["auth"], json={"body": "ещё вопрос"})
    assert r.status_code == 200 and r.json()["status"] == "open"


def test_admin_only_guard(client, user_factory):
    u = user_factory("NotAdmin")
    t = _create(client, u)
    # не-админ не лезет в админ-ручки
    assert client.get("/admin/support/tickets", headers=u["auth"]).status_code == 403
    assert client.post(f"/admin/support/tickets/{t['id']}/reply", headers=u["auth"],
                       json={"body": "x"}).status_code == 403


def test_admin_status_filter(client, user_factory):
    u = user_factory("SupFilter")
    admin = user_factory("Admin2", role=UserRole.admin)
    t = _create(client, u)
    client.post(f"/support/tickets/{t['id']}/close", headers=u["auth"])
    # ?status=open не показывает закрытый; ?status=all — показывает
    open_ids = [it["id"] for it in client.get("/admin/support/tickets?status=open", headers=admin["auth"]).json()]
    all_ids = [it["id"] for it in client.get("/admin/support/tickets?status=all", headers=admin["auth"]).json()]
    assert t["id"] not in open_ids
    assert t["id"] in all_ids


def test_admin_open_on_top(client, user_factory):
    u = user_factory("SupOrder")
    admin = user_factory("Admin3", role=UserRole.admin)
    closed = _create(client, u)
    client.post(f"/support/tickets/{closed['id']}/close", headers=u["auth"])
    opened = _create(client, u)   # свежий открытый
    rows = client.get("/admin/support/tickets?status=all", headers=admin["auth"]).json()
    statuses = [it["status"] for it in rows]
    # ни один open не идёт после closed
    if "closed" in statuses and "open" in statuses:
        assert statuses.index("open") < statuses.index("closed")
    assert opened["id"] in [it["id"] for it in rows]
