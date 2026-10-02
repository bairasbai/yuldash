"""N1 (P1, независимое ревью): «Подтвердить и оставить паузу такси» не должно ОСЛАБЛЯТЬ наказание.

Было (safety.py, admin_resolve_report, keep_pause=True): `quality.unpause_taxi()` безусловно
стирает ЛЮБУЮ паузу + шлёт «Такси снова доступно», затем `quality.pause_taxi(72ч,
reason="reports")` ставит короткую паузу под ДРУГОЙ причиной. Три беды разом:

1. Курьерская дверь (`quality.under_severe_review`) смотрит ТОЛЬКО на `reason=="review"` —
   смена причины на "reports" открывает платную доставку в ту же минуту, хотя опасное
   вождение только что ПОДТВЕРЖДЕНО админом.
2. `unpause_taxi()` не смотрит, есть ли ДРУГАЯ, ещё не разобранная тяжёлая жалоба (B) или
   более длинная РУЧНАЯ пауза админа — обе слетают к 72 часам вместе с паузой текущей жалобы.
3. `unpause_taxi()` сам шлёт пуш «пауза снята», хотя пауза остаётся — водитель читает «можно
   работать» и упирается в 403.

Правка: причину не меняем (дверь остаётся закрытой весь срок паузы), трогаем только СВОЮ
"review"-паузу, и только если других нерешённых тяжёлых жалоб на цель нет.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import DriverProfile, InstantOrder, InstantOrderStatus as S, Report, User, UserRole
from app.timeutil import utcnow
from app import quality


def _ensure_profile(uid: int) -> None:
    with Session(engine) as s:
        if not s.exec(select(DriverProfile).where(DriverProfile.user_id == uid)).first():
            s.add(DriverProfile(user_id=uid, verified=True))
            s.commit()


def _severe_report_pauses_driver(driver_id: int, reporter_id: int, category="dangerous_driving") -> int:
    """Тяжёлая жалоба, привязанная к настоящей (done) поездке — как в проде ставит паузу «до
    разбора». Возврат: id жалобы."""
    _ensure_profile(driver_id)
    with Session(engine) as s:
        order = InstantOrder(passenger_id=reporter_id, driver_id=driver_id, status=S.done,
                             price_estimate=300, price_final=300)
        s.add(order)
        s.commit()
        s.refresh(order)
        report = Report(reporter_id=reporter_id, target_user_id=driver_id, category=category,
                        order_id=order.id, status="new")
        s.add(report)
        s.commit()
        s.refresh(report)
        rid = report.id
    from app import quality as q
    with Session(engine) as s:
        rep = s.get(Report, rid)
        q.pause_taxi(s, driver_id, hours=None, reason=q.PAUSE_REASON_REVIEW)
    return rid


def test_подтвердить_и_оставить_не_открывает_курьерскую_дверь(client, user_factory):
    """Главное: после «подтвердить + оставить» дверь `under_severe_review` остаётся закрытой —
    ровно та проверка, на которой стоит и курьер, и посылка (courier.py:1064, parcels.py:142)."""
    drv = user_factory("N1Drv1", role=UserRole.driver)
    pax = user_factory("N1Pax1")
    admin = user_factory("N1Admin1", role=UserRole.admin)
    rid = _severe_report_pauses_driver(drv["id"], pax["id"])

    with Session(engine) as s:
        assert quality.under_severe_review(s, drv["id"]) is True, "контроль: пауза разбора стоит"

    r = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"],
                    json={"resolution": "confirmed", "keep_pause": True})
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        assert quality.under_severe_review(s, drv["id"]) is True, (
            "курьерская/посылочная дверь открылась сразу после ПОДТВЕРЖДЁННОГО опасного "
            "вождения — водитель в ту же минуту может брать платную доставку"
        )


def test_подтвердить_и_оставить_укорачивает_паузу_до_72_часов(client, user_factory):
    """Пауза не снимается вовсе («оставить» значит оставить), но и не остаётся вечной —
    переходит в честные quality_pause_hours (72ч) от момента решения."""
    drv = user_factory("N1Drv2", role=UserRole.driver)
    pax = user_factory("N1Pax2")
    admin = user_factory("N1Admin2", role=UserRole.admin)
    rid = _severe_report_pauses_driver(drv["id"], pax["id"])

    before = utcnow()
    r = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"],
                    json={"resolution": "confirmed", "keep_pause": True})
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        prof = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).first()
        assert prof.taxi_pause_reason == quality.PAUSE_REASON_REVIEW, (
            "причина паузы не должна меняться на 'reports' — иначе дверь откроется"
        )
        expected = before + timedelta(hours=quality.settings.quality_pause_hours)
        assert abs((prof.taxi_paused_until - expected).total_seconds()) < 30, (
            f"пауза должна стать ~72ч от решения, а не остаться вечной и не обнулиться: "
            f"{prof.taxi_paused_until}"
        )


def test_другая_открытая_тяжёлая_жалоба_не_даёт_укоротить_паузу(client, user_factory):
    """Если на того же водителя висит ВТОРАЯ, ещё не разобранная тяжёлая жалоба (B), «оставить»
    по первой (A) не должно укорачивать общую паузу — B всё ещё ждёт разбора человеком."""
    drv = user_factory("N1Drv3", role=UserRole.driver)
    pax_a = user_factory("N1PaxA3")
    pax_b = user_factory("N1PaxB3")
    admin = user_factory("N1Admin3", role=UserRole.admin)
    rid_a = _severe_report_pauses_driver(drv["id"], pax_a["id"], category="dangerous_driving")
    # Вторая тяжёлая жалоба (B) — ещё не разобрана.
    with Session(engine) as s:
        order_b = InstantOrder(passenger_id=pax_b["id"], driver_id=drv["id"], status=S.done,
                               price_estimate=300, price_final=300)
        s.add(order_b)
        s.commit()
        s.refresh(order_b)
        s.add(Report(reporter_id=pax_b["id"], target_user_id=drv["id"], category="kicked_out",
                    order_id=order_b.id, status="new"))
        s.commit()

    r = client.post(f"/admin/reports/{rid_a}/resolve", headers=admin["auth"],
                    json={"resolution": "confirmed", "keep_pause": True})
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        prof = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).first()
        # Пауза «до разбора» (REVIEW_PAUSE_DAYS, т.е. годы вперёд) НЕ должна была укоротиться
        # до 72ч, пока жалоба B открыта.
        far_future = utcnow() + timedelta(days=365)
        assert prof.taxi_paused_until > far_future, (
            "паузу укоротили до 72ч, хотя вторая тяжёлая жалоба ещё не разобрана"
        )
        assert prof.taxi_pause_reason == quality.PAUSE_REASON_REVIEW


def test_подтвердить_и_оставить_не_присылает_ложное_пауза_снята(client, user_factory, monkeypatch):
    """Старый код звал unpause_taxi(), который шлёт «Такси снова доступно» — неправда, пауза
    остаётся. Проверяем push_notification изнутри safety.py (прямой вызов, без Firebase)."""
    import app.routers.safety as safety

    calls = []
    monkeypatch.setattr(safety, "push_notification",
                        lambda session, uid, kind, t1, t2, b1, b2, **kw: calls.append((t1, b1)))
    drv = user_factory("N1Drv4", role=UserRole.driver)
    pax = user_factory("N1Pax4")
    admin = user_factory("N1Admin4", role=UserRole.admin)
    rid = _severe_report_pauses_driver(drv["id"], pax["id"])

    r = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"],
                    json={"resolution": "confirmed", "keep_pause": True})
    assert r.status_code == 200, r.text

    assert not any("снята" in (title + body).lower() or "асыҡ" in (title + body).lower()
                  for title, body in calls), (
        f"ушёл ложный пуш «пауза снята», хотя пауза осталась: {calls}"
    )


def test_более_длинную_ручную_паузу_админа_оставить_не_укорачивает(client, user_factory):
    """Контроль: если текущая пауза НЕ 'review' (например ручная 'admin' на год), «оставить»
    по НЕСВЯЗАННОЙ тяжёлой жалобе её вообще не трогает."""
    drv = user_factory("N1Drv5", role=UserRole.driver)
    pax = user_factory("N1Pax5")
    admin = user_factory("N1Admin5", role=UserRole.admin)
    # Админ сперва вручную ставит долгую паузу по совсем другому поводу.
    with Session(engine) as s:
        prof = DriverProfile(user_id=drv["id"], verified=True,
                             taxi_paused_until=utcnow() + timedelta(days=300),
                             taxi_pause_reason=quality.PAUSE_REASON_ADMIN)
        s.add(prof)
        s.commit()
    rid = _severe_report_pauses_driver(drv["id"], pax["id"])
    # _severe_report_pauses_driver уже заново вызвал pause_taxi(reason=review) — но pause_taxi
    # только УДЛИНЯЕТ, поэтому ручная годовая пауза (300 дней < REVIEW_PAUSE_DAYS) всё равно
    # станет review. Пересоздадим сценарий без этого побочного эффекта: уберём сам вызов,
    # имитируя "жалоба создана, но pause_taxi ещё не звали" — правим профиль обратно.
    with Session(engine) as s:
        prof = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).first()
        prof.taxi_pause_reason = quality.PAUSE_REASON_ADMIN
        prof.taxi_paused_until = utcnow() + timedelta(days=300)
        s.add(prof)
        s.commit()

    r = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"],
                    json={"resolution": "confirmed", "keep_pause": True})
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        prof = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).first()
        assert prof.taxi_pause_reason == quality.PAUSE_REASON_ADMIN, "ручная причина не должна подмениться"
        far_future = utcnow() + timedelta(days=200)
        assert prof.taxi_paused_until > far_future, "ручная годовая пауза не должна укоротиться до 72ч"
