"""Промо запуска и лесенка комиссии — две разные шкалы, кабинет обязан их различать.

ЗАЧЕМ. Промо «первым водителям — 0%» держится КАЛЕНДАРЁМ: launch_promo_days от одобрения
заявки. Лесенка комиссии двигается ПОЕЗДКАМИ: 3% → 5% → 15%. Кабинет считал только вторую
и на промо говорил водителю «Сейчас 0% — стартовая ставка, через N поездок станет 5%».

Неправдой тут было всё сразу: срок (промо кончится по дате, а не после поездок), причина
(поездки на промо ни на что не влияют) и число (после промо новичок попадёт на ПЕРВУЮ
ступень, 3%, а не на вторую). А если бы лесенка молчала — подпись падала во вторую ветку
и сообщала «максимальная ставка 0%», что просто бессмыслица.

Ошибка была спящей: видна только при ВКЛЮЧЁННОМ промо, а оно выключено. Первые же
приглашённые водители увидели бы неправду в цифрах — то есть ровно те, ради кого промо
и придумано.

Здесь проверяем:
  • на промо кабинет не обещает ступень «через N поездок»;
  • он называет остаток В ДНЯХ и ставку, которая наступит после;
  • ставка после промо — своя ступень водителя, а не следующая по лесенке;
  • промо кончилось / выключено → всё как раньше, лесенка по поездкам.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import TaxiApplication, UserRole
from app import debt as debt_mod  # noqa: F401 — используется тестами лесенки
from app.timeutil import utcnow


def _promo_on(monkeypatch, percent: float = 0.0, days: int = 90):
    """Включить промо запуска: окно набора открыто, срок промо — days дней."""
    monkeypatch.setattr(settings, "launch_promo_until",
                        (utcnow() + timedelta(days=30)).date().isoformat())
    monkeypatch.setattr(settings, "launch_promo_percent", percent)
    monkeypatch.setattr(settings, "launch_promo_days", days)


def _approved_days_ago(driver_id: int, days: int):
    """Сдвинуть дату одобрения заявки — от неё считается срок промо."""
    with Session(engine) as s:
        app = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == driver_id)).first()
        app.reviewed_at = utcnow() - timedelta(days=days)
        s.add(app)
        s.commit()


def _кабинет(client, driver):
    r = client.get("/instant/workday", headers=driver["auth"])
    assert r.status_code == 200, r.text
    return r.json()


# ==================== 1. На промо кабинет говорит про дни, а не про поездки ====================
def test_on_promo_the_cabinet_does_not_promise_a_tier_after_n_trips(client, user_factory, monkeypatch):
    """Пока идёт промо, «через N поездок ставка вырастет» — неправда, и её быть не должно."""
    _promo_on(monkeypatch)
    d = user_factory("ПромоНеПоездки", role=UserRole.driver)

    к = _кабинет(client, d)
    assert к["promo_active"] is True
    assert к["fee_percent"] == settings.launch_promo_percent
    assert к["fee_trips_to_next"] is None, "кабинет обещает ступень по поездкам во время промо"
    assert к["fee_next_percent"] is None


def test_the_cabinet_counts_the_days_that_are_left(client, user_factory, monkeypatch):
    """Остаток промо называется в днях и совпадает с календарём, а не берётся с потолка."""
    _promo_on(monkeypatch, days=90)
    d = user_factory("ПромоДни", role=UserRole.driver)
    _approved_days_ago(d["id"], 30)

    к = _кабинет(client, d)
    assert к["promo_active"] is True
    # 90 дней промо, 30 прошло → около 60 осталось. День туда-сюда — разница часов внутри суток.
    assert 59 <= к["promo_days_left"] <= 60, к["promo_days_left"]


def test_after_promo_he_lands_on_his_own_step_not_the_next_one(client, user_factory, monkeypatch):
    """После промо новичок попадёт на ПЕРВУЮ ступень, а не на вторую.

    Старая подпись брала «следующую ступень по лесенке» и обещала новичку 5% —
    то есть пугала его ставкой, на которую он не попадёт.
    """
    _promo_on(monkeypatch)
    d = user_factory("ПромоСтупень", role=UserRole.driver)

    к = _кабинет(client, d)
    assert к["trips_done"] == 0
    assert к["fee_after_promo_percent"] == settings.fee_tier1_percent
    assert к["fee_after_promo_percent"] != settings.fee_tier2_percent, (
        "водителю обещают ступень, на которую он не попадёт"
    )


def test_a_driver_with_trips_lands_where_his_trips_put_him(client, user_factory, monkeypatch):
    """Тот же расчёт для наездившего: ставка после промо — по его собственным поездкам."""
    from test_money_rules import _seed_done_trips

    _promo_on(monkeypatch)
    d = user_factory("ПромоНаездил", role=UserRole.driver)
    pax = user_factory("ПромоНаезженныйПас")
    _seed_done_trips(d["id"], pax["id"], settings.fee_tier1_trips + 1)

    к = _кабинет(client, d)
    assert к["promo_active"] is True
    assert к["fee_after_promo_percent"] == settings.fee_tier2_percent


# ==================== 2. Без промо ничего не изменилось ====================
def test_without_promo_the_ladder_speaks_as_before(client, user_factory):
    """Промо выключено (дефолт) → кабинет по-прежнему считает ступень поездками."""
    d = user_factory("БезПромоЛесенка", role=UserRole.driver)

    к = _кабинет(client, d)
    assert к["promo_active"] is False
    assert к["promo_days_left"] is None and к["fee_after_promo_percent"] is None
    assert к["fee_next_percent"] == settings.fee_tier2_percent
    assert к["fee_trips_to_next"] == settings.fee_tier1_trips


def test_when_the_promo_runs_out_the_ladder_takes_over(client, user_factory, monkeypatch):
    """Промо истекло по календарю → снова обычная лесенка, без следов промо в кабинете."""
    _promo_on(monkeypatch, days=90)
    d = user_factory("ПромоИстёк", role=UserRole.driver)
    _approved_days_ago(d["id"], 91)

    к = _кабинет(client, d)
    assert к["promo_active"] is False
    assert к["promo_days_left"] is None
    assert к["fee_percent"] == settings.fee_tier1_percent, "ставка не вернулась на лесенку"
    assert к["fee_trips_to_next"] == settings.fee_tier1_trips


def test_the_top_step_still_says_it_is_the_top(client, user_factory):
    """Верхняя ступень без промо — прежняя подпись «максимальная ставка», не сломали."""
    from test_money_rules import _seed_done_trips

    d = user_factory("ВерхняяСтупень", role=UserRole.driver)
    pax = user_factory("ВерхняяСтупеньПас")
    _seed_done_trips(d["id"], pax["id"], settings.fee_tier2_trips)

    к = _кабинет(client, d)
    assert к["promo_active"] is False
    assert к["fee_next_percent"] is None and к["fee_trips_to_next"] is None
    assert к["fee_percent"] == settings.service_fee_percent
