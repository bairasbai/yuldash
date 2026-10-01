"""QA-B08-003: the real HTTPX INFO record must not expose a Telegram bot key.

Only outbound transport is replaced; the notifier, HTTPX request construction,
logging and callback payload are real. MockTransport opens no socket. INFO is
explicitly enabled in this isolated probe, not claimed enabled in production.
Harmless HTTP requests before/during/after the notifier prevent a logging fix
from disabling unrelated HTTP diagnostics, including inside the notification.
"""
import json
import logging

import httpx
import pytest

from app import services
from app.config import settings
from app.observability import scrub_text


SYNTHETIC_BOT_KEY = "QA_SYNTH_HTTPX_ONLY"
SYNTHETIC_CHAT = "QA_SYNTH_HTTPX_ADMIN_CHAT"
SYNTHETIC_NOTE = "QA_SYNTH_CALLBACK: шылтыратыу ярҙамы кәрәк"
CONTROL_ROOT = "https://unrelated.invalid/qa-httpx-control"


@pytest.fixture(autouse=True)
def isolated_settings_before_any_provider_patch():
    # Report only booleans; do not disclose configuration values on guard failure.
    dev_mock = settings.env == "dev" and settings.sms_provider == "mock"
    original_credentials_empty = not any((
        settings.telegram_bot_token, settings.admin_telegram_chat_id,
        settings.redis_url, settings.firebase_credentials, settings.yandex_geocoder_key,
        settings.sentry_dsn, settings.vapid_private_key,
        settings.yookassa_shop_id, settings.yookassa_secret_key,
    ))
    assert dev_mock, "Run only under the isolated dev/mock test profile"
    assert original_credentials_empty, "Clear provider credentials before importing/running this probe"


@pytest.fixture
def synthetic_telegram_configuration(monkeypatch, isolated_settings_before_any_provider_patch):
    monkeypatch.setattr(settings, "telegram_bot_token", SYNTHETIC_BOT_KEY)
    monkeypatch.setattr(settings, "admin_telegram_chat_id", SYNTHETIC_CHAT)


def _snapshot_request(request):
    # Store the actual wire inputs before any subsequent logging/filtering occurs.
    return {"method": request.method, "url": str(request.url), "body": bytes(request.content)}


def _httpx_info_records(caplog):
    return [record for record in caplog.records
            if record.name == "httpx" and record.levelno == logging.INFO
            and record.getMessage().startswith("HTTP Request:")]


def _assert_control_info(caplog, phases):
    records = _httpx_info_records(caplog)
    for phase in phases:
        matching = [record.getMessage() for record in records
                    if f"{CONTROL_ROOT}/{phase}" in record.getMessage()]
        assert len(matching) == 1, f"Keep the actual harmless HTTPX INFO record: {phase}"
        assert matching[0].startswith("HTTP Request: GET ") and " 200 " in matching[0]


def _privacy_observations(caplog):
    full_info = [record.getMessage() for record in caplog.records if record.levelno >= logging.INFO]
    named_yuldash = [record.getMessage() for record in caplog.records if record.name == "yuldash"]
    sensitive = (SYNTHETIC_BOT_KEY, SYNTHETIC_CHAT, SYNTHETIC_NOTE)
    return {
        "synthetic_key_in_full_info": any(SYNTHETIC_BOT_KEY in message for message in full_info),
        "sensitive_marker_in_full_info": any(marker in message for message in full_info for marker in sensitive),
        "sensitive_marker_in_named_yuldash": any(marker in message for message in named_yuldash for marker in sensitive),
        "sensitive_marker_in_formatted_capture": any(marker in caplog.text for marker in sensitive),
        "httpx_info_count": len(_httpx_info_records(caplog)),
    }


def _assert_safe_capture(observations):
    assert not observations["sensitive_marker_in_full_info"], "Sensitive synthetic marker reached a full INFO record"
    assert not observations["sensitive_marker_in_named_yuldash"], "Sensitive synthetic marker reached the application log"
    assert not observations["sensitive_marker_in_formatted_capture"], "Sensitive synthetic marker reached formatted captured logs"


@pytest.mark.parametrize("status, expected", [(200, True), (204, True), (429, False), (500, False)],
                         ids=["accepted-200", "accepted-204", "rate-limited-429", "server-error-500"])
def test_notifier_httpx_info_is_safe_and_unrelated_http_logs_survive(
        monkeypatch, caplog, synthetic_telegram_configuration, status, expected):
    requests = []
    markup = {"inline_keyboard": [[{"text": "QA ярҙам", "callback_data": "QA_SYNTH_HTTPX_ACK"}]]}

    def response_transport(request):
        requests.append(_snapshot_request(request))
        if request.url.host == "api.telegram.org":
            # A real harmless HTTPX request overlaps the notifier's transport.
            # This catches a global logger-level toggle even if it is restored
            # before/after the notification; no threads or artificial sleeps.
            nested = transport_client.get(f"{CONTROL_ROOT}/during")
            assert nested.status_code == 200
            return httpx.Response(status)
        assert request.url.host == "unrelated.invalid" and request.method == "GET"
        return httpx.Response(200)

    with httpx.Client(transport=httpx.MockTransport(response_transport), trust_env=False) as transport_client:
        monkeypatch.setattr(httpx, "post", transport_client.post)
        with caplog.at_level(logging.INFO, logger="httpx"), caplog.at_level(logging.INFO, logger="yuldash"):
            assert transport_client.get(f"{CONTROL_ROOT}/before").status_code == 200
            accepted = services.notify_admin_telegram(SYNTHETIC_NOTE, reply_markup=markup)
            assert transport_client.get(f"{CONTROL_ROOT}/after").status_code == 200

    observations = _privacy_observations(caplog)
    telegram = [request for request in requests if request["method"] == "POST"]
    post_records = [record for record in _httpx_info_records(caplog)
                    if record.getMessage().startswith("HTTP Request: POST ")]
    print("QA_ADMIN_TG_HTTPX_LOG " + json.dumps({
        **observations, "http_status": status, "accepted": accepted,
        "transport_entries": len(requests), "telegram_posts": len(telegram),
        "httpx_post_info_count": len(post_records), "socket_transport": False,
        "existing_scrub_masks_synthetic_url": bool(telegram)
            and SYNTHETIC_BOT_KEY not in scrub_text(telegram[0]["url"]),
    }, ensure_ascii=False, sort_keys=True))

    assert accepted is expected, "Logging protection must preserve actual provider acceptance/failure"
    assert [request["method"] for request in requests] == ["GET", "POST", "GET", "GET"]
    assert len(telegram) == 1
    assert telegram[0]["url"] == f"https://api.telegram.org/bot{SYNTHETIC_BOT_KEY}/sendMessage"
    assert json.loads(telegram[0]["body"]) == {
        "chat_id": SYNTHETIC_CHAT, "text": SYNTHETIC_NOTE, "reply_markup": markup,
    }, "The real wire payload must retain Unicode note, recipient and buttons"
    _assert_control_info(caplog, ("before", "during", "after"))
    assert len(post_records) == 1, "Calibration: retain actual HTTPX POST diagnostic, with a safe URL"
    assert all(SYNTHETIC_BOT_KEY not in repr(record.args) for record in post_records), "Structured handlers must not retain the original credential argument"
    assert f" {status} " in post_records[0].getMessage(), "Retain the actual HTTP status diagnostic"
    _assert_safe_capture(observations)


def test_missing_configuration_has_no_telegram_transport_but_keeps_safe_info(monkeypatch, caplog):
    requests = []

    def response_transport(request):
        requests.append(_snapshot_request(request))
        assert request.url.host == "unrelated.invalid" and request.method == "GET"
        return httpx.Response(200)

    # Use the actual empty original configuration; do not replace the notifier.
    with httpx.Client(transport=httpx.MockTransport(response_transport), trust_env=False) as transport_client:
        monkeypatch.setattr(httpx, "post", transport_client.post)
        with caplog.at_level(logging.INFO, logger="httpx"), caplog.at_level(logging.INFO, logger="yuldash"):
            assert transport_client.get(f"{CONTROL_ROOT}/before").status_code == 200
            accepted = services.notify_admin_telegram(SYNTHETIC_NOTE)
            assert transport_client.get(f"{CONTROL_ROOT}/after").status_code == 200

    observations = _privacy_observations(caplog)
    diagnostics = [record.getMessage() for record in caplog.records
                   if record.name == "yuldash" and "[ADMIN_TG]" in record.getMessage()]
    print("QA_ADMIN_TG_HTTPX_LOG " + json.dumps({
        **observations, "configuration_empty": True, "accepted": accepted,
        "transport_entries": len(requests), "telegram_posts": sum(r["method"] == "POST" for r in requests),
        "named_diagnostic_count": len(diagnostics), "socket_transport": False,
    }, sort_keys=True))
    assert accepted is False
    assert [request["url"] for request in requests] == [f"{CONTROL_ROOT}/before", f"{CONTROL_ROOT}/after"]
    assert diagnostics, "Calibration: actual missing-configuration diagnostic must be captured"
    _assert_control_info(caplog, ("before", "after"))
    _assert_safe_capture(observations)
