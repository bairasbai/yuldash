"""Live-ссылка поездки для близких (B7c): публичная страница /t/{token}.

Пассажир делится поездкой → близкий получает SMS со ссылкой → открывает В БРАУЗЕРЕ
(без приложения): живая карта (маршрут А→Б, движущаяся машина), статус поездки,
только имя пассажира. Работает и для такси-заказа (order), и для попутки (booking).

Безопасность (важно):
- Доступ — ТОЛЬКО по capability-токену (≥16 случайных байт, unique). Без auth.
- Пока поездка активна — статус + маршрут + машина; после done/отмены — «Поездка
  завершена ✅» БЕЗ координат. Никаких телефонов/фамилий/внутренних id.
- Отзыв share (DELETE в family.py) удаляет строку → токен «сгорает» (404).
- Координаты не логируем (access-лог маскирует /t/*** — middleware.py).
- Карта — Leaflet + OpenStreetMap-тайлы: без API-ключей (Яндекс JS-API требует ключ,
  для одноразовой публичной странички OSM прагматичнее). Ключей на странице нет.

Позиция машины — из Redis-кэша (livepos.py, пишется WS-хендлерами location.py,
TTL ~2 мин). Без Redis car=null — страница показывает статусы без машины, не падает.
"""
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse
from sqlmodel import Session, select

from ..db import get_session
from ..livepos import livepos_get
from ..models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus, Ride, TripShare, User,
)
from ..timeutil import utcnow

router = APIRouter(tags=["share"])

_NO_STORE = {"Cache-Control": "no-store", "X-Robots-Tag": "noindex, nofollow"}

# Фазы поездки для близкого — упрощённые (внутренние статусы наружу не отдаём).
# BA — черновики модели, финалит Александр (docs/tasks.md «Переводы — Live-ссылка»).
_PHASES = {
    "search":    {"ru": "Ищем машину…", "ba": "Машина эҙләйбеҙ…"},
    "wait":      {"ru": "Поездка скоро начнётся", "ba": "Сәфәр оҙакламай башлана"},
    "to_pickup": {"ru": "Машина едет к точке посадки", "ba": "Машина ултырыу урынына килә"},
    "onboard":   {"ru": "В пути", "ba": "Юлда"},
    "finished":  {"ru": "Поездка завершена ✅", "ba": "Сәфәр тамамланды ✅"},
}

_ORDER_LIVE_CAR = (InstantOrderStatus.accepted, InstantOrderStatus.arriving, InstantOrderStatus.onboard)


def _first_name(session: Session, user_id: int) -> str:
    """ТОЛЬКО имя (первое слово) — без фамилии/телефона (минимум перс.данных)."""
    u = session.get(User, user_id)
    name = (u.name or "").strip() if u else ""
    return name.split()[0] if name else "Пассажир"


def _resolve_share(session: Session, token: str) -> TripShare:
    if not token or len(token) < 16:   # короткий/пустой токен даже не ищем (анти-перебор)
        raise HTTPException(404, "Ссылка не найдена")
    share = session.exec(select(TripShare).where(TripShare.token == token)).first()
    if not share:
        raise HTTPException(404, "Ссылка не найдена")
    return share


def _order_state(session: Session, share: TripShare) -> dict:
    order = session.get(InstantOrder, share.order_id)
    if not order:
        raise HTTPException(404, "Ссылка не найдена")
    first_name = _first_name(session, order.passenger_id)
    if order.status in (InstantOrderStatus.done, InstantOrderStatus.cancelled, InstantOrderStatus.expired):
        return _finished(first_name)
    phase = "onboard" if order.status == InstantOrderStatus.onboard else (
        "to_pickup" if order.status in (InstantOrderStatus.accepted, InstantOrderStatus.arriving) else "search")
    car = livepos_get("order", order.id) if order.status in _ORDER_LIVE_CAR else None
    return _live(first_name, phase,
                 frm={"lat": order.from_lat, "lng": order.from_lng, "text": order.from_text or "Точка А"},
                 to={"lat": order.to_lat, "lng": order.to_lng, "text": order.to_text or "Точка Б"},
                 car=car)


def _booking_state(session: Session, share: TripShare) -> dict:
    booking = session.get(Booking, share.booking_id)
    ride = session.get(Ride, booking.ride_id) if booking else None
    if not booking or not ride:
        raise HTTPException(404, "Ссылка не найдена")
    first_name = _first_name(session, booking.passenger_id)
    if booking.status in (BookingStatus.done, BookingStatus.cancelled):
        return _finished(first_name)
    phase = "onboard" if booking.status == BookingStatus.onboard else (
        "to_pickup" if booking.status == BookingStatus.confirmed else "wait")
    car = livepos_get("booking", booking.id) \
        if booking.status in (BookingStatus.confirmed, BookingStatus.onboard) else None
    return _live(first_name, phase,
                 frm={"lat": ride.from_lat, "lng": ride.from_lng, "text": ride.from_city},
                 to={"lat": ride.to_lat, "lng": ride.to_lng, "text": ride.to_city},
                 car=car)


def _live(first_name: str, phase: str, frm: dict, to: dict, car) -> dict:
    car_out = None
    if car is not None:
        # Отдаём близкому только сами координаты/направление — без ts и прочей кухни.
        car_out = {"lat": car.get("lat"), "lng": car.get("lng"), "bearing": car.get("bearing")}
    return {
        "status": phase,
        "phase_text": _PHASES[phase],
        "from": frm,
        "to": to,
        "car": car_out,
        "passenger_first_name": first_name,
        "updated_at": utcnow().isoformat(),
    }


def _finished(first_name: str) -> dict:
    """Завершена/отменена: БЕЗ координат (маршрут и позиция после поездки — не дело ссылки)."""
    return {
        "status": "finished",
        "phase_text": _PHASES["finished"],
        "car": None,
        "passenger_first_name": first_name,
        "updated_at": utcnow().isoformat(),
    }


def _state(session: Session, share: TripShare) -> dict:
    if share.order_id:
        return _order_state(session, share)
    if share.booking_id:
        return _booking_state(session, share)
    raise HTTPException(404, "Ссылка не найдена")


@router.get("/t/{token}/state.json")
def live_state(token: str, session: Session = Depends(get_session)):
    """Публичное состояние поездки по токену. Страница поллит его каждые ~5 с."""
    share = _resolve_share(session, token)
    return JSONResponse(_state(session, share), headers=_NO_STORE)


@router.get("/t/{token}", response_class=HTMLResponse)
def live_page(token: str, session: Session = Depends(get_session)):
    """Server-rendered страница live-поездки: самодостаточная (inline CSS/JS),
    двуязычная (RU основной + BA подписи), мобильная. Все данные тянет из state.json
    и вставляет через textContent (никакого user-контента в HTML — анти-XSS)."""
    _resolve_share(session, token)   # невалидный/отозванный токен → 404 сразу
    return HTMLResponse(_PAGE_HTML, headers=_NO_STORE)


# Страница: без ключей и без сборки. Leaflet — с CDN (guard: нет CDN → статусы без карты).
_PAGE_HTML = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex, nofollow">
<title>Юлдаш — живая поездка · тере сәфәр</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" defer></script>
<style>
  :root{
    --green:#1E7A46; --green-dark:#155C34; --yellow:#F5B301;
    --ink:#14261C; --muted:#5F7268; --bg:#F4F7F5; --card:#FFFFFF; --line:#E3EAE5;
  }
  @media (prefers-color-scheme: dark){
    :root{ --ink:#EAF2ED; --muted:#93A79B; --bg:#0F1713; --card:#18231D; --line:#26332B; }
  }
  *{ margin:0; padding:0; box-sizing:border-box; }
  html,body{ height:100%; }
  body{
    font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
    background:var(--bg); color:var(--ink); display:flex; flex-direction:column;
  }
  header{ padding:calc(env(safe-area-inset-top) + 14px) 20px 12px; }
  .brand{ display:flex; align-items:center; gap:8px; font-weight:700; color:var(--green);
          font-size:15px; letter-spacing:.2px; }
  .brand .dot{ width:9px; height:9px; border-radius:50%; background:var(--yellow);
               animation:pulse 2s ease-in-out infinite; }
  @keyframes pulse{ 0%,100%{transform:scale(1);opacity:1} 50%{transform:scale(1.35);opacity:.55} }
  h1{ font-size:22px; font-weight:700; margin-top:10px; line-height:1.25; }
  .ba{ color:var(--muted); font-size:14px; margin-top:3px; }
  .route{ color:var(--muted); font-size:14px; margin-top:8px; overflow:hidden;
          text-overflow:ellipsis; white-space:nowrap; }
  #map{ flex:1; min-height:280px; margin:12px 14px calc(env(safe-area-inset-bottom) + 14px);
        border-radius:18px; border:1px solid var(--line); background:var(--card);
        overflow:hidden; opacity:0; transition:opacity .6s ease; }
  #map.on{ opacity:1; }
  .foot{ text-align:center; color:var(--muted); font-size:12px;
         padding:0 20px calc(env(safe-area-inset-bottom) + 12px); }
  .done{ flex:1; display:none; flex-direction:column; align-items:center; justify-content:center;
         gap:10px; padding:24px; text-align:center; }
  .done .check{ font-size:56px; animation:pop .5s cubic-bezier(.2,1.6,.4,1) both; }
  @keyframes pop{ from{transform:scale(.4);opacity:0} to{transform:scale(1);opacity:1} }
  .err{ display:none; flex:1; align-items:center; justify-content:center; color:var(--muted);
        padding:24px; text-align:center; font-size:15px; }
  .leaflet-marker-icon{ transition:transform 1.2s linear; }
  .carpin{ font-size:26px; line-height:1; filter:drop-shadow(0 1px 2px rgba(0,0,0,.35)); }
  .apin,.bpin{ width:16px; height:16px; border-radius:50%; border:3px solid #fff;
               box-shadow:0 1px 4px rgba(0,0,0,.3); }
  .apin{ background:var(--green); } .bpin{ background:var(--yellow); }
</style>
</head>
<body>
<header>
  <div class="brand"><span class="dot"></span>Юлдаш · живая поездка · тере сәфәр</div>
  <h1 id="title">Загружаем поездку…</h1>
  <div class="ba" id="title-ba">Сәфәр тураһында мәғлүмәт тейәйбеҙ…</div>
  <div class="route" id="route"></div>
</header>
<div id="map" role="img" aria-label="Карта поездки / Сәфәр картаһы"></div>
<div class="done" id="done">
  <div class="check">✅</div>
  <h1 id="done-title">Поездка завершена</h1>
  <div class="ba" id="done-ba">Сәфәр тамамланды</div>
</div>
<div class="err" id="err">Ссылка не работает или поездка недоступна.<br>Һылтанма эшләмәй йәки сәфәр асылмай.</div>
<div class="foot" id="foot">Обновляется каждые 5 секунд · Һәр 5 секунд һайын яңыртыла</div>
<script>
(function(){
  "use strict";
  var stateUrl = location.pathname.replace(/\\/+$/, "") + "/state.json";
  var map = null, carMarker = null, fitted = false, failures = 0;
  var el = function(id){ return document.getElementById(id); };

  function showError(){
    el("err").style.display = "flex";
    el("map").style.display = "none"; el("done").style.display = "none";
    el("foot").style.display = "none";
    el("title").textContent = "Юлдаш"; el("title-ba").textContent = ""; el("route").textContent = "";
  }

  function showFinished(st){
    el("map").style.display = "none"; el("done").style.display = "flex";
    el("route").textContent = ""; el("foot").style.display = "none";
    var who = st.passenger_first_name || "";
    el("title").textContent = who ? (who + " — поездка завершена ✅") : "Поездка завершена ✅";
    el("title-ba").textContent = st.phase_text && st.phase_text.ba ? st.phase_text.ba : "";
    el("done-title").textContent = st.phase_text && st.phase_text.ru ? st.phase_text.ru : "Поездка завершена ✅";
    el("done-ba").textContent = st.phase_text && st.phase_text.ba ? st.phase_text.ba : "";
  }

  function ensureMap(st){
    if (map || typeof L === "undefined") return;
    var f = st.from || {}, t = st.to || {};
    if (typeof f.lat !== "number" || typeof t.lat !== "number") return;
    map = L.map("map", { zoomControl:false, attributionControl:true });
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
      { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(map);
    L.marker([f.lat, f.lng], { icon: L.divIcon({ className:"apin", iconSize:[16,16] }) }).addTo(map);
    L.marker([t.lat, t.lng], { icon: L.divIcon({ className:"bpin", iconSize:[16,16] }) }).addTo(map);
    L.polyline([[f.lat, f.lng], [t.lat, t.lng]],
      { color:"#1E7A46", weight:4, opacity:.75, dashArray:"1 8", lineCap:"round" }).addTo(map);
    map.fitBounds([[f.lat, f.lng], [t.lat, t.lng]], { padding:[36,36] });
    fitted = true;
    el("map").classList.add("on");
  }

  function updateCar(st){
    if (!map || typeof L === "undefined") return;
    if (!st.car || typeof st.car.lat !== "number"){
      if (carMarker){ map.removeLayer(carMarker); carMarker = null; }
      return;
    }
    var pos = [st.car.lat, st.car.lng];
    if (!carMarker){
      carMarker = L.marker(pos, {
        icon: L.divIcon({ className:"", html:'<div class="carpin">🚗</div>', iconSize:[26,26], iconAnchor:[13,13] }),
        zIndexOffset: 1000
      }).addTo(map);
    } else {
      carMarker.setLatLng(pos);
    }
  }

  function render(st){
    if (st.status === "finished"){ showFinished(st); return true; }
    var who = st.passenger_first_name || "";
    var ru = st.phase_text && st.phase_text.ru ? st.phase_text.ru : "";
    el("title").textContent = who ? (who + " · " + ru) : ru;
    el("title-ba").textContent = st.phase_text && st.phase_text.ba ? st.phase_text.ba : "";
    var f = st.from || {}, t = st.to || {};
    el("route").textContent = (f.text && t.text) ? (f.text + " → " + t.text) : "";
    ensureMap(st);
    updateCar(st);
    return false;
  }

  function tick(){
    fetch(stateUrl, { cache: "no-store" }).then(function(r){
      if (r.status === 404){ showError(); return null; }
      if (!r.ok) throw new Error("http " + r.status);
      return r.json();
    }).then(function(st){
      if (!st) return;
      failures = 0;
      if (!render(st)) setTimeout(tick, 5000);
    }).catch(function(){
      failures += 1;
      if (failures >= 5) showError(); else setTimeout(tick, 7000);   // сеть мигнула — пробуем ещё
    });
  }
  tick();
})();
</script>
</body>
</html>
"""
