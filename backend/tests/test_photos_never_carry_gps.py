"""Фото не должно уносить координаты съёмки.

Почему это важнее, чем кажется. Человек фотографирует документы или посылку дома. В EXIF
снимка лежат координаты — то есть его домашний адрес. Фото документов уходит модератору,
фото посылки видит вторая сторона, аватар вообще скачивается по прямой ссылке кем угодно.
Один снимок «для профиля» превращается в «вот где я живу» (аудит 2026-08-08, волна 21).

Метаданные срезаются в ОДНОЙ точке — `services._validate_upload`, и только когда позвали с
`sniff_image=True`. Обе дороги (multipart и base64) сходятся туда же. Это правильно, но
держится на внимании: добавит кто-нибудь новую ручку с фото и не поставит флаг — координаты
поедут, и никто этого не заметит. Ни один тест такого не ловил.

Сторож проверяет две вещи: чистка реально срезает GPS, и ни одна загрузка КАРТИНКИ не идёт
мимо флага.
"""
from __future__ import annotations

import io
import pathlib
import re

import pytest

from app.imagemeta import strip_image_metadata


def _jpeg_with_gps() -> bytes:
    """JPEG с координатами съёмки в EXIF — ровно то, что кладёт телефон.

    Собираем через Pillow (он уже есть в проекте): пишем GPS IFD и сохраняем.
    Сам `strip_image_metadata` намеренно написан БЕЗ Pillow — он режет сегменты руками,
    и проверять его чужой библиотекой правильно: она не разделяет его предположений.
    """
    PILImage = pytest.importorskip("PIL.Image", reason="нет Pillow — собрать фото с EXIF нечем")
    img = PILImage.new("RGB", (8, 8), (120, 200, 160))
    exif = img.getexif()
    gps = {
        1: "N", 2: (52.0, 32.0, 0.0),      # широта — Баймак
        3: "E", 4: (58.0, 19.0, 0.0),      # долгота
    }
    exif[0x8825] = gps                               # GPSInfo IFD
    buf = io.BytesIO()
    img.save(buf, format="JPEG", exif=exif.tobytes())
    return buf.getvalue()


def _has_gps(data: bytes) -> bool:
    from PIL import Image

    with Image.open(io.BytesIO(data)) as im:
        return bool(im.getexif().get_ifd(0x8825))


def test_координаты_съёмки_из_фото_вырезаются():
    data = _jpeg_with_gps()
    assert _has_gps(data), "тест собрал фото БЕЗ GPS — проверять было бы нечего"
    cleaned = strip_image_metadata(data, "jpg")
    assert not _has_gps(cleaned), (
        "координаты съёмки остались в файле — фото документов или посылки унесёт домашний адрес"
    )


def test_чистка_не_портит_саму_картинку():
    """Режем служебные блоки, а не пиксели: фото после чистки должно открываться и не менять
    размер. Иначе «защита приватности» превратилась бы в порчу того, что человек прислал."""
    from PIL import Image

    cleaned = strip_image_metadata(_jpeg_with_gps(), "jpg")
    with Image.open(io.BytesIO(cleaned)) as im:
        assert im.size == (8, 8), "чистка изменила изображение, а должна была тронуть только метаданные"


def test_обработчик_загрузки_реально_зовёт_чистку():
    """Сквозная проверка проводки, а не только самой чистки.

    Нашёл на себе: сломал вызов `strip_image_metadata` ВНУТРИ обработчика загрузки — и оба
    прежних теста остались зелёными. Один проверял функцию напрямую, второй — что флаг
    проставлен в ручках. А между ними, в самом обработчике, дыра никем не закрывалась.
    """
    from app.config import settings
    from app.services import decode_upload_b64
    import base64

    raw = base64.b64encode(_jpeg_with_gps()).decode()
    data, ext = decode_upload_b64(raw, settings.image_ext_set, "jpg", "фото", sniff_image=True)
    assert ext == "jpg"
    assert not _has_gps(data), (
        "обработчик загрузки вернул фото с координатами — значит чистку он не позвал"
    )


def test_ни_одна_загрузка_картинки_не_идёт_мимо_чистки():
    """Сторож на причину: чистка включается флагом, и флаг легко забыть.

    Ищем вызовы `read_upload(...)` / `decode_upload_b64(...)`, где среди разрешённых
    расширений картинки (`image_ext_set`), и требуем `sniff_image=True`. Аудио и документы
    другого рода сюда не попадают — у них EXIF нет.
    """
    app_dir = pathlib.Path(__file__).resolve().parents[1] / "app"
    call = re.compile(r"(read_upload|decode_upload_b64)\((?:[^()]|\([^()]*\))*\)", re.S)
    bad = []
    for path in sorted(app_dir.rglob("*.py")):
        if path.name == "services.py":
            continue    # там сама реализация и внутренняя переброска флага
        src = path.read_text(encoding="utf-8")
        for m in call.finditer(src):
            text = m.group(0)
            if "image_ext_set" not in text:
                continue
            if "sniff_image=True" in text.replace(" ", ""):
                continue
            line = src[: m.start()].count("\n") + 1
            bad.append(f"{path.relative_to(app_dir.parent).as_posix()}:{line}  {text[:90]}")
    assert not bad, (
        "загрузка картинки без sniff_image=True — %d шт. Координаты съёмки уедут вместе с фото:\n%s"
        % (len(bad), "\n".join(bad))
    )
