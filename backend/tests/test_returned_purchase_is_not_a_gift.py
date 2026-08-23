# -*- coding: utf-8 -*-
"""Курьер купил лекарства на свои — получателя нет дома — деньги исчезли (волна 185).

История. Женщина заказывает «купи и привези»: лекарства из аптеки в Уфе внучке в Стерлитамак.
Курьер идёт в аптеку, платит СВОИ 1800 ₽, едет. Внучки нет дома, телефон молчит. Курьер
разворачивается и везёт покупку обратно бабушке — это правильный и предусмотренный ход.

Дальше приложение закрывало дело так: заказ уходил в «возвращена», отправительнице приходило
«Курьер вернул посылку. Комиссию за возврат мы не берём», курьеру — НИЧЕГО. Ни строчки о том,
что у него из кармана ушло 1800 ₽, а лекарства теперь у неё.

А в квитанции обе стороны видели «Итого 5 094 ₽» — доставка плюс товар. Доставки не было
(мы за неё и комиссию не берём), а число стоит и выглядит как счёт. Один читает его как
«я должен пять тысяч», другой — как «мне должны пять тысяч».

Почему это третья дверь. Правило «после закупки товара расчёт только через разбор» в проекте
есть и работает на двух дверях: отправитель не может отменить заказ, курьер не может сняться —
обе отвечают «открой спор, чтобы вернуть деньги». Возврат этой проверки не имел вовсе. И это
ровно та дверь, которой пользуются чаще всего: «получателя нет дома» — обычное дело,
а отмена и снятие — редкость.

Теперь: сумма за товар названа отдельным полем и словами, оба человека получают письмо,
курьеру назван выход (спор), а «итого» в чеке вернувшейся покупки — это ровно то, что нужно
вернуть, а не цена неоказанной услуги.
"""
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Notification, UserRole
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


def _заказ_купи_и_привези(client, отправитель, телефон):
    r = client.post("/courier/orders", headers=отправитель["auth"], json={
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958, "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": "Лекарство", "receiver_name": "Айгуль",
        "receiver_phone": телефон, "rules_accepted": True,
        "delivery_type": "buy_bring", "urgency": "bypath",
        "cod_amount_kop": 200_000, "declared_value_kop": 250_000,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _письма(кому: int, посылка: int) -> list[tuple[str, str, str]]:
    with Session(engine) as s:
        rows = s.exec(select(Notification).where(
            Notification.user_id == кому, Notification.ref_id == посылка,
        )).all()
    return [(n.title_ru or "", n.body_ru or "", n.body_ba or "") for n in rows]


@pytest.fixture
def покупка_вернулась(client, user_factory, request):
    """Полный путь: заказ → курьер купил на свои → повёз → никого нет дома → вернул."""
    метка = request.node.name[-12:]
    отправитель = _make_courier(client, user_factory, f"Бабушка{метка}")
    курьер = _make_courier(client, user_factory, f"Курьер{метка}")
    pid = _заказ_купи_и_привези(client, отправитель, f"+7999{abs(hash(метка)) % 10**7:07d}")
    assert client.post(f"/parcels/{pid}/accept", headers=курьер["auth"]).status_code == 200
    assert client.post(f"/courier/orders/{pid}/goods-cost", headers=курьер["auth"],
                       json={"actual_kop": 180_000}).status_code == 200
    assert client.post(f"/parcels/{pid}/status", headers=курьер["auth"],
                       json={"status": "in_transit"}).status_code == 200
    assert client.post(f"/parcels/{pid}/return-start", headers=курьер["auth"],
                       json={"reason": "Получателя нет дома"}).status_code == 200
    assert client.post(f"/parcels/{pid}/return-done", headers=курьер["auth"]).status_code == 200
    return отправитель, курьер, pid


def test_курьеру_говорят_что_ему_должны(client, покупка_вернулась):
    """Главное: человек, потративший свои деньги, должен узнать, что дело закрыто не в ноль."""
    _, курьер, pid = покупка_вернулась

    письма = _письма(курьер["id"], pid)

    assert письма, (
        "курьер закрыл возврат и не получил ни одного слова: 1800 ₽ его денег ушли "
        "без единой записи в приложении"
    )
    тексты = " ".join(t + " " + b for t, b, _ in письма)
    assert "1800" in тексты, f"сумму не назвали, курьеру нечем оперировать: {письма}"
    assert "спор" in тексты.lower(), (
        "не назван выход, если договориться не выйдет — а на двух соседних дверях он назван"
    )
    assert all(ba for _, _, ba in письма), "письмо курьеру не на двух языках"


def test_отправителю_говорят_что_он_должен(client, покупка_вернулась):
    """Вторая сторона: покупка у неё в руках, и она должна понимать, чьи это деньги."""
    отправитель, _, pid = покупка_вернулась

    тексты = [(t, b, ba) for t, b, ba in _письма(отправитель["id"], pid) if "верн" in (t + b).lower()]

    assert тексты, "отправительнице не сказали про деньги курьера"
    последнее = тексты[-1]
    assert "1800" in последнее[1], (
        f"сумма не названа — «верни ему» без числа не действие, а намёк: {последнее[1]}"
    )
    assert последнее[2], "письмо отправительнице не на двух языках"


def test_квитанция_показывает_долг_а_не_цену_неоказанной_услуги(client, покупка_вернулась):
    """Число в чеке читают как решение спора. Значит оно обязано быть верным."""
    отправитель, курьер, pid = покупка_вернулась

    чек = client.get(f"/parcels/{pid}/receipt", headers=отправитель["auth"]).json()
    чек_курьера = client.get(f"/parcels/{pid}/receipt", headers=курьер["auth"]).json()

    assert чек["owed_to_courier_kop"] == 180_000, чек
    assert чек["total_kop"] == 180_000, (
        f"в чеке «итого» {чек['total_kop'] // 100} ₽ — это доставка, которой не было, плюс товар. "
        "Отправительница прочтёт это как счёт, курьер — как свой долг ей"
    )
    деньги = ("owed_to_courier_kop", "total_kop", "goods_kop", "delivery_price_kop",
              "commission_kop", "cancel_fee_kop")
    assert {k: чек[k] for k in деньги} == {k: чек_курьера[k] for k in деньги}, (
        "две стороны видят разные суммы в одном чеке — спор упрётся ровно в это"
    )
    assert чек["commission_kop"] == 0, "за неоказанную услугу взяли комиссию"


def test_спор_после_возврата_действительно_открывается(client, покупка_вернулась):
    """Обещанный выход должен работать: иначе это ожидание без конца."""
    _, курьер, pid = покупка_вернулась

    r = client.post(f"/parcels/{pid}/dispute", headers=курьер["auth"],
                    json={"reason": "Купил на свои, отправитель не возвращает"})

    assert r.status_code == 200, (
        f"курьеру назвали спор как выход, а спор не открывается: {r.status_code} {r.text[:200]}"
    )


# --------------------------- обратная сторона ---------------------------

def test_обычная_посылка_возвращается_как_прежде(client, user_factory):
    """Перестраховка не должна превращать обычный возврат в разговор о деньгах."""
    отправитель = _make_courier(client, user_factory, "БабушкаОбычная185")
    курьер = _make_courier(client, user_factory, "КурьерОбычный185")
    r = client.post("/courier/orders", headers=отправитель["auth"], json={
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958, "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": "Коробка", "receiver_name": "Айгуль",
        "receiver_phone": "+79990001857", "rules_accepted": True,
        "delivery_type": "courier", "urgency": "bypath",
    })
    pid = r.json()["id"]
    client.post(f"/parcels/{pid}/accept", headers=курьер["auth"])
    client.post(f"/parcels/{pid}/status", headers=курьер["auth"], json={"status": "in_transit"})
    client.post(f"/parcels/{pid}/return-start", headers=курьер["auth"], json={"reason": "Нет дома"})
    client.post(f"/parcels/{pid}/return-done", headers=курьер["auth"])

    чек = client.get(f"/parcels/{pid}/receipt", headers=отправитель["auth"]).json()
    письма = " ".join(t + b for t, b, _ in _письма(отправитель["id"], pid))

    assert чек["owed_to_courier_kop"] == 0, "в обычной доставке курьер своих денег не тратил"
    assert "верни ему" not in письма.lower(), (
        f"человеку сказали вернуть деньги, которых никто не тратил: {письма}"
    )


def test_доставленная_покупка_считается_как_прежде(client, user_factory):
    """Вручили — получатель рассчитался на месте, долга не остаётся."""
    отправитель = _make_courier(client, user_factory, "БабушкаДошло185")
    курьер = _make_courier(client, user_factory, "КурьерДошло185")
    pid = _заказ_купи_и_привези(client, отправитель, "+79990001858")
    client.post(f"/parcels/{pid}/accept", headers=курьер["auth"])
    client.post(f"/courier/orders/{pid}/goods-cost", headers=курьер["auth"],
                json={"actual_kop": 180_000})
    client.post(f"/parcels/{pid}/status", headers=курьер["auth"], json={"status": "in_transit"})
    with Session(engine) as s:
        from app.models import ParcelDelivery
        код = s.get(ParcelDelivery, pid).confirm_code
    r = client.post(f"/parcels/{pid}/status", headers=курьер["auth"],
                    json={"status": "delivered", "code": код})
    assert r.status_code == 200, r.text

    чек = client.get(f"/parcels/{pid}/receipt", headers=курьер["auth"]).json()

    assert чек["owed_to_courier_kop"] == 0, "получатель рассчитался при вручении — долга нет"
    assert чек["total_kop"] == чек["delivery_price_kop"] + чек["goods_kop"], (
        "у доставленной покупки итог по-прежнему доставка плюс товар"
    )


def test_экран_чека_называет_долг_словами():
    """Сторож: число живёт на телефоне и разойтись с сервером может молча."""
    from pathlib import Path

    экран = (Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "java" /
             "com" / "yuldash" / "app" / "ParcelReceiptDialog.kt").read_text(encoding="utf-8")
    assert "owedToCourierKop" in экран, (
        "чек снова показывает «Итого» за вернувшуюся покупку — счёт за услугу, которой не было"
    )
    assert "бәхәс" in экран, "объяснение в чеке не на двух языках"
