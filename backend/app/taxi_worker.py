# -*- coding: utf-8 -*-
"""Фоновый воркер такси и доставки: доводит заказы до конца, когда живой человек не может.

Зачем (аудит 2026-07-26, три блокера одной работой):
1. **Предзаказ «на утро» не срабатывал сам.** Активация была ленивой — только когда пассажир
   ВРУЧНУЮ откроет экран «Мои предзаказы». Бабушка заказала машину на 6:00 к автобусу в Уфу —
   утром поиск даже не запускался. Теперь активирует воркер.
2. **Заказ мог зависнуть навсегда.** У водителя сел телефон на трассе Сибай–Уфа → заказ вечно
   «в пути»: отменить его мог только водитель, фонового закрытия не было, у админа кнопки нет.
   Пассажир при этом НЕ мог заказать другое такси (сервер возвращал ему мёртвый заказ).
3. **«Рядом никого» = мгновенная смерть заказа.** В райцентре ночью на линии 2-3 водителя, оба
   заняты — пассажир получал отказ за 2 секунды и уходил к конкуренту. Теперь он может нажать
   «подождать», а воркер спокойно перезапускает поиск, пока не найдёт или не выйдет время.

Плюс четвёртая, техническая: протухшие офферы двигались только «лениво» (когда кто-нибудь
опросит заказ) — при закрытом приложении подбор стоял.

5. **То же самое было с посылками** (аудит 2026-08-03). Доставку чинили только для такси:
   посылка, принятая курьером, который пропал, висела «в пути» вечно — закрыть или переназначить
   её мог только админ вручную. Бабушка из Темясово ждёт лекарство и не понимает, что делать.
   Теперь: курьер взял и не тронулся с места — снимаем его, посылка возвращается в общий список;
   везёт слишком долго — закрываем разбор и говорим обеим сторонам честно, БЕЗ потери следа,
   кто вёз (courier_id на закрытой посылке остаётся — иначе спор потом не с кем разбирать).

Запуск (systemd-таймер, см. deploy/yuldash-taxi-worker.*):
    python -m app.taxi_worker            # реальный прогон
    python -m app.taxi_worker --dry-run  # показать, что сделал бы, ничего не меняя

Свойства: один процесс (без гонок между воркерами API), идемпотентно (каждый шаг проверяет
статус под свежим чтением), безопасно (ошибка одной задачи не валит остальные).
"""
import sys
from datetime import timedelta

from sqlalchemy import update
from sqlmodel import Session, select

from . import declare_remind, incident_escalate, instant_service as isv, waiting_on_us
from . import ads_expire, promo_ride, sos_escalate, winter_escalate
from .config import settings
from .db import engine
from .logs import log
from .models import InstantOrder, InstantOrderStatus as S, ParcelDelivery
from .services import push_notification
from .taxi_route_resume import _MARKERS as _ROUTE_RESUME_MARKERS
from .timeutil import utcnow

# Статусы, из которых заказ уже никуда не уедет сам (терминальные).
_TERMINAL = (S.done, S.cancelled, S.expired)
# Живые статусы, где заказ ждёт действий человека — именно они могут «зависнуть».
# `created` тут не случайно: этот статус живёт доли секунды (между записью заказа и запуском
# поиска), но если ровно там оборвалось — заказ висел в нём ВЕЧНО, а анти-дубль на создании
# считает его активным и на каждый тап «Заказать» возвращает тот же мёртвый заказ. Один сбой —
# и человек больше никогда не мог вызвать такси (аудит 2026-08-07).
_ALIVE = isv.LIVE_ORDER_STATUSES   # общий список «человек сейчас занят» (волна 76)


def _last_move_at(o: InstantOrder):
    """Момент последнего осмысленного перехода заказа — от него считаем «завис»."""
    return (o.onboard_at or o.arriving_at or o.accepted_at or o.offered_at
            or o.searching_at or o.created_at)


# ----------------------------- 1. Предзаказы -----------------------------
def activate_due_scheduled(session: Session, dry_run: bool = False) -> list:
    """scheduled с наступившим временем → запускаем поиск. Возврат: id активированных."""
    now = utcnow()
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.status == S.scheduled,
            InstantOrder.scheduled_at != None,          # noqa: E711 — SQL IS NOT NULL
            InstantOrder.scheduled_at <= now,
        ).limit(200)
    ).all()
    done = []
    for o in rows:
        if dry_run:
            done.append(o.id)
            continue
        try:
            isv.activate_scheduled(session, o)
            done.append(o.id)
        except Exception as e:  # noqa: BLE001 — один заказ не должен ронять прогон
            log.warning(f"[TAXI-WORKER] активация предзаказа #{o.id}: {type(e).__name__}: {e}")
    return done


# ----------------------------- 2. Зависшие заказы -----------------------------
def close_stuck_orders(session: Session, dry_run: bool = False) -> list:
    """Заказы в живых статусах без движения дольше order_stuck_hours → закрываем системно.

    Пассажир и водитель получают честный пуш: «заказ закрыт автоматически». Деньги не двигаем
    (Модель А — платформа их не касается), штрафов не ставим: виноватых тут нет, связь пропала."""
    cutoff = utcnow() - timedelta(hours=settings.order_stuck_hours)
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.status.in_(_ALIVE),
            InstantOrder.created_at <= cutoff,          # грубый предфильтр по индексу
        ).limit(500)
    ).all()
    closed = []
    for o in rows:
        if _last_move_at(o) > cutoff:
            continue                                    # двигался недавно — не трогаем
        if dry_run:
            closed.append(o.id)
            continue
        try:
            fresh = session.exec(
                select(InstantOrder).where(InstantOrder.id == o.id).with_for_update()
            ).first()
            if not fresh or fresh.status in _TERMINAL or _last_move_at(fresh) > cutoff:
                continue                                # успели закрыть/сдвинуть параллельно
            # onboard/accepted → «отменён системой»; ещё не найден водитель → «истёк».
            fresh.status = S.cancelled if fresh.driver_id else S.expired
            fresh.cancel_by = "system"
            fresh.cancel_reason = "stuck_timeout"
            fresh.cancelled_at = utcnow()
            fresh.current_offer_driver_id = None
            fresh.offer_expires_at = None
            fresh.wait_until = None
            session.add(fresh)
            session.commit()
            closed.append(fresh.id)
            # Близкие, с которыми пассажир поделился поездкой, должны узнать о финале —
            # особенно об ЭТОМ финале (волна 128). Зухра едет ночью Сибай — Уфа, мама получила
            # SMS «села в такси». У водителя сел телефон, «Завершена» никто не нажал, и через
            # шесть часов робот закрывает заказ. Пассажиру и водителю мы пишем, а маме — нет:
            # для неё последняя новость так и остаётся «села в такси», семь часов назад.
            # Функция «поделиться поездкой» существует ровно ради случая «что-то пошло не так».
            try:
                isv._notify_order_shares(session, fresh, "cancelled")
            except Exception:  # noqa: BLE001 — SMS вторичны, закрытие заказа важнее
                pass
            for uid in {fresh.passenger_id, fresh.driver_id} - {None}:
                try:
                    push_notification(
                        session, uid, "taxi",
                        "Заказ закрыт", "Заказ ябылды",
                        "Долго не было связи — заказ закрыли автоматически. "
                        "Если поездка состоялась, договоритесь напрямую.",
                        "Оҙаҡ бәйләнеш булманы — заказ автоматик ябылды. Сәфәр булған икән, "
                        "тура килешегеҙ.",
                        ref_kind="instant", ref_id=fresh.id,
                    )
                except Exception:  # noqa: BLE001 — пуш вторичен
                    pass
        except Exception as e:  # noqa: BLE001
            log.warning(f"[TAXI-WORKER] закрытие зависшего #{o.id}: {type(e).__name__}: {e}")
    # Поездки не было → промокод возвращаем. Он даётся раз в жизни аккаунта, и сжигать его
    # за заказ, который закрыла сама система, нечестно (то же правило, что при отмене).
    # dry-run только показывает кандидатов: отдельная сессия release_ids иначе всё равно
    # коммитила возврат скидки, хотя статус заказа здесь намеренно не менялся.
    if not dry_run:
        promo_ride.release_ids(closed)
    return closed


# ----------------------------- 3. Очередь «рядом никого» -----------------------------
def recover_confirmed_dispatches(session: Session, dry_run: bool = False) -> list:
    """Finish confirmed initial searches left pending by an interrupted API or worker."""
    ids = session.exec(
        select(InstantOrder.id).where(isv.pending_confirmed_dispatch())
        .order_by(InstantOrder.created_at, InstantOrder.id).limit(200)
    ).all()
    if dry_run:
        return list(ids)
    finished = []
    for order_id in ids:
        try:
            if isv.dispatch_confirmed_order(session, order_id):
                finished.append(order_id)
        except Exception as exc:
            # dispatch_confirmed_order rolled back; a later pass can retry this row.
            log.warning("[TAXI-WORKER] initial dispatch order=%s error=%s", order_id, type(exc).__name__)
    return finished


def retry_waiting_orders(session: Session, dry_run: bool = False) -> list:
    """Пассажир нажал «подождать» (wait_until в будущем) → тихо перезапускаем поиск.

    notify=False: пассажир УЖЕ знает, что машин нет — повторять ему «рядом никого» каждые
    две минуты значит выпросить выключение уведомлений. Нашли водителя → обычный оффер, и
    пассажир получит нормальный пуш о найденной машине."""
    now = utcnow()
    rows = session.exec(select(*isv._WAIT_RETRY_COLUMNS).where(
        InstantOrder.status.in_((S.expired, S.searching)), *isv._ordinary_wait_predicates(session),
    ).limit(200)).all()
    retried = []
    gap = timedelta(minutes=settings.order_retry_every_min)
    for row in rows:
        if row.searching_at and (now - row.searching_at) < gap:
            continue
        if dry_run:
            retried.append(row.id)
            continue
        try:
            result = isv.start_matching(session, row, notify=False, _wait_snapshot=tuple(row))
            if result is None:
                continue
            retried.append(row.id)
            if result.offer is not None:
                passenger_id = isv._current_queued_offer_passenger(session, result.offer)
                if passenger_id is not None:
                    push_notification(
                        session, passenger_id, "taxi",
                        "Водитель нашёлся 🚕", "Водитель табылды 🚕",
                        "Мы нашли машину по твоему заказу.",
                        "Заказың буйынса машина таптыҡ.",
                        ref_kind="instant", ref_id=row.id,
                    )
        except Exception as e:  # noqa: BLE001
            session.rollback()
            log.warning(f"[TAXI-WORKER] перезапуск поиска #{row.id}: {type(e).__name__}: {e}")
    return retried


def finish_expired_waits(session: Session, dry_run: bool = False) -> list:
    """Close only the exact expired ordinary wait, never a TF04 recovery."""
    now = utcnow()
    columns = promo_ride._WAIT_EXPIRY_COLUMNS
    unmarked = tuple(getattr(InstantOrder, name).is_(None) for name in _ROUTE_RESUME_MARKERS)
    rows = session.exec(select(*columns).where(
        InstantOrder.wait_until <= now,
        InstantOrder.status.in_((S.expired, S.searching)),
        *unmarked,
    ).limit(200)).all()
    if dry_run:
        return [row.id for row in rows]

    finished = {}
    for row in rows:
        try:
            # Lock via the conditional write. A stale renewal, acceptance
            # or resume marker installed after selection must win intact.
            changed = session.execute(
                update(InstantOrder).where(
                    *(col == value for col, value in zip(columns, row, strict=True)),
                    *unmarked,
                ).values(wait_until=None, status=S.expired, expired_at=row.expired_at or utcnow())
                .execution_options(synchronize_session=False).returning(*columns)
            ).one_or_none()
            if changed is None:
                session.rollback()
                continue
            session.commit()
            finished[changed.id] = tuple(changed)
        except Exception as e:  # noqa: BLE001
            session.rollback()
            log.warning(f"[TAXI-WORKER] закрытие ожидания #{row.id}: {type(e).__name__}: {e}")
            continue
        try:
            # Best-effort fresh notice check; keep network outside the write
            # lock. Promo release separately uses a guarded physical UPDATE.
            if session.exec(select(InstantOrder.id).where(
                *promo_ride._closed_wait_predicates(tuple(changed)),
            )).one_or_none() is not None:
                push_notification(
                    session, changed.passenger_id, "taxi",
                    "Машину не нашли", "Машина табылманы",
                    "Свободных водителей рядом так и не появилось. Попробуй ещё раз "
                    "или оставь заявку попутчикам.",
                    "Тирә-яҡта буш водителдәр табылманы. Тағы ҡабатлап ҡара йәки "
                    "юлдаштарға ғариза ҡалдыр.",
                    ref_kind="instant", ref_id=changed.id,
                )
        except Exception:  # noqa: BLE001
            session.rollback()
    # Preserve the established PromoRedemption -> Order lock ordering.
    promo_ride.release_ids(finished, _closed_waits=finished)
    return list(finished)


# ----------------------------- 4. Протухшие офферы -----------------------------
def advance_stale_offers(session: Session, dry_run: bool = False) -> list:
    """Оффер висит без ответа дольше срока → двигаем подбор к следующему водителю.

    Раньше это делалось только «лениво» (при опросе заказа клиентом): пассажир закрыл
    приложение — и подбор замирал до его возвращения."""
    now = utcnow()
    stale_before = now - timedelta(minutes=settings.order_offer_stale_min)
    rows = session.exec(select(*isv._SEARCH_OWNER_COLUMNS).where(
        InstantOrder.status == S.offered, InstantOrder.offer_expires_at < now, *isv._legacy_no_resume(),
    ).limit(200)).all()
    moved = []
    for old in rows:
        if old.offered_at and old.offered_at > stale_before:
            continue
        if dry_run:
            moved.append(old.id)
            continue
        try:
            _, won = isv._advance_legacy_offer(session, tuple(old), notify=False)
            if won:
                moved.append(old.id)
        except Exception as e:  # noqa: BLE001
            session.rollback()
            log.warning(f"[TAXI-WORKER] сдвиг оффера #{old.id}: {type(e).__name__}: {e}")
    return moved


# ----------------------------- 5. Зависшие посылки -----------------------------
# Посылка на руках у курьера. `returning` сюда НЕ входит намеренно: коробку физически везут
# обратно отправителю, и «закрыть» такую доставку из фона значило бы соврать обоим — этот
# случай остаётся админу.
_PARCEL_STUCK_STATUSES = ("accepted", "in_transit")
# Закрытая ветка: дальше посылка сама не двинется.
_PARCEL_FINAL = ("delivered", "canceled", "returned")


def _last_parcel_move_at(p: ParcelDelivery):
    """Момент последнего осмысленного движения посылки.

    Отдельной отметки времени у перехода в `in_transit` в модели нет, поэтому берём принятие
    заказа (а до него — создание). Это консервативно: курьер, взявший посылку три дня назад
    и не закрывший её, попадает в разбор, даже если статус двигал."""
    return (p.accepted_at or p.created_at)


def close_stuck_parcels(session: Session, dry_run: bool = False) -> list:
    """Посылка у курьера без движения дольше parcel_stuck_hours → разводим ситуацию.

    Два разных случая — два разных честных исхода:
      • `accepted` (взял, но так и не поехал) → снимаем курьера, посылка возвращается в общий
        список. Отправитель ничего не теряет: её сможет взять другой;
      • `in_transit` (коробка уехала с ним) → возвращать в список нечего, закрываем разбор
        как «отменена» и говорим обеим сторонам прямо. courier_id НЕ обнуляем: след «кто вёз»
        — единственное, по чему потом можно открыть спор и найти посылку.
    Денег не двигаем и страйков не ставим (Модель А): пропавшая связь — не доказанная вина.
    """
    cutoff = utcnow() - timedelta(hours=settings.parcel_stuck_hours)
    rows = session.exec(
        select(ParcelDelivery).where(
            ParcelDelivery.status.in_(_PARCEL_STUCK_STATUSES),
            ParcelDelivery.created_at <= cutoff,         # грубый предфильтр по индексу
        ).limit(500)
    ).all()
    handled = []
    for p in rows:
        if _last_parcel_move_at(p) > cutoff:
            continue                                    # двигалась недавно — не трогаем
        if dry_run:
            handled.append(p.id)
            continue
        try:
            fresh = session.exec(
                select(ParcelDelivery).where(ParcelDelivery.id == p.id).with_for_update()
            ).first()
            if (not fresh or fresh.status in _PARCEL_FINAL
                    or fresh.status not in _PARCEL_STUCK_STATUSES
                    or _last_parcel_move_at(fresh) > cutoff):
                continue                                # успели закрыть/сдвинуть параллельно
            prev_courier = fresh.courier_id
            route = f"{fresh.from_city} → {fresh.to_city}"
            released = fresh.status == "accepted"
            if released:
                fresh.courier_id = None
                fresh.status = "created"
                fresh.accepted_at = None
                fresh.pickup_photo_url = ""             # фото относилось к снятому курьеру
                fresh.return_reason = "stuck_timeout"
            else:
                fresh.status = "canceled"               # courier_id оставляем — это след «кто вёз»
                fresh.return_reason = "stuck_timeout"
            session.add(fresh)
            session.commit()
            session.refresh(fresh)
            handled.append(fresh.id)
            if released:
                title = ("Ищем другого курьера", "Башҡа курьер эҙләйбеҙ")
                body_sender = (f"{route} · курьер долго не выходил на связь. Посылка снова в поиске.",
                               f"{route} · курьер оҙаҡ бәйләнешкә сыҡманы. Бандероль яңынан эҙләүҙә.")
                body_courier = (f"{route} · мы сняли тебя с доставки: долго не было движения.",
                                f"{route} · һине доставканан алдыҡ: оҙаҡ хәрәкәт булманы.")
            else:
                title = ("Доставка закрыта", "Доставка ябылды")
                body_sender = (f"{route} · долго не было связи, доставку закрыли автоматически. "
                               "Свяжись напрямую или напиши в поддержку.",
                               f"{route} · оҙаҡ бәйләнеш булманы, доставка автоматик ябылды. "
                               "Туранан-тура бәйлән йәки ярҙамға яҙ.")
                body_courier = body_sender
            for uid, body in ((fresh.sender_id, body_sender), (prev_courier, body_courier)):
                if not uid:
                    continue
                try:
                    push_notification(session, uid, "parcel", title[0], title[1], body[0], body[1],
                                      ref_kind="parcel", ref_id=fresh.id,
                                      data={"type": "parcel_status", "id": fresh.id})
                except Exception:  # noqa: BLE001 — уведомление вторично
                    pass
        except Exception as e:  # noqa: BLE001 — одна посылка не должна ронять прогон
            log.warning(f"[TAXI-WORKER] зависшая посылка #{p.id}: {type(e).__name__}: {e}")
    return handled


# Все задачи прогона: (ключ сводки, что запускаем, что вернуть при сбое).
#
# Порядок важен и он же был опасен. Шесть такси-задач стоят первыми, а за ними — то,
# от чего зависит человек, а не заказ: непринятая красная кнопка SOS уходит выше
# (волна 82), зимний протокол зовёт близких у молчащего в дороге (волна 114), жалоба
# без ответа обвинённого идёт на разбор сама (волна 175). Все двенадцать собирались
# ОДНИМ выражением-словарём — и первое же исключение обрывало сборку целиком. Зависшая
# посылка молча выключала эскалацию тревоги (аудит 2026-08-08, волна 198).
#
# Рядом с этими задачами прямо написано, зачем они в этом воркере: «безопасность не должна
# зависеть от того, открыт ли у человека экран». Значит она не должна зависеть и от того,
# всё ли в порядке с чужим заказом.
ЗАДАЧИ = (
    # ---------- сначала то, от чего зависит ЧЕЛОВЕК ----------
    # Порядок здесь — это приоритет, а не оформление (волна 199). Изоляция из волны 198
    # спасает от исключения и ничем не помогает, когда процесс перестаёт жить: воркер
    # запускается как `Type=oneshot` без своего `TimeoutStartSec`, то есть по умолчанию
    # systemd убивает его через полторы минуты. Шесть хозяйственных задач ходят в сеть
    # (пуши, SMS, Telegram) и разбирают до пятисот строк каждая — затянулись, и всё,
    # что стояло ниже, не выполнялось вовсе. Тревога стояла седьмой.
    #
    # Хуже того, это тихо: пока прогон идёт, следующий запуск systemd пропускает, а
    # в логе нет ни одной ошибки — просто SOS не уходит часами.
    ("sos_escalated", lambda s, d: sos_escalate.escalate_unhandled(s, d), []),
    # Зимний протокол сухого прогона не знает: у него нет режима «посмотреть».
    ("winter_escalated", lambda s, d: 0 if d else winter_escalate.escalate_silent(s), 0),
    ("incidents_escalated", lambda s, d: incident_escalate.escalate_silent_incidents(s, d), []),
    # Водитель заявил оплату, а подтверждения нет: предупреждаем ОБЕ стороны за сутки
    # до того, как доверие кончится и такси закроется (волна 176). Тоже про человека:
    # не успели предупредить — он узнаёт об отключении по факту отключения.
    ("declares_reminded", lambda s, d: declare_remind.remind_pending_declares(s, d), []),
    # Очереди, которые упираются в Александра: человек ждёт ответа и не знает, ждут ли его
    # вообще. Пишем ему и даём Александру сводку (волна 178).
    ("people_waiting", lambda s, d: waiting_on_us.remind_waiting_people(s, d), []),
    # ---------- потом то, от чего зависит ЗАКАЗ ----------
    ("scheduled_activated", lambda s, d: activate_due_scheduled(s, d), []),
    ("stuck_closed", lambda s, d: close_stuck_orders(s, d), []),
    ("consent_dispatches", lambda s, d: recover_confirmed_dispatches(s, d), []),
    ("waits_retried", lambda s, d: retry_waiting_orders(s, d), []),
    ("waits_finished", lambda s, d: finish_expired_waits(s, d), []),
    ("offers_advanced", lambda s, d: advance_stale_offers(s, d), []),
    ("parcels_handled", lambda s, d: close_stuck_parcels(s, d), []),
    # Реклама с вышедшим сроком (волна 125): показы прекращались сами, а в кабинете горело
    # «Оплачено · показывается» и уведомления не было — человек шёл в поддержку с обвинением.
    ("ads_expired", lambda s, d: ads_expire.expire_finished_ads(s, d), 0),
)


def run_once(session: Session, dry_run: bool = False) -> dict:
    """Один полный прогон всех задач. Возврат — сводка для лога/тестов.

    Сбой одной задачи не отменяет остальные: ключ остаётся, значение пустое, ошибка —
    в лог с трассировкой. Тот же приём, что у обхода документов (`doc_check.run_once`);
    здесь он нужнее, потому что ниже по списку стоят тревога и зимний протокол.
    """
    result: dict = {}
    for ключ, задача, пусто in ЗАДАЧИ:
        try:
            result[ключ] = задача(session, dry_run)
        except Exception as e:  # noqa: BLE001 — одна задача не должна валить остальные
            # Откат обязателен. Без него сорвавшаяся транзакция остаётся на сессии, и КАЖДАЯ
            # следующая задача падает на ней же — изоляция была бы только на бумаге.
            try:
                session.rollback()
            except Exception:  # noqa: BLE001
                pass
            log.exception(f"[TAXI-WORKER] задача {ключ} упала: {type(e).__name__}: {e}")
            result[ключ] = пусто
    return result


def main():
    dry = "--dry-run" in sys.argv
    mode = "СУХОЙ ПРОГОН (ничего не меняется)" if dry else "РЕАЛЬНЫЙ прогон"
    print(f"=== Воркер такси и доставки · {mode} · {utcnow().isoformat()} ===")
    if not settings.taxi_worker_enabled:
        print("  выключен (taxi_worker_enabled=false) — пропуск")
        return
    with Session(engine) as session:
        res = run_once(session, dry_run=dry)
    labels = {
        "scheduled_activated": "предзаказов запущено",
        "stuck_closed": "зависших закрыто",
        "consent_dispatches": "первых поисков обработано",
        "waits_retried": "поисков перезапущено",
        "waits_finished": "ожиданий завершено",
        "offers_advanced": "офферов сдвинуто",
        "parcels_handled": "зависших посылок разобрано",
    }
    for key, label in labels.items():
        print(f"  {label}: {len(res[key])}")
    print("=== Готово ===")


if __name__ == "__main__":
    main()
