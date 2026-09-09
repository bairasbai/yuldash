"""Волна 202: программу приглашений нельзя обмануть двумя одновременными запросами.

Бонус за приглашение — это бесплатное поднятие поездки, по прайсу 200 ₽. Ферму закрывают
два потолка: сколько бонусов лежит на руках (`MAX_REFERRAL_CREDITS`) и сколько человек
получил за всю жизнь (`MAX_REFERRAL_BONUS_LIFETIME` — «вот он и закрывает ферму», сказано
прямо в коде).

Оба считались обычной парой «прочитал — прибавил — записал». Защита была одна: блокировка
строки пользователя. Её понимает боевой Postgres и ИГНОРИРУЕТ SQLite, на котором живут
все тесты, локальная разработка и демо-база эмулятора. То есть правило не проверял никто:
удали блокировку — всё осталось бы зелёным (класс волны 201).

Две двери:
  * человек вводит код: два одновременных запроса — два бонуса себе и кредит двум разным
    пригласившим при одном приглашении;
  * начисление пригласившему: двое приглашённых вводят его код одновременно — оба читают
    один и тот же счётчик, и пожизненный потолок продвигается на единицу вместо двух.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

from fastapi import HTTPException
from sqlalchemy import text
from sqlmodel import Session, select

from app.db import engine
from app.models import User
from app.routers import referral as ref


def _код(client, кто) -> str:
    return client.get("/referral/me", headers=кто["auth"]).json()["code"]


# ==================== 1. Ввод кода: два запроса от одного человека ====================
def test_two_codes_at_once_give_only_one_bonus(client, user_factory, monkeypatch):
    """Человек отправляет два кода одновременно. Приглашение одно — значит и бонус один.

    Вклиниваемся В СЕРЕДИНЕ запроса: наш вызов уже прочитал «код ещё не введён», и ровно
    в этот момент проходит второй запрос с ДРУГИМ кодом.
    """
    первый = user_factory("ФермаПервый")
    второй = user_factory("ФермаВторой")
    новичок = user_factory("ФермаНовичок")
    код1, код2 = _код(client, первый), _код(client, второй)

    настоящий = ref.grant_referral_credit
    сработало = {"раз": False}

    def начисление_с_вклиниванием(session, referrer):
        if not сработало["раз"]:
            сработало["раз"] = True
            client.post("/referral/redeem", headers=новичок["auth"], json={"code": код2})
        return настоящий(session, referrer)

    if engine.dialect.name == "postgresql":
        # A nested synchronous request would wait for the outer request's row lock,
        # while that outer request waits for the nested one: a test-only deadlock.
        # Real requests run independently and can commit to release their locks.
        barrier = Barrier(2)

        def redeem(code):
            with Session(engine) as session:
                session.execute(text("SET LOCAL lock_timeout = '4s'"))
                session.execute(text("SET LOCAL statement_timeout = '8s'"))
                pid = session.execute(text("SELECT pg_backend_pid()")).scalar_one()
                user = session.get(User, новичок["id"])
                assert user.referred_by is None
                barrier.wait(timeout=10)
                try:
                    result = ref.referral_redeem(ref.RedeemIn(code=code), user, session)
                    assert result["credits"] == 1
                    status = 200
                except HTTPException as exc:
                    status = exc.status_code
                return pid, status

        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(redeem, code) for code in (код1, код2)]
            results = [future.result(timeout=20) for future in futures]
        assert len({pid for pid, _ in results}) == 2
        assert sorted(status for _, status in results) == [200, 400]
    else:
        monkeypatch.setattr(ref, "grant_referral_credit", начисление_с_вклиниванием)
        client.post("/referral/redeem", headers=новичок["auth"], json={"code": код1})

    with Session(engine) as s:
        я = s.get(User, новичок["id"])
        приглашённых = {
            u.id: u.referral_credits
            for u in s.exec(select(User).where(User.id.in_([первый["id"], второй["id"]]))).all()
        }

    assert я.referral_credits == 1, (
        f"человек получил {я.referral_credits} бонуса за одно приглашение "
        f"({я.referral_credits * 200} ₽ вместо 200 ₽)"
    )
    assert sum(приглашённых.values()) == 1, (
        f"кредит достался обоим пригласившим сразу: {приглашённых}"
    )


def test_redeem_still_works_and_is_idempotent(client, user_factory):
    """Защита не сломана: первый ввод проходит, второй отвечает «код уже введён»."""
    хозяин = user_factory("ФермаХозяин")
    гость = user_factory("ФермаГость")
    код = _код(client, хозяин)

    первый = client.post("/referral/redeem", headers=гость["auth"], json={"code": код})
    повтор = client.post("/referral/redeem", headers=гость["auth"], json={"code": код})

    assert первый.status_code == 200, первый.text
    assert первый.json()["credits"] == 1
    assert повтор.status_code == 400, "второй ввод кода должен отказать"
    with Session(engine) as s:
        assert s.get(User, гость["id"]).referral_credits == 1
        assert s.get(User, хозяин["id"]).referral_credits == 1


def test_own_code_and_swap_are_still_refused(client, user_factory):
    """И старые правила на месте: свой код нельзя, обмен кодами нельзя (волна 165)."""
    a = user_factory("ФермаА")
    b = user_factory("ФермаБ")
    код_a, код_b = _код(client, a), _код(client, b)

    assert client.post("/referral/redeem", headers=a["auth"],
                       json={"code": код_a}).status_code == 400
    assert client.post("/referral/redeem", headers=b["auth"],
                       json={"code": код_a}).status_code == 200
    assert client.post("/referral/redeem", headers=a["auth"],
                       json={"code": код_b}).status_code == 400, "обмен кодами разрешили"


# ==================== 2. Начисление пригласившему: два приглашённых сразу ====================
def test_lifetime_cap_counts_every_grant(client, user_factory):
    """Двое вводят код одного человека одновременно. Пожизненный счётчик обязан вырасти на два.

    Он и есть ограда фермы: если при одновременных вводах он растёт на единицу, ферма
    получает вдвое больше бонусов, чем ей положено за жизнь.
    """
    хозяин = user_factory("ПотолокХозяин")

    # Два независимых запроса читают ОДНОГО И ТОГО ЖЕ пригласившего каждый в своей сессии —
    # ровно так и выглядят два одновременных ввода кода на боевом сервере.
    with Session(engine) as s1, Session(engine) as s2:
        он_у_первого = s1.exec(select(User).where(User.id == хозяин["id"])).one()
        он_у_второго = s2.exec(select(User).where(User.id == хозяин["id"])).one()
        # Оба запроса уже прочитали человека — и только теперь первый завершается.
        # Второй пишет по УСТАРЕВШЕМУ числу — ровно так теряются счётчики на боевой базе.
        assert ref.grant_referral_credit(s1, он_у_первого) is True
        s1.commit()
        assert ref.grant_referral_credit(s2, он_у_второго) is True
        s2.commit()

    with Session(engine) as s:
        он = s.get(User, хозяин["id"])

    assert он.referral_bonus_lifetime == 2, (
        f"пожизненный счётчик {он.referral_bonus_lifetime} при двух начислениях — "
        "ограда фермы продвинулась не на все выданные бонусы"
    )
    assert он.referral_credits == 2, f"бонусов на руках {он.referral_credits} вместо 2"


def test_lifetime_cap_still_stops_the_farm(client, user_factory):
    """Защита не сломана: выбрал пожизненный потолок — бонусов больше нет."""
    хозяин = user_factory("ПотолокВыбран")
    with Session(engine) as s:
        u = s.get(User, хозяин["id"])
        u.referral_bonus_lifetime = ref.MAX_REFERRAL_BONUS_LIFETIME
        s.add(u)
        s.commit()

    with Session(engine) as s:
        u = s.exec(select(User).where(User.id == хозяин["id"])).one()
        assert ref.grant_referral_credit(s, u) is False
        s.commit()

    with Session(engine) as s:
        assert s.get(User, хозяин["id"]).referral_credits == 0


def test_full_wallet_does_not_burn_a_lifetime_slot(client, user_factory):
    """Кошелёк полон — начисления нет, и пожизненный слот при этом не тратится."""
    хозяин = user_factory("ПотолокКошелёк")
    with Session(engine) as s:
        u = s.get(User, хозяин["id"])
        u.referral_credits = ref.MAX_REFERRAL_CREDITS
        s.add(u)
        s.commit()

    with Session(engine) as s:
        u = s.exec(select(User).where(User.id == хозяин["id"])).one()
        assert ref.grant_referral_credit(s, u) is False
        s.commit()

    with Session(engine) as s:
        u = s.get(User, хозяин["id"])
        assert u.referral_credits == ref.MAX_REFERRAL_CREDITS
        assert u.referral_bonus_lifetime == 0, "слот сгорел впустую при полном кошельке"


def test_full_wallet_invitee_does_not_go_over_the_cap(client, user_factory):
    """Он сам уже пригласил многих и кошелёк полон. Теперь вводит чужой код.

    Приглашение засчитывается (статистика должна его видеть), но бонусов на руках больше
    потолка быть не может: потолок и есть ограда от накрутки взаимными вводами."""
    хозяин = user_factory("ПотолокВвода")
    гость = user_factory("ПотолокГость")
    код = _код(client, хозяин)
    with Session(engine) as s:
        u = s.get(User, гость["id"])
        u.referral_credits = ref.MAX_REFERRAL_CREDITS
        s.add(u)
        s.commit()

    ответ = client.post("/referral/redeem", headers=гость["auth"], json={"code": код})

    assert ответ.status_code == 200, ответ.text
    assert ответ.json()["credits"] == ref.MAX_REFERRAL_CREDITS, (
        f"на руках {ответ.json()['credits']} при потолке {ref.MAX_REFERRAL_CREDITS}"
    )
    with Session(engine) as s:
        u = s.get(User, гость["id"])
        assert u.referral_credits == ref.MAX_REFERRAL_CREDITS
        assert u.referred_by == хозяин["id"], "приглашение не засчиталось"
