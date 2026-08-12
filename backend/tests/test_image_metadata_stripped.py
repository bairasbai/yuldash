# -*- coding: utf-8 -*-
"""Метаданные фото: срезаны, а картинка цела.

Откуда взялось. Телефон пишет в снимок координаты съёмки. Фото профиля видно каждому, кто
открыл карточку поездки, и качается по прямой ссылке без токена — то есть адрес дома человека
лежал в открытом доступе (аудит 2026-08-08, волна 21). Проверено пробой: файл возвращался
байт-в-байт вместе с GPS-тегом.

Здесь две половины, и обе обязательны: метаданные должны исчезнуть, а картинка — остаться
валидной. Чистка, которая ломает фото, хуже, чем её отсутствие.
"""
import struct

import pytest

from app.imagemeta import strip_image_metadata
from app.services import _detect_image_ext


def _exif_app1(payload: bytes = b"GPS-\x88\x25-\xde\xad") -> bytes:
    """APP1-сегмент с EXIF-подписью (внутри — что угодно: мы его целиком выкидываем)."""
    body = b"Exif\x00\x00" + payload
    return b"\xff\xe1" + struct.pack(">H", len(body) + 2) + body


def _jpeg(with_exif: bool = True) -> bytes:
    """Минимальный валидный JPEG: SOI, [APP1], DQT, SOF0, SOS + данные, EOI."""
    parts = [b"\xff\xd8"]
    if with_exif:
        parts.append(_exif_app1())
        comment = "комментарий".encode()          # COM-сегмент: тоже метаданные, тоже режем
        parts.append(b"\xff\xfe" + struct.pack(">H", 2 + len(comment)) + comment)
    parts.append(b"\xff\xdb\x00\x43" + bytes(65))                       # DQT
    parts.append(b"\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00")  # SOF0
    parts.append(b"\xff\xda\x00\x08\x01\x01\x00\x00\x3f\x00")           # SOS
    parts.append(b"\x00\x11\x22\x33")                                   # «пиксели»
    parts.append(b"\xff\xd9")                                           # EOI
    return b"".join(parts)


def _png_chunk(ctype: bytes, data: bytes = b"") -> bytes:
    import zlib
    return (struct.pack(">I", len(data)) + ctype + data
            + struct.pack(">I", zlib.crc32(ctype + data) & 0xFFFFFFFF))


def _png(with_meta: bool = True) -> bytes:
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    out = [b"\x89PNG\r\n\x1a\n", _png_chunk(b"IHDR", ihdr)]
    if with_meta:
        out.append(_png_chunk(b"eXIf", b"MM\x00\x2a\x00\x00\x00\x08"))
        out.append(_png_chunk(b"tEXt", b"Comment\x00snyato doma"))
    out.append(_png_chunk(b"IDAT", b"\x78\x9c\x62\x00\x00\x00\x02\x00\x01"))
    out.append(_png_chunk(b"IEND"))
    return b"".join(out)


def _webp_chunk(ctype: bytes, data: bytes) -> bytes:
    pad = b"\x00" if len(data) & 1 else b""
    return ctype + struct.pack("<I", len(data)) + data + pad


def _webp(with_exif: bool = True) -> bytes:
    body = [b"WEBP", _webp_chunk(b"VP8L", b"\x2f\x00\x00\x00\x00\x88\x88\x08")]
    if with_exif:
        body.append(_webp_chunk(b"EXIF", b"Exif\x00\x00MM\x00\x2a\x00\x00\x00\x08"))
        body.append(_webp_chunk(b"XMP ", b"<x:xmpmeta/>"))
    payload = b"".join(body)
    return b"RIFF" + struct.pack("<I", len(payload)) + payload


# ----------------------------- метаданные исчезают -----------------------------
@pytest.mark.parametrize("ext,make", [("jpg", _jpeg), ("png", _png), ("webp", _webp)])
def test_метаданные_срезаются(ext, make):
    cleaned = strip_image_metadata(make(True), ext)
    assert b"Exif\x00\x00" not in cleaned, "EXIF остался — координаты съёмки уедут с фото"
    assert b"snyato doma" not in cleaned
    assert b"xmpmeta" not in cleaned


@pytest.mark.parametrize("ext,make", [("jpg", _jpeg), ("png", _png), ("webp", _webp)])
def test_картинка_остаётся_валидной(ext, make):
    """Вторая половина обещания: чистка не должна ломать фото."""
    cleaned = strip_image_metadata(make(True), ext)
    assert _detect_image_ext(cleaned) == ext, "после чистки файл перестал быть картинкой"
    if ext == "jpg":
        assert cleaned.endswith(b"\xff\xd9"), "потерян конец файла"
        assert b"\xff\xda" in cleaned, "потеряны сами данные картинки (SOS)"
        assert b"\x00\x11\x22\x33" in cleaned, "изменены пиксели — так нельзя"
    if ext == "png":
        assert b"IHDR" in cleaned and b"IDAT" in cleaned and cleaned.endswith(b"IEND\xae\x42\x60\x82")
    if ext == "webp":
        assert b"VP8L" in cleaned
        # Размер в заголовке RIFF обязан совпасть с фактическим, иначе файл битый.
        assert struct.unpack("<I", cleaned[4:8])[0] == len(cleaned) - 8


@pytest.mark.parametrize("ext,make", [("jpg", _jpeg), ("png", _png), ("webp", _webp)])
def test_фото_без_метаданных_не_меняется(ext, make):
    """Чистое фото проходит насквозь: лишних правок не вносим."""
    original = make(False)
    assert strip_image_metadata(original, ext) == original


# ----------------------------- не ломаемся на мусоре -----------------------------
def test_битый_файл_не_роняет_загрузку():
    """Обрезанный/кривой файл — не повод отказать человеку в загрузке."""
    broken = _jpeg()[:14]                       # оборвали посреди сегмента
    assert strip_image_metadata(broken, "jpg")  # что-то вернули, не упали
    assert strip_image_metadata(b"", "jpg") == b""
    assert strip_image_metadata(b"\xff\xd8\xff\xff\xff", "jpg")


def test_чужие_форматы_не_трогаем():
    """Голосовое сообщение (не картинка) через эту дорогу не ходит и меняться не должно."""
    audio = b"OggS\x00\x02" + bytes(40)
    assert strip_image_metadata(audio, "ogg") == audio
