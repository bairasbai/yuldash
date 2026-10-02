"""Лист 2.1 — вход и личные данные: отказы входа обязаны звучать на двух языках (RU+BA).

До исправления `current_user`, `issue_tokens` и `rotate_refresh` в app/security.py бросали
`HTTPException(status.HTTP_401_UNAUTHORIZED, "русский текст")` напрямую — detail был голой
русской строкой. Человек с башкирским интерфейсом в момент, когда сессия умерла или токен
не прошёл проверку (а это САМЫЙ частый отказ во всём приложении — проверяется на каждом
защищённом запросе), читал один русский текст без перевода; клиент на «голый» строковый detail
обычно показывает общую заглушку вместо перевода. Правило проекта требует двух языков у каждого
отказа, который видит обычный человек, и 401/403/429 прямо названы в фокусе этого листа.

Сторож `tests/test_every_refusal_speaks_both_languages.py` эту дыру не видел: он ищет литеральный
трёхзначный код сразу после `HTTPException(` (`HTTPException(403, "...")`), а здесь стоял
символьный `status.HTTP_401_UNAUTHORIZED` — единственное такое место во всём app/. Это слепое
пятно самого сторожа — отдельная находка, см. отчёт (раздел «ВНЕ ЗОНЫ»), сторож не в зоне этого
листа.

Исправление — все четыре сообщения переведены на `herr(...)`, который всегда отдаёт
detail={"ru": ..., "ba": ...}. Башкирский текст — черновой перевод модели (кроме части
«Ҡулланыусы табылманы», уже используемой в app/routers/antifraud.py), отмечен в отчёте
на проверку Александру как носителю.
"""
from __future__ import annotations

from app import security


def _bilingual(detail) -> None:
    assert isinstance(detail, dict), f"отказ человеку пришёл одной строкой, без языка: {detail!r}"
    assert detail.get("ru") and detail.get("ba"), f"обе языковые версии обязательны: {detail}"
    assert detail["ru"] != detail["ba"], "башкирский текст не должен быть копией русского"


def test_invalid_token_speaks_both_languages(client):
    r = client.get("/me", headers={"Authorization": "Bearer garbage.not-a-jwt.token"})
    assert r.status_code == 401, r.text
    _bilingual(r.json()["detail"])


def test_token_of_missing_user_speaks_both_languages(client):
    token = security.make_token(999_999_999)  # ни один тест этого листа не заводит такой id
    r = client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 401, r.text
    _bilingual(r.json()["detail"])


def test_session_ended_by_logout_speaks_both_languages(client, user_factory):
    u = user_factory("БилингвОтказ")
    old_auth = dict(u["auth"])
    assert client.post("/auth/logout", headers=u["auth"]).status_code == 200
    r = client.get("/me", headers=old_auth)
    assert r.status_code == 401, r.text
    _bilingual(r.json()["detail"])


def test_dead_refresh_token_speaks_both_languages(client):
    r = client.post("/auth/refresh", json={"refresh_token": "nothing-like-a-real-token"})
    assert r.status_code == 401, r.text
    _bilingual(r.json()["detail"])
