# -*- coding: utf-8 -*-
"""Модерация текста стала видимой + три непроверенных поля (2026-08-08).

Что было. Движок модерации (`antifraud.moderate_text`) уже стоял на комментариях поездок и
заявок, откликах, отзывах, заказах такси, посылках и чате. Но результат ложился ТОЛЬКО в
счётчик Redis: в админ-пульсе было видно число помеченных за сегодня — и всё. Кто и за что,
посмотреть было нельзя, то есть среагировать не на что. Помечать и не показывать — работа
впустую.

Плюс три открытых поля не проверялись вовсе: имя профиля (самое публичное поле в приложении),
«Где встречаемся» и тексты спора, которые читает вторая сторона.

Приватность: в журнале НЕТ самого текста — только ссылка (place + ref_id). Текст лежит в своей
таблице, админ откроет запись и увидит в контексте; второй копии личных данных не появляется.

Принцип не меняется: помечаем, не блокируем. Тексты во всех тестах ниже сохраняются.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import TextFlag, UserRole

ABUSE = "ты мудак"                     # мат — ловится во всех полях
PHONE = "звони 8 917 123 45 67"        # телефон — помечается только там, где есть комиссия


def _flags(user_id: int, place: str | None = None):
    with Session(engine) as s:
        q = select(TextFlag).where(TextFlag.user_id == user_id)
        if place:
            q = q.where(TextFlag.place == place)
        return s.exec(q).all()


# ----------------------------- журнал появился -----------------------------

def test_flag_is_written_with_place_and_ref(client, user_factory):
    """Мат в отзыве → в журнале появилась запись: кто, какая метка, где."""
    drv = user_factory("ModDrv", role=UserRole.driver)
    pax = user_factory("ModPax")
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 3, "price": 300, "comment": ABUSE})
    assert r.status_code == 200, r.text
    rows = _flags(drv["id"], "ride_comment")
    assert len(rows) == 1
    assert rows[0].kind == "abuse"
    # Сам текст в журнале НЕ хранится — только ссылка на запись.
    assert not hasattr(rows[0], "text")


def test_flagged_text_is_still_saved(client, user_factory):
    """Главный принцип: помечаем, но НЕ блокируем — текст сохранён и виден."""
    drv = user_factory("ModDrv2", role=UserRole.driver)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Темясово", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 3, "price": 300, "comment": ABUSE})
    assert r.status_code == 200
    assert r.json()["comment"] == ABUSE


def test_clean_text_leaves_no_flag(client, user_factory):
    """Обычный текст журнал не засоряет — иначе экран админа превратится в шум."""
    drv = user_factory("CleanDrv", role=UserRole.driver)
    client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 3, "price": 300, "comment": "Выезжаю в 8 утра, есть место под багаж"})
    assert _flags(drv["id"]) == []


def test_carpool_phone_is_not_flagged(client, user_factory):
    """В попутке телефон — норма и суть «между своими», комиссии тут нет. Не помечаем.

    Это защита не от нарушителя, а от нас самих: пометить обмен номерами у соседей значит
    завалить админа ложными срабатываниями и приучить его игнорировать журнал.
    """
    drv = user_factory("PhoneDrv", role=UserRole.driver)
    client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 3, "price": 300, "comment": PHONE})
    assert _flags(drv["id"], "ride_comment") == []


# ----------------------------- три новых поля -----------------------------

def test_profile_name_is_rejected_not_just_flagged(client, user_factory):
    """Имя видно всем и везде — и было единственным непроверенным публичным полем.

    ⚠️ Имя — ИСКЛЮЧЕНИЕ из общего правила «не блокируем, помечаем для админа». Правку внесли
    2026-08-08 при слиянии двух параллельных веток: одна помечала имя как обычный открытый
    текст, вторая (аудит) отказывала сразу. Оставили отказ.

    Почему. Остальной открытый текст человек пишет один раз и больше не видит — там молчаливая
    метка уместна. Имя же редактируют осознанно и результат видят сразу: молча пропустить чужое
    имя, а потом показывать его всем в карточках, чате и отзывах — хуже, чем честно сказать
    «так нельзя». Отказ живёт в `_guard_display_name` (`routers/auth.py`), 422 + двуязычный текст.
    """
    u = user_factory("NameUser")
    r = client.post("/me/update", headers=u["auth"], json={"name": ABUSE})
    assert r.status_code == 422, r.text
    # Отказ обязан быть на двух языках — иначе башкироязычный увидит пустоту.
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"], detail
    # И имя действительно не сохранилось.
    me = client.get("/me", headers=u["auth"])
    assert me.status_code == 200 and ABUSE not in (me.json().get("name") or "")


def test_pickup_field_is_moderated(client, user_factory):
    """«Где встречаемся» — такое же открытое поле, что и комментарий."""
    drv = user_factory("PickupDrv", role=UserRole.driver)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 3, "price": 300, "pickup": ABUSE})
    assert r.status_code == 200, r.text
    rows = _flags(drv["id"], "pickup")
    assert len(rows) == 1 and rows[0].kind == "abuse"


def test_incident_text_is_moderated(client, user_factory):
    """Описание спора читает вторая сторона — проверяем и его.

    Жалобу нельзя открыть «в воздух»: она привязывается к совместной поездке (иначе это был бы
    канал харассмента). Поэтому сначала настоящая поездка и бронь, потом жалоба.
    """
    drv = user_factory("IncDrv", role=UserRole.driver)
    pax = user_factory("IncPax")
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 3, "price": 300}).json()
    booking = client.post("/bookings", headers=pax["auth"],
                          json={"ride_id": ride["id"], "seats": 1}).json()
    r = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": drv["id"], "type": "rude",
        "description": ABUSE, "booking_id": booking["id"]})
    assert r.status_code == 200, r.text
    rows = _flags(pax["id"], "incident")
    assert len(rows) == 1 and rows[0].kind == "abuse"
    assert rows[0].ref_id == r.json()["id"]     # ссылка ведёт на сам спор


# ----------------------------- экран админа -----------------------------

def test_admin_sees_who_and_for_what(client, user_factory):
    """Ради чего всё: админ видит имя, телефон, вид метки и ЧЕЛОВЕЧЕСКОЕ название места."""
    drv = user_factory("AdmModDrv", role=UserRole.driver)
    admin = user_factory("AdmMod", role=UserRole.admin)
    client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 3, "price": 300, "comment": ABUSE})
    rows = client.get("/admin/text-flags", headers=admin["auth"]).json()
    mine = [x for x in rows if x["user_id"] == drv["id"]]
    assert mine, "пометка не доехала до админа"
    assert mine[0]["kind"] == "abuse"
    assert mine[0]["place_label"] == "Комментарий к поездке"
    assert mine[0]["user_flags_total"] >= 1
    assert "text" not in mine[0]      # текста в выдаче нет — только ссылка


def test_admin_filter_by_kind(client, user_factory):
    """Фильтр по виду метки: искать мат среди фишинга неудобно."""
    admin = user_factory("AdmMod2", role=UserRole.admin)
    rows = client.get("/admin/text-flags", headers=admin["auth"], params={"kind": "abuse"}).json()
    assert all(x["kind"] == "abuse" for x in rows)


def test_text_flags_are_admin_only(client, user_factory):
    """В журнале имена и телефоны — обычному пользователю он закрыт."""
    u = user_factory("NotAdmin")
    assert client.get("/admin/text-flags", headers=u["auth"]).status_code == 403
    assert client.get("/admin/text-flags").status_code == 401
