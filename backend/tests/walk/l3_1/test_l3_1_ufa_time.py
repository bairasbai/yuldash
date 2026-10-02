"""Время в сигнале SOS — местное, по Уфе (UTC+5), а не сырой UTC сервера.

Фокус листа прямо требует проверить часовой пояс Уфы. `/sos` печатает дежурному и
доверенным контактам время по шаблону `%H:%M`, посчитанное как
`utcnow() + timedelta(hours=settings.local_tz_offset_hours)` (routers/safety.py).
Если бы сервер отдавал сырой UTC, дежурный ночью видел бы сигнал промаркированным
"на 5 часов в будущем" относительно своих же часов — путаница именно тогда, когда
нужна ясность.

До этой проверки правило нигде не было закреплено тестом: ни разу не сравнивалось
фактическое время в тексте с ожидаемым местным временем при известном "сейчас".
"""
from datetime import datetime, timedelta, timezone

import app.routers.safety as safety
from app.config import settings


def _capture_tg(monkeypatch):
    msgs = []
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda m: msgs.append(m))
    return msgs


def test_sos_admin_message_states_ufa_time_not_utc(client, user_factory, monkeypatch):
    assert settings.local_tz_offset_hours == 5, "контрольное допущение: Уфа = UTC+5"

    fixed_utc = datetime(2026, 1, 15, 20, 30, tzinfo=timezone.utc)   # 20:30 UTC = 01:30 по Уфе
    monkeypatch.setattr(safety, "utcnow", lambda: fixed_utc)
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: None)
    msgs = _capture_tg(monkeypatch)

    u = user_factory("UfaTimeUser")
    r = client.post("/sos", headers=u["auth"], json={"category": "medical"})
    assert r.status_code == 200, r.text

    assert msgs, "админу ничего не ушло"
    ожидаемое_местное = (fixed_utc + timedelta(hours=settings.local_tz_offset_hours)).strftime("%H:%M")
    assert ожидаемое_местное == "01:30", "контроль самого теста: арифметика часового пояса"
    assert ожидаемое_местное in msgs[0], (
        f"сигнал называет не местное (Уфа) время: ожидали {ожидаемое_местное!r} в {msgs[0]!r}"
    )
    # Сырой UTC не должен быть тем, что видит дежурный как время сигнала.
    сырой_utc = fixed_utc.strftime("%H:%M")
    assert сырой_utc not in msgs[0].split("\n")[0], (
        f"в заголовке сигнала сырой UTC вместо местного: {msgs[0]!r}"
    )


def test_sos_family_sms_also_states_ufa_time_not_utc(client, user_factory, monkeypatch):
    """То же правило, но для SMS БЛИЗКИМ (своё, отдельное выражение в коде — routers/safety.py,
    переменная `местное`) — ревью отдельно отметило, что это место нигде не проверялось."""
    assert settings.local_tz_offset_hours == 5

    fixed_utc = datetime(2026, 6, 10, 2, 15, tzinfo=timezone.utc)   # 02:15 UTC = 07:15 по Уфе
    monkeypatch.setattr(safety, "utcnow", lambda: fixed_utc)
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda *a, **k: True)
    sent = []
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: sent.append((list(phones), text)))

    u = user_factory("UfaTimeFamily")
    client.post("/trusted-contacts", headers=u["auth"],
                json={"name": "Сестра", "relation": "сестра", "phone": "+79170003344"})
    r = client.post("/sos", headers=u["auth"], json={"category": "medical"})
    assert r.status_code == 200, r.text

    to_family = [t for phones, t in sent if "+79170003344" in phones]
    assert to_family, "близким SMS не ушло"
    ожидаемое_местное = (fixed_utc + timedelta(hours=settings.local_tz_offset_hours)).strftime("%H:%M")
    assert ожидаемое_местное == "07:15"
    assert ожидаемое_местное in to_family[0], (
        f"SMS близким называет не местное (Уфа) время: ожидали {ожидаемое_местное!r} в {to_family[0]!r}"
    )
    сырой_utc = fixed_utc.strftime("%H:%M")
    assert сырой_utc not in to_family[0].split("\n")[0], (
        f"в заголовке SMS близким сырой UTC вместо местного: {to_family[0]!r}"
    )
