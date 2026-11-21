"""Store provider links with the identity and environment of their authorization."""

from cryptography.fernet import Fernet, InvalidToken

from .rideshare_bank_provider import validate_checkout_url


def _binding(checkout_id: int, operation_id: str, test_mode: bool, account_id: str | None) -> bytes:
    if account_id is None:
        return f"rideshare-confirmation-v1\0{checkout_id}\0{operation_id}\0{int(test_mode)}\0".encode("ascii")
    return f"rideshare-confirmation-v2\0{checkout_id}\0{operation_id}\0{int(test_mode)}\0{account_id}\0".encode("ascii")


def seal_bank_confirmation(cipher: Fernet, checkout_id: int, operation_id: str,
                           test_mode: bool, url: str, *, account_id: str | None = None) -> bytes:
    binding = _binding(checkout_id, operation_id, test_mode, account_id)
    return cipher.encrypt(binding + validate_checkout_url(url).encode("utf-8"))


def open_bank_confirmation(cipher: Fernet, checkout_id: int, operation_id: str,
                           test_mode: bool, ciphertext: bytes, *, account_id: str | None = None) -> str | None:
    prefix = _binding(checkout_id, operation_id, test_mode, account_id)
    try:
        envelope = cipher.decrypt(ciphertext)
        if not envelope.startswith(prefix):
            return None
        return validate_checkout_url(envelope[len(prefix):].decode("utf-8"))
    except (InvalidToken, ValueError):
        return None
