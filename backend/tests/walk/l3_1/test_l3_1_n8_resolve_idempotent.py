"""N8 (независимое ревью): разбор жалобы (`resolve`/`reject`) не был идемпотентен и не
был защищён замком строки.

Было: повторный клик «подтвердить» снова шлёт пуши («Разбор закончен», «Комиссия списана»/
«возвращена» — ВТОРОЙ раз, хотя деньги уже вернули в первый), снова ставит/снимает паузу,
снова крутит лестницу. Два ОДНОВРЕМЕННЫХ клика на PostgreSQL без `with_for_update` оба проходят
проверку «жалоба не разобрана» и оба делают возврат комиссии — двойная выплата курьеру/
водителю за счёт платформы (согласовано с F3, leaf-1.1: та сторона — `debt.py`, эта —
`routers/safety.py`, точка входа).

Правка: строка жалобы блокируется (`with_for_update`), статус проверяется ДО побочек —
terminal-статус (resolved/rejected) отвечает текущим состоянием и ничего не повторяет.
"""
import os
import threading

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import DriverProfile, LedgerEntry, LedgerKind, ParcelDelivery, Report, UserRole


def _unpaid_parcel_report(sender_id: int, courier_id: int) -> tuple[int, int]:
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=sender_id, courier_id=courier_id, from_city="Сибай",
                           to_city="Баймак", status="delivered",
                           commission_paid=True, commission_kop=500)
        s.add(p)
        s.commit()
        s.refresh(p)
        report = Report(reporter_id=sender_id, target_user_id=courier_id, category="unpaid",
                        parcel_id=p.id, status="new")
        s.add(report)
        s.commit()
        s.refresh(report)
        return report.id, p.id


def test_повторное_подтвердить_не_повторяет_возврат_комиссии(client, user_factory, monkeypatch):
    sender = user_factory("N8Sender1")
    courier = user_factory("N8Courier1")
    admin = user_factory("N8Admin1", role=UserRole.admin)
    rid, pid = _unpaid_parcel_report(sender["id"], courier["id"])

    pushes = []
    import app.routers.safety as safety
    monkeypatch.setattr(safety, "push_notification",
                        lambda session, uid, kind, t1, t2, b1, b2, **kw: pushes.append(t1))

    r1 = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"], json={"resolution": "ok"})
    r2 = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"], json={"resolution": "ok"})
    assert r1.status_code == 200 and r2.status_code == 200

    with Session(engine) as s:
        entries = s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == courier["id"], LedgerEntry.kind == LedgerKind.adj,
        )).all()
        assert len(entries) == 1, f"комиссия возвращена больше одного раза: {len(entries)} записей"

    refund_pushes = [t for t in pushes if "возвращена" in t or "списана" in t]
    assert len(refund_pushes) == 1, f"повторный разбор снова прислал денежный пуш: {refund_pushes}"


def test_повторное_подтвердить_возвращает_то_же_состояние(client, user_factory):
    sender = user_factory("N8Sender2")
    courier = user_factory("N8Courier2")
    admin = user_factory("N8Admin2", role=UserRole.admin)
    rid, _pid = _unpaid_parcel_report(sender["id"], courier["id"])

    r1 = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"], json={"resolution": "первое"})
    r2 = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"], json={"resolution": "второе"})
    assert r1.json()["status"] == "resolved" == r2.json()["status"]
    assert r1.json()["resolution"] == "первое", (
        "повторный resolve с другим текстом не должен переписывать уже принятое решение"
    )
    assert r2.json()["resolution"] == "первое", "второй вызов должен вернуть ТЕКУЩЕЕ состояние, не применить новое"


def test_отклонить_после_подтвердить_не_отменяет_возврат(client, user_factory):
    """«Смена resolved↔rejected — отдельное осознанное действие» (из ревью): повторный вызов
    другой ручкой на уже решённой жалобе — тоже идемпотентен, не разворачивает решение."""
    sender = user_factory("N8Sender3")
    courier = user_factory("N8Courier3")
    admin = user_factory("N8Admin3", role=UserRole.admin)
    rid, _pid = _unpaid_parcel_report(sender["id"], courier["id"])

    r1 = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"], json={"resolution": "ok"})
    assert r1.status_code == 200 and r1.json()["status"] == "resolved"
    r2 = client.post(f"/admin/reports/{rid}/reject", headers=admin["auth"], json={})
    assert r2.json()["status"] == "resolved", "reject на уже решённой жалобе не должен её перерешать"

    with Session(engine) as s:
        entries = s.exec(select(LedgerEntry).where(LedgerEntry.driver_id == courier["id"])).all()
        assert len(entries) == 1, "возврат не должен исчезнуть/задвоиться при смене ручки"


@pytest.mark.skipif(not os.environ.get("DATABASE_URL", "").startswith("postgres"),
                    reason="настоящая гонка двух транзакций проверяется только на PostgreSQL")
def test_гонка_два_одновременных_resolve_дают_один_возврат_комиссии(user_factory):
    """Два РЕАЛЬНЫХ потока жмут «подтвердить» на ОДНОЙ жалобе одновременно (barrier, два
    соединения с PostgreSQL).

    Честно о пределах этого теста (самопроверка после прогона мутаций, раздел 4а брифа).
    Поток-level barrier синхронизирует СТАРТ, а не момент самого SQL-запроса — реальная
    гонка на уровне СУБД (оба потока проходят `SELECT` ДО того, как другой сделает `COMMIT`)
    здесь НЕ гарантирована детерминированно: мутация, снимающая `with_for_update`, в одном
    прогоне не была поймана этим тестом (одно исполнение успело полностью завершиться раньше
    второго). Тест достоверно доказывает только КОНЕЧНЫЙ результат: сколько ни запускай подряд,
    записей в `LedgerEntry` не больше одной, — то есть идемпотентность по `status` (M18,
    подтверждено) держит инвариант даже когда возможную гонку не удалось форсировать. Сама
    блокировка строки (`with_for_update`) оставлена как defense-in-depth без собственной
    нарочной поломки — см. карточку, раздел «Остаток»."""
    from fastapi.testclient import TestClient
    from app.main import app

    sender = user_factory("N8RaceSender")
    courier = user_factory("N8RaceCourier")
    admin = user_factory("N8RaceAdmin", role=UserRole.admin)
    rid, _pid = _unpaid_parcel_report(sender["id"], courier["id"])

    barrier = threading.Barrier(2)
    results = []

    def _resolve():
        with TestClient(app) as c:
            barrier.wait(timeout=5)
            r = c.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"], json={"resolution": "race"})
            results.append(r)

    t1 = threading.Thread(target=_resolve)
    t2 = threading.Thread(target=_resolve)
    t1.start(); t2.start()
    t1.join(timeout=10); t2.join(timeout=10)

    assert len(results) == 2 and all(r.status_code == 200 for r in results)
    with Session(engine) as s:
        entries = s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == courier["id"], LedgerEntry.kind == LedgerKind.adj,
        )).all()
        assert len(entries) == 1, (
            f"две одновременные транзакции дали {len(entries)} возвратов комиссии вместо одного — "
            "двойная выплата за счёт платформы"
        )
