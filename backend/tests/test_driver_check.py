"""Unit tests for driver document autocheck without external OCR calls."""

import httpx
import pytest

from app.config import settings
from app import driver_check
from app import storage as storage_mod


@pytest.fixture(autouse=True)
def _reset_storage_singleton():
    """Тесты подменяют корень хранилища — вернём синглтон в исходное после каждого."""
    yield
    storage_mod.reset_storage(None)


class FakeResponse:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self.payload = payload

    def json(self):
        return self.payload


def _doc(tmp_path, monkeypatch, name="license.jpg", content=b"fake image"):
    # _doc_path (локальный резолвер) читает DOC_DIR/<name> напрямую.
    monkeypatch.setattr(driver_check, "DOC_DIR", str(tmp_path))
    # check_driver_docs теперь читает байты через storage → ключ docs/<name> из PRIVATE_DIR/docs.
    monkeypatch.setattr(storage_mod, "PRIVATE_DIR", str(tmp_path))
    storage_mod.reset_storage(storage_mod.LocalStorage())
    (tmp_path / "docs").mkdir(exist_ok=True)
    (tmp_path / "docs" / name).write_bytes(content)
    path = tmp_path / name
    path.write_bytes(content)
    return path


def test_doc_path_uses_basename_and_requires_existing_file(tmp_path, monkeypatch):
    path = _doc(tmp_path, monkeypatch, name="safe.jpg")
    assert driver_check._doc_path("https://example.test/secure/docs/safe.jpg?token=1") == str(path)
    assert driver_check._doc_path("https://example.test/secure/docs/../safe.jpg") == str(path)
    assert driver_check._doc_path("") is None
    assert driver_check._doc_path("https://example.test/secure/docs/missing.jpg") is None


def test_extract_full_text_supports_full_text_lines_and_words():
    assert driver_check._extract_full_text({"result": {"textAnnotation": {"fullText": "hello"}}}) == "hello"
    assert driver_check._extract_full_text({
        "result": {
            "textAnnotation": {
                "blocks": [
                    {"lines": [{"text": "line one"}, {"words": [{"text": "line"}, {"text": "two"}]}]},
                ],
            },
        },
    }) == "line one line two"
    assert driver_check._extract_full_text({"result": {"textAnnotation": {}}}) is None
    assert driver_check._extract_full_text([]) is None


def test_ocr_text_handles_supported_unsupported_and_error_paths(monkeypatch):
    old_key, old_folder = settings.yandex_vision_key, settings.yandex_vision_folder_id
    settings.yandex_vision_key = ""
    settings.yandex_vision_folder_id = ""
    try:
        assert driver_check._ocr_text(b"image", "jpg") is None

        settings.yandex_vision_key = "key"
        assert driver_check._ocr_text(b"image", "webp") is None

        captured = {}

        def ok_post(url, headers, json, timeout):
            captured["url"] = url
            captured["headers"] = headers
            captured["json"] = json
            captured["timeout"] = timeout
            return FakeResponse(200, {"result": {"textAnnotation": {"fullText": "recognized text"}}})

        settings.yandex_vision_folder_id = "folder"
        monkeypatch.setattr(httpx, "post", ok_post)
        assert driver_check._ocr_text(b"image", "png") == "recognized text"
        assert captured["headers"]["Authorization"] == "Api-Key key"
        assert captured["headers"]["x-data-logging-enabled"] == "false"
        assert captured["headers"]["x-folder-id"] == "folder"
        assert captured["json"]["mimeType"] == "PNG"

        monkeypatch.setattr(httpx, "post", lambda *_args, **_kwargs: FakeResponse(500, {}))
        assert driver_check._ocr_text(b"image", "jpg") is None

        def boom(*_args, **_kwargs):
            raise RuntimeError("network")

        monkeypatch.setattr(httpx, "post", boom)
        assert driver_check._ocr_text(b"image", "jpg") is None
    finally:
        settings.yandex_vision_key = old_key
        settings.yandex_vision_folder_id = old_folder


def test_parse_license_extracts_number_dates_and_ignores_invalid_dates():
    parsed = driver_check._parse_license("License 12 34 567890 valid 01.01.2020 to 31.12.2099 bad 99.99.9999")
    assert parsed["license_number"] == "1234567890"
    assert parsed["expiry"] == "2099-12-31"
    assert "2020-01-01" in parsed["dates_found"]
    assert "2099-12-31" in parsed["dates_found"]


def test_check_driver_docs_result_matrix(tmp_path, monkeypatch):
    _doc(tmp_path, monkeypatch)
    assert driver_check.check_driver_docs("", "") == {
        "result": "error",
        "score": 0.0,
        "data": {"reasons": ["doc_not_found"]},
    }

    monkeypatch.setattr(driver_check, "_ocr_text", lambda _bytes, _ext: None)
    unavailable = driver_check.check_driver_docs("license.jpg", "")
    assert unavailable["result"] == "needs_human"
    assert unavailable["data"]["reasons"] == ["ocr_unavailable"]

    monkeypatch.setattr(driver_check, "_ocr_text", lambda _bytes, _ext: "short")
    rejected = driver_check.check_driver_docs("license.jpg", "")
    assert rejected["result"] == "reject"
    assert rejected["data"]["reasons"] == ["not_a_license"]

    monkeypatch.setattr(
        driver_check,
        "_ocr_text",
        lambda _bytes, _ext: "Driver license 12 34 567890 issued 01.01.2010 expires 01.01.2000",
    )
    expired = driver_check.check_driver_docs("license.jpg", "")
    assert expired["result"] == "needs_human"
    assert "license_expired" in expired["data"]["reasons"]

    monkeypatch.setattr(
        driver_check,
        "_ocr_text",
        lambda _bytes, _ext: "Driver license 12 34 567890 issued 01.01.2020 expires 31.12.2099",
    )
    passed = driver_check.check_driver_docs("license.jpg", "")
    assert passed["result"] == "pass"
    assert passed["score"] == 1.0
    assert passed["data"]["reasons"] == ["ok"]

    monkeypatch.setattr(
        driver_check,
        "_ocr_text",
        lambda _bytes, _ext: "Driver license without number but valid until 31.12.2099",
    )
    manual = driver_check.check_driver_docs("license.jpg", "")
    assert manual["result"] == "needs_human"
    assert "no_license_number" in manual["data"]["reasons"]
