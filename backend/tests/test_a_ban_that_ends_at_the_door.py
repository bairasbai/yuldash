"""Волна 204: бан устройства должен выключать и уже открытую сессию, а не только вход.

Бан устройства — единственный ответ на обход блокировки новым номером: человека выгнали,
он завёл свежий телефонный номер и вернулся с того же аппарата. Гейт стоит на трёх дверях
входа: запрос кода, проверка кода и вход через Telegram.

Четвёртой двери у него не было. Приложение само продлевает вход по refresh-токену
(`/auth/refresh`) — и эта ручка про устройство ничего не знала: она не принимает заголовок
`X-Device-Id` и не смотрит, с какого аппарата человек заходил. То есть забаненный
продолжал работать как ни в чём не бывало, пока приложение обновляет ключи, — а обновляет
оно их само и бесконечно.

Бан при этом выглядел выполненным: в админке он есть, новый вход с этого аппарата режется.
Не работало ровно то, ради чего его нажимали.
"""
from __future__ import annotations

from sqlmodel import Session, select

from app.db import engine
from app.models import DeviceBan, User, UserRole

_счётчик = {"n": 6400}


def _свежий_номер() -> str:
    _счётчик["n"] += 1
    return f"+7900{_счётчик['n']:07d}"


def _войти(client, телефон: str, устройство: str, имя: str = "Человек"):
    """Полный вход по коду с заголовком устройства."""
    headers = {"X-Device-Id": устройство} if устройство else {}
    r = client.post("/auth/request-code", json={"phone": телефон}, headers=headers)
    assert r.status_code == 200, r.text
    код = r.json()["dev_code"]
    r = client.post("/auth/verify", json={"phone": телефон, "code": код, "name": имя},
                    headers=headers)
    assert r.status_code == 200, r.text
    return r.json()


def _забанить(client, admin, устройство: str) -> None:
    r = client.post("/admin/bans/device", headers=admin["auth"],
                    json={"device_id": устройство, "reason": "проба"})
    assert r.status_code == 200, r.text


# ==================== 1. Главное: бан выключает продление входа ====================
def test_banned_device_cannot_refresh_its_session(client, user_factory):
    """Забанили аппарат — приложение на нём больше не продлевает вход."""
    admin = user_factory("БанАдмин", role=UserRole.admin)
    вход = _войти(client, _свежий_номер(), "dev-ban-refresh")

    _забанить(client, admin, "dev-ban-refresh")
    r = client.post("/auth/refresh", json={"refresh_token": вход["refresh_token"]},
                    headers={"X-Device-Id": "dev-ban-refresh"})

    assert r.status_code == 403, (
        f"забаненное устройство продлило вход ({r.status_code}): бан не выключает "
        "уже открытую сессию, а приложение обновляет ключи само и бесконечно"
    )


def test_ban_works_even_if_the_app_stops_sending_the_header(client, user_factory):
    """Заголовок шлёт клиент — значит его можно и не слать. Смотрим ещё и на память сервера.

    С какого аппарата человек входил, сервер помнит сам (`User.last_device_id` — по нему же
    он предупреждает о входе с нового устройства). Бан обязан опираться на эту память,
    иначе обходится удалением одной строки в запросе.
    """
    admin = user_factory("БанАдмин2", role=UserRole.admin)
    вход = _войти(client, _свежий_номер(), "dev-no-header")

    _забанить(client, admin, "dev-no-header")
    r = client.post("/auth/refresh", json={"refresh_token": вход["refresh_token"]})

    assert r.status_code == 403, (
        f"продление прошло без заголовка ({r.status_code}): бан обходится тем, "
        "что клиент перестаёт представляться"
    )


def test_ban_is_lifted_and_the_session_lives_again(client, user_factory):
    """Сняли бан — человек снова продлевает вход. Наказание конечно."""
    admin = user_factory("БанАдмин3", role=UserRole.admin)
    вход = _войти(client, _свежий_номер(), "dev-unban")
    _забанить(client, admin, "dev-unban")
    assert client.post("/auth/refresh",
                       json={"refresh_token": вход["refresh_token"]}).status_code == 403

    r = client.delete("/admin/bans/device/dev-unban", headers=admin["auth"])
    assert r.status_code == 200, r.text

    r = client.post("/auth/refresh", json={"refresh_token": вход["refresh_token"]})
    assert r.status_code == 200, f"бан сняли, а вход не продлевается: {r.text}"


# ==================== 2. Защита не бьёт по честным ====================
def test_clean_device_refreshes_as_before(client, user_factory):
    """Чужой бан не задевает нормального человека."""
    admin = user_factory("БанАдмин4", role=UserRole.admin)
    _забанить(client, admin, "dev-someone-elses")
    вход = _войти(client, _свежий_номер(), "dev-honest")

    r = client.post("/auth/refresh", json={"refresh_token": вход["refresh_token"]},
                    headers={"X-Device-Id": "dev-honest"})

    assert r.status_code == 200, r.text
    assert r.json().get("access_token"), "новая пара ключей не выдана"


def test_old_client_without_a_device_header_still_works(client, user_factory):
    """Старое приложение заголовок не шлёт и устройства за ним не записано — не наказываем."""
    вход = _войти(client, _свежий_номер(), "")     # вход без заголовка вообще

    r = client.post("/auth/refresh", json={"refresh_token": вход["refresh_token"]})

    assert r.status_code == 200, (
        f"человек со старым приложением потерял вход ({r.status_code}) — "
        "обновление доезжает не до всех"
    )


def test_moving_to_a_clean_device_restores_access(client, user_factory):
    """Бан на АППАРАТЕ, а не на человеке: пересел на другой телефон — работает.

    Иначе это уже пожизненная блокировка человека, а такого решения никто не принимал.
    """
    admin = user_factory("БанАдмин5", role=UserRole.admin)
    телефон = _свежий_номер()
    _войти(client, телефон, "dev-old-bad")
    _забанить(client, admin, "dev-old-bad")

    новый_вход = _войти(client, телефон, "dev-new-clean")
    r = client.post("/auth/refresh", json={"refresh_token": новый_вход["refresh_token"]},
                    headers={"X-Device-Id": "dev-new-clean"})

    assert r.status_code == 200, (
        f"человек пересел на чистый телефон, а вход не продлевается ({r.status_code}): "
        "бан устройства превратился в бан человека"
    )


# ==================== 3. Сторож: все двери входа под гейтом ====================
# Ручки, которые ВЫДАЮТ или ПРОДЛЕВАЮТ вход: после них человек работает в приложении.
ДВЕРИ_ВХОДА = ("/auth/request-code", "/auth/verify", "/auth/tg/verify", "/auth/refresh")


def _двери_без_гейта(исходник: str) -> list:
    """Какие из дверей входа не зовут проверку бана устройства.

    Режем файл по декораторам и берём блок ручки целиком: окном по числу символов длинную
    ручку не поймать, и сторож молча проверял бы пустоту (на этом я и попался, когда писал
    его первый раз).
    """
    import re

    блоки = {}
    for кусок in исходник.split("@router.")[1:]:
        m = re.match(r'\w+\("([^"]+)"', кусок)
        if m:
            блоки[m.group(1)] = кусок
    return [путь for путь in ДВЕРИ_ВХОДА
            if путь in блоки and "guard_device_not_banned" not in блоки[путь]]


def test_the_guard_itself_notices_a_missing_gate():
    """Сторож обязан уметь краснеть — иначе он просто зелёная строчка в отчёте.

    Мутационный проход показал ровно это: порчу ВНУТРИ сторожа он поймать не может.
    Значит нужен второй тест, который кормит ему заведомо дырявый файл и требует находки.
    """
    дырявый = (
        '@router.post("/auth/refresh")\n'
        "def refresh(body, session):\n"
        "    return rotate_refresh(session, body.refresh_token)\n\n"
        '@router.post("/auth/verify")\n'
        "def verify(body, session):\n"
        "    guard_device_not_banned(session, x_device_id)\n"
        "    return выдать_ключи()\n\n"
        '@router.get("/me")\n'
    )
    assert _двери_без_гейта(дырявый) == ["/auth/refresh"], (
        "сторож не замечает дверь без гейта — значит на настоящем файле он тоже ничего "
        "не проверяет"
    )


def test_the_guard_finds_all_the_doors():
    """И обратная сторона: все четыре двери в файле есть, сторож не устарел."""
    import pathlib

    исходник = (pathlib.Path("app") / "routers" / "auth.py").read_text(encoding="utf-8")
    for путь in ДВЕРИ_ВХОДА:
        assert f'"{путь}"' in исходник, f"ручка {путь} исчезла — сторож устарел, поправь его"


def test_every_login_door_checks_the_device_ban():
    """Появится пятая дверь — сторож напомнит поставить на неё тот же гейт.

    Так дыра и жила: гейт написали трижды поштучно, а продление входа добавили отдельно
    и про него забыли.
    """
    import pathlib

    исходник = (pathlib.Path("app") / "routers" / "auth.py").read_text(encoding="utf-8")
    без_гейта = _двери_без_гейта(исходник)

    assert not без_гейта, (
        f"эти двери выдают вход без проверки бана устройства: {без_гейта}. "
        "Бан обходится через ту из них, где гейта нет"
    )


def test_ban_record_is_what_the_gate_reads(client, user_factory):
    """Опора: гейт смотрит на запись бана, а не на что-то своё."""
    admin = user_factory("БанАдмин6", role=UserRole.admin)
    _забанить(client, admin, "dev-record")
    with Session(engine) as s:
        запись = s.exec(select(DeviceBan).where(DeviceBan.device_id == "dev-record")).first()
    assert запись is not None and запись.reason == "проба"


def test_last_device_is_remembered_on_login(client, user_factory):
    """Опора для второй половины защиты: сервер помнит аппарат сам."""
    вход = _войти(client, _свежий_номер(), "dev-memory")
    with Session(engine) as s:
        u = s.get(User, вход["user"]["id"])
    assert u.last_device_id == "dev-memory"
