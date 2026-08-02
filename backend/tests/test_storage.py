"""Тесты абстракции хранилища медиа (storage.py): локальный диск + S3 + фолбэк.

Реального S3 в CI нет — boto3-клиент мокаем (FakeS3Client). Проверяем:
- по умолчанию (без ключей) выбирается локальный диск (полный фолбэк);
- локальный режим сохраняет/читает/удаляет и генерит те же URL, что и раньше;
- S3-режим реально дёргает клиент (put/get/head/delete) и отдаёт подписанный URL;
- фабрика выбирает S3, когда задан бакет+ключи.
"""
import io
import uuid

import pytest

from app import storage as storage_mod
from app.config import Settings
from app.services import public_media_url, secure_docs_url
from app.storage import (
    LocalStorage, S3Storage, Storage, _build_storage, get_storage, reset_storage,
)


# ----------------------------- Мок S3-клиента (boto3) -----------------------------
def _not_found_error() -> Exception:
    e = Exception("NoSuchKey")
    e.response = {"Error": {"Code": "NoSuchKey"}}   # форма boto3 ClientError
    return e


class FakeS3Client:
    """Минимальный двойник boto3 s3-клиента: держит объекты в памяти, пишет журнал вызовов."""

    def __init__(self):
        self.objects: dict[tuple[str, str], bytes] = {}
        self.calls: list[tuple] = []

    def put_object(self, Bucket, Key, Body):
        self.calls.append(("put", Bucket, Key))
        self.objects[(Bucket, Key)] = Body

    def get_object(self, Bucket, Key):
        self.calls.append(("get", Bucket, Key))
        if (Bucket, Key) not in self.objects:
            raise _not_found_error()
        return {"Body": io.BytesIO(self.objects[(Bucket, Key)])}

    def head_object(self, Bucket, Key):
        if (Bucket, Key) not in self.objects:
            raise _not_found_error()
        return {}

    def delete_object(self, Bucket, Key):
        self.calls.append(("delete", Bucket, Key))
        self.objects.pop((Bucket, Key), None)

    def generate_presigned_url(self, op, Params, ExpiresIn):
        self.calls.append(("presign", op, Params["Key"], ExpiresIn))
        return f"https://s3.example/{Params['Bucket']}/{Params['Key']}?sig=abc&exp={ExpiresIn}"


@pytest.fixture(autouse=True)
def _restore_singleton():
    """Каждый тест не должен утаскивать за собой подменённый синглтон хранилища."""
    yield
    reset_storage(None)


# ----------------------------- Выбор бэкенда (фолбэк) -----------------------------
def test_default_backend_is_local_without_s3_keys():
    """Без ключей S3 (тестовое окружение) — по умолчанию локальный диск."""
    reset_storage(None)
    assert isinstance(get_storage(), LocalStorage)


def test_storage_is_s3_property():
    """storage_is_s3: явный режим главнее авто; авто включается только при полном наборе ключей."""
    assert Settings(storage_backend="", s3_bucket="", s3_access_key="", s3_secret_key="").storage_is_s3 is False
    # авто: неполный набор → всё ещё локальный
    assert Settings(s3_bucket="b", s3_access_key="a").storage_is_s3 is False
    # авто: полный набор → S3
    assert Settings(s3_bucket="b", s3_access_key="a", s3_secret_key="s").storage_is_s3 is True
    # явный local главнее наличия ключей
    assert Settings(storage_backend="local", s3_bucket="b", s3_access_key="a", s3_secret_key="s").storage_is_s3 is False
    # явный s3 даже без ключей (валидатор прода отдельно ругнётся)
    assert Settings(storage_backend="s3").storage_is_s3 is True


def test_factory_selects_s3_when_configured(monkeypatch):
    """При заданных бакете+ключах фабрика поднимает S3Storage (boto3 — реальный клиент, офлайн)."""
    for k, v in {
        "storage_backend": "s3", "s3_bucket": "yuldash-media",
        "s3_access_key": "AK", "s3_secret_key": "SK",
        "s3_endpoint_url": "https://s3.timeweb.cloud", "s3_region": "ru-1",
    }.items():
        monkeypatch.setattr(storage_mod.settings, k, v, raising=False)
    reset_storage(None)
    st = get_storage()
    assert isinstance(st, S3Storage)
    assert st.bucket == "yuldash-media"


# ----------------------------- Локальный режим (1:1 как раньше) -----------------------------
def test_local_roundtrip_and_delete():
    st = LocalStorage()
    key = f"voice/{uuid.uuid4().hex}.m4a"
    payload = b"\x00\x01hello-voice"
    assert st.exists(key) is False
    st.save(key, payload)
    assert st.exists(key) is True
    assert st.load(key) == payload
    st.delete(key)
    assert st.exists(key) is False
    # повторное удаление и чтение отсутствующего — без падений/находок
    st.delete(key)
    with pytest.raises(FileNotFoundError):
        st.load(key)


def test_local_urls_match_legacy_helpers():
    """URL публичного медиа и приватного документа = те же, что отдавали хелперы до абстракции."""
    st = LocalStorage()
    assert st.url("voice/note.m4a") == public_media_url("voice/note.m4a")
    assert st.url("chat/pic.jpg") == public_media_url("chat/pic.jpg")
    assert st.url("docs/7_abc.jpg") == secure_docs_url("7_abc.jpg")


def test_local_private_and_public_separated(tmp_path, monkeypatch):
    """docs/ пишется в приватное дерево, voice/chat — в публичное (разные корни)."""
    monkeypatch.setattr(storage_mod, "MEDIA_DIR", str(tmp_path / "media"))
    monkeypatch.setattr(storage_mod, "PRIVATE_DIR", str(tmp_path / "private"))
    st = LocalStorage()
    st.save("voice/a.m4a", b"pub")
    st.save("docs/1_b.jpg", b"priv")
    assert (tmp_path / "media" / "voice" / "a.m4a").read_bytes() == b"pub"
    assert (tmp_path / "private" / "docs" / "1_b.jpg").read_bytes() == b"priv"


def test_local_neutralizes_traversal_key(tmp_path, monkeypatch):
    """'..' в ключе вырезается — файл не может уйти выше корня хранилища."""
    import os
    monkeypatch.setattr(storage_mod, "MEDIA_DIR", str(tmp_path / "media"))
    monkeypatch.setattr(storage_mod, "PRIVATE_DIR", str(tmp_path / "private"))
    st = LocalStorage()
    path = st._path("voice/../../../etc/passwd")
    root = os.path.realpath(str(tmp_path / "media"))
    assert os.path.realpath(path).startswith(root)
    # пустой ключ (после чистки ничего не осталось) — ошибка, писать некуда
    with pytest.raises(ValueError):
        st.save("../..", b"x")


# ----------------------------- S3-режим (мок клиента) -----------------------------
def test_s3_save_load_exists_delete():
    fake = FakeS3Client()
    st = S3Storage(bucket="bkt", client=fake)
    key = "docs/9_doc.jpg"
    assert st.exists(key) is False
    st.save(key, b"jpeg-bytes")
    assert ("put", "bkt", "docs/9_doc.jpg") in fake.calls
    assert st.exists(key) is True
    assert st.load(key) == b"jpeg-bytes"
    st.delete(key)
    assert st.exists(key) is False


def test_s3_load_missing_raises_filenotfound():
    st = S3Storage(bucket="bkt", client=FakeS3Client())
    with pytest.raises(FileNotFoundError):
        st.load("docs/missing.jpg")


def test_s3_url_is_presigned():
    fake = FakeS3Client()
    st = S3Storage(bucket="bkt", client=fake, signed_ttl=120)
    url = st.url("voice/x.m4a")
    assert url.startswith("https://s3.example/bkt/voice/x.m4a")
    assert ("presign", "get_object", "voice/x.m4a", 120) in fake.calls


def test_s3_is_remote_flag():
    assert S3Storage(bucket="b", client=FakeS3Client()).is_remote is True
    assert LocalStorage().is_remote is False


def test_s3_requires_bucket():
    with pytest.raises(ValueError):
        S3Storage(bucket="", client=FakeS3Client())


def test_storage_contract():
    """Обе реализации — Storage."""
    assert isinstance(LocalStorage(), Storage)
    assert isinstance(S3Storage(bucket="b", client=FakeS3Client()), Storage)
