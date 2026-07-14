// ================================================================
//  Активная поездка (сшивает bookings.py + chat.py + location.py).
//  - Live-статус: поллинг GET /bookings/{id}/role (status + driver_phase).
//  - Карта: маршрут A→B + live-точка водителя (WS /ws/trip/{id}/location).
//  - Код посадки: GET /bookings/{id}/boarding-code (для confirmed/onboard).
//  - Чат брони: REST-история + живой WS /ws/bookings/{id} (иначе поллинг).
//  - Оценка при завершении: POST /bookings/{id}/rate.
//  - Договорённость об оплате — read-only. SOS — заглушка (волна 7).
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
import { LoadingList, ErrorState } from "../components/States";
import YandexMap, { type GeoPoint } from "../components/YandexMap";
import { StatusPill } from "../components/StatusPill";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconStar } from "../components/Icons";
import { formatWhen, priceLabel, payMethodLabel } from "../utils/format";

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
            <span className="info-row__v">📞 {appText("Позвонить", "Шылтыратырға")}</span>
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
          🆘 {appText("SOS — помощь", "SOS — ярҙам")}
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

  async function send() {
    const t = text.trim();
    if (!t) return;
    setText("");
    // Пробуем живым сокетом; если не отправилось — REST (он тоже разошлёт в сокеты).
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
