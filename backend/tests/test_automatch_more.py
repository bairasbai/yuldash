"""Авто-подбор водителя для заявок «за пожилого» — крайние случаи.

Что это. Обычную заявку пассажир закрывает сам: видит отклики водителей и выбирает.
Но заявку, созданную по телефону за человека без смартфона, выбрать некому. Раньше это
делал Александр руками в Telegram, теперь система берёт лучший отклик сама.

Здесь проверено то, что обычными сценариями не задевается: выключенный подбор, сухой
прогон (посмотреть, что было бы, ничего не меняя), и главное — что сбой на ОДНОЙ заявке
не останавливает проход. Иначе одна закрывшаяся в последний момент заявка оставила бы
без машины всех остальных бабушек в очереди.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app import automatch
from app.config import settings
from app.db import engine
from app.models import RequestResponse, RideRequest
from app.timeutil import utcnow


@pytest.fixture
def matching_on(monkeypatch):
    monkeypatch.setattr(settings, "automatch_enabled", True, raising=False)
    monkeypatch.setattr(settings, "automatch_grace_sec", 0, raising=False)
    yield


def _ripe_request(session: Session, passenger_id: int, driver_id: int) -> tuple[int, int]:
    """Заявка пассажира без приложения + отклик водителя, готовые к подбору."""
    req = RideRequest(
        passenger_id=passenger_id, from_city="Сибай", to_city="Уфа",
        depart_at=utcnow(), seats=1, status="active",
    )
    session.add(req)
    session.commit()
    session.refresh(req)
    resp = RequestResponse(
        request_id=req.id, driver_id=driver_id, price=500, status="offered",
        created_at=utcnow(),
    )
    session.add(resp)
    session.commit()
    session.refresh(resp)
    return req.id, resp.id


def test_выключенный_подбор_ничего_не_трогает(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "automatch_enabled", False, raising=False)
    with Session(engine) as s:
        assert automatch.automatch_once(s) == []


def test_сухой_прогон_показывает_но_не_меняет(client, user_factory, matching_on):
    from app.models import UserRole
    passenger = user_factory(role=UserRole.passenger)
    driver = user_factory(role=UserRole.driver)
    with Session(engine) as s:
        req_id, resp_id = _ripe_request(s, passenger["id"], driver["id"])
        matched = automatch.automatch_once(s, dry_run=True)
        assert (req_id, resp_id) in matched, "сухой прогон обязан показать, кого бы выбрал"
    with Session(engine) as s:
        assert s.get(RideRequest, req_id).status == "active", "сухой прогон не имеет права менять заявку"


def test_сбой_на_одной_заявке_не_останавливает_проход(client, user_factory, matching_on, monkeypatch):
    from app.models import UserRole
    passenger = user_factory(role=UserRole.passenger)
    driver = user_factory(role=UserRole.driver)
    with Session(engine) as s:
        _ripe_request(s, passenger["id"], driver["id"])

        # Приём падает на каждой заявке — проход обязан дойти до конца и вернуть пустой список,
        # а не выбросить исключение наружу и оставить таймер в ошибке.
        import app.routers.requests as req_router
        monkeypatch.setattr(
            req_router, "accept_request_response",
            lambda *a, **kw: (_ for _ in ()).throw(RuntimeError("заявку уже закрыли")),
            raising=False,
        )
        assert automatch.automatch_once(s) == []


def test_заявка_без_откликов_пропускается(client, user_factory, matching_on):
    from app.models import UserRole
    passenger = user_factory(role=UserRole.passenger)
    with Session(engine) as s:
        req = RideRequest(
            passenger_id=passenger["id"], from_city="Баймак", to_city="Сибай",
            depart_at=utcnow(), seats=1, status="active",
        )
        s.add(req)
        s.commit()
        s.refresh(req)
        assert all(r[0] != req.id for r in automatch.automatch_once(s, dry_run=True))


def test_пассажиру_с_приложением_подбор_не_нужен(client, user_factory, matching_on):
    from app.models import DeviceToken, UserRole
    passenger = user_factory(role=UserRole.passenger)
    driver = user_factory(role=UserRole.driver)
    with Session(engine) as s:
        s.add(DeviceToken(user_id=passenger["id"], token="fcm-token-test", platform="android"))
        s.commit()
        req_id, _ = _ripe_request(s, passenger["id"], driver["id"])
        # У человека есть приложение — он выбирает водителя сам, вмешиваться нельзя.
        assert all(r[0] != req_id for r in automatch.automatch_once(s, dry_run=True))


def test_свежий_отклик_ждёт_паузу(client, user_factory, monkeypatch):
    """Пауза нужна, чтобы выбрать лучшего водителя, а не первого нажавшего."""
    from app.models import UserRole
    monkeypatch.setattr(settings, "automatch_enabled", True, raising=False)
    monkeypatch.setattr(settings, "automatch_grace_sec", 600, raising=False)
    passenger = user_factory(role=UserRole.passenger)
    driver = user_factory(role=UserRole.driver)
    with Session(engine) as s:
        req_id, _ = _ripe_request(s, passenger["id"], driver["id"])
        assert all(r[0] != req_id for r in automatch.automatch_once(s, dry_run=True))


def test_запуск_из_командной_строки_не_падает(monkeypatch, capsys):
    """`python -m app.automatch --dry-run` — то, что реально крутится по таймеру на сервере."""
    monkeypatch.setattr(automatch.sys, "argv", ["automatch", "--dry-run"], raising=False)
    monkeypatch.setattr(settings, "automatch_enabled", False, raising=False)
    automatch.main()
    out = capsys.readouterr().out
    assert "выключено" in out, "при выключенном подборе запуск обязан честно об этом сказать"


def test_запуск_с_включённым_подбором_печатает_итог(monkeypatch, capsys, client):
    monkeypatch.setattr(automatch.sys, "argv", ["automatch", "--dry-run"], raising=False)
    monkeypatch.setattr(settings, "automatch_enabled", True, raising=False)
    automatch.main()
    out = capsys.readouterr().out
    assert "Итог" in out
