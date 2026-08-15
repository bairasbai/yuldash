"""Отказ по блокировке не должен объяснять причину — и это осознанно.

Обычно правило проекта такое: ошибка говорит человеку, что произошло и что делать. Здесь —
единственное место, где мы намеренно молчим.

Почему. Гульнара заблокировала Рустама после неприятной поездки. Если приложение ответит ему
«этот человек вас заблокировал», он узнает то, чего знать не должен: она от него закрылась.
В деревне, где все друг друга знают, это не абстракция — это разговор у магазина, обида
и иногда что похуже. Блокировка обязана работать тихо: со стороны она выглядит просто как
«недоступно».

Опасность в том, что глухой текст выглядит как недоделка. Любой, кто придёт «улучшать
сообщения об ошибках» — включая меня в соседней волне, — захочет дописать причину. Этот тест
существует, чтобы такая правка сразу покраснела.

Проверяются обе стороны: и что причина не раскрыта, и что сам отказ по-прежнему работает.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.models import UserRole

from test_api import _ride

ROUTERS = Path(__file__).resolve().parents[1] / "app" / "routers"

# Слова, которые выдали бы факт блокировки.
REVEALING = re.compile(
    r"(заблокирова|блокиров|чёрн\w+ список|черн\w+ список|занёс|внесл\w+ в список)", re.IGNORECASE,
)


def _block(client, who, whom_id: int):
    r = client.post("/blocks", headers=who["auth"], json={"blocked_user_id": whom_id})
    assert r.status_code in (200, 201), r.text


def test_отказ_по_блокировке_не_называет_причину(client, user_factory):
    """Заблокированный водитель пытается подтвердить бронь — и не узнаёт, почему нельзя."""
    driver = user_factory("СекретВодитель", role=UserRole.driver)
    passenger = user_factory("СекретПассажирка")
    ride_id = _ride(client, driver, comment="секрет блокировки")
    booking_id = client.post("/bookings", headers=passenger["auth"],
                             json={"ride_id": ride_id, "seats": 1}).json()["id"]
    _block(client, passenger, driver["id"])

    r = client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"])

    assert r.status_code == 403, f"блокировка перестала останавливать: {r.status_code}"
    text = str(r.json())
    assert not REVEALING.search(text), (
        f"отказ выдал факт блокировки: {text}. Человек не должен узнать, что от него закрылись "
        "— в деревне это разговор у магазина, а не абстракция."
    )


def test_отклик_на_заявку_молчит_так_же(client, user_factory):
    driver = user_factory("СекретОткликВодитель", role=UserRole.driver)
    passenger = user_factory("СекретОткликПассажирка")
    r = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-11-01T10:00:00", "seats": 1,
    })
    assert r.status_code == 200, r.text
    request_id = r.json()["id"]
    _block(client, passenger, driver["id"])

    r = client.post(f"/requests/{request_id}/respond", headers=driver["auth"], json={"price": 300})

    assert r.status_code in (403, 404), r.status_code
    assert not REVEALING.search(str(r.json())), f"отклик выдал блокировку: {r.json()}"


def test_глухие_тексты_не_превратились_в_объяснения():
    """Сторож на будущее: рядом с проверкой блокировки не должно появиться слово «заблокировал».

    Ловится по исходникам, потому что мест таких восемь — от чата до посылок, и переписать
    «поприветливее» можно любое из них, не тронув то, которое покрыто тестом выше.
    """
    guilty = []
    for path in sorted(ROUTERS.glob("*.py")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if "is_blocked(" not in line:
                continue
            around = "\n".join(lines[i: i + 4])          # сам отказ идёт следующей строкой
            for m in re.finditer(r'"([^"]{4,})"', around):
                if REVEALING.search(m.group(1)):
                    guilty.append(f"{path.name}:{i + 1} → {m.group(1)[:50]}")
    assert not guilty, (
        "Рядом с проверкой блокировки появился текст, раскрывающий её: "
        + "; ".join(guilty)
        + ". Отказ здесь ДОЛЖЕН быть глухим — это не недоделка, а защита того, кто закрылся."
    )


@pytest.mark.parametrize("name", ["bookings.py", "chat.py", "parcels.py", "requests.py"])
def test_проверка_блокировки_на_месте(name: str):
    """Обратная сторона: молчание не должно превратиться в отсутствие проверки."""
    src = (ROUTERS / name).read_text(encoding="utf-8")
    assert "is_blocked(" in src, (
        f"в {name} пропала проверка блокировки — теперь заблокированный снова доберётся "
        "до человека, который от него закрылся"
    )
