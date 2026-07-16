"""Роутеры Юлдаша по доменам. `all_routers` — порядок подключения в main.py.

ВАЖНО: rides идёт ПОСЛЕ requests и т.п. неважно, но `/rides/{ride_id}`
объявлен в rides ПОСЛЕ статических `/rides/near`,`/rides/price_hint` — порядок
внутри файла сохранён, поэтому динамический путь не перехватывает их.
"""
from . import ads, antifraud, auth, bookings, chat, coupons, courier, debt, discovery, driver_schedule, drivers, events, family, health, instant, location, medical, notifications, parcels, payments, pickup, places, promo, referral, requests, reviews, rides, route_watch, safety, settlements, share, stats, support, taxi, trust, waitlist, wallet

all_routers = [
    health.router,
    auth.router,
    rides.router,
    requests.router,
    bookings.router,
    drivers.router,
    driver_schedule.router,
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
    stats.router,       # «Мой Юлдаш» — личная статистика (F18)
    medical.router,     # клиники-партнёры (F22)
    coupons.router,     # партнёрский слой + купоны «Скидки по пути» (M1)
    promo.router,       # промокоды и кампании (M2) — рычаг роста: именные коды блогеров/акций
    parcels.router,     # доставка посылок между сёлами (M3) — символический сбор за вещь-доставку
    courier.router,     # профиль «Курьер» (C1) — профессия + заказать курьера + купи и привези
    places.router,      # сохранённые/недавние адреса — быстрый выбор точки в форме заказа
    support.router,     # поддержка внутри приложения (тикеты) — замена ссылки в Telegram
    events.router,      # анонимная продуктовая аналитика (веб-версия) — POST /events

]
