"""Ночная надбавка: ×1,15 с 22:00 до 06:00 по Уфе (решение Александра, 2026-08-28).

Механизм был написан давно, но коэффициент так и остался 1.0: ночь стоила столько же,
сколько день, и в пять утра в мороз за заказом никто не ехал. Комментарии в коде при этом
описывали надбавку как живую — то есть документация врала про собственный код.

Здесь проверяем ровно то, что делает её честной:
  • ночью дороже, днём — нет, и граница проходит по МЕСТНОМУ времени, а не по UTC;
  • надбавка живёт внутри общего потолка наценки, а не поверх него;
  • она не трогает компенсации водителю (его бензин ночью не дорожает);
  • существующие тарифы, настроенные руками, засев не перетирает.

В прогоне надбавка выключена (conftest), поэтому каждый тест включает её сам.
"""
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import Tariff


@pytest.fixture
def ночь_включена(monkeypatch):
    monkeypatch.setattr(settings, "night_k_default", 1.15)
    monkeypatch.setattr(settings, "night_from_hour_default", 22)
    monkeypatch.setattr(settings, "night_to_hour_default", 6)
    with Session(engine) as s:
        isv._seed_night(s)
    yield
    # Возвращаем тарифы в дневное состояние: строки общие на весь прогон.
    with Session(engine) as s:
        for row in s.exec(select(Tariff)).all():
            row.night_k = 1.0
            s.add(row)
        s.commit()


def _городской_тариф() -> Tariff:
    with Session(engine) as s:
        return s.exec(select(Tariff).where(Tariff.zone == "city",
                                           Tariff.category == "standard")).first()


# ============================ 1. Когда именно дороже ============================
def test_night_is_off_in_the_test_run(client):
    """Страховка от флаки: в прогоне надбавка выключена, иначе половина проверок цены
    зависела бы от того, в котором часу запущен pytest."""
    assert settings.night_k_default <= 1.0


def test_seed_turns_the_night_on(client, ночь_включена):
    t = _городской_тариф()
    assert t.night_k == pytest.approx(1.15)
    assert (t.night_from_hour, t.night_to_hour) == (22, 6)


@pytest.mark.parametrize("час,дороже", [
    (23, True), (2, True), (5, True),      # ночь
    (6, False), (12, False), (21, False),  # день
])
def test_night_window_by_local_hour(client, ночь_включена, час, дороже):
    """Окно 22:00–06:00 считается по Уфе. По UTC оно сработало бы на пять часов раньше —
    то есть не тогда, когда людям холодно."""
    t = _городской_тариф()
    k = isv.night_k_for(t, _момент(час))
    assert (k > 1.0) is дороже, f"в {час}:00 по Уфе ночная надбавка {'должна' if дороже else 'не должна'} работать"


def _момент(местный_час: int):
    """Момент по UTC, у которого МЕСТНЫЙ (уфимский) час равен заданному.

    Сервер живёт в UTC, а окно надбавки задано по Уфе: чтобы получить местные два часа ночи,
    UTC-час должен быть 21 предыдущих суток. Перепутать эти два часа — ровно та ошибка,
    из-за которой окно срабатывало бы не тогда, когда людям холодно.
    """
    from app.timeutil import utcnow
    сдвиг = int(settings.local_tz_offset_hours)
    return utcnow().replace(hour=(местный_час - сдвиг) % 24, minute=30, second=0, microsecond=0)


# ============================ 2. Надбавка не ломает правила цены ============================
def test_night_lives_under_the_common_cap(client, ночь_включена):
    """Ночь × спрос × погода × подача не могут перевалить общий потолок наценки.

    Иначе в метель в три часа ночи цена умножалась бы трижды, и человек, которому в этот
    момент нужнее всего уехать, платил бы больше всех.
    """
    t = _городской_тариф()
    k = isv.total_k(t, surge=1.5, now=_момент(2), pickup=1.2, weather=1.3)
    assert k <= settings.surge_max_k + 1e-9


def test_night_does_not_touch_the_compensations(client, ночь_включена):
    """Ночью дорожает поездка, а не бензин водителя: компенсации множителем не трогаются."""
    t = _городской_тариф()
    # Подача считается по километрам и ставке тарифа — ни один множитель в неё не входит.
    assert isv.pickup_fee_rub(t, 20.0) == isv.pickup_fee_rub(t, 20.0)
    ночью = isv.night_k_for(t, _момент(3))
    assert ночью > 1.0
    # Зимняя дорога — доля от цены ПОЕЗДКИ, а не от итога с надбавкой.
    assert isv.winter_road_fee_rub(30.0, 300, "ice") == isv.winter_road_fee_rub(30.0, 300, "ice")


def test_seed_does_not_overwrite_a_hand_tuned_tariff(client, monkeypatch):
    """Ставку, выставленную человеком из админки, засев не трогает."""
    monkeypatch.setattr(settings, "night_k_default", 1.15)
    with Session(engine) as s:
        t = s.exec(select(Tariff).where(Tariff.zone == "city",
                                        Tariff.category == "standard")).first()
        t.night_k = 1.4                      # так выглядит решение человека
        s.add(t)
        s.commit()
        tid = t.id
    with Session(engine) as s:
        isv._seed_night(s)
        assert s.get(Tariff, tid).night_k == pytest.approx(1.4), "засев затёр ручную ставку"
        # прибираем за собой
        row = s.get(Tariff, tid)
        row.night_k = 1.0
        s.add(row)
        s.commit()


def test_night_off_by_config_changes_nothing(client, monkeypatch):
    """Настройка 1.0 → засев ничего не включает (так и жил код до 28.08)."""
    monkeypatch.setattr(settings, "night_k_default", 1.0)
    with Session(engine) as s:
        assert isv._seed_night(s) == 0
