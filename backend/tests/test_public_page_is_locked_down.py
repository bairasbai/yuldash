# -*- coding: utf-8 -*-
"""Публичная страница слежения: ключ от чужой поездки не должен утечь.

Аудит 2026-08-12, волна 33. `/t/{token}` — единственный наш HTML, который открывают чужим
браузером, и на нём живые координаты человека. Токен стоит прямо в адресе: кто знает ссылку,
тот видит, где человек едет сейчас.

Что уже было (прошлые волны): SRI на скрипт с CDN, `Referrer-Policy: no-referrer`,
`X-Frame-Options: DENY`, `Cache-Control: no-store`, срок жизни ссылки.

Чего не было: CSP (страница грузит сторонний скрипт и тайлы карты), запрета на камеру
и геолокацию, HSTS. Проверено настоящим браузером: с этой политикой карта грузится,
тайлы приходят, инлайновый скрипт работает по одноразовому nonce.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus, TripShare, UserRole
from app.timeutil import utcnow


@pytest.fixture
def live_link(client, user_factory):
    """Готовая ссылка слежения на активную бронь."""
    pax = user_factory("Пассажир под слежением")
    drv = user_factory("Водитель под слежением", role=UserRole.driver)
    # Токен уникален у каждого теста: колонка с уникальным индексом, а база у прогона одна.
    token = f"guardtoken33{pax['id']}" + "x" * 20
    with Session(engine) as s:
        r = Ride(driver_id=drv["id"], from_city="Баймак", to_city="Сибай", price=300, seats=3,
                 depart_at=utcnow() + timedelta(hours=5), status=RideStatus.active,
                 from_lat=52.59, from_lng=58.31, to_lat=52.71, to_lng=58.66)
        s.add(r)
        s.commit()
        s.refresh(r)
        b = Booking(ride_id=r.id, passenger_id=pax["id"], seats=1,
                    status=BookingStatus.confirmed, price=300)
        s.add(b)
        s.commit()
        s.refresh(b)
        s.add(TripShare(booking_id=b.id, token=token, expires_at=utcnow() + timedelta(days=1)))
        s.commit()
    return token


def test_страница_слежения_ничего_лишнего_не_грузит(client, live_link):
    """Браузеру разрешено ровно то, что нужно карте, и ничего сверх."""
    r = client.get(f"/t/{live_link}")
    assert r.status_code == 200
    csp = r.headers.get("Content-Security-Policy", "")
    assert csp, "у страницы с чужими координатами нет политики безопасности"
    assert "default-src 'none'" in csp                    # по умолчанию — ничего
    assert "https://unpkg.com" in csp                     # карта (Leaflet)
    assert "tile.openstreetmap.org" in csp                # тайлы карты
    assert "connect-src 'self'" in csp                    # состояние тянем только у себя
    assert "frame-ancestors 'none'" in csp                # чужой сайт нас не встроит
    assert "'unsafe-inline'" not in csp.split("script-src")[1].split(";")[0], \
        "инлайновый скрипт разрешён огулом — тогда разрешён и чужой"


def test_каждый_показ_страницы_получает_свой_одноразовый_ключ(client, live_link):
    """nonce угадать заранее нельзя: два показа — два разных значения, и он есть в самом теге."""
    first = client.get(f"/t/{live_link}")
    second = client.get(f"/t/{live_link}")
    n1 = first.headers["Content-Security-Policy"].split("'nonce-")[1].split("'")[0]
    n2 = second.headers["Content-Security-Policy"].split("'nonce-")[1].split("'")[0]
    assert n1 != n2, "nonce повторяется — значит его можно подставить заранее"
    assert f'<script nonce="{n1}">' in first.text, "nonce не доехал до самого скрипта"
    assert "__CSP_NONCE__" not in first.text, "в странице осталась заглушка вместо nonce"


def test_страница_не_просит_камеру_и_геолокацию_смотрящего(client, live_link):
    """Смотрящий видит, где едет ДРУГОЙ человек. Его собственные камера и место тут ни при чём."""
    r = client.get(f"/t/{live_link}")
    policy = r.headers.get("Permissions-Policy", "")
    for capability in ("geolocation=()", "camera=()", "microphone=()"):
        assert capability in policy, f"не запрещено: {capability}"


def test_прежние_защиты_на_месте(client, live_link):
    """Регресс: то, что закрыли прошлые волны, не должно отвалиться при новой правке."""
    r = client.get(f"/t/{live_link}")
    assert r.headers.get("Referrer-Policy") == "no-referrer"   # токен не уйдёт с Referer
    assert r.headers.get("X-Frame-Options") == "DENY"
    assert r.headers.get("Cache-Control") == "no-store"
    assert "noindex" in r.headers.get("X-Robots-Tag", "")
    assert "integrity=" in r.text, "пропала проверка целостности скрипта с CDN (SRI)"


def test_в_проде_браузер_запоминает_только_https(client, live_link, monkeypatch):
    """Ссылку присылают сообщением; один переход по http означал бы ключ открытым текстом."""
    monkeypatch.setattr(settings, "env", "prod")
    r = client.get(f"/t/{live_link}")
    hsts = r.headers.get("Strict-Transport-Security", "")
    assert "max-age=" in hsts and "includeSubDomains" in hsts, hsts


def test_в_dev_hsts_не_ставим(client, live_link):
    """Иначе браузер разработчика на год закроет себе доступ к localhost по http."""
    r = client.get(f"/t/{live_link}")
    assert "Strict-Transport-Security" not in r.headers
