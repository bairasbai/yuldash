"""Уведомление, которое знает адресата, обязано нести адрес.

Что было. Сервер кладёт в ленту уведомлений вид и номер (`ref_kind` + `ref_id`) — по ним
человек находит, о чём речь. А в сам ПУШ этот адрес клали руками, и клали не везде: из
37 уведомлений с известным адресатом его несли 18, а 34 уходили пустыми.

Что это значит у человека. Приходит «Поддержка Юлдаш ответила» — он жмёт и попадает просто
в приложение. Дальше сам: вспомнить, где поддержка, открыть, найти своё обращение. Человек,
который писал ЗА ПОМОЩЬЮ, делает лишнюю работу ровно тогда, когда помощь пришла. То же с
«Заявку приняли» (попутка), с решением по спору и с напоминанием про долг.

Для посылок и такси это уже чинили отдельными заходами — в `FcmService.kt` про это два
комментария. Для попуток, поддержки, споров и долга не чинили: классический «поправили там,
где вспомнили».

Чинить 34 места по одному незачем: сервер УЖЕ знает вид и номер. Адрес собирается один раз
в `push_notification`. Явный `data` (чаты — у них свой тип канала) по-прежнему главнее.

Тест держит правило: если уведомление знает `ref_kind` и `ref_id`, в пуш обязан уйти адрес.
"""
from __future__ import annotations

import pathlib
import re

from sqlmodel import Session

import app.services as services
from app.db import engine


def test_адрес_подставляется_когда_сервер_его_знает(monkeypatch, user_factory):
    sent: list = []
    _s = Session(engine)
    monkeypatch.setattr(services, "send_push", lambda *a, **kw: sent.append((a, kw)))
    u = user_factory("Получатель")
    services.push_notification(
        _s, u["id"], "system", "Заголовок", "Баш", "Текст", "Текст",
        ref_kind="support", ref_id=42,
    )
    assert sent, "пуш вообще не ушёл"
    data = sent[-1][1].get("data")
    assert isinstance(data, dict), "пуш ушёл без payload — тап откроет просто приложение"
    assert data.get("type") == "support" and data.get("id") == "42", data


def test_свой_адрес_не_перебиваем(monkeypatch, user_factory):
    """У чатов свой тип (канал «Сообщения» и другой экран) — он должен побеждать."""
    sent: list = []
    _s = Session(engine)
    monkeypatch.setattr(services, "send_push", lambda *a, **kw: sent.append((a, kw)))
    u = user_factory("Получатель")
    services.push_notification(
        _s, u["id"], "message", "Марат", "Марат", "Подъезжаю", "Килеп етәм",
        ref_kind="booking", ref_id=7, data={"type": "chat", "id": 7},
    )
    assert sent[-1][1]["data"]["type"] == "chat", "явный адрес чата затёрли автоматическим"


def test_без_адресата_ничего_не_выдумываем(monkeypatch, user_factory):
    """Нет `ref_id` — значит открывать нечего, и подставлять «куда-нибудь» нельзя."""
    sent: list = []
    _s = Session(engine)
    monkeypatch.setattr(services, "send_push", lambda *a, **kw: sent.append((a, kw)))
    u = user_factory("Получатель")
    services.push_notification(
        _s, u["id"], "system", "Просто новость", "Хәбәр", "Текст", "Текст",
    )
    assert not sent[-1][1], "в пуш без адресата подставили выдуманный payload"


def test_приложение_умеет_открыть_то_что_шлёт_сервер():
    """Сторож на ОБЕ стороны: сервер начал слать новые виды — приложение должно их разбирать.

    Иначе получится хуже прежнего: адрес в пуше есть, а приложение его игнорирует, и тап
    по-прежнему открывает просто приложение — только теперь это незаметно.
    """
    root = pathlib.Path(__file__).resolve().parents[2]
    router = (root / "android/app/src/main/java/com/yuldash/app/MainActivity.kt").read_text(encoding="utf-8")
    handled = set(re.findall(r'"([a-z_]+)"\s*->', router))
    # Виды, ради которых правило и заводилось: человек ждёт ответа и должен попасть точно.
    must = {"support", "incident", "booking"}
    missing = must - handled
    assert not missing, (
        "сервер шлёт адрес, а приложение не знает, куда открыть: %s. "
        "Добавь ветку в openChatFromPush (MainActivity.kt)." % ", ".join(sorted(missing))
    )
