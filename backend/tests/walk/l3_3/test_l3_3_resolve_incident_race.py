# -*- coding: utf-8 -*-
"""`/admin/incidents/{id}/resolve` не блокировал строку спора — тот же класс гонки, что чинили
в routers/safety.py::admin_resolve_report (N8, независимое ревью leaf-3.1), только там его
нашли и починили, а здесь, в соседнем файле с идентичной формой, он остался.

Было: `session.get(Incident, incident_id)` без `with_for_update`. Статус проверяется в Python
ДО похода в apply_incident_resolution, который блокирует (`with_for_update`) только
SafetyProfile ОБВИНЁННОГО — не саму строку спора. Два одновременных клика «Подтвердить»
(двойной тап админа или авто-ретрай при плохой связи на трассе) оба читают ещё не решённый
статус и ОБА прогоняют лестницу целиком: предупреждение/страйк начисляется дважды за один
и тот же разбор.

Правка: строка спора блокируется (`with_for_update`) ДО проверки статуса — второй поток
дожидается commit первого и видит уже terminal-статус (409), ничего не повторяя.
"""
from __future__ import annotations

import os
import threading
import time

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Incident, SafetyProfile, UserRole


def _open_incident(reporter_id: int, respondent_id: int) -> int:
    with Session(engine) as s:
        inc = Incident(reporter_id=reporter_id, respondent_id=respondent_id,
                       type="rude", status="under_review", description="гонка резолва")
        s.add(inc)
        s.commit()
        s.refresh(inc)
        return inc.id


def test_последовательные_вызовы_не_гонка_и_не_удваивают(client, user_factory):
    """Контроль: строго ОДИН за другим (без пересечения) — второй получает 409, лестница
    не повторяется. Это уже было верно и до правки (см. test_incidents.py::test_resolve_twice_conflicts);
    держим здесь рядом с гоночным тестом, чтобы было видно, что чинили именно ГОНКУ, а не
    обычную последовательность."""
    reporter = user_factory("L33ResolveReporterSeq")
    respondent = user_factory("L33ResolveRespondentSeq")
    admin = user_factory("L33ResolveAdminSeq", role=UserRole.admin)
    iid = _open_incident(reporter["id"], respondent["id"])

    r1 = client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                     json={"resolution": "warning", "fault": "respondent"})
    r2 = client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                     json={"resolution": "warning", "fault": "respondent"})
    assert r1.status_code == 200 and r2.status_code == 409

    with Session(engine) as s:
        prof = s.exec(select(SafetyProfile).where(SafetyProfile.user_id == respondent["id"])).first()
    assert prof is not None and prof.warnings == 1, (
        f"последовательный повтор всё равно накрутил {prof.warnings if prof else 0} предупреждений"
    )


@pytest.mark.skipif(not os.environ.get("DATABASE_URL", "").startswith("postgres"),
                    reason="настоящая гонка двух транзакций проверяется только на PostgreSQL")
def test_гонка_два_одновременных_resolve_дают_одно_предупреждение(user_factory, monkeypatch):
    """Два РЕАЛЬНЫХ потока жмут «Подтвердить» на ОДНОМ споре одновременно (два соединения
    с PostgreSQL) — воспроизводит двойной тап админа или авто-ретрай клиента при плохой связи.

    Честно про синхронизацию (самопроверка перед сдачей, раздел 4а брифа — по примеру карточки
    routers/safety.py N8, где голый `threading.Barrier` на СТАРТ потоков оказался недостоверным:
    один запрос иногда полностью завершался раньше, чем второй доходил до своего SELECT). Здесь
    то же самое подтвердилось на практике при подготовке правки: чистый barrier-на-старт то
    ловил удвоение на коде без `with_for_update`, то нет — потоки не всегда успевали пересечься
    именно на нужной строке. Поэтому `apply_incident_resolution` на время теста искусственно
    придерживается небольшой паузой (`time.sleep`) — САМА проверяемая логика (замок строки,
    лестница наказаний) отрабатывает настоящим кодом без подмены, пауза только ширит окно
    «прочитали, но не закоммитили» до надёжно измеримого размера:
    - ДО правки оба потока успевают прочитать ещё не решённый статус (без замка им ничто не
      мешает) и оба уходят спать внутри паузы одновременно — оба потом применяют решение;
    - ПОСЛЕ правки второй поток своим `with_for_update` блокируется на SQL-уровне, пока первый
      (держащий замок все время своей паузы) не закоммитит, и видит уже «resolved»."""
    from fastapi.testclient import TestClient
    from app.main import app
    import app.routers.incidents as incidents_mod

    reporter = user_factory("L33RaceReporter")
    respondent = user_factory("L33RaceRespondent")
    admin = user_factory("L33RaceAdmin", role=UserRole.admin)
    iid = _open_incident(reporter["id"], respondent["id"])

    real_apply = incidents_mod.apply_incident_resolution

    def _slow_apply(*a, **kw):
        time.sleep(0.4)   # искусственно расширяем окно «прочитали, не закоммитили»
        return real_apply(*a, **kw)

    monkeypatch.setattr(incidents_mod, "apply_incident_resolution", _slow_apply)

    start = threading.Barrier(2)
    results = []

    def _resolve():
        with TestClient(app) as c:
            start.wait(timeout=5)
            r = c.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                      json={"resolution": "warning", "fault": "respondent"})
            results.append(r)

    t1 = threading.Thread(target=_resolve)
    t2 = threading.Thread(target=_resolve)
    t1.start(); t2.start()
    t1.join(timeout=15); t2.join(timeout=15)

    assert len(results) == 2, "один из потоков не успел завершиться (barrier мог порвать таймаут)"
    коды = sorted(r.status_code for r in results)
    assert коды == [200, 409] or коды == [200, 200], (
        f"неожиданные коды ответа: {коды} — {[r.text for r in results]}"
    )

    with Session(engine) as s:
        prof = s.exec(select(SafetyProfile).where(SafetyProfile.user_id == respondent["id"])).first()
    assert prof is not None and prof.warnings == 1, (
        f"два одновременных «Подтвердить» дали {prof.warnings if prof else 0} предупреждений "
        "вместо одного — обвинённого наказали дважды за один и тот же разбор"
    )
    with Session(engine) as s:
        inc = s.get(Incident, iid)
        assert inc.status == "resolved"
