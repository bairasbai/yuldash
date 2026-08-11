"""Сторож «уведомление есть — открыть нечего».

Откуда взялся. Аудит 2026-08-06 нашёл две дыры одного рода. Первая: пуш о новом сообщении
нёс с собой тип и номер, но приложение их не читало — человек жал по «Марат: подъезжаю»
и попадал на карту. Вторая: в Центре уведомлений открывались только бронь, заявка
и обращение, а доставка и такси — самая большая группа событий — молчали, хотя карточка
пружинила под пальцем и обещала переход.

Ни один существующий тест этого увидеть не мог: серверные ходили по маршрутам сервера,
а приложение собирается отдельно и про них не знает.

Что проверяет. Каждый вид уведомления, который сервер РОЖДАЕТ, приложение умеет ОТКРЫТЬ:
`ref_kind` — веткой в Центре уведомлений, тип пуша — переходом по тапу.

Android-исходников рядом нет (бэкенд выкачен отдельно) → тест пропускается, а не падает —
как и в контрактном тесте адресов.
"""
from __future__ import annotations

import pathlib
import re

import pytest

_REPO = pathlib.Path(__file__).resolve().parents[2]
_APP = _REPO / "android" / "app" / "src" / "main" / "java" / "com" / "yuldash" / "app"
_BACKEND = pathlib.Path(__file__).resolve().parents[1] / "app"

# Виды, которые сознательно никуда не ведут — каждый с причиной. Список только уменьшается.
_KIND_WITHOUT_SCREEN: dict[str, str] = {}

# Типы пушей, по которым переход не нужен — с причиной.
_PUSH_WITHOUT_ROUTE: dict[str, str] = {
    "instant_offer": "не уведомление, а полноэкранная карточка заказа со своими кнопками",
}


def _skip_without_app():
    if not _APP.is_dir():
        pytest.skip("Android-исходников рядом нет — сторожить нечего")


def _py_sources() -> str:
    return "\n".join(p.read_text(encoding="utf-8") for p in sorted(_BACKEND.rglob("*.py")))


def _server_ref_kinds() -> set[str]:
    kinds = set(re.findall(r'ref_kind\s*=\s*"([a-z_]+)"', _py_sources())) - {""}
    # Ночная чистка передаёт вид ПЕРЕМЕННОЙ (`ref_kind=link` по таблице `_NOTIFY_LINK`), поэтому
    # литерала в коде нет и регулярка выше слепа. Ровно так мимо сторожа прошёл такси-заказ
    # с видом "order", которого приложение не знает (аудит 2026-08-08, волна 19).
    from app.cleanup import _NOTIFY_LINK
    return kinds | {v for v in _NOTIFY_LINK.values() if v}


def _server_push_types() -> set[str]:
    """Типы ИМЕННО пушей. Рядом живут кадры веб-сокета с таким же ключом `type`
    ("message", "loc", "refresh") — их отсеиваем по окружению: пуш всегда рождается
    возле send_push / push_notification / data=."""
    out: set[str] = set()
    for path in sorted(_BACKEND.rglob("*.py")):
        lines = path.read_text(encoding="utf-8").split("\n")
        for i, line in enumerate(lines):
            m = re.search(r'"type"\s*:\s*"([a-z_]+)"', line)
            if not m:
                continue
            window = "\n".join(lines[max(0, i - 12):i + 1])
            if re.search(r"send_push\(|push_notification\(|data\s*=\s*\{", window):
                out.add(m.group(1))
    return out


def _notification_branches() -> set[str]:
    """Ветки `when (n.refKind)` из Центра уведомлений."""
    src = (_APP / "SecondaryScreens.kt").read_text(encoding="utf-8")
    block = re.search(r"when\s*\(\s*n\.refKind\s*\)\s*\{(.+?)\n\s*\}", src, re.S)
    assert block, "не нашёл разбор refKind — сторож ослеп, почини разбор"
    return set(re.findall(r'"([a-z_]+)"\s*->', block.group(1)))


def _push_routes() -> tuple[set[str], str]:
    """Типы, по которым приложение делает ПЕРЕХОД.

    Читаем только `MainActivity.kt` — тап превращается в экран именно там. `FcmService.kt`
    сюда не берём нарочно: он раскладывает уведомления по каналам, и тип, упомянутый в списке
    «это чат», ещё не значит, что по нему куда-то попадёшь. На первой версии этого сторожа
    так и вышло: убрал переход — тест остался зелёным, потому что слово нашлось в списке каналов.
    """
    main = (_APP / "MainActivity.kt").read_text(encoding="utf-8")
    return set(re.findall(r'"([a-z_]+)"', main)), main


def test_every_notification_kind_can_be_opened():
    _skip_without_app()
    kinds = _server_ref_kinds()
    assert len(kinds) >= 5, f"видов уведомлений подозрительно мало ({kinds}) — разбор сломался"

    handled = _notification_branches()
    dead = sorted(kinds - handled - set(_KIND_WITHOUT_SCREEN))
    assert not dead, (
        "Эти уведомления сервер шлёт, а по тапу ничего не открывается:\n"
        + "\n".join(f"  • {k}" for k in dead)
        + "\nЛибо добавь ветку в NotificationsScreen, либо впиши сюда с причиной."
    )


def test_notification_branch_list_is_not_stale():
    """Ветка на вид, которого сервер больше не шлёт, — мёртвый код и ложное спокойствие."""
    _skip_without_app()
    kinds = _server_ref_kinds()
    extra = sorted(_notification_branches() - kinds)
    assert not extra, f"эти ветки открывают то, чего сервер не присылает: {extra}"


def test_every_push_leads_somewhere():
    _skip_without_app()
    types = _server_push_types()
    assert "chat" in types and "parcel_status" in types, f"разбор типов пушей сломался: {types}"

    routed, both = _push_routes()
    dead = []
    for t in sorted(types - set(_PUSH_WITHOUT_ROUTE)):
        # Ход посылки ловится общим правилом «тип начинается на parcel» — отдельной строки нет.
        if t.startswith("parcel") and 'startsWith("parcel")' in both:
            continue
        if t not in routed:
            dead.append(t)
    assert not dead, (
        "По этим пушам тап никуда не ведёт — человек читает новость и остаётся на месте:\n"
        + "\n".join(f"  • {t}" for t in dead)
        + "\nЛибо добавь переход, либо впиши в _PUSH_WITHOUT_ROUTE с причиной."
    )


def test_chat_pushes_do_not_share_one_type():
    """Три чата — три типа. Один тип на всех означал бы открытие чужой поездки по чужому номеру."""
    _skip_without_app()
    types = _server_push_types()
    chats = {t for t in types if t.endswith("chat")}
    assert chats == {"chat", "order_chat", "parcel_chat"}, f"типы чатов разъехались: {chats}"
