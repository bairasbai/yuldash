"""Unconnected exact-generation prototype. Not an application storage contract.

Equality is portable; SQLite TEXT ordering and SQL arithmetic are NOT numeric.
Raw SQL bypasses bind validation. No callers, migration or recovery activation.
"""

from decimal import Decimal
import re

from sqlalchemy import Numeric, Text
from sqlalchemy.types import TypeDecorator

DIGITS = 128
_LIMIT = 10**DIGITS


def validate_integer(value):
    if value is None:
        return None
    if type(value) is not int or not -_LIMIT < value < _LIMIT:
        raise ValueError("bounded exact integer required")
    return value


def decode_decimal(value):
    if value is None:
        return None
    if not isinstance(value, Decimal) or not value.is_finite():
        raise ValueError("finite Decimal required")
    parts = value.as_tuple()
    if (
        len(parts.digits) > DIGITS
        or abs(parts.exponent) > DIGITS
        or (not value.is_zero() and value.adjusted() >= DIGITS)
    ):
        raise ValueError("decimal budget exceeded")
    if value != value.to_integral_value():
        raise ValueError("integral Decimal required")
    return validate_integer(int(value))


def decode_text(value):
    if value is None:
        return None
    if (
        type(value) is not str
        or len(value) > DIGITS + (value.startswith("-"))
        or re.fullmatch(r"0|-?[1-9][0-9]*", value) is None
    ):
        raise ValueError("bounded canonical signed text required")
    return validate_integer(int(value))


def exact_next(value):
    """Pure proposal only: caller must establish ownership and locking first."""
    value = validate_integer(value)
    return validate_integer(1 if value is None else value + 1)


class ExactGeneration(TypeDecorator):
    impl = Text
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == "postgresql":
            return dialect.type_descriptor(Numeric(asdecimal=True))
        if dialect.name == "sqlite":
            return dialect.type_descriptor(Text())
        raise ValueError("unsupported generation dialect")

    def process_bind_param(self, value, dialect):
        value = validate_integer(value)
        if value is None:
            return None
        if dialect.name == "postgresql":
            return Decimal(value)
        if dialect.name == "sqlite":
            return str(value)
        raise ValueError("unsupported generation dialect")

    def process_result_value(self, value, dialect):
        if dialect.name == "postgresql":
            return decode_decimal(value)
        if dialect.name == "sqlite":
            return decode_text(value)
        raise ValueError("unsupported generation dialect")
