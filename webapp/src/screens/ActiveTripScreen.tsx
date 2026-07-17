// ================================================================
//  Активная поездка (сшивает bookings.py + chat.py + location.py).
//  - Live-статус: поллинг GET /bookings/{id}/role (status + driver_phase).
//  - Карта: маршрут A→B + live-точка водителя (WS /ws/trip/{id}/location).
//  - Код посадки: GET /bookings/{id}/boarding-code (для confirmed/onboard).
//  - Чат брони: REST-история + живой WS /ws/bookings/{id} (иначе поллинг).
//  - Оценка при завершении: POST /bookings/{id}/rate.
//  - Договорённость об оплате — read-only. SOS — заглушка (волна 7).
//  - Зимняя проверка «доехал?» (safety.py): по расчётной ETA пассажиру
//    показываем мягкий вопрос; «Доехал ✓» → POST winter-check/ok.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchBookingDetails,
  fetchTripState,
  fetchBoardingCode,
  rateBooking,
  type BookingDetails,
  type TripState,
} from "../api/bookings";
import {
  fetchMessages,
  sendMessageRest,
  openBookingChat,
  openTripLocation,
  type ChatMessage,
} from "../api/chat";
import { winterCheck, winterCheckOk } from "../api/safety";
import { LoadingList, ErrorState } from "../components/States";
import YandexMap, { type GeoPoint } from "../components/YandexMap";
import { StatusPill } from "../components/StatusPill";
import QuickReplies from "../components/QuickReplies";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconStar, IconPhone, IconWarn, IconCheck } from "../components/Icons";
import { formatWhen, priceLabel, payMethodLabel } from "../utils/format";

// ---- Зимняя проверка «доехал?» ----
const WINTER_ASKED_KEY = (id: number) => `yuldash.winterAsk.${id}`;
const WINTER_SPEED_KMH = 45; // средняя скорость по региональным дорогам
const WINTER_BUFFER_MS = 45 * 60_000; // запас 45 минут сверх расчётного пути
const WINTER_FALLBACK_MS = 3 * 60 * 60_000; // нет координат → спросим через 3 часа

/** Расстояние по прямой, км (хаверсин). */
function haversineKm(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const rad = (d: number) => (d * Math.PI) / 180;
  const R = 6371;
  const dLat = rad(bLat - aLat);
  const dLng = rad(bLng - aLng);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

/** Когда пора мягко спросить «доехал?» (мс epoch) — ETA + запас; без координат — фолбэк. */
function winterDueAt(d: BookingDetails): number {
  const depart = new Date(d.depart_at).getTime();
  if (isNaN(depart)) return Number.POSITIVE_INFINITY;
  if (d.from_lat != null && d.from_lng != null && d.to_lat != null && d.to_lng != null) {
    const km = haversineKm(d.from_lat, d.from_lng, d.to_lat, d.to_lng);
    const travelMs = (km / WINTER_SPEED_KMH) * 3_600_000;
    return depart + travelMs + WINTER_BUFFER_MS;
  }
  return depart + WINTER_FALLBACK_MS;
}

export default function ActiveTripScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { user } = useAuth();
  const { id } = useParams();
  const bookingId = Number(id);

  const [status, setStatus] = useState<"loading" | "error" | "ready">("loading");
  const [details, setDetails] = useState<BookingDetails | null>(null);
  const [trip, setTrip] = useState<TripState | null>(null);
  const [code, setCode] = useState<string | null>(null);
  const [driverLoc, setDriverLoc] = useState<GeoPoint | null>(null);
  const [rated, setRated] = useState(false);
  const [winterAsk, setWinterAsk] = useState(false); // показать мягкий вопрос «Ты доехал(а)?»

  // ---- Загрузка деталей ----
  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!bookingId) {
        setStatus("error");
        return;
      }
      setStatus("loading");
      fetchBookingDetails(bookingId, signal)
        .then((d) => {
          setDetails(d);
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus("error");
        });
    },
    [bookingId]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // ---- Поллинг live-статуса каждые 10 сек ----
  useEffect(() => {
    if (!bookingId) return;
    let alive = true;
    const tick = () => {
      fetchTripState(bookingId)
        .then((s) => {
          if (alive) setTrip(s);
        })
        .catch(() => {
          /* сеть моргнула — попробуем в следующий тик */
        });
    };
    tick();
    const iv = window.setInterval(tick, 10000);
    return () => {
      alive = false;
      window.clearInterval(iv);
    };
  }, [bookingId]);

  const st = trip?.status ?? details?.status;
  const active = st === "confirmed" || st === "onboard";

  // ---- Код посадки (только для confirmed/onboard) ----
  useEffect(() => {
    if (!bookingId || !active) return;
    let alive = true;
    fetchBoardingCode(bookingId)
      .then((r) => {
        if (alive) setCode(r.code);
      })
      .catch(() => {
        /* нет кода — не критично */
      });
    return () => {
      alive = false;
    };
  }, [bookingId, active]);

  // ---- Live-точка водителя (WS) — только пока активна ----
  useEffect(() => {
    if (!bookingId || !active) return;
    const conn = openTripLocation(bookingId, (p) => setDriverLoc({ lat: p.lat, lng: p.lng }));
    return () => conn.close();
  }, [bookingId, active]);

  // ---- Зимняя проверка «доехал?» — только пассажиру, один раз на бронь ----
  useEffect(() => {
    if (!bookingId || !details) return;
    if (details.role !== "passenger") return; // водителю не показываем
    if (!(st === "confirmed" || st === "onboard")) return; // только живая поездка
    let asked = false;
    try {
      asked = sessionStorage.getItem(WINTER_ASKED_KEY(bookingId)) === "1";
    } catch {
      /* приватный режим — просто не дедупим */
    }
    if (asked) return;

    const delay = Math.max(0, winterDueAt(details) - Date.now());
    if (!isFinite(delay)) return;
    let alive = true;
    const timer = window.setTimeout(() => {
      // Сервер идемпотентен: шлёт пуш обеим сторонам / говорит «уже ок»/«рано».
      winterCheck(bookingId)
        .then((r) => {
          if (!alive) return;
          if (["check_sent", "waiting", "no_share", "escalated"].includes(String(r.state))) {
            try {
              sessionStorage.setItem(WINTER_ASKED_KEY(bookingId), "1");
            } catch {
              /* не критично */
            }
            setWinterAsk(true);
          }
        })
        .catch(() => {
          /* 404 до деплоя release / сеть — тихо, без вопроса */
        });
    }, delay);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [bookingId, details, st]);

  async function winterAnswerOk() {
    setWinterAsk(false);
    try {
      await winterCheckOk(bookingId);
    } catch {
      /* уже отмечено / нет ручки — не критично */
    }
  }

  async function onRate(stars: number) {
    if (!bookingId) return;
    try {
      await rateBooking(bookingId, stars);
      setRated(true);
    } catch {
      /* уже оценено / не завершена — тихо */
      setRated(true);
    }
  }

  if (status === "loading") {
    return (
      <>
        <SubHeader title={appText("Поездка", "Сәфәр")} onBack={() => navigate(-1)} />
        <LoadingList count={2} />
      </>
    );
  }
  if (status === "error" || !details) {
    return (
      <>
        <SubHeader title={appText("Поездка", "Сәфәр")} onBack={() => navigate(-1)} />
        <ErrorState onRetry={() => load()} />
      </>
    );
  }

  const from =
    details.from_lat != null ? { lat: details.from_lat, lng: details.from_lng ?? 0 } : null;
  const to =
    details.to_lat != null ? { lat: details.to_lat, lng: details.to_lng ?? 0 } : null;

  return (
    <>
      <SubHeader
        title={`${details.from_city} → ${details.to_city}`}
        subtitle={formatWhen(details.depart_at, ru)}
        onBack={() => navigate(-1)}
      />

      {/* Живой баннер статуса */}
      <div className="trip-banner">
        <StatusPill status={(trip?.status ?? details.status) as BookingDetails["status"]} phase={trip?.driver_phase} />
        {st === "pending" && (
          <span className="trip-banner__hint">
            {appText("Ждём, пока водитель подтвердит.", "Водитель раҫлағанды көтәбеҙ.")}
          </span>
        )}
      </div>

      {/* Зимняя проверка: мягкий вопрос «Ты доехал(а)?» по расчётной ETA */}
      {winterAsk && (
        <div className="winter-check">
          <div className="winter-check__title">
            {appText("Ты доехал(а)? ❄️", "Барып еттеңме? ❄️")}
          </div>
          <p className="winter-check__hint">
            {appText(
              "По нашим расчётам поездка уже могла завершиться. Отметь, что всё хорошо — и близкие будут спокойны.",
              "Иҫәпләүебеҙсә, сәфәр тамамланырға тейеш ине. Барыһы ла яҡшы тип билдәлә — яҡындарың тыныс булыр."
            )}
          </p>
          <div className="winter-check__actions">
            <button type="button" className="btn-primary" onClick={winterAnswerOk}>
              <IconCheck size={17} /> {appText("Доехал ✓", "Барып еттем ✓")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setWinterAsk(false)}>
              {appText("Ещё в пути", "Әле юлда")}
            </button>
          </div>
        </div>
      )}

      {(from || to || driverLoc) && (
        <div className="home-map" style={{ marginTop: 12 }}>
          <YandexMap
            from={from}
            to={to}
            route={!!(from && to)}
            me={driverLoc}
            height={200}
          />
        </div>
      )}

      {/* Код посадки */}
      {active && code && (
        <div className="code-card">
          <div className="code-card__label">
            {appText("Код посадки", "Ултырыу коды")}
          </div>
          <div className="code-card__value">{code}</div>
          <div className="code-card__hint">
            {appText("Назови его водителю при посадке", "Ултырғанда водителгә әйт")}
          </div>
        </div>
      )}

      {/* Оплата (read-only) + контакт */}
      <div className="info-list">
        <div className="info-row">
          <span className="info-row__k">{appText("Оплата", "Түләү")}</span>
          <span className="info-row__v">
            {payMethodLabel(details.pay_method, ru)}
            {" · "}
            {priceLabel(details.pay_amount ?? details.price, ru)}
          </span>
        </div>
        {details.contact_unlocked && details.driver_phone && (
          <a className="info-row info-row--link" href={`tel:${details.driver_phone}`}>
            <span className="info-row__k">{appText("Водитель", "Водитель")}</span>
            <span className="info-row__v" style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
              <IconPhone size={16} /> {appText("Позвонить", "Шылтыратырға")}
            </span>
          </a>
        )}
        {details.contact_unlocked && details.pickup && (
          <div className="info-row">
            <span className="info-row__k">{appText("Точка", "Нөктә")}</span>
            <span className="info-row__v">{details.pickup}</span>
          </div>
        )}
      </div>

      {/* SOS — заглушка (полноценно в волне 7) */}
      {active && (
        <button
          type="button"
          className="sos-btn"
          onClick={() =>
            window.alert(
              appText(
                "Экстренная помощь появится в ближайшем обновлении. В опасности — звони 112.",
                "Ашығыс ярҙам яҡын яңыртыуҙа буласаҡ. Хәүефтә — 112-гә шылтырат."
              )
            )
          }
        >
          <IconWarn size={16} /> {appText("SOS — помощь", "SOS — ярҙам")}
        </button>
      )}

      {/* Оценка при завершении */}
      {st === "done" && !rated && (
        <div className="rate-card">
          <div className="rate-card__title">
            {appText("Как прошла поездка?", "Сәфәр нисек үтте?")}
          </div>
          <div className="rate-stars">
            {[1, 2, 3, 4, 5].map((n) => (
              <button
                key={n}
                type="button"
                className="rate-star"
                onClick={() => onRate(n)}
                aria-label={appText(`${n} звёзд`, `${n} йондоҙ`)}
              >
                <IconStar size={34} />
              </button>
            ))}
          </div>
        </div>
      )}
      {st === "done" && rated && (
        <div className="consents__status ok" style={{ marginTop: 14 }}>
          {appText("Спасибо за оценку! 🌿", "Баһаң өсөн рәхмәт! 🌿")}
        </div>
      )}
      {st === "done" && (
        <button
          type="button"
          className="btn-soft"
          style={{ marginTop: 12 }}
          onClick={() => navigate(`/receipt/${bookingId}`)}
        >
          {appText("Квитанция поездки", "Сәфәр квитанцияһы")}
        </button>
      )}

      {/* Чат брони */}
      <TripChat bookingId={bookingId} myId={user?.id ?? -1} />
    </>
  );
}

// ---- Чат: REST-история + живой WS, поллинг как фолбэк ----
function TripChat({ bookingId, myId }: { bookingId: number; myId: number }) {
  const { appText } = useLang();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [text, setText] = useState("");
  const [live, setLive] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const endRef = useRef<HTMLDivElement | null>(null);
  const chatRef = useRef<ReturnType<typeof openBookingChat> | null>(null);

  const upsert = useCallback((m: ChatMessage) => {
    setMessages((prev) => (prev.some((x) => x.id === m.id) ? prev : [...prev, m]));
  }, []);

  // История + WS.
  useEffect(() => {
    if (!bookingId) return;
    let alive = true;
    fetchMessages(bookingId)
      .then((list) => {
        if (alive) {
          setMessages(list);
          setLoaded(true);
        }
      })
      .catch(() => alive && setLoaded(true));

    const chat = openBookingChat(bookingId, {
      onMessage: upsert,
      onOpen: () => setLive(true),
      onClose: () => setLive(false),
      onError: () => setLive(false),
    });
    chatRef.current = chat;
    return () => {
      alive = false;
      chat.close();
      chatRef.current = null;
    };
  }, [bookingId, upsert]);

  // Поллинг-фолбэк, когда живого WS нет.
  useEffect(() => {
    if (live || !loaded) return;
    const iv = window.setInterval(() => {
      fetchMessages(bookingId)
        .then(setMessages)
        .catch(() => {});
    }, 7000);
    return () => window.clearInterval(iv);
  }, [live, loaded, bookingId]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [messages]);

  // Общая отправка (поле ввода и быстрые ответы): живой сокет, фолбэк — REST.
  async function sendText(t: string) {
    if (!t) return;
    const sentLive = chatRef.current?.send(t);
    if (!sentLive) {
      try {
        const m = await sendMessageRest(bookingId, t);
        upsert(m);
      } catch (e) {
        if (e instanceof ApiError) setText(t); // вернём текст, чтобы не потерять
      }
    }
  }

  async function send() {
    const t = text.trim();
    if (!t) return;
    setText("");
    await sendText(t);
  }

  return (
    <div className="chat">
      <div className="chat__title">{appText("Чат с водителем", "Водитель менән чат")}</div>
      <div className="chat__body">
        {loaded && messages.length === 0 && (
          <p className="chat__empty">
            {appText("Напиши первым — обсудите детали встречи.", "Беренсе булып яҙ — осрашыуҙы һөйләшегеҙ.")}
          </p>
        )}
        {messages.map((m) => {
          const mine = m.sender_id === myId;
          return (
            <div key={m.id} className={"bubble" + (mine ? " bubble--mine" : "")}>
              {m.from_admin && (
                <span className="bubble__admin">{appText("Поддержка", "Ярҙам")}</span>
              )}
              {m.text}
            </div>
          );
        })}
        <div ref={endRef} />
      </div>
      {/* Быстрые ответы — один тап отправляет готовую фразу */}
      <QuickReplies onPick={(t) => void sendText(t)} />
      <div className="chat__input">
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") send();
          }}
          placeholder={appText("Сообщение…", "Хат…")}
          aria-label={appText("Сообщение", "Хат")}
        />
        <button type="button" onClick={send} aria-label={appText("Отправить", "Ебәрергә")}>
          <IconArrow size={20} />
        </button>
      </div>
    </div>
  );
}
