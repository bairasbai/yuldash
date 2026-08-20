"""Половина админских решений не оставляла следа, а права нельзя было отобрать.

Журнал «кто и что сделал» завели, когда стало ясно: модерация ручная, помощник рано или поздно
появится, и без авторства вся прошлая история окажется ничьей. Рой проверил, насколько журнал
полон (аудит 2026-08-08, волна 150).

**Восемнадцать пишущих ручек следа не оставляли.** Ровно те, где деньги и репутация: создание
и правка промокампании (в пробе скидку подняли с 500 до 99 999 ₽ — след нулевой), снятие оценки
из среднего и публикация чужого текста о человеке, одобрение бизнеса и рекламы, включение такси
в городе.

**Вторая дверь: кнопки в Telegram.** Они делают то же, что ручки админки, — одобряют водителя,
публикуют рекламу, подтверждают платёж. Через HTTP след оставался, через кнопку — нет. Журнал
выглядел полным, фиксируя один вход из двух.

**Разбор спора писал половину решения.** В строке было «был suspend», но не было ни суммы
компенсации, ни срока паузы, ни того, кого наказали. Соседние действия эти цифры пишут.

**Права администратора нельзя было отобрать.** Роль выдаётся при входе по номеру из настроек,
а обратной дороги не было: убрал номер из настроек — роль уже в базе и живёт вечно. «Уволить»
помощника можно было бы только руками в базе, и никто бы об этом не вспомнил. Сама выдача прав —
самое чувствительное событие в системе — тоже не писалась.

Теперь вход сверяет роль с настройками в обе стороны. Пустой список админов при этом
не разжалует никого: незаполненная настройка не должна оставить сервис без администратора.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import Rating, User, UserRole

ROUTERS = Path(__file__).resolve().parents[1] / "app" / "routers"


def _следы(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if "[ADMIN]" in r.getMessage()]


def test_каждое_пишущее_действие_админа_оставляет_след():
    """Сторож на класс: новая админская кнопка обязана унаследовать журнал.

    Ищем по признаку — маршрут, который что-то меняет, и вызов записи следа рядом. Пока
    правило держится тестом, следующая ручка не окажется безымянной.
    """
    без_следа = []
    for файл in sorted(ROUTERS.glob("*.py")):
        строки = файл.read_text(encoding="utf-8").splitlines()
        for i, ln in enumerate(строки):
            m = re.match(r'@router\.(post|delete)\("(/admin/[^"]*)"', ln.strip())
            if not m:
                continue
            if "admin_action(" not in "\n".join(строки[i:i + 60]):
                без_следа.append(f"{файл.name}:{i + 1} {m.group(2)}")

    assert not без_следа, (
        f"эти админские действия не оставляют следа: {без_следа}. Через год нельзя будет "
        "ответить, кто снял оценку человеку, поднял скидку или выключил такси в городе"
    )


def test_разбор_спора_записывает_сумму_и_срок(client, user_factory, caplog):
    """«Был suspend» — это не ответ: важно, на сколько отключили и сколько присудили."""
    водитель = user_factory("СледВодитель", role=UserRole.driver)
    жалобщик = user_factory("СледЖалобщик")
    админ = user_factory("СледАдмин", role=UserRole.admin)
    from test_api import _ride
    ride_id = _ride(client, водитель, comment="след")
    bid = client.post("/bookings", headers=жалобщик["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    inc = client.post("/incidents", headers=жалобщик["auth"], json={
        "booking_id": bid, "type": "unsafe", "text": "гнал", "respondent_id": водитель["id"],
    }).json()["id"]

    with caplog.at_level("INFO", logger="yuldash"):
        client.post(f"/admin/incidents/{inc}/resolve", headers=админ["auth"], json={
            "resolution": "suspend", "fault": "respondent", "suspend_days": 30,
            "compensation_kop": 500000, "note": "опасное вождение",
        })

    строка = " ".join(_следы(caplog))
    assert "compensation_kop=500000" in строка, f"в следе нет суммы компенсации: {строка}"
    assert "suspend_days=30" in строка, f"в следе нет срока отключения: {строка}"
    assert f"target_user={водитель['id']}" in строка, f"в следе не видно, кого наказали: {строка}"


def test_выдача_прав_администратора_записывается(client, user_factory, monkeypatch, caplog):
    """Самое чувствительное событие в системе не должно проходить молча."""
    номер = "+79995551500"
    monkeypatch.setattr(settings, "admin_phones", номер)
    человек = user_factory("СледНовыйАдмин")
    with Session(engine) as s:
        u = s.get(User, человек["id"])
        u.phone = номер
        s.add(u)
        s.commit()

    from app.routers.auth import _maybe_promote_admin
    with caplog.at_level("INFO", logger="yuldash"), Session(engine) as s:
        _maybe_promote_admin(s, s.get(User, человек["id"]))
        s.commit()

    строка = " ".join(_следы(caplog))
    assert "role.promote" in строка, f"выдача прав администратора не записана: {строка}"
    with Session(engine) as s:
        assert s.get(User, человек["id"]).role == UserRole.admin


def test_права_снимаются_когда_номер_убрали_из_настроек(client, user_factory, monkeypatch,
                                                        caplog):
    """Главное: уволенного помощника должно быть можно уволить."""
    номер = "+79995551501"
    monkeypatch.setattr(settings, "admin_phones", номер)
    человек = user_factory("СледБывшийАдмин")
    with Session(engine) as s:
        u = s.get(User, человек["id"])
        u.phone = номер
        u.role = UserRole.admin
        s.add(u)
        s.commit()

    monkeypatch.setattr(settings, "admin_phones", "+79990000000")   # номер убрали из настроек
    from app.routers.auth import _maybe_promote_admin
    with caplog.at_level("INFO", logger="yuldash"), Session(engine) as s:
        _maybe_promote_admin(s, s.get(User, человек["id"]))
        s.commit()

    with Session(engine) as s:
        роль = s.get(User, человек["id"]).role
    assert роль != UserRole.admin, (
        "номер убрали из настроек, а права администратора остались: отобрать их можно было бы "
        "только руками в базе, и об этом никто не вспомнит"
    )
    assert "role.revoke" in " ".join(_следы(caplog)), "снятие прав не записано в журнал"


def test_пустой_список_админов_никого_не_разжалует(client, user_factory, monkeypatch):
    """Обратная сторона: неверно прочитанная настройка не должна оставить сервис без хозяина."""
    человек = user_factory("СледОстаётсяАдмин")
    with Session(engine) as s:
        u = s.get(User, человек["id"])
        u.role = UserRole.admin
        s.add(u)
        s.commit()
    monkeypatch.setattr(settings, "admin_phones", "")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")

    from app.routers.auth import _maybe_promote_admin
    with Session(engine) as s:
        _maybe_promote_admin(s, s.get(User, человек["id"]))
        s.commit()

    with Session(engine) as s:
        assert s.get(User, человек["id"]).role == UserRole.admin, (
            "список админов пуст (настройка не заполнена), а права уже сняли — сервис остался "
            "вообще без администратора"
        )


def test_решение_кнопкой_в_телеграме_тоже_оставляет_след(client, user_factory, monkeypatch,
                                                          caplog):
    """Кнопка делает то же, что ручка админки: допускает водителя, двигает деньги."""
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "555000")
    monkeypatch.setattr(settings, "telegram_webhook_secret", "")
    водитель = user_factory("СледТгВодитель", role=UserRole.driver)

    with caplog.at_level("INFO", logger="yuldash"):
        client.post("/telegram/webhook", json={
            "callback_query": {
                "id": "cb-1", "from": {"id": 555000},
                "data": f"drv:ok:{водитель['id']}",
            }
        })

    строка = " ".join(_следы(caplog))
    assert "telegram." in строка, (
        f"решение, принятое кнопкой в Telegram, не оставило следа: {строка or 'журнал пуст'}. "
        "Журнал выглядит полным, а фиксирует один вход из двух"
    )


@pytest.mark.parametrize("действие", ["rating.exclude", "promo.create", "taxi_city.add"])
def test_денежные_и_репутационные_действия_названы_в_журнале(действие: str):
    """Проверяем не факт вызова, а что у действия есть внятное имя.

    По журналу должно читаться, ЧТО произошло: «сняли оценку человеку», «завели кампанию»,
    «включили такси в городе», — а не безымянная строка запроса.
    """
    исходники = "\n".join(f.read_text(encoding="utf-8") for f in ROUTERS.glob("*.py"))

    assert f'"{действие}"' in исходники, (
        f"в журнале нет действия {действие}: по логу нельзя понять, что именно сделали"
    )


def test_снятие_оценки_записывается(client, user_factory, caplog):
    """Репутация человека меняется — автор изменения должен остаться в журнале."""
    админ = user_factory("СледАдминОценка", role=UserRole.admin)
    водитель = user_factory("СледОценённый", role=UserRole.driver)
    оценщик = user_factory("СледОценщик")
    with Session(engine) as s:
        r = Rating(rater_id=оценщик["id"], ratee_id=водитель["id"], stars=1, text="плохо")
        s.add(r)
        s.commit()
        s.refresh(r)
        rid = r.id

    with caplog.at_level("INFO", logger="yuldash"):
        ответ = client.post(f"/admin/ratings/{rid}/exclude", headers=админ["auth"],
                            json={"excluded": True})

    assert ответ.status_code == 200, ответ.text
    assert "rating.exclude" in " ".join(_следы(caplog)), (
        "оценку сняли из среднего, а кто это сделал — нигде не записано"
    )
