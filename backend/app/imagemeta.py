"""Вырезание метаданных из загруженных фото и голосовых.

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
from datetime import datetime

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


# ---------------------------------------------------------------------------
# Что можно узнать о снимке ДО того, как метаданные срезаны
# ---------------------------------------------------------------------------
# Зачем (фотоконтроль машины, 30.08.2026). Мы просим человека показать машину — и должны
# отличить сегодняшний снимок от прошлогоднего, а фотографию от скриншота. Всё, что об этом
# знает файл, лежит в тех самых метаданных, которые мы через строчку срезаем. Значит,
# посмотреть на них надо РАНЬШЕ: сначала прочитали, потом вырезали. Наружу отдаём три
# безобидные вещи (когда снято, чем снято, размер), координаты не берём даже в память.
#
# Отсутствие даты — НЕ повод не верить человеку: наше приложение пережимает фото перед
# отправкой и метаданные теряет само. Поэтому «даты нет» значит «не знаем», а не «обманул»
# (то же различение, что у ответа государственного реестра: молчание ≠ отказ).


def photo_probe(data: bytes, ext: str) -> dict:
    """Безобидные факты о снимке: когда снят, чем снят, какого размера.

    Возврат: {"shot_at": datetime|None, "software": str, "make": str,
              "width": int|None, "height": int|None}. Ничего не разобрали — все поля пустые.

    Время из EXIF — по часам ТЕЛЕФОНА, без пояса. Поэтому сравнивать его можно только
    сутками («снято на неделе»), а не часами: иначе пояс превратится в обвинение.
    """
    из_коробки = {"shot_at": None, "software": "", "make": "", "width": None, "height": None}
    try:
        import io as _io
        from PIL import Image
    except Exception:  # noqa: BLE001 — нет Pillow: просто ничего не знаем о снимке
        return из_коробки
    try:
        with Image.open(_io.BytesIO(data)) as img:
            из_коробки["width"], из_коробки["height"] = img.size
            try:
                exif = img.getexif()
            except Exception:  # noqa: BLE001 — EXIF битый: размер уже узнали, и хватит
                return из_коробки
            из_коробки["software"] = str(exif.get(305) or "")[:80]     # 305 = Software
            из_коробки["make"] = str(exif.get(271) or "")[:80]         # 271 = Make (производитель)
            сырое = None
            try:
                подраздел = exif.get_ifd(0x8769)                        # ExifIFD
                сырое = подраздел.get(36867) or подраздел.get(36868)    # DateTimeOriginal/Digitized
            except Exception:  # noqa: BLE001 — подраздела нет, возьмём общую дату файла
                сырое = None
            сырое = сырое or exif.get(306)                              # 306 = DateTime
            из_коробки["shot_at"] = _exif_datetime(сырое)
    except Exception:  # noqa: BLE001 — не картинка или не разобрали: не знаем ничего
        return из_коробки
    return из_коробки


def _exif_datetime(raw) -> "datetime | None":
    """«2026:08:30 14:02:11» → datetime. Кривое значение = отсутствующее."""
    if not raw:
        return None
    text = str(raw).strip()
    for формат, длина in (("%Y:%m:%d %H:%M:%S", 19), ("%Y-%m-%d %H:%M:%S", 19),
                          ("%Y:%m:%d", 10), ("%Y-%m-%d", 10)):
        try:
            return datetime.strptime(text[:длина], формат)
        except ValueError:
            continue
    return None


# ---------------------------------------------------------------------------
# Голосовые: место записи внутри звукового файла
# ---------------------------------------------------------------------------
# Зачем (аудит 2026-08-08, волна 142). У фото метаданные срезаются с волны 21, а голосовые
# всё это время сохранялись байт-в-байт. Проверено пробой: файл с координатами внутри доехал
# до сервера целиком и отдавался ГОСТЮ, без всякого входа, — голосовые лежат по публичной
# ссылке, чтобы собеседник мог их послушать.
#
# Телефоны действительно пишут туда место: в контейнере MP4/M4A для этого есть отдельные
# поля (`©xyz` в формате ISO 6709, `loci`, `gps `). Женщина записала голосовое дома —
# и адрес дома уехал вместе со звуком.
#
# Как режем. Тип атома заменяем на `free` — это «пустое место», которое проигрыватели
# пропускают. Размеры и структура файла остаются прежними, звук не трогаем: перекодировать
# ради чистки было бы дороже и рискованнее, чем сама задача.
_ЗНАК = bytes([0xA9])          # тот самый «©» в имени атома — один байт, не UTF-8
_MP4_GEO_ATOMS = {_ЗНАК + b"xyz", _ЗНАК + b"loc", b"loci", b"gps "}
_MP4_FREE = b"free"

# ID3-тег в MP3: кадр GEOB и «Ownership»/пользовательские кадры сюда не лезем — там место
# записи телефоны не пишут. Для MP3/OGG/WAV ограничиваемся тем, что уже даёт формат: наши
# записи делаются приложением, а не импортируются из чужих файлов.


def _strip_mp4_geo(data: bytes) -> bytes:
    """Обезвредить геометки в MP4/M4A. Ходим по дереву атомов, тип геоатома → `free`."""
    out = bytearray(data)
    n = len(data)

    def обойти(начало: int, конец: int, глубина: int = 0) -> None:
        i = начало
        while i + 8 <= конец and глубина < 6:
            size = struct.unpack(">I", data[i:i + 4])[0]
            atom = data[i + 4:i + 8]
            if size == 1:                     # 64-битный размер лежит следующими 8 байтами
                if i + 16 > конец:
                    return
                size = struct.unpack(">Q", data[i + 8:i + 16])[0]
                тело = i + 16
            elif size == 0:                   # «до конца файла»
                size = конец - i
                тело = i + 8
            else:
                тело = i + 8
            if size < 8 or i + size > конец:
                return                        # битая длина — дальше не идём, файл не портим
            if atom in _MP4_GEO_ATOMS:
                out[i + 4:i + 8] = _MP4_FREE
            elif atom in (b"moov", b"udta", b"meta", b"ilst", b"trak", b"mdia", b"minf"):
                смещение = 4 if atom == b"meta" else 0   # у meta есть 4 байта версии/флагов
                обойти(тело + смещение, i + size, глубина + 1)
            i += size

    обойти(0, n)
    return bytes(out)


_AUDIO_STRIPPERS = {"m4a": _strip_mp4_geo, "mp4": _strip_mp4_geo, "aac": _strip_mp4_geo}


def strip_audio_metadata(data: bytes, ext: str) -> bytes:
    """Голосовое без места записи. Звук не трогаем.

    Неизвестный формат или неожиданная структура → возвращаем исходные байты: голосовое
    сообщение важнее идеальной чистки, а сломанный файл человек уже не переслушает.
    """
    fn = _AUDIO_STRIPPERS.get((ext or "").lower())
    if fn is None:
        return data
    try:
        cleaned = fn(data)
    except Exception:  # noqa: BLE001 — кривой файл не должен ронять отправку голосового
        return data
    return cleaned if cleaned and len(cleaned) == len(data) else data
