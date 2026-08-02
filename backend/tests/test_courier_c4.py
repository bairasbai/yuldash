"""C4 — умные правила комиссии курьера: минимум + лесенка по стажу + промо запуска + надбавка buy_bring.

Позитив:
- минимум за доставку: мелкая доставка (8% < 25 ₽) → комиссия = пол 25 ₽; чистая функция на цене < пола → вся цена;
- лесенка по стажу: 10 дней → 3%, 45 → 5%, 90 → 8% (мокаем reviewed_at), проверка на ФИНАЛЕ (вручение);
- промо запуска: COURIER_LAUNCH_PROMO_UNTIL в будущем → комиссия 0% (без минимума — подарок); после даты → лесенка;
- «купи и привези» дороже обычного на надбавку;
- комиссия не превышает цену доставки.

Комиссия ФИНАЛИЗИРУЕТСЯ при вручении по стажу назначенного курьера (при создании — лишь оценка).
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

import app.routers.courier as cr
from app.config import settings
from app.db import engine
from app.models import CourierApplication, ParcelDelivery, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _apply(client, u, transport="car", selfie="secure/docs/selfie.jpg"):
    return client.post("/courier/apply", headers=u["auth"],
                       json={"transport": transport, "selfie_url": selfie})


def _approve(client, admin, app_id):
    return client.post(f"/admin/courier-applications/{app_id}/approve", headers=admin["auth"])


def _make_courier(client, user_factory, name="Курьер"):
    admin = user_factory(name="АдминC4", role=UserRole.admin)
    c = user_factory(name=name)
    aid = _apply(client, c).json()["id"]
    assert _approve(client, admin, aid).status_code == 200
    assert client.post("/courier/online", headers=c["auth"], json={"zone": "region"}).status_code == 200
    return c


def _set_tenure_days(courier_id: int, days: int):
    """Смещаем reviewed_at курьера в прошлое на N дней — эмулируем стаж для лесенки."""
    with Session(engine) as s:
        app = s.exec(
            select(CourierApplication).where(CourierApplication.user_id == courier_id)
            .order_by(CourierApplication.id.desc())
        ).first()
        app.reviewed_at = utcnow() - timedelta(days=days)
        s.add(app)
        s.commit()


def _order(client, sender, **ov):
    body = {
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958,
        "to_lat": 53.630, "to_lng": 55.950,        # межгород → цена большая (лесенка различима)
        "size": "small", "description": "Документы",
        "receiver_name": "Айгуль", "receiver_phone": "+79990009900",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    return client.post("/courier/orders", headers=sender["auth"], json=body)


def _deliver(client, courier, sender, **ov):
    """Полный флоу до вручения. Возвращает delivered-json (в нём финальный commission_kop и price_kop)."""
    ro = _order(client, sender, **ov)
    assert ro.status_code == 200, ro.text
    order = ro.json()
    pid, code = order["id"], order["confirm_code"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    if order["delivery_type"] == "buy_bring":   # C2: сперва фактическая стоимость товара
        assert client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"],
                           json={"actual_kop": 30000}).status_code == 200
    rd = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                     json={"status": "delivered", "code": code})
    assert rd.status_code == 200, rd.text
    out = rd.json()
    out["order_price_kop"] = order["price_kop"]
    return out


def _expect(price_kop: int, percent: float) -> int:
    """Эталон комиссии по формуле courier_commission_kop (min + пол ≤ цена)."""
    if percent <= 0:
        return 0
    raw = round(price_kop * percent / 100)
    return min(price_kop, max(cr.COURIER_COMMISSION_MIN_KOP, raw))


# ----------------------------- ЧИСТАЯ ФУНКЦИЯ (минимум/потолок) -----------------------------

def test_commission_kop_formula():
    MIN = cr.COURIER_COMMISSION_MIN_KOP
    # мелкая доставка: 8% от 15000 (=150 ₽) = 1200 < пол → комиссия = пол
    assert cr.courier_commission_kop(15000, 8.0) == MIN
    # цена доставки МЕНЬШЕ пола → берём всю цену (комиссия не может превышать доставку)
    assert cr.courier_commission_kop(2000, 8.0) == 2000
    # крупная доставка: 8% выше пола → берём процент
    assert cr.courier_commission_kop(400000, 8.0) == 32000
    # промо 0% → реально 0, без пола (подарок первым)
    assert cr.courier_commission_kop(400000, 0.0) == 0
    # комиссия никогда не превышает цену доставки
    assert cr.courier_commission_kop(1000, 50.0) <= 1000


# ----------------------------- МИНИМУМ на вручении -----------------------------

def test_min_commission_on_small_delivery(client, user_factory):
    """Мелкая доставка (одна точка → цена = подача 100 ₽): 3% = 3 ₽ < пол → комиссия = 25 ₽."""
    courier = _make_courier(client, user_factory, name="КурьерМинимум")
    _set_tenure_days(courier["id"], 10)   # tier1 3%
    sender = user_factory(name="ОтпрМинимум")
    # from == to → distance 0 → цена = только base (100 ₽ = 10000 коп)
    d = _deliver(client, courier, sender, to_lat=54.735, to_lng=55.958, size="small")
    assert d["order_price_kop"] == 10000                  # только подача
    assert d["commission_kop"] == cr.COURIER_COMMISSION_MIN_KOP   # 3% (300) < пол → 25 ₽
    assert d["commission_kop"] <= d["order_price_kop"]    # ≤ цены доставки


# ----------------------------- ЛЕСЕНКА по стажу -----------------------------

@pytest.mark.parametrize("days,percent", [(10, 3.0), (45, 5.0), (90, 8.0)])
def test_ladder_tiers_finalized_on_delivery(client, user_factory, days, percent):
    courier = _make_courier(client, user_factory, name=f"КурьерСтаж{days}")
    _set_tenure_days(courier["id"], days)
    sender = user_factory(name=f"Отпр{days}")
    d = _deliver(client, courier, sender)   # межгород → цена большая, ступени различимы
    assert d["commission_kop"] == _expect(d["order_price_kop"], percent)


def test_me_shows_current_fee_tier(client, user_factory):
    """/courier/me отдаёт текущую ступень курьера (для UI «сейчас ты платишь N%»)."""
    courier = _make_courier(client, user_factory, name="КурьерСтупень")
    _set_tenure_days(courier["id"], 45)
    st = client.get("/courier/me", headers=courier["auth"]).json()["statement"]
    assert st["current_fee_percent"] == 5.0
    assert st["fee_tier"] == "tier2"
    assert st["commission_min_kop"] == cr.COURIER_COMMISSION_MIN_KOP


# ----------------------------- ПРОМО запуска -----------------------------

def test_launch_promo_zero_commission(client, user_factory, monkeypatch):
    """Промо активно (дата в будущем, курьер из первого набора) → комиссия 0% без минимума."""
    future = (utcnow() + timedelta(days=30)).date().isoformat()
    monkeypatch.setattr(cr, "COURIER_LAUNCH_PROMO_UNTIL", future)
    courier = _make_courier(client, user_factory, name="КурьерПромо")
    _set_tenure_days(courier["id"], 5)    # даже с малым стажем — промо перекрывает лесенку
    sender = user_factory(name="ОтпрПромо")
    d = _deliver(client, courier, sender)
    assert d["commission_kop"] == 0       # подарок первым, без пола
    # и статус ступени в кабинете — promo
    st = client.get("/courier/me", headers=courier["auth"]).json()["statement"]
    assert st["fee_tier"] == "promo"
    assert st["current_fee_percent"] == 0.0


def test_promo_expired_falls_back_to_ladder(client, user_factory, monkeypatch):
    """Промо-дата в прошлом → промо не действует, работает лесенка по стажу."""
    past = (utcnow() - timedelta(days=1)).date().isoformat()
    monkeypatch.setattr(cr, "COURIER_LAUNCH_PROMO_UNTIL", past)
    courier = _make_courier(client, user_factory, name="КурьерПромоВышел")
    _set_tenure_days(courier["id"], 90)   # tier3 8%
    sender = user_factory(name="ОтпрПромоВышел")
    d = _deliver(client, courier, sender)
    assert d["commission_kop"] == _expect(d["order_price_kop"], 8.0)
    assert d["commission_kop"] > 0        # не 0 — промо не действует


# ----------------------------- «Купи и привези» дороже -----------------------------

def test_buy_bring_higher_than_courier(client, user_factory):
    """При одинаковом стаже и цене доставки buy_bring-комиссия выше обычной на надбавку."""
    tenure = 90   # tier3 8% → buy_bring 10%
    c1 = _make_courier(client, user_factory, name="КурьерОбычный")
    _set_tenure_days(c1["id"], tenure)
    c2 = _make_courier(client, user_factory, name="КурьерКупи")
    _set_tenure_days(c2["id"], tenure)
    s1 = user_factory(name="ОтпрОбычный")
    s2 = user_factory(name="ОтпрКупи")

    d_courier = _deliver(client, c1, s1, delivery_type="courier")
    d_buy = _deliver(client, c2, s2, delivery_type="buy_bring", cod_amount_kop=30000)

    # цена доставки одинаковая (маршрут/размер те же) → сравниваем чистую комиссию
    assert d_courier["order_price_kop"] == d_buy["order_price_kop"]
    assert d_buy["commission_kop"] > d_courier["commission_kop"]
    assert d_courier["commission_kop"] == _expect(d_courier["order_price_kop"], 8.0)
    assert d_buy["commission_kop"] == _expect(d_buy["order_price_kop"], 10.0)


# ----------------------------- Комиссия ≤ цены доставки (на реальном заказе) -----------------------------

def test_commission_never_exceeds_delivery_price(client, user_factory):
    courier = _make_courier(client, user_factory, name="КурьерПотолок")
    _set_tenure_days(courier["id"], 5)
    sender = user_factory(name="ОтпрПотолок")
    d = _deliver(client, courier, sender, to_lat=54.735, to_lng=55.958)  # мелкая (только подача)
    assert d["commission_kop"] <= d["order_price_kop"]
    assert d["commission_kop"] > 0
