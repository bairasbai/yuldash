// ================================================================
//  «Быстрый заказ» — такси, пассажир. RequireAuth → /taxi
//  (зеркало backend routers/instant.py + taxi.py).
//
//  Поток: гейт города (availability) → «Куда едем» (точка Б + карта,
//  быстрые адреса Дом/Работа/недавние) → оценка цены (estimate,
//  класс Эконом/Комфорт) → «Сейчас / На время» → «Вызвать» →
//  «Ищем машину» (поллинг) → «Водитель едет» (карта A→B, ETA,
//  телефон ПОСЛЕ accept, чат, отмена). Восстановление активного заказа.
//
//  Мягкая деградация: instant/* появятся на проде после мержа
//  release-2026-07 → до этого 404/405 → «Такси скоро».
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchTaxiAvailability,
  instantEstimate,
  createInstantOrder,
  createScheduledOrder,
  fetchMyOrders,
  fetchInstantOrder,
  cancelInstantOrder,
  rateInstantOrder,
  fetchNearbyDrivers,
  ACTIVE_PASSENGER_STATUSES,
  isUnlocked,
  type InstantOrder,
  type EstimateResult,
  type TaxiCategory,
  type NearbyDriver,
} from "../api/instant";
import { fetchSavedPlaces, fetchRecentPlaces } from "../api/places";
import { geocode } from "../api/discovery";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList } from "../components/States";
import YandexMap, { type GeoPoint } from "../components/YandexMap";
import {
  IconCar,
  IconStar,
  IconPhone,
  IconChat,
  IconHome,
  IconWork,
  IconPin,
  IconClock,
} from "../components/Icons";
import { priceLabel } from "../utils/format";

type Point = { lat: number; lng: number; text: string };
type View = "boot" | "gate" | "compose" | "tracking";

const POLL_MS = 3500;

export default function InstantOrderScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [view, setView] = useState<View>("boot");
  const [gateMsg, setGateMsg] = useState<{ ru: string; ba: string } | null>(null);

  // Точка А — «моё место» (геолокация). Точка Б — назначение.
  const [from, setFrom] = useState<Point | null>(null);
  const [to, setTo] = useState<Point | null>(null);

  const [order, setOrder] = useState<InstantOrder | null>(null);

  // ---------------- boot: гео → доступность → восстановление активного ----------------
  const boot = useCallback(() => {
    setView("boot");
    const proceed = (pt: GeoPoint | null) => {
      if (pt) setFrom({ lat: pt.lat, lng: pt.lng, text: appText("Моё место", "Урыным") });
      // Сначала — есть ли уже живой заказ (восстановление экрана).
      fetchMyOrders(5)
        .then((list) => {
          const live = list.find((o) => ACTIVE_PASSENGER_STATUSES.includes(o.status));
          if (live) {
            setOrder(live);
            setView("tracking");
            return;
          }
          checkAvailability(pt);
        })
        .catch((e) => {
          // 404 (эндпоинта ещё нет) — идём проверять гейт, он тоже мягко деградирует.
          if (e instanceof ApiError && e.status === 404) checkAvailability(pt);
          else checkAvailability(pt);
        });
    };
    const checkAvailability = (pt: GeoPoint | null) => {
      fetchTaxiAvailability(pt?.lat, pt?.lng)
        .then((av) => {
          if (av.enabled) setView("compose");
          else {
            setGateMsg(av.message);
            setView("gate");
          }
        })
        .catch(() => {
          // Эндпоинта ещё нет / нет сети → мягкий гейт «скоро».
          setGateMsg(null);
          setView("gate");
        });
    };

    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (p) => proceed({ lat: p.coords.latitude, lng: p.coords.longitude }),
        () => proceed(null),
        { timeout: 8000, maximumAge: 60000 }
      );
    } else {
      proceed(null);
    }
  }, [appText]);

  useEffect(() => {
    boot();
  }, [boot]);

  // ---------------- Поллинг активного заказа ----------------
  useEffect(() => {
    if (view !== "tracking" || !order) return;
    let alive = true;
    const tick = () => {
      fetchInstantOrder(order.id)
        .then((o) => alive && setOrder(o))
        .catch(() => {});
    };
    const iv = window.setInterval(tick, POLL_MS);
    return () => {
      alive = false;
      window.clearInterval(iv);
    };
  }, [view, order?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  function backToCompose() {
    setOrder(null);
    setTo(null);
    setView("compose");
  }

  // ---------------- Рендер по фазам ----------------
  if (view === "boot") {
    return (
      <>
        <SubHeader title={appText("Такси Юлдаш", "Юлдаш такси")} onBack={() => navigate(-1)} />
        <LoadingList count={2} />
      </>
    );
  }

  if (view === "gate") {
    return (
      <>
        <SubHeader title={appText("Такси Юлдаш", "Юлдаш такси")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__emoji">🚕</div>
          <h2>{appText("Такси скоро в вашем городе", "Такси тиҙҙән ҡалағыҙҙа")}</h2>
          <p>
            {gateMsg
              ? (ru ? gateMsg.ru : gateMsg.ba)
              : appText(
                  "Мы уже готовим быстрый заказ у вас. Загляни чуть позже — а пока попутки ждут на карте.",
                  "Тиҙ заказды әҙерләйбеҙ. Аҙыраҡ һуңынан кил — әлегә юлдаштар картала көтә."
                )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/map")}>
            {appText("К попуткам", "Юлдаштарға")}
          </button>
        </div>
      </>
    );
  }

  if (view === "tracking" && order) {
    return (
      <TrackingView
        order={order}
        from={from}
        onCancelled={backToCompose}
        onNewOrder={backToCompose}
        onOpenChat={() => navigate(`/taxi-chat/${order.id}`)}
      />
    );
  }

  return (
    <ComposeView
      from={from}
      to={to}
      setFrom={setFrom}
      setTo={setTo}
      onOrdered={(o) => {
        setOrder(o);
        setView("tracking");
      }}
      onScheduled={() => navigate("/scheduled")}
    />
  );
}

// ================================================================
//  Составление заказа: точка Б, оценка, класс, «Сейчас/На время».
// ================================================================
function ComposeView({
  from,
  to,
  setFrom,
  setTo,
  onOrdered,
  onScheduled,
}: {
  from: Point | null;
  to: Point | null;
  setFrom: (p: Point | null) => void;
  setTo: (p: Point | null) => void;
  onOrdered: (o: InstantOrder) => void;
  onScheduled: () => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [category, setCategory] = useState<TaxiCategory>("standard");
  const [estimate, setEstimate] = useState<EstimateResult | null>(null);
  const [estimating, setEstimating] = useState(false);
  const [when, setWhen] = useState<"now" | "later">("now");
  const [schedAt, setSchedAt] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [nearby, setNearby] = useState<NearbyDriver[]>([]);

  // Анонимные машинки рядом (для «живой» карты).
  useEffect(() => {
    if (!from) return;
    let alive = true;
    fetchNearbyDrivers(from.lat, from.lng)
      .then((r) => alive && setNearby(r.drivers))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [from?.lat, from?.lng]); // eslint-disable-line react-hooks/exhaustive-deps

  // Оценка, как только есть обе точки (и при смене класса).
  useEffect(() => {
    if (!from || !to) {
      setEstimate(null);
      return;
    }
    let alive = true;
    setEstimating(true);
    setError(null);
    instantEstimate({
      from_lat: from.lat,
      from_lng: from.lng,
      to_lat: to.lat,
      to_lng: to.lng,
      from_text: from.text,
      to_text: to.text,
      category,
    })
      .then((e) => alive && setEstimate(e))
      .catch((e) => {
        if (!alive) return;
        setEstimate(null);
        setError(
          e instanceof ApiError && e.status === 404
            ? appText("Оценка появится после обновления сервиса.", "Баһалау яңыртыуҙан һуң күренәсәк.")
            : e instanceof ApiError && e.message
              ? e.message
              : appText("Не получилось оценить поездку.", "Сәфәрҙе баһаларға булманы.")
        );
      })
      .finally(() => alive && setEstimating(false));
    return () => {
      alive = false;
    };
  }, [from?.lat, from?.lng, to?.lat, to?.lng, category]); // eslint-disable-line react-hooks/exhaustive-deps

  function priceFor(cat: TaxiCategory): number | null {
    const opt = estimate?.options?.find((o) => o.category === cat);
    return opt ? opt.price : cat === category ? estimate?.price ?? null : null;
  }

  async function order() {
    if (!from || !to || busy) return;
    setBusy(true);
    setError(null);
    const body = {
      from_lat: from.lat,
      from_lng: from.lng,
      to_lat: to.lat,
      to_lng: to.lng,
      from_text: from.text,
      to_text: to.text,
      category,
    };
    try {
      if (when === "later") {
        if (!schedAt) {
          setError(appText("Выбери время подачи.", "Килеү ваҡытын һайла."));
          setBusy(false);
          return;
        }
        // datetime-local → ISO с локальной таймзоной (бэк нормализует в UTC).
        await createScheduledOrder({ ...body, scheduled_at: new Date(schedAt).toISOString() });
        onScheduled();
      } else {
        const o = await createInstantOrder(body);
        onOrdered(o);
      }
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать заказ. Попробуй снова.", "Заказ булманы. Ҡабат ҡара.")
      );
      setBusy(false);
    }
  }

  const canOrder = !!from && !!to && !busy && (when === "now" || !!schedAt);

  return (
    <>
      <SubHeader
        title={appText("Куда едем?", "Ҡайҙа барабыҙ?")}
        subtitle={appText("Быстрый заказ · такси между своими", "Тиҙ заказ · үҙебеҙ араһында такси")}
        onBack={() => navigate(-1)}
      />

      <div className="home-map" style={{ marginTop: 4 }}>
        <YandexMap
          from={from}
          to={to}
          route={!!(from && to)}
          markers={
            !to
              ? nearby.map((d, i) => ({ id: `d${i}`, lat: d.lat, lng: d.lng, kind: "me" as const }))
              : undefined
          }
          height={200}
        />
      </div>

      {/* Точки маршрута */}
      <div className="taxi-route">
        <div className="taxi-route__row">
          <span className="taxi-route__dot taxi-route__dot--a" aria-hidden />
          <div className="taxi-route__text">
            <span className="taxi-route__label">{appText("Откуда", "Ҡайҙан")}</span>
            <span className="taxi-route__value">
              {from ? from.text : appText("Определяем место…", "Урын билдәләнә…")}
            </span>
          </div>
        </div>
        <PlacePicker
          value={to}
          onPick={setTo}
          onUseMyLocation={
            from
              ? undefined
              : () => {
                  if (!navigator.geolocation) return;
                  navigator.geolocation.getCurrentPosition((p) =>
                    setFrom({
                      lat: p.coords.latitude,
                      lng: p.coords.longitude,
                      text: appText("Моё место", "Урыным"),
                    })
                  );
                }
          }
        />
      </div>

      {/* Классы с ценами */}
      {to && (
        <div className="taxi-classes">
          {(["standard", "comfort"] as TaxiCategory[]).map((cat) => {
            const price = priceFor(cat);
            const active = category === cat;
            return (
              <button
                key={cat}
                type="button"
                className={"taxi-class" + (active ? " is-active" : "")}
                onClick={() => setCategory(cat)}
              >
                <span className="taxi-class__icon">
                  <IconCar size={26} />
                </span>
                <span className="taxi-class__name">
                  {cat === "standard" ? appText("Эконом", "Эконом") : appText("Комфорт", "Комфорт")}
                </span>
                <span className="taxi-class__price">
                  {estimating && price == null
                    ? "…"
                    : price != null
                      ? priceLabel(price, ru)
                      : "—"}
                </span>
              </button>
            );
          })}
        </div>
      )}

      {/* Сурж */}
      {to && estimate?.surge_note && (
        <div className="taxi-surge">
          ⚡ {ru ? estimate.surge_note.ru : estimate.surge_note.ba}
        </div>
      )}

      {/* Сейчас / На время */}
      {to && (
        <>
          <div className="taxi-when">
            <button
              type="button"
              className={"taxi-when__tab" + (when === "now" ? " is-active" : "")}
              onClick={() => setWhen("now")}
            >
              {appText("Сейчас", "Хәҙер")}
            </button>
            <button
              type="button"
              className={"taxi-when__tab" + (when === "later" ? " is-active" : "")}
              onClick={() => setWhen("later")}
            >
              <IconClock size={16} /> {appText("На время", "Ваҡытҡа")}
            </button>
          </div>
          {when === "later" && (
            <label className="field" style={{ marginTop: 10 }}>
              <span className="field__label">{appText("Время подачи", "Килеү ваҡыты")}</span>
              <input
                className="field__input"
                type="datetime-local"
                value={schedAt}
                onChange={(e) => setSchedAt(e.target.value)}
              />
            </label>
          )}
        </>
      )}

      {error && <div className="auth__error">{error}</div>}

      {estimate && (
        <div className="taxi-estimate-hint">
          {appText(
            `≈ ${Math.round(estimate.eta_min)} мин в пути · ${estimate.distance_km} км`,
            `≈ ${Math.round(estimate.eta_min)} мин юлда · ${estimate.distance_km} км`
          )}
        </div>
      )}

      <button
        type="button"
        className="btn-primary btn-taxi submit-btn"
        style={{ marginTop: 14 }}
        onClick={order}
        disabled={!canOrder}
      >
        {busy ? (
          appText("Отправляем…", "Ебәрәбеҙ…")
        ) : when === "later" ? (
          <>
            <IconClock size={18} /> {appText("Заказать на время", "Ваҡытҡа заказ итеү")}
          </>
        ) : (
          <>
            <IconCar size={18} />{" "}
            {estimate
              ? appText(`Вызвать за ${priceLabel(estimate.price, ru)}`, `${priceLabel(estimate.price, ru)} — саҡырыу`)
              : appText("Вызвать машину", "Машина саҡырыу")}
          </>
        )}
      </button>

      <button
        type="button"
        className="btn-soft"
        style={{ marginTop: 10 }}
        onClick={() => navigate("/scheduled")}
      >
        <IconClock size={18} /> {appText("Мои предзаказы", "Алдан заказдарым")}
      </button>
    </>
  );
}

// ----------------------------- Выбор точки Б -----------------------------
function PlacePicker({
  value,
  onPick,
  onUseMyLocation,
}: {
  value: Point | null;
  onPick: (p: Point | null) => void;
  onUseMyLocation?: () => void;
}) {
  const { appText } = useLang();
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<{ title: string; lat: number; lng: number }[]>([]);
  const [saved, setSaved] = useState<{ label: string; kind: string; lat: number; lng: number; address: string }[]>([]);
  const [focus, setFocus] = useState(false);
  const tRef = useRef<number | null>(null);

  // Быстрые адреса (Дом/Работа/недавние) — мягко, 404 = просто без чипов.
  useEffect(() => {
    Promise.all([
      fetchSavedPlaces().catch(() => []),
      fetchRecentPlaces().catch(() => []),
    ]).then(([sv, rc]) => {
      const list: typeof saved = [];
      sv.forEach((s) => {
        if (s.lat != null && s.lng != null)
          list.push({ label: s.label || s.address, kind: s.kind as string, lat: s.lat, lng: s.lng, address: s.address });
      });
      rc.slice(0, 3).forEach((r) => {
        if (r.lat != null && r.lng != null)
          list.push({ label: r.address, kind: "recent", lat: r.lat, lng: r.lng, address: r.address });
      });
      setSaved(list);
    });
  }, []);

  // Геокодер с дебаунсом.
  useEffect(() => {
    if (tRef.current) window.clearTimeout(tRef.current);
    const text = q.trim();
    if (text.length < 3) {
      setHits([]);
      return;
    }
    tRef.current = window.setTimeout(() => {
      geocode(text)
        .then((r) => setHits(r.items.map((h) => ({ title: h.title, lat: h.lat, lng: h.lon }))))
        .catch(() => setHits([]));
    }, 350);
    return () => {
      if (tRef.current) window.clearTimeout(tRef.current);
    };
  }, [q]);

  function pick(p: Point) {
    onPick(p);
    setQ("");
    setHits([]);
    setFocus(false);
  }

  const kindIcon = (kind: string) =>
    kind === "home" ? <IconHome size={18} /> : kind === "work" ? <IconWork size={18} /> : <IconPin size={18} />;

  return (
    <div className="taxi-route__row taxi-route__row--pick">
      <span className="taxi-route__dot taxi-route__dot--b" aria-hidden />
      <div className="taxi-route__text" style={{ width: "100%" }}>
        <span className="taxi-route__label">{appText("Куда", "Ҡайҙа")}</span>
        {value ? (
          <button type="button" className="taxi-route__chosen" onClick={() => onPick(null)}>
            <span className="taxi-route__value">{value.text || appText("Точка на карте", "Картала нөктә")}</span>
            <span className="taxi-route__change">{appText("Изменить", "Үҙгәртеү")}</span>
          </button>
        ) : (
          <input
            className="taxi-route__input"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onFocus={() => setFocus(true)}
            placeholder={appText("Адрес или место", "Адрес йәки урын")}
            aria-label={appText("Куда едем", "Ҡайҙа барабыҙ")}
            autoComplete="off"
          />
        )}
      </div>

      {!value && focus && (hits.length > 0 || saved.length > 0) && (
        <div className="taxi-suggest">
          {hits.map((h, i) => (
            <button
              key={`h${i}`}
              type="button"
              className="taxi-suggest__row"
              onClick={() => pick({ lat: h.lat, lng: h.lng, text: h.title })}
            >
              <IconPin size={18} />
              <span>{h.title}</span>
            </button>
          ))}
          {hits.length === 0 &&
            saved.map((s, i) => (
              <button
                key={`s${i}`}
                type="button"
                className="taxi-suggest__row"
                onClick={() => pick({ lat: s.lat, lng: s.lng, text: s.label })}
              >
                {kindIcon(s.kind)}
                <span>{s.label}</span>
              </button>
            ))}
          {onUseMyLocation && (
            <button type="button" className="taxi-suggest__row" onClick={onUseMyLocation}>
              <IconPin size={18} /> <span>{appText("Определить моё место", "Урынымды билдәләү")}</span>
            </button>
          )}
        </div>
      )}
    </div>
  );
}

// ================================================================
//  Отслеживание заказа: поиск → водитель едет → в пути → завершено.
// ================================================================
function TrackingView({
  order,
  from,
  onCancelled,
  onNewOrder,
  onOpenChat,
}: {
  order: InstantOrder;
  from: Point | null;
  onCancelled: () => void;
  onNewOrder: () => void;
  onOpenChat: () => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [rated, setRated] = useState(false);

  const s = order.status;
  const searching = s === "searching" || s === "offered" || s === "created";
  const enRoute = s === "accepted" || s === "arriving";
  const unlocked = isUnlocked(s);

  const fromPt: GeoPoint | null =
    order.from_lat != null ? { lat: order.from_lat, lng: order.from_lng ?? 0 } : from;
  const toPt: GeoPoint | null = order.to_lat != null ? { lat: order.to_lat, lng: order.to_lng ?? 0 } : null;

  async function cancel() {
    if (busy) return;
    setBusy(true);
    try {
      await cancelInstantOrder(order.id);
    } catch {
      /* уже отменён/завершён — всё равно выходим */
    }
    setBusy(false);
    onCancelled();
  }

  async function rate(stars: number) {
    try {
      await rateInstantOrder(order.id, stars);
    } catch {
      /* тихо */
    }
    setRated(true);
  }

  // --- Поиск машины ---
  if (searching) {
    return (
      <>
        <SubHeader title={appText("Ищем машину", "Машина эҙләйбеҙ")} onBack={() => navigate(-1)} />
        <div className="taxi-search">
          <div className="taxi-search__pulse" aria-hidden>
            <IconCar size={40} />
          </div>
          <h2>{appText("Ищем машину рядом", "Яҡында машина эҙләйбеҙ")}</h2>
          <p>
            {appText(
              "Подбираем ближайшего водителя. Обычно это меньше минуты.",
              "Иң яҡын водительды табабыҙ. Ғәҙәттә бер минуттан кәм."
            )}
          </p>
          <div className="taxi-fare-line">
            <span>{appText("Примерная цена", "Яҡынса хаҡ")}</span>
            <b>{priceLabel(order.price_estimate, ru)}</b>
          </div>
        </div>
        <button type="button" className="btn-ghost" style={{ marginTop: 8 }} onClick={cancel} disabled={busy}>
          {appText("Отменить поиск", "Эҙләүҙе туҡтатыу")}
        </button>
      </>
    );
  }

  // --- Рядом никого ---
  if (s === "expired") {
    return (
      <>
        <SubHeader title={appText("Такси Юлдаш", "Юлдаш такси")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__emoji">🌙</div>
          <h2>{appText("Рядом пока никого", "Яҡында әлегә бер кем юҡ")}</h2>
          <p>
            {appText(
              "Свободных машин рядом не нашлось. Попробуй ещё раз через пару минут.",
              "Яҡында буш машина табылманы. Бер-ике минуттан ҡабат ҡара."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={onNewOrder}>
            {appText("Попробовать снова", "Ҡабат ҡарау")}
          </button>
        </div>
      </>
    );
  }

  // --- Отменён ---
  if (s === "cancelled") {
    const byDriver = order.cancel_by === "driver";
    return (
      <>
        <SubHeader title={appText("Заказ отменён", "Заказ кире алынды")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__emoji">🫧</div>
          <h2>{byDriver ? appText("Водитель отменил заказ", "Водитель заказды кире алды") : appText("Заказ отменён", "Заказ кире алынды")}</h2>
          <p>
            {appText(
              "Бывает. Давай вызовем другую машину — рядом наверняка есть свободные.",
              "Була. Әйҙә, башҡа машина саҡырайыҡ — яҡында буштар бар."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={onNewOrder}>
            {appText("Новый заказ", "Яңы заказ")}
          </button>
        </div>
      </>
    );
  }

  // --- Завершено: цена + оценка ---
  if (s === "done") {
    return (
      <>
        <SubHeader title={appText("Поездка завершена", "Сәфәр тамамланды")} onBack={() => navigate(-1)} />
        <div className="taxi-done">
          <div className="taxi-done__emoji">🎉</div>
          <div className="taxi-fare">
            <span>{appText("К оплате", "Түләргә")}</span>
            <b>{priceLabel(order.price_final ?? order.price_estimate, ru)}</b>
          </div>
          <p className="taxi-done__hint">
            {appText("Оплата напрямую водителю — как договорились.", "Түләү тура водителгә — килешкәнсә.")}
          </p>
        </div>
        {!rated ? (
          <div className="rate-card">
            <div className="rate-card__title">{appText("Как прошла поездка?", "Сәфәр нисек үтте?")}</div>
            <div className="rate-stars">
              {[1, 2, 3, 4, 5].map((n) => (
                <button
                  key={n}
                  type="button"
                  className="rate-star"
                  onClick={() => rate(n)}
                  aria-label={appText(`${n} звёзд`, `${n} йондоҙ`)}
                >
                  <IconStar size={34} />
                </button>
              ))}
            </div>
          </div>
        ) : (
          <div className="consents__status ok" style={{ marginTop: 14 }}>
            {appText("Спасибо за оценку! 🌿", "Баһаң өсөн рәхмәт! 🌿")}
          </div>
        )}
        <button type="button" className="btn-primary" style={{ marginTop: 14 }} onClick={onNewOrder}>
          {appText("Новый заказ", "Яңы заказ")}
        </button>
      </>
    );
  }

  // --- Водитель едет / в пути ---
  const phaseTitle = enRoute
    ? appText("Водитель едет к тебе", "Водитель һиңә килә")
    : appText("В пути", "Юлда");

  return (
    <>
      <SubHeader
        title={phaseTitle}
        subtitle={
          enRoute
            ? appText(`≈ ${Math.round(order.eta_min)} мин до подачи`, `≈ ${Math.round(order.eta_min)} мин килеүгә`)
            : appText("Хорошей дороги 🌿", "Юлың уң булһын 🌿")
        }
        onBack={() => navigate(-1)}
      />

      {(fromPt || toPt) && (
        <div className="home-map" style={{ marginTop: 4 }}>
          <YandexMap from={fromPt} to={toPt} route={!!(fromPt && toPt)} height={200} />
        </div>
      )}

      {/* Карточка водителя */}
      {unlocked && (
        <div className="taxi-driver">
          <div className="taxi-driver__avatar">
            <IconCar size={26} />
          </div>
          <div className="taxi-driver__info">
            <div className="taxi-driver__name">
              {order.driver_name || appText("Водитель", "Водитель")}
              {order.driver_verified && (
                <span className="badge badge--mint" style={{ marginLeft: 8 }}>
                  {appText("Проверен", "Тикшерелгән")}
                </span>
              )}
            </div>
            <div className="taxi-driver__meta">
              {order.driver_car && <span>{order.driver_car}</span>}
              {order.driver_rating > 0 && (
                <span className="taxi-driver__rating">
                  <IconStar size={14} /> {order.driver_rating.toFixed(1)}
                </span>
              )}
            </div>
          </div>
          <div className="taxi-driver__actions">
            <button
              type="button"
              className="taxi-icon-btn"
              onClick={onOpenChat}
              aria-label={appText("Чат с водителем", "Водитель менән чат")}
            >
              <IconChat size={20} />
            </button>
            {order.driver_phone && (
              <a
                className="taxi-icon-btn taxi-icon-btn--call"
                href={`tel:${order.driver_phone}`}
                aria-label={appText("Позвонить водителю", "Водителгә шылтыратыу")}
              >
                <IconPhone size={20} />
              </a>
            )}
          </div>
        </div>
      )}

      {/* Маршрут + цена */}
      <div className="info-list">
        <div className="info-row">
          <span className="info-row__k">{appText("Маршрут", "Маршрут")}</span>
          <span className="info-row__v">
            {order.from_text || appText("Точка А", "А нөктә")} → {order.to_text || appText("Точка Б", "Б нөктә")}
          </span>
        </div>
        <div className="info-row">
          <span className="info-row__k">{appText("Цена", "Хаҡ")}</span>
          <span className="info-row__v">{priceLabel(order.price_estimate, ru)}</span>
        </div>
        {order.category === "comfort" && (
          <div className="info-row">
            <span className="info-row__k">{appText("Класс", "Класс")}</span>
            <span className="info-row__v">{appText("Комфорт", "Комфорт")}</span>
          </div>
        )}
      </div>

      {enRoute && (
        <button type="button" className="btn-ghost" style={{ marginTop: 12 }} onClick={cancel} disabled={busy}>
          {order.cancel_fee_now_kop > 0
            ? appText(
                `Отменить (${Math.round(order.cancel_fee_now_kop / 100)} ₽)`,
                `Кире алыу (${Math.round(order.cancel_fee_now_kop / 100)} ₽)`
              )
            : appText("Отменить заказ", "Заказды кире алыу")}
        </button>
      )}

      <p className="taxi-note">
        {appText(
          "Юлдаш — такси между своими. Береги водителя, води себя по-доброму 🤝",
          "Юлдаш — үҙебеҙ араһында такси. Водителгә иғтибарлы бул 🤝"
        )}
      </p>
    </>
  );
}
