# -*- coding: utf-8 -*-
"""Курьера сняли с работы за то, как он вёл себя в роли заказчика (волна 186).

История. Айрат работает курьером — это его заработок. Он же сам иногда отправляет посылки:
у нас один человек часто и возит, и заказывает, район маленький. Три курьера, возившие его
коробки, поставили ему по единице как ЗАКАЗЧИКУ.

Приложение сняло Айрата с линии на двое суток и написало: «Рейтинг заметно просел». Как
курьер он к тому моменту не вёз ещё ни одной посылки — претензий к его работе не было
ни у кого.

Две ошибки в одной. Первая — не та дверь: оценка после доставки взаимная (отправитель
оценивает курьера, курьер — отправителя), а лестница качества вызывалась для «кого оценили»,
кем бы он ни был. Вторая — не те данные: она смотрела на ОБЩИЙ балл человека, а он
складывается из всех ролей сразу — пассажир, водитель попутки, отправитель, курьер.
Для витрины это правильно (доверие человеку одно), но лестница отнимает РАБОТУ, а работа
у него одна.

Теперь лестница включается, только если оценили именно курьера этой доставки, и считает
по его курьерским оценкам. Наказание должно быть за то, что человек сделал плохо, — иначе
это не качество, а лотерея.
"""
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import CourierProfile, Notification, ParcelDelivery, UserRole
from conftest import upload_doc


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _make_courier(client, user_factory, name):
    admin = user_factory(name=f"Админ{name}", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": upload_doc(client, c["auth"])}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=admin["auth"]).status_code == 200
    assert client.post("/courier/online", headers=c["auth"],
                       json={"zone": "region"}).status_code == 200
    return c


def _доставка(client, отправитель, курьер, метка: str) -> int:
    """Полный путь одной посылки: заказ → принял → забрал → вручил."""
    r = client.post("/courier/orders", headers=отправитель["auth"], json={
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958, "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": f"Коробка {метка}", "receiver_name": "Айгуль",
        "receiver_phone": f"+7999{abs(hash(метка)) % 10**7:07d}", "rules_accepted": True,
        "delivery_type": "courier", "urgency": "bypath",
    })
    assert r.status_code == 200, r.text
    pid = r.json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=курьер["auth"]).status_code == 200
    client.post(f"/parcels/{pid}/status", headers=курьер["auth"], json={"status": "in_transit"})
    with Session(engine) as s:
        код = s.get(ParcelDelivery, pid).confirm_code
    assert client.post(f"/parcels/{pid}/status", headers=курьер["auth"],
                       json={"status": "delivered", "code": код}).status_code == 200
    return pid


def _профиль(user_id: int) -> CourierProfile:
    with Session(engine) as s:
        return s.exec(select(CourierProfile).where(CourierProfile.user_id == user_id)).first()


def _письма(user_id: int) -> list[tuple[str, str, str]]:
    with Session(engine) as s:
        rows = s.exec(select(Notification).where(Notification.user_id == user_id)).all()
    return [(n.title_ru or "", n.body_ru or "", n.body_ba or "") for n in rows]


def test_плохой_заказчик_остаётся_хорошим_курьером(client, user_factory):
    """Главное: работу отнимают за работу, а не за поведение в другой роли."""
    айрат = _make_courier(client, user_factory, "Айрат186")
    for i in range(3):
        возчик = _make_courier(client, user_factory, f"Возчик186_{i}")
        pid = _доставка(client, айрат, возчик, f"заказ{i}")
        # Курьер оценивает ОТПРАВИТЕЛЯ (Айрата) единицей — как заказчика.
        assert client.post(f"/parcels/{pid}/rate", headers=возчик["auth"],
                           json={"stars": 1}).status_code == 200

    проф = _профиль(айрат["id"])

    assert проф.paused_until is None, (
        f"Айрата сняли с линии до {проф.paused_until} за оценки, полученные как заказчику. "
        "Как курьер он не вёз ни одной посылки — наказывать не за что"
    )
    assert проф.online is True, "его молча выключили с линии — заказы просто перестанут приходить"
    assert not [1 for t, _, _ in _письма(айрат["id"]) if "Пауза" in t], "пришло письмо о паузе"


def test_плохой_курьер_по_прежнему_уходит_на_паузу(client, user_factory):
    """Обратная сторона: защита должна остаться защитой, иначе я просто её сломал."""
    курьер = _make_courier(client, user_factory, "Небрежный186")
    for i in range(3):
        заказчик = _make_courier(client, user_factory, f"Заказчик186_{i}")
        pid = _доставка(client, заказчик, курьер, f"небрежно{i}")
        # Теперь оценивает ОТПРАВИТЕЛЬ — то есть оценивают самого КУРЬЕРА.
        assert client.post(f"/parcels/{pid}/rate", headers=заказчик["auth"],
                           json={"stars": 1}).status_code == 200

    проф = _профиль(курьер["id"])

    assert проф.paused_until is not None, (
        "три единицы за собственные доставки — и никакой паузы: лестница качества перестала работать"
    )
    assert проф.online is False, "оставили «на линии», хотя заказы ему теперь отдавать нельзя"


def test_письмо_о_паузе_говорит_за_что(client, user_factory):
    """«Рейтинг просел» — не объяснение: человек должен понять, о каких оценках речь."""
    курьер = _make_courier(client, user_factory, "Небрежный186Письмо")
    for i in range(3):
        заказчик = _make_courier(client, user_factory, f"ЗаказчикП186_{i}")
        pid = _доставка(client, заказчик, курьер, f"письмо{i}")
        client.post(f"/parcels/{pid}/rate", headers=заказчик["auth"], json={"stars": 1})

    паузы = [(t, ru, ba) for t, ru, ba in _письма(курьер["id"]) if "Пауза" in t]

    assert паузы, "человека отстранили молча"
    _, ru, ba = паузы[-1]
    assert "доставк" in ru.lower(), (
        f"не сказано, за что именно пауза: {ru!r}. Человек не поймёт, что исправлять"
    )
    assert ba and ba != ru, "письмо об отстранении не на двух языках"


def test_отсидевшего_паузу_не_сажают_заново_из_другой_роли(client, user_factory):
    """Пауза кончилась — и новая не выдаётся за событие, к работе не относящееся.

    Тонкий случай, который поймался только мутацией. Даже если считать по КУРЬЕРСКИМ оценкам,
    но включать лестницу на любой оценке, человек с просевшей историей будет получать новые
    двое суток каждый раз, когда его оценят как заказчика: старые оценки никуда не делись,
    а повод пересчитать даёт чужое действие. Отсидел — работай; наказание не должно
    возобновляться само.
    """
    курьер = _make_courier(client, user_factory, "Отсидел186")
    for i in range(3):
        заказчик = _make_courier(client, user_factory, f"ЗаказчикО186_{i}")
        pid = _доставка(client, заказчик, курьер, f"отсидел{i}")
        client.post(f"/parcels/{pid}/rate", headers=заказчик["auth"], json={"stars": 1})
    assert _профиль(курьер["id"]).paused_until is not None, "пауза не выдалась — проверять нечего"

    # Пауза прошла: человек снова на линии.
    with Session(engine) as s:
        проф = s.exec(select(CourierProfile).where(CourierProfile.user_id == курьер["id"])).first()
        проф.paused_until = None
        проф.online = True
        s.add(проф)
        s.commit()

    # Теперь он сам отправляет посылку, и ВОЗЧИК оценивает его как заказчика.
    возчик = _make_courier(client, user_factory, "Возчик186Отсидел")
    pid = _доставка(client, курьер, возчик, "он заказчик")
    assert client.post(f"/parcels/{pid}/rate", headers=возчик["auth"],
                       json={"stars": 5}).status_code == 200

    проф = _профиль(курьер["id"])
    assert проф.paused_until is None, (
        f"человека снова сняли с линии до {проф.paused_until} — за оценку, которую он получил "
        "как заказчик, да ещё и хорошую. Он отсидел паузу и не сделал ничего нового"
    )


def test_курьерский_рейтинг_не_смешивает_роли(client, user_factory):
    """Прямая проверка правила: в курьерский счёт идут только его доставки."""
    from app.routers.courier import courier_rating
    from app.services import user_rating

    айрат = _make_courier(client, user_factory, "Айрат186Счёт")
    возчик = _make_courier(client, user_factory, "Возчик186Счёт")
    # Одна оценка Айрату как заказчику…
    pid = _доставка(client, айрат, возчик, "как заказчику")
    client.post(f"/parcels/{pid}/rate", headers=возчик["auth"], json={"stars": 1})
    # …и одна как курьеру.
    pid2 = _доставка(client, возчик, айрат, "как курьеру")
    client.post(f"/parcels/{pid2}/rate", headers=возчик["auth"], json={"stars": 5})

    with Session(engine) as s:
        общий, общее_число = user_rating(s, айрат["id"])
        курьерский, курьерских = courier_rating(s, айрат["id"])

    assert общее_число == 2, f"ожидались обе оценки в общем счёте: {общий}, {общее_число}"
    assert курьерских == 1, (
        f"в курьерский счёт попало {курьерских} оценок вместо одной: туда затекла оценка, "
        "которую человек получил как заказчик"
    )
    assert курьерский == 5.0, f"курьерский рейтинг {курьерский} вместо 5.0"


def test_у_кого_нет_курьерских_оценок_счёт_пустой(client, user_factory):
    """Граница: новичок без единой доставки не должен получить «рейтинг 0» и паузу."""
    from app.routers.courier import courier_rating

    новичок = _make_courier(client, user_factory, "Новичок186")

    with Session(engine) as s:
        avg, cnt = courier_rating(s, новичок["id"])

    assert (avg, cnt) == (0.0, 0)
    assert _профиль(новичок["id"]).paused_until is None
