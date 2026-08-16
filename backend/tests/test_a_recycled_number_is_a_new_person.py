"""Номер телефона человеку не принадлежит — а вход у нас по нему.

Оператор забирает неиспользуемый номер и через полгода-год продаёт другому. Это не редкость
и не злой умысел: так устроена связь. Дальше новый владелец ставит Юлдаш, входит по SMS —
номер-то его — и получает ЧУЖОЙ аккаунт целиком (аудит 2026-08-08, волна 139).

Проверено пробой. Постороннему доставались: имя прежней хозяйки, её история поездок, переписка
с водителями, доверенные контакты. То есть при беде его сигнал SOS ушёл бы её маме — а мама
получила бы тревогу за незнакомого человека и поехала бы его искать.

Отличить «человек сменил телефон» от «номер перешёл к другому» было нечем: отметки последней
активности у нас не существовало вовсе.

**Что теперь.** Отметка активности пишется при каждой выдаче ключей входа (у активного —
минимум раз в 12 часов). Если аккаунт молчал больше полугода И вход идёт с другого устройства,
номер откручивается от старого аккаунта, а вошедший получает чистый новый.

**Почему нужны оба признака.** Долгое молчание само по себе — это сезонный пассажир: ездил
прошлым летом, вернулся этим. Другое устройство само по себе — обычная смена телефона, их
меняют каждые два-три года. Опасно только сочетание.

**Данные прежнего владельца не удаляются.** Если это всё-таки был он, поездки, отзывы и деньги
целы — доступ вернёт поддержка, а в Центре уведомлений его ждёт объяснение, почему вход
перестал работать. Удалить было бы проще и гораздо хуже: цена ошибки автоматики стала бы
невосполнимой.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.antifraud import PHONE_RECYCLE_DAYS
from app.db import engine
from app.models import Notification, OtpCode, TrustedContact, User, UserRole
from app.timeutil import utcnow

from test_api import _ride

НОМЕР = "+79995550139"


def _войти(client, phone: str, device: str, name: str = ""):
    """Настоящий путь входа: запросить код по SMS и подтвердить его с телефона."""
    client.post("/auth/request-code", json={"phone": phone})
    with Session(engine) as s:
        otp = s.exec(select(OtpCode).where(OtpCode.phone == phone)
                     .order_by(OtpCode.id.desc())).first()
        код = otp.code if otp else None
    r = client.post("/auth/verify", json={"phone": phone, "code": код, "name": name},
                    headers={"X-Device-Id": device})
    assert r.status_code == 200, r.text
    return r.json()


def _молчал(user_id: int, дней: int) -> None:
    with Session(engine) as s:
        u = s.get(User, user_id)
        u.created_at = u.last_seen_at = utcnow() - timedelta(days=дней)
        s.add(u)
        s.commit()


@pytest.fixture
def прежняя_хозяйка(client, user_factory):
    """Гульнара: год назад ездила, копила историю и вписала маму как доверенный контакт."""
    водитель = user_factory("НомерВодитель", role=UserRole.driver)
    вход = _войти(client, НОМЕР, "device-gulnara", name="Гульнара")
    токен = {"Authorization": f"Bearer {вход['access_token']}"}
    uid = вход["user"]["id"]

    ride_id = _ride(client, водитель, comment="прошлым летом")
    bid = client.post("/bookings", headers=токен,
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    client.post(f"/bookings/{bid}/messages", headers=токен, json={"text": "буду у школы"})
    client.post("/trusted-contacts", headers=токен,
                json={"name": "Мама", "phone": "+79990001390"})
    return uid, bid, токен


def test_новый_владелец_номера_получает_чистый_аккаунт(client, прежняя_хозяйка):
    """Главное: посторонний не должен войти в чужую жизнь по купленной симке."""
    uid, _, _ = прежняя_хозяйка
    _молчал(uid, PHONE_RECYCLE_DAYS + 30)

    вошёл = _войти(client, НОМЕР, "device-airat", name="Айрат")

    assert вошёл["user"]["id"] != uid, (
        "новый владелец номера вошёл в аккаунт прежней хозяйки: ему достались её имя, "
        "поездки, переписка и доверенные контакты"
    )
    assert вошёл["user"]["name"] == "Айрат", (
        f"в профиле осталось чужое имя {вошёл['user']['name']!r}"
    )


def test_чужая_переписка_и_история_недоступны(client, прежняя_хозяйка):
    """Даже если знать номер поездки — по прямой ссылке тоже не пустят."""
    uid, bid, _ = прежняя_хозяйка
    _молчал(uid, PHONE_RECYCLE_DAYS + 30)
    новый = _войти(client, НОМЕР, "device-airat2", name="Айрат")
    хедеры = {"Authorization": f"Bearer {новый['access_token']}"}

    переписка = client.get(f"/bookings/{bid}/messages", headers=хедеры)
    детали = client.get(f"/bookings/{bid}/details", headers=хедеры)
    свои = client.get("/bookings/mine", headers=хедеры).json()

    assert переписка.status_code == 403, f"чужая переписка открыта: {переписка.status_code}"
    assert детали.status_code == 403, f"чужие детали брони открыты: {детали.status_code}"
    assert свои == [], f"в «моих поездках» чужая история: {свои}"


def test_мамин_номер_не_достаётся_постороннему(client, прежняя_хозяйка):
    """Самое опасное: SOS нового человека ушёл бы маме прежней хозяйки."""
    uid, _, _ = прежняя_хозяйка
    _молчал(uid, PHONE_RECYCLE_DAYS + 30)
    новый = _войти(client, НОМЕР, "device-airat3", name="Айрат")

    близкие = client.get("/trusted-contacts",
                         headers={"Authorization": f"Bearer {новый['access_token']}"}).json()

    assert близкие == [], (
        f"новому владельцу номера достались чужие близкие: {близкие}. При беде тревога ушла бы "
        "маме постороннего человека, и она поехала бы искать не свою дочь"
    )
    with Session(engine) as s:
        у_прежней = s.exec(select(TrustedContact).where(TrustedContact.user_id == uid)).all()
    assert len(у_прежней) == 1, "контакты прежней хозяйки удалены — а они её, не его"


def test_прежней_хозяйке_объяснили_что_случилось(client, прежняя_хозяйка):
    """Иначе для неё это выглядит как «приложение сломалось» — и она просто уйдёт."""
    uid, _, _ = прежняя_хозяйка
    _молчал(uid, PHONE_RECYCLE_DAYS + 30)
    было = _уведомлений(uid)

    _войти(client, НОМЕР, "device-airat4", name="Айрат")

    assert _уведомлений(uid) > было, "прежней хозяйке не сказали, почему она больше не входит"
    with Session(engine) as s:
        свежее = s.exec(select(Notification).where(Notification.user_id == uid)
                        .order_by(Notification.id.desc())).first()
    assert свежее.title_ba and свежее.body_ba, "нет башкирского текста"
    assert "поддержк" in свежее.body_ru.lower(), (
        f"в объяснении нет пути дальше: {свежее.body_ru!r}. Человек должен понимать, куда идти"
    )


def test_смена_телефона_у_активного_ничего_не_ломает(client, прежняя_хозяйка):
    """Обратная сторона: телефоны меняют все, и это не повод отбирать аккаунт."""
    uid, _, _ = прежняя_хозяйка

    вошёл = _войти(client, НОМЕР, "device-new-phone")

    assert вошёл["user"]["id"] == uid, (
        "человек просто купил новый телефон — и потерял все свои поездки, отзывы и историю"
    )


def test_долгий_перерыв_на_том_же_телефоне_не_страшен(client, прежняя_хозяйка):
    """Сезонный пассажир: ездил прошлым летом, вернулся этим — телефон тот же."""
    uid, _, _ = прежняя_хозяйка
    _молчал(uid, PHONE_RECYCLE_DAYS + 200)

    вошёл = _войти(client, НОМЕР, "device-gulnara")     # то же устройство

    assert вошёл["user"]["id"] == uid, (
        "человек вернулся через год на своём же телефоне и обнаружил пустой аккаунт"
    )


def test_старый_клиент_без_отметки_устройства_не_наказан(client, прежняя_хозяйка):
    """Устройство не назвали — значит судить не по чему. Цена ошибки слишком велика."""
    uid, _, _ = прежняя_хозяйка
    _молчал(uid, PHONE_RECYCLE_DAYS + 30)
    client.post("/auth/request-code", json={"phone": НОМЕР})
    with Session(engine) as s:
        код = s.exec(select(OtpCode).where(OtpCode.phone == НОМЕР)
                     .order_by(OtpCode.id.desc())).first().code

    r = client.post("/auth/verify", json={"phone": НОМЕР, "code": код})   # без X-Device-Id

    assert r.status_code == 200, r.text
    assert r.json()["user"]["id"] == uid, (
        "старая версия приложения не шлёт отметку устройства — и человек лишился аккаунта "
        "просто потому, что давно не обновлялся"
    )


def test_короткий_перерыв_не_считается_сменой_владельца(client, прежняя_хозяйка):
    """Полгода — это граница, за которой оператор передаёт номер. Месяц — нет."""
    uid, _, _ = прежняя_хозяйка
    _молчал(uid, 30)

    вошёл = _войти(client, НОМЕР, "device-another")

    assert вошёл["user"]["id"] == uid, (
        "месяц без приложения и новый телефон — обычное дело, а аккаунт уже отобрали"
    )


def test_вход_обновляет_отметку_жизни(client, user_factory):
    """Без свежей отметки активный человек через полгода выглядел бы как ушедший."""
    номер = "+79995550140"
    вход = _войти(client, номер, "device-fresh", name="Активный")
    uid = вход["user"]["id"]
    _молчал(uid, 300)

    _войти(client, номер, "device-fresh")                 # зашёл сегодня, тем же телефоном

    with Session(engine) as s:
        u = s.get(User, uid)
    assert u.last_seen_at is not None, "отметка последней активности не пишется вовсе"
    assert (utcnow() - u.last_seen_at) < timedelta(hours=1), (
        f"после входа отметка осталась старой ({u.last_seen_at}): человек, который заходит "
        "каждый день, всё равно считался бы пропавшим"
    )


def test_обновление_ключей_тоже_считается_жизнью(client, user_factory):
    """Приложение обновляет ключи само — это и есть признак, что телефоном пользуются."""
    номер = "+79995550141"
    вход = _войти(client, номер, "device-refresh", name="Тихий")
    uid = вход["user"]["id"]
    _молчал(uid, 300)

    r = client.post("/auth/refresh", json={"refresh_token": вход["refresh_token"]})

    assert r.status_code == 200, r.text
    with Session(engine) as s:
        u = s.get(User, uid)
    assert (utcnow() - u.last_seen_at) < timedelta(hours=1), (
        "человек пользуется приложением, ключи обновляются, а отметка жизни стоит на месте — "
        "через полгода у него отберут аккаунт"
    )


def _уведомлений(user_id: int) -> int:
    with Session(engine) as s:
        return len(s.exec(select(Notification).where(Notification.user_id == user_id)).all())
