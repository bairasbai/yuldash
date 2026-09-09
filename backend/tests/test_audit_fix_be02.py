"""Регрессия BE02: приватные S3-объекты нельзя читать через публичный /media."""

from fastapi.testclient import TestClient


class _RemoteStorage:
    is_remote = True

    def __init__(self) -> None:
        self.requested: list[str] = []

    def url(self, key: str) -> str:
        self.requested.append(key)
        return f"https://storage.example.test/signed/{key}"


def test_remote_media_redirects_public_but_hides_private_areas(monkeypatch):
    from app import main

    storage = _RemoteStorage()
    monkeypatch.setattr(main, "get_storage", lambda: storage)

    with TestClient(main.create_app()) as client:
        public = client.get("/media/voice/note.m4a", follow_redirects=False)
        assert public.status_code in (302, 307)
        assert public.headers["location"] == (
            "https://storage.example.test/signed/voice/note.m4a"
        )

        for private_area in ("docs", "evidence", "carphoto"):
            response = client.get(
                f"/media/{private_area}/private.jpg", follow_redirects=False
            )
            assert response.status_code == 404
            assert "location" not in response.headers

    assert storage.requested == ["voice/note.m4a"]
