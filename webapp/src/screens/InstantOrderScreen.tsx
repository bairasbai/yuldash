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
  fetchAlternatives,
  addAlternative,
  type InstantOrder,
  type EstimateResult,
  type TaxiCategory,
  type NearbyDriver,
  type FallbackOption,
} from "../api/instant";
import { fetchSavedPlaces, fetchRecentPlaces } from "../api/places";
import { geocode } from "../api/discovery";
import { track } from "../analytics";
import { SubHeader } from "./ConsentsScreen";
import WeatherWarningCard, { useRouteWeather } from "../components/WeatherWarningCard";
import ShareTripCard from "../components/ShareTripCard";
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
  IconCheck,
  IconBolt,
  IconReceipt,
  IconChevron,
} from "../components/Icons";
import { YuMoon, YuQuiet, YuWomenOnly } from "../components/BrandIcons";
import { priceLabel } from "../utils/format";
import { serverMs } from "../utils/serverTime";
import { minDateTimeNow, maxDateTimeInDays } from "../utils/dateInput";

/** Класс машины человеческой строкой (подписи живут в клиенте, коды — на сервере). */
function categoryLabel(cat: string, appText: (ru: string, ba: string) => string): string {
  switch (cat) {
    case "standard":
      return appText("Эконом", "Эконом");
    case "comfort":
      return appText("Комфорт", "Комфорт");
    case "business":
      return appText("Бизнес", "Бизнес");
    case "minivan":
      return appText("Минивэн", "Минивэн");
    default:
      return cat;
  }
}

type Point = { lat: number; lng: number; text: string };
type View = "boot" | "gate" | "compose" | "tracking";

const POLL_MS = 3500;
/** Насколько вперёд сервер принимает предзаказ (`scheduled_max_days` в конфиге). */
const SCHEDULE_MAX_DAYS = 7;

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
          <div className="state__icon"><IconCar size={34} /></div>
          <h2>{appText("Такси скоро в твоём городе 🚕", "Такси тиҙҙән ҡалаңда 🚕")}</h2>
          <p>
            {gateMsg
              ? (ru ? gateMsg.ru : gateMsg.ba)
              : appText(
                  "Мы уже готовим быстрый заказ в твоём городе. Загляни чуть позже — а пока попутки ждут на карте.",
                  "Тиҙ заказды әҙерләйбеҙ. Аҙыраҡ һуңынан кил — әлегә юлдаштар картала көтә."
                )}
          </p>
          {/* Почему именно тут пусто. Иначе выглядит как «до нас не дошли руки»,
              а причина обратная: пускаем город, только когда машины реально рядом. */}
          <p className="demand__quiet">
            {appText(
              "Мы подключаем города по очереди, чтобы машины точно были рядом. А попутка уже работает по всей республике.",
              "Ҡалаларҙы сиратлап тоташтырабыҙ — машиналар яҡында булһын өсөн. Ә юлдаш инде бөтә республикала эшләй."
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

  /** Место не дали — без точки А машину не вызвать, и это надо сказать словами. */
  const [geoNote, setGeoNote] = useState("");
  const [category, setCategory] = useState<TaxiCategory>("standard");
  const [estimate, setEstimate] = useState<EstimateResult | null>(null);
  const [estimating, setEstimating] = useState(false);
  const [when, setWhen] = useState<"now" | "later">("now");
  const [schedAt, setSchedAt] = useState("");
  const [busy, setBusy] = useState(false);

  // «Как меня найти» — свёрнуто по умолчанию: большинству хватает адреса,
  // а кому нужно — там подъезд, ориентир и заказ для другого человека.
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [entrance, setEntrance] = useState("");
  const [comment, setComment] = useState("");
  const [forOther, setForOther] = useState(false);
  const [forName, setForName] = useState("");
  const [forPhone, setForPhone] = useState("");
  const [womenOnly, setWomenOnly] = useState(false);
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
      // Предзаказ считаем на время подачи, а не на сейчас: иначе человек запоминает
      // дневное число, а машина утром приезжает по ночной ставке.
      ...(when === "later" && schedAt
        ? { scheduled_at: new Date(schedAt).toISOString() }
        : {}),
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
  }, [from?.lat, from?.lng, to?.lat, to?.lng, category, when, schedAt]); // eslint-disable-line react-hooks/exhaustive-deps

  // ❄️ Погода на маршруте заказа — по координатам точек (сервер округляет их до ~5 км).
  const weather = useRouteWeather({
    fromLat: from?.lat ?? null,
    fromLng: from?.lng ?? null,
    toLat: to?.lat ?? null,
    toLng: to?.lng ?? null,
    at: when === "later" && schedAt ? new Date(schedAt).toISOString() : undefined,
  });

  function priceFor(cat: TaxiCategory): number | null {
    const opt = estimate?.options?.find((o) => o.category === cat);
    return opt ? opt.price : cat === category ? estimate?.price ?? null : null;
  }

  async function order() {
    if (!from || !to || busy) return;
    setBusy(true);
    setError(null);
    // Начало заказа такси — без координат/адресов, только безопасный контекст.
    track("taxi_order_start", { category, when });
    const body = {
      from_lat: from.lat,
      from_lng: from.lng,
      to_lat: to.lat,
      to_lng: to.lng,
      from_text: from.text,
      to_text: to.text,
      category,
      // «Как меня найти» и «для кого» уходят вместе с заказом: чат откроется
      // только после того, как водитель примет, — до этого сказать нечего.
      comment: comment.trim() || undefined,
      entrance: entrance.trim() || undefined,
      for_name: forOther ? forName.trim() || undefined : undefined,
      for_phone: forOther ? forPhone.trim() || undefined : undefined,
      women_only: womenOnly || undefined,
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

      {/* ❄️ Погода на маршруте — до заказа, а не когда машина уже едет */}
      <WeatherWarningCard weather={weather} />

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
                  navigator.geolocation.getCurrentPosition(
                    (p) => {
                      setGeoNote("");
                      setFrom({
                        lat: p.coords.latitude,
                        lng: p.coords.longitude,
                        text: appText("Моё место", "Урыным"),
                      });
                    },
                    // Раньше отказ проходил молча: человек жал «моё место»,
                    // ничего не менялось, а кнопка «Вызвать» оставалась серой —
                    // и было непонятно, что вообще не так.
                    () =>
                      setGeoNote(
                        appText(
                          "Не видим твоё место. Разреши геолокацию в настройках браузера или укажи точку подачи на карте.",
                          "Урыныңды күрмәйбеҙ. Браузер көйләүҙәрендә геолокацияға рөхсәт бир йәки килеү нөктәһен картала күрһәт."
                        )
                      ),
                    { timeout: 8000, maximumAge: 60000 }
                  );
                }
          }
        />
      </div>

      {geoNote && (
        <div className="notice" role="status">
          {geoNote}
        </div>
      )}

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
                {/* Чем классы отличаются — иначе выбор между ними вслепую */}
                <span className="taxi-class__hint">
                  {cat === "standard"
                    ? appText("обычная машина", "ғәҙәти машина")
                    : appText("новее и просторнее", "яңыраҡ һәм киңерәк")}
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
          <IconBolt size={14} /> {ru ? estimate.surge_note.ru : estimate.surge_note.ba}
        </div>
      )}

      {/* Дорога водителя к пассажиру — отдельные деньги в цене (2026-08-23).
          Без этой строки человек видит только выросшее число и не понимает, за что платит. */}
      {to && estimate && ((estimate.pickup_fee ?? 0) > 0 || (estimate.options_fee ?? 0) > 0
        || (estimate.weather_fee ?? 0) > 0) && (
        <div className={`taxi-pickup${estimate.pickup_enroute ? " taxi-pickup--win" : ""}`}>
          {appText(
            [
              `Поездка ${estimate.ride_price ?? estimate.price} ₽`,
              (estimate.pickup_fee ?? 0) > 0 ? `дорога водителя ${estimate.pickup_fee} ₽` : "",
              (estimate.options_fee ?? 0) > 0 ? `опции ${estimate.options_fee} ₽` : "",
              (estimate.weather_fee ?? 0) > 0 ? `зимняя дорога ${estimate.weather_fee} ₽` : "",
            ].filter(Boolean).join(" + "),
            [
              `Сәфәр ${estimate.ride_price ?? estimate.price} һум`,
              (estimate.pickup_fee ?? 0) > 0 ? `водитель юлы ${estimate.pickup_fee} һум` : "",
              (estimate.options_fee ?? 0) > 0 ? `өҫтәмәләр ${estimate.options_fee} һум` : "",
              (estimate.weather_fee ?? 0) > 0 ? `ҡышҡы юл ${estimate.weather_fee} һум` : "",
            ].filter(Boolean).join(" + ")
          )}
          {estimate.pickup_note && (
            <div>
              <b>{ru ? estimate.pickup_note.ru : estimate.pickup_note.ba}</b>
            </div>
          )}
        </div>
      )}

      {/* Рядом никого: точной суммы не существует. Говорим потолок и про бесплатную отмену —
          обещать цифру, которой у нас нет, значит соврать в первом же заказе. */}
      {to && estimate?.pickup_pending && estimate.pickup_note && (
        <div className="taxi-pickup">
          {ru ? estimate.pickup_note.ru : estimate.pickup_note.ba}
        </div>
      )}

      {/* «Сюда уже едет машина — подождёшь, и подача выйдет дешевле».
          Честная замена идее «поделить подачу между соседями»: второй ничего не теряет. */}
      {to && estimate?.pickup_wait_hint && (
        <div className="taxi-pickup taxi-pickup--win">
          🚗 {ru ? estimate.pickup_wait_hint.ru : estimate.pickup_wait_hint.ba}
        </div>
      )}

      {/* Пока человек думает, цена не вырастет. Молчаливая заморозка никого не успокаивает —
          успокаивает только названная. 0 секунд = выключена, тогда молчим и не обещаем. */}
      {to && when === "now" && (estimate?.price_locked_sec ?? 0) > 0 && (
        <div className="taxi-pickup taxi-pickup--win">
          {appText(
            `Цена закреплена на ${Math.ceil((estimate!.price_locked_sec ?? 0) / 60)} мин — пока думаешь, не вырастет`,
            `Хаҡ ${Math.ceil((estimate!.price_locked_sec ?? 0) / 60)} минутҡа беркетелгән — уйлағанда артмай`,
          )}
        </div>
      )}

      {/* Промокод сработал. Вводить ничего не надо — сервер применил сам.
          Отдельно говорим, кто платит скидку: иначе водитель думает, что
          недоплатили ему, и спорит с пассажиром на ровном месте. */}
      {to && estimate && (estimate.promo_discount_kop ?? 0) > 0 && (
        <div className="promo-hit">
          <div className="promo-hit__head">
            <span className="promo-hit__title">{appText("Промокод сработал", "Промокод эшләне")}</span>
            <span className="promo-hit__sum">
              −{Math.round((estimate.promo_discount_kop ?? 0) / 100)} ₽
            </span>
          </div>
          {estimate.promo_code && (
            <div className="promo-hit__code">
              {appText(`Код ${estimate.promo_code} · один раз`, `Код ${estimate.promo_code} · бер тапҡыр`)}
            </div>
          )}
          <p className="promo-hit__note">
            {estimate.promo_note
              ? ru
                ? estimate.promo_note.ru
                : estimate.promo_note.ba
              : appText(
                  "Скидку оплачивает Юлдаш из своей комиссии — водитель получит своё полностью.",
                  "Ташламаны Юлдаш үҙ комиссияһынан түләй — йөрөтөүсе үҙенекен тулыһынса ала."
                )}
          </p>
        </div>
      )}

      {/* Из чего сложилась цена. Показываем ДО вызова машины: «Юлдаш накрутил» — самое
          частое подозрение к такси, и отвечать на него надо заранее, а не после поездки. */}
      {to && estimate && (
        <details className="price-why">
          <summary>{appText("Цена рассчитана программой", "Хаҡты программа иҫәпләй")}</summary>
          <div className="info-list">
            {estimate.base_price > 0 && (
              <div className="info-row">
                <span className="info-row__k">{appText("База", "Нигеҙ")}</span>
                <span className="info-row__v">{priceLabel(estimate.base_price, ru)}</span>
              </div>
            )}
            <div className="info-row">
              <span className="info-row__k">{appText("Расстояние", "Ара")}</span>
              <span className="info-row__v">
                {estimate.distance_km.toFixed(1)} {appText("км", "км")}
              </span>
            </div>
            <div className="info-row">
              <span className="info-row__k">{appText("В пути", "Юлда")}</span>
              <span className="info-row__v">
                {Math.round(estimate.eta_min)} {appText("мин", "мин")}
              </span>
            </div>
            <div className="info-row">
              <span className="info-row__k">{appText("Наценка", "Өҫтәмә")}</span>
              <span className="info-row__v">
                {estimate.dynamic_k && estimate.dynamic_k > 1.01
                  ? `×${estimate.dynamic_k.toFixed(2)}`
                  : appText("Наценки сейчас нет", "Хәҙер өҫтәмә юҡ")}
              </span>
            </div>
            {estimate.night_note && (
              <div className="info-row">
                <span className="info-row__k">{appText("Ночь", "Төн")}</span>
                <span className="info-row__v">
                  {ru ? estimate.night_note.ru : estimate.night_note.ba}
                </span>
              </div>
            )}
            {estimate.pricing_cap_k && (
              <div className="info-row">
                <span className="info-row__k">{appText("Потолок наценки", "Өҫтәмә түшәме")}</span>
                <span className="info-row__v">×{estimate.pricing_cap_k.toFixed(1)}</span>
              </div>
            )}
          </div>
          <p className="act-card__text" style={{ margin: "10px 0 0" }}>
            {appText(
              "Цену считаем мы — по расстоянию, времени и спросу. Вручную её никто не накручивает.",
              "Хаҡты беҙ иҫәпләйбеҙ — ара, ваҡыт һәм һорау буйынса. Уны ҡулдан бер кем дә арттырмай."
            )}
          </p>
        </details>
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
                min={minDateTimeNow()}
                max={maxDateTimeInDays(SCHEDULE_MAX_DAYS)}
                value={schedAt}
                onChange={(e) => setSchedAt(e.target.value)}
              />
            </label>
          )}
        </>
      )}

      {/* Только женщина за рулём. В попутках такой выбор был с начала, а в такси —
          нет, хотя ночью в чужую машину садятся именно здесь. Фильтр жёсткий,
          поэтому честно предупреждаем: ждать можно дольше или не дождаться. */}
      {to && (
        <label className="taxi-women">
          <input
            type="checkbox"
            checked={womenOnly}
            onChange={(e) => setWomenOnly(e.target.checked)}
          />
          <span className="taxi-women__main">
            <span className="taxi-women__title">
              <YuWomenOnly size={16} className="amenity-ic" />{" "}
              {appText("Только женщина за рулём", "Тик ҡатын-ҡыҙ йөрөтөүсе")}
            </span>
            <span className="taxi-women__sub">
              {appText(
                "Заказ увидят только женщины-водители. Их меньше — машину можно ждать дольше или не дождаться.",
                "Заказды тик ҡатын-ҡыҙ йөрөтөүселәр күрә. Улар аҙыраҡ — машинаны оҙағыраҡ көтөргә йәки көтөп алмаҫҡа мөмкин."
              )}
            </span>
          </span>
        </label>
      )}

      {/* Как меня найти. В селе «Ленина 12» — пять домов без табличек, а чат
          откроется только после принятия заказа: сказать водителю больше негде. */}
      {to && (
        <div className="taxi-details">
          <button
            type="button"
            className="taxi-details__head"
            onClick={() => setDetailsOpen((v) => !v)}
            aria-expanded={detailsOpen}
          >
            <span>
              <span className="taxi-details__title">
                {appText("Как меня найти", "Мине нисек табырға")}
              </span>
              <span className="taxi-details__sub">
                {appText(
                  "Подъезд, ориентир, заказ для другого",
                  "Подъезд, ориентир, башҡа кеше өсөн заказ"
                )}
              </span>
            </span>
            <span className={"taxi-details__chev" + (detailsOpen ? " is-open" : "")}>
              <IconChevron size={20} />
            </span>
          </button>

          {detailsOpen && (
            <div className="taxi-details__body">
              <label className="field">
                <span className="field__label">{appText("Подъезд, квартира, этаж", "Подъезд, фатир, ҡат")}</span>
                <input
                  className="field__input"
                  value={entrance}
                  maxLength={60}
                  onChange={(e) => setEntrance(e.target.value)}
                  placeholder={appText("2 подъезд, 14 кв.", "2-се подъезд, 14-се фатир")}
                />
              </label>

              <label className="field">
                <span className="field__label">{appText("Комментарий водителю", "Йөрөтөүсегә иҫкәрмә")}</span>
                <input
                  className="field__input"
                  value={comment}
                  maxLength={300}
                  onChange={(e) => setComment(e.target.value)}
                  placeholder={appText("«За магазином, синие ворота»", "«Магазин артында, зәңгәр ҡапҡа»")}
                />
              </label>

              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={forOther}
                  onChange={(e) => setForOther(e.target.checked)}
                />
                <span>{appText("Заказ для другого человека", "Башҡа кеше өсөн заказ")}</span>
              </label>
              <div className="taxi-details__note">
                {appText("Водитель будет звонить ему, а не тебе", "Йөрөтөүсе уға шылтырата, һиңә түгел")}
              </div>

              {forOther && (
                <>
                  <label className="field">
                    <span className="field__label">{appText("Кого везём (имя)", "Кемде алып барабыҙ (исем)")}</span>
                    <input
                      className="field__input"
                      value={forName}
                      maxLength={120}
                      onChange={(e) => setForName(e.target.value)}
                    />
                  </label>
                  <label className="field">
                    <span className="field__label">{appText("Телефон", "Телефон")}</span>
                    <input
                      className="field__input"
                      type="tel"
                      value={forPhone}
                      maxLength={32}
                      onChange={(e) => setForPhone(e.target.value)}
                      placeholder="+7 917 000-00-00"
                    />
                  </label>
                  <div className="taxi-details__note">
                    {appText(
                      "Телефон увидит только водитель и только после того, как примет заказ.",
                      "Телефонды тик йөрөтөүсе, тик заказды алғандан һуң күрә."
                    )}
                  </div>
                </>
              )}
            </div>
          )}
        </div>
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
    // Человек мог уйти с экрана, пока адреса летели: тогда ответ уже никому не нужен.
    let alive = true;
    Promise.all([
      fetchSavedPlaces().catch(() => []),
      fetchRecentPlaces().catch(() => []),
    ]).then(([sv, rc]) => {
      if (!alive) return;
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
    return () => {
      alive = false;
    };
  }, []);

  // Геокодер с дебаунсом.
  useEffect(() => {
    if (tRef.current) window.clearTimeout(tRef.current);
    const text = q.trim();
    if (text.length < 3) {
      setHits([]);
      return;
    }
    const ac = new AbortController();
    tRef.current = window.setTimeout(() => {
      geocode(text, ac.signal)
        .then((r) => setHits(r.items.map((h) => ({ title: h.title, lat: h.lat, lng: h.lon }))))
        .catch(() => setHits([]));
    }, 350);
    return () => {
      if (tRef.current) window.clearTimeout(tRef.current);
      // Запрос уже ушёл — обрываем: иначе ответ по старому тексту перетрёт
      // подсказки по новому (человек печатает быстрее, чем отвечает сервер).
      ac.abort();
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
  // «В твоём классе никого нет» — что предложить взамен. null = предлагать нечего.
  const [alts, setAlts] = useState<FallbackOption[] | null>(null);
  const [altBusy, setAltBusy] = useState(false);

  const s = order.status;
  const searching = s === "searching" || s === "offered" || s === "created";
  const enRoute = s === "accepted" || s === "arriving";
  const unlocked = isUnlocked(s);

  const fromPt: GeoPoint | null =
    order.from_lat != null ? { lat: order.from_lat, lng: order.from_lng ?? 0 } : from;
  const toPt: GeoPoint | null = order.to_lat != null ? { lat: order.to_lat, lng: order.to_lng ?? 0 } : null;

  // Пока ищем — спрашиваем сервер, есть ли что предложить в соседнем класcе.
  // Сервер сам решает, когда пора (after_sec); молчаливой подмены класса нет.
  useEffect(() => {
    if (!searching) {
      setAlts(null);
      return;
    }
    const ac = new AbortController();
    let alive = true;
    let timer = 0;
    const ask = () => {
      fetchAlternatives(order.id, ac.signal)
        .then((r) => {
          if (!alive) return;
          setAlts(r.options.length ? r.options : null);
          if (!r.options.length) timer = window.setTimeout(ask, 15000);
        })
        .catch(() => {
          if (alive) setAlts(null); // 404 до деплоя / нет сети → блок скрыт
        });
    };
    ask();
    return () => {
      alive = false;
      window.clearTimeout(timer);
      ac.abort();
    };
  }, [searching, order.id]);

  async function pickAlternative(category: string) {
    if (altBusy) return;
    setAltBusy(true);
    try {
      await addAlternative(order.id, category);
      setAlts(null); // согласились — дальше ищем шире, цена уже пересчитана
    } catch {
      /* заказ уже не в поиске — экран обновится поллингом */
    } finally {
      setAltBusy(false);
    }
  }

  /**
   * Отмена всегда через вопрос «почему»: ответ не обязателен, но без него
   * мы не знаем, машины подъезжают долго или адрес был не тот.
   * Платная отмена объясняется прямо тут — счёт постфактум люди не прощают.
   */
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState<string | null>(null);

  /** Отмена не прошла из-за связи — уходить с экрана нельзя, заказ живёт. */
  const [cancelNote, setCancelNote] = useState("");

  async function cancel(reason: string) {
    if (busy) return;
    setBusy(true);
    setCancelNote("");
    try {
      await cancelInstantOrder(order.id, reason);
    } catch (e) {
      // Сервер ответил (заказ уже завершён/отменён) — выходим, это нормально.
      // А вот при обрыве связи выходить НЕЛЬЗЯ: раньше экран закрывался, будто
      // отмена прошла, — водитель ехал, а пассажир был уверен, что отменил.
      if (e instanceof ApiError && e.status === 0) {
        setBusy(false);
        setCancelNote(
          appText(
            "Не получилось отменить — нет связи. Заказ ещё активен, попробуй ещё раз.",
            "Кире алып булманы — бәйләнеш юҡ. Заказ әле әүҙем, тағы ҡабатла."
          )
        );
        return;
      }
    }
    setBusy(false);
    setCancelOpen(false);
    onCancelled();
  }

  /** Оценка не ушла — «спасибо» показывать нельзя, человек решит, что оценил. */
  const [rateNote, setRateNote] = useState("");

  async function rate(stars: number) {
    try {
      await rateInstantOrder(order.id, stars);
      setRateNote("");
      setRated(true);
    } catch {
      setRateNote(
        appText("Оценка не отправилась. Попробуй ещё раз.", "Баһа китмәне. Тағы ҡабатла.")
      );
    }
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
          <h2>{appText("Ищем машину рядом…", "Яҡында машина эҙләйбеҙ…")}</h2>
          <p>
            {appText(
              "Подбираем ближайшего водителя. Обычно это меньше минуты.",
              "Иң яҡын йөрөтөүсене табабыҙ. Ғәҙәттә бер минуттан кәм."
            )}
          </p>
          <div className="taxi-fare-line">
            <span>{appText("Примерная цена", "Яҡынса хаҡ")}</span>
            <b>{priceLabel(order.price_estimate, ru)}</b>
          </div>
        </div>

        {/* Никого в выбранном классе — предлагаем соседний. Решает пассажир, цену видит заранее. */}
        {alts && alts.length > 0 && (
          <div className="act-card act-card--warn">
            <div className="act-card__title">
              <IconCar size={18} /> {appText("В твоём классе пока никого", "Һайлаған класта әлегә бер кем юҡ")}
            </div>
            <p className="act-card__text">
              {appText(
                "Можем поискать шире. Цену увидишь до согласия — заплатишь ровно её.",
                "Киңерәк эҙләй алабыҙ. Хаҡты алдан күрәһең — шуны ғына түләйһең."
              )}
            </p>
            {alts.map((o) => (
              <button
                key={o.category}
                type="button"
                className="btn-soft"
                style={{ width: "100%", marginBottom: 8 }}
                onClick={() => pickAlternative(o.category)}
                disabled={altBusy}
              >
                {categoryLabel(o.category, appText)} · {priceLabel(o.price, ru)}
                {o.price_diff !== 0 && (
                  <span className="money-row__op">
                    {" "}
                    {o.price_diff > 0
                      ? appText(`(+${o.price_diff} ₽)`, `(+${o.price_diff} һ)`)
                      : appText(`(${o.price_diff} ₽)`, `(${o.price_diff} һ)`)}
                  </span>
                )}
              </button>
            ))}
          </div>
        )}
        <button
          type="button"
          className="btn-ghost"
          style={{ marginTop: 8 }}
          onClick={() => setCancelOpen(true)}
          disabled={busy}
        >
          {appText("Отменить поиск", "Эҙләүҙе туҡтатыу")}
        </button>

        {/* Во время поиска отмена бесплатна — но «почему» спрашиваем так же:
            «долго ждать» на этой фазе и есть самый ценный ответ. */}
        {cancelNote && (
          <div className="notice" role="status">
            {cancelNote}
          </div>
        )}
        {cancelOpen && (
          <CancelSheet
            feeRub={0}
            busy={busy}
            reason={cancelReason}
            onPick={setCancelReason}
            onClose={() => setCancelOpen(false)}
            onConfirm={() => void cancel(cancelReason ?? "")}
          />
        )}
      </>
    );
  }

  // --- Рядом никого ---
  if (s === "expired") {
    return (
      <>
        <SubHeader title={appText("Такси Юлдаш", "Юлдаш такси")} onBack={() => navigate(-1)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon"><YuMoon size={34} /></div>
          <h2>{appText("Рядом пока никого", "Яҡында әлегә бер кем юҡ")}</h2>
          {/* Выбор «только женщина за рулём» сужает круг машин, и человек имеет право
              знать, что дело в этом, а не в поломке. Молча подставить мужчину нельзя:
              тогда галочка ничего не значила бы — а её ставят ради безопасности. */}
          <p>
            {order.women_only
              ? appText(
                  "Свободных женщин-водителей рядом не нашли. Мы не подставим вместо них другого водителя — ты просила именно женщину. Можем подождать: как только кто-то освободится, пришлём уведомление.",
                  "Яҡында буш ҡатын-ҡыҙ йөрөтөүсе табылманы. Уның урынына башҡа йөрөтөүсене тәҡдим итмәйбеҙ — һин нәҡ ҡатын-ҡыҙ һораның. Көтә алабыҙ: берәйһе бушаныу менән хәбәр итәбеҙ."
                )
              : appText(
                  "Свободных машин рядом не нашлось. Попробуй ещё раз через пару минут.",
                  "Яҡында буш машина табылманы. Бер-ике минуттан ҡабат ҡара."
                )}
          </p>
          <button type="button" className="btn-primary" onClick={onNewOrder}>
            {appText("Попробовать снова", "Ҡабат ҡарау")}
          </button>
          {/* Машин нет — но в ту же сторону кто-то и так едет. Попутка дешевле
              и часто быстрее, чем ждать такси, которого в селе может не быть вовсе. */}
          <button type="button" className="btn-soft" onClick={() => navigate("/map")}>
            <IconCar size={18} /> {appText("Поехали попуткой", "Юлдаш менән киттек")}
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
          <div className="state__icon"><YuQuiet size={34} /></div>
          <h2>{byDriver ? appText("Водитель отменил заказ", "Йөрөтөүсе заказды кире алды") : appText("Заказ отменён", "Заказ кире алынды")}</h2>
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
          <div className="state__icon"><IconCheck size={34} /></div>
          <div className="taxi-fare">
            <span>{appText("К оплате", "Түләргә")}</span>
            <b>{priceLabel(order.price_final ?? order.price_estimate, ru)}</b>
          </div>
          <p className="taxi-done__hint">
            {appText("Оплата напрямую водителю — как договорились.", "Түләү тура йөрөтөүсегә — килешкәнсә.")}
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
            {rateNote && (
              <div className="notice" role="status">
                {rateNote}
              </div>
            )}
          </div>
        ) : (
          <div className="consents__status ok" style={{ marginTop: 14 }}>
            {appText("Спасибо за оценку! 🌿", "Баһаң өсөн рәхмәт! 🌿")}
          </div>
        )}
        {/* Чек — сразу и потом: он остаётся в «Мои поездки на такси», а не теряется. */}
        <button
          type="button"
          className="btn-soft"
          style={{ width: "100%", marginTop: 14 }}
          onClick={() => navigate(`/taxi-receipt/${order.id}`)}
        >
          <IconReceipt size={18} /> {appText("Чек за поездку", "Сәфәр чегы")}
        </button>
        <button type="button" className="btn-primary" style={{ marginTop: 10 }} onClick={onNewOrder}>
          {appText("Новый заказ", "Яңы заказ")}
        </button>
      </>
    );
  }

  // --- Водитель едет / в пути ---
  const phaseTitle = enRoute
    ? appText("Водитель едет к тебе", "Йөрөтөүсе һиңә килә")
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
              {order.driver_name || appText("Водитель", "Йөрөтөүсе")}
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
              aria-label={appText("Чат с водителем", "Йөрөтөүсе менән чат")}
            >
              <IconChat size={20} />
            </button>
            {order.driver_phone && (
              <a
                className="taxi-icon-btn taxi-icon-btn--call"
                href={`tel:${order.driver_phone}`}
                aria-label={appText("Позвонить водителю", "Йөрөтөүсегә шылтыратыу")}
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
          <span className="info-row__k">{appText("Маршрут", "Юл")}</span>
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

      {/* Поделиться поездкой с близким: живая карта у него в браузере, без приложения */}
      {enRoute && <ShareTripCard orderId={order.id} />}

      {/* Счётчик ожидания: сначала видно, сколько бесплатного осталось,
          потом — сколько уже набежало. Цифра до, а не счёт после. */}
      <WaitCounter order={order} />

      {enRoute && (
        <button
          type="button"
          className="btn-ghost"
          style={{ marginTop: 12 }}
          onClick={() => setCancelOpen(true)}
          disabled={busy}
        >
          {order.cancel_fee_now_kop > 0
            ? appText(
                `Отменить (${Math.round(order.cancel_fee_now_kop / 100)} ₽)`,
                `Кире алыу (${Math.round(order.cancel_fee_now_kop / 100)} ₽)`
              )
            : appText("Отменить заказ", "Заказды кире алыу")}
        </button>
      )}

      {cancelOpen && (
        <CancelSheet
          feeRub={Math.round(order.cancel_fee_now_kop / 100)}
          busy={busy}
          reason={cancelReason}
          onPick={setCancelReason}
          onClose={() => setCancelOpen(false)}
          onConfirm={() => void cancel(cancelReason ?? "")}
        />
      )}

      <p className="taxi-note">
        {appText(
          "Юлдаш — такси между своими. Береги водителя, води себя по-доброму 🤝",
          "Юлдаш — үҙебеҙ араһында такси. Йөрөтөүсегә иғтибарлы бул 🤝"
        )}
      </p>
    </>
  );
}

// ----------------------------- Ожидание у подъезда -----------------------------
/**
 * Водитель приехал и ждёт. Сначала окно бесплатное — показываем, сколько его
 * осталось; потом счётчик платного. Смысл в том, чтобы цифра была ДО списания,
 * а не пришла счётом после поездки: именно так люди и ссорятся с такси.
 *
 * Считаем на клиенте от waiting_started_at — сервер отдаёт момент начала и тариф,
 * а тикать секунды на сервере незачем.
 */
function WaitCounter({ order }: { order: InstantOrder }) {
  const { appText } = useLang();
  const [now, setNow] = useState(() => Date.now());

  const startedMs = serverMs(order.waiting_started_at);
  const running = !Number.isNaN(startedMs);

  useEffect(() => {
    if (!running) return;
    const iv = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(iv);
  }, [running]);

  if (!running) return null;

  const elapsedSec = Math.max(0, Math.floor((now - startedMs) / 1000));
  const freeSec = Math.max(0, (order.wait_free_min ?? 0) * 60);
  const isFree = elapsedSec < freeSec;

  if (isFree) {
    const left = freeSec - elapsedSec;
    const mmss = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
    return (
      <div className="wait-counter">
        <div className="wait-counter__row">
          <IconClock size={18} />
          <b>{appText(`Бесплатное ожидание ${mmss}`, `Бушлай көтөү ${mmss}`)}</b>
        </div>
        <div className="wait-counter__bar" aria-hidden>
          <span style={{ width: `${Math.round((left / freeSec) * 100)}%` }} />
        </div>
      </div>
    );
  }

  const paidMin = Math.max(0, Math.floor(elapsedSec / 60) - (order.wait_free_min ?? 0));
  const paidRub = paidMin * (order.wait_fee_rub_per_min ?? 0);
  return (
    <div className="wait-counter wait-counter--paid">
      <div className="wait-counter__row">
        <IconClock size={18} />
        <b>
          {appText(
            `Платное ожидание · +${order.wait_fee_rub_per_min} ₽/мин` +
              (paidRub > 0 ? ` (уже +${paidRub} ₽)` : ""),
            `Түләүле көтөү · +${order.wait_fee_rub_per_min} ₽/мин` +
              (paidRub > 0 ? ` (инде +${paidRub} ₽)` : "")
          )}
        </b>
      </div>
    </div>
  );
}

// ----------------------------- Отмена: почему -----------------------------
/** Причины отмены — те же, что в приложении. Порядок неслучаен: сверху частое. */
const CANCEL_REASONS: Array<{ id: string; ru: string; ba: string }> = [
  { id: "long_wait", ru: "Долго ждать машину", ba: "Машинаны оҙаҡ көтөргә" },
  { id: "other_way", ru: "Уехал(а) другим способом", ba: "Башҡа юл менән киттем" },
  { id: "wrong_address", ru: "Ошибся адресом", ba: "Адресты яңылыш яҙҙым" },
  { id: "plans", ru: "Планы изменились", ba: "Пландар үҙгәрҙе" },
  { id: "other", ru: "Другая причина", ba: "Башҡа сәбәп" },
];

function CancelSheet({
  feeRub,
  busy,
  reason,
  onPick,
  onClose,
  onConfirm,
}: {
  feeRub: number;
  busy: boolean;
  reason: string | null;
  onPick: (id: string) => void;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <h2 className="sheet__title">{appText("Почему отменяешь?", "Ниңә кире алаһың?")}</h2>

        {/* Платная отмена объясняется до нажатия, а не приходит счётом после. */}
        {feeRub > 0 && (
          <p className="sheet__comment">
            {appText(
              `Отмена сейчас платная: ${feeRub} ₽ за подачу — переведи водителю. Частые платные отмены ставят такси на паузу.`,
              `Кире алыу хәҙер түләүле: килеү өсөн ${feeRub} ₽ — водителгә күсер. Йыш түләүле кире алыу таксины паузаға ҡуя.`
            )}
          </p>
        )}

        <div className="reason-list">
          {CANCEL_REASONS.map((r) => (
            <button
              key={r.id}
              type="button"
              className={"reason-chip" + (reason === r.id ? " is-active" : "")}
              onClick={() => onPick(r.id)}
            >
              {ru ? r.ru : r.ba}
            </button>
          ))}
        </div>

        <p className="sheet__comment">
          {appText(
            "Ответ не обязателен. Он нужен нам, чтобы машины подъезжали быстрее.",
            "Яуап мотлаҡ түгел. Ул машиналар тиҙерәк килһен өсөн кәрәк."
          )}
        </p>

        <button type="button" className="btn-danger" onClick={onConfirm} disabled={busy}>
          {busy
            ? appText("Отменяем…", "Кире алабыҙ…")
            : feeRub > 0
              ? appText("Всё равно отменить", "Барыбер кире алыу")
              : appText("Отменить заказ", "Заказды кире алыу")}
        </button>
        <button type="button" className="btn-soft" style={{ marginTop: 8 }} onClick={onClose}>
          {feeRub > 0 ? appText("Я выхожу", "Мин сығам") : appText("Не отменять", "Кире алмаҫҡа")}
        </button>
      </div>
    </div>
  );
}
