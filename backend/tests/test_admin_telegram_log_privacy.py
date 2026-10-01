"""B08: admin Telegram failures must not copy the URL's bot key into local logs.

Actual notify_admin_telegram and HTTPX request/exception objects, with only the
outbound transport replaced. MockTransport never opens a socket. Exception text
intentionally includes a synthetic full URL; this does not claim that ordinary
HTTPX failures always include URLs or that a production key has been exposed.
"""
import json
import logging

import httpx
import pytest

from app import services
from app.config import settings
from app.observability import scrub_exc, scrub_text


SYNTHETIC_BOT_KEY = "QA_SYNTH_ONLY"
SYNTHETIC_CHAT = "QA_SYNTH_ADMIN_CHAT"
SYNTHETIC_NOTE = "QA_SYNTH_CALLBACK: шылтыратыу ярҙамы кәрәк"


@pytest.fixture(autouse=True)
def isolated_settings_before_any_provider_patch():
    # Check booleans rather than exposing actual configuration in failed assertions.
    dev_mock = settings.env == "dev" and settings.sms_provider == "mock"
    original_credentials_empty = not any((
        settings.telegram_bot_token, settings.admin_telegram_chat_id,
        settings.redis_url, settings.firebase_credentials, settings.yandex_geocoder_key, settings.sentry_dsn,
        settings.vapid_private_key, settings.yookassa_shop_id, settings.yookassa_secret_key,
    ))
    assert dev_mock, "Run only under the isolated dev/mock test profile"
    assert original_credentials_empty, "Clear real provider credentials before importing/running this probe"


@pytest.fixture
def synthetic_telegram_configuration(monkeypatch, isolated_settings_before_any_provider_patch):
    monkeypatch.setattr(settings, "telegram_bot_token", SYNTHETIC_BOT_KEY)
    monkeypatch.setattr(settings, "admin_telegram_chat_id", SYNTHETIC_CHAT)


def _admin_warnings(caplog):
    return [record.getMessage() for record in caplog.records
            if record.name == "yuldash" and record.levelno >= logging.WARNING and "[ADMIN_TG]" in record.getMessage()]


def _assert_actual_request(request, expected_markup=None):
    assert request.method == "POST"
    assert request.url.scheme == "https" and request.url.host == "api.telegram.org"
    assert request.url.path == f"/bot{SYNTHETIC_BOT_KEY}/sendMessage"
    payload = json.loads(request.content)
    expected = {"chat_id": SYNTHETIC_CHAT, "text": SYNTHETIC_NOTE}
    if expected_markup:
        expected["reply_markup"] = expected_markup
    assert payload == expected, "Logging changes must preserve the actual outbound callback message"


@pytest.mark.parametrize("error_type", [httpx.RequestError, httpx.ReadTimeout],
                         ids=["request-error-with-url", "read-timeout-with-url"])
def test_transport_url_in_exception_is_not_copied_to_local_admin_log(
        monkeypatch, caplog, synthetic_telegram_configuration, error_type):
    requests = []
    errors = []

    def failing_transport(request):
        requests.append(request)
        # Controlled failure AFTER real HTTPX built the actual request. The key
        # comes from the request URL, not from a fake notifier or a fake logger.
        error = error_type(f"QA_SYNTH_TRANSPORT_FAILURE during POST {request.url}", request=request)
        errors.append(error)
        raise error

    with httpx.Client(transport=httpx.MockTransport(failing_transport), trust_env=False) as transport_client:
        monkeypatch.setattr(httpx, "post", transport_client.post)
        with caplog.at_level(logging.WARNING, logger="yuldash"):
            accepted = services.notify_admin_telegram(SYNTHETIC_NOTE)

    warnings = _admin_warnings(caplog)
    sensitive_value_in_local_log = any(SYNTHETIC_BOT_KEY in warning for warning in warnings)
    print("QA_ADMIN_TG_LOG " + json.dumps({
        "exception_type": error_type.__name__, "accepted": accepted,
        "requests": len(requests), "warning_count": len(warnings),
        "controlled_exception_contains_synthetic_url_key": bool(errors) and SYNTHETIC_BOT_KEY in str(errors[0]),
        "synthetic_url_key_in_local_warning": sensitive_value_in_local_log,
        # Observations, not a mandate to change the shared scrub helper. A fix can
        # retain a safe diagnostic without copying arbitrary exception contents.
        "existing_scrub_text_masks_synthetic_url_key": bool(errors) and SYNTHETIC_BOT_KEY not in scrub_text(str(errors[0])),
        "existing_scrub_exc_masks_synthetic_url_key": bool(errors) and SYNTHETIC_BOT_KEY not in scrub_exc(errors[0]),
        "socket_transport": False,
    }, ensure_ascii=False, sort_keys=True))
    assert accepted is False, "Controlled outbound failure cannot count as delivered"
    assert len(requests) == len(errors) == 1
    _assert_actual_request(requests[0])
    assert warnings, "Calibration: actual local admin warning must be captured"
    assert any(error_type.__name__ in warning for warning in warnings), "Keep the safe failure class for diagnosis"
    assert not sensitive_value_in_local_log, "Bot key from controlled exception URL reached the local admin warning"
    assert all(SYNTHETIC_CHAT not in warning and SYNTHETIC_NOTE not in warning for warning in warnings)


@pytest.mark.parametrize("status, expected", [(200, True), (204, True), (429, False), (500, False)],
                         ids=["accepted-200", "accepted-204", "rate-limited-429", "server-error-500"])
def test_actual_http_status_and_payload_survive_safe_logging(
        monkeypatch, caplog, synthetic_telegram_configuration, status, expected):
    requests = []
    markup = {"inline_keyboard": [[{"text": "QA помочь", "callback_data": "QA_SYNTH_ACK"}]]}

    def response_transport(request):
        requests.append(request)
        return httpx.Response(status)

    with httpx.Client(transport=httpx.MockTransport(response_transport), trust_env=False) as transport_client:
        monkeypatch.setattr(httpx, "post", transport_client.post)
        with caplog.at_level(logging.WARNING, logger="yuldash"):
            accepted = services.notify_admin_telegram(SYNTHETIC_NOTE, reply_markup=markup)

    warnings = _admin_warnings(caplog)
    print("QA_ADMIN_TG_LOG " + json.dumps({"http_status": status, "accepted": accepted,
        "requests": len(requests), "warning_count": len(warnings), "socket_transport": False}, sort_keys=True))
    assert accepted is expected
    assert len(requests) == 1
    _assert_actual_request(requests[0], expected_markup=markup)
    assert all(SYNTHETIC_BOT_KEY not in warning and SYNTHETIC_NOTE not in warning for warning in warnings)


def test_missing_configuration_returns_false_without_entering_transport(monkeypatch, caplog):
    requests = []

    def forbidden_transport(request):
        requests.append(request)
        raise AssertionError("Missing-config notifier must not enter even the offline transport")

    # Keep actual empty configuration; do not replace notify_admin_telegram.
    with httpx.Client(transport=httpx.MockTransport(forbidden_transport), trust_env=False) as transport_client:
        monkeypatch.setattr(httpx, "post", transport_client.post)
        with caplog.at_level(logging.INFO, logger="yuldash"):
            accepted = services.notify_admin_telegram(SYNTHETIC_NOTE)

    notes = [record.getMessage() for record in caplog.records
             if record.name == "yuldash" and "[ADMIN_TG]" in record.getMessage()]
    print("QA_ADMIN_TG_LOG " + json.dumps({"configuration_empty": True, "accepted": accepted,
        "requests": len(requests), "diagnostic_count": len(notes), "socket_transport": False}, sort_keys=True))
    assert accepted is False and requests == []
    assert notes, "Calibration: actual missing-configuration diagnostic must be captured"
    assert all(SYNTHETIC_NOTE not in note and SYNTHETIC_CHAT not in note for note in notes)
