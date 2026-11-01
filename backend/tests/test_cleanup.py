# -*- coding: utf-8 -*-
"""Ретеншен-чистка (app.cleanup): убирает эфемерное, бережёт важное.
Ключевое — бронь С рейтингом НЕ удаляется (репутацию не теряем), аккаунт цел."""
from datetime import timedelta

from sqlmodel import Session, select

from app import cleanup
from app.db import engine
from app.models import (
    Booking, BookingStatus, Message, OtpCode, Rating, RefreshToken, Ride, RideStatus, User,
)
from app.timeutil import utcnow


def _old(days):
    return utcnow() - timedelta(days=days)


def test_cleanup_purges_ephemeral_keeps_important(client, user_factory):
    uid = user_factory(name="Ретеншен")["id"]
    with Session(engine) as s:
        ride = Ride(driver_id=uid, from_city="A", to_city="B", depart_at=utcnow(),
                    status=RideStatus.active, created_at=_old(200))
        s.add(ride); s.commit(); s.refresh(ride)
        b_keep = Booking(ride_id=ride.id, passenger_id=uid, status=BookingStatus.done, created_at=_old(200))
        b_del = Booking(ride_id=ride.id, passenger_id=uid, status=BookingStatus.done, created_at=_old(200))
        s.add(b_keep); s.add(b_del); s.commit(); s.refresh(b_keep); s.refresh(b_del)
        s.add(Rating(booking_id=b_keep.id, rater_id=uid, ratee_id=uid, stars=5, created_at=_old(200)))
        old_msg = Message(booking_id=b_del.id, sender_id=uid, text="старое", created_at=_old(40))
        new_msg = Message(booking_id=b_keep.id, sender_id=uid, text="свежее", created_at=utcnow())
        s.add(old_msg); s.add(new_msg)
        s.add(OtpCode(phone="+79990001122", code="123456", created_at=_old(5), expires_at=_old(5)))
        s.add(RefreshToken(user_id=uid, token_hash="cleanup-dead", revoked=True,
                           expires_at=_old(5), created_at=_old(5)))
        s.commit()
        keep_b, del_b, old_m, new_m = b_keep.id, b_del.id, old_msg.id, new_msg.id

    cleanup.main()   # реальная чистка

    with Session(engine) as s:
        assert s.get(Message, old_m) is None                                   # старое сообщение вычищено
        assert s.get(Message, new_m) is not None                                # свежее осталось
        assert s.get(Booking, keep_b) is not None                               # бронь С рейтингом сохранена
        assert s.get(Booking, del_b) is None                                    # бронь без рейтинга удалена
        assert s.exec(select(RefreshToken).where(RefreshToken.token_hash == "cleanup-dead")).first() is None
        assert s.exec(select(OtpCode).where(OtpCode.phone == "+79990001122")).first() is None
        assert s.get(User, uid) is not None                                     # АККАУНТ ЦЕЛ
        assert s.exec(select(Rating).where(Rating.booking_id == keep_b)).first() is not None  # рейтинг цел


def test_cleanup_dry_run_deletes_nothing(client, user_factory):
    uid = user_factory(name="Сухой")["id"]
    with Session(engine) as s:
        # Реальная бронь (FK на Postgres обязателен — SQLite его игнорировал бы) + старое сообщение на ней.
        ride = Ride(driver_id=uid, from_city="A", to_city="B", depart_at=utcnow(),
                    status=RideStatus.active, created_at=_old(200))
        s.add(ride); s.commit(); s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=uid, status=BookingStatus.done, created_at=_old(200))
        s.add(b); s.commit(); s.refresh(b)
        m = Message(booking_id=b.id, sender_id=uid, text="dry-старое", created_at=_old(40))
        s.add(m); s.commit()
        mid = m.id
    try:
        cleanup.DRY = True     # сухой прогон: только считает, ничего не удаляет
        cleanup.main()
    finally:
        cleanup.DRY = False
    with Session(engine) as s:
        assert s.get(Message, mid) is not None   # сухой прогон ничего не удалил


def test_cleanup_media_sweeps_old_keeps_fresh(client):
    """Медиа-чистка через storage: старое фото удаляется (диск/S3), свежее — остаётся."""
    import os
    import time as _t

    from app.services import CHAT_DIR
    os.makedirs(CHAT_DIR, exist_ok=True)
    old_path = os.path.join(CHAT_DIR, "cleanup_old.jpg")
    fresh_path = os.path.join(CHAT_DIR, "cleanup_fresh.jpg")
    with open(old_path, "wb") as f:
        f.write(b"x" * 16)
    with open(fresh_path, "wb") as f:
        f.write(b"y" * 16)
    old_ts = _t.time() - (cleanup.MEDIA_DAYS + 5) * 86400
    os.utime(old_path, (old_ts, old_ts))                 # состарить mtime
    cleanup.main()
    assert not os.path.exists(old_path)                  # старое медиа вычищено
    assert os.path.exists(fresh_path)                    # свежее осталось
    os.remove(fresh_path)


def test_cleanup_batched_delete_removes_all(client, monkeypatch):
    """Батчинг: даже при маленьком чанке удаляются ВСЕ подходящие строки (несколько итераций)."""
    from app.models import OtpCode
    with Session(engine) as s:
        for i in range(5):
            s.add(OtpCode(phone=f"+7000000{i:04d}", code="000000", created_at=_old(5), expires_at=_old(5)))
        s.commit()
    monkeypatch.setattr(cleanup, "_BATCH", 2)            # форсируем несколько чанков (5 строк по 2)
    cleanup.main()
    with Session(engine) as s:
        left = s.exec(select(OtpCode).where(OtpCode.phone.like("+7000000%"))).all()
    assert left == []                                    # все старые OTP удалены, несмотря на чанки

# ---------------- Приватные доказательства: сирота уходит, улика живёт ----------------

def test_orphan_evidence_photo_is_deleted_but_live_one_stays(client, user_factory):
    """Фото споров и снимки границ ответственности по доставке лежат в приватной области,
    и ретеншен не трогал её НИКОГДА.

    Посылка старше 180 дней уходит по сроку, спор разрешается — а снимок с лицом, подъездом
    и содержимым коробки оставался на диске навсегда (аудит 2026-08-08, волна 11). Это против
    ст. 5 п. 7 152-ФЗ: хранить ровно столько, сколько нужно для цели.

    Проверяем ОБА конца правила: осиротевший файл удаляется, а тот, на который ещё ссылается
    живой спор, остаётся — сколько бы ему ни было лет. Иначе чистка съедала бы улики.
    """
    import os
    import time

    from app.models import Incident
    from app.storage import get_storage

    uid = user_factory(name="Сирота-фото")["id"]
    other = user_factory(name="Вторая сторона")["id"]
    storage = get_storage()

    orphan = f"{uid}_orphan_evidence.jpg"
    live = f"{uid}_live_evidence.jpg"
    for name in (orphan, live):
        storage.save(f"evidence/{name}", bytes([0xFF, 0xD8, 0xFF]) + b"test")

    # Живой спор ссылается на второй файл — он должен пережить чистку.
    with Session(engine) as s:
        s.add(Incident(reporter_id=uid, respondent_id=other, type="rude",
                       description="спор идёт", status="under_review",
                       evidence_urls=f"https://yulbash.ru/secure/evidence/{live}"))
        s.commit()

    # Состариваем оба файла: чистка смотрит на время изменения.
    old_ts = time.time() - (cleanup.MEDIA_DAYS + 5) * 86400
    for name in (orphan, live):
        path = os.path.join(storage._path(f"evidence/{name}"))   # локальный диск в тестах
        os.utime(path, (old_ts, old_ts))

    cleanup._clean_media()

    assert not storage.exists(f"evidence/{orphan}"), "осиротевшее фото осталось на диске навсегда"
    assert storage.exists(f"evidence/{live}"), "чистка съела улику живого спора"


def _поля_со_снимками(исходник: str) -> set:
    """Поля, куда код кладёт ССЫЛКУ НА СНИМОК, — по признаку, а не по одному слову в имени.

    Раньше признаком было просто «в имени есть photo или evidence». Волна 170 завела поле
    `docs_photo_due_at` — момент времени «до какого дня водитель работает, пока не принёс фото
    документа», — и сторож потребовал внести ЭТУ ДАТУ в список живых ссылок чистки. Дата
    к файлам отношения не имеет, а сторож её не отличал.

    Уточнение простое: имя, оканчивающееся на `_at`, — это момент времени, а не ссылка.
    Так сторож продолжает ловить настоящие поля со снимками и перестаёт цепляться к соседям
    с похожим словом внутри.
    """
    import re as _re
    найдено = {
        поле for _obj, поле in _re.findall(r"(\w+)\.(\w*(?:evidence|photo)\w*)\s*=", исходник)
    }
    return {поле for поле in найдено if not поле.endswith("_at")}


def test_сторож_снимков_отличает_дату_от_ссылки():
    """Проверка самого сторожа: слепой и придирчивый ломаются одинаково тихо.

    Слепой пропустит новое поле со снимком — и чистка съест улики живого спора. Придирчивый
    будет падать на каждом соседе с похожим именем, и его перестанут читать.
    """
    настоящие = "parcel.pickup_photo_url = url\n    inc.evidence_urls = csv\n"
    даты = "app.docs_photo_due_at = utcnow()\n    dp.car_photo_checked_at = utcnow()\n"

    поля = _поля_со_снимками(настоящие + даты)

    assert "pickup_photo_url" in поля, "сторож перестал видеть настоящее поле со снимком"
    assert "docs_photo_due_at" not in поля, "сторож принял дату за ссылку на файл"
    assert "car_photo_checked_at" not in поля, "сторож принял отметку времени за ссылку"


def test_every_evidence_field_is_known_to_the_cleaner():
    """Сторож прибора: чистка удаляет приватные снимки, на которые НЕ осталось ссылок.

    Значит её список «живых ссылок» обязан знать про КАЖДОЕ поле, куда код кладёт
    /secure/evidence-URL. Заведут пятое поле и забудут внести — чистка сочтёт эти снимки
    сиротами и удалит улики из живого спора. Цена ошибки здесь выше обычного, поэтому
    полноту сторожит тест, а не память.
    """
    import pathlib as _pl

    app_src = chr(10).join(f.read_text(encoding="utf-8") for f in _pl.Path("app").rglob("*.py"))
    cleaner = _pl.Path("app/cleanup.py").read_text(encoding="utf-8")

    # Поля, куда код реально ПИШЕТ снимок. Документы водителя/таксиста (docs/) исключаем:
    # их чистит только удаление аккаунта (580-ФЗ требует хранить, пока человек работает).
    docs_fields = {"license_url", "car_photo_url", "permit_photo_url", "osago_url",
                   "criminal_record_url", "selfie_url"}
    written = _поля_со_снимками(app_src) - docs_fields
    assert written, "разбор сломался — полей не нашлось вовсе"

    missing = sorted(f for f in written if f not in cleaner)
    assert not missing, (
        "чистка не знает про поля со снимками: " + ", ".join(missing) +
        ". Внеси их в _referenced_media_keys, иначе ретеншен удалит живые доказательства."
    )


# ── RS-03 А: зачистка точных точек встречи/высадки ──

def _ride_with_booking(s, *, status, depart_days_ago, confirmed=True):
    """Бронь с точкой встречи на рейсе с выездом N дней назад (отрицательное — в будущем)."""
    _ride_with_booking.seq = getattr(_ride_with_booking, "seq", 0) + 1
    u = User(name="MP Clean", phone=f"+7992{_ride_with_booking.seq % 10**7:07d}",
             role="passenger")
    s.add(u)
    s.commit()
    s.refresh(u)
    ride = Ride(driver_id=u.id, from_city="Сибай", to_city="Акъяр",
                depart_at=_old(depart_days_ago) if depart_days_ago >= 0
                else utcnow() + timedelta(days=-depart_days_ago),
                status=RideStatus.active)
    s.add(ride)
    s.commit()
    s.refresh(ride)
    b = Booking(ride_id=ride.id, passenger_id=u.id, status=status,
                confirmed_at=utcnow() if confirmed else None,
                meet_lat=52.7, meet_lng=58.6, meet_note="у школы", meet_proposal="")
    s.add(b)
    s.commit()
    s.refresh(b)
    return b.id


def _point_gone(bid: int) -> bool:
    with Session(engine) as s:
        b = s.get(Booking, bid)
        return (b.meet_lat is None and b.meet_note == "" and b.meet_landmark_id is None
                and b.meet_proposal == "")


def test_scrub_meeting_points_cancelled_without_confirm_goes_tonight(client):
    """Отменённая без подтверждения — точка стирается в ближайшую ночь (без ожидания 30 дней)."""
    with Session(engine) as s:
        bid = _ride_with_booking(s, status=BookingStatus.cancelled, depart_days_ago=-5, confirmed=False)
    assert cleanup.scrub_meeting_points() >= 1
    assert _point_gone(bid)


def test_scrub_meeting_points_done_after_30_days_from_depart_not_earlier(client):
    """Завершённая — через 30 дней от ВЫЕЗДА, даже если бронь закрылась позже (D9)."""
    with Session(engine) as s:
        fresh = _ride_with_booking(s, status=BookingStatus.done, depart_days_ago=10, confirmed=True)
        old = _ride_with_booking(s, status=BookingStatus.done, depart_days_ago=40, confirmed=True)
    n = cleanup.scrub_meeting_points()
    assert n >= 1
    assert not _point_gone(fresh), "свежая (10 дней от выезда) — точка на месте"
    assert _point_gone(old)


def test_scrub_meeting_points_keeps_open_sos_and_incident(client):
    """Открытый SOS / живой разбор / жалоба в разборе — точка нужна, не стираем."""
    from app.models import Incident, Report, SosEvent
    with Session(engine) as s:
        keep_sos = _ride_with_booking(s, status=BookingStatus.done, depart_days_ago=60, confirmed=True)
        s.add(SosEvent(user_id=1, booking_id=keep_sos, category="other", status="open"))
        keep_inc = _ride_with_booking(s, status=BookingStatus.done, depart_days_ago=60, confirmed=True)
        s.add(Incident(booking_id=keep_inc, type="harassment", reporter_role="passenger",
                       description="", status="open"))
        keep_rep = _ride_with_booking(s, status=BookingStatus.done, depart_days_ago=60, confirmed=True)
        s.add(Report(reporter_id=1, booking_id=keep_rep, category="other", status="new"))
        free = _ride_with_booking(s, status=BookingStatus.done, depart_days_ago=60, confirmed=True)
        s.commit()
    cleanup.scrub_meeting_points()
    assert not _point_gone(keep_sos) and not _point_gone(keep_inc) and not _point_gone(keep_rep)
    assert _point_gone(free)


def test_scrub_meeting_points_dry_run_changes_nothing(client, monkeypatch):
    monkeypatch.setattr(cleanup, "DRY", True)
    with Session(engine) as s:
        bid = _ride_with_booking(s, status=BookingStatus.done, depart_days_ago=60, confirmed=True)
    n = cleanup.scrub_meeting_points()
    assert n >= 1, "сухой прогон считает"
    assert not _point_gone(bid), "но ничего не стирает"
