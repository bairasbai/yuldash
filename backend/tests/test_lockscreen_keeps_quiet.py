"""Погашенный экран не должен рассказывать соседям про жалобы и долги.

История. Телефон лежит на столе — в доме, где живёт вся семья. Или в кармане у водителя
на стоянке, где он с коллегами. Экран гаснет, приходит уведомление, и на нём читается
целиком:

    «Поступила жалоба. Категория: не заплатил»
    «Такси на паузе — за месяц накопилось несколько подтверждённых жалоб»
    «Долг списан: пассажир не заплатил»

В районе, где все друг друга знают, это не мелочь: это разговор у магазина. Волна 14 уже
закрыла так переписку, но остановилась на ней — приватным помечался КАНАЛ чата, а не смысл
сообщения (аудит 2026-08-08, волна 110).

Теперь сервер сам помечает чувствительное флагом, а приложение показывает на замке нейтральное
«Юлдаш · Новое уведомление» и открывает текст после разблокировки.

Обратная сторона: обычные пуши остаются как есть. «Водитель подъезжает» полезен именно
с погашенного экрана — пассажир стоит на улице и не должен разблокировать телефон, чтобы
понять, идти ему к дороге или нет. И помощь: ни один SOS этим типом не ходит.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

import app.services as svc
from app.db import engine


@pytest.fixture
def caught(monkeypatch):
    """Ловим то, что реально уходит на телефон: заголовок и служебные поля."""
    out: list[tuple[str, dict | None]] = []
    monkeypatch.setattr(svc, "send_push", lambda s, uid, t, b, data=None: out.append((t, data)))
    # Сдвигаем часы на день: ночью пуш не уходит вовсе (волна 105), и тест бы просто молчал.
    monkeypatch.setattr(svc, "_is_quiet_hour", lambda: False)
    return out


def _notify(user_id: int, ntype: str, title: str = "Заголовок", **kw):
    with Session(engine) as s:
        svc.push_notification(s, user_id, ntype, title, "Баш", "текст", "текст", **kw)


def _flag(entry) -> str | None:
    return (entry[1] or {}).get("private")


def test_жалоба_не_читается_с_погашенного_экрана(client, user_factory, caught):
    person = user_factory("ЗамокРустам")

    _notify(person["id"], "safety", "Поступила жалоба")

    assert caught, "пуш вообще не ушёл"
    assert _flag(caught[-1]) == "1", (
        "текст жалобы уйдёт на замок целиком — его прочитает любой, кто взял телефон со стола"
    )


def test_долг_тоже_личное(client, user_factory, caught):
    person = user_factory("ЗамокИльдар")

    _notify(person["id"], "money", "Долг списан")

    assert _flag(caught[-1]) == "1", "деньги и долги видны на замке"


def test_просроченные_документы_личное(client, user_factory, caught):
    person = user_factory("ЗамокМарат")

    _notify(person["id"], "docs", "Такси на паузе: документы просрочены")

    assert _flag(caught[-1]) == "1", "чужие глаза узнают, что у человека просрочены документы"


def test_водитель_подъезжает_виден_сразу(client, user_factory, caught):
    """Обратная сторона: полезное на замке прятать нельзя.

    Пассажирка стоит на улице с сумками. «Водитель подъезжает» она должна прочитать, не
    разблокируя телефон, — иначе смысл уведомления теряется.
    """
    person = user_factory("ЗамокГульнара")

    _notify(person["id"], "booking", "Водитель подъезжает")

    assert _flag(caught[-1]) is None, "обычный пуш спрятали — теперь его надо разблокировать"


def test_помощь_на_замке_не_прячется(client, user_factory, caught):
    """Сторож: ни один сигнал SOS не должен ходить типом, который мы прячем."""
    import re
    from pathlib import Path

    app_dir = Path(__file__).resolve().parents[1] / "app"
    guilty = []
    for path in app_dir.rglob("*.py"):
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if not re.search(r'"(safety|money|docs)"\s*,', line):
                continue
            around = "\n".join(lines[i: i + 3])
            if re.search(r"SOS|тревог|Тревог|на помощь", around):
                guilty.append(f"{path.name}:{i + 1}")
    assert not guilty, (
        "сигнал о помощи ходит типом, который прячется на замке: " + "; ".join(guilty)
        + ". Такое человек должен увидеть, не разблокируя телефон."
    )


def test_приложение_понимает_флаг():
    """Сервер может ставить флаг сколько угодно — прячет его приложение.

    Проверяется по исходникам: поведение живёт на телефоне, и заметить его расхождение
    с сервером иначе негде — пуш до эмулятора в тестах не доходит.
    """
    from pathlib import Path

    fcm = (Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "java" /
           "com" / "yuldash" / "app" / "data" / "FcmService.kt")
    src = fcm.read_text(encoding="utf-8")
    assert 'msg.data["private"]' in src, "приложение перестало читать флаг приватности с сервера"
    assert "setPublicVersion" in src, (
        "нет безопасной версии уведомления — само по себе VISIBILITY_PRIVATE ничего не прячет"
    )
    # Мало прочитать флаг — по нему надо ДЕЙСТВОВАТЬ. Первая версия этого теста проверяла
    # только чтение, и правка «убрать флаг из условия» проходила мимо неё незамеченной.
    condition = [ln for ln in src.splitlines() if ln.strip().startswith("if (") and "privateText" in ln]
    assert condition, (
        "флаг приватности читается, но ни на что не влияет: безопасная версия строится "
        "по-старому, только для канала чата — жалобы и долги снова видны на замке"
    )
