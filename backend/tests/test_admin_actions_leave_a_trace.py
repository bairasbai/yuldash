"""У админского действия должен остаться след: КТО его сделал.

Аудит 2026-08-06, четвёртая волна. Из 41 пишущего админского действия след оставляли два.
Общий лог запросов пишет «POST /admin/debts/7/forgive -> 200» — то есть ЧТО, но не КТО.

Пока админ один, разницы не видно. Но модерация водителей ручная, помощник рано или поздно
появится — и вся прошлая история окажется без авторства задним числом. Больнее всего это там,
где деньги (прощение долга, подтверждение платежа), где человека отрезают от сервиса (бан
устройства) и где действуют ЗА человека (заявка по звонку, с заведением аккаунта).

Правило файла: такое действие пишет строку `[ADMIN] admin=<id> …`. И в этой строке нет
телефонов — только идентификаторы и суммы (§8): по ним всё поднимается из базы, а сам лог
остаётся безопасным.
"""
import logging

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import CommissionDebt, DebtStatus, User, UserRole
from app.timeutil import utcnow


@pytest.fixture
def admin_log(caplog):
    caplog.set_level(logging.INFO, logger="yuldash")
    return caplog


def _admin(user_factory):
    u = user_factory("TraceAdmin", role=UserRole.admin)
    with Session(engine) as s:
        row = s.get(User, u["id"])
        row.role = UserRole.admin
        s.add(row)
        s.commit()
    return u


def _lines(admin_log) -> list[str]:
    return [r.getMessage() for r in admin_log.records if "[ADMIN]" in r.getMessage()]


def test_forgiving_a_debt_names_the_admin(client, user_factory, admin_log):
    """Прощение долга — это деньги. Без автора такую операцию не разобрать."""
    adm = _admin(user_factory)
    drv = user_factory("TraceDrv", role=UserRole.driver)
    with Session(engine) as s:
        d = CommissionDebt(driver_id=drv["id"], order_id=None, amount_kop=15000,
                           status=DebtStatus.unpaid, created_at=utcnow())
        s.add(d)
        s.commit()
        s.refresh(d)
        debt_id = d.id

    r = client.post(f"/admin/debts/{debt_id}/forgive", headers=adm["auth"], json={"reason": "пассажир не заплатил"})
    assert r.status_code == 200, r.text

    hits = [x for x in _lines(admin_log) if "debt.forgive" in x]
    assert hits, f"прощение долга не оставило следа: {_lines(admin_log)}"
    assert f"admin={adm['id']}" in hits[0], f"в следе нет автора: {hits[0]}"
    assert "amount_kop=15000" in hits[0], f"в следе нет суммы: {hits[0]}"


def test_banning_a_device_names_the_admin(client, user_factory, admin_log):
    """Бан отрезает человека от сервиса — тем более нужен автор."""
    adm = _admin(user_factory)
    victim = user_factory("TraceBanned")

    r = client.post("/admin/bans/device", headers=adm["auth"],
                    json={"device_id": "dev-trace-1", "reason": "мультиаккаунт",
                          "user_id": victim["id"]})
    assert r.status_code in (200, 201), r.text

    hits = [x for x in _lines(admin_log) if "device.ban" in x]
    assert hits, f"бан устройства не оставил следа: {_lines(admin_log)}"
    assert f"admin={adm['id']}" in hits[0]


def test_the_trace_never_carries_a_phone(client, user_factory, admin_log):
    """§8: в логах нет телефонов. След должен отвечать «кто и что», а не «чей номер»."""
    adm = _admin(user_factory)
    phone = "+79170000777"

    r = client.post("/admin/request-for-phone", headers=adm["auth"],
                    json={"phone": phone, "name": "Гөлнара", "from_city": "Баймак",
                          "to_city": "Сибай", "seats": 1, "comment": ""})
    assert r.status_code in (200, 201), r.text

    hits = [x for x in _lines(admin_log) if "request.create_for_user" in x]
    assert hits, f"создание заявки за человека не оставило следа: {_lines(admin_log)}"
    assert f"admin={adm['id']}" in hits[0]
    assert phone not in hits[0], f"телефон утёк в лог: {hits[0]}"
    assert "target_user=" in hits[0], "по следу не найти, за кого действовали"


def test_ordinary_user_actions_do_not_spam_the_trace(client, user_factory, admin_log):
    """След — про админские действия. Обычная жизнь сервиса его не засоряет."""
    pax = user_factory("TraceOrdinary")
    client.get("/me", headers=pax["auth"])
    assert not _lines(admin_log), f"обычные запросы пишут админский след: {_lines(admin_log)}"
