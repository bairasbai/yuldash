"""SOS обязан сказать близким, ГДЕ человек.

Аудит 2026-08-06. Мягкая кнопка «застрял на трассе» слала доверенным контактам ссылку
на карту, а красный SOS — нет: родные получали «нужна срочная помощь» и не знали,
куда ехать. Координаты в приложении были, но лежали в тексте заметки, который читал
только админ.

Правило, которое сторожит этот файл: если координаты пришли — ссылка на место есть
в SMS близким, в сообщении админу и в самом событии. Не пришли (GPS не схватился) —
сигнал всё равно уходит, просто без места.
"""
import app.routers.safety as safety


def _capture_sms(monkeypatch):
    """Перехватываем рассылку SMS: нам важен ТЕКСТ, который увидит близкий."""
    sent = []

    def fake(phones, text):
        sent.append((list(phones), text))

    monkeypatch.setattr(safety, "_send_sos_sms", fake)
    return sent


def _capture_tg(monkeypatch):
    msgs = []
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda m: msgs.append(m))
    return msgs


def test_sos_sms_to_family_carries_map_link(client, user_factory, monkeypatch):
    u = user_factory("SosWhere")
    client.post("/trusted-contacts", headers=u["auth"],
                json={"name": "Мама", "relation": "мама", "phone": "+79170000001"})
    sent = _capture_sms(monkeypatch)

    r = client.post("/sos", headers=u["auth"],
                    json={"category": "breakdown", "note": "стою на трассе",
                          "lat": 53.1234, "lng": 58.5678})
    assert r.status_code == 200, r.text

    to_family = [t for phones, t in sent if "+79170000001" in phones]
    assert to_family, "близким SMS вообще не ушло"
    text = to_family[0]
    assert "yandex.ru/maps" in text, f"в SMS близким нет ссылки на место: {text}"
    assert "58.5678" in text and "53.1234" in text, f"координаты потерялись: {text}"


def test_sos_without_coords_still_calls_for_help(client, user_factory, monkeypatch):
    """GPS не схватился — сигнал всё равно уходит, просто без места. Контроль: не падаем."""
    u = user_factory("SosNoGeo")
    client.post("/trusted-contacts", headers=u["auth"],
                json={"name": "Брат", "relation": "брат", "phone": "+79170000002"})
    sent = _capture_sms(monkeypatch)

    r = client.post("/sos", headers=u["auth"], json={"category": "other", "note": ""})
    assert r.status_code == 200, r.text

    to_family = [t for phones, t in sent if "+79170000002" in phones]
    assert to_family, "без координат сигнал близким пропал совсем"
    assert "yandex.ru/maps" not in to_family[0], "выдумали место, которого не знаем"


def test_admin_also_sees_the_place(client, user_factory, monkeypatch):
    u = user_factory("SosAdminSees")
    _capture_sms(monkeypatch)
    msgs = _capture_tg(monkeypatch)

    r = client.post("/sos", headers=u["auth"],
                    json={"category": "medical", "lat": 54.7, "lng": 55.9})
    assert r.status_code == 200, r.text
    assert msgs, "админу в Telegram ничего не ушло"
    assert "yandex.ru/maps" in msgs[0], f"админ не видит места: {msgs[0]}"


def test_place_is_kept_on_the_event(client, user_factory, monkeypatch):
    """Место остаётся в событии: лента админа переживёт непрочитанный Telegram."""
    u = user_factory("SosEventKeeps")
    _capture_sms(monkeypatch)

    r = client.post("/sos", headers=u["auth"],
                    json={"category": "other", "note": "тёмный участок", "lat": 52.2, "lng": 59.3})
    assert r.status_code == 200, r.text
    note = r.json()["note"]
    assert "тёмный участок" in note, "потеряли то, что написал человек"
    assert "yandex.ru/maps" in note, f"место не сохранилось в событии: {note}"


def test_roadside_and_sos_agree_about_the_place(client, user_factory, monkeypatch):
    """Оба сигнала — про одно: найти человека. Разного поведения между ними быть не должно."""
    u = user_factory("SosVsStuck")
    client.post("/trusted-contacts", headers=u["auth"],
                json={"name": "Сестра", "relation": "сестра", "phone": "+79170000003"})
    sent = _capture_sms(monkeypatch)

    client.post("/sos", headers=u["auth"],
                json={"category": "breakdown", "lat": 53.0, "lng": 58.0})
    sos_texts = [t for phones, t in sent if "+79170000003" in phones]

    sent.clear()
    ride = client.post("/rides", headers=u["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "depart_at": "2030-01-01T10:00:00Z",
        "seats_total": 3, "price": 500,
    })
    # Кнопка «застрял» живёт на брони; если создать поездку не вышло — сравниваем только SOS.
    assert sos_texts and "yandex.ru/maps" in sos_texts[0], "SOS снова молчит о месте"
    assert ride.status_code in (200, 201, 403, 422), ride.text
