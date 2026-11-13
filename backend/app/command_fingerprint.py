"""Отпечаток исходного JSON команд с ключом cmd1_."""

from decimal import Decimal, InvalidOperation
from hashlib import sha256
import json
from typing import Any


KEY_PREFIX = "cmd1_"
MAX_BODY_BYTES = 64 * 1024
MAX_DEPTH = 32


def _unique_object(pairs: list[tuple[str, Any]]) -> dict:
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("Duplicate JSON member")
        result[name] = value
    return result


def _invalid_number(_value: str) -> None:
    raise ValueError("Invalid JSON number")


def parse_body(raw: bytes) -> Any:
    if len(raw) > MAX_BODY_BYTES:
        raise ValueError("Command body too large")
    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8"), parse_float=Decimal, parse_int=Decimal,
                          parse_constant=_invalid_number, object_pairs_hook=_unique_object)
    except (InvalidOperation, RecursionError) as error:
        raise ValueError("Invalid JSON body") from error


def _decimal_text(value: int | Decimal) -> str:
    if isinstance(value, int):
        value = Decimal(value)
    if not value.is_finite() or len(value.as_tuple().digits) > 350:
        raise ValueError("Invalid JSON number")
    if not value:
        return "0"
    if not -324 <= value.adjusted() <= 308:
        raise ValueError("JSON number outside command range")
    text = format(value, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def _canonical(value: Any, depth: int = 0) -> str:
    if depth > MAX_DEPTH:
        raise ValueError("JSON body too deeply nested")
    if value is None or isinstance(value, (bool, str)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, (int, Decimal)):
        return _decimal_text(value)
    if isinstance(value, list):
        return "[" + ",".join(_canonical(item, depth + 1) for item in value) + "]"
    if isinstance(value, dict):
        # JavaScript и Kotlin сравнивают строки по кодовым единицам UTF-16.
        names = sorted(value, key=lambda key: key.encode("utf-16-be"))
        return "{" + ",".join(
            _canonical(name, depth + 1) + ":" + _canonical(value[name], depth + 1) for name in names
        ) + "}"
    raise ValueError("Unsupported JSON value")


def wire_fingerprint(scope: str, target_id: int | None, body: Any) -> str:
    text = _canonical({"scope": scope, "target_id": target_id, "body": body})
    return sha256(text.encode("utf-8")).hexdigest()
