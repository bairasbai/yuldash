"""N4 (независимое ревью): двойное нажатие SOS создаёт ДВА независимых события (это
осознанно и правильно — «жизнь дороже дедупа на входе»), но без склейки на выходе дежурный,
принявший первое, ещё час получает от эскалатора «⏰ SOS НЕ ПРИНЯТ» по второму — тому же
самому случаю. Ложная тревога приучает не реагировать на настоящие повторы.

Правка: `admin_sos_handle` («Принял») закрывает дубли ТОГО ЖЕ человека вместе с основным
событием — но ТОЛЬКО настоящие дубли: та же категория, тот же вид сигнала (SOS / «застрял» /
зимняя тревога, по префиксу note — `_dup_kind`), и короткое окно (15 мин назад, 3 мин вперёд).

Б3 (повторное ревью, регрессия первой версии N4). Окно ±15 минут БЕЗ учёта категории и вида
само стало дырой: «Принял» по мягкому «застрял на трассе» в 21:00 закрывало НАСТОЯЩИЙ красный
SOS той же категории «breakdown», пришедший в 21:10, — дежурный видел «закрыт вместе с #…», а
страховочный повтор эскалации для него выключался. Та же беда — между зимней тревогой (её
заводит робот, категория "other") и обычным SOS той же категории.

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


def test_принял_позднее_событие_закрывает_более_раннее_в_окне_назад(client, user_factory, monkeypatch):
    """Окно «назад» — 15 минут: дежурный мог открыть список не по порядку и принять ВТОРОЕ
    (более позднее) из двух настоящих дублей — первое, ещё открытое, тоже должно закрыться."""
    from datetime import timedelta
    from app.timeutil import utcnow
    monkeypatch.setattr("app.routers.safety.send_text", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)
    u = user_factory("N4User8")
    admin = user_factory("N4Admin7", role=UserRole.admin)

    earlier_id = client.post("/sos", headers=u["auth"], json={"category": "other"}).json()["id"]
    with Session(engine) as s:
        earlier = s.get(SosEvent, earlier_id)
        earlier.created_at = utcnow() - timedelta(minutes=10)
        s.add(earlier)
        s.commit()
    later_id = client.post("/sos", headers=u["auth"], json={"category": "other"}).json()["id"]

    h = client.post(f"/admin/sos/{later_id}/handle", headers=admin["auth"])
    assert earlier_id in h.json().get("closed_with", []), (
        "более ранний дубль (10 минут назад, тот же человек/категория/вид) не закрылся вместе "
        "с принятым — окно «назад» должно быть 15 минут"
    )


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


def test_застрял_потом_настоящий_sos_не_закрывается_вместе(client, user_factory, monkeypatch):
    """Б3: «Принял» по мягкому «застрял на трассе» НЕ должно закрывать пришедший следом
    красный SOS той же категории «breakdown» — это разные поводы, разный вид сигнала."""
    from app.timeutil import utcnow
    monkeypatch.setattr("app.routers.safety.send_text", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)
    u = user_factory("N4User5")
    admin = user_factory("N4Admin4", role=UserRole.admin)

    stuck_resp = client.post("/bookings/999999999/stuck", headers=u["auth"], json={})
    stuck_id = stuck_resp.json()["id"]
    # «застрял» всегда пишется с category="breakdown" (_roadside) — бьём SOS той же
    # категорией, чтобы разделяло их ИМЕННО по виду сигнала (note-префикс), а не по категории:
    # иначе мутация, убивающая только проверку вида, прошла бы незамеченной.
    sos_id = client.post("/sos", headers=u["auth"], json={"category": "breakdown"}).json()["id"]
    with Session(engine) as s:
        stuck_event = s.get(SosEvent, stuck_id)
        sos_event = s.get(SosEvent, sos_id)
        assert stuck_event.category == sos_event.category == "breakdown"  # контроль

    h = client.post(f"/admin/sos/{stuck_id}/handle", headers=admin["auth"])
    assert sos_id not in h.json().get("closed_with", []), (
        "«Принял» по «застрял» закрыл пришедший следом настоящий SOS — дежурный больше не "
        "получит по нему страховочный повтор эскалации"
    )
    with Session(engine) as s:
        assert s.get(SosEvent, sos_id).status == "open"


def test_sos_через_10_минут_вперёд_не_закрывается(client, user_factory, monkeypatch):
    """Б3: окно «вперёд» — 3 минуты, не 15. Тот же человек, та же категория, тот же вид
    сигнала (оба обычный SOS) — но пришёл через 10 минут, это уже другой повод."""
    from datetime import timedelta
    from app.timeutil import utcnow
    monkeypatch.setattr("app.routers.safety.send_text", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)
    u = user_factory("N4User7")
    admin = user_factory("N4Admin6", role=UserRole.admin)

    first_id = client.post("/sos", headers=u["auth"], json={"category": "other"}).json()["id"]
    second_id = client.post("/sos", headers=u["auth"], json={"category": "other"}).json()["id"]
    with Session(engine) as s:
        second = s.get(SosEvent, second_id)
        second.created_at = utcnow() + timedelta(minutes=10)
        s.add(second)
        s.commit()

    h = client.post(f"/admin/sos/{first_id}/handle", headers=admin["auth"])
    assert second_id not in h.json().get("closed_with", []), (
        "сигнал, пришедший через 10 минут, закрыт вместе с принятым — окно «вперёд» должно "
        "быть коротким (3 мин), а не 15"
    )


def test_зимняя_тревога_и_sos_не_закрывают_друг_друга(client, user_factory, monkeypatch):
    """Б3: тревога зимнего протокола (заводит робот/ручка, категория "other") и обычный SOS
    той же категории того же человека — разные поводы, «Принял» по одному не должен гасить
    другой."""
    from app.winter_escalate import _winter_note
    monkeypatch.setattr("app.routers.safety.send_text", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)
    u = user_factory("N4User6")
    admin = user_factory("N4Admin5", role=UserRole.admin)

    with Session(engine) as s:
        winter = SosEvent(user_id=u["id"], category="other", note=_winter_note("booking", 1))
        s.add(winter)
        s.commit()
        s.refresh(winter)
        winter_id = winter.id

    sos_id = client.post("/sos", headers=u["auth"], json={"category": "other"}).json()["id"]

    h = client.post(f"/admin/sos/{winter_id}/handle", headers=admin["auth"])
    assert sos_id not in h.json().get("closed_with", []), (
        "«Принял» по зимней тревоге закрыл независимый красный SOS того же человека"
    )
    with Session(engine) as s:
        assert s.get(SosEvent, sos_id).status == "open"


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
