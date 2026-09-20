"""New refresh-recovery credentials must not leave the server in error reports."""
import json

import pytest

from app.observability import before_send, scrub_text


SECRETS = [
    "abcdef0123456789" * 4,
    "a" * 64,
    "b" * 20 + "79991234567" + "c" * 33,
]


@pytest.mark.parametrize("secret", SECRETS)
def test_scrub_text_masks_complete_hex_credentials_before_partial_phone_rules(secret):
    assert len(secret) == 64
    message = f"refresh failed: raw='{secret}', child_raw='{secret}', rotation_id='{secret}'"
    assert scrub_text(message) == (
        "refresh failed: raw='<токен>', child_raw='<токен>', rotation_id='<токен>'"
    )


def test_before_send_scrubs_nested_payload_and_stack_frame_locals():
    raw, nonce, child = SECRETS
    event = {
        "request": {"data": {"refresh_token": raw, "rotation_id": nonce}},
        "exception": {"values": [{"stacktrace": {"frames": [{
            "filename": "app/security.py",
            "vars": {"raw": raw, "child_raw": child, "rotation_id": nonce},
        }]}}]},
        "breadcrumbs": {"values": [{"message": f"rotation failed for '{child}'"}]},
    }
    cleaned = before_send(event, {})
    assert cleaned is not None
    assert cleaned["request"]["data"] == {
        "refresh_token": "<токен>", "rotation_id": "<токен>",
    }
    frame = cleaned["exception"]["values"][0]["stacktrace"]["frames"][0]
    assert frame["filename"] == "app/security.py"
    assert frame["vars"] == {"raw": "<токен>", "child_raw": "<токен>", "rotation_id": "<токен>"}
    for secret in SECRETS:
        assert secret not in json.dumps(cleaned)
    assert event["request"]["data"]["refresh_token"] == raw


def test_short_diagnostic_identifiers_remain_readable():
    assert scrub_text("revision abcdef1234; HTTP 401; retry 2") == "revision abcdef1234; HTTP 401; retry 2"
