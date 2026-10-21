"""Настоящий multipart /voice: браузерный WebM разрешён, ограничения загрузки сохранены.

Данные синтетические: проверяем транспорт/хранение, не декодирование аудио и не чистку WebM.
"""
from urllib.parse import urlsplit

import pytest

from app.config import settings
from app import storage


WEBM = b"\x1aE\xdf\xa3" + b"synthetic-audio-container" * 80
M4A = b"\x00\x00\x00\x20ftypM4A " + b"\x00" * 100


@pytest.fixture
def isolated_audio_store(tmp_path, monkeypatch):
    public = tmp_path / "owned_voice_compatibility"
    private = tmp_path / "owned_voice_compatibility_private"
    public.mkdir()
    private.mkdir()
    monkeypatch.setattr(storage, "MEDIA_DIR", str(public))
    monkeypatch.setattr(storage, "PRIVATE_DIR", str(private))
    own_store = storage.LocalStorage()
    monkeypatch.setattr(storage, "_storage", own_store)
    # Проверяем именно продуктовый DEFAULT, не возможную внешнюю настройку рабочего окружения.
    monkeypatch.setattr(settings, "allowed_audio_ext", type(settings).model_fields["allowed_audio_ext"].default)
    yield own_store
    for root in (public, private):
        for path in root.rglob("*"):
            if path.is_file():
                path.unlink()
        assert not any(path.is_file() for path in root.rglob("*"))


def _key(response):
    assert response.status_code == 200, response.text
    path = urlsplit(response.json()["url"]).path
    assert path.startswith("/media/voice/")
    return path.removeprefix("/media/")


def test_default_webm_real_multipart_saves_owned_file(client, user_factory, isolated_audio_store):
    user = user_factory("Synthetic browser WebM")
    response = client.post("/voice", headers=user["auth"],
                           files={"file": ("voice.webm", WEBM, "audio/webm;codecs=opus")})
    key = _key(response)
    assert key.startswith(f"voice/{user['id']}_") and key.endswith(".webm")
    assert isolated_audio_store.load(key) == WEBM
    assert list(isolated_audio_store.iter_owned(["voice"], user["id"])) == [key]


@pytest.mark.parametrize("filename,mime,data", [
    ("voice.m4a", "audio/mp4", M4A),
    ("voice.ogg", "audio/ogg", b"OggS" + b"synthetic-audio" * 100),
    ("voice.mp3", "audio/mpeg", b"ID3" + b"synthetic-audio" * 100),
])
def test_previous_audio_formats_keep_working(client, user_factory, isolated_audio_store, filename, mime, data):
    user = user_factory("Synthetic existing audio")
    response = client.post("/voice", headers=user["auth"], files={"file": (filename, data, mime)})
    key = _key(response)
    assert key.endswith("." + filename.rsplit(".", 1)[1])
    assert isolated_audio_store.exists(key)


def test_webm_cannot_bypass_authentication(client, isolated_audio_store):
    response = client.post("/voice", files={"file": ("voice.webm", WEBM, "audio/webm")})
    assert response.status_code == 401
    assert list(isolated_audio_store.iter_old(["voice"], float("inf"))) == []


def test_declared_audio_mime_does_not_allow_unapproved_extension(client, user_factory, isolated_audio_store):
    user = user_factory("Synthetic rejected extension")
    response = client.post("/voice", headers=user["auth"],
                           files={"file": ("voice.exe", WEBM, "audio/webm")})
    assert response.status_code == 400
    assert list(isolated_audio_store.iter_owned(["voice"], user["id"])) == []


def test_webm_cannot_bypass_existing_size_limit(client, user_factory, isolated_audio_store, monkeypatch):
    user = user_factory("Synthetic oversized audio")
    monkeypatch.setattr(settings, "max_upload_mb", 1)
    response = client.post("/voice", headers=user["auth"],
                           files={"file": ("voice.webm", b"x" * (1024 * 1024 + 1), "audio/webm")})
    assert response.status_code == 413
    assert list(isolated_audio_store.iter_owned(["voice"], user["id"])) == []


def test_webm_shares_the_existing_per_owner_daily_quota(client, user_factory, isolated_audio_store, monkeypatch):
    user = user_factory("Synthetic upload quota")
    other = user_factory("Synthetic other upload owner")
    monkeypatch.setattr(settings, "max_uploads_per_day", 1)
    first = client.post("/voice", headers=user["auth"], files={"file": ("voice.m4a", M4A, "audio/mp4")})
    assert first.status_code == 200, first.text
    limited = client.post("/voice", headers=user["auth"], files={"file": ("voice.webm", WEBM, "audio/webm")})
    assert limited.status_code == 429
    independent = client.post("/voice", headers=other["auth"], files={"file": ("voice.webm", WEBM, "audio/webm")})
    assert independent.status_code == 200, independent.text
    assert len(list(isolated_audio_store.iter_owned(["voice"], user["id"]))) == 1
    assert len(list(isolated_audio_store.iter_owned(["voice"], other["id"]))) == 1


def test_webm_uses_server_owner_prefix_instead_of_client_filename(client, user_factory, isolated_audio_store):
    user = user_factory("Synthetic filename owner")
    response = client.post("/voice", headers=user["auth"],
                           files={"file": ("../../foreign-owner.webm", WEBM, "audio/webm")})
    key = _key(response)
    assert key.startswith(f"voice/{user['id']}_") and key.endswith(".webm")
    assert "foreign-owner" not in key and ".." not in key
    assert isolated_audio_store.exists(key)


def test_account_cleanup_removes_only_owned_webm_and_keeps_other_owner(client, user_factory, isolated_audio_store):
    leaving = user_factory("Synthetic WebM leaving")
    staying = user_factory("Synthetic WebM staying")
    keys = []
    for user in (leaving, staying):
        response = client.post("/voice", headers=user["auth"],
                               files={"file": ("voice.webm", WEBM, "audio/webm")})
        keys.append(_key(response))
    deleted = client.post("/me/delete", headers=leaving["auth"], json={"confirm": "УДАЛИТЬ"})
    assert deleted.status_code == 200, deleted.text
    assert list(isolated_audio_store.iter_owned(["voice"], leaving["id"])) == []
    assert not isolated_audio_store.exists(keys[0])
    assert isolated_audio_store.exists(keys[1])
