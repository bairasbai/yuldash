"""Пара VAPID-ключей для Web Push — один раз, руками.

Печатает две строки в формате, который ждут и браузер (applicationServerKey),
и pywebpush (vapid_private_key): base64url без «=».

  python tools/vapid_keys.py

Публичный ключ → webapp/.env.production (VITE_VAPID_PUBLIC_KEY) и /opt/yuldash/.env
(VAPID_PUBLIC_KEY). Приватный → только /opt/yuldash/.env (VAPID_PRIVATE_KEY).
Приватный ключ никуда не копировать и не пересылать: у кого он есть, тот может
слать пуши от нашего имени.
"""
from cryptography.hazmat.primitives import serialization
from py_vapid import Vapid01, b64urlencode


def main() -> None:
    vapid = Vapid01()
    vapid.generate_keys()
    public = vapid.public_key.public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
    )
    private = vapid.private_key.private_numbers().private_value.to_bytes(32, "big")
    print("VAPID_PUBLIC_KEY=" + b64urlencode(public))
    print("VAPID_PRIVATE_KEY=" + b64urlencode(private))


if __name__ == "__main__":
    main()
