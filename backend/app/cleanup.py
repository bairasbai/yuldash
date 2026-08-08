# -*- coding: utf-8 -*-
"""Ретеншен-чистка: убираем ЭФЕМЕРНОЕ (сообщения+медиа, коды входа, протухшие токены,
события рекламы, старые завершённые поездки), БЕРЕЖЁМ важное (аккаунты, профили водителей,
доверенные контакты, блок-лист, рейтинги, отзывы, рекламу, платежи, открытые SOS).

Запуск:
    python -m app.cleanup            # реальная чистка
    python -m app.cleanup --dry-run  # только показать, сколько удалилось бы (ничего не трогает)

Свойства: идемпотентно (можно гонять хоть каждый день), безопасно по внешним ключам
(дети раньше родителей + NOT EXISTS-гарды, чтобы не осиротить рейтинги/платежи/SOS),
кросс-диалектно (даты — параметром, работает и на Postgres, и на sqlite)."""
import sys
import time
from datetime import timedelta

from sqlalchemy import text

from .config import settings
from .db import engine
from .storage import StorageError, get_storage
from .timeutil import utcnow

DRY = "--dry-run" in sys.argv

# Окна хранения (дни). Аккаунты/репутация/финансы/безопасность-контакты НЕ входят — они вечны.
MSG_DAYS = 30        # сообщения чата (главный объём)
MEDIA_DAYS = 35      # файлы фото/голос на диске (запас к сообщениям)
OTP_DAYS = 1         # коды входа (живут минуты)
TG_DAYS = 1          # telegram-сессии входа
UPLOAD_DAYS = 2      # события загрузки (квота 24ч)
TOKEN_DAYS = 1       # протухшие/отозванные refresh-токены
ADEVENT_DAYS = 90    # показы/клики рекламы (поштучно потом не нужны)
DECLINE_DAYS = 90    # причины отказа водителей от офферов (нужна статистика, не строки)
ANALYTICS_DAYS = 90  # анонимные продуктовые события веб-версии (воронка) — поштучно не нужны
NOTIF_DAYS = 90      # старые уведомления (быстрорастущий объём: строка на каждый пуш)
SOS_DAYS = 180       # ТОЛЬКО закрытые (handled) SOS; открытые не трогаем
REPORT_DAYS = 180    # жалобы (история модерации)
TRIP_DAYS = 180      # старые завершённые поездки/заявки — только без рейтингов/платежей/SOS
# Лист ожидания раннего доступа. Телефон человека, оставленный на САЙТЕ (аккаунта у него нет).
# Хранился вечно: таблицы не было ни в ретеншене, ни в удалении аккаунта — а это персональные
# данные, которые по 152-ФЗ (ст. 5 п. 7) уничтожают по достижении цели сбора (аудит 2026-08-08).
# Цель одна — «позвать на запуск», поэтому:
WAITLIST_INVITED_DAYS = 90    # позвали → цель достигнута, даём запас на повторную волну
WAITLIST_STALE_DAYS = 365     # так и не позвали за год → обещание не сбылось, номер не держим


# Белый список имён таблиц: имена в _rules() — наши константы, НЕ юзер-ввод (инъекции нет).
# Но SQL строится f-строкой по имени таблицы, поэтому явно ограничиваем набор — страховка от
# будущей правки, где в table случайно попадёт внешнее значение (см. review-plan 2026-07-03, P3).
_ALLOWED_TABLES = frozenset({
    "message", "otpcode", "tgauth", "uploadevent", "refreshtoken", "adevent", "offerdecline",
    "sosevent", "report", "tripshare", "requestresponse", "riderequest",
    "booking", "ride", "notification", "instantorder", "parceldelivery",
    "analyticsevent", "waitlistentry",
})


def _rules(now):
    """Список (метка, таблица, WHERE, параметры). Порядок ВАЖЕН: дети раньше родителей."""
    def cut(days):
        return now - timedelta(days=days)
    return [
        # --- Фаза 1: эфемерное (безопасно, основной объём) ---
        ("сообщения чата >30д", "message", "created_at < :c", {"c": cut(MSG_DAYS)}),
        ("коды входа (OTP) >1д", "otpcode", "created_at < :c", {"c": cut(OTP_DAYS)}),
        ("telegram-сессии >1д", "tgauth", "created_at < :c", {"c": cut(TG_DAYS)}),
        ("события загрузок >2д", "uploadevent", "created_at < :c", {"c": cut(UPLOAD_DAYS)}),
        ("протухшие refresh-токены",
         "refreshtoken", "(revoked = true OR expires_at < :now) AND created_at < :c",
         {"now": now, "c": cut(TOKEN_DAYS)}),
        ("показы/клики рекламы >90д", "adevent", "created_at < :c", {"c": cut(ADEVENT_DAYS)}),
        ("причины отказа от офферов >90д", "offerdecline", "created_at < :c", {"c": cut(DECLINE_DAYS)}),
        ("аналитика веб (события) >90д", "analyticsevent", "created_at < :c", {"c": cut(ANALYTICS_DAYS)}),
        ("уведомления >90д", "notification", "created_at < :c", {"c": cut(NOTIF_DAYS)}),
        # Лист ожидания: позванным цель достигнута, непозванным за год — обещание не сбылось.
        ("лист ожидания: позваны >90д", "waitlistentry",
         "invited_at IS NOT NULL AND invited_at < :c", {"c": cut(WAITLIST_INVITED_DAYS)}),
        ("лист ожидания: ждут больше года", "waitlistentry",
         "invited_at IS NULL AND created_at < :c", {"c": cut(WAITLIST_STALE_DAYS)}),
        # --- Фаза 2: старое завершённое (осторожно, с гардами) ---
        ("закрытые SOS >180д", "sosevent", "status = 'handled' AND created_at < :c", {"c": cut(SOS_DAYS)}),
        ("жалобы >180д", "report", "created_at < :c", {"c": cut(REPORT_DAYS)}),
        ("расшаренные поездки >180д", "tripshare", "created_at < :c", {"c": cut(TRIP_DAYS)}),
        ("отклики на заявки >180д", "requestresponse", "created_at < :c", {"c": cut(TRIP_DAYS)}),
        ("завершённые заявки >180д",
         "riderequest",
         "status <> 'active' AND created_at < :c "
         "AND NOT EXISTS (SELECT 1 FROM requestresponse rr WHERE rr.request_id = riderequest.id)",
         {"c": cut(TRIP_DAYS)}),
        # Брони: только done/cancelled, старые, и БЕЗ рейтинга/SOS/шеринга/сообщений/споров
        # (репутацию/безопасность бережём; incident.booking_id — жёсткий FK, без гарда чистка падает).
        ("завершённые брони >180д",
         "booking",
         "status IN ('done', 'cancelled') AND created_at < :c "
         "AND NOT EXISTS (SELECT 1 FROM rating rt WHERE rt.booking_id = booking.id) "
         "AND NOT EXISTS (SELECT 1 FROM sosevent se WHERE se.booking_id = booking.id) "
         "AND NOT EXISTS (SELECT 1 FROM tripshare ts WHERE ts.booking_id = booking.id) "
         "AND NOT EXISTS (SELECT 1 FROM message m WHERE m.booking_id = booking.id) "
         "AND NOT EXISTS (SELECT 1 FROM incident i WHERE i.booking_id = booking.id) "
         # Ниже три ссылки, которых тут не было (аудит 2026-08-07). Каждая — внешний ключ,
         # и любая старая бронь с заработком, платежом или жалобой роняла ВЕСЬ пакет удаления.
         # Пакет берётся по одному и тому же условию каждую ночь → одна такая бронь
         # останавливала чистку броней навсегда, молча: строк удалено 0, ошибка в логе.
         "AND NOT EXISTS (SELECT 1 FROM ledgerentry le WHERE le.booking_id = booking.id) "
         "AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.booking_id = booking.id) "
         "AND NOT EXISTS (SELECT 1 FROM report rp WHERE rp.booking_id = booking.id)",
         {"c": cut(TRIP_DAYS)}),
        # Поездки: старые cancelled/done БЕЗ броней и платежей (финансы бережём; у done обычно есть брони → пропустятся).
        ("старые поездки без броней/платежей >180д",
         "ride",
         "status IN ('cancelled', 'done') AND created_at < :c "
         "AND NOT EXISTS (SELECT 1 FROM booking b WHERE b.ride_id = ride.id) "
         "AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.ride_id = ride.id)",
         {"c": cut(TRIP_DAYS)}),
        # Такси-заказы: старые терминальные БЕЗ финансов/оценок/споров/связей (в основном cancelled/expired;
        # done с долгом/леджером/рейтингом → останутся, финансы и репутацию бережём).
        ("старые терминальные такси-заказы >180д (без финансов/связей)",
         "instantorder",
         "status IN ('done', 'cancelled', 'expired') AND created_at < :c "
         "AND NOT EXISTS (SELECT 1 FROM commissiondebt cd WHERE cd.order_id = instantorder.id) "
         "AND NOT EXISTS (SELECT 1 FROM ledgerentry le WHERE le.order_id = instantorder.id) "
         "AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.order_id = instantorder.id) "
         "AND NOT EXISTS (SELECT 1 FROM rating rt WHERE rt.order_id = instantorder.id) "
         "AND NOT EXISTS (SELECT 1 FROM message m WHERE m.order_id = instantorder.id) "
         "AND NOT EXISTS (SELECT 1 FROM tripshare ts WHERE ts.order_id = instantorder.id) "
         "AND NOT EXISTS (SELECT 1 FROM sosevent se WHERE se.order_id = instantorder.id) "
         "AND NOT EXISTS (SELECT 1 FROM report rp WHERE rp.order_id = instantorder.id) "
         # incident.order_id — тот же жёсткий случай, что и у брони: спор по такси-заказу
         # держал ссылку, а гарда не было (аудит 2026-08-07).
         "AND NOT EXISTS (SELECT 1 FROM incident i WHERE i.order_id = instantorder.id) "
         # promoredemption.used_order_id — жёсткий FK: заказ, на котором потрачена промо-скидка,
         # держит ссылку из погашения (оно живёт вечно, «один код на аккаунт»). Без гарда чистка
         # падает на внешнем ключе.
         "AND NOT EXISTS (SELECT 1 FROM promoredemption pm WHERE pm.used_order_id = instantorder.id) "
         # offerdecline.order_id — тоже жёсткий FK. Сейчас спасает случайность (журнал живёт
         # 90 дней, заказы чистятся после 180), но правило не должно держаться на разнице
         # двух независимых чисел: подняли бы DECLINE_DAYS — и чистка упала бы на внешнем ключе.
         "AND NOT EXISTS (SELECT 1 FROM offerdecline od WHERE od.order_id = instantorder.id)",
         {"c": cut(TRIP_DAYS)}),
        # Доставки: старые терминальные с ЗАКРЫТОЙ комиссией и без спора/оценки (финансы/репутацию
        # бережём). message.parcel_id — жёсткий FK (чат отправитель ↔ курьер): без гарда чистка
        # падает на внешнем ключе, пока переписке нет 30 дней (сообщения удаляются раньше — их
        # правило первое в списке, — но у свежих доставок они ещё живы).
        ("старые доставки >180д (комиссия закрыта, без спора/оценки/переписки)",
         "parceldelivery",
         # `returned` — такой же терминальный статус, как delivered/canceled
         # (`parcels.py: _FINAL_STATUSES`), но в правило чистки его забыли добавить:
         # возвращённые доставки копились в базе вечно (независимая проверка аудита
         # 2026-08-07). Не безопасность, но неаккуратно — и растёт молча.
         "status IN ('delivered', 'canceled', 'returned') AND created_at < :c "
         "AND (commission_kop = 0 OR commission_paid = true) "
         "AND NOT EXISTS (SELECT 1 FROM report rp WHERE rp.parcel_id = parceldelivery.id) "
         "AND NOT EXISTS (SELECT 1 FROM rating rt WHERE rt.parcel_id = parceldelivery.id) "
         "AND NOT EXISTS (SELECT 1 FROM message m WHERE m.parcel_id = parceldelivery.id) "
         # Спор по доставке и публичная ссылка «следить за курьером» тоже держат ссылку
         # на доставку, а гардов не было (аудит 2026-08-07). Ссылку получатель хранит
         # в переписке/SMS — она живёт дольше самой доставки.
         "AND NOT EXISTS (SELECT 1 FROM incident i WHERE i.parcel_id = parceldelivery.id) "
         "AND NOT EXISTS (SELECT 1 FROM tripshare ts WHERE ts.parcel_id = parceldelivery.id)",
         {"c": cut(TRIP_DAYS)}),
    ]


_BATCH = 2000   # чанк удаления: короткая транзакция вместо одной длинной блокировки на всю таблицу


def _delete_batched(table, where, params):
    """Удаляем чанками по _BATCH — каждый чанк своя короткая транзакция (не держим долгий лок и
    не раздуваем WAL на больших таблицах вроде message/notification). `id IN (SELECT ... LIMIT)`
    кросс-диалектно (Postgres/sqlite); коррелированные NOT EXISTS во WHERE продолжают работать."""
    sql = text(f"DELETE FROM {table} WHERE id IN (SELECT id FROM {table} WHERE {where} LIMIT :_batch)")
    total = 0
    while True:
        with engine.begin() as conn:
            n = conn.execute(sql, {**params, "_batch": _BATCH}).rowcount or 0
        total += n
        if n < _BATCH:      # неполный чанк → больше подходящих строк нет
            break
    return total


def _referenced_media_keys() -> set:
    """Ключи файлов, на которые живут ПОСТОЯННЫЕ ссылки из БД (аватар профиля, картинка рекламы).
    Они загружаются через /upload/chat-photo и лежат в области chat/ — но это НЕ эфемерный чат:
    без этого исключения ретеншен через 35 дней молча стирал бы фото профиля у всех давних юзеров.
    URL вида '/media/chat/<файл>' (или абсолютный) → ключ 'chat/<файл>'."""
    keys = set()
    with engine.begin() as conn:
        for sql in ('SELECT avatar_url FROM "user" WHERE avatar_url <> \'\'',
                    "SELECT image_url FROM ad WHERE image_url <> ''"):
            for (url,) in conn.execute(text(sql)):
                if url and "/media/" in url:
                    keys.add(url.split("/media/", 1)[1])
    return keys


def _clean_media():
    """Удаляем публичные медиа (фото/голос) старше MEDIA_DAYS — на диске И в S3 (через storage).
    Драйвер-доки (docs, приватные) НЕ трогаем. В S3-режиме без этого объекты копились бы вечно."""
    cutoff = time.time() - MEDIA_DAYS * 86400
    removed, freed = 0, 0
    try:
        keep = _referenced_media_keys()
    except Exception as e:  # noqa: BLE001 — БД недоступна: без списка ссылок удалять опасно, пропускаем
        print(f"  медиа-файлы: не смог собрать живые ссылки — пропуск ({type(e).__name__}: {e})")
        return
    try:
        storage = get_storage()
        for key, size in storage.iter_old(["voice", "chat"], cutoff):
            if key in keep:            # аватар/картинка рекламы — живая ссылка, не эфемерный чат
                continue
            if not DRY:
                storage.delete(key)
            removed += 1
            freed += size
    except StorageError as e:      # облако недоступно — чистку медиа пропускаем, БД уже вычищена
        print(f"  медиа-файлы: хранилище недоступно — пропуск ({e})")
        return
    verb = "удалилось бы" if DRY else "удалено"
    print(f"  медиа-файлы (фото/голос >{MEDIA_DAYS}д): {verb} {removed} шт, {freed // (1024 * 1024)} МБ")



# ------------------------------ уведомления о закрытии автоматом ------------------------------
# Правило, выведенное в этом же аудите: если объект человека закрывает автомат, человек обязан
# об этом узнать. Иначе он ждёт: отправитель ждёт курьера по посылке, которую сняли с ленты
# месяц назад; пассажир ждёт отклика по заявке, которая уже закрыта.
#
# Уведомление идёт через единую точку `services.push_notification` — то есть остаётся записью
# в Центре уведомлений, а не только пушем «как получится»: ночью пуш почти наверняка не увидят.
#
# Куда ведёт тап, если экран не совпадает с поводом написать: у одного объекта поводов бывает
# несколько — «заказ закрыт» и «заказ закрыт, комиссия начислена» открывают один и тот же заказ.
# Пустая строка = вести некуда (в Центре уведомлений это просто текст без перехода).
_NOTIFY_LINK = {"order_done": "order", "debt": ""}


def _notify_closed(kind: str, rows: list[tuple[int, int]]) -> None:
    """rows: (id объекта, id человека). Молча пропускаем, если уведомить не вышло:
    уведомление вторично, чистка важнее и падать из-за него не должна."""
    if not rows:
        return
    try:
        from sqlmodel import Session

        from .services import push_notification
        texts = {
            "request": (
                "Заявка закрыта", "Заявка ябылды",
                "Время поездки прошло, и никто не откликнулся. Создай новую — водители увидят.",
                "Сәфәр ваҡыты үтте, бер кем дә яуап бирмәне. Яңыһын булдыр — водителдәр күрер.",
            ),
            "parcel": (
                "Посылку никто не взял", "Бандеролде бер кем дә алманы",
                "Она снята с ленты курьеров. Если всё ещё нужно отправить — создай заново.",
                "Ул курьерҙар таҫмаһынан алынды. Әгәр ебәрергә кәрәк булһа — яңынан булдыр.",
            ),
            "ride": (
                "Поездка закрыта", "Сәфәр ябылды",
                "Время выезда прошло, попутчиков не было. Опубликуй новую, когда поедешь.",
                "Сығыу ваҡыты үтте, юлдаштар булманы. Киткәндә яңыһын баҫтыр.",
            ),
            "order": (
                "Заказ закрыт", "Заказ ябылды",
                "Поездку долго не завершали, и мы закрыли её сами. Новые заказы снова доступны.",
                "Сәфәр оҙаҡ тамамланманы, беҙ уны үҙебеҙ яптыҡ. Яңы заказдар тағы асыҡ.",
            ),
            # Отдельный текст для поездки, закрытой как СОСТОЯВШАЯСЯ: по ней начислена комиссия,
            # и водитель обязан узнать об этом от нас, а не обнаружить сумму в кабинете.
            "order_done": (
                "Поездка закрыта автоматически", "Сәфәр автоматик рәүештә ябылды",
                "Ты не нажал «Завершил», и мы закрыли поездку сами. Комиссия по ней начислена. "
                "Если поездки не было — напиши в поддержку, спишем.",
                "Һин «Тамамланды» төймәһенә баҫманың, беҙ сәфәрҙе үҙебеҙ яптыҡ. Комиссия иҫәпләнде. "
                "Әгәр сәфәр булмаған булһа — ярҙам хеҙмәтенә яҙ, алып ташлайбыҙ.",
            ),
            # «Я оплатил» без подтверждения деньгами протухло → долг снова неоплачен.
            "debt": (
                "Оплата комиссии не подтвердилась", "Комиссия түләүе раҫланманы",
                "Мы так и не увидели твой перевод. Долг снова числится неоплаченным — "
                "проверь платёж или переведи ещё раз.",
                "Беҙ һинең күсереүеңде һаман күрмәнек. Бурыс тағы түләнмәгән булып тора — "
                "түләүҙе тикшер йәки яңынан күсер.",
            ),
        }[kind]
        link = _NOTIFY_LINK.get(kind, kind)
        with Session(engine) as session:
            for obj_id, user_id in rows:
                if not user_id:
                    continue
                push_notification(session, user_id, "system", texts[0], texts[1],
                                  texts[2], texts[3], ref_kind=link,
                                  ref_id=(obj_id if link else None))
    except Exception as ex:  # noqa: BLE001 — чистка важнее уведомления
        print(f"  уведомления о закрытии ({kind}): ОШИБКА {type(ex).__name__}: {ex}")


# ------------------------------ закрытие прошедших поездок ------------------------------
# Не удаление, а смена статуса — поэтому отдельно от правил ретеншена выше.
#
# Что было не так (найдено на проде 2026-08-03). У поездки не существовало состояния
# «просрочена»: только active / done / cancelled. Лента прячет поездку через
# RIDE_PAST_GRACE_HOURS после времени выезда, но статус ей не менял НИКТО. Итог — три поездки
# от 5, 6 и 15 июля висели active третью неделю: пассажирам не видно, а у водителя в «моих
# поездках» они навсегда числились текущими. Закрыть их было нечем.
#
# Правило разное, потому что случаи разные:
#   есть подтверждённые брони -> done     поездка состоялась, её просто забыли закрыть;
#   броней нет               -> expired  никто не поехал, «выполненной» её называть нечестно.
# Разделение не косметическое: на done строится статистика поездок и рейтинг водителя,
# и приписать туда несостоявшиеся — значит соврать в цифрах.
RIDE_CLOSE_GRACE_HOURS = 6   # запас к RIDE_PAST_GRACE_HOURS=2: выехать могли позже, чем объявили


def close_past_rides(now=None) -> tuple:
    """Закрыть поездки, время которых прошло. Возвращает (сколько done, сколько expired)."""
    now = now or utcnow()
    cut = now - timedelta(hours=RIDE_CLOSE_GRACE_HOURS)
    done = expired = 0
    with engine.begin() as conn:
        # done: прошедшие активные, у которых была хотя бы одна подтверждённая бронь
        done = conn.execute(text(
            "UPDATE ride SET status = 'done' "
            "WHERE status = 'active' AND depart_at < :cut AND EXISTS ("
            "  SELECT 1 FROM booking b WHERE b.ride_id = ride.id AND b.status = 'confirmed')"
        ), {"cut": cut}).rowcount or 0
        # expired: всё остальное прошедшее и активное — никто не поехал
        ride_victims = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, driver_id FROM ride WHERE status = 'active' AND depart_at < :cut"
        ), {"cut": cut}).all()]
        expired = conn.execute(text(
            "UPDATE ride SET status = 'expired' "
            "WHERE status = 'active' AND depart_at < :cut"
        ), {"cut": cut}).rowcount or 0
        # Брони закрытых поездок. Без этого закрывался только «родитель»: поездка становилась
        # done/expired, а бронь навсегда оставалась pending — и у пассажира в приложении вечно
        # висело «ждём подтверждения» по поездке, которая уехала неделю назад (аудит 2026-08-06).
        # Подтверждённые на состоявшейся поездке → done (как в ручном /rides/{id}/complete),
        # всё остальное живое → cancelled: человек не поехал, «выполненной» её звать нечестно.
        conn.execute(text(
            "UPDATE booking SET status = 'done' "
            "WHERE status = 'confirmed' AND EXISTS ("
            "  SELECT 1 FROM ride r WHERE r.id = booking.ride_id "
            "  AND r.status = 'done' AND r.depart_at < :cut)"
        ), {"cut": cut})
        conn.execute(text(
            "UPDATE booking SET status = 'cancelled' "
            "WHERE status IN ('pending', 'confirmed') AND EXISTS ("
            "  SELECT 1 FROM ride r WHERE r.id = booking.ride_id "
            "  AND r.status IN ('done', 'expired') AND r.depart_at < :cut)"
        ), {"cut": cut})
    _notify_closed("ride", ride_victims)
    return done, expired


# ------------------------------ закрытие прошедших заявок ------------------------------
# Та же болезнь, что у поездок (выше), только у заявок пассажира — и найдена она позже,
# 2026-08-06. Заявка не закрывалась НИКОГДА: «нужна машина завтра в 8» трёхмесячной давности
# висела в ленте водителей, водитель откликался, а человек давно уехал. Плюс с появлением
# потолка на число активных заявок вечная заявка превращала его в пожизненный запрет.
#
# Сроков два, потому что случая два: у заявки с желаемым временем считаем от него (плюс запас
# на «выехал позже, чем просил»), у заявки без времени — от создания.
# Те же числа читает лента (`routers/requests.live_request_conds`): чистка идёт раз в сутки,
# а из ленты прошедшая заявка обязана уходить сразу.
def close_past_requests(now=None) -> int:
    """Закрыть заявки, время которых прошло. Возвращает, сколько закрыто."""
    now = now or utcnow()
    time_cut = now - timedelta(hours=settings.request_grace_hours)
    created_cut = now - timedelta(days=settings.request_no_time_days)
    with engine.begin() as conn:
        # Кого закрываем — собираем ДО обновления: после него признак 'active' пропадёт.
        victims = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, passenger_id FROM riderequest "
            "WHERE status = 'active' AND ("
            "  (desired_at IS NOT NULL AND desired_at < :tcut) OR "
            "  (desired_at IS NULL AND created_at < :ccut))"
        ), {"tcut": time_cut, "ccut": created_cut}).all()]
        n = conn.execute(text(
            "UPDATE riderequest SET status = 'expired' "
            "WHERE status = 'active' AND ("
            "  (desired_at IS NOT NULL AND desired_at < :tcut) OR "
            "  (desired_at IS NULL AND created_at < :ccut))"
        ), {"tcut": time_cut, "ccut": created_cut}).rowcount or 0
        # Отклики закрытых заявок. Та же болезнь, что у броней: заявка закрывалась, а отклик
        # навсегда оставался «в торге» — водитель видел у себя открытый торг по заявке,
        # которой уже месяц нет (аудит 2026-08-06).
        conn.execute(text(
            "UPDATE requestresponse SET status = 'expired' "
            "WHERE status = 'offered' AND EXISTS ("
            "  SELECT 1 FROM riderequest rq WHERE rq.id = requestresponse.request_id "
            "  AND rq.status = 'expired')"
        ))
    _notify_closed("request", victims)
    return n


# ------------------------------ закрытие протухших посылок ------------------------------
# Та же болезнь, что у поездок и заявок. Никем не взятая посылка висела в ленте курьеров
# вечно: через три месяца курьер её брал и звонил человеку, который давно отвёз всё сам.
# Сроки читает и лента (`routers/parcels.live_parcel_conds`) — чистка идёт раз в сутки,
# а из ленты протухшая обязана уходить сразу.
def close_stale_parcels(now=None) -> int:
    """Закрыть открытые посылки, которых никто не взял вовремя. Возвращает, сколько закрыто."""
    now = now or utcnow()
    created_cut = now - timedelta(days=settings.parcel_open_days)
    with engine.begin() as conn:
        # Только по возрасту объявления. На «нужно доставить к дате» здесь НЕ смотрим:
        # просроченная посылка остаётся в ленте с флагом overdue («срок сорван, но везти
        # надо») — это живая механика, а не мусор. См. live_parcel_conds в routers/parcels.py.
        victims = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, sender_id FROM parceldelivery "
            "WHERE status = 'created' AND created_at < :ccut"
        ), {"ccut": created_cut}).all()]
        n = conn.execute(text(
            "UPDATE parceldelivery SET status = 'canceled' "
            "WHERE status = 'created' AND created_at < :ccut"
        ), {"ccut": created_cut}).rowcount or 0
        # «Везу обратно» без выхода (независимая проверка аудита 2026-08-07). Из `returning`
        # ведут только две двери, и обе ручные: курьер жмёт «Вернул отправителю» либо
        # вмешивается админ. Курьер пропал на обратном пути — заявка висит, пока Александр
        # не разберёт её сам. Для `in_transit` это решено осознанно (посылка у человека
        # в руках, автоматика тут вредна), про `returning` просто не подумали: там посылка
        # едет НАЗАД, отправитель ничего не ждёт, и держать заявку живой смысла нет.
        # Даём тот же щедрый срок, что и объявлению, и закрываем как возвращённую.
        stuck = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, sender_id FROM parceldelivery "
            "WHERE status = 'returning' AND created_at < :ccut"
        ), {"ccut": created_cut}).all()]
        n += conn.execute(text(
            "UPDATE parceldelivery SET status = 'returned' "
            "WHERE status = 'returning' AND created_at < :ccut"
        ), {"ccut": created_cut}).rowcount or 0
        victims += stuck
    _notify_closed("parcel", victims)
    return n



# ------------------------------ закрытие забытых такси-заказов ------------------------------
# Самый дорогой из зависших: водитель не нажал «завершил» (сел телефон, удалил приложение) —
# и заказ висит активным вечно. Дальше две беды сразу (аудит 2026-08-06):
#   • у водителя стоит защита «нельзя взять второй заказ при активном первом» — он НАВСЕГДА
#     терял возможность работать и без поддержки выбраться из этого не мог;
#   • у пассажира в приложении оставалось «вы едете» по поездке месячной давности.
#
# Две правды у закрытия, как и у поездок:
#   accepted / arriving  -> cancelled   водитель так и не посадил человека, поездки не было;
#   onboard              -> done        человек сидел в машине — поездка почти наверняка была;
#   scheduled в прошлом  -> expired     предзаказ на время, которое давно прошло.
#
# ВАЖНО про деньги (пересмотрено, аудит 2026-08-07). Раньше комиссию здесь не начисляли вообще:
# «брать за поездку, которую никто не подтвердил, хуже, чем не взять». Но onboard ставит САМ
# водитель — это и есть его подтверждение, что человек сел в машину, и ровно поэтому мы
# закрываем такой заказ как СОСТОЯВШИЙСЯ (done), а не отменяем. Получалось нечестно в обе
# стороны: поездка идёт в статистику и рейтинг как выполненная, а комиссия — нет. Хуже того,
# это был рабочий бесплатный тариф: не жми «Завершил» на последней поездке за день — утром она
# закроется сама, без комиссии и без штрафа. Одна бесплатная поездка в сутки, повторяемо.
# Теперь начисляем — но по обычному пути (debt.accrue_for_order, идемпотентно), и спорный
# случай админ по-прежнему закрывает списанием: POST /admin/debts/{id}/forgive.
def _accrue_auto_done(order_ids: list[int]) -> None:
    """Комиссия за поездки, которые закрыл автомат. Ошибку глотаем: чистка важнее начисления,
    а пропущенное начисление подберёт следующий прогон (accrue_for_order идемпотентен)."""
    if not order_ids:
        return
    try:
        from sqlmodel import Session

        from . import debt as debt_mod
        from .models import InstantOrder
        n = 0
        with Session(engine) as session:
            for oid in order_ids:
                order = session.get(InstantOrder, oid)
                if order is None:
                    continue
                d = debt_mod.accrue_for_order(session, order)
                if d is not None and not d.note:
                    # След для админа: по этой поездке водитель «Завершил» не нажимал.
                    d.note = "Поездку закрыл автомат (водитель не нажал «Завершил»)"
                    session.add(d)
                    session.commit()
                    n += 1
        print(f"  комиссия за авто-закрытые заказы: начислено {n}")
    except Exception as ex:  # noqa: BLE001 — чистка важнее начисления
        print(f"  комиссия за авто-закрытые заказы: ОШИБКА {type(ex).__name__}: {ex}")


def close_stale_orders(now=None) -> int:
    """Закрыть такси-заказы, зависшие в активном состоянии. Возвращает, сколько закрыто."""
    now = now or utcnow()
    cut = now - timedelta(hours=settings.taxi_stale_hours)
    closed = 0
    with engine.begin() as conn:
        # Водителю важнее всех: пока заказ висел, он не мог взять ни одного нового.
        # Списки разные, потому что и правда разная: несостоявшийся заказ и состоявшаяся
        # поездка с комиссией — это два разных письма человеку.
        cancel_victims = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, driver_id FROM instantorder "
            "WHERE status IN ('accepted', 'arriving') AND created_at < :cut"
        ), {"cut": cut}).all()]
        done_victims = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, driver_id FROM instantorder "
            "WHERE status = 'onboard' AND created_at < :cut"
        ), {"cut": cut}).all()]
        closed += conn.execute(text(
            "UPDATE instantorder SET status = 'cancelled' "
            "WHERE status IN ('accepted', 'arriving') AND created_at < :cut"
        ), {"cut": cut}).rowcount or 0
        # done_at обязателен: без него поездка выпадает из заработка водителя и из стажа для
        # лесенки комиссии — комиссию бы взяли, а поездку человек в своей истории не нашёл.
        closed += conn.execute(text(
            "UPDATE instantorder SET status = 'done', done_at = COALESCE(done_at, :now) "
            "WHERE status = 'onboard' AND created_at < :cut"
        ), {"cut": cut, "now": now}).rowcount or 0
        closed += conn.execute(text(
            "UPDATE instantorder SET status = 'expired' "
            "WHERE status = 'scheduled' AND scheduled_at IS NOT NULL AND scheduled_at < :cut"
        ), {"cut": cut}).rowcount or 0
    _accrue_auto_done([oid for oid, _ in done_victims])
    _notify_closed("order", cancel_victims)
    _notify_closed("order_done", done_victims)
    return closed


# ------------------------------ протухшее «Я оплатил» ------------------------------
# Долг по комиссии — единственная выручка платформы, и держится она на честном слове:
# водитель жмёт «Я оплатил», блокировка снимается, Александр потом подтверждает перевод.
# Слово без срока превращалось в способ не платить никогда (аудит 2026-08-07): pending не
# блокировал, не протухал, а счётчик обещаний рос только при новом нажатии — то есть никогда,
# пока админ вручную не отклонит. Возвращаем протухшие заявления в unpaid: водитель снова
# видит долг и может заявить оплату ещё раз, но уже под счётчиком (см. debt.taxi_block_reason).
def expire_stale_declares(now=None) -> int:
    """Вернуть в unpaid долги, где «Я оплатил» так и не подтвердилось деньгами. Сколько вернули."""
    now = now or utcnow()
    from .debt import DECLARE_TRUST_DAYS

    edge = now - timedelta(days=DECLARE_TRUST_DAYS)
    # COALESCE — на случай pending без заявления (ручная правка/старые строки): у них отсчёт
    # идёт от начисления, «вечного доверия» не остаётся ни у кого.
    where = "status = 'pending' AND COALESCE(paid_declared_at, created_at) < :edge"
    with engine.begin() as conn:
        victims = [(r[0], r[1]) for r in conn.execute(text(
            f"SELECT id, driver_id FROM commissiondebt WHERE {where}"), {"edge": edge}).all()]
        # declare_count НЕ трогаем: счётчик обещаний — память о том, сколько раз доверяли.
        n = conn.execute(text(
            f"UPDATE commissiondebt SET status = 'unpaid', paid_declared_at = NULL WHERE {where}"
        ), {"edge": edge}).rowcount or 0
    # Одно письмо на водителя, а не на каждую строку долга: у недели их бывает десяток.
    seen: set = set()
    per_driver = []
    for debt_id, driver_id in victims:
        if driver_id and driver_id not in seen:
            seen.add(driver_id)
            per_driver.append((debt_id, driver_id))
    _notify_closed("debt", per_driver)
    return n


def main():
    now = utcnow()
    mode = "СУХОЙ ПРОГОН (ничего не удаляется)" if DRY else "РЕАЛЬНАЯ чистка"
    print(f"=== Ретеншен-чистка Юлдаш · {mode} · {now.isoformat()} ===")
    total = 0
    for label, table, where, params in _rules(now):
        # Имя таблицы подставляется в SQL f-строкой → пускаем только заведомо свои имена.
        if table not in _ALLOWED_TABLES:
            print(f"  {label}: ПРОПУЩЕНО — таблица '{table}' не в белом списке")
            continue
        try:
            if DRY:
                with engine.begin() as conn:
                    n = conn.execute(text(f"SELECT count(*) FROM {table} WHERE {where}"), params).scalar() or 0
                print(f"  {label}: удалилось бы {n}")
            else:
                n = _delete_batched(table, where, params)   # чанками: короткие транзакции, без долгого лока
                print(f"  {label}: удалено {n}")
            total += n
        except Exception as e:  # одна таблица упала — не роняем всю чистку
            print(f"  {label}: ОШИБКА {type(e).__name__}: {e}")
    _clean_media()
    # Прошедшие поездки: не удаляем, а закрываем — иначе висят active вечно (см. выше).
    if DRY:
        print("  прошедшие поездки: в сухом прогоне не трогаем")
    else:
        try:
            d, e = close_past_rides(now)
            print(f"  прошедшие поездки: закрыто как состоявшиеся {d}, как несостоявшиеся {e}")
        except Exception as ex:  # noqa: BLE001 — не роняем всю чистку
            print(f"  прошедшие поездки: ОШИБКА {type(ex).__name__}: {ex}")
        try:
            n = close_past_requests(now)
            print(f"  прошедшие заявки: закрыто {n}")
        except Exception as ex:  # noqa: BLE001 — не роняем всю чистку
            print(f"  прошедшие заявки: ОШИБКА {type(ex).__name__}: {ex}")
        try:
            n = close_stale_parcels(now)
            print(f"  протухшие посылки: закрыто {n}")
        except Exception as ex:  # noqa: BLE001 — не роняем всю чистку
            print(f"  протухшие посылки: ОШИБКА {type(ex).__name__}: {ex}")
        try:
            n = close_stale_orders(now)
            print(f"  забытые такси-заказы: закрыто {n}")
        except Exception as ex:  # noqa: BLE001 — не роняем всю чистку
            print(f"  забытые такси-заказы: ОШИБКА {type(ex).__name__}: {ex}")
        try:
            n = expire_stale_declares(now)
            print(f"  протухшие заявления «Я оплатил»: возвращено в долг {n}")
        except Exception as ex:  # noqa: BLE001 — не роняем всю чистку
            print(f"  протухшие заявления «Я оплатил»: ОШИБКА {type(ex).__name__}: {ex}")
    print(f"=== Итог: строк {'к удалению' if DRY else 'удалено'} — {total} ===")


if __name__ == "__main__":
    main()
