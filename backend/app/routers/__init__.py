"""Роутеры Юлдаша по доменам. `all_routers` — порядок подключения в main.py.

ВАЖНО: rides идёт ПОСЛЕ requests и т.п. неважно, но `/rides/{ride_id}`
объявлен в rides ПОСЛЕ статических `/rides/near`,`/rides/price_hint` — порядок
внутри файла сохранён, поэтому динамический путь не перехватывает их.
"""
from . import ads, antifraud, auth, bookings, chat, debt, discovery, drivers, family, health, instant, location, notifications, payments, pickup, referral, requests, reviews, rides, route_watch, safety, settlements, share, taxi, trust, waitlist, wallet

all_routers = [
    health.router,
    auth.router,
    rides.router,
    requests.router,
    bookings.router,
    drivers.router,
    chat.router,
    location.router,
    discovery.router,
    family.router,
    share.router,   # публичная live-ссылка /t/{token} (B7c) — без auth, по токену
    safety.router,
    reviews.router,
    payments.router,
    ads.router,
    pickup.router,
    referral.router,
    notifications.router,
    route_watch.router,
    instant.router,
    wallet.router,
    debt.router,
    taxi.router,
    settlements.router,
    waitlist.router,
    antifraud.router,   # анти-фрод (B8): admin-баны устройств
    trust.router,       # уровни доверия (Фаза 4)

]
