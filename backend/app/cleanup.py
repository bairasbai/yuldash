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
import os
import sys
import time
from datetime import timedelta

from sqlalchemy import text

from .db import engine
from .services import CHAT_DIR, VOICE_DIR
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
ANALYTICS_DAYS = 90  # анонимные продуктовые события веб-версии (воронка) — поштучно не нужны
NOTIF_DAYS = 90      # старые уведомления (быстрорастущий объём: строка на каждый пуш)
SOS_DAYS = 180       # ТОЛЬКО закрытые (handled) SOS; открытые не трогаем
REPORT_DAYS = 180    # жалобы (история модерации)
TRIP_DAYS = 180      # старые завершённые поездки/заявки — только без рейтингов/платежей/SOS


# Белый список имён таблиц: имена в _rules() — наши константы, НЕ юзер-ввод (инъекции нет).
# Но SQL строится f-строкой по имени таблицы, поэтому явно ограничиваем набор — страховка от
# будущей правки, где в table случайно попадёт внешнее значение (см. review-plan 2026-07-03, P3).
_ALLOWED_TABLES = frozenset({
    "message", "otpcode", "tgauth", "uploadevent", "refreshtoken", "adevent",
    "sosevent", "report", "tripshare", "requestresponse", "riderequest",
    "booking", "ride", "notification", "instantorder", "parceldelivery",
    "analyticsevent",
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
        ("аналитика веб (события) >90д", "analyticsevent", "created_at < :c", {"c": cut(ANALYTICS_DAYS)}),
        ("уведомления >90д", "notification", "created_at < :c", {"c": cut(NOTIF_DAYS)}),
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
        # Брони: только done/cancelled, старые, и БЕЗ рейтинга/SOS/шеринга/сообщений (репутацию/безопасность бережём).
        ("завершённые брони >180д",
         "booking",
         "status IN ('done', 'cancelled') AND created_at < :c "
         "AND NOT EXISTS (SELECT 1 FROM rating rt WHERE rt.booking_id = booking.id) "
         "AND NOT EXISTS (SELECT 1 FROM sosevent se WHERE se.booking_id = booking.id) "
         "AND NOT EXISTS (SELECT 1 FROM tripshare ts WHERE ts.booking_id = booking.id) "
         "AND NOT EXISTS (SELECT 1 FROM message m WHERE m.booking_id = booking.id)",
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
         "AND NOT EXISTS (SELECT 1 FROM report rp WHERE rp.order_id = instantorder.id)",
         {"c": cut(TRIP_DAYS)}),
        # Доставки: старые терминальные с ЗАКРЫТОЙ комиссией и без спора/оценки (финансы/репутацию бережём).
        ("старые доставки >180д (комиссия закрыта, без спора/оценки)",
         "parceldelivery",
         "status IN ('delivered', 'canceled') AND created_at < :c "
         "AND (commission_kop = 0 OR commission_paid = true) "
         "AND NOT EXISTS (SELECT 1 FROM report rp WHERE rp.parcel_id = parceldelivery.id) "
         "AND NOT EXISTS (SELECT 1 FROM rating rt WHERE rt.parcel_id = parceldelivery.id)",
         {"c": cut(TRIP_DAYS)}),
    ]


def _clean_media():
    """Удаляем файлы фото/голос старше MEDIA_DAYS (по времени модификации). Драйвер-доки НЕ трогаем."""
    cutoff = time.time() - MEDIA_DAYS * 86400
    removed, freed = 0, 0
    for d in (VOICE_DIR, CHAT_DIR):
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            path = os.path.join(d, name)
            try:
                if os.path.isfile(path) and os.path.getmtime(path) < cutoff:
                    size = os.path.getsize(path)
                    if not DRY:
                        os.remove(path)
                    removed += 1
                    freed += size
            except OSError:
                pass
    verb = "удалилось бы" if DRY else "удалено"
    print(f"  медиа-файлы (фото/голос >35д): {verb} {removed} шт, {freed // (1024 * 1024)} МБ")


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
            with engine.begin() as conn:
                if DRY:
                    n = conn.execute(text(f"SELECT count(*) FROM {table} WHERE {where}"), params).scalar() or 0
                    print(f"  {label}: удалилось бы {n}")
                else:
                    n = conn.execute(text(f"DELETE FROM {table} WHERE {where}"), params).rowcount
                    print(f"  {label}: удалено {n}")
                total += n
        except Exception as e:  # одна таблица упала — не роняем всю чистку
            print(f"  {label}: ОШИБКА {type(e).__name__}: {e}")
    _clean_media()
    print(f"=== Итог: строк {'к удалению' if DRY else 'удалено'} — {total} ===")


if __name__ == "__main__":
    main()
