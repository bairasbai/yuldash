"""Абстракция хранилища медиа Юлдаша: локальный диск (по умолчанию) или S3.

Зачем: фото машин/документов/чеков раньше лежали ТОЛЬКО на диске сервера —
при переезде/масштабировании (несколько воркеров, новый сервер) файлы терялись.
Здесь единая точка «сохранить/прочитать/удалить/отдать» — а КУДА (диск или облако)
решает конфиг.

Правила:
- Без ключей S3 → локальный диск, поведение 1:1 как раньше (полный фолбэк, прод не ломается).
- S3-совместимое хранилище (Timeweb/VK Cloud/Selectel/AWS) — endpoint и ключи из .env,
  секреты НЕ в git. boto3 импортируется лениво: без S3-конфига он на рантайме не нужен.
- Ключ (`key`) — путь внутри хранилища: `voice/<файл>`, `chat/<файл>` (публичные),
  `docs/<файл>` (приватные документы водителя, 152-ФЗ).

Внешние URL приложения (`/media/...`, `/secure/docs/...`) НЕ меняются: загрузка по-прежнему
возвращает стабильный app-URL (см. services.public_media_url/secure_docs_url), а в S3-режиме
эти маршруты просто редиректят на подписанный (presigned) URL объекта. Так сохраняются:
валидация voice_url в чате, стабильные ссылки в БД и авторизация на /secure/docs.
"""
from __future__ import annotations

import os
from abc import ABC, abstractmethod

from .config import settings
from .services import MEDIA_DIR, PRIVATE_DIR, public_media_url, secure_docs_url

# Первый сегмент ключа, который считается приватным (лежит вне публичного /media).
PRIVATE_AREAS = {"docs"}


class StorageError(Exception):
    """Хранилище недоступно (напр. S3 отвалился/таймаут). Роутеры ловят и отдают мягкую 503,
    а не 500 — чтобы сбой провайдера не выглядел как краш при отправке фото/голоса/документов."""


def _sanitize(key: str) -> list[str]:
    """Ключ → безопасные сегменты пути (анти path-traversal). Пустое/'.'/'..' выкидываем."""
    parts = [p for p in (key or "").split("/") if p not in ("", ".", "..")]
    if not parts:
        raise ValueError("Пустой ключ хранилища")
    return parts


def _is_private(key: str) -> bool:
    return _sanitize(key)[0] in PRIVATE_AREAS


class Storage(ABC):
    """Единый контракт хранилища. Реализации: LocalStorage, S3Storage."""

    #: True — файлы лежат в облаке (нужен редирект на presigned URL при отдаче).
    is_remote: bool = False

    @abstractmethod
    def save(self, key: str, data: bytes) -> None:
        """Записать байты по ключу (перезаписывает)."""

    @abstractmethod
    def load(self, key: str) -> bytes:
        """Прочитать байты. Нет объекта → FileNotFoundError."""

    @abstractmethod
    def exists(self, key: str) -> bool:
        ...

    @abstractmethod
    def delete(self, key: str) -> None:
        """Удалить (best-effort: отсутствие объекта — не ошибка)."""

    @abstractmethod
    def url(self, key: str) -> str:
        """URL, по которому клиент реально забирает файл.

        Локально — стабильный app-URL (его отдаёт StaticFiles/FileResponse).
        S3 — подписанный (presigned) URL объекта с коротким TTL.
        """


class LocalStorage(Storage):
    """Диск сервера (поведение как до абстракции). Публичные ключи → MEDIA_DIR,
    приватные (`docs/`) → PRIVATE_DIR."""

    is_remote = False

    def _path(self, key: str) -> str:
        parts = _sanitize(key)
        base = PRIVATE_DIR if parts[0] in PRIVATE_AREAS else MEDIA_DIR
        return os.path.join(base, *parts)

    def save(self, key: str, data: bytes) -> None:
        path = self._path(key)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)

    def load(self, key: str) -> bytes:
        with open(self._path(key), "rb") as f:
            return f.read()

    def exists(self, key: str) -> bool:
        return os.path.isfile(self._path(key))

    def delete(self, key: str) -> None:
        try:
            path = self._path(key)
        except ValueError:
            return
        try:
            if os.path.isfile(path):
                os.remove(path)
        except OSError:
            pass

    def url(self, key: str) -> str:
        # Локально файлы отдаёт StaticFiles(/media) и /secure/docs — этот метод для симметрии.
        if _is_private(key):
            return secure_docs_url(_sanitize(key)[-1])
        return public_media_url("/".join(_sanitize(key)))


class S3Storage(Storage):
    """S3-совместимое хранилище (boto3). Endpoint/регион/бакет/ключи — из .env.

    `client` можно передать явно (для тестов — мок boto3-клиента, реального S3 в CI нет).
    По умолчанию собираем boto3-клиент лениво из настроек."""

    is_remote = True

    def __init__(
        self,
        *,
        bucket: str,
        client=None,
        endpoint_url: str = "",
        region: str = "",
        access_key: str = "",
        secret_key: str = "",
        signed_ttl: int = 3600,
    ) -> None:
        if not bucket:
            raise ValueError("S3Storage: не задан бакет (S3_BUCKET)")
        self.bucket = bucket
        self.signed_ttl = signed_ttl
        if client is None:
            import boto3  # ленивый импорт: без S3-конфига boto3 на рантайме не нужен
            client = boto3.client(
                "s3",
                endpoint_url=endpoint_url or None,
                region_name=region or None,
                aws_access_key_id=access_key or None,
                aws_secret_access_key=secret_key or None,
            )
        self.client = client

    @staticmethod
    def _key(key: str) -> str:
        return "/".join(_sanitize(key))

    def save(self, key: str, data: bytes) -> None:
        try:
            self.client.put_object(Bucket=self.bucket, Key=self._key(key), Body=data)
        except Exception as e:  # boto3 ClientError/EndpointConnectionError → S3 недоступен, не 500
            raise StorageError(str(e)) from e

    def load(self, key: str) -> bytes:
        try:
            resp = self.client.get_object(Bucket=self.bucket, Key=self._key(key))
        except Exception as e:  # NoSuchKey / ClientError → трактуем как «нет файла»
            if _is_not_found(e):
                raise FileNotFoundError(key) from e
            raise
        return resp["Body"].read()

    def exists(self, key: str) -> bool:
        try:
            self.client.head_object(Bucket=self.bucket, Key=self._key(key))
            return True
        except Exception as e:
            if _is_not_found(e):
                return False
            raise

    def delete(self, key: str) -> None:
        try:
            self.client.delete_object(Bucket=self.bucket, Key=self._key(key))
        except Exception:
            pass  # best-effort, как и на диске

    def url(self, key: str) -> str:
        return self.client.generate_presigned_url(
            "get_object",
            Params={"Bucket": self.bucket, "Key": self._key(key)},
            ExpiresIn=self.signed_ttl,
        )


def _is_not_found(exc: Exception) -> bool:
    """boto3 ClientError с кодом 404/NoSuchKey → объект отсутствует."""
    resp = getattr(exc, "response", None)
    if isinstance(resp, dict):
        err = resp.get("Error", {})
        if str(err.get("Code")) in ("404", "NoSuchKey", "NotFound"):
            return True
        if str(resp.get("ResponseMetadata", {}).get("HTTPStatusCode")) == "404":
            return True
    return False


# ----------------------------- Фабрика (синглтон) -----------------------------
_storage: Storage | None = None


def _build_storage() -> Storage:
    if settings.storage_is_s3:
        return S3Storage(
            bucket=settings.s3_bucket,
            endpoint_url=settings.s3_endpoint_url,
            region=settings.s3_region,
            access_key=settings.s3_access_key,
            secret_key=settings.s3_secret_key,
            signed_ttl=settings.s3_signed_url_ttl,
        )
    return LocalStorage()


def get_storage() -> Storage:
    """Текущее хранилище (по конфигу). Кэшируется на процесс."""
    global _storage
    if _storage is None:
        _storage = _build_storage()
    return _storage


def reset_storage(storage: Storage | None = None) -> None:
    """Сбросить/подменить синглтон (для тестов)."""
    global _storage
    _storage = storage
