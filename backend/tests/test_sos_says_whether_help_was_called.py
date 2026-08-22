"""Седьмой SOS за час никого не звал, и человек об этом не знал (волна 172).

Рассылка близким глушится после шести сигналов за час — защита правильная и нужная: заевшая
в кармане кнопка иначе даёт поток платных SMS, а настоящий сигнал тонет среди сорока
одинаковых. Диспетчер при этом узнаёт всегда: Telegram намеренно не капится.

Но ответ приложению был просто записью о событии — без единого слова о том, ушло ли SMS.
Значит седьмое нажатие выглядело ровно как первое: «сигнал отправлен». Женщина в беде видит
успех и ждёт маму, которая ничего не получила.

Молчание тут опаснее самого потолка. Знай человек правду — он позвонит родным сам; не зная,
он выбирает ждать. Поэтому ответ теперь честный: сигнал принят и дежурный его видит, а SMS
родным сейчас не уходит — позвони сама.

Тот же класс, что и в волне 171: защита от злоупотребления бьёт по честному случаю. Только
там она молча теряла рабочее время, а здесь молча теряет помощь.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import TrustedContact
from app.routers.safety import SOS_SMS_PER_HOUR


@pytest.fixture(autouse=True)
def _оператор_подключён(monkeypatch):
    """Мир этого файла: канал SMS РАБОТАЕТ, сообщения близким уходят по-настоящему.

    Раньше это подразумевалось молча — и потому не проверялось: на проде канал выключен,
    а тесты всё равно видели «уведомлено: 2», потому что сервер считал намерение, а не факт
    (волна 184). Что честный счёт бывает нулём при молчащем канале — проверяет
    `test_help_counted_is_help_sent.py`.
    """
    from app.config import settings as _s
    monkeypatch.setattr(_s, "sms_provider", "smsru")
    monkeypatch.setattr(_s, "sms_ru_api_id", "test-id")


@pytest.fixture
def тихие_смс(monkeypatch):
    """Собираем рассылки вместо отправки: проверяем поведение, а не доставку."""
    ушло = []
    monkeypatch.setattr("app.routers.safety._send_sos_sms",
                        lambda phones, *a, **kw: ушло.append(list(phones)))
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **kw: None)
    return ушло


def _человек_с_мамой(user_factory, метка: str):
    человек = user_factory(метка)
    with Session(engine) as s:
        s.add(TrustedContact(user_id=человек["id"], name="Мама", phone="+79170001111"))
        s.commit()
    return человек


def _sos(client, человек, номер: int = 0):
    return client.post("/sos", headers=человек["auth"],
                       json={"category": "other", "note": f"сигнал {номер}"})


def test_заглушённый_сигнал_честно_об_этом_говорит(client, user_factory, тихие_смс):
    """Главное: человек в беде не должен ждать помощь, которая не выехала."""
    человек = _человек_с_мамой(user_factory, "ЖенщинаВБеде")
    for i in range(SOS_SMS_PER_HOUR):
        _sos(client, человек, i)

    ответ = _sos(client, человек, SOS_SMS_PER_HOUR)

    тело = ответ.json()
    assert тело["sms_suppressed"] is True, (
        "рассылка близким заглушена, а в ответе об этом ни слова: человек видит «сигнал "
        "отправлен» и ждёт маму, которая ничего не получила"
    )
    assert тело["contacts_notified"] == 0
    assert тело["hint_ru"] and тело["hint_ba"], "подсказка не на двух языках"
    assert "позвони" in тело["hint_ru"].lower(), (
        f"человеку не сказали, что делать дальше: {тело['hint_ru']}"
    )


def test_обычный_сигнал_говорит_что_родных_позвали(client, user_factory, тихие_смс):
    """Обратная сторона: когда помощь вызвана, человек тоже должен это знать."""
    человек = _человек_с_мамой(user_factory, "ЖенщинаПерваяКнопка")

    тело = _sos(client, человек).json()

    assert тело["sms_suppressed"] is False
    assert тело["contacts_notified"] == 1, (
        f"маме отправили SMS, а в ответе {тело['contacts_notified']} — человек не увидит, "
        "что помощь позвали"
    )
    assert not тело["hint_ru"], "лишняя тревожная подсказка там, где всё сработало"


def test_событие_записано_даже_когда_смс_не_ушло(client, user_factory, тихие_смс):
    """Жизнь дороже: сигнал фиксируется всегда, даже если рассылка заглушена."""
    человек = _человек_с_мамой(user_factory, "ЖенщинаСемьРаз")
    for i in range(SOS_SMS_PER_HOUR):
        _sos(client, человек, i)

    ответ = _sos(client, человек, SOS_SMS_PER_HOUR)

    assert ответ.status_code == 200
    assert ответ.json()["id"], "седьмой сигнал вообще не записан — дежурному нечего смотреть"


def test_у_кого_нет_доверенных_подсказка_не_врёт(client, user_factory, тихие_смс):
    """Без доверенных контактов звать некого — но это не «нас заглушили»."""
    человек = user_factory("ЧеловекБезРодных")

    тело = _sos(client, человек).json()

    assert тело["contacts_notified"] == 0
    assert тело["sms_suppressed"] is False, (
        "человеку сказали, что рассылку заглушили, хотя у него просто нет доверенных контактов: "
        "он будет ждать звонка от несуществующей мамы"
    )


def test_первые_шесть_сигналов_доходят(client, user_factory, тихие_смс):
    """Контроль потолка: он начинается там, где заканчивается разумное, а не сразу."""
    человек = _человек_с_мамой(user_factory, "ЖенщинаШестьРаз")

    ответы = [_sos(client, человек, i).json() for i in range(SOS_SMS_PER_HOUR)]

    assert all(о["contacts_notified"] == 1 for о in ответы), (
        f"часть из первых {SOS_SMS_PER_HOUR} сигналов не дошла до родных: "
        f"{[о['contacts_notified'] for о in ответы]}"
    )
