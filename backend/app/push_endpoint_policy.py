"""Outbound Web Push policy for the browser providers supported by this release.

Unknown/self-hosted providers need a reviewed policy extension. Never fall back
to arbitrary HTTPS: subscriptions are user input, not trusted outbound URLs.
"""
import re
from urllib.parse import urlsplit

import requests


_PROVIDER_HOSTS = frozenset({
    "fcm.googleapis.com",
    "updates.push.services.mozilla.com",
    "web.push.apple.com",
})
_WNS_HOST = re.compile(r"(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+notify\.windows\.com")


def is_allowed_push_endpoint(endpoint: str) -> bool:
    if not isinstance(endpoint, str) or not endpoint or len(endpoint) > 1000:
        return False
    # Reject ambiguous parser input instead of normalizing it into a trusted host.
    if "\\" in endpoint or any(ord(char) <= 32 or ord(char) >= 127 for char in endpoint):
        return False
    try:
        url = urlsplit(endpoint)
        if (url.scheme != "https" or url.username is not None or url.password is not None
                or url.port not in (None, 443) or url.fragment):
            return False
        host = url.hostname or ""
        return host in _PROVIDER_HOSTS or _WNS_HOST.fullmatch(host) is not None
    except ValueError:
        return False


class PushRequestsSession(requests.Session):
    """pywebpush transport: a provider response cannot redirect into our network."""

    def request(self, method, url, **kwargs):
        if not is_allowed_push_endpoint(url):
            raise ValueError("Untrusted Web Push endpoint")
        kwargs["allow_redirects"] = False
        return super().request(method, url, **kwargs)
