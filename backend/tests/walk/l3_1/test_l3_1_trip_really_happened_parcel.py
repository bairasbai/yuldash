"""`trip_really_happened(parcel_id=...)` — третья, непроверенная дверь того же правила
(волна 158, безопасность людей).

Правило (safety_logic.py, `trip_really_happened`) защищает от автоматического наказания
по фиктивной "встрече": номер поездки/заказа/доставки сам по себе ничего не доказывает,
нужен ВСТРЕЧНЫЙ ШАГ второй стороны. Для попутки это подтверждение водителем
(`test_the_punishment_reaches_the_right_person.py::test_встреча_засчитывается_по_встречному_шагу`),
для такси — назначенный водитель (`...::test_заказ_такси_без_водителя_не_встреча`).

Третья дверь — доставка (`parcel_id`) — читает то же самое поле (`courier_id`), но прямого
теста на неё не было нигде в проекте: ни для самой функции, ни для того, что её использует
(`quality.escalate_severe` — авто-пауза по тяжёлой жалобе; `_maybe_ask_for_salon_photo` в
routers/safety.py — требование фото по жалобе «грязно»).

Human cost without this door closed: отправитель создаёт заявку на доставку, курьер её ещё
не взял (`courier_id is None`) — заявка «висит» и доступна любому зарегистрированному курьеру
теоретически по номеру. Если бы правило здесь требовало только «запись есть», чужая не принятая
заявка на доставку считалась бы законченной «встречей» и давала бы право на авто-наказание/
авто-требование фото тому, кто с отправителем вообще не пересекался.
"""
from sqlmodel import Session

from app.db import engine
from app.models import ParcelDelivery
from app.safety_logic import trip_really_happened


def _parcel(sender_id: int, courier_id: int | None = None) -> int:
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=sender_id, courier_id=courier_id,
                           from_city="Сибай", to_city="Баймак")
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def test_посылку_без_курьера_встречей_не_считаем(client, user_factory):
    """Заявка создана, курьер её ещё не взял — "встречи" ещё не было."""
    sender = user_factory("ParcelSender1")
    pid = _parcel(sender["id"])
    with Session(engine) as s:
        assert trip_really_happened(s, parcel_id=pid) is False, (
            "посылка без курьера посчиталась состоявшейся встречей — "
            "чужую не принятую заявку можно было бы использовать для авто-наказания"
        )


def test_курьер_принял_посылку_встреча_засчитана(client, user_factory):
    """Курьер принял посылку (courier_id проставлен) — встречный шаг случился."""
    sender = user_factory("ParcelSender2")
    courier = user_factory("ParcelCourier2")
    pid = _parcel(sender["id"], courier_id=courier["id"])
    with Session(engine) as s:
        assert trip_really_happened(s, parcel_id=pid) is True, (
            "курьер реально принял посылку, но правило всё равно не считает это встречей"
        )


def test_несуществующая_посылка_не_встреча(client):
    """Выдуманный id не должен давать True."""
    with Session(engine) as s:
        assert trip_really_happened(s, parcel_id=999_999_999) is False


def test_пустой_вызов_без_какой_либо_привязки_не_встреча():
    """Контроль читаемости функции: без booking/order/parcel — однозначный False,
    а не исключение (используется как булева проверка во всех вызывающих)."""
    with Session(engine) as s:
        assert trip_really_happened(s) is False
