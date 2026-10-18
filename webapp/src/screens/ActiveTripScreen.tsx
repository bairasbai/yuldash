// ================================================================
//  Активная поездка (сшивает bookings.py + chat.py + location.py).
//  - Live-статус: поллинг GET /bookings/{id}/role (status + driver_phase).
//  - Карта: маршрут A→B + live-точка водителя (WS /ws/trip/{id}/location).
//  - Код посадки: GET /bookings/{id}/boarding-code (для confirmed/onboard).
//  - Чат брони — отдельная страница /trip/:id/chat (TripChatScreen.tsx);
//    здесь только карточка «Чат по поездке» (последнее сообщение + новые).
//  - Оценка при завершении: POST /bookings/{id}/rate.
//  - Договорённость об оплате: POST /bookings/{id}/pay-agreement —
//    запись «как решили платить», её правит любая сторона. SOS ведёт на /sos.
//  - Водитель двигает статус: POST /bookings/{id}/driver-status
//    («выехал / подъезжаю / завершил») — пассажир перестаёт гадать.
//  - «Поделиться поездкой с близким» + статусы для близких (family.py).
//  - Зимняя проверка «доехал?» (safety.py): по расчётной ETA пассажиру
//    показываем мягкий вопрос; «Доехал ✓» → POST winter-check/ok.
//  - Паритет с Android (16.10.2026, BookingActiveTripScreen.kt):
//    · «Завершить поездку» у водителя — через окно «Завершить поездку?»;
//    · пассажир отменяет ПОДТВЕРЖДЁННУЮ поездку с причиной (POST /bookings/{id}/cancel {reason});
//      после посадки отмены нет — только SOS и разбор;
//    · водитель в пути отправляет свою точку в канал поездки (DriverLocationShare).
//  - Посадку отмечает ВОДИТЕЛЬ (решение 17.10.2026): у него «Пассажир сел» и «Отменить» в первые
//    5 минут (DriverBoarding), у пассажира — «Водитель отметит посадку» и «Сообщить о проблеме»
//    (BoardingWait). Своего «Я сел» у пассажира нет: его можно было нажать из дома.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import {
  CANCEL_REASONS,
  cancelBooking,
  fetchBookingDetails,
  fetchTripState,
  fetchBoardingCode,
  rateBooking,
  ratingTags,
  setPayAgreement,
  type BoardingResult,
  type BookingDetails,
  type BookingStatus,
  type CancelReason,
  type TripState,
} from "../api/bookings";
import { openTripLocation } from "../api/chat";
import { winterCheck, winterCheckOk } from "../api/safety";
import RoadsideHelp from "../components/RoadsideHelp";
import AlertDialog from "../components/AlertDialog";
import DriverLocationShare from "../components/DriverLocationShare";
import { driverInTransit } from "../utils/tripLocationShare";
import { useRefreshOwnBookings } from "../booking/OwnBookingsProvider";
import { track } from "../analytics";
import { clearTripPassBoardingCode, dropTripPass, tripPassStage, type TripPass } from "../utils/tripPass";
import { sosPath } from "../utils/sosLink";
import { loadOfflineTripPass, saveVerifiedTripPass } from "../utils/offlineTripPass";
import OfflineTripPass from "../components/OfflineTripPass";
import { setDriverStatus, type DriverPhase } from "../api/driver";
import { LoadingList, ErrorState } from "../components/States";
import YandexMap, { type GeoPoint } from "../components/YandexMap";
import { StatusPill } from "../components/StatusPill";
import { SubHeader } from "./ConsentsScreen";
import WeatherWarningCard, { useRouteWeather } from "../components/WeatherWarningCard";
import ShareTripCard from "../components/ShareTripCard";
import DriverBoarding from "../components/DriverBoarding";
import BoardingWait from "../components/BoardingWait";
import DriverBoardedDispute from "../components/DriverBoardedDispute";
import TripChatCard from "../components/TripChatCard";
import { IconStar, IconPhone, IconWarn, IconCheck, IconCar, IconWallet, IconClose } from "../components/Icons";
import { formatWhen, priceLabel, payMethodLabel } from "../utils/format";
import { serverMs } from "../utils/serverTime";
import { enqueue } from "../utils/outbox";
import { useVisibleInterval } from "../utils/useVisibleInterval";
import {
  forgetWinterCheck,
  rememberWinterCheck,
  wasWinterCheckAsked,
  winterCheckDelay,
  winterCheckNeedsAnswer,
} from "../utils/winterCheck.js";
import {
  isTerminalBookingStatus,
  shouldApplyBookingDetails,
  shouldRefreshBookingDetails,
  reconcilePaymentDraft,
  acceptPaymentDraft,
  type PaymentDraft,
} from "../utils/tripDetailsSync.js";

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
  const sessionGeneration = useRef(getSessionGeneration()).current;
  const { id } = useParams();
  const bookingId = Number(id);
  const currentBookingIdRef = useRef(bookingId);
  currentBookingIdRef.current = bookingId;

  const [status, setStatus] = useState<"loading" | "error" | "ready">("loading");
  const [details, setDetails] = useState<BookingDetails | null>(null);
  const [trip, setTrip] = useState<TripState | null>(null);
  const [code, setCode] = useState<string | null>(null);
  /**
   * Офлайн-паспорт: снимок брони, сохранённый локально. Нужен ровно там, где сети нет —
   * у машины на ночной трассе. Читаем СРАЗУ, до всякой загрузки: если сервер не ответит,
   * человеку всё равно есть что показать.
   */
  const [pass, setPass] = useState<TripPass | null>(() => loadOfflineTripPass(bookingId));
  const [driverLoc, setDriverLoc] = useState<GeoPoint | null>(null);
  const [rated, setRated] = useState(false);
  const [winterAskForId, setWinterAskForId] = useState<number | null>(null);
  const winterAsk = winterAskForId === bookingId; // вопрос принадлежит только текущей брони
  const [winterAnswerBusy, setWinterAnswerBusy] = useState(false);
  const [winterAnswerNote, setWinterAnswerNote] = useState("");
  const [phaseBusy, setPhaseBusy] = useState(false); // водитель отправляет «выехал/подъезжаю/завершил»
  const [phaseNote, setPhaseNote] = useState("");
  /** Окно «Завершить поездку?» — завершение нельзя вернуть, случайный тап его не делает. */
  const [confirmFinish, setConfirmFinish] = useState(false);
  // Отмена подтверждённой поездки пассажиром: окно с причиной (по желанию), как в Android.
  const refreshOwnBookings = useRefreshOwnBookings();
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState<CancelReason>("");
  const [cancelBusy, setCancelBusy] = useState(false);
  const [cancelNote, setCancelNote] = useState("");
  // Договорённость об оплате — правит любая сторона, видят оба.
  const [payDraft, setPayDraft] = useState<PaymentDraft>({ bookingId, payMethod: "cash", payAmount: "", dirty: false });
  const { payMethod, payAmount } = payDraft;
  const [payBusy, setPayBusy] = useState(false);
  const [payNote, setPayNote] = useState("");
  const tripRef = useRef<TripState | null>(null);
  const detailsStatusRef = useRef<BookingStatus | null>(null);
  const detailsSyncBusyRef = useRef(false);
  const lastDetailsAtRef = useRef(Date.now());
  /** Счётчик «Сел» / «Отменить» водителя: опрос, начатый до действия, его ответ не перетирает. */
  const boardingSeqRef = useRef(0);

  /** Один путь применения details: UI, форма оплаты и офлайн-паспорт не расходятся. */
  const applyBookingDetails = useCallback(
    (d: BookingDetails) => {
      detailsStatusRef.current = d.status;
      lastDetailsAtRef.current = Date.now();
      setDetails(d);
      if (d.status === "onboard") clearTripPassBoardingCode(bookingId, sessionGeneration);
      if (isTerminalBookingStatus(d.status)) {
        dropTripPass(bookingId, sessionGeneration);
        setPass(null);
      } else if (d.contact_unlocked) {
        setPass((previous) => {
          const snapshot = {
            bookingId,
            fromCity: d.from_city,
            toCity: d.to_city,
            departAt: d.depart_at,
            driverName: d.driver_name,
            driverCar: d.driver_car,
            driverPlate: d.driver_plate ?? "",
            driverPhone: d.driver_phone,
            boardingCode: d.role === "passenger" && d.status === "confirmed" && previous?.bookingId === bookingId &&
              previous.ownerId === user?.id && previous.sessionGeneration === sessionGeneration
              ? previous.boardingCode
              : "",
            pickup: d.pickup,
            price: d.pay_amount ?? d.price,
            seats: d.seats,
          };
          saveVerifiedTripPass(snapshot, user?.id, sessionGeneration);
          return { ...snapshot, ownerId: user?.id, sessionGeneration, savedAt: Date.now() };
        });
      }
    },
    [bookingId, user?.id, sessionGeneration]
  );

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
          applyBookingDetails(d);
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus("error");
        });
    },
    [applyBookingDetails, bookingId]
  );

  /** Фоновая сверка не переводит весь экран в loading и не скрывает старые данные при ошибке. */
  const syncBookingDetails = useCallback(async () => {
    if (!bookingId || currentBookingIdRef.current !== bookingId || detailsSyncBusyRef.current) return;
    detailsSyncBusyRef.current = true;
    lastDetailsAtRef.current = Date.now();
    try {
      const fresh = await fetchBookingDetails(bookingId);
      if (currentBookingIdRef.current !== bookingId) return;
      if (shouldApplyBookingDetails(tripRef.current?.status, fresh.status)) {
        applyBookingDetails(fresh);
      }
    } catch {
      /* Оставляем последний честный снимок; следующий редкий тик повторит. */
    } finally {
      if (currentBookingIdRef.current === bookingId) detailsSyncBusyRef.current = false;
    }
  }, [applyBookingDetails, bookingId]);

  useEffect(() => {
    tripRef.current = null;
    detailsStatusRef.current = null;
    detailsSyncBusyRef.current = false;
    lastDetailsAtRef.current = Date.now();
    setPass(loadOfflineTripPass(bookingId));
  }, [bookingId]);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Форму оплаты заполняем тем, о чём уже договорились: человек правит, а не вводит заново.
  useEffect(() => {
    if (!details) return;
    setPayDraft((current) => reconcilePaymentDraft(current, bookingId, details));
  }, [bookingId, details]);

  // ---- Живой статус поездки: раз в 10 сек, пока экран виден ----
  // В фоне телефон таймеры всё равно морозит, а тут ещё и трафик экономим.
  // Главное — при ВОЗВРАЩЕНИИ статус обновляется сразу: свернул на десять минут,
  // открыл — и видишь, что водитель уже выехал, а не то, что было при уходе.
  const tickTrip = useCallback(() => {
    if (!bookingId) return;
    const boardingSeq = boardingSeqRef.current;
    return fetchTripState(bookingId)
      .then((fresh) => {
        if (currentBookingIdRef.current !== bookingId) return;
        // Опрос ушёл до «Сел» / «Отменить» водителя — его ответ старше ответа самого действия.
        if (boardingSeq !== boardingSeqRef.current) return;
        const previousStatus = tripRef.current?.status ?? detailsStatusRef.current;
        if (!shouldApplyBookingDetails(previousStatus, fresh.status)) return;
        tripRef.current = fresh;
        setTrip(fresh);
        if (fresh.status === "onboard") {
          clearTripPassBoardingCode(bookingId, sessionGeneration);
          setPass((previous) => previous?.bookingId === bookingId && previous.boardingCode
            ? { ...previous, boardingCode: "" }
            : previous);
        }
        if (isTerminalBookingStatus(fresh.status)) {
          dropTripPass(bookingId, sessionGeneration);
          setPass(null);
          return;
        }
        const elapsed = Date.now() - lastDetailsAtRef.current;
        if (shouldRefreshBookingDetails(previousStatus, fresh.status, elapsed)) {
          void syncBookingDetails();
        }
      })
      .catch(() => {
        /* сеть моргнула — попробуем в следующий тик */
      });
  }, [bookingId, syncBookingDetails, sessionGeneration]);

  useEffect(() => {
    if (status === "ready") tickTrip();
  }, [status, tickTrip]);

  useVisibleInterval(10000, tickTrip, !!bookingId && status === "ready");

  /**
   * Ответ на «Сел» / «Отменить» водителя — сразу на экран. Это ответ самого действия, а не
   * опроса: «Отменить» возвращает бронь из «в пути» в «подтверждена», а опрос назад по шкале
   * статусов не ходит (запоздавший ответ не должен откатывать посадку). Отказ сервера —
   * перечитываем как обычно.
   */
  const applyBoarding = useCallback(
    (result?: BoardingResult) => {
      if (!result) return tickTrip();
      boardingSeqRef.current += 1;
      const current = tripRef.current;
      if (!current) return tickTrip();
      const next = { ...current, status: result.status, boarding_undo_sec: result.boarding_undo_sec };
      tripRef.current = next;
      setTrip(next);
    },
    [tickTrip]
  );

  useEffect(() => {
    const changed = (event: StorageEvent) => {
      if (event.key !== null && !event.key.startsWith(`yuldash.tripPass.${bookingId}.`)) return;
      try {
        const stage = tripPassStage(bookingId, sessionGeneration);
        if (stage === "confirmed") return;
        setCode(null);
        setPass(stage === "terminal" ? null : loadOfflineTripPass(bookingId));
      } catch {
        setCode(null);
        setPass(null);
      }
    };
    if (typeof window === "undefined") return;
    window.addEventListener("storage", changed);
    return () => window.removeEventListener("storage", changed);
  }, [bookingId, sessionGeneration]);

  const st = trip?.status ?? details?.status;
  const active = st === "confirmed" || st === "onboard";

  let savedStage: "confirmed" | "onboard" | "terminal" = "terminal";
  try { savedStage = tripPassStage(bookingId, sessionGeneration); } catch { /* no safe saved state */ }
  const needsBoardingCode = details?.role === "passenger" && st === "confirmed" && savedStage === "confirmed";

  // ---- Код нужен пассажиру до посадки, не после неё ----
  useEffect(() => {
    if (!bookingId || !needsBoardingCode) {
      setCode(null);
      return;
    }
    let alive = true;
    fetchBoardingCode(bookingId)
      .then((r) => {
        if (!alive || currentBookingIdRef.current !== bookingId ||
          tripPassStage(bookingId, sessionGeneration) !== "confirmed" ||
          !shouldApplyBookingDetails(tripRef.current?.status, "confirmed") ||
          !shouldApplyBookingDetails(detailsStatusRef.current, "confirmed")) return;
        setCode(r.code);
        // Код приходит отдельным запросом — дописываем его в уже сохранённый снимок.
        // Без этого офлайн-паспорт показывал бы всё, кроме того, что называют вслух.
        setPass((prev) => {
          if (!prev || prev.bookingId !== bookingId || !r.code) return prev;
          const next = { ...prev, boardingCode: r.code };
          saveVerifiedTripPass(next, user?.id, sessionGeneration);
          return next;
        });
      })
      .catch(() => {
        /* нет кода — не критично */
      });
    return () => {
      alive = false;
    };
  }, [bookingId, needsBoardingCode, user?.id, sessionGeneration]);

  // ---- Live-точка водителя (WS) — только пока активна и только пассажиру ----
  // Водителю этот канал на приём не нужен: свою точку он отправляет сам (DriverLocationShare),
  // а точку пассажира экран сайта не рисует — лишнее соединение и лишние чужие координаты.
  const isPassenger = details?.role === "passenger";
  useEffect(() => {
    if (!bookingId || !active || !isPassenger) return;
    const conn = openTripLocation(bookingId, (p) => setDriverLoc({ lat: p.lat, lng: p.lng }));
    return () => conn.close();
  }, [bookingId, active, isPassenger]);

  // ---- Зимняя проверка «доехал?» — только пассажиру, один раз на бронь ----
  useEffect(() => {
    setWinterAnswerNote("");
    if (!bookingId || !details) {
      setWinterAskForId(null);
      return;
    }
    if (details.role !== "passenger") {
      setWinterAskForId(null);
      return; // водителю не показываем
    }
    if (!(st === "confirmed" || st === "onboard")) {
      setWinterAskForId(null);
      return; // только живая поездка
    }
    const key = WINTER_ASKED_KEY(bookingId);
    const asked = wasWinterCheckAsked(key);

    const delay = winterCheckDelay(asked, winterDueAt(details));
    if (!isFinite(delay)) return;
    let alive = true;
    const timer = window.setTimeout(() => {
      // Сервер идемпотентен: шлёт пуш обеим сторонам / говорит «уже ок»/«рано».
      winterCheck(bookingId)
        .then((r) => {
          if (!alive) return;
          const needsAnswer = winterCheckNeedsAnswer(r.state);
          setWinterAskForId(needsAnswer ? bookingId : null);
          if (needsAnswer) rememberWinterCheck(key);
          else if (r.state === "ok" || r.state === "closed") forgetWinterCheck(key);
        })
        .catch(() => {
          if (alive && asked) setWinterAskForId(bookingId);
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
    const submittedDraft = payDraft;
    try {
      const amount = payAmount.trim() ? Math.max(0, Math.round(Number(payAmount))) : null;
      const agreement = await setPayAgreement(bookingId, payMethod, amount);
      if (currentBookingIdRef.current !== bookingId) return;
      setPayDraft((current) => acceptPaymentDraft(current, submittedDraft, agreement));
      lastDetailsAtRef.current = Date.now();
      setDetails((current) =>
        current
          ? { ...current, pay_method: agreement.pay_method, pay_amount: agreement.pay_amount ?? current.pay_amount }
          : current
      );
      setPass((current) => {
        if (!current) return current;
        const next = { ...current, price: agreement.pay_amount ?? current.price };
        saveVerifiedTripPass(next, user?.id, sessionGeneration);
        return next;
      });
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
    const generation = getSessionGeneration();
    const currentSession = () => generation === getSessionGeneration();
    setPhaseBusy(true);
    setPhaseNote("");
    try {
      await setDriverStatus(bookingId, phase);
      if (!currentSession()) return;
      const fresh = await fetchTripState(bookingId);
      if (!currentSession()) return;
      setTrip(fresh);
      if (phase === "done") setPhaseNote(appText("Поездка завершена", "Сәфәр тамамланды"));
    } catch (e) {
      if (!currentSession()) return;
      if (e instanceof ApiError && e.status === 0) {
        // Трасса без связи: отметка не пропадает — уйдёт сама, когда сеть вернётся.
        try {
          await enqueue(bookingId, "driver_status", phase);
          if (!currentSession()) return;
          setPhaseNote(appText("Нет сети — отправим позже", "Селтәр юҡ — һуңыраҡ ебәрербеҙ"));
        } catch {
          if (currentSession()) setPhaseNote(appText("Не получилось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер."));
        }
      } else {
        setPhaseNote(
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер.")
        );
      }
    } finally {
      if (currentSession()) setPhaseBusy(false);
    }
  }

  /**
   * Пассажир отменяет подтверждённую поездку (Android BookingActiveTripScreen.kt).
   * Сервер ответил не «cancelled» — значит, поездку уже завершили, пока окно было открыто:
   * говорить «отменено» нельзя, показываем правду и перечитываем статус.
   */
  async function cancelTrip() {
    if (!bookingId || cancelBusy) return;
    const generation = getSessionGeneration();
    const currentSession = () => generation === getSessionGeneration() && currentBookingIdRef.current === bookingId;
    setCancelBusy(true);
    setCancelNote("");
    try {
      const res = await cancelBooking(bookingId, cancelReason);
      if (!currentSession()) return;
      setCancelOpen(false);
      if (res.status !== "cancelled") {
        setCancelNote(
          appText("Поездка уже завершена — отменить её нельзя.", "Сәфәр тамамланған инде — уны кире алып булмай.")
        );
      } else {
        refreshOwnBookings();
        track("booking_cancel");
        // B8-8: телефон или чат уже открывались — мягко напоминаем про защиту в приложении.
        setCancelNote(
          res.contact_then_cancel
            ? appText(
                "Договорились ехать? Заверши поездку в приложении — так работает защита и SOS 💚",
                "Барырға һөйләштегеҙме? Сәфәрҙе ҡушымтала тамамла — шулай яҡлау һәм SOS эшләй 💚"
              )
            : appText("Поездка отменена", "Сәфәр кире алынды")
        );
      }
      await tickTrip();
      void syncBookingDetails();
    } catch (e) {
      if (!currentSession()) return;
      setCancelNote(
        e instanceof ApiError && e.message ? e.message : appText("Не удалось отменить", "Кире алып булманы")
      );
    } finally {
      if (currentSession()) setCancelBusy(false);
    }
  }

  async function winterAnswerOk() {
    if (winterAnswerBusy) return;
    setWinterAnswerBusy(true);
    setWinterAnswerNote("");
    try {
      await winterCheckOk(bookingId);
      forgetWinterCheck(WINTER_ASKED_KEY(bookingId));
      setWinterAskForId(null);
    } catch {
      setWinterAskForId(bookingId);
      setWinterAnswerNote(
        appText(
          "Ответ не отправился. Проверь связь и попробуй ещё раз.",
          "Яуап ебәрелмәне. Бәйләнеште тикшереп, тағы ҡабатла."
        )
      );
    } finally {
      setWinterAnswerBusy(false);
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
  const [rateNote, setRateNote] = useState("");

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
    setRateNote("");
    try {
      await rateBooking(bookingId, myStars, rateText.trim(), myTags);
      setRated(true);
    } catch {
      setRateNote(appText("Не получилось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер."));
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
        {/* Сервер не ответил — но если поездку уже открывали, снимок брони лежит рядом.
            Сверяют машину как раз там, где связи нет: перевал, ночная трасса, обочина
            у выезда из села. Пустой экран в этот момент — худшее, что можно показать. */}
        {pass && savedStage !== "terminal"
          ? <OfflineTripPass pass={savedStage === "onboard" ? { ...pass, boardingCode: "" } : pass} /> : null}
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
            <button type="button" className="btn-primary" onClick={winterAnswerOk} disabled={winterAnswerBusy}>
              <IconCheck size={17} /> {appText("Доехал ✓", "Барып еттем ✓")}
            </button>
            <button
              type="button"
              className="btn-ghost"
              onClick={() => setWinterAskForId(null)}
              disabled={winterAnswerBusy}
            >
              {appText("Ещё в пути", "Әле юлда")}
            </button>
          </div>
          {winterAnswerNote && (
            <div className="notice" role="status">
              {winterAnswerNote}
            </div>
          )}
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
      {needsBoardingCode && code && (
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

      {/* Посадку отмечает водитель: пассажиру — «Водитель отметит посадку» и разбор, если что не так.
          «В пути» разбор не пропадает: отмены у пассажира нет, а не садился — путь только этот (PR174). */}
      {details.role === "passenger" && (st === "confirmed" || st === "onboard") && (
        <BoardingWait
          bookingId={bookingId}
          driverId={trip?.peer_id}
          driverName={details.driver_name}
          stage={st === "onboard" ? "marked" : "waiting"}
        />
      )}

      {/* Оплата (read-only) + контакт */}
      <div className="info-list">
        {details.front_seat && (
          <div className="info-row">
            <span className="info-row__k">{appText("Место", "Урын")}</span>
            <span className="info-row__v">
              {details.role === "driver"
                ? appText("Пассажир просит переднее место", "Юлсы алғы урынды һорай")
                : appText("Переднее место", "Алғы урын")}
            </span>
          </div>
        )}
        <div className="info-row">
          <span className="info-row__k">{appText("Оплата", "Түләү")}</span>
          <span className="info-row__v">
            {payMethodLabel(details.pay_method, ru)}
            {" · "}
            {priceLabel(details.pay_amount ?? details.price, ru)}
          </span>
        </div>
        {details.role === "passenger" && details.contact_unlocked && details.driver_phone && (
          <a className="info-row info-row--link" href={`tel:${details.driver_phone}`}>
            <span className="info-row__k">{appText("Водитель", "Йөрөтөүсе")}</span>
            <span className="info-row__v" style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
              <IconPhone size={16} /> {appText("Позвонить", "Шылтыратыу")}
            </span>
          </a>
        )}
        {/* Едет подросток. Водителю это нужно знать ДО посадки, а не когда тот сядет
            в машину: у него другой разговор с ребёнком и другая ответственность.
            Телефон взрослого сервер отдаёт только водителю этой брони и только пока
            бронь жива — отменили, и номер закрывается. */}
        {details.minor_passenger && (
          <div className="info-row">
            <span className="info-row__k">{appText("Пассажир", "Юлсы")}</span>
            <span className="info-row__v">
              {appText("младше 18, со взрослым на связи", "18-ҙән кесе, оло кеше бәйләнештә")}
            </span>
          </div>
        )}
        {details.minor_passenger && details.minor_guardian_phone && (
          <a className="info-row info-row--link" href={`tel:${details.minor_guardian_phone}`}>
            <span className="info-row__k">{appText("Взрослый", "Оло кеше")}</span>
            <span className="info-row__v" style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
              {details.minor_guardian_name || appText("Позвонить", "Шылтыратыу")}
              <IconPhone size={16} />
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

      {/* Чат по поездке — отдельная страница, как в мессенджерах (строка ввода внизу экрана) */}
      <TripChatCard
        bookingId={bookingId}
        myId={user?.id ?? -1}
        onOpen={() => navigate(`/trip/${bookingId}/chat`, { state: { fromTrip: true } })}
      />

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
                onClick={() => setPayDraft((current) => ({ ...current, payMethod: m, dirty: true }))}
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
              onChange={(e) => {
                const value = e.target.value;
                setPayDraft((current) => ({ ...current, payAmount: value, dirty: true }));
              }}
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
              {appText("Подъезжаю", "Яҡынлашам")}
            </button>
            {/* «Пассажир сел» → «Пассажир в машине» и «Отменить» в первые 5 минут. */}
            <DriverBoarding
              bookingId={bookingId}
              status={st ?? ""}
              undoSec={trip?.boarding_undo_sec ?? 0}
              variant="chip"
              onChanged={applyBoarding}
              onError={setPhaseNote}
            >
              {/* Окно «Отменить» вышло — разбор, как в кабинете (Q4, приёмка PR174). */}
              <DriverBoardedDispute bookingId={bookingId} passengerId={trip?.peer_id} onError={setPhaseNote} />
            </DriverBoarding>
            <button
              type="button"
              className="chip"
              onClick={() => setConfirmFinish(true)}
              disabled={phaseBusy}
            >
              {appText("Завершить поездку", "Сәфәрҙе тамамлау")}
            </button>
          </div>
          {phaseNote && <p className="demand__quiet">{phaseNote}</p>}
          {/* Точка для пассажира: до «Выехал» — зачем, в пути — отправка и её состояние. */}
          <DriverLocationShare bookingId={bookingId} inTransit={driverInTransit(details.role, st, trip?.driver_phase)} />
        </div>
      )}

      {confirmFinish && (
        <AlertDialog
          title={appText("Завершить поездку?", "Сәфәрҙе тамамларғамы?")}
          text={appText("Подтверди, что поездка действительно закончилась.", "Сәфәр ысынлап та тамамланғанын раҫла.")}
          onClose={() => setConfirmFinish(false)}
          confirm={{
            label: appText("Да, завершить", "Эйе, тамамларға"),
            tone: "green",
            onClick: () => {
              setConfirmFinish(false);
              void sendPhase("done");
            },
          }}
          dismiss={{ label: appText("Не завершать", "Тамамламаҫҡа"), tone: "muted", onClick: () => setConfirmFinish(false) }}
        />
      )}

      {/* Попутчики вышли — дальше человек едет вдвоём с водителем.
          Не тревога и не обвинение: тихая подсказка отправить близкому ссылку.
          Именно в этот момент SOS не нажимают — боятся «поднимать шум из-за слов». */}
      {active && details.role === "passenger" && trip?.alone_with_driver && (
        <div className="act-card act-card--mint">
          <div className="act-card__title">
            <IconCar size={18} />{" "}
            {appText("Попутчики вышли — дальше едешь одна(один)", "Юлдаштар төштө — артабан яңғыҙ бараһың")}
          </div>
          <p className="act-card__text" style={{ marginBottom: 0 }}>
            {appText(
              "Можно отправить близкому ссылку — он будет видеть, где ты едешь.",
              "Яҡыныңа һылтанма ебәрергә була — ул ҡайҙа барғаныңды күреп торор."
            )}
          </p>
        </div>
      )}

      {/* Поделиться поездкой с близким + статусы (у пассажира) */}
      {active && details.role === "passenger" && (
        <ShareTripCard bookingId={bookingId} showStatuses bookingStatus={st} onStatusChange={tickTrip} />
      )}

      {/* Отменить подтверждённую поездку — только пассажиру и только до посадки (Android:
          bookingStatus == "confirmed"). «В пути» отмены нет: человек уже в машине. */}
      {details.role === "passenger" && st === "confirmed" && (
        <div className="trip-cancel">
          <button
            type="button"
            className="trip-cancel__btn"
            onClick={() => {
              setCancelReason("");
              setCancelOpen(true);
            }}
            disabled={cancelBusy}
          >
            <IconClose size={18} /> {appText("Отменить поездку", "Сәфәрҙе кире алыу")}
          </button>
          <p className="trip-cancel__note">
            {appText(
              "Отмена бесплатна до начала поездки — место вернётся в поездку.",
              "Сәфәр башланғанға тиклем кире алыу бушлай — урын кире ҡайта."
            )}
          </p>
        </div>
      )}
      {cancelNote && (
        <div className="notice" role="status">
          {cancelNote}
        </div>
      )}
      {cancelOpen && (
        <AlertDialog
          title={appText("Отменить поездку?", "Сәфәрҙе кире аларғамы?")}
          text={appText("Бронь будет отменена, место освободится для других.", "Брон кире алына, урын башҡаларға бушай.")}
          onClose={() => {
            if (!cancelBusy) setCancelOpen(false);
          }}
          confirm={{ label: appText("Да, отменить", "Эйе, кире алырға"), tone: "danger", busy: cancelBusy, onClick: () => void cancelTrip() }}
          dismiss={{ label: appText("Назад", "Артҡа"), onClick: () => setCancelOpen(false), disabled: cancelBusy }}
        >
          <div className="adlg__reasons" role="group" aria-label={appText("Причина (по желанию):", "Сәбәбе (теләгәнсә):")}>
            <p className="adlg__reasons-label">{appText("Причина (по желанию):", "Сәбәбе (теләгәнсә):")}</p>
            {CANCEL_REASONS.map((r) => {
              const on = cancelReason === r.code;
              return (
                <button
                  key={r.code}
                  type="button"
                  className={"adlg__reason" + (on ? " is-on" : "")}
                  aria-pressed={on}
                  disabled={cancelBusy}
                  onClick={() => setCancelReason(on ? "" : r.code)}
                >
                  <span>{appText(r.ru, r.ba)}</span>
                  {on && <IconCheck size={18} />}
                </button>
              );
            })}
          </div>
        </AlertDialog>
      )}

      {/* «Застряли» — ступень мягче SOS: машина не едет, но никто не в опасности.
          Зимой между сёлами это самая частая беда, а красную кнопку в такой ситуации
          люди жать стесняются — и не зовут никого вообще. */}
      {active && <RoadsideHelp target={{ kind: "booking", id: bookingId }} />}

      {/* SOS — ведёт на настоящий экран помощи. Бронь едет в адресе: по ней дежурный
          узнаёт, в какой машине человек (без неё сигнал «человеку плохо» без адреса). */}
      {active && (
        <button type="button" className="sos-btn" onClick={() => navigate(sosPath(bookingId))}>
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
              {rateNote && <div className="consents__status error" role="alert">{rateNote}</div>}
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
      {/* После поездки — «Сообщить о проблеме», пока сервер держит окно (6 часов, приёмка PR174):
          водитель мог отметить не того и сразу закрыть поездку. */}
      {st === "done" && details.role === "passenger" && (trip?.problem_report_sec ?? 0) > 0 && (
        <BoardingWait bookingId={bookingId} driverId={trip?.peer_id} driverName={details.driver_name} stage="after" />
      )}

    </>
  );
}
