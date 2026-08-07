"""Публичные поля профиля — имя и аватар (аудит 2026-08-07).

Что чинили и почему.

**Имя.** Открытые поля (комментарий заявки, отклик, отзыв, описание посылки, комментарий
заказа такси) проходят `moderate_open_text` с 2026-08-03. Имя — тоже публичное поле, и даже
более заметное: его видно в карточках поездок, в ленте заявок, в откликах, в чате и в
отзывах. Проверку туда просто забыли навесить. Имя «Такси Баймак 8987…» — это объявление
в обход приложения, то есть обход комиссии в Такси и Курьере: ровно то, от чего защищает
проверка остальных полей.

**Аватар.** Сервер принимал ЛЮБУЮ строку как ссылку на фото. Чужой URL подгружался у
каждого, кто видит карточку/чат/отклик с этим человеком, и хозяин чужого сервера собирал
их IP, город и время просмотра. Штатный путь один — `/upload/chat-photo`, он отдаёт ссылку
на наш домен; всё остальное теперь отвергается.

**Автоадмин.** Номера сверялись точной строкой, хотя в базе телефон всегда нормализован
(`+7…`). Запись `ADMIN_PHONES=8987…` молча не срабатывала. Теперь — тем же `_phone_key`,
что и в стартовой проверке конфигурации.
"""
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import OtpCode, User, UserRole
from app.routers.auth import _maybe_promote_admin


def _code_for(phone: str) -> str:
    with Session(engine) as s:
        otp = s.exec(select(OtpCode).where(OtpCode.phone == phone).order_by(OtpCode.id.desc())).first()
        return otp.code


def _login(client, phone="+79990001111", name="Тест"):
    client.post("/auth/request-code", json={"phone": phone})
    r = client.post("/auth/verify", json={"phone": phone, "code": _code_for(phone), "name": name})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _auth(token):
    return {"Authorization": f"Bearer {token}"}


# --- имя: телефон и грубость не проходят -----------------------------------------------------

def test_phone_in_name_is_rejected(client):
    """Главный сценарий: имя как рекламная вывеска с номером — мимо комиссии."""
    token = _login(client)
    r = client.post("/me/update", json={"name": "Такси Баймак 89871234567"}, headers=_auth(token))
    assert r.status_code == 422, r.text
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"], "ошибка обязана быть двуязычной"

    # имя осталось прежним, а не затёрлось наполовину
    assert client.get("/me", headers=_auth(token)).json()["name"] == "Тест"


def test_messenger_handle_in_name_is_rejected(client):
    """Увод в мессенджер — та же дыра, просто без цифр."""
    token = _login(client, phone="+79990001112")
    r = client.post("/me/update", json={"name": "пиши в вотсап @taxi_baymak"}, headers=_auth(token))
    assert r.status_code == 422


def test_normal_name_still_works(client):
    """Сторож не должен мешать обычным именам — в том числе башкирским."""
    token = _login(client, phone="+79990001113")
    for good in ("Азамат", "Гөлнара Ниғмәтуллина", "Александр", "Айгуль С."):
        r = client.post("/me/update", json={"name": good}, headers=_auth(token))
        assert r.status_code == 200, f"{good} → {r.text}"
        assert r.json()["name"] == good


def test_phone_in_name_rejected_at_registration(client):
    """Второй путь к тому же полю: имя задаётся при входе, а не в правке профиля."""
    phone = "+79990001114"
    client.post("/auth/request-code", json={"phone": phone})
    r = client.post("/auth/verify", json={"phone": phone, "code": _code_for(phone),
                                          "name": "Довезу 89871234567"})
    assert r.status_code == 422, r.text


# --- аватар: только наше хранилище -----------------------------------------------------------

def test_foreign_avatar_url_is_rejected(client):
    """Тихая слежка: чужая ссылка грузится у всех, кто видит этого человека."""
    token = _login(client, phone="+79990001115")
    for bad in ("https://tracker.example/pixel.png",
                "http://198.51.100.7/a.jpg",
                "javascript:alert(1)",
                "//evil.example/x.png"):
        r = client.post("/me/update", json={"avatar_url": bad}, headers=_auth(token))
        assert r.status_code == 422, f"{bad} прошёл: {r.text}"
        assert r.json()["detail"]["ba"], "ошибка обязана быть двуязычной"


def test_our_avatar_url_is_accepted(client):
    """Штатный путь (то, что отдаёт /upload/chat-photo) должен работать как раньше."""
    from app.services import public_media_url
    token = _login(client, phone="+79990001116")
    ours = public_media_url("chat/abc123.jpg")
    r = client.post("/me/update", json={"avatar_url": ours}, headers=_auth(token))
    assert r.status_code == 200, r.text
    assert r.json()["avatar_url"] == ours


def test_empty_avatar_url_resets(client):
    """Сброс аватара пустой строкой — не ошибка, это штатное «убрать фото»."""
    token = _login(client, phone="+79990001117")
    assert client.post("/me/update", json={"avatar_url": ""}, headers=_auth(token)).status_code == 200


# --- автоадмин: номер в любом формате --------------------------------------------------------

def test_admin_phone_matches_regardless_of_format(monkeypatch):
    """`ADMIN_PHONES=8987…` в .env и `+7987…` в базе — один и тот же человек."""
    monkeypatch.setattr(settings, "admin_phones", "89871234567")
    with Session(engine) as s:
        user = User(phone="+79871234567", name="Владелец")
        s.add(user)
        s.commit()
        s.refresh(user)
        _maybe_promote_admin(s, user)
        assert user.role == UserRole.admin, "номер в другом формате запер владельца снаружи админки"


def test_stranger_does_not_become_admin(monkeypatch):
    """Обратная сторона: нормализация не должна раздавать роль лишним."""
    monkeypatch.setattr(settings, "admin_phones", "89871234567")
    with Session(engine) as s:
        user = User(phone="+79877654321", name="Чужой")
        s.add(user)
        s.commit()
        s.refresh(user)
        _maybe_promote_admin(s, user)
        assert user.role != UserRole.admin
