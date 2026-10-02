"""leaf-1.3 — деньги: купонный маркетплейс «Скидки по пути» (app/routers/coupons.py).

Фокус группы «Деньги»:
  R1 — купон нельзя погасить дважды, даже двумя одновременными запросами (PostgreSQL);
  R2 — бизнес не может погасить чужой купон (чужой владелец → тот же 404, код не раскрывается);
  R3 — лимит «один купон на человека» не превышается даже под двумя одновременными активациями
       одного и того же пассажира (PostgreSQL) — единственная защита здесь — row-lock на
       Coupon (нет атомарного CAS-UPDATE, как у погашения), так что эта гонка проверяется
       ТОЛЬКО на настоящем Postgres: на SQLite её не ловит никто (FOR UPDATE — no-op);
  R4 — срок действия купона считается в едином времени: уфимское время из анкеты бизнеса
       (без явного пояса) конвертируется в UTC со сдвигом −5ч, и сравнение с «сейчас» идёт
       уже в UTC с обеих сторон; дата БЕЗ времени (как шлёт приложение) = конец суток по Уфе,
       а не начало (B-3, найдено независимым ревью);
  R5 — просроченный или снятый админом купон не гасится, даже если код уже выдан на руки
       (найдено независимым ревью, п.4);
  R6 — «осталось N» на витрине не должно врать: незавершённые брони (взяты, но ещё не
       погашены) съедают общий лимит ТАК ЖЕ, как считает сама активация (найдено независимым
       ревью, п.4 — «вечная бронь»);
  R7 — лимит 50 купонов на бизнес не пробивается даже под двумя одновременными «создать
       купон» на границе лимита (PostgreSQL; найдено ревью круга 2, почина — круг 3);
  R8 — забытая (reserved) бронь не держит лимит вечно: истекает вместе со сроком купона, а
       для бессрочного купона — через RESERVATION_TTL_DAYS после самой брони (решение
       ведущего, круг 3 — «вечная бронь»).
"""
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

import pytest
from sqlalchemy import func, text
from sqlmodel import Session, select

from app.db import engine
from app.models import Coupon, CouponRedemption, Partner, User, UserRole
from app.routers import coupons as coupons_router
from app.routers.coupons import RedeemIn
from app.timeutil import client_dt_to_utc


def _past():
    return (datetime.utcnow() - timedelta(days=1)).replace(microsecond=0).isoformat()


def _future():
    return (datetime.utcnow() + timedelta(days=30)).replace(microsecond=0).isoformat()


def _register_active_partner(client, user_factory, name="Купон-бизнес"):
    """Компактная копия сценария test_coupons.py: регистрация → одобрение → оплаченная подписка."""
    owner = user_factory(name)
    admin = user_factory(f"{name}-админ", role=UserRole.admin)
    r = client.post("/partner", headers=owner["auth"], json={"name": name, "city": "Уфа"})
    assert r.status_code == 200, r.text
    pid = r.json()["id"]
    assert client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"]).status_code == 200
    rs = client.post("/partner/subscribe", headers=owner["auth"], json={"plan": "basic"})
    assert rs.status_code == 200, rs.text
    payment_id = rs.json()["payment_id"]
    assert client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"]).status_code == 200
    return owner, admin, pid


def _make_active_coupon(client, owner, **overrides):
    body = {"title": "Скидка леафа 1.3", "discount_text": "−15%", "limit_per_user": 1}
    body.update(overrides)
    r = client.post("/partner/coupons", headers=owner["auth"], json=body)
    assert r.status_code == 200, r.text
    cid = r.json()["id"]
    assert client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"],
                       json={"status": "active"}).status_code == 200
    return cid


def _pg_session():
    # SET LOCAL — не просто SET: без LOCAL настройка осталась бы на соединении и после commit
    # внутри вызванной функции утекла бы в СЛЕДУЮЩИЙ тест, который возьмёт это же соединение
    # из пула (независимое ревью leaf-1.3).
    s = Session(engine)
    s.execute(text("SET LOCAL lock_timeout = '4s'"))
    s.execute(text("SET LOCAL statement_timeout = '8s'"))
    return s


# ============================ R1: не погашается дважды (гонка, PostgreSQL) ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_redeem_only_one_wins(client, user_factory, monkeypatch):
    """Два кассира одного бизнеса нажимают «погасить» на один и тот же код одновременно —
    должен пройти РОВНО один, счётчик погашений вырасти РОВНО на 1.

    Без притормаживания гонка ловится лишь С ВЫСОКОЙ ВЕРОЯТНОСТЬЮ: второму потоку хватает
    одного SELECT, чтобы прочитать код, а первому нужно больше шагов и commit — иногда ОС
    успевает прогнать первый поток целиком раньше, чем второй вообще стартует (независимое
    ревью leaf-1.3). Задержка ПРЯМО ПЕРЕД атомарным UPDATE гарантирует окно гонки каждый раз,
    а правильный код всё равно спасает только блокировка/условный UPDATE, а не удачный момент."""
    owner, _admin, _pid = _register_active_partner(client, user_factory, "ГонкаПогашения")
    cid = _make_active_coupon(client, owner, limit_per_user=1)
    pax = user_factory("ГонкаПогашенияПас")
    act = client.post(f"/coupons/{cid}/activate", headers=pax["auth"])
    assert act.status_code == 200, act.text
    code = act.json()["code"]

    original_utcnow = coupons_router.utcnow

    def delayed_utcnow():
        time.sleep(0.2)   # зовётся ПРЯМО В МОМЕНТ сборки CAS-UPDATE (redeemed_at=utcnow())
        return original_utcnow()

    monkeypatch.setattr(coupons_router, "utcnow", delayed_utcnow)

    barrier = threading.Barrier(2)

    def redeem():
        with _pg_session() as s:
            biz_user = s.get(User, owner["id"])
            barrier.wait(timeout=10)
            try:
                return coupons_router.coupon_redeem(RedeemIn(code=code), user=biz_user, session=s)
            except Exception as exc:  # HTTPException — ожидаемый отказ второго кассира
                return exc

    with ThreadPoolExecutor(max_workers=2) as ex:
        futures = [ex.submit(redeem) for _ in range(2)]
        results = [f.result(timeout=20) for f in futures]

    oks = [r for r in results if isinstance(r, dict)]
    fails = [r for r in results if not isinstance(r, dict)]
    assert len(oks) == 1, f"погашение прошло не ровно один раз: {results}"
    assert len(fails) == 1
    assert getattr(fails[0], "status_code", None) == 409
    assert "погаш" in fails[0].detail["ru"].lower() and fails[0].detail["ba"]

    with Session(engine) as s:
        coupon = s.get(Coupon, cid)
        assert coupon.redeemed_count == 1, "счётчик погашений не должен задваиваться"
        red = s.exec(select(CouponRedemption).where(CouponRedemption.code == code)).first()
        assert red.status == "redeemed"


# ============================ R2: бизнес не может погасить чужой купон ============================
def test_business_cannot_redeem_foreign_coupon(client, user_factory):
    owner_a, _admin_a, _pid_a = _register_active_partner(client, user_factory, "СвойБизнесА")
    owner_b, _admin_b, _pid_b = _register_active_partner(client, user_factory, "ЧужойБизнесБ")
    cid = _make_active_coupon(client, owner_a, limit_per_user=1)
    pax = user_factory("ЧужойКупонПас")
    act = client.post(f"/coupons/{cid}/activate", headers=pax["auth"])
    assert act.status_code == 200, act.text
    code = act.json()["code"]

    # Бизнес Б пытается погасить код бизнеса А.
    r = client.post("/coupons/redeem", headers=owner_b["auth"], json={"code": code})
    assert r.status_code == 404, r.text
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"], "отказ должен быть понятным человеку на двух языках"

    # Код остался цел — законный владелец бизнеса А всё ещё может его погасить.
    r_owner = client.post("/coupons/redeem", headers=owner_a["auth"], json={"code": code})
    assert r_owner.status_code == 200, r_owner.text

    with Session(engine) as s:
        coupon = s.get(Coupon, cid)
        assert coupon.redeemed_count == 1, "чужая попытка не должна была засчитаться"


# ============================ R5: просроченный/снятый купон не гасится ============================
# Найдено независимым ревью leaf-1.3 (п.4): `coupon_redeem` проверял только СТАТУС БРОНИ
# (red.status), но не срок и не "снятие" самого купона. Человек бронирует код, пока акция ещё
# идёт, акция заканчивается или админ снимает купон за жалобу — а касса продолжает отвечать
# «ok» и бизнес получает +10 ₽ за каждое такое погашение купона, которого по правилам уже нет.
def test_redeem_rejects_expired_coupon(client, user_factory):
    owner, _admin, _pid = _register_active_partner(client, user_factory, "СрокПогашения")
    cid = _make_active_coupon(client, owner, limit_per_user=1)
    pax = user_factory("СрокПогашенияПас")
    act = client.post(f"/coupons/{cid}/activate", headers=pax["auth"])
    assert act.status_code == 200, act.text
    code = act.json()["code"]

    # Акция закончилась ПОСЛЕ того, как код уже был выдан на руки.
    with Session(engine) as s:
        coupon = s.get(Coupon, cid)
        coupon.valid_until = datetime.utcnow() - timedelta(hours=1)
        s.add(coupon)
        s.commit()

    r = client.post("/coupons/redeem", headers=owner["auth"], json={"code": code})
    assert r.status_code == 422, r.text
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"], "отказ должен быть понятным человеку на двух языках"

    with Session(engine) as s:
        coupon = s.get(Coupon, cid)
        assert coupon.redeemed_count == 0, "просроченный купон не должен был засчитаться бизнесу"
        red = s.exec(select(CouponRedemption).where(CouponRedemption.code == code)).first()
        assert red.status == "reserved", "статус брони трогать не должны — отказ временный"


def test_redeem_rejects_blocked_coupon(client, user_factory):
    owner, admin, _pid = _register_active_partner(client, user_factory, "СнятПогашения")
    cid = _make_active_coupon(client, owner, limit_per_user=1)
    pax = user_factory("СнятПогашенияПас")
    act = client.post(f"/coupons/{cid}/activate", headers=pax["auth"])
    assert act.status_code == 200, act.text
    code = act.json()["code"]

    # Админ снимает купон с публикации уже ПОСЛЕ того, как код выдан на руки.
    r_block = client.post(f"/admin/coupons/{cid}/block", headers=admin["auth"], json={"reason": "жалоба"})
    assert r_block.status_code == 200, r_block.text

    r = client.post("/coupons/redeem", headers=owner["auth"], json={"code": code})
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"]

    with Session(engine) as s:
        coupon = s.get(Coupon, cid)
        assert coupon.redeemed_count == 0, "снятый админом купон не должен был засчитаться бизнесу"


# ============================ R3: лимит на человека (гонка, PostgreSQL) ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_activation_respects_per_user_limit(client, user_factory, monkeypatch):
    """Один и тот же пассажир жмёт «активировать купон» дважды почти одновременно на купоне
    с limit_per_user=1 — в базе должна остаться РОВНО одна бронь, а не две.

    Голого барьера перед вызовом мало: после него поток ещё должен реально оказаться внутри
    критического участка (после чтения купона, до вставки брони) ОДНОВРЕМЕННО со вторым, а
    планировщик (особенно на Windows) может прогнать один поток целиком раньше, чем вообще
    переключится на другой. Поэтому дополнительно притормаживаем генерацию кода (`_gen_code`) —
    единственный шаг, который звучит ПОСЛЕ проверки лимита и ДО вставки, и его зовут оба потока."""
    original_gen_code = coupons_router._gen_code

    def delayed_gen_code(session):
        time.sleep(0.2)
        return original_gen_code(session)

    monkeypatch.setattr(coupons_router, "_gen_code", delayed_gen_code)

    owner, _admin, _pid = _register_active_partner(client, user_factory, "ГонкаЛимита")
    cid = _make_active_coupon(client, owner, limit_per_user=1, limit_total=0)
    pax = user_factory("ГонкаЛимитаПас")

    barrier = threading.Barrier(2)

    def activate():
        with _pg_session() as s:
            user = s.get(User, pax["id"])
            barrier.wait(timeout=10)
            return coupons_router.coupon_activate(cid, user=user, session=s)

    with ThreadPoolExecutor(max_workers=2) as ex:
        futures = [ex.submit(activate) for _ in range(2)]
        results = [f.result(timeout=20) for f in futures]

    assert results[0]["code"] == results[1]["code"], "обе активации должны сойтись на ОДНОЙ брони"

    with Session(engine) as s:
        count = s.exec(
            select(func.count()).select_from(CouponRedemption).where(
                CouponRedemption.coupon_id == cid, CouponRedemption.user_id == pax["id"],
            )
        ).one()
    assert count == 1, "под гонкой лимит «один купон на человека» не должен пробиваться"


# ============================ R4: срок действия и часовой пояс Уфы ============================
def test_window_respects_ufa_timezone():
    """Бизнес ставит срок «до 23:59» в анкете — это уфимское время (UTC+5), а не UTC.
    Купон обязан закрыться в 18:59 UTC (= 23:59 Уфа), а не в 23:59 UTC."""
    valid_until_local_ufa = datetime(2026, 6, 15, 23, 59, 0)
    coupon = Coupon(partner_id=1, title="Срок по Уфе", valid_until=client_dt_to_utc(valid_until_local_ufa))

    # Конвертация должна сдвинуть время на −5 часов (Уфа = UTC+5).
    assert coupon.valid_until == datetime(2026, 6, 15, 18, 59, 0)

    # 18:00 UTC того же дня = 23:00 по Уфе — купон ещё в силе.
    assert coupons_router._in_window(coupon, datetime(2026, 6, 15, 18, 0, 0)) is True
    # 20:00 UTC того же дня = 01:00 по Уфе УЖЕ СЛЕДУЮЩИХ суток — если бы сервер по ошибке
    # сравнивал уфимское время напрямую с UTC (забыв про сдвиг), купон здесь ещё казался бы
    # действующим (20:00 < 23:59) — а на самом деле уфимский дедлайн уже прошёл.
    assert coupons_router._in_window(coupon, datetime(2026, 6, 15, 20, 0, 0)) is False


# ============================ R4b (B-3): дата без времени = конец суток по Уфе ============================
# Найдено независимым ревью leaf-1.3. Приложение шлёт срок купона ДАТОЙ без времени
# («Действует до: 2026-10-31») — pydantic превращает её в полночь. Полночь — это НАЧАЛО
# суток, а бизнес имел в виду «весь день 31-е ещё должен действовать». Старое поведение
# (голый client_dt_to_utc) понимало полночь как точный момент, сдвигало на −5ч и получало
# 2026-10-30 19:00 UTC — купон закрывался на весь день раньше. Хуже: форма правки
# подставляет в поле только ДАТУ (первые 10 символов), и КАЖДОЕ повторное сохранение
# (даже правка заголовка) двигало срок ещё на сутки назад.
def test_coupon_valid_until_treats_date_only_midnight_as_end_of_day():
    naive_midnight = datetime(2026, 10, 31, 0, 0, 0)
    stored = coupons_router._coupon_valid_until(naive_midnight)
    # 23:59:59 по Уфе = 18:59:59 UTC ТОГО ЖЕ календарного дня — не 30-го числа.
    assert stored == datetime(2026, 10, 31, 18, 59, 59)


def test_coupon_valid_until_leaves_explicit_time_alone():
    """Время с явными часами (не полночь) — человек просил именно этот момент, подмены нет."""
    naive_evening = datetime(2026, 10, 31, 20, 0, 0)
    stored = coupons_router._coupon_valid_until(naive_evening)
    assert stored == datetime(2026, 10, 31, 15, 0, 0)   # просто −5ч, без сдвига на конец суток


def test_coupon_deadline_survives_repeated_date_only_resaves(client, user_factory):
    """Круговой путь «сохранил → прочитал → сохранил ТО ЖЕ САМОЕ ещё раз» не должен сдвигать
    срок. Приложение на правке подставляет в поле только дату (`validUntil.take(10)`) — ровно
    это и воспроизводим: шлём туда же то, что сервер только что вернул, дважды подряд."""
    owner, _admin, _pid = _register_active_partner(client, user_factory, "СрокНеПлывёт")
    r = client.post("/partner/coupons", headers=owner["auth"],
                    json={"title": "Срок", "valid_until": "2026-10-31", "limit_per_user": 1})
    assert r.status_code == 200, r.text
    cid = r.json()["id"]
    first = r.json()["valid_until"]
    assert first[:10] == "2026-10-31", f"дата уже съехала на первом сохранении: {first}"

    date_only = first[:10]
    r2 = client.post(f"/partner/coupons/{cid}", headers=owner["auth"],
                     json={"title": "Срок (поправили заголовок)", "valid_until": date_only,
                           "limit_per_user": 1})
    assert r2.status_code == 200, r2.text
    second = r2.json()["valid_until"]
    assert second == first, f"срок сдвинулся после повторного сохранения той же даты: {first} -> {second}"

    r3 = client.post(f"/partner/coupons/{cid}", headers=owner["auth"],
                     json={"title": "Срок (и ещё раз)", "valid_until": second[:10],
                           "limit_per_user": 1})
    assert r3.status_code == 200, r3.text
    assert r3.json()["valid_until"] == first, "и на третьем круге срок обязан остаться тем же"


# ============================ R6: «осталось N» не врёт про занятые брони ============================
def test_storefront_remaining_counts_outstanding_reservations(client, user_factory):
    """Пассажир забронировал код, но ещё не погасил его — это МЕСТО занято, и витрина обязана
    показать на один слот меньше, а не только после реального погашения."""
    owner, _admin, _pid = _register_active_partner(client, user_factory, "ОстатокЧестный")
    cid = _make_active_coupon(client, owner, limit_total=2, limit_per_user=1)
    pax1 = user_factory("ОстатокПас1")
    pax2 = user_factory("ОстатокПас2")

    listing0 = client.get("/coupons", params={"city": "Уфа"}).json()
    card0 = next(c for c in listing0 if c["id"] == cid)
    assert card0["remaining"] == 2

    assert client.post(f"/coupons/{cid}/activate", headers=pax1["auth"]).status_code == 200

    listing1 = client.get("/coupons", params={"city": "Уфа"}).json()
    card1 = next(c for c in listing1 if c["id"] == cid)
    assert card1["remaining"] == 1, "бронь ещё не погашена, но слот уже должен считаться занятым"
    detail1 = client.get(f"/coupons/{cid}").json()
    assert detail1["remaining"] == 1, "деталь купона должна считать так же, как список"

    assert client.post(f"/coupons/{cid}/activate", headers=pax2["auth"]).status_code == 200
    listing2 = client.get("/coupons", params={"city": "Уфа"}).json()
    card2 = next(c for c in listing2 if c["id"] == cid)
    assert card2["remaining"] == 0, "обе брони заняты — свободных мест для витрины больше нет"


# ============================ R7: лимит 50 купонов на бизнес (гонка, PostgreSQL) ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_coupon_creation_respects_fifty_limit(client, user_factory, monkeypatch):
    """Бизнес уже на 49 купонах из 50 — два одновременных «создать купон» должны дать ровно
    один успех и один внятный отказ, а не 51–52 купона."""
    owner, _admin, _pid = _register_active_partner(client, user_factory, "Лимит50Гонка")
    for i in range(coupons_router.MAX_COUPONS_PER_PARTNER - 1):
        assert client.post("/partner/coupons", headers=owner["auth"],
                           json={"title": f"К{i}"}).status_code == 200

    original = coupons_router._apply_review

    def delayed_apply_review(coupon, user_id):
        time.sleep(0.2)   # между проверкой лимита и вставкой строки купона
        return original(coupon, user_id)

    monkeypatch.setattr(coupons_router, "_apply_review", delayed_apply_review)

    barrier = threading.Barrier(2)

    def create():
        with _pg_session() as s:
            user = s.exec(select(User).where(User.id == owner["id"])).one()
            barrier.wait(timeout=10)
            try:
                return coupons_router.partner_coupon_create(
                    coupons_router.CouponIn(title="Пограничный"), user=user, session=s,
                )
            except Exception as exc:
                return exc

    with ThreadPoolExecutor(max_workers=2) as ex:
        futures = [ex.submit(create) for _ in range(2)]
        results = [f.result(timeout=20) for f in futures]

    oks = [r for r in results if isinstance(r, dict)]
    fails = [r for r in results if not isinstance(r, dict)]
    assert len(oks) == 1, f"на границе лимита 50 должен пройти только один запрос: {results}"
    assert len(fails) == 1
    assert getattr(fails[0], "status_code", None) == 429

    with Session(engine) as s:
        pid = s.exec(select(Partner).where(Partner.owner_id == owner["id"])).one().id
        count = s.exec(select(func.count()).select_from(Coupon).where(Coupon.partner_id == pid)).one()
    assert count == coupons_router.MAX_COUPONS_PER_PARTNER, (
        f"итог {count} вместо {coupons_router.MAX_COUPONS_PER_PARTNER} — лимит пробит гонкой"
    )


# ============================ R8: вечная бронь истекает ============================
def test_expired_unbounded_reservation_frees_the_limit_slot(client, user_factory):
    """Купон БЕЗ срока действия: забытая бронь старше RESERVATION_TTL_DAYS не должна держать
    лимит вечно — другой человек обязан получить свободный слот."""
    owner, _admin, _pid = _register_active_partner(client, user_factory, "ВечнаяБронь")
    cid = _make_active_coupon(client, owner, limit_total=1, limit_per_user=1)
    old_pax = user_factory("ВечнаяБроньСтарый")
    new_pax = user_factory("ВечнаяБроньНовый")

    act = client.post(f"/coupons/{cid}/activate", headers=old_pax["auth"])
    assert act.status_code == 200, act.text
    with Session(engine) as s:
        red = s.exec(select(CouponRedemption).where(CouponRedemption.code == act.json()["code"])).one()
        red.reserved_at = datetime.utcnow() - timedelta(days=coupons_router.RESERVATION_TTL_DAYS + 1)
        s.add(red)
        s.commit()

    # Слот теперь свободен — новый человек должен суметь активировать тот же купон.
    act2 = client.post(f"/coupons/{cid}/activate", headers=new_pax["auth"])
    assert act2.status_code == 200, act2.text
    assert act2.json()["code"] != act.json()["code"]

    detail = client.get(f"/coupons/{cid}").json()
    assert detail["remaining"] == 0, "свежая бронь нового человека обязана занять единственный слот"


def test_expired_reservation_cannot_be_redeemed(client, user_factory):
    """Та же истёкшая (по 30-дневному правилу) бронь не должна гаситься у кассы — бизнесу
    нельзя засчитать погашение по коду, который человек, скорее всего, давно потерял."""
    owner, _admin, _pid = _register_active_partner(client, user_factory, "ПросрочГашение")
    cid = _make_active_coupon(client, owner, limit_total=0, limit_per_user=1)
    pax = user_factory("ПросрочГашениеПас")
    act = client.post(f"/coupons/{cid}/activate", headers=pax["auth"])
    assert act.status_code == 200, act.text
    code = act.json()["code"]
    with Session(engine) as s:
        red = s.exec(select(CouponRedemption).where(CouponRedemption.code == code)).one()
        red.reserved_at = datetime.utcnow() - timedelta(days=coupons_router.RESERVATION_TTL_DAYS + 1)
        s.add(red)
        s.commit()

    r = client.post("/coupons/redeem", headers=owner["auth"], json={"code": code})
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"]

    with Session(engine) as s:
        coupon = s.get(Coupon, cid)
        assert coupon.redeemed_count == 0, "просроченная бронь не должна была засчитаться бизнесу"
