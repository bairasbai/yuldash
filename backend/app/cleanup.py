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
# Брошенные водителем заказы. Наказание смотрит окно `driver_cancel_window_days` (7 дней),
# но события — это ещё и разбор спора «он вообще приезжал?», поэтому держим кварталом,
# как и причины отказов. Меньше нельзя: админ разбирает жалобы не в тот же день.
DRIVER_CANCEL_DAYS = 90
# Жалобы на цену: сигнал для тарифа, а не дело с участниками. Полгода — больше, чем нужно,
# чтобы увидеть сезон и починить цифру; дальше это просто чужое недовольство в базе.
PRICE_COMPLAINT_DAYS = 180
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
# Волна 29 (2026-08-12). Ниже — то, что росло вечно просто потому, что об этом не спрашивали.
PRETRIP_DAYS = 180   # предрейсовые самопроверки: заявление водителя о себе на конкретный день
WORKDAY_DAYS = 365   # рабочие смены таксиста: год для разбора спорных случаев, дальше не нужны
DIGESTLOG_DAYS = 90  # журнал отправки дневной сводки — чисто служебный след
TEXTFLAG_DAYS = 180  # журнал помеченных текстов: тот же срок, что у жалоб (история модерации)
RECENT_PLACE_DAYS = 180   # «недавние адреса» человека: это его личные места, не архив
# Журнал SMS близким: нужен ровно затем, чтобы считать суточный потолок (`send_family_sms`),
# и переживать удаление контактов. Держим 30 дней — с запасом к суткам счёта, но это не архив.
FAMILY_SMS_DAYS = 30

# Таблицы, которые мы храним БЕССРОЧНО, и причина у каждой. Список нужен не для красоты:
# рядом стоит сторож (`tests/test_nothing_grows_forever.py`), который требует, чтобы КАЖДАЯ
# таблица была либо в ночной чистке, либо здесь — с человеческой причиной. Новая таблица
# → красный тест, пока кто-то не решит её судьбу. До волны 29 такого решения не принимал никто:
# 42 таблицы из 61 просто росли, и понять, что из этого осознанно, было нельзя.
KEEP_FOREVER = {
    # Аккаунт и его прямые части. Стираются целиком при удалении аккаунта (app/account.py).
    "user": "сам аккаунт — живёт, пока человек им пользуется",
    "driverprofile": "профиль водителя = часть аккаунта",
    "courierprofile": "профиль курьера = часть аккаунта",
    "safetyprofile": "страйки и паузы — репутация, действует пока жив аккаунт",
    "trust": "уровень доверия — репутация аккаунта",
    "consent": "согласия: доказательство, что человек соглашался (152-ФЗ)",
    "block": "чёрный список: пока человек не разблокирует сам",
    "trustedcontact": "доверенные близкие — их человек ведёт сам",
    "savedplace": "сохранённые места — их человек завёл сам и сам удаляет",
    "devicetoken": "токены пушей: строка на устройство, снимается при выходе",
    "deviceban": "бан устройства снимает только админ",
    "invitecode": "инвайт-коды круга доверия — их выдаёт и тратит сам человек",
    "referralbonus": "кому уже начисляли бонус — без этого начислим второй раз",
    # Деньги и документы: закон о бухучёте, споры, налоги. Чистить нельзя.
    "payment": "деньги — хранится по закону",
    "ledgerentry": "кошелёк водителя append-only: история денег не редактируется",
    "commissiondebt": "долг по комиссии — деньги",
    "promoredemption": "какой промокод человек уже применял (один на жизнь аккаунта)",
    "promoclaimlog": "какой номер уже брал промокод — след ПЕРЕЖИВАЕТ удаление аккаунта, "
                     "иначе код доится через «удалил — завёл заново» (волна 149)",
    "couponredemption": "погашенные купоны — расчёты с бизнесом",
    "taxiapplication": "заявка таксиста — допуск к работе (580-ФЗ)",
    "courierapplication": "заявка курьера — допуск к работе",
    "pretripcheck": "см. PRETRIP_DAYS: чистим, но с большим окном",
    # Репутация и разборы: их стирание переписало бы историю для второй стороны.
    "rating": "оценки и отзывы — репутация, на неё смотрят пассажиры",
    "appreview": "отзыв о приложении — публичная витрина",
    "incident": "разбор спора: у него всегда есть вторая сторона",
    "supportticket": "переписка с поддержкой — доказательство для обеих сторон",
    "supportmessage": "сообщения обращения — вместе с тикетом",
    # Витрины и справочники: это не персональные данные.
    "ad": "объявление рекламодателя — живёт, пока владелец не снимет сам",
    "partner": "карточка бизнеса в витрине скидок — ведёт её сам владелец",
    "coupon": "купон бизнеса — витрина, живёт пока бизнес его не снял",
    "couponreport": "жалоба на купон — история модерации витрины",
    "promocode": "кампания промокодов — маркетинговая настройка, не персональные данные",
    "medicalpartner": "справочник клиник — точки на карте",
    "pickuppoint": "точки посадки — справочник",
    "settlement": "справочник населённых пунктов",
    "tariff": "тарифы такси — настройка продукта, не персональные данные",
    "taxicity": "города, где включено такси — настройка продукта",
    "routewatch": "подписка на маршрут — человек ведёт её сам",
    "driverschedule": "расписание водителя — он ведёт его сам",
    "recentplace": "см. RECENT_PLACE_DAYS: чистим с окном",
    "taxiworkday": "см. WORKDAY_DAYS: чистим с окном",
    "textflag": "см. TEXTFLAG_DAYS: чистим с окном",
    "dailydigestlog": "см. DIGESTLOG_DAYS: чистим с окном",
}


# Белый список имён таблиц: имена в _rules() — наши константы, НЕ юзер-ввод (инъекции нет).
# Но SQL строится f-строкой по имени таблицы, поэтому явно ограничиваем набор — страховка от
# будущей правки, где в table случайно попадёт внешнее значение (см. review-plan 2026-07-03, P3).
_ALLOWED_TABLES = frozenset({
    "message", "otpcode", "tgauth", "uploadevent", "refreshtoken", "adevent", "offerdecline",
    "drivercancel",
    "sosevent", "report", "tripshare", "requestresponse", "riderequest",
    "booking", "ride", "notification", "instantorder", "parceldelivery",
    "analyticsevent", "waitlistentry", "pricecomplaint",
    "pretripcheck", "taxiworkday", "dailydigestlog", "textflag", "recentplace",
    "familysmslog",
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
        ("брошенные водителем заказы >90д", "drivercancel", "at < :c", {"c": cut(DRIVER_CANCEL_DAYS)}),
        # Жалобы на цену: их ценность — в сумме и причине, а не в том, кто именно написал.
        # Полгода хватает, чтобы поправить тариф по фактам.
        ("жалобы на цену >180д", "pricecomplaint", "created_at < :c",
         {"c": cut(PRICE_COMPLAINT_DAYS)}),
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
         "AND NOT EXISTS (SELECT 1 FROM offerdecline od WHERE od.order_id = instantorder.id) "
         # drivercancel.order_id — тот же жёсткий FK: событие «водитель бросил принятый заказ»
         # живёт 90 дней, заказ — 180. Разница спасает сегодня, но правило не должно держаться
         # на разнице двух независимых чисел (ровно урок соседней строки выше).
         "AND NOT EXISTS (SELECT 1 FROM drivercancel dc WHERE dc.order_id = instantorder.id) "
         # pricecomplaint.order_id — тот же жёсткий FK: жалоба на цену живёт 180 дней, столько
         # же, сколько заказ. Без гарда чистка упала бы на внешнем ключе ровно в тот день,
         # когда обе даты сойдутся.
         "AND NOT EXISTS (SELECT 1 FROM pricecomplaint pc WHERE pc.order_id = instantorder.id)",
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

        # --- Волна 29: то, что росло вечно, потому что об этом не спрашивали ---
        # Предрейсовая самопроверка — заявление водителя о себе на конкретный день. Через полгода
        # она не нужна ни ему, ни нам, а строка копится КАЖДЫЙ рабочий день на каждого водителя.
        ("предрейсовые самопроверки >180д", "pretripcheck", "created_at < :c", {"c": cut(PRETRIP_DAYS)}),
        # Рабочие смены таксиста: год на разбор спорных случаев (жалоба, долг), дальше — объём.
        ("рабочие смены таксиста >365д", "taxiworkday", "day < :c", {"c": cut(WORKDAY_DAYS)}),
        # Журнал отправки дневной сводки — чисто служебный след, к человеку отношения не имеет.
        ("журнал дневной сводки >90д", "dailydigestlog", "day < :c", {"c": cut(DIGESTLOG_DAYS)}),
        # Помеченные тексты: тот же срок, что у жалоб. Разобрал админ или нет — через полгода
        # метка ничего не решает, а таблица растёт с каждым флагом.
        ("помеченные тексты >180д", "textflag", "created_at < :c", {"c": cut(TEXTFLAG_DAYS)}),
        ("журнал SMS близким >30д", "familysmslog", "created_at < :c", {"c": cut(FAMILY_SMS_DAYS)}),
        # «Недавние адреса» — личные места человека (дом, работа, больница). Полгода — это уже
        # не «недавние»; сохранённые места (savedplace) он ведёт сам, их не трогаем.
        ("недавние адреса >180д", "recentplace", "used_at < :c", {"c": cut(RECENT_PLACE_DAYS)}),
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

    Вторая группа — ПРИВАТНЫЕ доказательства (evidence/): фото споров и снимки границ
    ответственности по доставке. Они не чистились НИКОГДА. Посылка старше 180 дней уходит по
    ретеншену, спор разрешается — а снимок с лицом, подъездом и содержимым коробки оставался
    на диске навсегда (аудит 2026-08-08, волна 11). Это прямо против ст. 5 п. 7 152-ФЗ:
    хранить ровно столько, сколько нужно для цели.

    URL '/media/chat/<файл>' → ключ 'chat/<файл>'; '/secure/evidence/<файл>' → 'evidence/<файл>'."""
    keys = set()
    with engine.begin() as conn:
        for sql in ('SELECT avatar_url FROM "user" WHERE avatar_url <> \'\'',
                    "SELECT image_url FROM ad WHERE image_url <> ''"):
            for (url,) in conn.execute(text(sql)):
                if url and "/media/" in url:
                    keys.add(url.split("/media/", 1)[1])
        # Живые ссылки на приватные доказательства. CSV-поля спора разбираем по запятой.
        for sql in ("SELECT evidence_urls FROM incident WHERE evidence_urls <> ''",
                    "SELECT respondent_evidence_urls FROM incident WHERE respondent_evidence_urls <> ''",
                    "SELECT pickup_photo_url FROM parceldelivery WHERE pickup_photo_url <> ''",
                    "SELECT delivery_photo_url FROM parceldelivery WHERE delivery_photo_url <> ''"):
            for (val,) in conn.execute(text(sql)):
                for one in (val or "").split(","):
                    if "/secure/evidence/" in one:
                        keys.add("evidence/" + one.rsplit("/secure/evidence/", 1)[1].strip())
    return keys


def _clean_media():
    """Удаляем медиа старше MEDIA_DAYS, на которые не осталось ссылок — на диске и в S3.

    Области: публичные `voice`/`chat` и приватные `evidence` (фото споров и границ
    ответственности по доставке). Документы водителя и таксиста (`docs`) НЕ трогаем: их
    хранение требует 580-ФЗ, пока человек работает, и стирает их только удаление аккаунта.

    Защита от потери улики — список живых ссылок (`_referenced_media_keys`): пока запись в БД
    ссылается на файл, он остаётся, сколько бы ему ни было лет. Не смогли собрать список —
    чистку пропускаем целиком, а не удаляем вслепую."""
    cutoff = time.time() - MEDIA_DAYS * 86400
    removed, freed = 0, 0
    try:
        keep = _referenced_media_keys()
    except Exception as e:  # noqa: BLE001 — БД недоступна: без списка ссылок удалять опасно, пропускаем
        print(f"  медиа-файлы: не смог собрать живые ссылки — пропуск ({type(e).__name__}: {e})")
        return
    try:
        storage = get_storage()
        for key, size in storage.iter_old(["voice", "chat", "evidence"], cutoff):
            if key in keep:            # живая ссылка (аватар, реклама, доказательство) — не трогаем
                continue
            if not DRY:
                storage.delete(key)
            removed += 1
            freed += size
    except StorageError as e:      # облако недоступно — чистку медиа пропускаем, БД уже вычищена
        print(f"  медиа-файлы: хранилище недоступно — пропуск ({e})")
        return
    verb = "удалилось бы" if DRY else "удалено"
    print(f"  медиа без ссылок (фото/голос/доказательства >{MEDIA_DAYS}д): {verb} {removed} шт, {freed // (1024 * 1024)} МБ")



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
#
# Названия здесь НЕ произвольные: приложение разбирает ref_kind точным списком
# (`SecondaryScreens.openDeepLink`) и молча ничего не делает на незнакомом. Такси-заказ там
# зовётся `instant`; стояло `order` — карточка пружинила под пальцем и никуда не вела
# (аудит 2026-08-08, волна 19). Добавляя вид, сверься со списком в приложении.
#
# Таблица ПОЛНАЯ — запись обязана быть у каждого вида, даже когда она совпадает с его именем.
# Раньше недостающие брались как есть (`.get(kind, kind)`), и сторож
# `tests/test_notifications_lead_somewhere.py` их не видел: он ищет в коде литералы
# `ref_kind="..."`, а тут вид приходит переменной. Так «order» и проскочил мимо сторожа.
# Теперь неизвестный вид падает с KeyError у автора, а не немым тапом у человека.
_NOTIFY_LINK = {"ride": "ride", "request": "request", "parcel": "parcel",
                "order": "instant", "order_done": "instant",
                "order_pax": "instant", "order_done_pax": "instant",
                "parcel_returning": "parcel", "response": "request",
                "booking_closed": "booking", "debt": ""}


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
            # Курьер вёз посылку ОБРАТНО и пропал — вещь у него на руках. Слать сюда текст
            # «никто не взял, создай заново» было прямой неправдой: человек решал, что посылка
            # так и лежит дома, и переставал искать (аудит 2026-08-08, волна 19).
            "parcel_returning": (
                "Посылку так и не вернули", "Бандероль кире ҡайтарылманы",
                "Курьер вёз её обратно, но не отметил возврат. Заявку мы закрыли. "
                "Если посылку не отдали — напиши в поддержку, поможем найти.",
                "Курьер уны кире алып ҡайта ине, әммә ҡайтарыуҙы билдәләмәне. Заявканы яптыҡ. "
                "Әгәр бандеролде бирмәгән булһалар — ярҙам хеҙмәтенә яҙ, табырға булышабыҙ.",
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
            # --- вторая сторона (аудит 2026-08-08, волна 19) ---
            # Автомат закрывал сделку и писал только ОДНОМУ её участнику. Второй оставался
            # с открытым ожиданием в приложении: пассажир ждал подтверждения брони по рейсу,
            # который уехал два дня назад; человек в такси видел «вы едете» по поездке
            # месячной давности; водитель ждал ответа на отклик по несуществующей заявке.
            "booking_closed": (
                "Рейс уехал без ответа", "Сәфәр яуапһыҙ китте",
                "Водитель так и не подтвердил твою бронь, а время выезда прошло. "
                "Посмотри другие поездки — по этому направлению обычно есть ещё.",
                "Водитель һинең броныңды раҫламаны, ә сығыу ваҡыты үтте. "
                "Башҡа сәфәрҙәрҙе ҡара — был йүнәлештә ғәҙәттә тағы бар.",
            ),
            "order_pax": (
                "Заказ закрыт", "Заказ ябылды",
                "Водитель так и не начал поездку, и мы закрыли заказ. "
                "Деньги не списывались. Можно вызвать машину заново.",
                "Водитель сәфәрҙе башламаны, беҙ заказды яптыҡ. "
                "Аҡса алынманы. Машинаны яңынан саҡырырға була.",
            ),
            "order_done_pax": (
                "Поездка завершена", "Сәфәр тамамланды",
                "Водитель не закрыл поездку сам, и мы отметили её выполненной. "
                "Если что-то пошло не так — напиши в поддержку.",
                "Водитель сәфәрҙе үҙе япманы, беҙ уны үтәлгән тип билдәләнек. "
                "Әгәр берәй нәмә дөрөҫ булмаһа — ярҙам хеҙмәтенә яҙ.",
            ),
            "response": (
                "Заявка закрылась", "Заявка ябылды",
                "Пассажир не ответил на твоё предложение, и время заявки прошло. "
                "Твой отклик закрыт — посмотри свежие заявки.",
                "Пассажир һинең тәҡдимеңә яуап бирмәне, заявка ваҡыты үтте. "
                "Откликың ябылды — яңы заявкаларҙы ҡара.",
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
        link = _NOTIFY_LINK[kind]   # см. комментарий к таблице: список полный, дырок быть не должно
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
        #
        # Кого гасим — собираем ДО обновления (после него признак пропадёт). Гасим МОЛЧА —
        # так было до аудита 2026-08-08 (волна 19): человек забронировал, ждал подтверждения,
        # рейс уехал, бронь стала cancelled — и ни уведомления, ни причины, ни времени.
        # Проверено запросом: у пассажира ноль уведомлений, у водителя — «Поездка закрыта».
        #
        # `cancelled_by` НЕ ставим намеренно. Это не чья-то отмена, а протухание по времени;
        # поле читает «Надёжность» (`reliability_for` считает по нему поздние отмены), и записать
        # туда кого-то — значит наказать человека за то, что сделал автомат.
        booking_victims = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, passenger_id FROM booking "
            "WHERE status IN ('pending', 'confirmed') AND EXISTS ("
            "  SELECT 1 FROM ride r WHERE r.id = booking.ride_id "
            "  AND r.status IN ('done', 'expired') AND r.depart_at < :cut)"
        ), {"cut": cut}).all()]
        conn.execute(text(
            "UPDATE booking SET status = 'cancelled', "
            "  cancelled_at = COALESCE(cancelled_at, :now), "
            "  cancel_reason = COALESCE(cancel_reason, 'ride_closed') "
            "WHERE status IN ('pending', 'confirmed') AND EXISTS ("
            "  SELECT 1 FROM ride r WHERE r.id = booking.ride_id "
            "  AND r.status IN ('done', 'expired') AND r.depart_at < :cut)"
        ), {"cut": cut, "now": now})
    _notify_closed("ride", ride_victims)
    _notify_closed("booking_closed", booking_victims)
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
        # Водителю об этом тоже говорим (аудит 2026-08-08, волна 19). Он назвал цену и ждёт
        # ответа; заявка тихо протухала, отклик тихо закрывался, и у него в «моих откликах»
        # висел торг, которого больше нет. Ссылка ведёт на заявку — там видно, что она закрыта.
        resp_victims = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT request_id, driver_id FROM requestresponse "
            "WHERE status = 'offered' AND EXISTS ("
            "  SELECT 1 FROM riderequest rq WHERE rq.id = requestresponse.request_id "
            "  AND rq.status = 'expired')"
        )).all()]
        conn.execute(text(
            "UPDATE requestresponse SET status = 'expired' "
            "WHERE status = 'offered' AND EXISTS ("
            "  SELECT 1 FROM riderequest rq WHERE rq.id = requestresponse.request_id "
            "  AND rq.status = 'expired')"
        ))
    _notify_closed("request", victims)
    _notify_closed("response", resp_victims)
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
    # Два списка, а не один: случаи разные, и текст обязан быть разным. Пропавший на обратном
    # пути курьер держит вещь у себя, а отправитель получал письмо «посылку никто не взял,
    # создай заново» — прямую неправду, из-за которой человек переставал искать вещь
    # (аудит 2026-08-08, волна 19).
    _notify_closed("parcel", victims)
    _notify_closed("parcel_returning", stuck)
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
                # pay_now_allowed=False: поездку закрыл автомат ночью. Короткий срок оплаты
                # тут был бы ловушкой — водитель спит, пуша не видел, а к утру уже в блоке.
                d = debt_mod.accrue_for_order(session, order, pay_now_allowed=False)
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
        #
        # Пассажира тоже уведомляем (аудит 2026-08-08, волна 19). Ровно та беда, что описана
        # в шапке этой функции — «у пассажира в приложении оставалось "вы едете"» — чинилась
        # только со стороны водителя: он получал письмо, пассажир не получал ничего.
        cancel_victims = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, driver_id FROM instantorder "
            "WHERE status IN ('accepted', 'arriving') AND created_at < :cut"
        ), {"cut": cut}).all()]
        cancel_pax = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, passenger_id FROM instantorder "
            "WHERE status IN ('accepted', 'arriving') AND created_at < :cut"
        ), {"cut": cut}).all()]
        done_victims = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, driver_id FROM instantorder "
            "WHERE status = 'onboard' AND created_at < :cut"
        ), {"cut": cut}).all()]
        done_pax = [(r[0], r[1]) for r in conn.execute(text(
            "SELECT id, passenger_id FROM instantorder "
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
        stale_scheduled = [r[0] for r in conn.execute(text(
            "SELECT id FROM instantorder "
            "WHERE status = 'scheduled' AND scheduled_at IS NOT NULL AND scheduled_at < :cut"
        ), {"cut": cut}).all()]
        closed += conn.execute(text(
            "UPDATE instantorder SET status = 'expired' "
            "WHERE status = 'scheduled' AND scheduled_at IS NOT NULL AND scheduled_at < :cut"
        ), {"cut": cut}).rowcount or 0
    _accrue_auto_done([oid for oid, _ in done_victims])
    # Поездки не было → промокод возвращаем человеку (обещание в шапке `promo_ride`).
    # Закрытые как «done» сюда НЕ попадают: там поездка состоялась и скидка отработала.
    from . import promo_ride
    promo_ride.release_ids([oid for oid, _ in cancel_victims] + stale_scheduled)
    _notify_closed("order", cancel_victims)
    _notify_closed("order_done", done_victims)
    _notify_closed("order_pax", cancel_pax)
    _notify_closed("order_done_pax", done_pax)
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


def _clean_stale_presence() -> None:
    """Убрать из GEO-множества водителей без свежего heartbeat.

    У Redis GEO нет срока жизни: точка, записанная один раз, лежит вечно. Heartbeat
    (`presence:hb:<id>`) истекает сам, и матчер по нему отсеивает «залипших», поэтому поломки
    было не видно — а координаты копились. Большинство водителей не снимают тумблер «на линии»,
    а просто закрывают приложение, так что чистки на выходе мало (аудит 2026-08-08, волна 12).

    Удаляем ТОЛЬКО тех, у кого heartbeat уже нет: кто сейчас на линии, останется на месте.
    """
    try:
        from .instant_service import PRESENCE_KEY, _member_driver_id, _redis, presence_alive
        r = _redis()
        if r is None:
            print("  presence: Redis недоступен — пропуск")
            return
        members = r.zrange(PRESENCE_KEY, 0, -1)
        stale = []
        for m in members:
            try:
                did = _member_driver_id(m)
            except (ValueError, IndexError, AttributeError):
                stale.append(m)      # мусорный ключ — тоже нечего хранить
                continue
            if not presence_alive(r, did):
                stale.append(m)
        if stale and not DRY:
            r.zrem(PRESENCE_KEY, *stale)
        verb = "удалилось бы" if DRY else "удалено"
        print(f"  presence без heartbeat: {verb} {len(stale)} из {len(members)}")
    except Exception as e:  # noqa: BLE001 — уборка кэша не вправе ронять всю чистку
        print(f"  presence: пропуск ({type(e).__name__}: {e})")


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
    _clean_stale_presence()
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
