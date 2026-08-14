"""Вырезание метаданных из загруженных фото.

Зачем. Телефон пишет в снимок EXIF, а там — координаты съёмки. Женщина фотографируется
дома и ставит фото в профиль; фото профиля видно КАЖДОМУ, кто открыл карточку поездки,
и скачивается по прямой ссылке без всякого токена. Проверено пробой (аудит 2026-08-08,
волна 21): файл возвращался байт-в-байт, GPS-тег на месте — то есть адрес дома человека
лежал в открытом доступе. То же и с фото посылки, снятым у подъезда, и с фото-доказательством
в споре.

Почему на сервере, а не в приложении. Наш Android пережимает фото через Bitmap, и метаданные
там теряются сами. Но клиент — не источник правды: веб-версия (`webapp/`) шлёт файл как есть
(`form.append("file", file)`), старые установленные сборки могут слать что угодно, а к API
можно прийти и напрямую. Ровно та же логика, по которой «Только женщины» стало правилом
сервера, а не экрана.

Почему без Pillow. Задача узкая: не перекодировать картинку, а срезать служебные блоки.
Форматов ровно три (`allowed_image_ext` = jpg/png/webp), структура у всех простая, и
разбор укладывается в сотню строк — это дешевле новой тяжёлой зависимости в проде.
Пиксели не трогаем: качество фото остаётся ровно тем, что прислал человек.
"""
from __future__ import annotations

import struct

# Служебные блоки, которые вырезаем.
#
# JPEG: APP1..APP15 — это EXIF (в т.ч. GPS), XMP, IPTC, профили; COM — комментарий.
# APP0 (JFIF) оставляем: он описывает плотность пикселей и ничего личного не несёт,
# а некоторые старые декодеры на его отсутствие обижаются.
_JPEG_DROP = {0xFFFE} | {0xFFE0 + i for i in range(1, 16)}
_JPEG_SOS = 0xFFDA          # начало сжатых данных: дальше сегментов нет
_JPEG_STANDALONE = {0xFFD8, 0xFFD9} | {0xFFD0 + i for i in range(8)}   # без длины

# PNG: текстовые чанки и eXIf. tIME — время правки файла, тоже лишнее.
_PNG_DROP = {b"eXIf", b"tEXt", b"zTXt", b"iTXt", b"tIME"}

# WEBP: EXIF- и XMP-чанки внутри RIFF.
_WEBP_DROP = {b"EXIF", b"XMP "}


def _strip_jpeg(data: bytes) -> bytes:
    out = bytearray(data[:2])            # SOI
    i = 2
    n = len(data)
    while i + 1 < n:
        if data[i] != 0xFF:              # рассинхрон — дальше не разбираем, копируем как есть
            out += data[i:]
            return bytes(out)
        marker = struct.unpack(">H", data[i:i + 2])[0]
        if marker in _JPEG_STANDALONE:
            out += data[i:i + 2]
            i += 2
            continue
        if i + 4 > n:
            break
        seg_len = struct.unpack(">H", data[i + 2:i + 4])[0]
        if seg_len < 2 or i + 2 + seg_len > n:
            out += data[i:]              # битая длина — не портим файл, отдаём остаток
            return bytes(out)
        if marker == _JPEG_SOS:
            out += data[i:]              # SOS и всё после — сжатые данные
            return bytes(out)
        if marker not in _JPEG_DROP:
            out += data[i:i + 2 + seg_len]
        i += 2 + seg_len
    return bytes(out)


def _strip_png(data: bytes) -> bytes:
    out = bytearray(data[:8])            # сигнатура
    i = 8
    n = len(data)
    while i + 8 <= n:
        length = struct.unpack(">I", data[i:i + 4])[0]
        ctype = data[i + 4:i + 8]
        end = i + 12 + length            # длина + тип + данные + CRC
        if length > n or end > n:
            out += data[i:]              # битый чанк — оставляем хвост нетронутым
            return bytes(out)
        if ctype not in _PNG_DROP:
            out += data[i:end]
        i = end
        if ctype == b"IEND":
            break
    return bytes(out)


def _strip_webp(data: bytes) -> bytes:
    out = bytearray(data[:12])           # RIFF + размер + WEBP
    i = 12
    n = len(data)
    while i + 8 <= n:
        ctype = data[i:i + 4]
        size = struct.unpack("<I", data[i + 4:i + 8])[0]
        end = i + 8 + size + (size & 1)  # чанки выровнены по чётной границе
        if size > n or end > n:
            out += data[i:]
            return bytes(out)
        if ctype not in _WEBP_DROP:
            out += data[i:end]
        i = end
    out[4:8] = struct.pack("<I", len(out) - 8)   # размер RIFF пересчитан
    return bytes(out)


def _blind_gps_pointer(data: bytes) -> bytes:
    """Страховка: если EXIF всё-таки уцелел (нестандартная упаковка), обезвредить ссылку на GPS.

    Меняем НОМЕР тега 0x8825 (GPSInfo) в корневом IFD на 0x0000 — читалки пропускают
    неизвестный тег, длина файла не меняется, картинка остаётся валидной. Это последний
    рубеж, а не основной способ: основной — вырезать блок целиком, выше.
    """
    at = data.find(b"Exif\x00\x00")
    if at < 0:
        return data
    tiff = at + 6
    head = data[tiff:tiff + 8]
    if len(head) < 8 or head[:2] not in (b"MM", b"II"):
        return data
    be = head[:2] == b"MM"
    end = ">" if be else "<"
    ifd0 = tiff + struct.unpack(end + "I", head[4:8])[0]
    if ifd0 + 2 > len(data):
        return data
    count = struct.unpack(end + "H", data[ifd0:ifd0 + 2])[0]
    out = bytearray(data)
    for k in range(count):
        pos = ifd0 + 2 + k * 12
        if pos + 2 > len(data):
            break
        if struct.unpack(end + "H", data[pos:pos + 2])[0] == 0x8825:
            out[pos:pos + 2] = struct.pack(end + "H", 0x0000)
    return bytes(out)


_STRIPPERS = {"jpg": _strip_jpeg, "jpeg": _strip_jpeg, "png": _strip_png, "webp": _strip_webp}


def strip_image_metadata(data: bytes, ext: str) -> bytes:
    """Фото без служебных блоков (EXIF/GPS, XMP, комментарии). Пиксели не трогаем.

    Неизвестный формат или неожиданная структура → возвращаем исходные байты: загрузка
    фото важнее идеальной чистки, а всё, что мы принимаем, покрыто разборщиками выше.
    """
    fn = _STRIPPERS.get((ext or "").lower())
    if fn is None:
        return data
    try:
        cleaned = fn(data)
    except Exception:  # noqa: BLE001 — кривой файл не должен ронять загрузку
        return data
    if not cleaned or len(cleaned) > len(data):
        return data                       # что-то пошло не так — отдаём как было
    return _blind_gps_pointer(cleaned)


# Длинная сторона и качество, до которых ужимаем картинки на сервере.
# 1600 — та же планка, что у документов в приложении (волна 88): номер и даты в правах
# читаются, а вес падает в разы.
SHRINK_SIDE = 1600
SHRINK_QUALITY = 85


def shrink_image(data: bytes, ext: str) -> bytes:
    """Ужать картинку, если она больше нужного. Не смогли — возвращаем как есть.

    Зачем (аудит 2026-08-08, волна 97). Сервер принимал и отдавал фото ровно такими, какими
    их прислали, — до десяти мегабайт. Наше приложение жмёт картинки само (волна 88), но
    правило, которое живёт только в клиенте, защищает лишь тех, кто обновился: веб-версия,
    старая сборка и прямой вызов API шлют файл как есть. Дальше этот файл едет в ленту
    и в чат — то есть его качает каждый, кто открыл экран, а не только автор.

    Работаем мягко: нет Pillow, битый файл, неизвестный формат — отдаём исходные байты.
    Картинка важнее идеального веса, и загрузка не должна падать из-за пережатия.
    """
    fmt = {"jpg": "JPEG", "jpeg": "JPEG", "png": "PNG", "webp": "WEBP"}.get((ext or "").lower())
    if fmt is None:
        return data
    try:
        import io as _io

        from PIL import Image
    except Exception:  # noqa: BLE001 — Pillow нет в окружении: работаем как раньше
        return data
    try:
        with Image.open(_io.BytesIO(data)) as img:
            img.load()
            if max(img.size) <= SHRINK_SIDE and len(data) <= 1_000_000:
                return data          # уже маленькая — не трогаем пиксели зря
            img.thumbnail((SHRINK_SIDE, SHRINK_SIDE))
            if fmt == "JPEG" and img.mode not in ("RGB", "L"):
                img = img.convert("RGB")
            buf = _io.BytesIO()
            save_kwargs = {"quality": SHRINK_QUALITY, "optimize": True} if fmt in ("JPEG", "WEBP") else {"optimize": True}
            img.save(buf, format=fmt, **save_kwargs)
            out = buf.getvalue()
        return out if 0 < len(out) < len(data) else data
    except Exception:  # noqa: BLE001 — не разобрали картинку: пусть едет как есть
        return data
