"""leaf-3.2 — PG-гонка: один одноразовый инвайт-код не должен впустить в круг «своих» двоих.

Запускать ТОЛЬКО на изолированном PostgreSQL (`DATABASE_URL=postgresql://...` перед pytest) —
гонка двух настоящих транзакций на SQLite не воспроизводима.

Это ПРОВЕРКА защиты, которая, по чтению кода, уже стоит (`with_for_update()` + атомарный
`UPDATE ... WHERE uses_left > 0`, см. комментарий в `routers/trust.py::redeem_invite`) — круг
«только для своих» это обещание безопасности (`only_trusted`), поэтому стоит убедиться реальной
гонкой, а не поверить комментарию на слово (аудит leaf-3.2, §4а BRIEF: «нет ни блокировки, ни
ограничения — открытая находка»; здесь ОБА есть — тест это подтверждает двумя живыми
соединениями, а не одно за другим).
"""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest
from fastapi import HTTPException
from sqlalchemy import text
from sqlmodel import Session, select

from app.db import engine
from app.models import InviteCode, Trust, User, UserRole
from app.routers.trust import RedeemIn, redeem_invite

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")


def _mk(name, *, verified=False, avatar=""):
    with Session(engine) as s:
        u = User(phone=f"pgrace-{name}", name=name, avatar_url=avatar, verified=verified,
                 role=UserRole.passenger, telegram_id=f"pgrace{name}")
        s.add(u)
        s.commit()
        s.refresh(u)
        return u.id


def test_two_newcomers_cannot_both_redeem_the_same_single_use_code(client):
    inviter_id = _mk("inviter", verified=True, avatar="https://x/i.jpg")   # L2: может звать
    code = "RACE1234"
    with Session(engine) as s:
        s.add(InviteCode(code=code, owner_id=inviter_id, uses_left=1))
        s.commit()
    newbie_a = _mk("a")
    newbie_b = _mk("b")
    barrier = Barrier(2)
    pids: list[int] = []

    def worker(newbie_id):
        with Session(engine) as session:
            session.execute(text("SET lock_timeout = '5s'"))
            pids.append(session.execute(text("SELECT pg_backend_pid()")).scalar_one())
            user = session.get(User, newbie_id)
            barrier.wait(timeout=10)
            try:
                result = redeem_invite(RedeemIn(code=code), user=user, session=session)
                return newbie_id, 200, result
            except HTTPException as e:
                return newbie_id, e.status_code, None

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker, nid) for nid in (newbie_a, newbie_b)]
        results = [f.result(timeout=20) for f in futures]

    assert len(set(pids)) == 2, "обе попытки обязаны идти с РАЗНЫХ соединений — иначе это не гонка"
    statuses = sorted(status for _, status, _ in results)
    assert statuses == [200, 400], f"один код должен впустить РОВНО одного: {results}"

    with Session(engine) as session:
        winners = session.exec(
            select(Trust).where(Trust.invited_by == inviter_id, Trust.user_id.in_([newbie_a, newbie_b]))
        ).all()
        assert len(winners) == 1, (
            f"одноразовый код впустил {len(winners)} человек вместо одного: "
            f"{[(w.user_id, w.level) for w in winners]}"
        )
        inv = session.exec(select(InviteCode).where(InviteCode.code == code)).one()
        assert inv.uses_left == 0, f"использований осталось {inv.uses_left}, ожидали 0 (не -1 и не 1)"
