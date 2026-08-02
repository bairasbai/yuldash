# -*- coding: utf-8 -*-
"""Приватность-хвосты из аудита (release-2026-07).

/r/{id} и /r/{id}/preview — публичная OG/preview-витрина поездки: показываем только «живую»
поездку и НЕ «только для своих». История (done/cancelled) и only_trusted по прямому id → 404
(паритет с /rides/{id}, V5): иначе перебор id даёт анонимный скрейпинг графа поездок.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Ride, RideStatus, UserRole


def test_share_endpoints_hide_history_and_only_trusted(client, user_factory):
    driver = user_factory("ShareDrv", role=UserRole.driver)
    from app.timeutil import utcnow
    with Session(engine) as s:
        def mk(status, only_trusted=False):
            r = Ride(driver_id=driver["id"], from_city="Аҡ", to_city="Бе", depart_at=utcnow(),
                     price=100, seats_total=3, seats_left=3, status=status, only_trusted=only_trusted)
            s.add(r); s.commit(); s.refresh(r)
            return r.id
        active_id = mk(RideStatus.active)
        done_id = mk(RideStatus.done)
        trusted_id = mk(RideStatus.active, only_trusted=True)

    # preview (JSON): живая → 200; история и «только для своих» → 404
    assert client.get(f"/r/{active_id}/preview").status_code == 200
    assert client.get(f"/r/{done_id}/preview").status_code == 404
    assert client.get(f"/r/{trusted_id}/preview").status_code == 404
    # OG-страница (HTML): та же политика
    assert client.get(f"/r/{active_id}").status_code == 200
    assert client.get(f"/r/{done_id}").status_code == 404
    assert client.get(f"/r/{trusted_id}").status_code == 404
