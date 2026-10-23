"""Strict JSON input: duplicate object keys and non-JSON numbers are rejected."""

import json
from pathlib import Path


MAX_MANIFEST_BYTES = 8 * 1024 * 1024


class InputError(ValueError):
    pass


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InputError(f"Duplicate JSON object key: {key}")
        result[key] = value
    return result


def _constant(value):
    raise InputError(f"Non-JSON number: {value}")


def read_document(path):
    path = Path(path)
    with path.open("rb") as stream:
        raw = stream.read(MAX_MANIFEST_BYTES + 1)
    if len(raw) > MAX_MANIFEST_BYTES:
        raise InputError("Manifest exceeds the 8 MiB input limit")
    return json.loads(raw.decode("utf-8"), object_pairs_hook=_object, parse_constant=_constant)
