"""Лист 2.1: граница logout→login не пускает токен, выпущенный ДО или В ТОТ ЖЕ миг, что и выход.

Чистые функции `security.make_token`/`security._token_revoked`, без похода в БД — быстрее и точнее
ловит именно границу, а не поведение вокруг неё.

Если бы `make_token` мог вернуть iat <= `tokens_valid_from`, а `_token_revoked` сравнивал строго
«<» вместо «<=», человек, вышедший и тут же вошедший заново в ту же миллисекунду (дешёвые часы
Windows дают соседним вызовам одинаковое значение — см. комментарий в security.py), получил бы
токен, который logout не признал бы отозванным: выход «со всех устройств» в эту миллисекунду
отозвал бы ноль сессий.
"""
from __future__ import annotations

from datetime import timedelta

from jose import jwt

from app.config import settings
from app.models import User
from app.security import _token_revoked, make_token
from app.timeutil import utcnow


def _decode_iat(token: str) -> float:
    payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    return float(payload["iat"])


def test_new_token_is_issued_strictly_after_the_logout_boundary():
    boundary = utcnow()
    token = make_token(1, issued_after=boundary)
    assert _decode_iat(token) > boundary.timestamp(), (
        "новый токен выпущен в тот же момент (или раньше), что и граница выхода — "
        "logout не отзовёт его"
    )


def test_token_issued_exactly_at_the_boundary_is_revoked():
    boundary = utcnow()
    user = User(id=1, phone="+79990000001", tokens_valid_from=boundary)
    assert _token_revoked({"iat": boundary.timestamp()}, user) is True, (
        "токен, выпущенный РОВНО в момент logout, должен считаться отозванным (сравнение <=)"
    )


def test_token_issued_after_the_boundary_survives():
    boundary = utcnow()
    user = User(id=1, phone="+79990000002", tokens_valid_from=boundary)
    later = boundary + timedelta(seconds=1)
    assert _token_revoked({"iat": later.timestamp()}, user) is False, (
        "свежий вход после выхода не должен сразу же считаться отозванным"
    )


def test_token_without_iat_is_revoked_once_a_boundary_exists():
    user = User(id=1, phone="+79990000003", tokens_valid_from=utcnow())
    assert _token_revoked({}, user) is True, (
        "токен старого формата без iat нельзя безопасно отнести к новой сессии после logout"
    )


def test_no_boundary_means_nothing_is_revoked_yet():
    user = User(id=1, phone="+79990000004", tokens_valid_from=None)
    assert _token_revoked({"iat": utcnow().timestamp()}, user) is False
    assert _token_revoked({}, user) is False
