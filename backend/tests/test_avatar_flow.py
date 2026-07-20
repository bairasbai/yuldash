"""Баг «фото профиля не сохраняется»: клиент ВСЕГДА помечает загрузку ext=jpg, а телефон
отдаёт png (скриншот) / webp (скачанное). Раньше сервер отвергал их по magic-bytes (400)
→ аватар молча не сохранялся. Фикс: тип берём из содержимого (_detect_image_ext), а не из
заявленного клиентом расширения. Защита (не-картинка → отказ) сохранена.
"""
JPG = bytes.fromhex("ffd8ffe000104a464946000101") + b"\x00" * 24
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 48
WEBP = b"RIFF\x00\x00\x00\x00WEBPVP8 " + b"\x00" * 24
GARBAGE = b"definitely not an image" * 4


def _upload(client, auth, data, ext="jpg"):
    # как реальный клиент: multipart file + поле ext="jpg" ВСЕГДА (в этом и был баг)
    return client.post(
        "/upload/chat-photo", headers=auth,
        files={"file": ("photo.jpg", data, "image/jpeg")}, data={"ext": ext},
    )


def test_png_labeled_jpg_now_saved(client, user_factory):
    u = user_factory("PngUser")
    r = _upload(client, u["auth"], PNG)
    assert r.status_code == 200, r.text
    assert r.json()["url"].endswith(".png")      # сохранено с ВЕРНЫМ типом из содержимого


def test_webp_labeled_jpg_now_saved(client, user_factory):
    u = user_factory("WebpUser")
    r = _upload(client, u["auth"], WEBP)
    assert r.status_code == 200, r.text
    assert r.json()["url"].endswith(".webp")


def test_real_jpeg_still_ok(client, user_factory):
    u = user_factory("JpgUser")
    r = _upload(client, u["auth"], JPG)
    assert r.status_code == 200 and r.json()["url"].endswith(".jpg")


def test_non_image_still_rejected(client, user_factory):
    u = user_factory("BadUser")
    r = _upload(client, u["auth"], GARBAGE)
    assert r.status_code == 400                  # защита от заливки мусора сохранена


def test_avatar_end_to_end_persists(client, user_factory):
    """Полная цепочка: загрузка png-фото → /me/update → /me отдаёт сохранённый avatar_url."""
    u = user_factory("AvatarE2E")
    up = _upload(client, u["auth"], PNG)
    assert up.status_code == 200, up.text
    url = up.json()["url"]
    r = client.post("/me/update", headers=u["auth"], json={"avatar_url": url})
    assert r.status_code == 200 and r.json()["avatar_url"] == url
    me = client.get("/me", headers=u["auth"])
    assert me.status_code == 200 and me.json().get("avatar_url") == url
