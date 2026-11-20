"""Readiness of online payment for a passenger booking."""

from .errors import herr


CARD_PAYMENTS_SWITCH = "rideshare_card_payments"
BANK_PROJECT_UNACCEPTED = "bank_project_unaccepted"
_BANK_PENDING_RU = "Банковский модуль ещё не принят."
_BANK_PENDING_BA = "Банк модуле әле ҡабул ителмәгән."


def card_blockers() -> list[dict[str, str]]:
    return [{"code": BANK_PROJECT_UNACCEPTED, "ru": _BANK_PENDING_RU, "ba": _BANK_PENDING_BA}]


def reject_card_enable():
    return herr(409, _BANK_PENDING_RU, _BANK_PENDING_BA, code=BANK_PROJECT_UNACCEPTED, blockers=card_blockers())
