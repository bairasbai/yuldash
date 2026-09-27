# -*- coding: utf-8 -*-
"""Ключ от аккаунта обязан протухать — и это проверяет сервер, а не наша аккуратность.

Аудит 2026-08-12, волна 42. Токен открывает всё: поездки, телефон, адреса, кошелёк. Проба
постучалась в вход девятью способами. Восемь сервер отбил честно: чужая подпись, «алгоритм
none», просроченный, выписанный до «выйти со всех устройств», повторно использованный refresh,
токен удалённого аккаунта.

Девятый прошёл: **токен БЕЗ срока жизни принимался как вечный**. Подделать такой нельзя —
секрет наш. Но обещание «токен протухнет» переставало действовать для любого токена, который
однажды утёк: из бэкапа, из лога, со скриншота в поддержке.

Тесты ниже — про сам вход, поэтому проверяют его напрямую, а не через один эндпоинт.
"""
from datetime import timedelta, timezone

import pytest
from jose import jwt

from app.config import settings
from app.security import _decode
from app.timeutil import utcnow


def _token(**claims) -> str:
    return jwt.encode(claims, settings.jwt_secret, algorithm="HS256")


def test_токен_без_срока_жизни_не_принимается():
    """Вечный ключ — это ключ, который нельзя отозвать временем."""
    with pytest.raises(Exception):
        _decode(_token(sub="1", iat=utcnow().replace(tzinfo=timezone.utc).timestamp()))


def test_токен_без_владельца_не_принимается():
    """Без `sub` непонятно, чей это ключ вообще."""
    with pytest.raises(Exception):
        _decode(_token(exp=(utcnow() + timedelta(days=1)).replace(tzinfo=timezone.utc).timestamp()))


def test_нормальный_токен_работает(client, user_factory):
    """Защита не должна ломать вход: обычный человек заходит как раньше."""
    person = user_factory("Обычный человек")
    r = client.get("/me", headers=person["auth"])
    assert r.status_code == 200, r.text


def test_просроченный_токен_не_пускает(client, user_factory):
    """Регресс на то, что и так работало: срок жизни соблюдается."""
    person = user_factory("Человек со старым токеном")
    stale = _token(sub=str(person["id"]),
                   iat=(utcnow() - timedelta(days=9)).replace(tzinfo=timezone.utc).timestamp(),
                   exp=(utcnow() - timedelta(days=8)).replace(tzinfo=timezone.utc).timestamp())
    r = client.get("/me", headers={"Authorization": f"Bearer {stale}"})
    assert r.status_code == 401, r.text


def test_подделанная_подпись_не_пускает(client, user_factory):
    """Регресс: секрет чужой — дверь закрыта."""
    person = user_factory("Человек с поддельным токеном")
    forged = jwt.encode(
        {"sub": str(person["id"]), "iat": utcnow().replace(tzinfo=timezone.utc).timestamp(),
         "exp": (utcnow() + timedelta(days=1)).replace(tzinfo=timezone.utc).timestamp()},
        "не-наш-секрет", algorithm="HS256",
    )
    r = client.get("/me", headers={"Authorization": f"Bearer {forged}"})
    assert r.status_code == 401, r.text
