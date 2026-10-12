"""Промокод доился с одного номера: удалил аккаунт — скидка снова.

«Один код на всю жизнь аккаунта» держал уникальный индекс по человеку. Но удаление аккаунта
уносило запись вместе с ним, а номер оставался тем же (аудит 2026-08-08, волна 149).

Проверено пробой: вошёл по SMS, применил код на 300 ₽, съездил, удалил аккаунт, вошёл снова
с ТЕМ ЖЕ номером и с того же телефона — код принялся заново. Три круга подряд.

Каждый круг — прямые деньги Александра: скидку по промокоду оплачивает платформа, а водитель
получает своё полностью. Если водитель в сговоре, это готовый канал обналички: один номер,
бесконечно.

Теперь рядом с записью «этот человек применял код» живёт след «этот номер применял код»,
и он переживает удаление аккаунта — в этом весь смысл. Тот же приём, что у пожизненного
счётчика рефералов и журнала SMS близким.

**Что в следе.** Ключ телефона (только цифры) и отметка устройства. Самого номера нет: вторая
копия чужого телефона в базе не нужна, а по ключу человека не найти, если не знать номер
заранее. Живёт след дольше аккаунта намеренно, чистится по сроку — кампания ведь заканчивается.
"""
from __future__ import annotations

from sqlmodel import Session, select

from app.db import engine
from app.models import PromoClaimLog, User, UserRole
from app.routers.promo import _phone_claim_key

НОМЕР = "+79995551490"


def _кампания(client, админ, code: str = "SKIDKA300") -> int:
    r = client.post("/admin/promo", headers=админ["auth"], json={
        "code": code, "title": "Скидка на такси", "kind": "taxi_ride",
        "perk_value": 300, "limit_total": 0,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _человек_с_номером(user_factory, имя: str, номер: str):
    """Обычный человек, у которого в аккаунте настоящий телефонный номер."""
    ч = user_factory(имя)
    with Session(engine) as s:
        u = s.get(User, ч["id"])
        u.phone = номер
        s.add(u)
        s.commit()
    return ч


def test_после_удаления_аккаунта_код_повторно_не_берётся(client, user_factory):
    """Главное: платформа не должна платить одну и ту же скидку человеку дважды."""
    админ = user_factory("ПромоАдмин", role=UserRole.admin)
    _кампания(client, админ)
    первый = _человек_с_номером(user_factory, "ПромоКруг1", НОМЕР)
    первый_раз = client.post("/promo/apply", headers=первый["auth"], json={"code": "SKIDKA300"})
    assert первый_раз.status_code == 200, первый_раз.text

    client.post("/me/delete", headers=первый["auth"])
    второй = _человек_с_номером(user_factory, "ПромоКруг2", НОМЕР)   # тот же номер
    второй_раз = client.post("/promo/apply", headers=второй["auth"], json={"code": "SKIDKA300"})

    assert второй_раз.status_code == 409, (
        f"с того же номера код взяли повторно ({второй_раз.status_code}): каждый круг — "
        "живые деньги платформы, и повторять его можно бесконечно"
    )


def test_след_переживает_удаление_аккаунта(client, user_factory):
    """Сам механизм: запись о человеке уходит, запись о номере остаётся."""
    админ = user_factory("ПромоАдмин2", role=UserRole.admin)
    pid = _кампания(client, админ, "SLED100")
    человек = _человек_с_номером(user_factory, "ПромоСлед", "+79995551491")
    client.post("/promo/apply", headers=человек["auth"], json={"code": "SLED100"})

    client.post("/me/delete", headers=человек["auth"])

    with Session(engine) as s:
        след = s.exec(select(PromoClaimLog).where(PromoClaimLog.promo_id == pid)).all()
    assert след, "след исчез вместе с аккаунтом — значит и защиты нет"
    # Ключ — HMAC номера (leaf-1.3, круг 3-4), не сам номер цифрами: самого номера в следе
    # нет и по строке его не узнать, даже если база утечёт.
    assert след[0].phone_key == _phone_claim_key("+79995551491"), след[0].phone_key
    assert "79995551491" not in след[0].phone_key, "в следе не должно быть самого номера"
    assert "79995551491" not in [след[0].device_id], "в следе не должно быть самого номера"


def test_другой_человек_код_возьмёт(client, user_factory):
    """Обратная сторона: кампания должна работать — код берут разные люди."""
    админ = user_factory("ПромоАдмин3", role=UserRole.admin)
    _кампания(client, админ, "OBSHIY50")
    первый = _человек_с_номером(user_factory, "ПромоПервый", "+79995551492")
    второй = _человек_с_номером(user_factory, "ПромоВторой", "+79995551493")

    a = client.post("/promo/apply", headers=первый["auth"], json={"code": "OBSHIY50"})
    b = client.post("/promo/apply", headers=второй["auth"], json={"code": "OBSHIY50"})

    assert a.status_code == 200, a.text
    assert b.status_code == 200, (
        f"второй человек не смог применить код ({b.status_code}): кампания перестала работать"
    )


def test_с_одного_телефона_второй_аккаунт_код_не_возьмёт(client, user_factory):
    """Ферма на одном телефоне: номера меняются, трубка та же."""
    админ = user_factory("ПромоАдмин4", role=UserRole.admin)
    _кампания(client, админ, "FERMA20")
    один = _человек_с_номером(user_factory, "ПромоФерма1", "+79995551494")
    два = _человек_с_номером(user_factory, "ПромоФерма2", "+79995551495")
    трубка = {"X-Device-Id": "device-ferma"}

    первый = client.post("/promo/apply", headers={**один["auth"], **трубка},
                         json={"code": "FERMA20"})
    второй = client.post("/promo/apply", headers={**два["auth"], **трубка},
                         json={"code": "FERMA20"})

    assert первый.status_code == 200, первый.text
    assert второй.status_code == 409, (
        f"с одной трубки код взяли дважды под разными номерами ({второй.status_code})"
    )


def test_разные_кампании_не_мешают_друг_другу(client, user_factory):
    """Обратная сторона: след привязан к кампании, а не к человеку вообще.

    Иначе новая акция не досталась бы тем, кто участвовал в прошлой, — а это и есть люди,
    которые уже пользуются приложением.
    """
    админ = user_factory("ПромоАдмин5", role=UserRole.admin)
    _кампания(client, админ, "OSEN10")
    _кампания(client, админ, "ZIMA10")
    человек = _человек_с_номером(user_factory, "ПромоДвеАкции", "+79995551496")
    client.post("/promo/apply", headers=человек["auth"], json={"code": "OSEN10"})
    client.post("/me/delete", headers=человек["auth"])
    новый = _человек_с_номером(user_factory, "ПромоДвеАкции2", "+79995551496")

    зима = client.post("/promo/apply", headers=новый["auth"], json={"code": "ZIMA10"})

    assert зима.status_code == 200, (
        f"новая кампания не досталась человеку из-за участия в прошлой: {зима.text[:120]}"
    )


def test_след_не_хранит_сам_номер():
    """Приватность: вторая копия чужого телефона в базе не нужна."""
    поля = set(PromoClaimLog.model_fields)

    assert "phone" not in поля, f"в следе лежит сам номер телефона: {поля}"
    assert "phone_key" in поля, "нет ключа номера — по чему тогда узнавать повтор"


def test_след_учтён_в_правилах_хранения():
    """Сторож: новая таблица обязана быть в списке «что чистим или храним всегда».

    Иначе она попадёт в слепую зону — будет расти вечно, и никто не заметит.
    """
    from pathlib import Path

    src = (Path(__file__).resolve().parents[1] / "app" / "cleanup.py").read_text(encoding="utf-8")

    assert "promoclaimlog" in src, (
        "след промокодов не упомянут в правилах хранения: он будет расти вечно, "
        "и об этом никто не узнает"
    )
