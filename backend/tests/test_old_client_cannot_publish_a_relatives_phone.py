"""Старый клиент не может опубликовать телефон близкого в комментарии заявки.

История. Экран «За близкого» в старых клиентах дописывал номер близкого человека прямо в
комментарий заявки, а заявку со всем комментарием видит любой вошедший (лента, карта) —
и она же копией уходит админу в Telegram, если это заявка «нужна помощь». Новый сайт и новое
приложение так больше не делают (PR #168, #172). Но старые копии живут: сайт обновляется,
только когда человек согласится на подсказку (`registerType: "prompt"`), а Android-сборки
29.06–05.07.2026 могут стоять у людей до сих пор.

Значит, сервер не доверяет клиенту (§8): такие комментарии он сам приводит в порядок ДО
записи в базу и до любого уведомления. Форматы старых клиентов — буква в букву:

    сайт, RU:     «Телефон близкого: <номер>[. <заметка>]»
    сайт, BA:     «Яҡындың телефоны: <номер>[. <заметка>]»   (git show 2530a881^:webapp/…/FamilyOrderScreen.tsx)
    Android, RU:  «Заказ за близкого · <номер>»
    Android, BA:  «Яҡын кеше өсөн заказ · <номер>»            (5562cf75, AccessibilityScreens.kt:785)

Номер → нейтральная метка того же языка; заметка остаётся, но через общую мойку
(`scrub_text`); если в заметке после мойки всё ещё 5+ цифр подряд — заметку убираем, оставляем
метку. Заявку НЕ отклоняем: человек хотел как лучше.
"""
from __future__ import annotations

import logging
import re

import pytest

from app.models import UserRole

RU, BA = "Заказ за близкого", "Яҡын кеше өсөн заказ"
# Цифры номера близкого во всех примерах ниже: +7 917 864-20-53. Хвост из семи цифр —
# его не должно остаться нигде: ни в базе, ни в Telegram, ни в логах.
TAIL = "8642053"
# Разделитель между цифрами — любой не-буквенный знак (пробел, неразрывный пробел, тире,
# слэш, перевод строки…): номер пишут как угодно, и проверка не должна быть уже чистки.
_LONG_RUN = re.compile(r"\d(?:[\W_]{0,3}\d){4,}")


def _assert_no_phone_digits(text: str, tail: str = TAIL) -> None:
    text = re.sub(r"#\d+", "#", text or "")   # «Отклики по заявке #57» — номер заявки, не телефон
    assert tail not in re.sub(r"\D", "", text), "цифры номера близкого остались в тексте"
    assert not _LONG_RUN.search(text), "в тексте осталась длинная цепочка цифр"


def _clean():
    """Сама функция чистки. Импорт внутри: до правки модуля нет — тест должен упасть, а не
    сломать сбор всего файла."""
    from app.relative_phone_comment import strip_legacy_relative_phone
    return strip_legacy_relative_phone


@pytest.fixture
def telegram(monkeypatch) -> list[str]:
    """Что ушло бы админу в Telegram (как в test_you_cannot_flood_the_admin.py)."""
    sent: list[str] = []
    monkeypatch.setattr("app.routers.requests.notify_admin_telegram",
                        lambda text, *a, **k: sent.append(text))
    return sent


def _create(client, auth, comment: str, **extra):
    return client.post("/requests", headers=auth, json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
        "for_relative_name": "Мама", "comment": comment, "assisted": True, **extra,
    })


# (что прислал старый клиент, что должно лечь в базу)
LEGACY = [
    # старый сайт, русский
    ("Телефон близкого: +7 917 864-20-53", RU),
    ("Телефон близкого: +7 917 864-20-53. Нужна помощь с сумкой", f"{RU}. Нужна помощь с сумкой"),
    # старый сайт, башкирский
    ("Яҡындың телефоны: 89178642053", BA),
    ("Яҡындың телефоны: 89178642053. Өләсәйгә ярҙам кәрәк", f"{BA}. Өләсәйгә ярҙам кәрәк"),
    # старый Android, русский: заметки по построению не было, но «. » в поле номера возможно
    ("Заказ за близкого · +7 917 864-20-53", RU),
    ("Заказ за близкого · 8 917 864 20 53. Встретить у автовокзала", f"{RU}. Встретить у автовокзала"),
    # старый Android, башкирский
    ("Яҡын кеше өсөн заказ · 8 (917) 864-20-53", BA),
    ("Яҡын кеше өсөн заказ · 8 (917) 864-20-53. Автовокзалда ҡаршы алырға",
     f"{BA}. Автовокзалда ҡаршы алырға"),
]


LEGACY_IDS = ["site-ru", "site-ru+note", "site-ba", "site-ba+note",
              "android-ru", "android-ru+note", "android-ba", "android-ba+note"]


@pytest.mark.parametrize("sent, stored", LEGACY, ids=LEGACY_IDS)
def test_номер_близкого_не_доходит_ни_до_базы_ни_до_админа(client, user_factory, telegram, sent, stored):
    pax = user_factory("СтарыйКлиент")
    r = _create(client, pax["auth"], sent)

    assert r.status_code == 200, r.text            # заявку не отклоняем: человек хотел как лучше
    assert r.json()["comment"] == stored
    mine = client.get("/requests/mine", headers=pax["auth"]).json()
    assert [q["comment"] for q in mine if q["id"] == r.json()["id"]] == [stored]   # в базе — чистое

    assert len(telegram) == 1                       # «нужна помощь» всё так же доходит до админа…
    assert stored in telegram[0]                    # …с нейтральной меткой вместо номера
    _assert_no_phone_digits(telegram[0])


def test_номер_внутри_заметки_моется(client, user_factory, telegram):
    pax = user_factory("СтарыйКлиентЗаметка")
    r = _create(client, pax["auth"],
                "Телефон близкого: +7 917 864-20-53. Если что, звони дочке 8 917 555 12 34")
    assert r.status_code == 200, r.text
    assert r.json()["comment"] == f"{RU}. Если что, звони дочке <телефон>"
    _assert_no_phone_digits(telegram[0])
    assert "5551234" not in re.sub(r"\D", "", telegram[0])


@pytest.mark.parametrize("sent, stored", [
    # Городской номер мойка не узнаёт (нет +7/8 и не с девятки) — 5+ цифр подряд, заметку убираем.
    ("Телефон близкого: +7 917 864-20-53. Домашний 26-18-90, звонить вечером", RU),
    ("Яҡындың телефоны: 89178642053. Өйҙәге телефон 2-64-18-90", BA),
], ids=["ru-landline", "ba-landline"])
def test_если_в_заметке_остались_длинные_цифры_остаётся_одна_метка(client, user_factory, telegram,
                                                                     sent, stored):
    pax = user_factory("СтарыйКлиентЦифры")
    r = _create(client, pax["auth"], sent)
    assert r.status_code == 200, r.text
    assert r.json()["comment"] == stored
    _assert_no_phone_digits(telegram[0])


@pytest.mark.parametrize("sent, stored", [
    # Номер, набранный с «. » внутри: кусок из одних цифр и знаков номера — ещё номер, не заметка.
    ("Телефон близкого: 8. 917 864-20-53. Встретить у подъезда", f"{RU}. Встретить у подъезда"),
    # Хвост из четырёх цифр правило «5+ подряд» не поймало бы — а это цифры номера.
    ("Телефон близкого: 8917864. 2053", RU),
], ids=["dot-then-note", "short-tail"])
def test_номер_с_точкой_внутри_уходит_целиком(client, user_factory, telegram, sent, stored):
    pax = user_factory("СтарыйКлиентТочка")
    r = _create(client, pax["auth"], sent)
    assert r.status_code == 200, r.text
    assert r.json()["comment"] == stored
    assert "2053" not in re.sub(r"\D", "", r.json()["comment"])
    _assert_no_phone_digits(telegram[0])


# Необычные разделители (находка ревью PR #173): общая мойка `scrub_text` знает только пробел,
# дефис, точку и скобки — номер через неразрывный пробел, тире, слэш или перевод строки она
# пропускает. Здесь номер близкого повторён и в заметке: его ловит запасное правило «5+ цифр».
# Саму мойку в этом PR не расширяем — это отдельная задача со своей проверкой ложных срабатываний.
@pytest.mark.parametrize("sent, stored, tail", [
    ("Телефон близкого: 8\xa0937\xa0156\xa023\xa048. Если не дозвонитесь — 8\xa0937\xa0156\xa023\xa048",
     RU, "1562348"),
    ("Яҡындың телефоны: 8–917–765–43–21. Уның номеры 8–917–765–43–21", BA, "7654321"),
    ("Заказ за близкого · 8 917 765 43 21. Её номер:\n8 917\n765 43 21", RU, "7654321"),
    ("Яҡын кеше өсөн заказ · 8/917/765/43/21. Шылтырат: 8/917/765/43/21", BA, "7654321"),
], ids=["nbsp", "en-dash", "newline", "slash"])
def test_номер_через_необычные_разделители_не_проходит(client, user_factory, telegram,
                                                        sent, stored, tail):
    pax = user_factory("СтарыйКлиентРазделитель")
    r = _create(client, pax["auth"], sent)
    assert r.status_code == 200, r.text
    assert r.json()["comment"] == stored
    _assert_no_phone_digits(r.json()["comment"], tail)
    _assert_no_phone_digits(telegram[0], tail)


@pytest.mark.parametrize("sep", ["\xa0", " ", " ", "–", "—", "‑", "−", "－",
                                 "/", ",", "_", "\n", "\t"],
                         ids=["nbsp", "nnbsp", "thin", "en", "em", "nb-hyphen", "minus", "fw-hyphen",
                              "slash", "comma", "underscore", "newline", "tab"])
def test_любой_разделитель_в_заметке_не_спасает_номер(sep):
    clean = _clean()
    num = sep.join(["8", "917", "765", "43", "21"])
    assert clean(f"Телефон близкого: {num}. Её номер {num}") == RU
    # …и номер, разрезанный «. » с хвостом короче пяти цифр, тоже уходит целиком
    assert clean(f"Телефон близкого: 8{sep}917{sep}765. 43{sep}21") == RU


@pytest.mark.parametrize("path", ["patch", "edit"])
def test_правка_заявки_тоже_чистит(client, user_factory, telegram, path):
    """Старый клиент мог дописать номер не при создании, а правкой (PATCH или POST …/edit)."""
    pax = user_factory("СтарыйКлиентПравка")
    r = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "comment": "Нужна машина утром",
    })
    assert r.status_code == 200, r.text
    rid = r.json()["id"]
    legacy = "Яҡындың телефоны: +7 917 864-20-53. Өләсәйгә ярҙам кәрәк"
    if path == "patch":
        e = client.patch(f"/requests/{rid}", headers=pax["auth"], json={"comment": legacy})
    else:
        e = client.post(f"/requests/{rid}/edit", headers=pax["auth"], json={"comment": legacy})
    assert e.status_code == 200, e.text
    assert e.json()["comment"] == f"{BA}. Өләсәйгә ярҙам кәрәк"
    mine = client.get("/requests/mine", headers=pax["auth"]).json()
    assert [q["comment"] for q in mine if q["id"] == rid] == [f"{BA}. Өләсәйгә ярҙам кәрәк"]


def test_заявка_от_админа_по_звонку_тоже_чистится(client, user_factory):
    """Третий путь записи комментария заявки — админ заводит её за человека по телефону."""
    admin = user_factory("АдминЗаявка", role=UserRole.admin)
    r = client.post("/admin/request-for-phone", headers=admin["auth"], json={
        "phone": "+79000000173", "name": "Бабушка", "from_city": "Сибай", "to_city": "Уфа",
        "comment": "Заказ за близкого · +7 917 864-20-53",
    })
    assert r.status_code == 200, r.text
    assert r.json()["comment"] == RU


def test_исходный_комментарий_не_попадает_в_логи(client, user_factory, telegram, caplog):
    pax = user_factory("СтарыйКлиентЛог")
    with caplog.at_level(logging.DEBUG):
        r = _create(client, pax["auth"], "Телефон близкого: +7 917 864-20-53. Нужна помощь")
    assert r.status_code == 200, r.text
    assert TAIL not in re.sub(r"\D", "", caplog.text)


@pytest.mark.parametrize("comment", [
    "Телефон сел, пишите в чат приложения",
    "У мамы нет телефона, встречайте у мечети",
    "Телефон близкого скажу в чате",        # без «: » — это не формат старого сайта
    f"{RU}. Нужна помощь с сумкой",          # так пишет новый сайт — уже чисто
    RU,                                      # так пишет новое приложение
    BA,
], ids=["phone-died", "no-phone", "no-colon", "new-site", "new-app-ru", "new-app-ba"])
def test_просто_слово_телефон_не_трогаем(client, user_factory, telegram, comment):
    pax = user_factory("ПростоТелефон")
    r = _create(client, pax["auth"], comment)
    assert r.status_code == 200, r.text
    assert r.json()["comment"] == comment


# --- сама функция чистки: края, которые через API гонять долго ---

@pytest.mark.parametrize("comment", [
    None, "", "звони 89170000000, поедем",   # обычный номер в комментарии — не её забота (моет лента)
    "телефон близкого: +7 917 864-20-53",    # буква в букву: старый сайт писал с большой
    " Телефон близкого: +7 917 864-20-53",
], ids=["none", "empty", "plain-phone", "lowercase", "leading-space"])
def test_чистка_не_трогает_чужие_форматы(comment):
    assert _clean()(comment) == comment


@pytest.mark.parametrize("sent, _stored", LEGACY, ids=LEGACY_IDS)
def test_чистка_идемпотентна(sent, _stored):
    clean = _clean()
    once = clean(sent)
    assert clean(once) == once


def test_пустая_заметка_и_пустой_номер_дают_метку():
    clean = _clean()
    assert clean("Телефон близкого: ") == RU
    assert clean("Яҡындың телефоны: 8 917 864-20-53.  ") == BA
    assert clean("Заказ за близкого · ") == RU
