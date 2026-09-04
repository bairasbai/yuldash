"""«0% комиссии первым водителям» обещаем, только пока набор реально идёт.

Экран зазыва водителей писал «0% комиссии первые 3 месяца» безусловно, а промо запуска
по умолчанию ВЫКЛЮЧЕНО (`launch_promo_until` пуста) и в любом случае кончается датой.
Обещание, которого сервер не выполнит, — обман, а не маркетинг. Теперь состояние набора
приходит с сервера, и приложение показывает строку только когда она правда.
"""
from datetime import timedelta

from app.config import settings
from app.taxi import launch_promo_state
from app.timeutil import utcnow


def _with_promo(until: str):
    old = settings.launch_promo_until
    settings.launch_promo_until = until
    return old


def test_promo_is_off_when_no_date(client, user_factory):
    old = _with_promo("")
    try:
        assert launch_promo_state()["on"] is False
        body = client.get("/instant/availability?lat=52.6&lng=58.3",
                          headers=user_factory("PromoOff")["auth"]).json()
        assert body["launch_promo"]["on"] is False
    finally:
        settings.launch_promo_until = old


def test_promo_is_on_while_the_window_lasts(client, user_factory):
    old = _with_promo((utcnow() + timedelta(days=30)).date().isoformat())
    try:
        assert launch_promo_state()["on"] is True
        body = client.get("/instant/availability?lat=52.6&lng=58.3",
                          headers=user_factory("PromoOn")["auth"]).json()
        assert body["launch_promo"]["on"] is True
        assert body["launch_promo"]["days"] == settings.launch_promo_days
    finally:
        settings.launch_promo_until = old


def test_promo_is_off_after_the_window(client, user_factory):
    """Дата прошла — набор закрыт. Раньше приложение звало тем же обещанием и через год."""
    old = _with_promo((utcnow() - timedelta(days=1)).date().isoformat())
    try:
        assert launch_promo_state()["on"] is False
    finally:
        settings.launch_promo_until = old


def test_broken_date_in_env_does_not_promise_anything(client, user_factory):
    """Кривая дата в .env не должна включать промо «на всякий случай»."""
    old = _with_promo("не-дата")
    try:
        assert launch_promo_state()["on"] is False
    finally:
        settings.launch_promo_until = old
