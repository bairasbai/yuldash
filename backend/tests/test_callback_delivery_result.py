"""DESIGN040 / B08: callback success must mean an accepted support notification.

Real authenticated HTTP/validation/DB, with only the outbound Telegram boundary
controlled in focused cases. False must become a temporary failure, not a promise
to call that exists nowhere. Real missing-configuration calibration does not replace
the notifier. No persistent ticket, new role policy or idempotency scheme is assumed.
Backend retries below do not prove Android/web draft retention or real TG delivery.
"""
import json

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Notification, Report, SosEvent, SupportMessage, SupportTicket, User
from app.routers import safety
from app import services


@pytest.fixture(autouse=True)
def isolated_callback_configuration(monkeypatch):
    assert settings.env == "dev" and settings.sms_provider == "mock"
    assert not any((settings.redis_url, settings.firebase_credentials, settings.telegram_bot_token,
                    settings.yandex_geocoder_key, settings.vapid_private_key,
                    settings.yookassa_shop_id, settings.yookassa_secret_key))
    # Configuration only; actual missing-config notifier cannot make a network call.
    monkeypatch.setattr(settings, "telegram_bot_token", "")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    monkeypatch.setattr(settings, "admin_phones", "")


@pytest.fixture
def outbound(monkeypatch):
    state = {"accepted": True, "attempts": []}

    def notify(message, reply_markup=None):
        accepted = state["accepted"]
        assert isinstance(accepted, bool)
        state["attempts"].append({"message": message, "reply_markup": reply_markup, "accepted": accepted})
        return accepted

    # Replace only the named outbound boundary used by the actual route (not its
    # handler/current_user/schema/HTTP adapter or DB). No actual TG credentials.
    monkeypatch.setattr(safety, "notify_admin_telegram", notify)
    return state


def _profile_and_support_rows(user_id):
    with Session(engine) as session:
        user = session.get(User, user_id)
        ticket_ids = list(session.exec(select(SupportTicket.id).where(SupportTicket.user_id == user_id)).all())
        return {"profile": {"name": user.name, "phone": user.phone},
                "sos_ids": list(session.exec(select(SosEvent.id).where(SosEvent.user_id == user_id)).all()),
                "report_ids": list(session.exec(select(Report.id).where(Report.reporter_id == user_id)).all()),
                "notification_ids": list(session.exec(select(Notification.id).where(Notification.user_id == user_id)).all()),
                "ticket_ids": ticket_ids,
                "support_message_ids": list(session.exec(select(SupportMessage.id)
                                            .where(SupportMessage.ticket_id.in_(ticket_ids))).all()) if ticket_ids else []}


def _receipt(responses, before, after, attempts=(), **extra):
    observed = {"responses": [{"http": response.status_code, "body": response.json()} for response in responses],
                "before": before, "after": after, "outbound_attempts": attempts, **extra}
    # Synthetic profiles/notes only; auth tokens are never placed in evidence.
    print("QA_CALLBACK_DELIVERY " + json.dumps(observed, ensure_ascii=False, sort_keys=True))
    return observed


def _assert_accepted(response):
    assert response.status_code == 200 and response.json() == {"ok": True}, response.text


def _assert_temporary_failure(response):
    # Do not prescribe exactly 503. A real temporary delivery refusal must reach
    # existing clients' failure/retry branch, rather than any successful 2xx.
    assert 500 <= response.status_code < 600, "callback was not accepted anywhere but HTTP reported success"
    body = response.json()
    assert body.get("ok") is not True
    detail = body.get("detail")
    assert isinstance(detail, dict) and all(isinstance(detail.get(lang), str) and detail[lang].strip()
                                           for lang in ("ru", "ba")), "temporary callback failure must explain retry in both languages"


@pytest.mark.parametrize("note", ["QA: помоги создать заявку", "QA: сәфәр табырға ярҙам ит, кире шылтырат"])
def test_refused_callback_notification_reports_failure_and_same_note_can_retry(client, user_factory, outbound, note):
    caller = user_factory("QA_SYNTH_CALLBACK_RETRY")
    before = _profile_and_support_rows(caller["id"])
    outbound["accepted"] = False
    refused = client.post("/callback", headers=caller["auth"], json={"note": note})
    after_refusal = _profile_and_support_rows(caller["id"])
    outbound["accepted"] = True
    repeated = client.post("/callback", headers=caller["auth"], json={"note": note})
    after = _profile_and_support_rows(caller["id"])
    _receipt((refused, repeated), before, after, outbound["attempts"], after_refusal=after_refusal,
             criterion="retry of the same original note; client draft retention remains a separate UI criterion")
    assert [attempt["accepted"] for attempt in outbound["attempts"]] == [False, True]
    assert outbound["attempts"][0]["message"] == outbound["attempts"][1]["message"]
    assert note in outbound["attempts"][1]["message"]
    assert before == after_refusal == after, "callback has no persistent ticket; do not invent an alternative acceptance"
    _assert_temporary_failure(refused)
    _assert_accepted(repeated)


def test_real_missing_telegram_configuration_cannot_report_callback_accepted(client, user_factory):
    caller = user_factory("QA_SYNTH_CALLBACK_MISSING_CONFIG")
    before = _profile_and_support_rows(caller["id"])
    assert safety.notify_admin_telegram is services.notify_admin_telegram
    real_calibration = services.notify_admin_telegram("QA_SYNTH missing-config calibration")
    response = client.post("/callback", headers=caller["auth"], json={"note": "QA: ярҙам кәрәк"})
    after = _profile_and_support_rows(caller["id"])
    _receipt((response,), before, after, real_notifier_calibration=real_calibration,
             telegram_settings_empty=True, notifier_replaced=False)
    assert real_calibration is False, "actual absent delivery channel must return False"
    assert after == before
    _assert_temporary_failure(response)


@pytest.mark.parametrize("payload", [
    pytest.param({}, id="omitted-note"),
    pytest.param({"note": ""}, id="empty-note"),
    pytest.param({"note": " \t\n "}, id="whitespace-note"),
    pytest.param({"note": "QA: Һаумыһығыҙ, өләсәйгә ярҙам кәрәк 🌿\nТәүҙә шылтыратығыҙ"}, id="bashkir-unicode-newline"),
    pytest.param({"note": "ҡ" * 499}, id="499-bashkir-codepoints"),
    pytest.param({"note": "ҡ" * 500}, id="500-bashkir-codepoints"),
])
def test_accepted_callback_preserves_valid_optional_note_and_profile(client, user_factory, outbound, payload):
    caller = user_factory("QA_SYNTH_CALLBACK_VALID")
    before = _profile_and_support_rows(caller["id"])
    response = client.post("/callback", headers=caller["auth"], json=payload)
    after = _profile_and_support_rows(caller["id"])
    _receipt((response,), before, after, outbound["attempts"], note_codepoints=len(payload.get("note", "")))
    assert after == before and len(outbound["attempts"]) == 1
    message = outbound["attempts"][0]["message"]
    assert before["profile"]["name"] in message and before["profile"]["phone"] in message
    expected_note = payload.get("note") or "—"
    assert message.endswith("Сообщение: " + expected_note), "accepted note must not be lost/truncated or replaced"
    assert outbound["attempts"][0]["accepted"] is True
    _assert_accepted(response)


@pytest.mark.parametrize("note", [
    pytest.param("a" * 501, id="501-ascii-codepoints"),
    pytest.param("ҡ" * 501, id="501-bashkir-codepoints"),
    pytest.param(None, id="null-note"),
    pytest.param(42, id="numeric-note"),
])
def test_callback_invalid_note_rejected_before_delivery(client, user_factory, outbound, note):
    caller = user_factory("QA_SYNTH_CALLBACK_INVALID")
    before = _profile_and_support_rows(caller["id"])
    response = client.post("/callback", headers=caller["auth"], json={"note": note})
    after = _profile_and_support_rows(caller["id"])
    _receipt((response,), before, after, outbound["attempts"], input_type=type(note).__name__)
    assert response.status_code == 422 and outbound["attempts"] == []
    assert after == before


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer QA_SYNTH_INVALID_TOKEN"}], ids=["guest", "invalid-token"])
def test_callback_requires_real_authentication_before_delivery(client, user_factory, outbound, headers):
    caller = user_factory("QA_SYNTH_CALLBACK_AUTH_CONTROL")
    before = _profile_and_support_rows(caller["id"])
    response = client.post("/callback", headers=headers, json={"note": "QA: no authenticated owner"})
    after = _profile_and_support_rows(caller["id"])
    _receipt((response,), before, after, outbound["attempts"])
    assert response.status_code in (401, 403) and outbound["attempts"] == []
    assert after == before


def test_callback_uses_authenticated_profile_not_body_identity(client, user_factory, outbound):
    caller = user_factory("QA_SYNTH_CALLBACK_REAL_OWNER")
    before = _profile_and_support_rows(caller["id"])
    forged_name, forged_phone = "QA_SYNTH_FORGED_OWNER", "+70000000555"
    response = client.post("/callback", headers=caller["auth"], json={
        "note": "QA: identity control", "name": forged_name, "phone": forged_phone})
    after = _profile_and_support_rows(caller["id"])
    _receipt((response,), before, after, outbound["attempts"])
    assert after == before and len(outbound["attempts"]) == 1
    message = outbound["attempts"][0]["message"]
    assert before["profile"]["name"] in message and before["profile"]["phone"] in message
    assert forged_name not in message and forged_phone not in message
    _assert_accepted(response)


@pytest.mark.parametrize("accepted", [False, True], ids=["telegram-refused", "telegram-accepted"])
def test_sos_neighbor_still_persists_signal_independently_of_telegram_result(client, user_factory, outbound, accepted):
    caller = user_factory("QA_SYNTH_CALLBACK_SOS_NEIGHBOR")
    outbound["accepted"] = accepted
    before = _profile_and_support_rows(caller["id"])
    response = client.post("/sos", headers=caller["auth"], json={"category": "other", "note": "QA_SYNTH_SOS_NEIGHBOR"})
    after = _profile_and_support_rows(caller["id"])
    _receipt((response,), before, after, outbound["attempts"], neighboring_persisted_sos=True)
    assert response.status_code == 200
    assert len(outbound["attempts"]) == 1 and outbound["attempts"][0]["accepted"] is accepted
    assert response.json()["id"] in after["sos_ids"] and len(after["sos_ids"]) == len(before["sos_ids"]) + 1
    with Session(engine) as session:
        event = session.get(SosEvent, response.json()["id"])
        assert event.user_id == caller["id"] and event.status == "open"
        assert event.category == "other" and event.note == "QA_SYNTH_SOS_NEIGHBOR"
    assert after["ticket_ids"] == before["ticket_ids"] and after["support_message_ids"] == before["support_message_ids"]
