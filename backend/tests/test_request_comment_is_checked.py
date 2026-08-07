"""Комментарий заявки проверяется при СОЗДАНИИ, а не только при правке.

Открытые поля проходят `moderate_open_text` (см. `test_open_text_moderated_everywhere.py`):
объявление водителя, комментарий к заказу такси, описание посылки, правка заявки, отклик,
заявка, созданная админом. И один пропуск: сама заявка пассажира при создании.

А комментарий заявки — самое публичное поле попутки: его видит вся лента водителей района.
Без проверки счётчик меток не растёт и в админ-пульс человек не попадает — проверка включалась
только если он потом зайдёт и отредактирует заявку. Метка ничего не режет и не блокирует,
решает человек; но чтобы решить, надо сначала увидеть.
"""
import app.antifraud as af
import app.routers.requests as requests_router


def _watch(monkeypatch):
    """Считаем, какие тексты роутер отдал на проверку (как в общем сторже открытых полей)."""
    seen = []
    real = af.moderate_open_text

    def spy(text, user_id, **kw):
        seen.append(text or "")
        return real(text, user_id, **kw)

    monkeypatch.setattr(requests_router, "moderate_open_text", spy)
    return seen


def test_request_comment_is_checked_on_create(client, user_factory, monkeypatch):
    pax = user_factory("МодЗаявка")
    seen = _watch(monkeypatch)
    text = "мой номер 8 917 000 11 22, заберите у мечети"

    r = client.post("/requests", headers=pax["auth"], json={
        "from_city": "МодГрад", "to_city": "Сибай", "comment": text,
    })
    assert r.status_code == 200, r.text
    assert text in seen, "комментарий заявки ушёл в ленту водителей без проверки"


def test_request_comment_is_not_cut(client, user_factory, monkeypatch):
    """Главное правило модерации Юлдаша: помечаем, но не режем и не роняем сохранение."""
    pax = user_factory("МодЗаявка2")
    text = "звони 89170000000, поедем"

    r = client.post("/requests", headers=pax["auth"], json={
        "from_city": "МодГрад2", "to_city": "Сибай", "comment": text,
    })
    assert r.status_code == 200, r.text
    assert r.json()["comment"] == text
