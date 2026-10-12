"""leaf-3.2 — находка: `GET /me/trust` врал про право звать своих во время паузы.

`trust_service.can_invite()` (реальная проверка, которую зовёт `POST /invites`) спрашивает
`account_paused` отдельно от уровня — ровно для случая «проверенный документами водитель
продолжает звать людей в круг, пока идёт разбор его же жалобы» (volna 56, docs/trust-levels-backend.md).

Но `trust_service.trust_summary()` (её отдаёт `GET /me/trust`, витрина профиля/бейджа) считала
поле `can_invite` ПО-ДРУГОМУ — простым `level >= MIN_INVITER_LEVEL`, без запроса паузы. Пассажир
на паузе открывает экран доверия, видит «можешь позвать своего» (can_invite=true), жмёт кнопку —
и получает 403 "Приглашать в круг своих может только проверенный участник". Несогласованность
между витриной и настоящим гейтом — не дыра в безопасности (сам POST /invites защищён), но
это противоречащие друг другу ответы сервера на один и тот же вопрос в один и тот же момент.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import SafetyProfile
from app.timeutil import utcnow
from test_trust import mk_user


def _pause(user_id: int, days: int = 7) -> None:
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=days)))
        s.commit()


def test_paused_l2_cannot_invite_and_trust_says_so(client):
    u = mk_user(name="Рамиль", avatar="https://x/r.jpg", verified=True)   # L2, может звать
    body = client.get("/me/trust", headers=u["auth"]).json()
    assert body["level"] == 2
    assert body["can_invite"] is True   # контроль: без паузы право есть (как в test_trust.py)

    _pause(u["id"])

    # Настоящий гейт (тот, что реально решает) — отказывает во время паузы.
    denied = client.post("/invites", headers=u["auth"])
    assert denied.status_code == 403, denied.text

    # Витрина ОБЯЗАНА отвечать то же самое, что настоящий гейт — иначе кнопка в профиле
    # показывает возможность, которой на самом деле нет.
    body = client.get("/me/trust", headers=u["auth"]).json()
    assert body["level"] == 2                 # базовый уровень (документы) наказанием не трогаем
    assert body["can_invite"] is False, (
        "GET /me/trust показал can_invite=true человеку на паузе, "
        "хотя POST /invites этому же человеку отвечает 403"
    )


def test_trust_can_invite_matches_real_gate_after_pause_ends(client):
    """Наказание кончилось — право возвращается само (без доп. действий), и витрина это видит."""
    u = mk_user(name="Зухра", avatar="https://x/z.jpg", verified=True)
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=u["id"], suspended_until=utcnow() - timedelta(days=1)))  # уже истекла
        s.commit()
    body = client.get("/me/trust", headers=u["auth"]).json()
    assert body["can_invite"] is True
    assert client.post("/invites", headers=u["auth"]).status_code == 200
