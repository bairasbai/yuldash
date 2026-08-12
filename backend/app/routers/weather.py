"""Погода на маршруте: предупреждение о гололёде, метели, тумане и морозе перед выездом.

Ручка отдаёт ГОТОВЫЕ фразы на двух языках — клиент их только рисует. Так решение «что считать
опасным» живёт в одном месте: пороги можно поправить на сервере, не выпуская новое приложение.

Данные и приватность — см. `app.weather_warn`: координаты округляются до ~5 км перед запросом
к внешнему источнику, точный адрес человека наружу не уходит.
"""
from datetime import datetime
from typing import Optional

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel

from ..models import User
from ..security import current_user_optional
from ..services import geocode_city
from ..timeutil import client_dt_to_utc, utcnow
from ..weather_warn import route_weather

router = APIRouter(tags=["weather"])


class WeatherWarningOut(BaseModel):
    kind: str          # ice | blizzard | snow | fog | wind | frost | thunder
    ru: str
    ba: str
    severe: bool


class RouteWeatherOut(BaseModel):
    available: bool                       # false → карточку не показывать вовсе
    warnings: list[WeatherWarningOut] = []
    temperature_c: Optional[float] = None


def _out(data) -> RouteWeatherOut:
    return RouteWeatherOut(
        available=data.available,
        warnings=[WeatherWarningOut(kind=w.kind, ru=w.ru, ba=w.ba, severe=w.severe)
                  for w in data.warnings],
        temperature_c=data.temperature_c,
    )


@router.get("/weather/route", response_model=RouteWeatherOut)
def weather_for_route(
    from_lat: Optional[float] = Query(None, ge=-90, le=90),
    from_lng: Optional[float] = Query(None, ge=-180, le=180),
    to_lat: Optional[float] = Query(None, ge=-90, le=90),
    to_lng: Optional[float] = Query(None, ge=-180, le=180),
    from_city: Optional[str] = Query(None, max_length=80),
    to_city: Optional[str] = Query(None, max_length=80),
    at: Optional[str] = Query(None, description="время выезда, ISO; пусто = сейчас"),
    user: Optional[User] = Depends(current_user_optional),
):
    """Предупреждения для произвольного маршрута (форма создания поездки, экран заказа такси).

    Маршрут можно задать координатами ИЛИ названиями городов: в форме публикации человек
    печатает «Баймак → Сибай», и заставлять клиент отдельно геокодить их ради погоды — лишние
    запросы к платному геокодеру и лишняя задержка. Названия резолвит сервер тем же
    справочником, что и поиск поездок.

    Вход открыт и без входа в аккаунт: это данные о погоде, а не о человеке. Ограничение
    нагрузки общее (rate-limit по IP), отдельного тут не нужно — ответы кешируются на полчаса
    и одинаковы для всех, кто едет тем же маршрутом.

    Названия резолвим ТОЛЬКО по своим спискам (`allow_external=False`). Платный Яндекс.Геокодер
    из открытой двери недоступен: иначе любой прохожий выбирал бы наш суточный лимит выдуманными
    названиями, а вместе с лимитом ложился бы радиус-поиск поездок у настоящих людей
    (аудит 2026-08-12, волна 34).
    """
    when = utcnow()
    if at:
        try:
            when = client_dt_to_utc(datetime.fromisoformat(at.replace("Z", "+00:00")))
        except (ValueError, TypeError):
            when = utcnow()   # кривое время от клиента — не ошибка, просто смотрим на сейчас

    points: list[tuple[float, float]] = []
    if from_lat is not None and from_lng is not None:
        points.append((from_lat, from_lng))
    elif from_city:
        found = geocode_city(from_city.strip(), allow_external=False)
        if found:
            points.append(found)
    if to_lat is not None and to_lng is not None:
        points.append((to_lat, to_lng))
    elif to_city:
        found = geocode_city(to_city.strip(), allow_external=False)
        if found:
            points.append(found)
    if not points:
        # Ни координат, ни известного города — честно «данных нет». Не ошибка: человек мог
        # ещё не дописать название, и ронять ему форму из-за погоды нельзя.
        return RouteWeatherOut(available=False)
    return _out(route_weather(points, when))

# Ручки «погода по id поездки» здесь СОЗНАТЕЛЬНО нет.
#
# Она была и её убрал сторож `test_foreign_objects_sweep`: отвечая 200 на любой id, она
# подтверждала посторонему сам ФАКТ существования чужой поездки. Погода — не персональные
# данные, но дверь, которая говорит «такая поездка есть», — уже утечка, и заводить её ради
# удобства не стоит. Экран деталей и так знает города и время выезда: он зовёт /weather/route.
