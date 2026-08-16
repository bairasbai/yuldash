"""Реклама, у которой кончился оплаченный срок (аудит 2026-08-08, волна 125).

Что было. На 31-й день показы прекращались сами собой — потому что публичная выдача смотрит
на `ends_at`. Но статус объявления оставался `active`, и в кабинете партнёра горело зелёным
«Оплачено · объявление показывается». Уведомления не было никакого.

Человек видит: деньги списаны, в кабинете «показывается», а объявления в приложении нет.
Дальше он идёт в поддержку с обвинением — и по-своему прав: ему никто не сказал, что срок
вышел и можно продлить (а с волны 116 продлить наконец есть чем).

Гасим статус и говорим об этом — той же общей точкой, что и решения модерации.
"""
from __future__ import annotations

import logging

from sqlmodel import Session, select

from .models import Ad
from .timeutil import utcnow

log = logging.getLogger("yuldash")


def expire_finished_ads(session: Session, dry_run: bool = False) -> int:
    """Перевести отжившие объявления в `expired` и предупредить владельцев."""
    from .routers.ads import notify_ad_expired

    now = utcnow()
    rows = session.exec(select(Ad).where(
        Ad.status == "active",
        Ad.ends_at != None,      # noqa: E711 — бессрочные (founder) не трогаем
        Ad.ends_at < now,
    )).all()
    if dry_run or not rows:
        return len(rows)
    for ad in rows:
        ad.status = "expired"
        session.add(ad)
    session.commit()
    for ad in rows:
        notify_ad_expired(session, ad)
    log.info(f"[ADS] срок вышел у {len(rows)} объявлений")
    return len(rows)
