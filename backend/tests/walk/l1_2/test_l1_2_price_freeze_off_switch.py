"""leaf-1.2 — backend/app/price_freeze.py: выключение заморозки должно забыть старые снимки.

Пробел обхода: `test_frozen_delivery_price_freezes_its_explanation_too.py::test_switch_off_really_switches_off`
проверяет ровно этот сценарий («запомнили цену, пока заморозка была включена → выключили
настройкой → старый снимок не должен примениться») для ДОСТАВКИ (`apply_courier`). Для ТАКСИ
(`apply`) такого теста нет — хотя код симметричный и защищён тем же рубильником
`price_freeze_sec`. Этот тест закрывает тот же сценарий для такси.

Заодно это единственный тест, который по-настоящему проверяет сам рубильник `lock_seconds()`
внутри `apply()`: когда Redis РЕАЛЬНЫЙ (не None) и старый снимок РЕАЛЬНО лежит в памяти,
ничего не бросает исключений — значит общий `except Exception: return est` тут не подстрахует,
и если рубильник сломан, тест это увидит.
"""
import fakeredis

from app import price_freeze as pf
from app.config import settings

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _est(price: int) -> dict:
    return {"price": price, "ride_price": price, "base_price": price,
            "pickup_fee": 0, "options_fee": 0, "weather_fee": 0, "weather_kind": "",
            "distance_km": 12.0, "eta_min": 18.0, "tariff_id": 1,
            "surge_k": 1.0, "pricing_k": 1.0, "round_trip": False}


def test_switching_off_forgets_the_already_remembered_taxi_price(monkeypatch):
    monkeypatch.setattr(settings, "price_freeze_sec", 90, raising=False)
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    pf.remember(r, 1, ORIG, DEST, "standard", "", False, _est(450))   # заморозили, пока было включено

    monkeypatch.setattr(settings, "price_freeze_sec", 0, raising=False)   # рубильник вниз
    out = pf.apply(r, 1, ORIG, DEST, "standard", "", False, _est(495))

    assert out["price"] == 495, (
        "заморозка выключена настройкой, а старая цена 450 всё равно применилась — "
        "рубильник price_freeze_sec не работает для уже лежащих в памяти снимков"
    )
    assert "price_was_frozen" not in out
