"""N4 (независимое ревью): двойное нажатие SOS создаёт ДВА независимых события (это
осознанно и правильно — «жизнь дороже дедупа на входе»), но без склейки на выходе дежурный,
принявший первое, ещё час получает от эскалатора «⏰ SOS НЕ ПРИНЯТ» по второму — тому же
самому случаю. Ложная тревога приучает не реагировать на настоящие повторы.

Правка: `admin_sos_handle` («Принял») закрывает дубли ТОГО ЖЕ человека в окне
±`SOS_DUPLICATE_WINDOW_MIN` минут вместе с основным событием.

Последний тест — настоящая гонка на PostgreSQL: два реальных одновременных POST /sos
(два потока, два соединения) не должны видеть друг друга никаким «дедупом на входе» —
каждый пишет СВОЮ запись независимо (контроль того, что склейка живёт только на выходе,
при «принял», а не прячет сигнал на входе)."""
import os
import threading

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import SosEvent, UserRole


def test_принял_закрывает_дубль_того_же_человека_рядом_по_времени(client, user_factory, monkeypatch):
    monkeypatch.setattr("app.routers.safety.send_text", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)
    u = user_factory("N4User1")
    admin = user_factory("N4Admin1", role=UserRole.admin)

    r1 = client.post("/sos", headers=u["auth"], json={"category": "other"})
    r2 = client.post("/sos", headers=u["auth"], json={"category": "other"})
    assert r1.status_code == 200 and r2.status_code == 200
    id1, id2 = r1.json()["id"], r2.json()["id"]

    h = client.post(f"/admin/sos/{id1}/handle", headers=admin["auth"], json={"note": "принял"})
    assert h.status_code == 200, h.text
    assert id2 in h.json().get("closed_with", []), "дубль не закрылся вместе с основным событием"

    with Session(engine) as s:
        e2 = s.get(SosEvent, id2)
        assert e2.status == "handled", "второй сигнал остался открытым — эскалатор продолжит его дёргать"
        assert str(id1) in (e2.handled_note or ""), "в заметке дубля должна быть ссылка на основной сигнал"


def test_дубль_другого_человека_не_закрывается(client, user_factory, monkeypatch):
    """Контроль: склейка — строго по ОДНОМУ человеку, не по времени вообще."""
    monkeypatch.setattr("app.routers.safety.send_text", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)
    u1 = user_factory("N4User2")
    u2 = user_factory("N4User3")
    admin = user_factory("N4Admin2", role=UserRole.admin)

    r1 = client.post("/sos", headers=u1["auth"], json={"category": "other"})
    r2 = client.post("/sos", headers=u2["auth"], json={"category": "other"})
    id1, id2 = r1.json()["id"], r2.json()["id"]

    h = client.post(f"/admin/sos/{id1}/handle", headers=admin["auth"])
    assert id2 not in h.json().get("closed_with", [])
    with Session(engine) as s:
        assert s.get(SosEvent, id2).status == "open", "чужой сигнал не должен закрываться заодно"


def test_давний_сигнал_того_же_человека_не_закрывается(client, user_factory, monkeypatch):
    """Контроль: окно склейки ограничено — сигнал получасовой давности не считается тем же
    случаем, что сигнал только что принятый."""
    from datetime import timedelta
    from app.timeutil import utcnow
    monkeypatch.setattr("app.routers.safety.send_text", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)
    u = user_factory("N4User4")
    admin = user_factory("N4Admin3", role=UserRole.admin)

    old_id = client.post("/sos", headers=u["auth"], json={"category": "other"}).json()["id"]
    with Session(engine) as s:
        old = s.get(SosEvent, old_id)
        old.created_at = utcnow() - timedelta(minutes=40)
        s.add(old)
        s.commit()
    new_id = client.post("/sos", headers=u["auth"], json={"category": "other"}).json()["id"]

    h = client.post(f"/admin/sos/{new_id}/handle", headers=admin["auth"])
    assert old_id not in h.json().get("closed_with", []), (
        "сигнал 40-минутной давности — это, вероятно, другой случай, его не нужно закрывать молча"
    )
    with Session(engine) as s:
        assert s.get(SosEvent, old_id).status == "open"


@pytest.mark.skipif(not os.environ.get("DATABASE_URL", "").startswith("postgres"),
                    reason="настоящая параллельная гонка проверяется только на PostgreSQL")
def test_гонка_два_одновременных_sos_пишут_два_независимых_события(user_factory):
    """Два РЕАЛЬНЫХ потока, два отдельных соединения с PostgreSQL, один и тот же пользователь
    жмёт SOS одновременно (barrier синхронизирует старт). Склейка дублей — забота «принял»
    (см. выше), а на ВХОДЕ сигнал не должен теряться или схлопываться в один: это по-прежнему
    два независимых происшествия, пока дежурный не решил иначе."""
    from fastapi.testclient import TestClient
    from app.main import app

    u = user_factory("N4RaceUser")
    barrier = threading.Barrier(2)
    results = []

    def _fire():
        with TestClient(app) as c:
            barrier.wait(timeout=5)
            r = c.post("/sos", headers=u["auth"], json={"category": "other"})
            results.append(r)

    t1 = threading.Thread(target=_fire)
    t2 = threading.Thread(target=_fire)
    t1.start(); t2.start()
    t1.join(timeout=10); t2.join(timeout=10)

    assert len(results) == 2
    assert all(r.status_code == 200 for r in results)
    ids = {r.json()["id"] for r in results}
    assert len(ids) == 2, "два одновременных нажатия должны дать два РАЗНЫХ события"
    with Session(engine) as s:
        rows = s.exec(select(SosEvent).where(SosEvent.id.in_(ids))).all()
        assert len(rows) == 2 and all(row.user_id == u["id"] for row in rows)
