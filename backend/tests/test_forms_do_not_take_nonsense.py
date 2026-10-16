"""Форма не должна принимать бессмыслицу и делать вид, что всё хорошо.

Три истории про то, как палец промахивается по стеклу в машине.

**Пустое сообщение.** Гульнара задела кнопку отправки, в поле были одни пробелы. Сообщение
уходило, водителю звонил телефон «Новое сообщение», он открывал чат — пустой пузырь.
Проверка на пустоту в проекте была, но только при ПРАВКЕ сообщения; при отправке её не было
(аудит 2026-08-08, волна 108).

**Один и тот же город.** «Сибай → Сибай» публиковалось спокойно. В расписании водителя такое
давно ловится словами «города отправления и назначения совпадают», а в поездке — нет.
Для пассажира это объявление-призрак: он пишет водителю и выясняет, что тот ошибся полем.

**Название из смайликов.** «🚗🚗 → Сибай» тоже проходило и попадало в общую ленту района.

Обратная сторона везде одна: настоящие названия и настоящие сообщения должны проходить.
Башкирские буквы (Баймаҡ, Стәрлетамаҡ) — это буквы, а не «мусор».
"""
from __future__ import annotations

import ast
from pathlib import Path

import pytest

from app.models import UserRole

from test_api import _ride

CHAT = Path(__file__).resolve().parents[1] / "app" / "routers" / "chat.py"


def _publish(client, driver, from_city: str, to_city: str = "Сибай", day: str = "2030-12-05"):
    return client.post("/rides", headers=driver["auth"], json={
        "from_city": from_city, "to_city": to_city,
        "depart_at": f"{day}T10:00:00", "seats_total": 3, "price": 300,
    })


@pytest.fixture
def chat(client, user_factory):
    """Подтверждённая бронь с открытым чатом: водитель и пассажир."""
    driver = user_factory("ФормаВодитель", role=UserRole.driver)
    passenger = user_factory("ФормаПассажир")
    ride_id = _ride(client, driver, comment="формы")
    booking_id = client.post("/bookings", headers=passenger["auth"],
                             json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200
    return passenger, booking_id


def test_сообщение_из_пробелов_не_уходит(client, chat):
    passenger, booking_id = chat

    r = client.post(f"/bookings/{booking_id}/messages", headers=passenger["auth"],
                    json={"text": "    "})

    assert r.status_code == 400, "пустое сообщение ушло — собеседника разбудили пустым пузырём"
    assert "пуст" in str(r.json()).lower(), r.json()


def test_обычное_сообщение_уходит(client, chat):
    """Обратная сторона: строгость не должна мешать написать «еду»."""
    passenger, booking_id = chat

    r = client.post(f"/bookings/{booking_id}/messages", headers=passenger["auth"],
                    json={"text": "выезжаю через пять минут"})

    assert r.status_code == 200, r.text


def _chat_doors_without_empty_guard(source: str):
    """Follow only real calls that pass this request body into a local helper."""
    tree = ast.parse(source)
    functions = {node.name: node for node in tree.body
                 if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}
    doors = [node for node in functions.values() for decorator in node.decorator_list
             if isinstance(decorator, ast.Call) and isinstance(decorator.func, ast.Attribute)
             and isinstance(decorator.func.value, ast.Name) and decorator.func.value.id == "router"
             and decorator.func.attr == "post" and decorator.args
             and isinstance(decorator.args[0], ast.Constant)
             and isinstance(decorator.args[0].value, str) and "/messages" in decorator.args[0].value]

    def trims_text(node, parameter):
        return any(isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute)
                   and call.func.attr == "strip" and any(
                       isinstance(value, ast.Attribute) and value.attr == "text"
                       and isinstance(value.value, ast.Name) and value.value.id == parameter
                       for value in ast.walk(call.func.value)) for call in ast.walk(node))

    def guarded(function, parameter, visited):
        edge = (function.name, parameter)
        if edge in visited:
            return False
        visited = visited | {edge}
        for condition in (node for node in ast.walk(function) if isinstance(node, ast.If)):
            aliases = {target.id for assignment in ast.walk(function)
                       if isinstance(assignment, ast.Assign) and assignment.lineno < condition.lineno
                       and trims_text(assignment.value, parameter)
                       for target in assignment.targets if isinstance(target, ast.Name)}
            empty_check = any(isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not)
                              and (trims_text(node.operand, parameter)
                                   or isinstance(node.operand, ast.Name) and node.operand.id in aliases)
                              for node in ast.walk(condition.test))
            refuses = any(isinstance(node, ast.Raise) and isinstance(node.exc, ast.Call)
                          and isinstance(node.exc.func, ast.Name) and node.exc.func.id == "herr"
                          and node.exc.args and isinstance(node.exc.args[0], ast.Constant)
                          and node.exc.args[0].value == 400 and any(
                              isinstance(arg, ast.Constant) and isinstance(arg.value, str)
                              and "пуст" in arg.value.lower() for arg in node.exc.args[1:])
                          for statement in condition.body for node in ast.walk(statement))
            if empty_check and refuses:
                return True
        for call in (node for node in ast.walk(function) if isinstance(node, ast.Call)):
            helper = functions.get(call.func.id) if isinstance(call.func, ast.Name) else None
            if helper is None:
                continue
            parameters = [arg.arg for arg in helper.args.posonlyargs + helper.args.args]
            bindings = list(zip(parameters, call.args)) + [(arg.arg, arg.value) for arg in call.keywords]
            for name, value in bindings:
                if isinstance(value, ast.Name) and value.id == parameter and guarded(helper, name, visited):
                    return True
        return False

    return doors, [node for node in doors if not guarded(node, "body", set())]


def test_пустое_не_уходит_ни_в_одном_из_чатов():
    """All POST message doors, including editing, must reject trimmed empty text.

    A shared helper counts only when this handler really calls it with its request body.
    There is no filename/function exception list and no arbitrary line-window limit.
    """
    doors, unguarded = _chat_doors_without_empty_guard(CHAT.read_text(encoding="utf-8"))
    assert len(doors) >= 3, f"чатов стало {len(doors)} — проверь, не появилась ли новая дверь"
    assert not unguarded, (
        "здесь можно отправить пустое сообщение: "
        + "; ".join(f"chat.py:{node.lineno} → {node.name}" for node in unguarded)
        + ". Собеседнику зазвонит телефон «Новое сообщение», он откроет чат — пустой пузырь."
    )


def test_поездка_из_города_в_тот_же_город_не_публикуется(client, user_factory):
    driver = user_factory("ОдинГородВодитель", role=UserRole.driver)

    r = _publish(client, driver, "Сибай", "Сибай")

    assert r.status_code == 400, "опубликована поездка «из Сибая в Сибай»"
    assert "разные" in str(r.json()).lower(), r.json()


def test_название_из_смайликов_не_проходит(client, user_factory):
    driver = user_factory("СмайликВодитель", role=UserRole.driver)

    r = _publish(client, driver, "🚗🚗")

    assert r.status_code == 400, "поездка из «🚗🚗» попала в общую ленту района"


def test_башкирские_названия_проходят(client, user_factory):
    """Главная проверка обратной стороны: «Баймаҡ» — это буквы, а не мусор."""
    driver = user_factory("БашкирскийНазваниеВодитель", role=UserRole.driver)

    r = _publish(client, driver, "Баймаҡ", "Стәрлетамаҡ", day="2030-12-06")

    assert r.status_code == 200, f"башкирское написание города не приняли: {r.text}"


def test_деревня_с_районом_в_скобках_проходит(client, user_factory):
    """Приложение подставляет «Берёзовка (Иглинский р-н)» — такая запись должна работать."""
    driver = user_factory("ДеревняВодитель", role=UserRole.driver)

    r = _publish(client, driver, "Берёзовка (Иглинский р-н)", "Уфа", day="2030-12-07")

    assert r.status_code == 200, f"деревню с районом не приняли: {r.text}"
