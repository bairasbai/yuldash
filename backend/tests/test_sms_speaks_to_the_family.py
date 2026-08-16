"""SMS близким: на их языке и только тогда, когда договаривались.

**Язык беды.** Спокойные сообщения маме давно уходят на двух языках: «сел в машину»,
«доехал» (волна 95). А единственное срочное — «SOS, место, машина» — уходило всегда
по-русски (аудит 2026-08-08, волна 121). Получалось наоборот: про обычное мама читала
по-башкирски, а про беду — на чужом языке, в тот момент, когда разбираться некогда.

Язык берём у того, кто завёл контакт: про язык его мамы мы ничего не знаем, а он знает.

**Пометка «Только SOS».** У контакта есть переключатель: тревожить только при беде. Человек
ставит его пожилой маме. В попутке пометку учли (волна 81), а в такси — нет: та же мама
получала SMS на каждый шаг такси-заказа. Одно обещание, две двери, закрыта была одна.

Но при самой беде SMS уходит ВСЕМ контактам, включая помеченных: жизнь дороже настроек.
Это проверяется отдельно — чтобы никто не «дочинил» пометку до опасного состояния.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

import app.services as svc
from app.db import engine
from app.models import User


@pytest.fixture
def смс(monkeypatch):
    поймано: list[tuple[str, str]] = []
    monkeypatch.setattr(svc, "send_text", lambda ph, t: поймано.append((ph, t)))
    import app.instant_service as isv
    monkeypatch.setattr(isv, "send_text", lambda ph, t: поймано.append((ph, t)))
    import app.routers.safety as safety
    monkeypatch.setattr(safety, "send_text", lambda ph, t: поймано.append((ph, t)))
    import app.routers.family as family
    monkeypatch.setattr(family, "send_text", lambda ph, t: поймано.append((ph, t)))
    return поймано


def _на_башкирском(client, user_factory, имя: str):
    человек = user_factory(имя)
    with Session(engine) as s:
        u = s.get(User, человек["id"])
        u.language = "ba"
        s.add(u)
        s.commit()
    return человек


def test_беда_говорит_на_языке_человека(client, user_factory, смс):
    зухра = _на_башкирском(client, user_factory, "СмсЗухра")
    client.post("/trusted-contacts", headers=зухра["auth"],
                json={"name": "Мама", "phone": "+79990000121"})
    смс.clear()

    assert client.post("/sos", headers=зухра["auth"],
                       json={"lat": 52.6, "lng": 58.3}).status_code == 200

    assert смс, "SOS не дошёл до близких вовсе"
    текст = смс[-1][1]
    assert "ярҙам" in текст, (
        f"в самый страшный момент маме пришло на чужом языке: {текст[:80]}"
    )


def test_застрял_на_трассе_тоже_на_своём(client, user_factory, смс):
    ильдар = _на_башкирском(client, user_factory, "СмсИльдар")
    client.post("/trusted-contacts", headers=ильдар["auth"],
                json={"name": "Брат", "phone": "+79990000122"})
    смс.clear()

    r = client.post("/roadside-help", headers=ильдар["auth"],
                    json={"lat": 52.6, "lng": 58.3, "note": ""})

    if r.status_code == 200 and смс:
        assert "юлда" in смс[-1][1], f"«застрял на трассе» ушло по-русски: {смс[-1][1][:80]}"


def test_русскоязычному_приходит_по_русски(client, user_factory, смс):
    """Обратная сторона: перевод не должен подменить язык тем, кто выбрал русский."""
    марат = user_factory("СмсМарат")
    client.post("/trusted-contacts", headers=марат["auth"],
                json={"name": "Жена", "phone": "+79990000123"})
    смс.clear()

    client.post("/sos", headers=марат["auth"], json={"lat": 52.6, "lng": 58.3})

    assert смс and "просит срочной помощи" in смс[-1][1], смс


def test_при_беде_пишут_даже_помеченным_только_sos(client, user_factory, смс):
    """Пометка гасит обычные сообщения, но не беду: жизнь дороже настроек."""
    человек = user_factory("СмсОсторожный")
    client.post("/trusted-contacts", headers=человек["auth"],
                json={"name": "Мама", "phone": "+79990000124", "notify_by_default": False})
    смс.clear()

    client.post("/sos", headers=человек["auth"], json={"lat": 52.6, "lng": 58.3})

    assert смс, "маму не предупредили о беде из-за пометки «только SOS» — это опаснее лишнего SMS"


def test_пометка_только_sos_действует_и_в_такси():
    """Сторож на класс: обещание должно работать во всех видах поездок, а не в одном.

    Проверяется по исходникам, потому что мест два и они в разных файлах — а разошлись они
    именно потому, что каждый писался отдельно.
    """
    from pathlib import Path

    app_dir = Path(__file__).resolve().parents[1] / "app"
    попутка = (app_dir / "routers" / "family.py").read_text(encoding="utf-8")
    такси = (app_dir / "instant_service.py").read_text(encoding="utf-8")
    assert "notify_by_default" in попутка, "в попутке пометка «Только SOS» перестала учитываться"
    assert "notify_by_default" in такси, (
        "в такси пометка «Только SOS» не учитывается: мама, которую просили тревожить только "
        "при беде, снова получает SMS на каждый шаг заказа"
    )
