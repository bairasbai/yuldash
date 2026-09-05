"""SOS не обещает того, чего не будет: сколько близких реально получили SMS.

    Поле называется `contacts_notified` (так его назвал main; в ветке-источнике
    было короткое `notified`). Смысл тот же: скольким SMS реально уйдёт сейчас.

Экран обещал «SMS твоим доверенным контактам» безусловно. У нового человека список пуст —
сигнал уходил только дежурному, а узнавал он об этом в беде. Теперь сервер отдаёт два числа:
скольким ушла SMS (`notified`) и сколько контактов заведено (`contacts_total`), и приложение
может сказать правду ДО нажатия и после (аудит 2026-09-02).
"""
from app.models import UserRole

from test_flows import _publish, _book


def _add_contact(client, user, phone="+79990001122"):
    r = client.post("/trusted-contacts", headers=user["auth"],
                    json={"name": "Мама", "relation": "мама", "phone": phone, "notify_by_default": True})
    assert r.status_code in (200, 201), r.text


def test_sos_without_contacts_says_zero(client, user_factory):
    user = user_factory("SosNoContacts")
    r = client.post("/sos", headers=user["auth"], json={"category": "other", "note": "проверка"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["contacts_total"] == 0     # слать некому — экран об этом скажет
    assert body["contacts_notified"] == 0
    assert body["id"] > 0                  # событие всё равно записано: дежурный видит сигнал


def test_sos_counts_only_what_the_channel_will_deliver(client, user_factory):
    """Контракт main строже, чем «сколько контактов заведено».

    `contacts_notified` — это число, которое человек ПРОЧИТАЕТ в беде, поэтому оно считает
    доставку, а не намерение: пока канал SMS молчит (нет провайдера, мок в тестах), там
    ноль, даже если контактов двое. Ветка-источник считала контакты и обещала больше, чем
    сбудется — ровно та неправда, ради ухода от которой поле и заводили.

    Отдельным полем приходит и подсказка человеку: «SMS сейчас не уходит, позвони сама».
    """
    from app.services import sms_channel_live

    user = user_factory("SosWithContacts")
    _add_contact(client, user)
    _add_contact(client, user, phone="+79990001133")
    body = client.post("/sos", headers=user["auth"], json={"category": "medical"}).json()

    assert body["contacts_total"] == 2, "заведённые контакты не посчитаны"
    if sms_channel_live():
        assert body["contacts_notified"] == 2, "канал живой, а SMS никому не ушла"
    else:
        assert body["contacts_notified"] == 0, (
            "канал SMS молчит, а приложение обещает доставку — человек в беде "
            "решит, что близкие уже знают"
        )
        assert body["hint_ru"], "молчащий канал — и ни слова человеку, что звонить надо самой"


def test_roadside_reports_contacts_too(client, user_factory):
    """«Застрял на трассе» обещает то же самое — и считает так же честно."""
    from app.services import sms_channel_live

    driver = user_factory("StuckDriver", role=UserRole.driver)
    pax = user_factory("StuckPax")
    ride = _publish(client, driver)
    booking = _book(client, pax, ride["id"])
    ожидаемо = 1 if sms_channel_live() else 0

    body = client.post(f"/bookings/{booking['id']}/stuck", headers=pax["auth"],
                       json={"note": "колесо", "lat": 52.6, "lng": 58.3}).json()
    assert body["contacts_total"] == 0
    assert body["contacts_notified"] == 0

    _add_contact(client, pax)
    body2 = client.post(f"/bookings/{booking['id']}/stuck", headers=pax["auth"],
                        json={"note": "колесо", "lat": 52.6, "lng": 58.3}).json()
    assert body2["contacts_total"] == 1, "контакт заведён, а ручка про него не знает"
    assert body2["contacts_notified"] == ожидаемо, (
        "число уведомлённых разошлось с тем, что реально сделает канал SMS"
    )
