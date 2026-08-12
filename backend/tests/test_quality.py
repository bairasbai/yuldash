"""Тесты батча B5 «Качество: жалобы + лестница наказаний» (волна 2, §9).

1) Жалобы: категории из перечня, привязка к заказу/брони (участник ок, не участник 403,
   на себя 400, несовпадение цели 400), старое тело (target+reason) совместимо.
2) Анонимность: в ответе автору нет reporter-полей; /me/restrictions цели без автора;
   /admin/reports видит автора ТОЛЬКО админ.
3) Оценки заказов: обе стороны после done, одна на (rater, order) — повтор обновляет,
   агрегат в driver.rating (учитывает и заказы), не участник 403, не done 409,
   в ответе нет rater («кто поставил» не раскрывается).
4) Лестница: 🟡 совет при <4.8 (дедуп 1/нед); 🟠 штраф в score при <4.6;
   🔴 3 resolved за 30 дн → авто-пауза такси 72ч (presence/offer/accept гейт);
   ⛔ тяжёлая → мгновенный Telegram + пауза до разбора; resolve снимает/оставляет
   (keep_pause), reject снимает; админ pause/unpause.
5) Пассажирские страйки: resolved-жалобы no_show/unpaid/damage → пауза такси-заказов
   (механика B3, общий счётчик).
6) Попутка работает при ЛЮБОЙ паузе такси. Админ-права/IDOR на всех новых ручках.
"""
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app import quality
from app.config import settings
from app.db import engine
from app.models import (
    DriverProfile, InstantOrder, InstantOrderStatus as S, Rating, Report, UserRole,
)
from app.timeutil import utcnow

ORIG = (52.591, 58.317)    # Баймак — точка А
DEST = (52.716, 58.664)    # Сибай — точка Б (~30 км)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def pushes(monkeypatch):
    """Перехват уведомлений качества — обоих видов.

    Совет о рейтинге остаётся голым пушем: не дошёл — не беда, завтра будет новый. А жалоба
    и пауза такси теперь идут записью в Центр уведомлений: это наказание, человек обязан
    узнать о нём, даже если пуш не дошёл (аудит 2026-08-08, волна 20). Тестам важно
    «человеку сказали», поэтому собираем оба канала в один список.
    """
    sent: list[tuple[int, str]] = []
    monkeypatch.setattr("app.services.send_push",
                        lambda session, uid, title, body, data=None: sent.append((uid, title)))
    monkeypatch.setattr(
        quality, "push_notification",
        lambda session, uid, ntype, title_ru, title_ba, body_ru, body_ba, **kw:
            sent.append((uid, title_ru)))
    return sent


@pytest.fixture
def admin_tg(monkeypatch):
    """Перехват Telegram-уведомлений админу (quality импортирует лениво из services)."""
    sent: list[str] = []
    monkeypatch.setattr("app.services.notify_admin_telegram",
                        lambda text, reply_markup=None: sent.append(text))
    return sent


# ------------------------------ helpers ------------------------------
def _driver_online(client, user_factory, name="QDrv"):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    return d


def _hb(client, d, coord=ORIG):
    return client.post("/instant/presence", headers=d["auth"], json={"lat": coord[0], "lng": coord[1]})


def _order_body(frm=ORIG, to=DEST):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай"}


def _ensure_profile(user_id: int) -> None:
    """DriverProfile создаёт /driver/online — в БД-хелперах заводим сами."""
    with Session(engine) as s:
        if s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first() is None:
            s.add(DriverProfile(user_id=user_id))
            s.commit()


def _done_order(driver_id: int, passenger_id: int, price: int = 200) -> int:
    """Завершённый заказ напрямую в БД (для оценок/жалоб без полного флоу)."""
    _ensure_profile(driver_id)
    with Session(engine) as s:
        o = InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id,
            from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
            status=S.done, price_estimate=price, price_final=price, done_at=utcnow(),
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def _profile(user_id: int) -> DriverProfile | None:
    with Session(engine) as s:
        return s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()


def _report(client, reporter, target=None, category="rude", order_id=None, reason=""):
    body = {"category": category, "reason": reason}
    if target is not None:
        body["target_user_id"] = target["id"]
    if order_id is not None:
        body["order_id"] = order_id
    return client.post("/reports", headers=reporter["auth"], json=body)


def _resolve(client, admin, report_id, keep_pause=False, resolution="подтверждено"):
    return client.post(f"/admin/reports/{report_id}/resolve", headers=admin["auth"],
                       json={"resolution": resolution, "keep_pause": keep_pause})


# ============================ 1. Жалобы: категории + привязка ============================
def test_report_with_order_participant_target_derived(client, user_factory):
    """Пассажир жалуется по заказу: цель (водитель) вычисляется сервером, категория пишется."""
    d = user_factory("RepDrv1", role=UserRole.driver)
    pax = user_factory("RepPax1")
    oid = _done_order(d["id"], pax["id"])
    r = _report(client, pax, category="dirty_car", order_id=oid, reason="салон грязный")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["category"] == "dirty_car" and body["status"] == "new"
    with Session(engine) as s:
        rep = s.get(Report, body["id"])
        assert rep.target_user_id == d["id"] and rep.order_id == oid
        assert rep.reason == "салон грязный"          # свободный текст (детали) остаётся


def test_report_driver_side_and_stranger_403(client, user_factory):
    """Водитель жалуется на пассажира по заказу; посторонний по тому же заказу — 403."""
    d = user_factory("RepDrv2", role=UserRole.driver)
    pax = user_factory("RepPax2")
    stranger = user_factory("RepStr2")
    oid = _done_order(d["id"], pax["id"])
    ok = _report(client, d, category="no_show", order_id=oid)
    assert ok.status_code == 200
    with Session(engine) as s:
        assert s.get(Report, ok.json()["id"]).target_user_id == pax["id"]
    assert _report(client, stranger, category="rude", order_id=oid).status_code == 403


def test_report_target_mismatch_400_and_self_400(client, user_factory):
    d = user_factory("RepDrv3", role=UserRole.driver)
    pax = user_factory("RepPax3")
    other = user_factory("RepOther3")
    oid = _done_order(d["id"], pax["id"])
    # target передан, но не совпадает со второй стороной заказа → 400.
    r = client.post("/reports", headers=pax["auth"],
                    json={"category": "rude", "order_id": oid, "target_user_id": other["id"]})
    assert r.status_code == 400
    # на себя нельзя (прежнее поведение).
    assert _report(client, pax, target=pax).status_code == 400
    # без цели и без привязки → 400.
    assert client.post("/reports", headers=pax["auth"], json={"category": "rude"}).status_code == 400


def test_report_old_body_still_works_default_category(client, user_factory):
    """Совместимость: старое тело {target_user_id, reason} → category=other."""
    a = user_factory("OldRep")
    b = user_factory("OldTgt")
    r = client.post("/reports", headers=a["auth"], json={"target_user_id": b["id"], "reason": "плохо"})
    assert r.status_code == 200
    assert r.json()["category"] == "other"


def test_report_unknown_category_422(client, user_factory):
    a = user_factory("BadCatRep")
    b = user_factory("BadCatTgt")
    r = client.post("/reports", headers=a["auth"],
                    json={"target_user_id": b["id"], "category": "nonsense"})
    assert r.status_code == 422   # закрытый перечень (Literal)


# ============================ 2. Анонимность ============================
def test_report_response_and_restrictions_have_no_reporter(client, user_factory, pushes):
    """Ответ автору без reporter-полей; цель в /me/restrictions не видит автора."""
    d = user_factory("AnonDrv", role=UserRole.driver)
    pax = user_factory("AnonPax")
    oid = _done_order(d["id"], pax["id"])
    r = _report(client, pax, category="kicked_out", order_id=oid)   # тяжёлая → пауза цели
    assert r.status_code == 200
    assert "reporter_id" not in r.json() and "reporter_name" not in r.json()
    # Пуш цели ушёл (анонимный — только категория).
    assert any(uid == d["id"] for uid, _ in pushes)
    # Цель видит ограничение, но НИ одного упоминания автора.
    res = client.get("/me/restrictions", headers=d["auth"])
    assert res.status_code == 200
    payload = res.json()
    assert payload["items"], payload
    flat = str(payload)
    assert "reporter" not in flat and "AnonPax" not in flat
    item = payload["items"][0]
    assert item["kind"] == "taxi_pause" and item["category"] == "kicked_out"


def test_admin_reports_admin_only_and_shows_author(client, user_factory):
    a = user_factory("AdmRep1")
    b = user_factory("AdmTgt1")
    admin = user_factory("QAdmin1", role=UserRole.admin)
    _report(client, a, target=b, category="late")
    assert client.get("/admin/reports", headers=a["auth"]).status_code == 403
    rows = client.get("/admin/reports", headers=admin["auth"]).json()
    row = next(x for x in rows if x["target_name"] == "AdmTgt1")
    assert row["reporter_name"] == "AdmRep1"          # автора видит ТОЛЬКО админ
    assert row["category"] == "late" and row["status"] == "new"
    # Фильтры status/category.
    filtered = client.get("/admin/reports?category=late&status=new", headers=admin["auth"]).json()
    assert all(x["category"] == "late" and x["status"] == "new" for x in filtered)


# ============================ 3. Оценки заказов ============================
def test_rate_order_both_sides_aggregate_and_anonymous(client, user_factory):
    d = user_factory("RateDrv", role=UserRole.driver)
    pax = user_factory("RatePax")
    oid = _done_order(d["id"], pax["id"])
    # Пассажир → водитель.
    r = client.post(f"/instant/orders/{oid}/rate", headers=pax["auth"], json={"stars": 4})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ratee_id"] == d["id"] and body["rating"] == 4.0 and body["count"] == 1
    assert "rater_id" not in body                      # кто поставил — не раскрывается
    assert _profile(d["id"]).rating == 4.0             # агрегат учёл оценку заказа
    # Водитель → пассажир.
    r2 = client.post(f"/instant/orders/{oid}/rate", headers=d["auth"], json={"stars": 5})
    assert r2.status_code == 200 and r2.json()["ratee_id"] == pax["id"]


def test_rate_order_unique_per_rater_updates(client, user_factory):
    d = user_factory("RateDrv2", role=UserRole.driver)
    pax = user_factory("RatePax2")
    oid = _done_order(d["id"], pax["id"])
    assert client.post(f"/instant/orders/{oid}/rate", headers=pax["auth"], json={"stars": 2}).status_code == 200
    assert client.post(f"/instant/orders/{oid}/rate", headers=pax["auth"], json={"stars": 5}).status_code == 200
    with Session(engine) as s:
        rows = s.exec(select(Rating).where(Rating.order_id == oid, Rating.rater_id == pax["id"])).all()
        assert len(rows) == 1 and rows[0].stars == 5   # одна оценка на (rater, order), повтор обновил


def test_rate_order_guards(client, user_factory):
    d = user_factory("RateDrv3", role=UserRole.driver)
    pax = user_factory("RatePax3")
    stranger = user_factory("RateStr3")
    oid = _done_order(d["id"], pax["id"])
    assert client.post(f"/instant/orders/{oid}/rate", headers=stranger["auth"], json={"stars": 5}).status_code == 403
    # Незавершённый заказ оценить нельзя.
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.status = S.onboard
        s.add(o)
        s.commit()
    assert client.post(f"/instant/orders/{oid}/rate", headers=pax["auth"], json={"stars": 5}).status_code == 409
    assert client.post("/instant/orders/9999999/rate", headers=pax["auth"], json={"stars": 5}).status_code == 404


# ============================ 4. Лестница ============================
def test_low_rating_matcher_penalty_in_score(client, user_factory):
    """🟠 rating < matcher_low_rating → score минус matcher_penalty_low_rating."""
    prof_ok = DriverProfile(user_id=1, rating=settings.matcher_low_rating)        # ровно порог — без штрафа
    prof_low = DriverProfile(user_id=2, rating=settings.matcher_low_rating)
    base = isv._score({1: prof_ok}, 1, 1.0)
    prof_low.rating = settings.matcher_low_rating - 0.1
    low = isv._score({2: prof_low}, 2, 1.0)
    expected_drop = settings.matcher_penalty_low_rating + settings.instant_w_rating * 0.1
    assert base - low == pytest.approx(expected_drop)


def test_low_rating_advice_push_deduped(client, user_factory, pushes):
    """🟡 rating < 4.8 → один мягкий пуш-совет, повтор в ту же неделю не шлётся."""
    d = user_factory("AdvDrv", role=UserRole.driver)
    pax = user_factory("AdvPax")
    pax2 = user_factory("AdvPax2")
    oid = _done_order(d["id"], pax["id"])
    oid2 = _done_order(d["id"], pax2["id"])
    assert client.post(f"/instant/orders/{oid}/rate", headers=pax["auth"], json={"stars": 3}).status_code == 200
    advice = [t for uid, t in pushes if uid == d["id"] and t == "Совет от Юлдаша"]
    assert len(advice) == 1
    assert client.post(f"/instant/orders/{oid2}/rate", headers=pax2["auth"], json={"stars": 3}).status_code == 200
    advice = [t for uid, t in pushes if uid == d["id"] and t == "Совет от Юлдаша"]
    assert len(advice) == 1                            # дедуп: не чаще 1/нед
    # Неделя прошла → можно снова.
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        p.low_rating_advice_at = utcnow() - timedelta(days=settings.quality_advice_interval_days + 1)
        s.add(p)
        s.commit()
    assert client.post(f"/instant/orders/{oid2}/rate", headers=pax2["auth"], json={"stars": 2}).status_code == 200
    advice = [t for uid, t in pushes if uid == d["id"] and t == "Совет от Юлдаша"]
    assert len(advice) == 2


def test_three_resolved_reports_auto_pause_gates_taxi_not_pooling(
        client, user_factory, fake_redis, pushes):
    """🔴 3 resolved за 30 дн → пауза такси 72ч: presence 403, offer None, accept 403;
    ПОПУТКА (POST /rides) работает."""
    d = _driver_online(client, user_factory, "LadderDrv")
    admin = user_factory("LadderAdmin", role=UserRole.admin)
    assert _hb(client, d).status_code == 200           # до паузы presence работает
    for i in range(settings.quality_pause_reports):
        pax = user_factory(f"LadderPax{i}")
        oid = _done_order(d["id"], pax["id"])
        rid = _report(client, pax, category="rude", order_id=oid).json()["id"]
        assert _resolve(client, admin, rid).status_code == 200
    prof = _profile(d["id"])
    assert prof.taxi_paused_until is not None and prof.taxi_pause_reason == "reports"
    left_h = (prof.taxi_paused_until - utcnow()).total_seconds() / 3600
    assert settings.quality_pause_hours - 1 < left_h <= settings.quality_pause_hours
    assert any(uid == d["id"] and t == "Такси на паузе" for uid, t in pushes)
    # Гейт: presence/offer/accept.
    assert _hb(client, d).status_code == 403
    assert client.get("/instant/driver/offer", headers=d["auth"]).json() == {"offer": None}
    pax = user_factory("LadderPaxAcc")
    oid = _done_order(d["id"], pax["id"])
    with Session(engine) as s:                          # свежий «оффер» этому водителю
        o = s.get(InstantOrder, oid)
        o.status = S.offered
        o.driver_id = None
        o.current_offer_driver_id = d["id"]
        o.offer_expires_at = utcnow() + timedelta(seconds=60)
        s.add(o)
        s.commit()
    assert client.post(f"/instant/orders/{oid}/accept", headers=d["auth"], json={}).status_code == 403
    # Попутка работает при любой паузе такси.
    from app.timeutil import utcnow as real_utcnow
    ride = {"from_city": "Сибай", "to_city": "Баймак",
            # Приложение шлёт МЕСТНОЕ время (Уфа = UTC+5), сервер сам приводит к UTC.
            # Прислать сюда UTC — значит отправить время на пять часов назад:
            # сервер примет его за местное, и поездка окажется в прошлом.
            "depart_at": (real_utcnow() + timedelta(hours=2)
                          + timedelta(hours=settings.local_tz_offset_hours)).isoformat(),
            "seats_total": 2, "price": 300}
    assert client.post("/rides", headers=d["auth"], json=ride).status_code == 200


def test_severe_report_immediate_pause_and_telegram_resolve_keep_or_release(
        client, user_factory, pushes, admin_tg):
    """⛔ Тяжёлая: мгновенный Telegram + пауза до разбора; resolve keep_pause=False снимает."""
    d = user_factory("SevDrv", role=UserRole.driver)
    pax = user_factory("SevPax")
    admin = user_factory("SevAdmin", role=UserRole.admin)
    oid = _done_order(d["id"], pax["id"])
    rid = _report(client, pax, category="dangerous_driving", order_id=oid).json()["id"]
    assert any("Тяжёлая жалоба" in t for t in admin_tg)   # админу — сразу
    prof = _profile(d["id"])
    assert prof.taxi_pause_reason == "review" and prof.taxi_paused_until is not None
    # «До разбора» в /me/restrictions без даты-через-10-лет.
    item = client.get("/me/restrictions", headers=d["auth"]).json()["items"][0]
    assert item["reason"] == "review" and item["until"] is None
    # Разбор: жалоба подтверждена, но паузу решили снять (1 resolved — лестница ещё не 🔴).
    assert _resolve(client, admin, rid, keep_pause=False).status_code == 200
    prof = _profile(d["id"])
    assert prof.taxi_paused_until is None and prof.taxi_pause_reason is None


def test_severe_resolve_keep_pause_converts_to_timed(client, user_factory, pushes, admin_tg):
    d = user_factory("SevKeepDrv", role=UserRole.driver)
    pax = user_factory("SevKeepPax")
    admin = user_factory("SevKeepAdmin", role=UserRole.admin)
    oid = _done_order(d["id"], pax["id"])
    rid = _report(client, pax, category="safety_threat", order_id=oid).json()["id"]
    assert _resolve(client, admin, rid, keep_pause=True).status_code == 200
    prof = _profile(d["id"])
    assert prof.taxi_paused_until is not None
    left_h = (prof.taxi_paused_until - utcnow()).total_seconds() / 3600
    assert left_h <= settings.quality_pause_hours       # не «вечная», а честная таймерная


def test_severe_reject_releases_review_pause(client, user_factory, pushes, admin_tg):
    d = user_factory("SevRejDrv", role=UserRole.driver)
    pax = user_factory("SevRejPax")
    admin = user_factory("SevRejAdmin", role=UserRole.admin)
    oid = _done_order(d["id"], pax["id"])
    rid = _report(client, pax, category="kicked_out", order_id=oid).json()["id"]
    assert _profile(d["id"]).taxi_pause_reason == "review"
    assert client.post(f"/admin/reports/{rid}/reject", headers=admin["auth"], json={}).status_code == 200
    prof = _profile(d["id"])
    assert prof.taxi_paused_until is None               # отклонили → не наказываем
    with Session(engine) as s:
        assert s.get(Report, rid).status == "rejected"


def test_admin_quality_pause_unpause_and_rights(client, user_factory):
    d = user_factory("PauseDrv", role=UserRole.driver)
    _ensure_profile(d["id"])
    admin = user_factory("PauseAdmin", role=UserRole.admin)
    mortal = user_factory("PauseMortal")
    # IDOR: не-админ не может ставить/снимать паузу и разбирать жалобы.
    assert client.post(f"/admin/quality/{d['id']}/pause", headers=mortal["auth"],
                       json={"hours": 5}).status_code == 403
    assert client.post(f"/admin/quality/{d['id']}/unpause", headers=mortal["auth"]).status_code == 403
    assert client.post("/admin/reports/1/resolve", headers=mortal["auth"],
                       json={"resolution": "x"}).status_code == 403
    assert client.post("/admin/reports/1/reject", headers=mortal["auth"], json={}).status_code == 403
    # Админ: пауза → продление → снятие. На пассажира (без профиля водителя) — 404.
    r = client.post(f"/admin/quality/{d['id']}/pause", headers=admin["auth"], json={"hours": 48})
    assert r.status_code == 200 and r.json()["reason"] == "admin"
    assert _profile(d["id"]).taxi_paused_until is not None
    assert client.post(f"/admin/quality/{d['id']}/unpause", headers=admin["auth"]).status_code == 200
    assert _profile(d["id"]).taxi_paused_until is None
    assert client.post(f"/admin/quality/{mortal['id']}/pause", headers=admin["auth"],
                       json={"hours": 5}).status_code == 404
    # Разбор несуществующей жалобы — 404.
    assert client.post("/admin/reports/9999999/resolve", headers=admin["auth"],
                       json={"resolution": "x"}).status_code == 404


# ============================ 5. Пассажирские страйки за жалобы ============================
def test_passenger_no_show_reports_pause_orders_pooling_ok(client, user_factory, pushes):
    """3 resolved no_show-жалобы на пассажира → такси-заказы на паузе (B3-механика);
    /me/restrictions показывает orders_pause; попутка не затронута (бронь — отдельный поток)."""
    pax = user_factory("StrikePax")
    admin = user_factory("StrikeAdmin", role=UserRole.admin)
    for i in range(settings.strike_limit):
        d = user_factory(f"StrikeDrv{i}", role=UserRole.driver)
        oid = _done_order(d["id"], pax["id"])
        rid = _report(client, d, category="no_show", order_id=oid).json()["id"]
        assert _resolve(client, admin, rid).status_code == 200
    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body())
    assert r.status_code == 403
    assert "пауз" in r.json()["detail"].lower()
    items = client.get("/me/restrictions", headers=pax["auth"]).json()["items"]
    assert any(it["kind"] == "orders_pause" for it in items)


def test_restrictions_empty_when_clean(client, user_factory):
    u = user_factory("CleanUser")
    payload = client.get("/me/restrictions", headers=u["auth"]).json()
    assert payload["items"] == []
    assert client.get("/me/restrictions").status_code in (401, 403)   # без токена нельзя
