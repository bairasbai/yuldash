"""Store provider links with the identity and environment of their authorization."""

from cryptography.fernet import Fernet, InvalidToken

from .rideshare_bank_provider import validate_checkout_url


def _binding(checkout_id: int, operation_id: str, test_mode: bool) -> bytes:
    return f"rideshare-confirmation-v1\0{checkout_id}\0{operation_id}\0{int(test_mode)}\0".encode("ascii")


def seal_bank_confirmation(cipher: Fernet, checkout_id: int, operation_id: str,
                           test_mode: bool, url: str) -> bytes:
    return cipher.encrypt(_binding(checkout_id, operation_id, test_mode) + validate_checkout_url(url).encode("utf-8"))


def open_bank_confirmation(cipher: Fernet, checkout_id: int, operation_id: str,
                           test_mode: bool, ciphertext: bytes) -> str | None:
    prefix = _binding(checkout_id, operation_id, test_mode)
    try:
        envelope = cipher.decrypt(ciphertext)
        if not envelope.startswith(prefix):
            return None
        return validate_checkout_url(envelope[len(prefix):].decode("utf-8"))
    except (InvalidToken, ValueError):
        return None
