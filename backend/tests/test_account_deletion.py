# -*- coding: utf-8 -*-
"""Полное удаление аккаунта (152-ФЗ + требование Google Play) — от личности не остаётся следов.

Ключевой инвариант: после delete_user_account ни одна строка ни в одной таблице не ссылается
на удалённого пользователя (любая колонка с FK на user.id → 0 строк с этим uid). Проверка
ГЕНЕРИЧЕСКАЯ (интроспекция метаданных) — она сама поймает НОВУЮ таблицу, если её забыли
добавить в удаление. На Postgres тест ещё и доказывает FK-безопасный порядок удаления.

ВАЖНО (аудит 2026-08-03): «нет следов» ≠ «нет строк». Разборы, где есть ПОСТРАДАВШАЯ вторая
сторона — жалобы (Report) и споры «Справедливости» (Incident) — не стираются, а обезличиваются:
ссылка на удалённого и его текст уходят, а сам факт, решение админа и улики второй стороны
живут дальше. Иначе нарушитель одним тапом уничтожал доказательства против себя.
"""
from datetime import date, timedelta

from sqlalchemy import text as satext
from sqlmodel import Session, select

from app import models as M
from app.account import delete_user_account
from app.db import engine
from app.models import SQLModel, User, UserRole
from app.timeutil import utcnow


def _user_ref_columns() -> list:
    """Все (таблица, колонка) с внешним ключом на `user.id` — по метаданным, а не по списку."""
    out = []
    for table in SQLModel.metadata.sorted_tables:
        for col in table.columns:
            for fk in col.foreign_keys:
                if fk.column.table.name == "user" and fk.column.name == "id":
                    out.append((table.name, col.name))
    return out


def _tables_holding(uid: int) -> set:
    """Таблицы, где СЕЙЧАС есть хоть одна строка с этим uid."""
    hit = set()
    with Session(engine) as s:
        for tname, cname in _user_ref_columns():
            n = s.execute(
                satext(f'SELECT COUNT(*) FROM "{tname}" WHERE "{cname}" = :u'), {"u": uid},
            ).scalar()
            if n:
                hit.add(tname)
    return hit


def _residual_user_refs(uid: int) -> list:
    """Все (таблица.колонка) с FK на user.id, где ещё остались строки с uid."""
    hits = []
    with Session(engine) as s:
        for table in SQLModel.metadata.sorted_tables:
            for col in table.columns:
                for fk in col.foreign_keys:
                    if fk.column.table.name == "user" and fk.column.name == "id":
                        n = s.execute(
                            satext(f'SELECT COUNT(*) FROM "{table.name}" WHERE "{col.name}" = :u'),
                            {"u": uid},
                        ).scalar()
                        if n:
                            hits.append(f"{table.name}.{col.name}={n}")
    return hits


def test_delete_account_leaves_no_residual_anywhere(client, user_factory):
    """Заселяем пользователя во ВСЕ новые user-таблицы (такси/курьер/деньги/бизнес/промо),
    удаляем аккаунт → ноль остатков нигде + сам юзер удалён + чужой пользователь цел."""
    u = user_factory("ToDelete", role=UserRole.driver)
    uid = u["id"]
    other = user_factory("Other")
    oid = other["id"]

    with Session(engine) as s:
        user = s.get(User, uid)
        phone = user.phone
        # --- сенсит. заявки (ИНН/паспортные), профили, расписание ---
        # TaxiApplication уже создан user_factory для роли driver (одна на юзера) — проверяем ЕГО удаление.
        s.add(M.CourierApplication(user_id=uid, transport="car", selfie_url="secure/docs/x.jpg"))
        s.add(M.CourierProfile(user_id=uid))
        s.add(M.DriverSchedule(driver_id=uid, from_city="A", to_city="B"))
        s.add(M.TaxiWorkDay(driver_id=uid, day=date(2026, 7, 13)))
        # Предрейсовые подтверждения (580-ФЗ): заявления человека о самом себе — уходят с аккаунтом.
        s.add(M.PreTripCheck(driver_id=uid, day=date(2026, 7, 13),
                             health_ok=True, car_ok=True, no_alcohol=True))
        # --- уведомления, подписки, рефералы, бан устройства ---
        s.add(M.Notification(user_id=uid, ntype="test", title="t", body="b"))
        s.add(M.RouteWatch(user_id=uid, from_city="A", to_city="B", expires_at=utcnow() + timedelta(days=7)))
        s.add(M.ReferralBonus(referrer_id=uid, invited_user_id=oid))
        s.add(M.DeviceBan(user_id=uid, device_id="dev-x"))
        # --- финансы (без заказа) ---
        s.add(M.LedgerEntry(driver_id=uid, kind="earn", amount_kop=100))
        s.add(M.CommissionDebt(driver_id=uid, amount_kop=100))
        # --- такси-заказ + дети по order_id ---
        order = M.InstantOrder(passenger_id=uid, from_lat=54.0, from_lng=55.0, to_lat=54.1, to_lng=55.1)
        contact = M.TrustedContact(user_id=uid, name="Мама", phone="+79990001177")
        s.add(order); s.add(contact); s.commit(); s.refresh(order); s.refresh(contact)
        s.add(M.CommissionDebt(driver_id=uid, order_id=order.id, amount_kop=200))
        s.add(M.LedgerEntry(driver_id=uid, order_id=order.id, kind="earn", amount_kop=200))
        s.add(M.TripShare(order_id=order.id, contact_id=contact.id, token="del-order-share-token-123456"))
        # Причины отказа от оффера: МОЯ и ЧУЖАЯ по МОЕМУ заказу. Вторая — ключевая: по driver_id
        # она не ловится, но держит внешний ключ на заказ, который сейчас удалится. Без явного
        # гарда по order_id удаление аккаунта падало бы на Postgres, то есть не работало бы
        # вообще (152-ФЗ). На SQLite ключи не проверяются — тест поймает это только на Postgres.
        s.add(M.OfferDecline(order_id=order.id, driver_id=uid, reason="far"))
        s.add(M.OfferDecline(order_id=order.id, driver_id=oid, reason="cheap"))
        # --- посылка + рейтинг курьера по parcel_id + чат отправитель ↔ курьер ---
        parcel = M.ParcelDelivery(sender_id=uid, from_city="A", to_city="B", courier_id=oid)
        s.add(parcel); s.commit(); s.refresh(parcel)
        s.add(M.Rating(parcel_id=parcel.id, rater_id=oid, ratee_id=uid, stars=5))
        # Сообщения по посылке: моё И курьера. Второе — ключевое: оно держит FK на посылку,
        # но по sender_id не ловится, поэтому без явного гарда по parcel_id удаление аккаунта
        # падало бы на Postgres (посылка удаляется, сообщение курьера на неё ещё ссылается).
        s.add(M.Message(parcel_id=parcel.id, sender_id=uid, text="Оставь у соседей"))
        s.add(M.Message(parcel_id=parcel.id, sender_id=oid, text="Понял, буду через час"))
        # --- бизнес: партнёр → купон → погашение ---
        partner = M.Partner(owner_id=uid, name="Biz", city="Уфа")
        s.add(partner); s.commit(); s.refresh(partner)
        coupon = M.Coupon(partner_id=partner.id, title="−10%")
        s.add(coupon); s.commit(); s.refresh(coupon)
        s.add(M.CouponRedemption(user_id=uid, coupon_id=coupon.id, code="CPN123"))
        # --- промокампания: код → погашение ---
        promo = M.PromoCode(owner_id=uid, code="MYCODE")
        s.add(promo); s.commit(); s.refresh(promo)
        # used_order_id — FK на такси-заказ (промо-скидка потрачена на нём). Без снятия этой
        # ссылки delete(InstantOrder) падает на Postgres и аккаунт становится неудаляемым.
        s.add(M.PromoRedemption(user_id=uid, promo_id=promo.id,
                                discount_kop=20000, used_order_id=order.id))
        # --- лист ожидания по телефону (удаляется по phone юзера) ---
        s.add(M.WaitlistEntry(phone=phone))
        s.commit()

        # --- «Справедливость»: споры (я — сторона; по моей броне; решённый МНОЙ чужой) + профиль ---
        ride = M.Ride(driver_id=uid, from_city="A", to_city="B", depart_at=utcnow())
        s.add(ride); s.commit(); s.refresh(ride)
        my_booking = M.Booking(ride_id=ride.id, passenger_id=oid, status=M.BookingStatus.done)
        s.add(my_booking); s.commit(); s.refresh(my_booking)
        inc_against_me = M.Incident(booking_id=my_booking.id, reporter_id=oid, respondent_id=uid,
                                    type="rude", description="Нахамил в дороге",
                                    evidence_urls="/secure/evidence/victim.jpg")
        inc_by_me = M.Incident(reporter_id=uid, respondent_id=oid, type="harassment",
                               description="Моя версия", evidence_urls="/secure/evidence/mine.jpg",
                               respondent_statement="Версия второй стороны",
                               respondent_evidence_urls="/secure/evidence/other.jpg")
        s.add(inc_against_me); s.add(inc_by_me); s.commit()
        s.refresh(inc_against_me); s.refresh(inc_by_me)
        inc_against_me_id, inc_by_me_id = inc_against_me.id, inc_by_me.id
        s.add(M.SafetyProfile(user_id=uid, strikes=1))
        # Чужой спор, решённый удаляемым как админом → должен ОСТАТЬСЯ, но resolved_by → NULL.
        other2 = M.User(phone=f"{phone}-o2", name="Other2", verified=True)
        s.add(other2); s.commit(); s.refresh(other2)
        foreign_inc = M.Incident(reporter_id=oid, respondent_id=other2.id, type="rude",
                                 status="resolved", resolved_by=uid)
        s.add(foreign_inc); s.commit(); s.refresh(foreign_inc)
        foreign_inc_id = foreign_inc.id
        # --- G1: трекинг-ссылка МОЕЙ посылки (contact_id=NULL → ловится только по parcel_id) ---
        s.add(M.TripShare(parcel_id=parcel.id, token="del-parcel-share-token-123456"))
        s.commit()
        parcel_id = parcel.id

        # --- обращение в поддержку + тред (сообщения user и admin) ---
        ticket = M.SupportTicket(user_id=uid, subject="Вопрос")
        s.add(ticket); s.commit(); s.refresh(ticket)
        s.add(M.SupportMessage(ticket_id=ticket.id, sender="user", body="Здравствуйте"))
        s.add(M.SupportMessage(ticket_id=ticket.id, sender="admin", body="Помогаем"))
        s.commit()
        ticket_id = ticket.id

        # --- ОСТАЛЬНЫЕ таблицы со ссылкой на пользователя (аудит 2026-08-08) ---
        # Раньше их тут не было, и проверка «не осталось следов» по ним ничего не значила.
        # Список держит самопроверка ниже: забудешь новую таблицу — тест покраснеет.
        s.add(M.RefreshToken(user_id=uid, token_hash="hash-of-refresh",
                             expires_at=utcnow() + timedelta(days=30)))
        s.add(M.DeviceToken(user_id=uid, token=f"fcm-{uid}"))
        s.add(M.DriverProfile(user_id=uid, car_make="Lada", car_number="А001АА102"))
        s.add(M.SavedPlace(user_id=uid, label="Дом", address="Уфа, Ленина 1"))
        s.add(M.RecentPlace(user_id=uid, address="Уфа, вокзал"))
        s.add(M.Consent(user_id=uid, kind="privacy"))
        s.add(M.UploadEvent(user_id=uid))
        s.add(M.Trust(user_id=uid, level=1, invited_by=oid))
        s.add(M.InviteCode(code=f"INV{uid}", owner_id=uid, uses_left=3))
        s.add(M.Block(user_id=uid, blocked_user_id=oid))
        s.add(M.AppReview(user_id=uid, name="ToDelete", city="Уфа", stars=5, text="Отлично"))
        s.add(M.SosEvent(user_id=uid, category="danger", note="Помогите"))
        s.add(M.Payment(user_id=uid, purpose="boost", tier="basic", method="sbp", amount_kop=10000))
        s.add(M.Ad(owner_id=uid, created_by=uid, partner_name="Biz", partner_contact="@biz",
                   title="Реклама", text="Текст", button="Открыть", target="https://x.ru",
                   image_url="", erid="erid-1", placements="profile", cities="Уфа"))
        # Жалоба на купон: таблица появилась 2026-08-08 вместе с модерацией витрины —
        # и в удаление аккаунта её тогда не добавили (ровно эту дыру ловит самопроверка).
        s.add(M.CouponReport(coupon_id=coupon.id, user_id=uid, reason="Скидки нет"))
        # Помеченный текст в журнале админа: таблица появилась вместе с очередью модерации,
        # и в удаление её снова не добавили — вторая находка той же самопроверки. Запись
        # говорит «этот человек писал телефон в открытом поле», то есть это его данные.
        s.add(M.TextFlag(user_id=uid, kind="contact", place="parcel", ref_id=parcel_id))
        # Жалоба на человека: и моя (reporter_id), и на меня (target_user_id).
        s.add(M.Report(reporter_id=uid, target_user_id=oid, reason="Не приехал"))
        s.add(M.Report(reporter_id=oid, target_user_id=uid, reason="Нахамил"))
        # Я как пассажир в ЧУЖОЙ поездке: своя бронь + заявка + отклик на чужую заявку.
        other_ride = M.Ride(driver_id=oid, from_city="C", to_city="D", depart_at=utcnow())
        s.add(other_ride); s.commit(); s.refresh(other_ride)
        s.add(M.Booking(ride_id=other_ride.id, passenger_id=uid, seats=1, price=100))
        my_req = M.RideRequest(passenger_id=uid, from_city="A", to_city="B", seats=1)
        other_req = M.RideRequest(passenger_id=oid, from_city="A", to_city="B", seats=1)
        s.add(my_req); s.add(other_req); s.commit(); s.refresh(other_req)
        s.add(M.RequestResponse(request_id=other_req.id, driver_id=uid, price=300, comment=""))
        # Кого-то пригласил я (self-FK `user.referred_by`) — эта ссылка тоже должна отвязаться,
        # иначе у приглашённого останется указатель на несуществующего человека.
        invited = s.get(User, oid)
        invited.referred_by = uid
        s.add(invited)
        s.commit()

        user = s.get(User, uid)

    # ⬇️ САМОПРОВЕРКА ПРИБОРА (аудит 2026-08-08). Главная проверка ниже ищет ОСТАТКИ строк.
    # Если таблицу забыли заселить, остатков в ней не будет никогда — и «зелено» будет значить
    # «мы туда не смотрели», а не «удаление работает». Ровно так проскочила `couponreport`:
    # таблица появилась в тот же день, в удаление её не добавили, и тест этого не заметил.
    # Поэтому сначала требуем ПОКРЫТИЕ: каждая таблица со ссылкой на пользователя должна быть
    # заселена этим тестом. Появилась новая — здесь и станет красным, до всякой дыры.
    seeded = _tables_holding(uid)
    all_tables = {t for t, _ in _user_ref_columns()}
    not_seeded = sorted(all_tables - seeded)
    assert not_seeded == [], (
        "тест не заселил таблицы со ссылкой на пользователя — по ним проверка удаления НИЧЕГО "
        f"не доказывает: {not_seeded}. Заведи строку выше и убедись, что удаление её сносит."
    )

    with Session(engine) as s:
        user = s.get(User, uid)
        delete_user_account(s, user)

    # Генеральная проверка: НИГДЕ нет ссылки на удалённого пользователя.
    residual = _residual_user_refs(uid)
    assert residual == [], f"после удаления остались ссылки на юзера: {residual}"

    # Поддержка: ни тикета, ни осиротевших сообщений (у SupportMessage нет FK на user —
    # проверяем явно, что тред стёрт вместе с тикетом).
    with Session(engine) as s:
        assert s.get(M.SupportTicket, ticket_id) is None
        orphan = s.exec(select(M.SupportMessage).where(M.SupportMessage.ticket_id == ticket_id)).all()
        assert orphan == []

    with Session(engine) as s:
        assert s.get(User, uid) is None                 # аккаунт удалён
        assert s.get(User, oid) is not None             # чужой пользователь цел
        # лист ожидания по телефону тоже вычищен (у него нет FK на user — проверяем отдельно)
        wl = s.exec(select(M.WaitlistEntry).where(M.WaitlistEntry.phone == phone)).first()
        assert wl is None
        # «Справедливость»: чужой спор жив, но ссылка «решил я» отвязана (данные чужие — не наши).
        fi = s.get(M.Incident, foreign_inc_id)
        assert fi is not None and fi.resolved_by is None
        # Спор ПРОТИВ меня НЕ исчез — обезличена только моя сторона. Заявление второй стороны,
        # её улики и суть разбора целы (иначе «удалить аккаунт» = стереть доказательства).
        against = s.get(M.Incident, inc_against_me_id)
        assert against is not None, "спор против удаляемого не должен исчезать вместе с ним"
        assert against.respondent_id is None                     # моей ссылки нет
        assert against.reporter_id == oid                        # заявитель на месте
        assert against.description == "Нахамил в дороге"         # текст жертвы цел
        assert against.evidence_urls == "/secure/evidence/victim.jpg"   # улики жертвы целы
        assert against.type == "rude"                            # суть спора цела
        assert against.booking_id is None                        # контекст удалён вместе с бронёй
        # Мой спор на другого тоже жив (вторая сторона — пострадавшая от МОЕЙ жалобы), но
        # мои личные данные из него вычищены.
        mine = s.get(M.Incident, inc_by_me_id)
        assert mine is not None
        assert mine.reporter_id is None and mine.respondent_id == oid
        assert mine.description == "" and mine.evidence_urls == ""      # мой текст и мои фото — стёрты
        assert mine.respondent_statement == "Версия второй стороны"     # объяснение второй стороны цело
        assert mine.respondent_evidence_urls == "/secure/evidence/other.jpg"
        # G1: шеринг посылки не осиротел (FK на parceldelivery, user-скан его не видит).
        ps = s.exec(select(M.TripShare).where(M.TripShare.parcel_id == parcel_id)).all()
        assert ps == []
        # Чат по посылке тоже вычищен целиком, включая сообщения ВТОРОЙ стороны: у них
        # sender_id чужой, поэтому generic-скан по user.id их не видит, а FK на удалённую
        # посылку они держат — на Postgres это ровно то, что роняло /me/delete.
        pm = s.exec(select(M.Message).where(M.Message.parcel_id == parcel_id)).all()
        assert pm == []


def test_accused_deleting_account_does_not_destroy_victims_case(client, user_factory, monkeypatch):
    """Атака «удалю аккаунт — и разбирать нечего».

    Сценарий: пассажирку обидели в поездке, она открыла спор с фотографиями, админ вынес
    решение. Обвинённый нажимает «удалить аккаунт». Раньше строка спора стиралась целиком —
    вместе с её заявлением, её фото и вердиктом. Теперь исчезает только ЕГО личность.
    """
    victim = user_factory("Жертва")
    accused = user_factory("Обвинённый", role=UserRole.driver)
    vid, aid = victim["id"], accused["id"]

    erased: list = []   # какие медиа реально пошли под нож
    monkeypatch.setattr("app.account._safe_unlink_media", lambda url: erased.append(url))

    with Session(engine) as s:
        inc = M.Incident(
            reporter_id=vid, respondent_id=aid, type="harassment",
            description="Приставал всю дорогу", evidence_urls="/secure/evidence/victim-photo.jpg",
            respondent_statement="Это неправда", respondent_evidence_urls="/secure/evidence/his.jpg",
            status="resolved", resolution="strike", fault="respondent",
            resolution_note="Разобрались: страйк обвинённому.", resolved_by=vid,
        )
        s.add(inc); s.commit(); s.refresh(inc)
        inc_id = inc.id
        s.add(M.SafetyProfile(user_id=aid, strikes=1))
        s.commit()
        delete_user_account(s, s.get(User, aid))

    with Session(engine) as s:
        kept = s.get(M.Incident, inc_id)
        assert kept is not None, "обвинённый не должен уносить с собой заявление жертвы"
        # Личности обвинённого нет — ни ссылки, ни его слов, ни его фото.
        assert kept.respondent_id is None
        assert kept.respondent_statement == "" and kept.respondent_evidence_urls == ""
        # Дело жертвы и решение админа целы.
        assert kept.reporter_id == vid
        assert kept.description == "Приставал всю дорогу"
        assert kept.evidence_urls == "/secure/evidence/victim-photo.jpg"
        assert kept.resolution == "strike" and kept.fault == "respondent"
        assert kept.resolution_note == "Разобрались: страйк обвинённому."
        # Профиль безопасности обвинённого ушёл вместе с аккаунтом (это его данные).
        assert s.exec(select(M.SafetyProfile).where(M.SafetyProfile.user_id == aid)).first() is None

    # Фото жертвы не тронуты (иначе улики уничтожал бы тот, против кого они собраны),
    # а фото обвинённого стёрты вместе с ним — и не остались сиротой на диске.
    assert "/secure/evidence/victim-photo.jpg" not in erased
    assert "/secure/evidence/his.jpg" in erased


def test_admin_can_resolve_incident_after_accused_deleted_account(client, user_factory):
    """Обвинённый удалил аккаунт до вердикта → админ всё равно может закрыть разбор.

    Наказывать некого, но решение должно записаться: это документ для жертвы (и для полиции,
    если дойдёт). Раньше такой строки просто не существовало, теперь она есть — и код разбора
    не должен на ней падать (наказание уходит в пустоту, а не в 500-ю)."""
    victim = user_factory("Жертва2")
    accused = user_factory("Обвинённый2", role=UserRole.driver)
    admin = user_factory("Админ", role=UserRole.admin)
    vid, aid = victim["id"], accused["id"]

    with Session(engine) as s:
        inc = M.Incident(reporter_id=vid, respondent_id=aid, type="harassment",
                         description="Версия жертвы", status="under_review")
        s.add(inc); s.commit(); s.refresh(inc)
        inc_id = inc.id
        delete_user_account(s, s.get(User, aid))

    r = client.post(f"/admin/incidents/{inc_id}/resolve",
                    json={"resolution": "strike", "fault": "respondent",
                          "note": "Виноват, но аккаунта уже нет.", "strike": True},
                    headers=admin["auth"])
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        kept = s.get(M.Incident, inc_id)
        assert kept.status == "resolved" and kept.resolution == "strike"
        assert kept.resolution_note == "Виноват, но аккаунта уже нет."
        # Страйк наложить не на кого — профиль-заглушку в БД не создаём.
        assert s.exec(select(M.SafetyProfile).where(M.SafetyProfile.user_id == 0)).first() is None

    # Админ-список споров тоже не должен падать на обезличенной стороне.
    lst = client.get("/admin/incidents", headers=admin["auth"])
    assert lst.status_code == 200, lst.text
    row = next(i for i in lst.json() if i["id"] == inc_id)
    assert row["respondent_id"] is None
    assert "далённ" in row["respondent_name"]      # «Удалённый аккаунт …»
