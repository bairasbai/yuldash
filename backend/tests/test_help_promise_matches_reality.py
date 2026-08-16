"""«Близкие получили твои координаты» — только если они правда получили.

История. Курьер встал на трассе в минус двадцать и нажал «Позвать помощь». Экран спокойно
пишет: «Помощь вызвана: близкие и поддержка получили твои координаты». Он выдыхает и перестаёт
звонить сам.

А доверенных контактов он никогда не заводил. SMS не ушло никому — только запись дежурному
(аудит 2026-08-08, волна 122). Обещание было написано в приложении раз и навсегда, без оглядки
на то, что произошло на самом деле.

Теперь сервер отвечает, СКОЛЬКИМ близким реально ушло, а экран выбирает текст по этому числу:
если некому — так и говорит и подсказывает добавить доверенных.
"""
from __future__ import annotations

import pytest

import app.services as svc

from test_api import _ride
from app.models import UserRole


@pytest.fixture
def смс(monkeypatch):
    поймано: list[tuple[str, str]] = []
    monkeypatch.setattr(svc, "send_text", lambda ph, t: поймано.append((ph, t)))
    import app.routers.safety as safety
    monkeypatch.setattr(safety, "send_text", lambda ph, t: поймано.append((ph, t)))
    return поймано


@pytest.fixture
def в_поездке(client, user_factory):
    водитель = user_factory("ПомощьВодитель", role=UserRole.driver)
    пассажир = user_factory("ПомощьКурьер")
    ride_id = _ride(client, водитель, comment="зимняя трасса")
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    return пассажир, bid


def test_без_доверенных_сервер_честно_отвечает_ноль(client, в_поездке, смс):
    """Главное: приложение должно узнать, что звать было некого."""
    пассажир, bid = в_поездке
    смс.clear()

    r = client.post(f"/bookings/{bid}/stuck", headers=пассажир["auth"],
                    json={"lat": 52.6, "lng": 58.3, "note": ""})

    assert r.status_code == 200, r.text
    assert r.json().get("contacts_notified") == 0, (
        f"сервер не сказал, что близких нет: {r.json().get('contacts_notified')}. Экран напишет "
        "«близкие получили твои координаты», человек перестанет звонить сам — а не ушло никому"
    )
    assert not смс, f"SMS ушло, хотя контактов нет: {смс}"


def test_с_доверенными_отвечает_сколько(client, в_поездке, смс):
    """Обратная сторона: когда позвали — так и надо сказать."""
    пассажир, bid = в_поездке
    client.post("/trusted-contacts", headers=пассажир["auth"],
                json={"name": "Брат", "phone": "+79990000131"})
    client.post("/trusted-contacts", headers=пассажир["auth"],
                json={"name": "Сосед", "phone": "+79990000132"})
    смс.clear()

    r = client.post(f"/bookings/{bid}/stuck", headers=пассажир["auth"],
                    json={"lat": 52.6, "lng": 58.3, "note": ""})

    assert r.json().get("contacts_notified") == 2, r.json()
    assert len(смс) == 2, смс


def test_экран_выбирает_текст_по_факту():
    """Сторож: обещание в приложении не должно снова стать безусловным.

    Проверяется по исходникам — этот текст живёт на телефоне, и разойтись с сервером он может
    молча. Ровно так дыра и появилась: сервер не сообщал число, а экран уверенно обещал.
    """
    from pathlib import Path

    экран = (Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "java" /
             "com" / "yuldash" / "app" / "RoadsideHelp.kt").read_text(encoding="utf-8")
    assert "notified > 0" in экран, (
        "экран снова обещает «близкие получили твои координаты» всем подряд — даже тому, "
        "у кого доверенных контактов нет и SMS не ушло никому"
    )
    assert "Доверенных" in экран, (
        "нет подсказки, что делать: человеку мало узнать, что звать было некого — "
        "он должен понять, как это исправить к следующему разу"
    )


def test_все_три_кнопки_помощи_возвращают_число():
    """Кнопка одна и та же на попутке, такси и доставке — ответ тоже должен быть одинаковым."""
    from pathlib import Path

    клиент = (Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "java" /
              "com" / "yuldash" / "app" / "data" / "ApiClient.kt").read_text(encoding="utf-8")
    for имя in ("roadsideHelp", "instantRoadsideHelp", "parcelRoadsideHelp"):
        строка = [ln for ln in клиент.splitlines() if f"suspend fun {имя}(" in ln]
        assert строка and "Result<Int>" in строка[0], (
            f"{имя} не сообщает, скольким ушло: на этом экране обещание снова разойдётся с делом"
        )
