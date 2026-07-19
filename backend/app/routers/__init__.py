"""Роутеры Юлдаша по доменам. `all_routers` — порядок подключения в main.py.

ВАЖНО: rides идёт ПОСЛЕ requests и т.п. неважно, но `/rides/{ride_id}`
объявлен в rides ПОСЛЕ статических `/rides/near`,`/rides/price_hint` — порядок
внутри файла сохранён, поэтому динамический путь не перехватывает их.
"""
from . import ads, auth, bookings, chat, discovery, drivers, family, health, incidents, location, payments, referral, requests, reviews, rides, safety

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
    safety.router,
    incidents.router,
    reviews.router,
    payments.router,
    ads.router,
    referral.router,
]
