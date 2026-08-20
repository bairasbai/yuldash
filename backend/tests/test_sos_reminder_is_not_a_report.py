"""Повтор по непринятому SOS отчитывался за сообщения, которых никто не получил.

Красная кнопка поднимает дежурного: сообщение в Telegram, ночью — SMS. Если за десять минут
сигнал не приняли, воркер шлёт напоминание и ставит метку «напомнили». В коде на этом месте
было честное обещание: «упали на отправке — повторим в следующий прогон, а не отчитаемся
о сигнале, которого никто не получил».

Проверять это было нечем (аудит 2026-08-08, волна 140). Обе функции отправки — и Telegram,
и SMS — глотали ошибку внутри себя и возвращали пустоту. Ловушка вокруг них ловила то, чего
не бывает. Значит любой исход выглядел одинаково: Telegram лежит, SMS выключены — сигнал всё
равно помечался «напомнили», и повтора больше не было НИКОГДА.

Человеческая цена. Ночь, трасса, человек нажал SOS. Дежурный спит. Через десять минут система
считает, что разбудила его, — и замолкает навсегда. Утром в списке запись «открыт», и по логам
не отличить «напомнили, но не подошёл» от «напоминание не ушло никуда».

Вторая половина той же истории: напоминание было РОВНО ОДНО. Не приняли за десять минут —
один повтор, дальше тишина. Человек в это время всё ещё стоит на трассе.

**Что теперь.** Каналы отвечают честно: «доставлено» или «нет». Без доставки метка не ставится
и следующий прогон пробует снова. Напоминание повторяется, пока сигнал открыт и ему меньше часа.

**Где сознательно остановились.** Через час напоминания прекращаются: за час дежурный либо
принял, либо не примет и от сотого сообщения, а случайно нажатая кнопка иначе превращается
в бесконечную рассылку — и на неё перестают смотреть вообще. Сигнал при этом остаётся
в админке. И если каналы не настроены вовсе — это не сбой доставки, а настройка: метку ставим,
но пишем в лог ошибку, потому что чинить это надо руками.
"""
from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest
from sqlmodel import Session

from app import sos_escalate
from app.config import settings
from app.db import engine
from app.models import SosEvent
from app.timeutil import utcnow


@pytest.fixture
def канал_настроен(monkeypatch):
    """Дежурному есть куда писать: бот заведён, chat_id известен."""
    monkeypatch.setattr(settings, "telegram_bot_token", "тест-токен")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "12345")
    monkeypatch.setattr(settings, "sos_sms_to_admin", False)


def _сигнал(user_id: int, минут_назад: int) -> int:
    """Человек нажал красную кнопку N минут назад, и её до сих пор никто не принял."""
    with Session(engine) as s:
        e = SosEvent(user_id=user_id, status="open", category="danger",
                     note="стою на трассе", created_at=utcnow() - timedelta(minutes=минут_назад))
        s.add(e)
        s.commit()
        s.refresh(e)
        return e.id


def _прогон() -> list[int]:
    with Session(engine) as s:
        return sos_escalate.escalate_unhandled(s)


def _напомнили(eid: int) -> bool:
    with Session(engine) as s:
        return s.get(SosEvent, eid).escalated_at is not None


def _прошло_минут(eid: int, с_напоминания: int, с_нажатия: int) -> None:
    with Session(engine) as s:
        e = s.get(SosEvent, eid)
        e.escalated_at = utcnow() - timedelta(minutes=с_напоминания)
        e.created_at = utcnow() - timedelta(minutes=с_нажатия)
        s.add(e)
        s.commit()


def test_недоставленное_напоминание_не_считается_отправленным(
        client, user_factory, monkeypatch, канал_настроен):
    """Главное: система не должна считать, что разбудила дежурного, если не разбудила."""
    человек = user_factory("СосЧеловек")
    monkeypatch.setattr(sos_escalate, "notify_admin_telegram", lambda text: False)
    eid = _сигнал(человек["id"], 30)

    ушло = _прогон()

    assert ушло == [], f"функция отчиталась об отправке {ушло}, хотя не отправила ничего"
    assert not _напомнили(eid), (
        "сигнал помечен «напомнили», хотя сообщение не дошло ни одним каналом: повтора больше "
        "не будет никогда, а человек всё ещё ждёт помощи"
    )


def test_когда_связь_вернулась_напоминание_уходит(
        client, user_factory, monkeypatch, канал_настроен):
    """Обратная сторона: попытка должна повториться, а не потеряться."""
    человек = user_factory("СосЧеловек2")
    monkeypatch.setattr(sos_escalate, "notify_admin_telegram", lambda text: False)
    eid = _сигнал(человек["id"], 30)
    _прогон()

    monkeypatch.setattr(sos_escalate, "notify_admin_telegram", lambda text: True)
    ушло = _прогон()

    assert eid in ушло, "связь вернулась, а напоминание так и не ушло"
    assert _напомнили(eid), "напоминание доставлено, но метка не поставлена — уйдёт второе подряд"


def test_смс_спасает_когда_телеграм_лежит(client, user_factory, monkeypatch):
    """Второй канал существует ровно для этого: один упал — доходит другой."""
    человек = user_factory("СосЧеловек3")
    monkeypatch.setattr(settings, "telegram_bot_token", "тест-токен")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "12345")
    monkeypatch.setattr(settings, "sos_sms_to_admin", True)
    monkeypatch.setattr(settings, "admin_phones", "+79990001400")
    monkeypatch.setattr(sos_escalate, "notify_admin_telegram", lambda text: False)
    monkeypatch.setattr(sos_escalate, "send_text", lambda phone, text: True)
    eid = _сигнал(человек["id"], 30)

    ушло = _прогон()

    assert eid in ушло, "Telegram лежал, SMS ушла — а система решила, что не напомнила"
    assert _напомнили(eid), "доставлено по SMS, но метка не поставлена"


def test_напоминание_повторяется_пока_человек_ждёт(
        client, user_factory, monkeypatch, канал_настроен):
    """Раньше напоминание было ровно одно: не услышали — тишина навсегда."""
    человек = user_factory("СосЧеловек4")
    monkeypatch.setattr(sos_escalate, "notify_admin_telegram", lambda text: True)
    eid = _сигнал(человек["id"], 30)
    assert _прогон() == [eid]

    _прошло_минут(eid, с_напоминания=15, с_нажатия=45)
    второе = _прогон()

    assert eid in второе, (
        "сигнал так и не приняли, а система напомнила один раз и замолчала — человек ночью "
        "на трассе, и больше о нём никто не вспомнит"
    )


def test_через_час_напоминания_прекращаются(client, user_factory, monkeypatch, канал_настроен):
    """Обратная сторона: случайное нажатие не должно пилить телефон бесконечно.

    Если на сигнал не ответили за час, сотое сообщение не поможет — а вот привычка отмахиваться
    от уведомлений Юлдаша появится, и следующий настоящий SOS утонет в этом шуме.
    """
    человек = user_factory("СосЧеловек5")
    monkeypatch.setattr(sos_escalate, "notify_admin_telegram", lambda text: True)
    eid = _сигнал(человек["id"], 180)                 # нажали три часа назад
    _прогон()

    _прошло_минут(eid, с_напоминания=30, с_нажатия=210)
    ещё = _прогон()

    assert eid not in ещё, (
        "напоминания по трёхчасовому сигналу продолжаются: дежурный привыкнет отмахиваться, "
        "и настоящий сигнал утонет в шуме"
    )


def test_старый_сигнал_всё_равно_получает_первое_напоминание(
        client, user_factory, monkeypatch, канал_настроен):
    """Ограничение окна не должно съесть само напоминание.

    Воркер мог не работать сутки: сигналу три часа, а напоминания не было ни одного.
    Первое обязано уйти в любом случае.
    """
    человек = user_factory("СосЧеловек6")
    monkeypatch.setattr(sos_escalate, "notify_admin_telegram", lambda text: True)
    eid = _сигнал(человек["id"], 300)

    assert eid in _прогон(), (
        "по старому сигналу не ушло ни одного напоминания — окно повторов съело первое"
    )


def test_принятый_сигнал_не_тревожат(client, user_factory, monkeypatch, канал_настроен):
    """Дежурный откликнулся — напоминать больше не о чем."""
    человек = user_factory("СосЧеловек7")
    monkeypatch.setattr(sos_escalate, "notify_admin_telegram", lambda text: True)
    eid = _сигнал(человек["id"], 30)
    with Session(engine) as s:
        e = s.get(SosEvent, eid)
        e.status = "handled"
        s.add(e)
        s.commit()

    assert eid not in _прогон(), "напоминание ушло по сигналу, который уже приняли"


def test_ненастроенные_каналы_не_крутят_пустой_цикл(client, user_factory, monkeypatch):
    """Тут не сбой, а настройка: чинить надо руками, поэтому громко пишем в лог.

    Повторять бессмысленно — доставить некому, и бесконечный цикл только забьёт логи, скрыв
    ту самую ошибку, ради которой всё и затевалось.
    """
    человек = user_factory("СосЧеловек8")
    monkeypatch.setattr(settings, "telegram_bot_token", "")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    monkeypatch.setattr(settings, "sos_sms_to_admin", False)
    eid = _сигнал(человек["id"], 30)

    _прогон()

    assert _напомнили(eid), (
        "каналы не настроены вовсе, а сигнал остался непомеченным: воркер будет вхолостую "
        "перебирать его каждый прогон и забьёт логи"
    )


def test_канал_доставки_говорит_правду_о_себе(monkeypatch):
    """Проверяем сами каналы, а не подпись функции.

    Все истории выше подменяют отправку заглушкой — значит настоящую функцию они не трогают.
    Если она снова начнёт возвращать пустоту, эскалация примет это за «не доставлено» или
    «доставлено» вслепую, а тесты останутся зелёными. Поэтому спрашиваем у неё напрямую.
    """
    from app.services import notify_admin_telegram, send_text

    monkeypatch.setattr(settings, "telegram_bot_token", "")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    ответ = notify_admin_telegram("проверка")
    assert ответ is False, (
        f"канал до дежурного не настроен, а функция отвечает {ответ!r} вместо честного False: "
        "по такому ответу нельзя отличить доставленное от потерянного"
    )

    monkeypatch.setattr(settings, "sms_provider", "mock")
    смс = send_text("+79990001401", "проверка")
    assert смс is False, (
        f"SMS только записана в лог, а функция отвечает {смс!r}: запись в лог — не сообщение, "
        "полученное человеком"
    )


def test_упавший_телеграм_честно_признаётся(monkeypatch):
    """Настроен и не ответил — это «не доставлено», а не тишина."""
    import httpx

    from app.services import notify_admin_telegram

    monkeypatch.setattr(settings, "telegram_bot_token", "тест-токен")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "12345")

    def упал(*a, **k):
        raise httpx.ConnectError("сеть недоступна")

    monkeypatch.setattr(httpx, "post", упал)

    assert notify_admin_telegram("проверка") is False, (
        "Telegram недоступен, а функция об этом молчит — напоминание по SOS снова будет "
        "помечено как доставленное"
    )


def test_успешная_отправка_признаётся_успешной(monkeypatch):
    """Обратная сторона честности: доставленное должно считаться доставленным.

    Если канал сработал, а функция об этом промолчала, эскалация решит «не дошло» и будет
    слать напоминание каждый прогон — дежурного завалит одинаковыми сообщениями по одному
    и тому же сигналу, и он перестанет их читать.
    """
    import httpx

    from app.services import notify_admin_telegram

    monkeypatch.setattr(settings, "telegram_bot_token", "тест-токен")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "12345")

    class Ответ:
        status_code = 200

    monkeypatch.setattr(httpx, "post", lambda *a, **k: Ответ())

    assert notify_admin_telegram("проверка") is True, (
        "Telegram принял сообщение, а функция об этом не сказала: напоминание уйдёт снова "
        "и снова, пока дежурный не перестанет обращать на них внимание"
    )


def test_каналы_отвечают_честно_а_не_пустотой():
    """Сторож на корень: пока отправка молчит о результате, любая проверка доставки — фикция.

    Именно из-за этого дыра прожила долго: код выглядел защищённым (ловушка вокруг отправки),
    а поймать было нечего — исключение гасилось внутри самой функции.
    """
    src = (Path(__file__).resolve().parents[1] / "app" / "services.py").read_text(encoding="utf-8")

    for функция in ("def notify_admin_telegram", "def send_text"):
        подпись = src[src.index(функция): src.index(функция) + 200].splitlines()[0]
        assert "-> bool" in подпись, (
            f"{функция} снова ничего не возвращает: вызывающий не отличит «доставлено» от "
            f"«не ушло никуда», и проверка доставки станет фикцией — {подпись}"
        )
