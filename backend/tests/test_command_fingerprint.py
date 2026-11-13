"""Общие контрольные примеры Python, Android и PWA."""

from hashlib import sha256
import json
from pathlib import Path

import pytest

from app.command_fingerprint import parse_body, wire_fingerprint


FIXTURES = json.loads((Path(__file__).resolve().parents[2] / "tools/fixtures/rs05-command-fingerprint.json")
                      .read_text(encoding="utf-8"))


@pytest.mark.parametrize("example", FIXTURES, ids=lambda example: example["name"])
def test_shared_wire_fingerprint(example):
    scope, target, body = example["scope"], example["target_id"], example["wire_body"]
    envelope = (f'{{"body":{example["canonical_body"]},"scope":{json.dumps(scope)},'
                f'"target_id":{json.dumps(target)}}}')
    expected = sha256(envelope.encode()).hexdigest()
    assert wire_fingerprint(scope, target, parse_body(body.encode() if body else b"")) == expected


@pytest.mark.parametrize("raw", [
    pytest.param(br'{"a":0,"\u0061":1}', id="duplicate"),
    pytest.param(b'{"a":NaN}', id="nan"),
    pytest.param(b'{"a":Infinity}', id="infinity"),
    pytest.param(b'{"a":"\xff"}', id="invalid-utf8"),
    pytest.param(br'{"a":"\ud800"}', id="unpaired-surrogate"),
    pytest.param(b'{"a":1e309}', id="large-exponent"),
    pytest.param(b'{"a":1e-325}', id="small-exponent"),
    pytest.param(b'{"a":' + b'1' * 351 + b'}', id="precision"),
    pytest.param(b"[" * 33 + b"0" + b"]" * 33, id="depth"),
    pytest.param(b" " * (64 * 1024 + 1), id="size"),
])
def test_invalid_wire_body_is_not_hashable(raw):
    with pytest.raises(ValueError):
        wire_fingerprint("booking.create", 73, parse_body(raw))
