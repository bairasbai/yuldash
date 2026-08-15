// ================================================================
//  Активная поездка (сшивает bookings.py + chat.py + location.py).
//  - Live-статус: поллинг GET /bookings/{id}/role (status + driver_phase).
//  - Карта: маршрут A→B + live-точка водителя (WS /ws/trip/{id}/location).
//  - Код посадки: GET /bookings/{id}/boarding-code (для confirmed/onboard).
//  - Чат брони: REST-история + живой WS /ws/bookings/{id} (иначе поллинг).
//  - Оценка при завершении: POST /bookings/{id}/rate.
//  - Договорённость об оплате: POST /bookings/{id}/pay-agreement —
//    запись «как решили платить», её правит любая сторона. SOS ведёт на /sos.
//  - Водитель двигает статус: POST /bookings/{id}/driver-status
//    («выехал / подъезжаю / завершил») — пассажир перестаёт гадать.
//  - «Поделиться поездкой с близким» + статусы для близких (family.py).
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
  ratingTags,
  setPayAgreement,
  type BookingDetails,
  type PayMethod,
  type TripState,
} from "../api/bookings";
import {
  fetchMessages,
  sendMessageRest,
  openBookingChat,
  openTripLocation,
  editMessage,
  deleteMessage,
  type ChatMessage,
} from "../api/chat";
import { ChatFlagPlate, ChatSafetyDisclaimer } from "../components/ChatSafety";
import { winterCheck, winterCheckOk } from "../api/safety";
import { setDriverStatus, type DriverPhase } from "../api/driver";
import { LoadingList, ErrorState } from "../components/States";
import YandexMap, { type GeoPoint } from "../components/YandexMap";
import { StatusPill } from "../components/StatusPill";
import QuickReplies from "../components/QuickReplies";
import { SubHeader } from "./ConsentsScreen";
import WeatherWarningCard, { useRouteWeather } from "../components/WeatherWarningCard";
import ShareTripCard from "../components/ShareTripCard";
import { ChatPhotoButton, ChatMessageBody } from "../components/ChatPhoto";
import { ChatVoiceButton, VoiceBubble } from "../components/ChatVoice";
import { IconArrow, IconStar, IconPhone, IconWarn, IconCheck, IconCar, IconWallet } from "../components/Icons";
import { formatWhen, priceLabel, payMethodLabel } from "../utils/format";
import { serverMs } from "../utils/serverTime";
import { enqueue, outboxCount, subscribeOutbox, watchOutbox } from "../utils/outbox";
import { useVisibleInterval } from "../utils/useVisibleInterval";

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
  const depart = serverMs(d.depart_at);
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
  const [phaseBusy, setPhaseBusy] = useState(false); // водитель отправляет «выехал/подъезжаю/завершил»
  const [phaseNote, setPhaseNote] = useState("");
  // Договорённость об оплате — правит любая сторона, видят оба.
  const [payMethod, setPayMethod] = useState<PayMethod>("cash");
  const [payAmount, setPayAmount] = useState("");
  const [payBusy, setPayBusy] = useState(false);
  const [payNote, setPayNote] = useState("");

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

  // Форму оплаты заполняем тем, о чём уже договорились: человек правит, а не вводит заново.
  useEffect(() => {
    if (!details) return;
    setPayMethod(details.pay_method);
    setPayAmount(details.pay_amount != null ? String(details.pay_amount) : "");
  }, [details]);

  // ---- Живой статус поездки: раз в 10 сек, пока экран виден ----
  // В фоне телефон таймеры всё равно морозит, а тут ещё и трафик экономим.
  // Главное — при ВОЗВРАЩЕНИИ статус обновляется сразу: свернул на десять минут,
  // открыл — и видишь, что водитель уже выехал, а не то, что было при уходе.
  const tickTrip = useCallback(() => {
    if (!bookingId) return;
    fetchTripState(bookingId)
      .then(setTrip)
      .catch(() => {
        /* сеть моргнула — попробуем в следующий тик */
      });
  }, [bookingId]);

  useEffect(() => {
    tickTrip();
  }, [tickTrip]);

  useVisibleInterval(10000, tickTrip, !!bookingId);

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

  // ❄️ Погода на маршруте. Хук зовём ДО ранних return — порядок хуков должен быть постоянным.
  // Поездка завершена/отменена → предупреждать уже поздно и незачем.
  const weather = useRouteWeather(
    {
      fromLat: details?.from_lat ?? null,
      fromLng: details?.from_lng ?? null,
      toLat: details?.to_lat ?? null,
      toLng: details?.to_lng ?? null,
      fromCity: details?.from_city ?? "",
      toCity: details?.to_city ?? "",
      at: details?.depart_at ?? undefined,
    },
    Boolean(details) && st !== "done" && st !== "cancelled"
  );

  /** Записать, как договорились платить. Это не платёж — только запись для обеих сторон. */
  async function savePay() {
    if (payBusy) return;
    setPayBusy(true);
    setPayNote("");
    try {
      const amount = payAmount.trim() ? Math.max(0, Math.round(Number(payAmount))) : null;
      await setPayAgreement(bookingId, payMethod, amount);
      load(); // перечитываем детали — договорённость показывается выше
      setPayNote(appText("Записали. Вторая сторона это видит.", "Яҙҙыҡ. Икенсе яҡ быны күрә."));
    } catch (e) {
      setPayNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не удалось сохранить. Проверь сеть.", "Һаҡлап булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setPayBusy(false);
    }
  }

  /** Водитель: «выехал / подъезжаю / завершил». Ошибку показываем текстом, экран не ломаем. */
  async function sendPhase(phase: DriverPhase) {
    if (phaseBusy) return;
    setPhaseBusy(true);
    setPhaseNote("");
    try {
      await setDriverStatus(bookingId, phase);
      const fresh = await fetchTripState(bookingId);
      setTrip(fresh);
      if (phase === "done") setPhaseNote(appText("Поездка завершена", "Сәфәр тамамланды"));
    } catch (e) {
      if (e instanceof ApiError && e.status === 0) {
        // Трасса без связи: отметка не пропадает — уйдёт сама, когда сеть вернётся.
        enqueue(bookingId, "driver_status", phase);
        setPhaseNote(appText("Нет сети — отправим позже", "Селтәр юҡ — һуңыраҡ ебәрербеҙ"));
      } else {
        setPhaseNote(
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер.")
        );
      }
    } finally {
      setPhaseBusy(false);
    }
  }

  async function winterAnswerOk() {
    setWinterAsk(false);
    try {
      await winterCheckOk(bookingId);
    } catch {
      /* уже отмечено / нет ручки — не критично */
    }
  }

  /**
   * Оценка в два шага: сначала звёзды, потом метки. Метки зависят от оценки —
   * «Везёт аккуратно» после двойки было бы издевательством, поэтому набор
   * пересобирается, а выбранное сбрасывается при смене звёзд.
   */
  const [myStars, setMyStars] = useState(0);
  const [myTags, setMyTags] = useState<string[]>([]);
  const [rateText, setRateText] = useState("");
  const [rateBusy, setRateBusy] = useState(false);

  function pickStars(n: number) {
    setMyStars(n);
    setMyTags([]);
  }

  function toggleTag(key: string) {
    setMyTags((prev) =>
      prev.includes(key) ? prev.filter((t) => t !== key) : prev.length >= 5 ? prev : [...prev, key]
    );
  }

  async function sendRate() {
    if (!bookingId || myStars < 1 || rateBusy) return;
    setRateBusy(true);
    try {
      await rateBooking(bookingId, myStars, rateText.trim(), myTags);
      setRated(true);
    } catch {
      /* уже оценено / не завершена — тихо */
      setRated(true);
    } finally {
      setRateBusy(false);
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

      {/* ❄️ Погода на маршруте — до выезда, а не когда машина уже на трассе */}
      <WeatherWarningCard weather={weather} />

      {/* Живой баннер статуса */}
      <div className="trip-banner">
        <StatusPill status={(trip?.status ?? details.status) as BookingDetails["status"]} phase={trip?.driver_phase} arrivalVerified={trip?.arrival_verified} />
        {st === "pending" && (
          <span className="trip-banner__hint">
            {appText("Ждём, пока водитель подтвердит.", "Йөрөтөүсе раҫлағанды көтәбеҙ.")}
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
            {appText("Назови его водителю при посадке", "Ултырғанда йөрөтөүсегә әйт")}
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
            <span className="info-row__k">{appText("Водитель", "Йөрөтөүсе")}</span>
            <span className="info-row__v" style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
              <IconPhone size={16} /> {appText("Позвонить", "Шылтыратыу")}
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

      {/* Как договорились платить. Это ЗАПИСЬ, а не платёж: деньги через приложение не идут.
          Менять может любая сторона — запись видна обоим и служит опорой в споре
          «мы же договаривались о 400». Раньше на сайте её можно было только читать. */}
      {active && (
        <div className="act-card">
          <div className="act-card__title">
            <IconWallet size={18} /> {appText("Как договорились платить", "Түләү тураһында нисек килештек")}
          </div>
          <p className="act-card__text">
            {appText(
              "Это просто запись договорённости — деньги через приложение не проходят.",
              "Был — килешеү яҙмаһы ғына, аҡса ҡушымта аша үтмәй."
            )}
          </p>
          <div className="seg">
            {(["cash", "sbp", "negotiate"] as const).map((m) => (
              <button
                key={m}
                type="button"
                className={"seg__item" + (payMethod === m ? " is-active" : "")}
                onClick={() => setPayMethod(m)}
              >
                {m === "cash"
                  ? appText("Наличными", "Наличный менән")
                  : m === "sbp"
                    ? appText("Перевод по СБП", "СБП аша күсереү")
                    : appText("Договоримся", "Килешербеҙ")}
              </button>
            ))}
          </div>
          <label className="field" style={{ marginTop: 10 }}>
            <span className="field__label">{appText("Сумма, ₽ (необязательно)", "Сумма, ₽ (мотлаҡ түгел)")}</span>
            <input
              className="field__input"
              type="number"
              inputMode="numeric"
              min={0}
              value={payAmount}
              onChange={(e) => setPayAmount(e.target.value)}
              placeholder={String(details.pay_amount ?? details.price ?? "")}
            />
          </label>
          <button
            type="button"
            className="btn-soft"
            style={{ width: "100%", marginTop: 10 }}
            onClick={savePay}
            disabled={payBusy}
          >
            {payBusy
              ? appText("Сохраняем…", "Һаҡлайбыҙ…")
              : appText("Записать договорённость", "Килешеүҙе яҙып ҡуйыу")}
          </button>
          {payNote && <p className="demand__quiet">{payNote}</p>}
        </div>
      )}

      {/* Водитель двигает статус — пассажир перестаёт гадать, едут за ним или нет */}
      {details.role === "driver" && active && (
        <div className="act-card">
          <div className="act-card__title">
            <IconCar size={18} /> {appText("Сообщить пассажиру", "Пассажирға хәбәр итеү")}
          </div>
          <p className="act-card__text">
            {appText(
              "Он увидит, что ты уже в пути, и не будет гадать.",
              "Ул һинең юлда икәнеңде күрер, уйланып торманы."
            )}
          </p>
          <div className="chips">
            <button
              type="button"
              className={"chip" + (trip?.driver_phase === "departed" ? " chip--on" : "")}
              onClick={() => sendPhase("departed")}
              disabled={phaseBusy}
            >
              {appText("Выехал", "Сыҡтым")}
            </button>
            <button
              type="button"
              className={"chip" + (trip?.driver_phase === "arriving" ? " chip--on" : "")}
              onClick={() => sendPhase("arriving")}
              disabled={phaseBusy}
            >
              {appText("Подъезжаю", "Килеп етәм")}
            </button>
            <button
              type="button"
              className="chip"
              onClick={() => sendPhase("done")}
              disabled={phaseBusy}
            >
              {appText("Завершить поездку", "Сәфәрҙе тамамлау")}
            </button>
          </div>
          {phaseNote && <p className="demand__quiet">{phaseNote}</p>}
        </div>
      )}

      {/* Поделиться поездкой с близким + статусы (у пассажира) */}
      {active && details.role === "passenger" && (
        <ShareTripCard bookingId={bookingId} showStatuses />
      )}

      {/* SOS — ведёт на настоящий экран помощи */}
      {active && (
        <button type="button" className="sos-btn" onClick={() => navigate("/sos")}>
          <IconWarn size={16} /> {appText("SOS — нужна помощь", "SOS — ярҙам")}
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
                className={"rate-star" + (n <= myStars ? " is-on" : "")}
                onClick={() => pickStars(n)}
                aria-label={appText(`${n} звёзд`, `${n} йондоҙ`)}
              >
                <IconStar size={34} />
              </button>
            ))}
          </div>

          {/* Метки — один тап вместо сочинения. Развёрнутый отзыв пишут единицы,
              а метку ставит почти каждый: из них и складывается портрет человека. */}
          {myStars > 0 && (
            <>
              <div className="chips rate-tags">
                {ratingTags(myStars, details.role === "passenger").map((t) => (
                  <button
                    key={t.key}
                    type="button"
                    className={"chip" + (myTags.includes(t.key) ? " chip--on" : "")}
                    onClick={() => toggleTag(t.key)}
                  >
                    {ru ? t.ru : t.ba}
                  </button>
                ))}
              </div>

              <label className="field" style={{ marginTop: 10 }}>
                <span className="field__label">
                  {appText("Пара слов о поездке (необязательно)", "Сәфәр хаҡында бер-ике һүҙ (мотлаҡ түгел)")}
                </span>
                <input
                  className="field__input"
                  value={rateText}
                  maxLength={500}
                  onChange={(e) => setRateText(e.target.value)}
                />
              </label>

              <button type="button" className="btn-primary" style={{ marginTop: 10 }} onClick={() => void sendRate()} disabled={rateBusy}>
                {rateBusy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Отправить оценку", "Баһаны ебәрергә")}
              </button>
            </>
          )}
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

/** «1 сообщение ждёт» / «2 сообщения ждут» / «5 сообщений ждут». */
function queuedWordRu(n: number): string {
  const m10 = n % 10;
  const m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return "сообщение ждёт";
  if (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) return "сообщения ждут";
  return "сообщений ждут";
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

  /** Правка/удаление: id сообщения, которое сейчас в работе. */
  const [menuId, setMenuId] = useState<number | null>(null);
  const [editId, setEditId] = useState<number | null>(null);
  const [editText, setEditText] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);

  /** Сколько сообщений ждёт сети (плашка «отправим, когда появится связь»). */
  const [queued, setQueued] = useState(() => outboxCount(bookingId));
  /** Вложение (голосовое или фото) не ушло — говорим об этом человеку. */
  const [attachNote, setAttachNote] = useState("");

  const upsert = useCallback((m: ChatMessage) => {
    setMessages((prev) => (prev.some((x) => x.id === m.id) ? prev : [...prev, m]));
  }, []);

  /** Сервер вернул обновлённое сообщение — подменяем его на месте, не дёргая всю историю. */
  const replace = useCallback((m: ChatMessage) => {
    setMessages((prev) => prev.map((x) => (x.id === m.id ? m : x)));
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

  // Сеть вернулась → досылаем накопленное и забираем историю с сервера (она авторитетная).
  useEffect(() => {
    if (!bookingId) return;
    const stop = watchOutbox(() => {
      setQueued(outboxCount(bookingId));
      fetchMessages(bookingId)
        .then(setMessages)
        .catch(() => {});
    });
    const unsub = subscribeOutbox(() => setQueued(outboxCount(bookingId)));
    return () => {
      stop();
      unsub();
    };
  }, [bookingId]);

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
        if (e instanceof ApiError && e.status === 0) {
          // Сети нет (трасса) — не теряем: отправим сами, когда связь вернётся.
          enqueue(bookingId, "message", t);
          setQueued(outboxCount(bookingId));
        } else if (e instanceof ApiError) {
          setText(t); // сервер отказал — вернём текст, решать человеку
        }
      }
    }
  }

  /** Голос уходит по REST: сокет передаёт только текст, а ссылку надо положить в поле. */
  async function sendVoice(voiceUrl: string) {
    try {
      const m = await sendMessageRest(bookingId, "", voiceUrl);
      upsert(m);
      setAttachNote("");
    } catch {
      // Раньше голосовое пропадало молча: человек говорил в трубку, отпускал —
      // и не знал, что записи никто не получил. В очередь его не положить
      // (запись живёт на сервере ограниченно), поэтому честно просим повторить.
      setAttachNote(
        appText("Голосовое не ушло. Запиши ещё раз.", "Тауыш китмәне. Тағы яҙып ҡара.")
      );
    }
  }

  /** Сохранить правку своего сообщения. Пустой текст = отмена, а не «стереть текст». */
  async function saveEdit(id: number) {
    const t = editText.trim();
    if (!t || busyId) {
      setEditId(null);
      return;
    }
    setBusyId(id);
    try {
      replace(await editMessage(bookingId, id, t));
      setEditId(null);
    } catch {
      // Поле остаётся открытым — текст не потерян. Но человек не знает, что
      // правка не ушла: он видит своё новое сообщение и думает, что сохранил.
      setAttachNote(
        appText("Правка не сохранилась. Попробуй ещё раз.", "Төҙәтеү һаҡланманы. Тағы ҡабатла.")
      );
    } finally {
      setBusyId(null);
    }
  }

  /** Удалить: у всех (только своё) или скрыть у себя. */
  async function removeMessage(id: number, scope: "all" | "me") {
    if (busyId) return;
    setMenuId(null);
    setBusyId(id);
    try {
      const m = await deleteMessage(bookingId, id, scope);
      // «У себя» сервер не возвращает пустой текст — просто убираем из своего списка.
      if (scope === "me") setMessages((prev) => prev.filter((x) => x.id !== id));
      else replace(m);
    } catch {
      // Сообщение осталось на месте — но человек нажал «удалить» и ждёт,
      // что оно исчезнет. Молчание он прочитает как «кнопка не работает».
      setAttachNote(
        appText("Не получилось удалить. Попробуй ещё раз.", "Юйып булманы. Тағы ҡабатла.")
      );
    } finally {
      setBusyId(null);
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
      <div className="chat__title">{appText("Чат с водителем", "Йөрөтөүсе менән чат")}</div>
      <div className="chat__body">
        <ChatSafetyDisclaimer />
        {!loaded && (
          <div className="chat__loading" aria-live="polite">
            <span className="skeleton chat__skeleton" />
            <span className="skeleton chat__skeleton chat__skeleton--mine" />
            <span className="skeleton chat__skeleton" />
          </div>
        )}
        {loaded && messages.length === 0 && (
          <p className="chat__empty">
            {appText("Напиши первым — обсудите детали встречи.", "Беренсе булып яҙ — осрашыуҙы һөйләшегеҙ.")}
          </p>
        )}
        {messages.map((m) => {
          const mine = m.sender_id === myId;
          // Своё текстовое можно поправить, удалить у всех или скрыть у себя.
          // Чужое — только скрыть у себя: чужие слова не наши.
          const canEdit = mine && !m.deleted && !m.voice_url;
          if (m.deleted) {
            return (
              <div key={m.id} className={"bubble bubble--gone" + (mine ? " bubble--mine" : "")}>
                {appText("Сообщение удалено", "Хәбәр юйылды")}
              </div>
            );
          }
          return (
            <div key={m.id} className={"msg" + (mine ? " msg--mine" : "")}>
            <div className={"bubble" + (mine ? " bubble--mine" : "")}>
              {m.from_admin && <span className="bubble__admin">Юлдаш ✓</span>}

              {editId === m.id ? (
                <div className="bubble__edit">
                  <input
                    value={editText}
                    autoFocus
                    onChange={(e) => setEditText(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") void saveEdit(m.id);
                      if (e.key === "Escape") setEditId(null);
                    }}
                    aria-label={appText("Поправить сообщение", "Хәбәрҙе төҙәтеү")}
                  />
                  <button type="button" onClick={() => void saveEdit(m.id)} disabled={busyId === m.id}>
                    {appText("Сохранить", "Һаҡлау")}
                  </button>
                  <button type="button" onClick={() => setEditId(null)}>
                    {appText("Отмена", "Баш тартыу")}
                  </button>
                </div>
              ) : (
                <>
                  {m.voice_url ? (
                    <VoiceBubble url={m.voice_url} />
                  ) : (
                    <ChatMessageBody text={m.text} />
                  )}
                  {m.edited && (
                    <span className="bubble__edited">{appText("изменено", "төҙәтелгән")}</span>
                  )}
                  <button
                    type="button"
                    className="bubble__more"
                    aria-label={appText("Действия с сообщением", "Хәбәр менән эштәр")}
                    onClick={() => setMenuId(menuId === m.id ? null : m.id)}
                  >
                    ⋯
                  </button>
                </>
              )}

              {menuId === m.id && (
                <div className="bubble__menu" role="menu">
                  {canEdit && (
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => {
                        setEditText(m.text);
                        setEditId(m.id);
                        setMenuId(null);
                      }}
                    >
                      {appText("Поправить", "Төҙәтергә")}
                    </button>
                  )}
                  {mine && (
                    <button
                      type="button"
                      role="menuitem"
                      className="bubble__menu-danger"
                      onClick={() => void removeMessage(m.id, "all")}
                    >
                      {appText("Удалить у всех", "Барыһынан юйырға")}
                    </button>
                  )}
                  <button type="button" role="menuitem" onClick={() => void removeMessage(m.id, "me")}>
                    {appText("Скрыть у себя", "Үҙемдән йәшерергә")}
                  </button>
                </div>
              )}
            </div>
              <ChatFlagPlate flag={m.flag} mine={mine} />
            </div>
          );
        })}
        <div ref={endRef} />
      </div>
      {/* Написал на трассе без связи — сообщение не пропало, ждёт сети */}
      {queued > 0 && (
        <div className="chat__queued" role="status">
          {appText(
            `${queued} ${queuedWordRu(queued)} сети — отправим сами`,
            `${queued} хат селтәрҙе көтә — үҙебеҙ ебәрербеҙ`
          )}
        </div>
      )}
      {attachNote && (
        <div className="chat__queued" role="status">
          {attachNote}
        </div>
      )}
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
          maxLength={4000}
          aria-label={appText("Сообщение", "Хәбәр")}
        />
        <ChatPhotoButton onReady={(t) => void sendText(t)} onProblem={setAttachNote} />
                <ChatVoiceButton onSend={(u) => void sendVoice(u)} onProblem={setAttachNote} />
        <button type="button" onClick={send} aria-label={appText("Отправить", "Ебәреү")}>
          <IconArrow size={20} />
        </button>
      </div>
    </div>
  );
}
