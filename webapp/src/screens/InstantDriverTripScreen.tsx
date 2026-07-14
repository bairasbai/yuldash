// ================================================================
//  Такси, водитель «на линии». RequireAuth → /taxi-drive
//  (зеркало backend routers/instant.py: presence, offer, accept/
//  arrived/onboard/done + taxi.py gate).
//
//  • Гейт: одобренная заявка таксиста (580-ФЗ) — иначе → онбординг.
//  • Тумблер «Я на линии» (POST /driver/online).
//  • Пока на линии: presence-heartbeat (геолокация) + опрос оффера.
//  • Оффер: полноэкранный оверлей с таймером ~20с «Взять»/«Пропустить»
//    (на вебе — баннер + звук/вибро вместо звонка).
//  • Активная поездка: навигация, «Приехал»/«Посадил»/«Завершить»,
//    телефон пассажира после accept, чат.
//
//  Мягкая деградация: instant/* появятся после мержа release-2026-07.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchDriverStatus, setDriverOnline, type DriverStatus } from "../api/driver";
import {
  fetchTaxiApplication,
  sendPresence,
  fetchDriverOffer,
  fetchInstantOrder,
  acceptOrder,
  declineOrder,
  arrivedOrder,
  onboardOrder,
  doneOrder,
  cancelInstantOrder,
  type InstantOrder,
} from "../api/instant";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList } from "../components/States";
import YandexMap, { type GeoPoint } from "../components/YandexMap";
import { IconCar, IconStar, IconPhone, IconChat, IconCheck } from "../components/Icons";
import { priceLabel } from "../utils/format";

type Boot = "loading" | "error" | "need-approval" | "ready";
const PRESENCE_MS = 15000;
const OFFER_POLL_MS = 3000;
const ACTIVE_KEY = "yuldash.taxi.activeOrder";

// Короткий сигнал оффера (WebAudio) + вибрация — вместо звонка на вебе.
function ringOffer() {
  try {
    const AC = (window as any).AudioContext || (window as any).webkitAudioContext;
    if (AC) {
      const ctx = new AC();
      const o = ctx.createOscillator();
      const g = ctx.createGain();
      o.connect(g);
      g.connect(ctx.destination);
      o.type = "sine";
      o.frequency.value = 880;
      g.gain.setValueAtTime(0.001, ctx.currentTime);
      g.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + 0.05);
      g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.6);
      o.start();
      o.stop(ctx.currentTime + 0.65);
    }
  } catch {
    /* звук не критичен */
  }
  try {
    navigator.vibrate?.([200, 100, 200]);
  } catch {
    /* вибро не критично */
  }
}

export default function InstantDriverTripScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [boot, setBoot] = useState<Boot>("loading");
  const [driver, setDriver] = useState<DriverStatus | null>(null);
  const [onlineBusy, setOnlineBusy] = useState(false);

  const [offer, setOffer] = useState<InstantOrder | null>(null);
  const [active, setActive] = useState<InstantOrder | null>(null);
  const posRef = useRef<GeoPoint | null>(null);

  // ---------------- Загрузка: заявка таксиста + статус водителя + активная поездка ----------------
  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchTaxiApplication(signal)
      .then((app) => {
        if (app.status !== "approved") {
          setBoot("need-approval");
          return;
        }
        return fetchDriverStatus(signal).then((st) => {
          setDriver(st);
          setBoot("ready");
          // Восстановление активной поездки (id в localStorage — у водителя нет /mine).
          const saved = Number(localStorage.getItem(ACTIVE_KEY) || 0);
          if (saved) {
            fetchInstantOrder(saved)
              .then((o) => {
                if (["accepted", "arriving", "onboard"].includes(o.status)) setActive(o);
                else localStorage.removeItem(ACTIVE_KEY);
              })
              .catch(() => localStorage.removeItem(ACTIVE_KEY));
          }
        });
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 404 = заявка не подана → онбординг; иначе ошибка.
        if (e instanceof ApiError && e.status === 404) setBoot("need-approval");
        else setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const online = !!driver?.online;

  // ---------------- Геолокация (watch) — держим последнюю позицию ----------------
  useEffect(() => {
    if (!online || !navigator.geolocation) return;
    const id = navigator.geolocation.watchPosition(
      (p) => {
        posRef.current = { lat: p.coords.latitude, lng: p.coords.longitude };
      },
      () => {},
      { enableHighAccuracy: true, maximumAge: 10000, timeout: 12000 }
    );
    return () => navigator.geolocation.clearWatch(id);
  }, [online]);

  // ---------------- Presence-heartbeat пока на линии ----------------
  useEffect(() => {
    if (!online) return;
    let alive = true;
    const beat = () => {
      const pos = posRef.current;
      if (!pos) return;
      sendPresence(pos.lat, pos.lng).catch((e) => {
        // 409 = «сначала включи на линии» рассинхрон; 403 = гейт → выключаем.
        if (alive && e instanceof ApiError && e.status === 403) {
          setDriver((d) => (d ? { ...d, online: false } : d));
        }
      });
    };
    beat();
    const iv = window.setInterval(beat, PRESENCE_MS);
    return () => {
      alive = false;
      window.clearInterval(iv);
    };
  }, [online]);

  // ---------------- Опрос оффера (пока на линии и нет активной поездки) ----------------
  useEffect(() => {
    if (!online || active) return;
    let alive = true;
    const tick = () => {
      fetchDriverOffer()
        .then((r) => {
          if (!alive) return;
          setOffer((prev) => {
            if (r.offer && (!prev || prev.id !== r.offer.id)) ringOffer();
            return r.offer;
          });
        })
        .catch(() => {});
    };
    tick();
    const iv = window.setInterval(tick, OFFER_POLL_MS);
    return () => {
      alive = false;
      window.clearInterval(iv);
    };
  }, [online, active]);

  // ---------------- Поллинг активной поездки (статус пассажира: отмена и т.п.) ----------------
  useEffect(() => {
    if (!active) return;
    let alive = true;
    const iv = window.setInterval(() => {
      fetchInstantOrder(active.id)
        .then((o) => {
          if (!alive) return;
          setActive(o);
          if (["done", "cancelled", "expired"].includes(o.status)) {
            localStorage.removeItem(ACTIVE_KEY);
          }
        })
        .catch(() => {});
    }, OFFER_POLL_MS);
    return () => {
      alive = false;
      window.clearInterval(iv);
    };
  }, [active?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  async function toggleOnline() {
    if (!driver || onlineBusy) return;
    setOnlineBusy(true);
    const next = !driver.online;
    setDriver({ ...driver, online: next });
    try {
      await setDriverOnline(next);
    } catch {
      setDriver({ ...driver, online: !next });
    } finally {
      setOnlineBusy(false);
    }
  }

  async function accept() {
    if (!offer) return;
    try {
      const o = await acceptOrder(offer.id);
      setActive(o);
      setOffer(null);
      localStorage.setItem(ACTIVE_KEY, String(o.id));
    } catch (e) {
      // 409 = кто-то принял раньше; просто убираем оффер.
      setOffer(null);
      if (!(e instanceof ApiError)) return;
    }
  }

  async function skip() {
    if (!offer) return;
    const id = offer.id;
    setOffer(null);
    try {
      await declineOrder(id);
    } catch {
      /* уже ушёл дальше */
    }
  }

  // ---------------- Активная поездка ----------------
  async function advance(next: "arrived" | "onboard" | "done") {
    if (!active) return;
    try {
      const fn = next === "arrived" ? arrivedOrder : next === "onboard" ? onboardOrder : doneOrder;
      const o = await fn(active.id);
      setActive(o);
      if (o.status === "done") localStorage.removeItem(ACTIVE_KEY);
    } catch {
      /* тихо — повторит по поллингу */
    }
  }

  async function cancelActive() {
    if (!active) return;
    try {
      await cancelInstantOrder(active.id);
    } catch {
      /* всё равно выходим */
    }
    localStorage.removeItem(ACTIVE_KEY);
    setActive(null);
  }

  // ---------------- Рендер ----------------
  if (boot === "loading") {
    return (
      <>
        <SubHeader title={appText("Я на линии", "Мин линияла")} onBack={() => navigate(-1)} />
        <LoadingList count={2} />
      </>
    );
  }

  if (boot === "error") {
    return (
      <>
        <SubHeader title={appText("Я на линии", "Мин линияла")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__emoji">📡</div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      </>
    );
  }

  if (boot === "need-approval") {
    return (
      <>
        <SubHeader title={appText("Такси Юлдаш", "Юлдаш такси")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__emoji">🚕</div>
          <h2>{appText("Сначала стань таксистом", "Башта таксист бул")}</h2>
          <p>
            {appText(
              "Чтобы возить пассажиров такси, нужна одобренная заявка (по 580-ФЗ). Это займёт пару минут.",
              "Такси юлаусыларын йөрөтөр өсөн хупланған ғариза кәрәк (580-ФЗ буйынса). Был бер-ике минут."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/taxi-onboarding")}>
            {appText("Стать таксистом", "Таксист булыу")}
          </button>
        </div>
      </>
    );
  }

  // Активная поездка перекрывает всё.
  if (active) {
    return (
      <DriverTrip
        order={active}
        pos={posRef.current}
        onAdvance={advance}
        onCancel={cancelActive}
        onChat={() => navigate(`/taxi-chat/${active.id}`)}
      />
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Я на линии (такси)", "Мин линияла (такси)")}
        subtitle={appText("Принимай быстрые заказы рядом", "Яҡындағы тиҙ заказдарҙы ал")}
        onBack={() => navigate(-1)}
      />

      <button
        type="button"
        className={"onb__simple" + (online ? " is-active" : "")}
        onClick={toggleOnline}
        disabled={onlineBusy}
      >
        <span className="onb__simple-emoji">{online ? "🟢" : "⚪️"}</span>
        <span className="onb__simple-text">
          <b>{appText("Я на линии", "Мин линияла")}</b>
          <span>
            {online
              ? appText("Ищем для тебя заказы рядом", "Һиңә яҡын заказдар эҙләйбеҙ")
              : appText("Включи, когда готов принимать", "Ҡабул итергә әҙер булғас ҡабыҙ")}
          </span>
        </span>
        <span className={"switch" + (online ? " on" : "")} />
      </button>

      {online ? (
        <div className="taxi-online-wait">
          <div className="taxi-search__pulse" aria-hidden>
            <IconCar size={38} />
          </div>
          <h2>{appText("Ждём заказ", "Заказ көтәбеҙ")}</h2>
          <p>
            {appText(
              "Как только рядом появится пассажир — покажем заказ со звуком. Держи телефон под рукой.",
              "Яҡында юлаусы сыҡһа — заказды тауыш менән күрһәтәбеҙ. Телефоныңды әҙер тот."
            )}
          </p>
        </div>
      ) : (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__emoji">🌙</div>
          <h2>{appText("Ты не на линии", "Һин линияла түгел")}</h2>
          <p>
            {appText(
              "Включи «Я на линии» — и начнём подбирать заказы поблизости.",
              "«Мин линияла»-ны ҡабыҙ — яҡындағы заказдарҙы табабыҙ."
            )}
          </p>
        </div>
      )}

      {/* Оффер — полноэкранный оверлей */}
      {offer && <OfferOverlay order={offer} ru={ru} onAccept={accept} onSkip={skip} />}
    </>
  );
}

// ----------------------------- Оффер: оверлей с таймером -----------------------------
function OfferOverlay({
  order,
  ru,
  onAccept,
  onSkip,
}: {
  order: InstantOrder;
  ru: boolean;
  onAccept: () => void;
  onSkip: () => void;
}) {
  const { appText } = useLang();
  const [left, setLeft] = useState(20);

  useEffect(() => {
    const exp = order.offer_expires_at ? new Date(order.offer_expires_at).getTime() : Date.now() + 20000;
    const tick = () => {
      const sec = Math.max(0, Math.round((exp - Date.now()) / 1000));
      setLeft(sec);
      if (sec <= 0) onSkip();
    };
    tick();
    const iv = window.setInterval(tick, 500);
    return () => window.clearInterval(iv);
  }, [order.id]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="offer-overlay" role="dialog" aria-modal="true">
      <div className="offer-card">
        <div className="offer-card__timer">{left}</div>
        <div className="offer-card__title">{appText("Новый заказ рядом!", "Яҡында яңы заказ!")}</div>
        <div className="offer-card__price">{priceLabel(order.price_estimate, ru)}</div>
        <div className="offer-card__route">
          <div className="offer-card__pt">
            <span className="taxi-route__dot taxi-route__dot--a" aria-hidden />
            {order.from_text || appText("Точка подачи", "Килеү нөктәһе")}
          </div>
          <div className="offer-card__pt">
            <span className="taxi-route__dot taxi-route__dot--b" aria-hidden />
            {order.to_text || appText("Точка назначения", "Барыр нөктә")}
          </div>
        </div>
        <div className="offer-card__meta">
          <span>{appText(`≈ ${Math.round(order.eta_min)} мин · ${order.distance_km} км`, `≈ ${Math.round(order.eta_min)} мин · ${order.distance_km} км`)}</span>
          {order.passenger_rating != null && (
            <span className="taxi-driver__rating">
              <IconStar size={14} /> {order.passenger_rating.toFixed(1)}
            </span>
          )}
          {order.category === "comfort" && (
            <span className="badge badge--gold">{appText("Комфорт", "Комфорт")}</span>
          )}
        </div>
        <div className="offer-card__actions">
          <button type="button" className="btn-ghost" onClick={onSkip}>
            {appText("Пропустить", "Үткәреү")}
          </button>
          <button type="button" className="btn-primary" onClick={onAccept}>
            {appText("Взять заказ", "Заказды алыу")}
          </button>
        </div>
      </div>
    </div>
  );
}

// ----------------------------- Активная поездка водителя -----------------------------
function DriverTrip({
  order,
  pos,
  onAdvance,
  onCancel,
  onChat,
}: {
  order: InstantOrder;
  pos: GeoPoint | null;
  onAdvance: (n: "arrived" | "onboard" | "done") => void;
  onCancel: () => void;
  onChat: () => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const fromPt: GeoPoint | null = order.from_lat != null ? { lat: order.from_lat, lng: order.from_lng ?? 0 } : null;
  const toPt: GeoPoint | null = order.to_lat != null ? { lat: order.to_lat, lng: order.to_lng ?? 0 } : null;
  const s = order.status;

  if (s === "done") {
    return (
      <>
        <SubHeader title={appText("Поездка завершена", "Сәфәр тамамланды")} onBack={() => navigate("/driver")} />
        <div className="taxi-done">
          <div className="taxi-done__emoji">✅</div>
          <div className="taxi-fare">
            <span>{appText("Заработано", "Табылды")}</span>
            <b>{priceLabel(order.price_final ?? order.price_estimate, ru)}</b>
          </div>
          <p className="taxi-done__hint">
            {appText(
              "Комиссия 8% начислена «на доверии». Спасибо, что возишь своих 🤝",
              "8% комиссия «ышаныс менән» иҫәпләнде. Үҙеңдекеләрҙе йөрөткәнең өсөн рәхмәт 🤝"
            )}
          </p>
        </div>
        <button type="button" className="btn-primary" style={{ marginTop: 14 }} onClick={() => navigate("/taxi-drive")}>
          {appText("Дальше на линию", "Линияға дауам")}
        </button>
      </>
    );
  }

  // Фаза → действие/заголовок.
  const phase =
    s === "accepted"
      ? { title: appText("Едем к пассажиру", "Юлаусыға барабыҙ"), action: "arrived" as const, btn: appText("Я приехал", "Килдем") }
      : s === "arriving"
        ? { title: appText("Ждём пассажира", "Юлаусыны көтәбеҙ"), action: "onboard" as const, btn: appText("Пассажир сел", "Юлаусы ултырҙы") }
        : { title: appText("В пути", "Юлда"), action: "done" as const, btn: appText("Завершить поездку", "Сәфәрҙе тамамлау") };

  return (
    <>
      <SubHeader title={phase.title} onBack={() => navigate("/driver")} />

      {(fromPt || toPt) && (
        <div className="home-map" style={{ marginTop: 4 }}>
          <YandexMap from={fromPt} to={toPt} me={pos} route={!!(fromPt && toPt)} height={210} />
        </div>
      )}

      {/* Пассажир */}
      <div className="taxi-driver">
        <div className="taxi-driver__avatar">🙋</div>
        <div className="taxi-driver__info">
          <div className="taxi-driver__name">
            {order.passenger_name || appText("Пассажир", "Юлаусы")}
          </div>
          <div className="taxi-driver__meta">
            {order.passenger_rating != null && (
              <span className="taxi-driver__rating">
                <IconStar size={14} /> {order.passenger_rating.toFixed(1)}
              </span>
            )}
            <span>{order.passenger_trips} {appText("поездок", "сәфәр")}</span>
          </div>
        </div>
        <div className="taxi-driver__actions">
          <button type="button" className="taxi-icon-btn" onClick={onChat} aria-label={appText("Чат", "Чат")}>
            <IconChat size={20} />
          </button>
          {order.passenger_phone && (
            <a
              className="taxi-icon-btn taxi-icon-btn--call"
              href={`tel:${order.passenger_phone}`}
              aria-label={appText("Позвонить пассажиру", "Юлаусыға шылтыратыу")}
            >
              <IconPhone size={20} />
            </a>
          )}
        </div>
      </div>

      {/* Маршрут */}
      <div className="info-list">
        <div className="info-row">
          <span className="info-row__k">{appText("Подача", "Килеү")}</span>
          <span className="info-row__v">{order.from_text || appText("Точка А", "А нөктә")}</span>
        </div>
        <div className="info-row">
          <span className="info-row__k">{appText("Назначение", "Барыр урын")}</span>
          <span className="info-row__v">{order.to_text || appText("Точка Б", "Б нөктә")}</span>
        </div>
        <div className="info-row">
          <span className="info-row__k">{appText("Цена", "Хаҡ")}</span>
          <span className="info-row__v">{priceLabel(order.price_estimate, ru)}</span>
        </div>
      </div>

      <button type="button" className="btn-primary submit-btn" style={{ marginTop: 14 }} onClick={() => onAdvance(phase.action)}>
        <IconCheck size={18} /> {phase.btn}
      </button>

      {(s === "accepted" || s === "arriving") && (
        <button type="button" className="btn-ghost" style={{ marginTop: 10 }} onClick={onCancel}>
          {appText("Отменить заказ", "Заказды кире алыу")}
        </button>
      )}
    </>
  );
}
