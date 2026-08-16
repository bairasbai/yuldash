"""«Удалили всё, что вы загружали» должно быть правдой — и бонус за друга должен приходить.

**Файлы после удаления аккаунта.** Волна 15 уже добавила уборку файлов, на которые нигде нет
ссылки: человек записал голосовое, передумал отправлять — файл лежит на диске, а в базе о нём
ничего. Уборка ищет такие файлы по префиксу имени: сервер начинает имя с номера владельца.

Только вот голосовые и фото чата именовались БЕЗ номера — просто случайной строкой
(аудит 2026-08-08, волна 127). То есть код честно перечислял области «chat» и «voice», а найти
в них ничего не мог. Проверено пробой: после загрузки поиск по владельцу давал ноль файлов,
и они переживали удаление аккаунта, уходя лишь ночной чисткой через 35 дней — дольше срока,
который обещан в законе о персональных данных.

Это «зелёное на пустоте»: строчка уборки есть, работает, ничего не находит.

**Бонус за друга.** Сосед позвал водителя в Юлдаш, тот раскатался — бонус пригласившему
не приходил. Начисление стояло на трёх дверях к «поездка состоялась» (бронь у водителя,
семейный контроль, такси), а на четвёртой — кнопке «Завершить рейс», самой частой у водителя, —
его не было. Проверено пробой: три рейса, закрытые этой кнопкой, дали ноль бонусов.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import ReferralBonus, Ride, User, UserRole
from app.storage import get_storage
from app.timeutil import utcnow

from test_api import _ride

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 200
M4A = b"\x00\x00\x00\x20ftypM4A " + b"\x00" * 100


def _файлы(uid: int) -> list[str]:
    st = get_storage()
    return [k for area in ("voice", "chat", "docs", "evidence") for k in st.iter_owned([area], uid)]


def test_загруженное_и_неприложенное_находится_по_владельцу(client, user_factory):
    """Главное: без этого уборка при удалении аккаунта ищет и не находит.

    Считаем ПРИРОСТ, а не общее число: хранилище в тестах общее, и от прошлых прогонов там
    остаются файлы с тем же номером владельца. Первая версия этого теста именно на этом
    и ослепла — мутация «убрать номер из имени» проходила мимо неё зелёной.
    """
    человек = user_factory("СледЧеловек")
    было = set(_файлы(человек["id"]))

    assert client.post("/voice", headers=человек["auth"],
                       files={"file": ("v.m4a", M4A, "audio/mp4")}).status_code == 200
    assert client.post("/upload/chat-photo", headers=человек["auth"],
                       files={"file": ("p.png", PNG, "image/png")}).status_code == 200

    новые = set(_файлы(человек["id"])) - было

    assert len(новые) == 2, (
        f"только что загруженные файлы не находятся по владельцу: {новые}. Значит уборка "
        "при удалении аккаунта пройдёт вхолостую, а голосовые и фото останутся на диске"
    )
    assert any(k.startswith("voice/") for k in новые), f"голосовое не найдено: {новые}"
    assert any(k.startswith("chat/") for k in новые), f"фото чата не найдено: {новые}"


def test_удаление_аккаунта_забирает_эти_файлы(client, user_factory):
    человек = user_factory("СледУходящий")
    client.post("/voice", headers=человек["auth"], files={"file": ("v.m4a", M4A, "audio/mp4")})
    client.post("/upload/chat-photo", headers=человек["auth"], files={"file": ("p.png", PNG, "image/png")})
    assert _файлы(человек["id"]), "проба не удалась: файлов нет ещё до удаления"

    r = client.post("/me/delete", headers=человек["auth"], json={"confirm": "УДАЛИТЬ"})

    assert r.status_code == 200, r.text
    assert _файлы(человек["id"]) == [], (
        "файлы пережили удаление аккаунта — «удалили всё, что вы загружали» оказалось неправдой"
    )


def test_чужие_файлы_удаление_не_трогает(client, user_factory):
    """Обратная сторона: уборка по префиксу не должна задеть соседа."""
    уходит = user_factory("СледУходит2")
    остаётся = user_factory("СледОстаётся")
    client.post("/voice", headers=остаётся["auth"], files={"file": ("v.m4a", M4A, "audio/mp4")})
    client.post("/voice", headers=уходит["auth"], files={"file": ("v.m4a", M4A, "audio/mp4")})
    было_у_соседа = len(_файлы(остаётся["id"]))

    client.post("/me/delete", headers=уходит["auth"], json={"confirm": "УДАЛИТЬ"})

    assert len(_файлы(остаётся["id"])) == было_у_соседа, "удаление задело файлы постороннего"


@pytest.fixture
def приглашённый_водитель(client, user_factory):
    сосед = user_factory("БонусСосед")
    водитель = user_factory("БонусВодитель", role=UserRole.driver)
    with Session(engine) as s:
        d = s.get(User, водитель["id"])
        d.referred_by = сосед["id"]
        s.add(d)
        s.commit()
    return сосед, водитель


def _рейс_завершён_кнопкой(client, водитель, user_factory, номер: int) -> None:
    from datetime import timedelta

    пассажир = user_factory(f"БонусПас{номер}")
    rid = _ride(client, водитель, comment=f"рейс {номер}", day=f"2030-05-0{номер + 1}")
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": rid, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    client.post(f"/bookings/{bid}/board", headers=водитель["auth"], json={"code": ""})
    with Session(engine) as s:                       # время выезда прошло
        r = s.get(Ride, rid)
        r.depart_at = utcnow() - timedelta(hours=2)
        s.add(r)
        s.commit()
    assert client.post(f"/rides/{rid}/complete", headers=водитель["auth"]).status_code == 200


def test_бонус_приходит_и_за_кнопку_завершить_рейс(client, приглашённый_водитель, user_factory):
    """Водитель закрывает рейсы этой кнопкой — значит бонус должен приходить и здесь."""
    _, водитель = приглашённый_водитель
    for i in range(3):                               # нужны три РАЗНЫХ пассажира
        _рейс_завершён_кнопкой(client, водитель, user_factory, i)

    with Session(engine) as s:
        бонусы = s.exec(select(ReferralBonus).where(
            ReferralBonus.invited_user_id == водитель["id"]
        )).all()

    assert бонусы, (
        "сосед позвал водителя, тот раскатался — а бонус не начислен, потому что рейсы "
        "закрывались не той кнопкой"
    )


def test_бонус_не_задваивается(client, приглашённый_водитель, user_factory):
    """Обратная сторона: четвёртая дверь не должна начислить второй бонус за того же человека."""
    _, водитель = приглашённый_водитель
    for i in range(4):
        _рейс_завершён_кнопкой(client, водитель, user_factory, i)

    with Session(engine) as s:
        бонусы = s.exec(select(ReferralBonus).where(
            ReferralBonus.invited_user_id == водитель["id"]
        )).all()

    assert len(бонусы) == 1, f"бонус начислен {len(бонусы)} раз за одного приглашённого"


def test_все_двери_к_завершению_начисляют_бонус():
    """Сторож: дверей к «поездка состоялась» четыре, и они уже расходились."""
    from pathlib import Path

    routers = Path(__file__).resolve().parents[1] / "app" / "routers"
    for файл in ("bookings.py", "family.py", "instant.py", "rides.py"):
        src = (routers / файл).read_text(encoding="utf-8")
        assert "reward_driver_referral" in src, (
            f"в {файл} завершение поездки не начисляет бонус пригласившему — "
            "человек, позвавший водителя, останется без обещанного"
        )
