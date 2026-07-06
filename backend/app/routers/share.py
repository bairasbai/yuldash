"""F16 — Публичная витрина поездки для расшаривания (viral loop).

Ссылка вида yulbash.ru/r/{id} ведёт СЮДА (через nginx-fallback на API):
- `GET /r/{id}/preview` — JSON без ПДн для карточки-превью (телефон/точка сбора НЕ отдаём).
- `GET /r/{id}` — server-rendered HTML с OG-тегами (красивая карточка в WhatsApp/Telegram)
  + deep-link «Открыть в приложении» + кнопка «Скачать Юлдаш».

Приватность (роль Security-инженера): раскрываем только то, что и так видно в ленте карты —
маршрут, время, цена, удобства, имя/рейтинг водителя. Телефон и точная точка встречи
доступны ТОЛЬКО участникам подтверждённой брони через `/bookings/{id}/details`.
"""
from html import escape

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import HTMLResponse
from sqlmodel import Session

from ..config import settings
from ..db import get_session
from ..models import Ride
from ..services import public_ride_payload, ride_out

router = APIRouter(tags=["share"])

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


@router.get("/r/{ride_id}/preview")
def ride_preview(ride_id: int, session: Session = Depends(get_session)) -> dict:
    """Публичные данные поездки для веб-превью и deep-link. Без ПДн."""
    ride = session.get(Ride, ride_id)
    if not ride:
        raise HTTPException(404, "Поездка не найдена")
    return _preview_dict(ride, session)


def _t(lang: str, ru: str, ba: str) -> str:
    return ba if lang == "ba" else ru


def _fmt_when(iso: str, lang: str) -> str:
    """ISO → короткое человекочитаемое «дд.мм, чч:мм» (без тяжёлых локалей)."""
    try:
        date, time = iso.split("T")
        y, m, d = date.split("-")
        hh, mm = time.split(":")[0], time.split(":")[1]
        return f"{d}.{m}, {hh}:{mm}"
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
    if not ride:
        raise HTTPException(404, "Поездка не найдена")
    lang = "ba" if str(lang).lower().startswith("ba") else "ru"
    return HTMLResponse(_render_html(_preview_dict(ride, session), lang))
