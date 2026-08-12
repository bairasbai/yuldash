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
from html import escape

from fastapi import APIRouter, Depends, Query
from fastapi.responses import HTMLResponse, JSONResponse
from sqlmodel import Session, select

import secrets
from datetime import datetime, timedelta, timezone

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..livepos import livepos_get
from ..models import (
    Booking, BookingStatus, InstantOrder, InstantOrderStatus, ParcelDelivery, Ride, RideStatus,
    TripShare, User,
)
from ..services import public_ride_payload, ride_out
from ..timeutil import utcnow

router = APIRouter(tags=["share"])

_NO_STORE = {"Cache-Control": "no-store", "X-Robots-Tag": "noindex, nofollow"}

# Публичная страница слежения — единственный HTML, который открывают ЧУЖИМ браузером, и на нём
# живые координаты человека. Заголовки ниже — вторая стена к тому, что уже есть (SRI на CDN,
# Referrer-Policy: no-referrer, X-Frame-Options: DENY, см. middleware).
#
# CSP перечисляет ровно то, что странице нужно: свой origin, Leaflet с unpkg, тайлы карты
# с OSM. Всё остальное браузер не загрузит и не выполнит — включая скрипт, который однажды
# смог бы туда попасть. Инлайновый скрипт страницы разрешается по одноразовому nonce, а не
# 'unsafe-inline': иначе разрешение распространялось бы и на чужой скрипт тоже.
#
# Стили: 'unsafe-inline' оставлен осознанно. Leaflet ставит стили прямо на элементы (позиция
# тайлов и маркеров), запрет сломал бы карту, а риск инлайнового СТИЛЯ несопоставим с риском
# инлайнового скрипта.
_CSP_TEMPLATE = (
    "default-src 'none'; "
    "script-src 'nonce-{nonce}' https://unpkg.com; "
    "style-src 'self' 'unsafe-inline' https://unpkg.com; "
    "img-src 'self' data: https://*.tile.openstreetmap.org; "
    "connect-src 'self'; "
    "base-uri 'none'; "
    "form-action 'none'; "
    "frame-ancestors 'none'"
)
# Странице не нужны ни камера, ни микрофон, ни геолокация САМОГО смотрящего: она показывает,
# где едет другой человек. Явный запрет — чтобы никакой будущий скрипт не спросил их от нашего
# имени (аудит 2026-08-12, волна 33).
_PERMISSIONS_POLICY = "geolocation=(), camera=(), microphone=(), payment=()"


def _page_headers(nonce: str) -> dict:
    return {
        **_NO_STORE,
        "Content-Security-Policy": _CSP_TEMPLATE.format(nonce=nonce),
        "Permissions-Policy": _PERMISSIONS_POLICY,
    }

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

# Фазы ДОСТАВКИ для получателя (G1). Статусы посылки наружу упрощаем так же, как поездку.
# BA — черновики модели, финалит Александр (docs/tasks.md «Переводы — Трекинг посылки»).
_PARCEL_PHASES = {
    "searching": {"ru": "Ищем курьера…", "ba": "Курьер эҙләйбеҙ…"},
    "accepted":  {"ru": "Курьер принял заказ", "ba": "Курьер заказды алды"},
    "onway":     {"ru": "Посылка в пути", "ba": "Бандероль юлда"},
    "finished":  {"ru": "Посылка доставлена ✅", "ba": "Бандероль еткерелде ✅"},
}
# Позицию курьера показываем получателю, пока он назначен и едет (accepted/in_transit —
# ровно когда курьер стримит гео, PARCEL_LOC_ACTIVE в location.py). До этого машины нет.
_PARCEL_STATUS_PHASE = {"created": "searching", "accepted": "accepted", "in_transit": "onway"}
_PARCEL_LIVE_CAR = ("accepted", "in_transit")


def _first_name(session: Session, user_id: int) -> str:
    """ТОЛЬКО имя (первое слово) — без фамилии/телефона (минимум перс.данных)."""
    u = session.get(User, user_id)
    name = (u.name or "").strip() if u else ""
    return name.split()[0] if name else "Пассажир"


def _first_name_of(name: str) -> str:
    """Первое слово имени получателя (у посылки имя — свободный текст, не User).
    Пусто → пустая строка: страница покажет просто фазу («Посылка в пути»), без имени."""
    name = (name or "").strip()
    return name.split()[0] if name else ""


def _resolve_share(session: Session, token: str) -> TripShare:
    if not token or len(token) < 16:   # короткий/пустой токен даже не ищем (анти-перебор)
        raise herr(404, "Ссылка не найдена", "Һылтанма табылманы")
    share = session.exec(select(TripShare).where(TripShare.token == token)).first()
    if not share:
        raise herr(404, "Ссылка не найдена", "Һылтанма табылманы")
    if share.expires_at and share.expires_at < utcnow():   # ссылка «сгорела» по TTL — гео не отдаём
        raise herr(404, "Ссылка не найдена", "Һылтанма табылманы")
    return share


def _order_state(session: Session, share: TripShare) -> dict:
    order = session.get(InstantOrder, share.order_id)
    if not order:
        raise herr(404, "Ссылка не найдена", "Һылтанма табылманы")
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
        raise herr(404, "Ссылка не найдена", "Һылтанма табылманы")
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


def _live(first_name: str, phase: str, frm: dict, to: dict, car, *,
          kind: str = "ride", phases: dict = _PHASES) -> dict:
    car_out = None
    if car is not None:
        # Отдаём близкому только сами координаты/направление — без ts и прочей кухни.
        car_out = {"lat": car.get("lat"), "lng": car.get("lng"), "bearing": car.get("bearing")}
    return {
        "kind": kind,                       # ride | parcel — страница выбирает иконку/подписи
        "status": phase,
        "phase_text": phases[phase],
        "from": frm,
        "to": to,
        "car": car_out,
        "passenger_first_name": first_name,
        "updated_at": utcnow().isoformat(),
    }


def _finished(first_name: str, *, kind: str = "ride", phases: dict = _PHASES) -> dict:
    """Завершена/отменена: БЕЗ координат (маршрут и позиция после поездки — не дело ссылки)."""
    return {
        "kind": kind,
        "status": "finished",
        "phase_text": phases["finished"],
        "car": None,
        "passenger_first_name": first_name,
        "updated_at": utcnow().isoformat(),
    }


def _parcel_state(session: Session, share: TripShare) -> dict:
    """G1: состояние ДОСТАВКИ для получателя. Статус посылки → упрощённая фаза; позиция
    курьера (livepos «parcel», пишется в location.py) — пока курьер назначен и едет. После
    вручения/отмены — «Посылка доставлена ✅» БЕЗ координат. Телефоны наружу не идут.

    to_address («куда именно» — дом/квартира/ориентир) отдаём: это СОБСТВЕННЫЙ адрес получателя,
    он по нему и ждёт. from_address (адрес отправителя) — чужие персональные данные: по публичной
    ссылке не уходит никогда. После вручения адреса тоже нет — там уже нет и маршрута."""
    parcel = session.get(ParcelDelivery, share.parcel_id)
    if not parcel:
        raise herr(404, "Ссылка не найдена", "Һылтанма табылманы")
    name = _first_name_of(parcel.receiver_name)      # имя получателя (первое слово) или ""
    if parcel.status in ("delivered", "canceled"):
        return _finished(name, kind="parcel", phases=_PARCEL_PHASES)
    phase = _PARCEL_STATUS_PHASE.get(parcel.status, "searching")
    car = livepos_get("parcel", parcel.id) if parcel.status in _PARCEL_LIVE_CAR else None
    out = _live(name, phase,
                frm={"lat": parcel.from_lat, "lng": parcel.from_lng, "text": parcel.from_city or "Точка А"},
                to={"lat": parcel.to_lat, "lng": parcel.to_lng, "text": parcel.to_city or "Точка Б"},
                car=car, kind="parcel", phases=_PARCEL_PHASES)
    out["to_address"] = getattr(parcel, "to_address", "") or ""
    return out


def _state(session: Session, share: TripShare) -> dict:
    if share.order_id:
        return _order_state(session, share)
    if share.booking_id:
        return _booking_state(session, share)
    if share.parcel_id:
        return _parcel_state(session, share)
    raise herr(404, "Ссылка не найдена", "Һылтанма табылманы")


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
    # nonce СВОЙ на каждый показ: угадать его заранее нельзя, значит и «пронести» скрипт
    # в страницу под чужим nonce тоже нельзя.
    nonce = secrets.token_urlsafe(16)
    return HTMLResponse(_PAGE_HTML.replace("__CSP_NONCE__", nonce), headers=_page_headers(nonce))


# Страница: без ключей и без сборки. Leaflet — с CDN (guard: нет CDN → статусы без карты).
_PAGE_HTML = """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex, nofollow">
<title>Юлдаш — живая поездка · тере сәфәр</title>
<!-- integrity/crossorigin (SRI) обязательны: страницу открывает близкий человек, и на ней
     живые координаты машины. Без хеша мы обещаем браузеру исполнить ЛЮБОЙ файл, который в этот
     момент отдаст unpkg.com; подмена на их стороне (или у того, кто до них дотянулся) означала бы
     чужой скрипт на странице с чьим-то местоположением. С хешем браузер сверяет содержимое и
     при несовпадении просто не подключает файл — сработает штатный запасной путь «нет CDN →
     статусы без карты» (аудит 2026-08-08). Хеши сверены с официальными для Leaflet 1.9.4. -->
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
      integrity="sha384-sHL9NAb7lN7rfvG5lfHpm643Xkcjzp4jFvuavGOndn6pjVqS6ny56CAt3nsEVT4H"
      crossorigin="anonymous" referrerpolicy="no-referrer">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" defer
        integrity="sha384-cxOPjt7s7Iz04uaHJceBmS+qpjv2JkIHNVcuOrM+YHwZOmJGBXI00mdUXEq65HTH"
        crossorigin="anonymous" referrerpolicy="no-referrer"></script>
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
  <div class="brand"><span class="dot"></span>Юлдаш · <span id="brandtxt">живая поездка · тере сәфәр</span></div>
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
<script nonce="__CSP_NONCE__">
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

  function setKind(kind){
    // Одна страница обслуживает и поездку, и посылку — подписи/иконку выбираем по kind.
    var parcel = (kind === "parcel");
    var sub = el("brandtxt");
    if (sub) sub.textContent = parcel ? "посылка · бандероль" : "живая поездка · тере сәфәр";
    document.title = parcel ? "Юлдаш — посылка · бандероль" : "Юлдаш — живая поездка · тере сәфәр";
  }

  function showFinished(st){
    el("map").style.display = "none"; el("done").style.display = "flex";
    el("route").textContent = ""; el("foot").style.display = "none";
    var who = st.passenger_first_name || "";
    // Текст финала берём из phase_text (ride → «Поездка завершена ✅», parcel → «Посылка доставлена ✅»).
    var ru = (st.phase_text && st.phase_text.ru) ? st.phase_text.ru : "Готово ✅";
    var ba = (st.phase_text && st.phase_text.ba) ? st.phase_text.ba : "";
    el("title").textContent = who ? (who + " · " + ru) : ru;
    el("title-ba").textContent = ba;
    el("done-title").textContent = ru;
    el("done-ba").textContent = ba;
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
      var emoji = (st.kind === "parcel") ? "📦" : "🚗";   // посылку везёт курьер → коробка, не машина
      carMarker = L.marker(pos, {
        icon: L.divIcon({ className:"", html:'<div class="carpin">' + emoji + '</div>', iconSize:[26,26], iconAnchor:[13,13] }),
        zIndexOffset: 1000
      }).addTo(map);
    } else {
      carMarker.setLatLng(pos);
    }
  }

  function render(st){
    setKind(st.kind);
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

# ===== F16: публичная OG-витрина /r/{ride_id} (viral share) =====

# Абсолютная база для OG/картинок/скачивания (тот же домен, что раздаёт лендинг).
SITE_BASE = settings.media_base_url.rstrip("/")
APK_URL = f"{SITE_BASE}/yuldash.apk"
OG_IMAGE = f"{SITE_BASE}/og.png"
ANDROID_PACKAGE = "com.yuldash.app"

# Человекочитаемые категории поездки (двуязычно).
_CATEGORY = {
    "ride": ("Поездка", "Сәфәр"),
    "parcel": ("Посылка", "Ебәрмә"),
    "intercity": ("Межгород", "Ҡалалар араһы"),
    "city": ("По городу", "Ҡала эсендә"),
}


def _preview_dict(ride: Ride, session: Session) -> dict:
    """PII-free витрина поездки. Явный белый список полей — чтобы новое поле
    в RideOut случайно не «протекло» в публичный ответ."""
    out = public_ride_payload(ride_out(ride, session))   # уже без pickup; телефона в RideOut нет
    return {
        "id": out.id,
        "from_city": out.from_city,
        "to_city": out.to_city,
        "depart_at": out.depart_at.isoformat(),
        "price": out.price,
        "seats_left": out.seats_left,
        "seats_total": out.seats_total,
        "category": out.category.value if hasattr(out.category, "value") else str(out.category),
        "status": out.status.value if hasattr(out.status, "value") else str(out.status),
        "comment": out.comment or "",
        # удобства (не ПДн) — для «дорогой» карточки
        "pets_allowed": out.pets_allowed,
        "child_seat": out.child_seat,
        "women_only": out.women_only,
        "baggage": out.baggage,
        "air_conditioner": out.air_conditioner,
        "smoking": out.smoking,
        # витрина водителя (имя показываем публично, как в ленте; телефон — НЕТ)
        "driver_name": out.driver_name,
        "driver_rating": out.driver_rating,
        "driver_verified": out.driver_verified,
        "driver_car": out.driver_car,
        "boosted": out.boosted,
        # осознанно НЕ отдаём: pickup/pickup_lat/lng, receiver_name, parcel_size,
        # driver_id/driver_avatar, любой телефон.
    }


def _shareable(ride: Ride) -> bool:
    """Публичную OG/preview-витрину показываем только для «живой» поездки и НЕ «только для своих».
    История (done/cancelled) и «круг своих» (only_trusted) по прямому /r/{id} не раскрываем — иначе
    перебор ride_id даёт анонимный скрейпинг графа поездок (кто/куда/когда возит). Паритет с /rides/{id}."""
    return ride.status == RideStatus.active and not getattr(ride, "only_trusted", False)


@router.get("/r/{ride_id}/preview")
def ride_preview(ride_id: int, session: Session = Depends(get_session)) -> dict:
    """Публичные данные поездки для веб-превью и deep-link. Без ПДн."""
    ride = session.get(Ride, ride_id)
    if not ride or not _shareable(ride):
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    return _preview_dict(ride, session)


def _t(lang: str, ru: str, ba: str) -> str:
    return ba if lang == "ba" else ru


def _fmt_when(iso: str, lang: str) -> str:
    """ISO (UTC из БД) → «дд.мм, чч:мм» по МЕСТНОМУ времени.

    Раньше строка просто резалась, и это случайно совпадало: время выезда хранилось в местных
    часах. После перевода базы на UTC (миграция y_utc_depart) резать стало нельзя — страница
    показывала бы время на пять часов раньше. А эту ссылку водитель кидает в семейный чат:
    карточка обещала бы 05:00 вместо 10:00, и человек вышел бы к подъезду не тогда.
    Приложение свою половину уже чинит (formatDepart) — здесь вторая половина того же бага.
    """
    try:
        base = iso.split(".")[0].replace("Z", "")
        dt = datetime.fromisoformat(base)
        if dt.tzinfo is not None:                      # пояс указан явно — верим ему
            dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
        local = dt + timedelta(hours=settings.local_tz_offset_hours)
        return f"{local.day:02d}.{local.month:02d}, {local.hour:02d}:{local.minute:02d}"
    except Exception:  # noqa: BLE001 — кривой формат не должен ронять страницу
        return iso


def _render_html(p: dict, lang: str) -> str:
    e = escape
    route = f"{e(p['from_city'])} → {e(p['to_city'])}"
    when = _fmt_when(p["depart_at"], lang)
    price = f"{p['price']} ₽"
    cat_ru, cat_ba = _CATEGORY.get(p["category"], ("Поездка", "Сәфәр"))
    driver = e(p["driver_name"])
    verified_badge = " ✓" if p["driver_verified"] else ""
    seats = p["seats_left"]

    title = _t(lang, f"{p['from_city']} → {p['to_city']} · {price} — Юлдаш",
               f"{p['from_city']} → {p['to_city']} · {price} — Юлдаш")
    desc = _t(
        lang,
        f"{_t(lang, cat_ru, cat_ba)} · {when} · водитель {p['driver_name']} · свободно мест: {seats}. "
        f"Попутка между своими по Башкортостану. Открой в Юлдаше.",
        f"{cat_ba} · {when} · йөрөтөүсе {p['driver_name']} · буш урын: {seats}. "
        f"Үҙебеҙҙекеләр араһында юллашыу. Юлдашта ас.",
    )
    page_url = f"{SITE_BASE}/r/{p['id']}"
    # Deep-link: если приложение стоит — откроет поездку в нём; иначе уйдёт на скачивание APK.
    intent_url = (
        f"intent://yulbash.ru/r/{p['id']}#Intent;scheme=https;"
        f"package={ANDROID_PACKAGE};S.browser_fallback_url={APK_URL};end"
    )

    amenities = []
    for key, (aru, aba) in (
        ("women_only", ("Только женщины", "Тик ҡатын-ҡыҙ")),
        ("child_seat", ("Детское кресло", "Бала ултырғысы")),
        ("pets_allowed", ("С животным", "Хайуан менән")),
        ("baggage", ("Багаж", "Йөк")),
        ("air_conditioner", ("Кондиционер", "Кондиционер")),
    ):
        if p.get(key):
            amenities.append(_t(lang, aru, aba))
    amenities_html = "".join(
        f'<span class="chip">{e(a)}</span>' for a in amenities
    )

    other_lang = "ru" if lang == "ba" else "ba"
    other_lang_label = _t(lang, "Башҡортса", "Русский")

    open_app = _t(lang, "Открыть в приложении", "Ҡушымтала асыу")
    download = _t(lang, "Скачать Юлдаш", "Юлдашты йөкләү")
    tagline = _t(lang, "Попутки между своими", "Үҙебеҙҙекеләр араһында юллашыу")
    seats_label = _t(lang, "свободно мест", "буш урын")
    driver_label = _t(lang, "Водитель", "Йөрөтөүсе")

    return f"""<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>{e(title)}</title>
<meta name="description" content="{e(desc)}" />
<link rel="canonical" href="{page_url}" />
<meta name="theme-color" content="#0A1410" />
<meta property="og:type" content="website" />
<meta property="og:site_name" content="Юлдаш" />
<meta property="og:title" content="{e(title)}" />
<meta property="og:description" content="{e(desc)}" />
<meta property="og:url" content="{page_url}" />
<meta property="og:image" content="{OG_IMAGE}" />
<meta property="og:image:width" content="1200" />
<meta property="og:image:height" content="630" />
<meta property="og:locale" content="{'ba_RU' if lang == 'ba' else 'ru_RU'}" />
<meta name="twitter:card" content="summary_large_image" />
<meta name="twitter:title" content="{e(title)}" />
<meta name="twitter:description" content="{e(desc)}" />
<meta name="twitter:image" content="{OG_IMAGE}" />
<link rel="icon" href="{SITE_BASE}/favicon.ico" />
<style>
  :root {{ --green:#0B6B3A; --green-bright:#2FB36E; --gold:#D89B12; --night:#0A1410; --ink:#0f2a1d; }}
  * {{ box-sizing:border-box; margin:0; padding:0; }}
  body {{ font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
         background:radial-gradient(120% 90% at 50% -10%, #0f2a1d 0%, var(--night) 60%);
         color:#eaf3ee; min-height:100vh; display:flex; flex-direction:column;
         align-items:center; padding:24px 18px 40px; }}
  .top {{ width:100%; max-width:460px; display:flex; justify-content:space-between;
         align-items:center; margin-bottom:22px; }}
  .brand {{ display:flex; align-items:center; gap:9px; font-weight:800; font-size:19px; letter-spacing:.3px; }}
  .dot {{ width:11px; height:11px; border-radius:50%;
         background:linear-gradient(135deg,var(--green-bright),var(--gold)); }}
  .lang {{ color:#9fb8ab; text-decoration:none; font-size:14px; border:1px solid rgba(255,255,255,.15);
          padding:6px 12px; border-radius:999px; }}
  .card {{ width:100%; max-width:460px; background:rgba(255,255,255,.05);
          border:1px solid rgba(255,255,255,.09); border-radius:22px; padding:26px 22px;
          backdrop-filter:blur(8px); box-shadow:0 20px 60px rgba(0,0,0,.35); }}
  .cat {{ display:inline-block; font-size:12px; font-weight:700; text-transform:uppercase;
         letter-spacing:1px; color:var(--gold); margin-bottom:12px; }}
  .route {{ font-size:27px; font-weight:800; line-height:1.2; margin-bottom:6px; }}
  .when {{ color:#a9c3b6; font-size:15px; margin-bottom:20px; }}
  .price {{ font-size:34px; font-weight:800; color:var(--green-bright); margin-bottom:2px; }}
  .seats {{ color:#a9c3b6; font-size:14px; margin-bottom:20px; }}
  .driver {{ display:flex; align-items:center; gap:12px; padding-top:18px;
            border-top:1px solid rgba(255,255,255,.08); }}
  .avatar {{ width:42px; height:42px; border-radius:50%; flex:0 0 auto;
            background:linear-gradient(135deg,var(--green),var(--green-bright));
            display:flex; align-items:center; justify-content:center; font-weight:800; font-size:18px; }}
  .dname {{ font-weight:700; font-size:16px; }}
  .dsub {{ color:#a9c3b6; font-size:13px; }}
  .chips {{ display:flex; flex-wrap:wrap; gap:8px; margin-top:16px; }}
  .chip {{ font-size:12.5px; color:#cfe6da; background:rgba(47,179,110,.14);
          border:1px solid rgba(47,179,110,.25); padding:5px 11px; border-radius:999px; }}
  .cta {{ width:100%; max-width:460px; margin-top:22px; display:flex; flex-direction:column; gap:12px; }}
  .btn {{ display:flex; align-items:center; justify-content:center; height:54px; border-radius:16px;
         font-size:16px; font-weight:700; text-decoration:none; }}
  .btn-primary {{ background:linear-gradient(135deg,var(--green-bright),var(--green)); color:#fff;
                 box-shadow:0 12px 30px rgba(47,179,110,.32); }}
  .btn-ghost {{ background:rgba(255,255,255,.06); color:#eaf3ee; border:1px solid rgba(255,255,255,.14); }}
  .foot {{ color:#7f9a8d; font-size:13px; margin-top:26px; text-align:center; }}
</style>
</head>
<body>
  <div class="top">
    <div class="brand"><span class="dot"></span>Юлдаш</div>
    <a class="lang" href="{page_url}?lang={other_lang}">{e(other_lang_label)}</a>
  </div>

  <div class="card">
    <span class="cat">{e(_t(lang, cat_ru, cat_ba))}</span>
    <div class="route">{route}</div>
    <div class="when">{e(when)}</div>
    <div class="price">{e(price)}</div>
    <div class="seats">{e(seats_label)}: {seats}</div>
    <div class="driver">
      <div class="avatar">{e(driver[:1].upper() if driver else "Ю")}</div>
      <div>
        <div class="dname">{driver}{verified_badge}</div>
        <div class="dsub">{e(driver_label)}{(' · ' + e(p['driver_car'])) if p['driver_car'] else ''} · ★ {p['driver_rating']}</div>
      </div>
    </div>
    {f'<div class="chips">{amenities_html}</div>' if amenities_html else ''}
  </div>

  <div class="cta">
    <a class="btn btn-primary" href="{e(intent_url)}">{e(open_app)}</a>
    <a class="btn btn-ghost" href="{APK_URL}">{e(download)}</a>
  </div>

  <div class="foot">Юлдаш · {e(tagline)}</div>
</body>
</html>"""


@router.get("/r/{ride_id}", response_class=HTMLResponse)
def ride_share_page(
    ride_id: int,
    lang: str = Query("ru"),
    session: Session = Depends(get_session),
):
    """Красивая server-rendered страница поездки с OG-тегами (карточка в мессенджерах)."""
    ride = session.get(Ride, ride_id)
    if not ride or not _shareable(ride):
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    lang = "ba" if str(lang).lower().startswith("ba") else "ru"
    return HTMLResponse(_render_html(_preview_dict(ride, session), lang))
