// ================================================================
//  Такси, водитель «на линии». RequireAuth → /taxi-drive
//  (зеркало backend routers/instant.py: presence, offer, accept/
//  arrived/onboard/done + taxi.py gate).
//
//  Вид — зеркало Android: пока тумблер выключен, это часть кабинета водителя
//  (тумблер «Я на линии», дашборд смены, зона); на линии — полноэкранная карта
//  со шторкой InstantDriverWaitingScreen; оффер — InstantOfferOverlay; поездка —
//  экран «Поездка» с картой сверху, после посадки — навигатор TaxiDriverOnboardNavigator;
//  финал — TaxiDriverCompletedScreen / TaxiDriverCancelledScreen.
//
//  • Гейт: одобренная заявка таксиста (580-ФЗ) — иначе → онбординг.
//  • Тумблер «Я на линии» (POST /driver/online).
//  • Пока на линии: presence-heartbeat (геолокация) + опрос оффера + спрос раз в минуту.
//  • Оффер: таймер по серверному дедлайну, «Принять»/«Пропустить» (на вебе — звук/вибро вместо звонка).
//  • Активная поездка: «Я на месте» → «Пассажир сел» → «Завершить», телефон и чат после accept.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchDriverStatus, setDriverOnline, type DriverStatus } from "../api/driver";
import { commissionLabel } from "../utils/commissionLabel";
import {
  fetchTaxiApplication,
  sendPresence,
  fetchDriverOffer,
  fetchInstantOrder,
  acceptOrder,
  declineOrder,
  DECLINE_REASONS,
  type DeclineReason,
  arrivedOrder,
  onboardOrder,
  doneOrder,
  cancelInstantOrder,
  fetchDemand,
  fetchPretrip,
  fetchWorkday,
  fetchWorkZone,
  type InstantOrder,
  type DemandZone,
  type PretripState,
  type Workday,
  type WorkZone,
} from "../api/instant";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList } from "../components/States";
import YandexMap, { type GeoPoint } from "../components/YandexMap";
import TaxiSheet, { type TaxiSheetStop } from "../components/TaxiSheet";
import RouteTimeline from "../components/RouteTimeline";
import TaxiTripProgress from "../components/TaxiTripProgress";
import AlertDialog from "../components/AlertDialog";
import { SettingsGroup, SettingSwitchRow } from "../components/cabinetUi";
import {
  IconCar,
  IconStar,
  IconPhone,
  IconChat,
  IconWarn,
  IconProfile,
  IconShield,
  IconClock,
  IconClose,
  IconCloudOff,
  IconGift,
  IconLocate,
  IconMap,
  IconPin,
  IconSearch,
} from "../components/Icons";
import { YuMoon, YuRoute } from "../components/BrandIcons";
import { formatWhen, kopExactLabel, pluralRu } from "../utils/format";
import DebtCard from "../components/DebtCard";
import WorkZoneCard from "../components/WorkZoneCard";
import TaxiDriverTripActions from "../components/TaxiDriverTripActions";
import { serverMs } from "../utils/serverTime";
import { track } from "../analytics";
import {
  canRestoreDriverOrder,
  clearStoredDriverOrder,
  isCurrentDriverOrderPoll,
  isReleasedDriverOrderStatus,
} from "../utils/driverActiveOrder";
import { DriverOfferExpiry } from "../utils/driverOfferExpiry";
import { openNavigator } from "../utils/navigator";
import TaxiDriverCompleted, { passengerPayKop } from "./TaxiDriverCompletedScreen";
import TaxiDriverCancelled, { PayMethodIcon } from "./TaxiDriverCancelledScreen";
import TaxiDriverOnboardNavigator from "./TaxiDriverNavigationScreen";

type Boot = "loading" | "error" | "need-approval" | "ready";
const PRESENCE_MS = 15000;
const OFFER_POLL_MS = 3000;
const DEMAND_MS = 60000; // карта спроса — авто-обновление раз в минуту на линии
const ACTIVE_KEY = "yuldash.taxi.activeOrder";

/** DriverBlockedStrip: коды приходят с сервера, подписи живут здесь — язык переключается кнопкой. */
const BLOCKED_COPY: Record<string, { title: [string, string]; detail: [string, string] }> = {
  debt: {
    title: ["Линия закрыта из-за долга по комиссии", "Комиссия бурысы арҡаһында линия ябыҡ"],
    detail: ["Оплати в кабинете — вернёшься сразу", "Кабинетта түлә — шунда уҡ ҡайтаһың"],
  },
  rest: {
    title: ["Сейчас время отдыха", "Хәҙер ял ваҡыты"],
    detail: ["Линия откроется, когда отдых закончится", "Ял бөткәс линия асыла"],
  },
  quality_pause: {
    title: ["Такси на паузе по жалобам", "Ялыуҙар буйынса такси паузала"],
    detail: ["Подробности — в Центре справедливости", "Ентеклеләр — Ғәҙеллек үҙәгендә"],
  },
  review_pause: {
    title: ["Идёт разбор — такси на паузе", "Тикшереү бара — такси паузала"],
    detail: ["Ответим, как только разберём", "Тикшереп бөткәс яуап бирербеҙ"],
  },
};

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

/** «1,5» — километры с запятой (formatOfferKm). */
function km1(value: number): string {
  return value.toFixed(1).replace(".", ",");
}
/** «2 ч 15 мин» / «2 сәғ 15 мин» (instantWaitingDurationRu/Ba). */
function durationLabel(seconds: number, ru: boolean): string {
  const safe = Math.max(0, seconds);
  const h = Math.floor(safe / 3600);
  const m = Math.floor((safe % 3600) / 60);
  return ru ? `${h} ч ${m} мин` : `${h} сәғ ${m} мин`;
}
/** Полное название способа расчёта (PayMethods.title): знает и карту, и счёт. */
function payMethodTitle(method: string | undefined, appText: (ru: string, ba: string) => string): string {
  switch (method) {
    case "cash":
      return appText("Наличными", "Наличный менән");
    case "sbp":
      return appText("Переводом по СБП", "СБП аша күсереү");
    case "card":
      return appText("Картой в приложении", "Ҡушымтала карта менән");
    case "corporate":
      return appText("Корпоративный счёт", "Корпоратив иҫәп");
    default:
      return appText("Договоримся на месте", "Урында килешәбеҙ");
  }
}
function categoryTitle(cat: string, appText: (ru: string, ba: string) => string): string {
  switch (cat) {
    case "comfort":
      return appText("Комфорт", "Комфорт");
    case "business":
      return appText("Бизнес", "Бизнес");
    case "minivan":
      return appText("Минивэн", "Минивэн");
    default:
      return appText("Эконом", "Эконом");
  }
}
/** Тикающее «сейчас» раз в секунду — для живых таймеров (rememberNowMs). */
function useNowMs(active = true): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    setNow(Date.now());
    const iv = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(iv);
  }, [active]);
  return now;
}

export default function InstantDriverTripScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const [boot, setBoot] = useState<Boot>("loading");
  const [driver, setDriver] = useState<DriverStatus | null>(null);
  const [onlineBusy, setOnlineBusy] = useState(false);
  // Предрейсовая готовность на сегодня (580-ФЗ). null = сервер о ней не знает → блок скрыт.
  const [pretrip, setPretrip] = useState<PretripState | null>(null);
  // Смена: сколько на линии и сколько осталось до обязательного отдыха. null = сервер не знает.
  const [workday, setWorkday] = useState<Workday | null>(null);
  // Зона работы — в шапке шторки ожидания и в строке «Рабочая зона». null = не задана.
  const [zone, setZone] = useState<WorkZone | null>(null);

  const [offer, setOffer] = useState<InstantOrder | null>(null);
  /** Заказ берётся прямо сейчас — второй тап не должен слать второй запрос. */
  const [accepting, setAccepting] = useState(false);
  /** Почему не взяли: «уже взял другой» либо «проверь связь». Молчать тут нельзя. */
  const [acceptNote, setAcceptNote] = useState("");
  const [active, setActive] = useState<InstantOrder | null>(null);
  const activeIdRef = useRef<number | null>(null);
  /** Почему прежний заказ исчез: сообщение остаётся на экране ожидания следующего. */
  const [endedNote, setEndedNote] = useState("");
  /** Действие по активной поездке не прошло — сказать словами, а не ждать поллинга. */
  const [tripNote, setTripNote] = useState("");
  /** Кнопка фазы уже отправлена — вторая отправка не нужна. */
  const [tripBusy, setTripBusy] = useState(false);
  const posRef = useRef<GeoPoint | null>(null);
  /** Позиция и в state: карта ожидания и навигатор перерисовываются по ней. */
  const [pos, setPos] = useState<GeoPoint | null>(null);
  /** Геолокацию не дали — «на линии» работать не будет. */
  const [geoNote, setGeoNote] = useState(false);
  /** Два подряд недошедших heartbeat: сервер уже может не видеть водителя. */
  const [presenceFails, setPresenceFails] = useState(0);
  /** Серверная причина, по которой офферов не будет, хотя тумблер включён. */
  const [offerBlocked, setOfferBlocked] = useState<string | null>(null);
  // Спрос рядом: агрегированные круги районов, без адресов и людей. Старые данные при сбое не стираем.
  const [demandZones, setDemandZones] = useState<DemandZone[]>([]);
  const [demandLoading, setDemandLoading] = useState(false);
  const [demandError, setDemandError] = useState(false);

  const setCurrentActive = useCallback((order: InstantOrder | null) => {
    activeIdRef.current = order?.id ?? null;
    setActive(order);
  }, []);

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
                if (!canRestoreDriverOrder(activeIdRef.current, saved)) return;
                if (["accepted", "arriving", "onboard"].includes(o.status)) setCurrentActive(o);
                else clearStoredDriverOrder(localStorage, ACTIVE_KEY, saved);
              })
              .catch(() => clearStoredDriverOrder(localStorage, ACTIVE_KEY, saved));
          }
        });
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 404 = заявка не подана → онбординг; иначе ошибка.
        if (e instanceof ApiError && e.status === 404) setBoot("need-approval");
        else setBoot("error");
      });
  }, [setCurrentActive]);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Готовность на сегодня, смена и зона — отдельными запросами: их отсутствие не должно ломать экран.
  useEffect(() => {
    if (boot !== "ready") return;
    const ac = new AbortController();
    fetchPretrip(ac.signal)
      .then(setPretrip)
      .catch(() => setPretrip(null)); // 404/403 → блок скрыт
    fetchWorkday(ac.signal)
      .then(setWorkday)
      .catch(() => setWorkday(null));
    fetchWorkZone(ac.signal)
      .then(setZone)
      .catch(() => setZone(null));
    return () => ac.abort();
  }, [boot]);

  const online = !!driver?.online;

  // ---------------- Геолокация (watch) — держим последнюю позицию ----------------
  useEffect(() => {
    if (!online || !navigator.geolocation) return;
    const id = navigator.geolocation.watchPosition(
      (p) => {
        posRef.current = { lat: p.coords.latitude, lng: p.coords.longitude };
        setPos(posRef.current);
        setGeoNote(false);
      },
      // Без места сервер водителя не видит: он «на линии», а заказы не приходят,
      // и понять причину невозможно. Молчать тут нельзя.
      () => setGeoNote(true),
      { enableHighAccuracy: true, maximumAge: 10000, timeout: 12000 }
    );
    return () => navigator.geolocation.clearWatch(id);
  }, [online]);

  // ---------------- Presence-heartbeat пока на линии ----------------
  useEffect(() => {
    if (!online) {
      setPresenceFails(0);
      return;
    }
    let alive = true;
    const beat = () => {
      const pos = posRef.current;
      if (!pos) return;
      sendPresence(pos.lat, pos.lng)
        .then((r) => {
          if (!alive) return;
          setPresenceFails(0);
          // Смена тикает на сервере — подтягиваем её в шапку без отдельного запроса.
          setWorkday((wd) =>
            wd ? { ...wd, seconds_online: r.shift_seconds_online, remaining_sec: r.shift_remaining_sec } : wd
          );
        })
        .catch((e) => {
          // 403 = гейт → выключаем. Остальные сбои считаем: молчащий heartbeat
          // означает, что сервер уже не видит машину и не пришлёт заказ.
          if (!alive) return;
          if (e instanceof ApiError && e.status === 403) {
            setPresenceFails(0);
            setDriver((d) => (d ? { ...d, online: false } : d));
          } else {
            setPresenceFails((count) => Math.min(99, count + 1));
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
    if (!online || active) {
      setOfferBlocked(null);
      return;
    }
    let alive = true;
    const tick = () => {
      fetchDriverOffer()
        .then((r) => {
          if (!alive) return;
          setOfferBlocked(r.blocked ?? null);
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

  // ---------------- Спрос рядом: раз в минуту, пока на линии без поездки ----------------
  useEffect(() => {
    if (!online || active) {
      setDemandZones([]);
      setDemandLoading(false);
      setDemandError(false);
      return;
    }
    let alive = true;
    let empty = true;
    const tick = () => {
      if (empty) setDemandLoading(true);
      fetchDemand()
        .then((d) => {
          if (!alive) return;
          empty = d.zones.length === 0;
          setDemandZones(d.zones);
          setDemandError(false);
        })
        .catch(() => {
          // 403 (не одобрен) / 404 (нет ручки) / сеть → старые данные остаются, пустые = ошибка.
          if (alive && empty) setDemandError(true);
        })
        .finally(() => alive && setDemandLoading(false));
    };
    tick();
    const iv = window.setInterval(tick, DEMAND_MS);
    return () => {
      alive = false;
      window.clearInterval(iv);
    };
  }, [online, active]);

  // ---------------- Поллинг активной поездки (статус пассажира: отмена и т.п.) ----------------
  useEffect(() => {
    if (!active) return;
    let alive = true;
    const orderId = active.id;
    const iv = window.setInterval(() => {
      fetchInstantOrder(orderId)
        .then((o) => {
          if (!alive || !isCurrentDriverOrderPoll(activeIdRef.current, orderId)) return;
          if (isReleasedDriverOrderStatus(o.status)) {
            clearStoredDriverOrder(localStorage, ACTIVE_KEY, orderId);
            setTripNote("");
            setEndedNote(
              o.status === "cancelled"
                ? appText(
                    "Пассажир отменил заказ. Ищем следующий.",
                    "Пассажир заказды кире алды. Киләһе заказды эҙләйбеҙ."
                  )
                : appText(
                    "Заказ больше не активен. Ищем следующий.",
                    "Заказ инде әүҙем түгел. Киләһе заказды эҙләйбеҙ."
                  )
            );
            // Отмену показываем экраном (TaxiDriverCancelledScreen) — с маршрутом, фактами и
            // платой за подачу; «Вернуться на линию» там же. Истёкший заказ просто отпускаем.
            if (o.status === "cancelled") setCurrentActive(o);
            else setCurrentActive(null);
            return;
          }
          setCurrentActive(o);
          if (o.status === "done") {
            clearStoredDriverOrder(localStorage, ACTIVE_KEY, orderId);
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

  async function accept(): Promise<"accepted" | "retry" | "closed"> {
    if (!offer || accepting) return "closed"; // второй тап на медленной сети — не второй заказ
    setAcceptNote("");
    setEndedNote("");
    setAccepting(true);
    try {
      const o = await acceptOrder(offer.id);
      setCurrentActive(o);
      setOffer(null);
      localStorage.setItem(ACTIVE_KEY, String(o.id));
      return "accepted";
    } catch (e) {
      const st = e instanceof ApiError ? e.status : -1;
      if (st === 409 || st === 410) {
        // Гонку проиграли: заказ уже у другого. Говорим об этом и ждём следующий.
        setOffer(null);
        setAcceptNote(appText("Заказ уже взял другой водитель", "Заказды башҡа йөрөтөүсе алды"));
        return "closed";
      } else {
        // Связь оборвалась. Оффер НЕ убираем: раньше он молча исчезал, и водитель
        // не знал, взял он заказ или нет — а пассажир ждал машину, которая не едет.
        setAcceptNote(
          appText(
            "Не удалось взять заказ. Проверь связь и попробуй снова.",
            "Заказды алып булманы. Бәйләнеште тикшереп ҡабатла."
          )
        );
        return "retry";
      }
    } finally {
      setAccepting(false);
    }
  }

  /**
   * «Почему не взял?» — вопрос ПОСЛЕ отказа, а не вместо него.
   *
   * У оффера тикает обратный отсчёт: пока водитель за рулём выбирает причину,
   * пассажир ждёт машину, которая уже не приедет. Поэтому сначала отказываем,
   * и только потом спрашиваем — ответ необязателен.
   *
   * Без причин платформа видит только «не берут» и продолжает слать те же
   * заказы тем же людям.
   */
  const [askWhy, setAskWhy] = useState<number | null>(null);

  async function skip(afterFailedAccept = false) {
    if (!offer || (accepting && !afterFailedAccept)) return;
    const id = offer.id;
    setAcceptNote("");
    setOffer(null);
    try {
      await declineOrder(id);
      // Дедлайн истёк сам — спрашивать нечего: водитель ничего не решал.
      if (!afterFailedAccept) setAskWhy(id);
    } catch {
      /* уже ушёл дальше */
    }
  }

  async function sendWhy(reason: DeclineReason) {
    const id = askWhy;
    setAskWhy(null);
    if (!id) return;
    try {
      await declineOrder(id, reason);
    } catch {
      /* причина — не критично: отказ уже прошёл */
    }
  }

  // ---------------- Активная поездка ----------------
  /**
   * Перечитать поездку сразу после действия, не дожидаясь круга поллинга.
   *
   * Нужно после ответа на смену адреса и после «Стоим»: водитель нажал — и должен
   * увидеть новое состояние, а не гадать, прошло ли. Ошибку глотаем: поездка жива,
   * следующий круг поллинга подтянет её сам.
   */
  function refreshActive() {
    if (!active) return;
    const orderId = active.id;
    fetchInstantOrder(orderId)
      .then((o) => {
        if (isCurrentDriverOrderPoll(activeIdRef.current, orderId)) setCurrentActive(o);
      })
      .catch(() => {});
  }

  async function advance(next: "arrived" | "onboard" | "done") {
    if (!active || tripBusy) return;
    setTripBusy(true);
    try {
      const fn = next === "arrived" ? arrivedOrder : next === "onboard" ? onboardOrder : doneOrder;
      const o = await fn(active.id);
      setCurrentActive(o);
      setTripNote("");
      // «Готово» — экран финала (доход, оценка пассажира); чек — кнопкой с него.
      if (o.status === "done") clearStoredDriverOrder(localStorage, ACTIVE_KEY, o.id);
    } catch (e) {
      // Молчать нельзя, особенно на «Завершить»: водитель уверен, что закрыл
      // поездку, убирает телефон и уезжает — а заказ висит открытым.
      setTripNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText(
              "Не получилось обновить поездку. Проверь сеть и повтори.",
              "Сәфәрҙе яңыртып булманы. Селтәрҙе тикшереп ҡабатла."
            )
      );
    } finally {
      setTripBusy(false);
    }
  }

  async function cancelActive(reason: "driver_cancel" | "no_show" = "driver_cancel") {
    if (!active || tripBusy) return;
    setTripNote("");
    setTripBusy(true);
    try {
      const o = await cancelInstantOrder(active.id, reason);
      track("instant_order_cancel");
      clearStoredDriverOrder(localStorage, ACTIVE_KEY, active.id);
      // Заказ закрыт (не вышел / пассажир уже в машине) — экран «Заказ отменён», как в приложении.
      // До посадки сервер отдаёт заказ следующему водителю и возвращает его в поиске — нам он
      // больше не принадлежит, возвращаемся на линию.
      if (o.status === "cancelled") setCurrentActive(o);
      else setCurrentActive(null);
    } catch (e) {
      // Обрыв связи: выходить НЕЛЬЗЯ. Раньше экран закрывался в любом случае —
      // заказ оставался живым, а пассажир ждал машину, которая не приедет.
      setTripNote(
        e instanceof ApiError && e.status === 0
          ? appText(
              "Не получилось отменить — нет связи. Поездка ещё активна, попробуй ещё раз.",
              "Кире алып булманы — бәйләнеш юҡ. Сәфәр әле әүҙем, тағы ҡабатла."
            )
          : e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось отменить. Проверь сеть и повтори.", "Кире алып булманы. Селтәрҙе тикшереп ҡабатла.")
      );
    } finally {
      setTripBusy(false);
    }
  }

  /** Финал закрыт: «Вернуться на линию» — экран ожидания, тумблер как был. */
  function releaseActive() {
    if (active) clearStoredDriverOrder(localStorage, ACTIVE_KEY, active.id);
    setCurrentActive(null);
    setTripNote("");
    setEndedNote(""); // отмену человек уже видел экраном — повторять её строкой незачем
  }
  /** «Завершить смену» на финале: сервер уже выключил линию — отражаем тумблер. */
  function shiftFinished() {
    releaseActive();
    setDriver((d) => (d ? { ...d, online: false } : d));
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
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
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
          <div className="state__icon"><IconCar size={34} /></div>
          <h2>{appText("Сначала стань таксистом", "Башта таксист бул")}</h2>
          <p>
            {appText(
              "Чтобы возить пассажиров такси, нужна одобренная заявка (по 580-ФЗ). Это займёт пару минут.",
              "Такси юлаусыларын йөрөтөр өсөн хупланған ғариза кәрәк (580-ФЗ буйынса). Был бер-ике минут."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/taxi-onboarding")}>
            {appText("Стать таксистом Юлдаша", "Юлдаш таксисы булыу")}
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
        pos={pos}
        note={tripNote}
        busy={tripBusy}
        onAdvance={advance}
        onCancel={() => void cancelActive("driver_cancel")}
        onNoShow={() => void cancelActive("no_show")}
        onRefresh={refreshActive}
        onChat={() => navigate(`/taxi-chat/${active.id}`)}
        onReturnToLine={releaseActive}
        onShiftFinished={shiftFinished}
      />
    );
  }

  // На линии без поездки — полноэкранная карта со шторкой (InstantDriverWaitingScreen).
  if (online) {
    return (
      <>
        <DriverWaiting
          pos={pos}
          geoNote={geoNote}
          demandZones={demandZones}
          demandLoading={demandLoading}
          demandError={demandError}
          workday={workday}
          zone={zone}
          connectionLost={presenceFails >= 2}
          blockedReason={offerBlocked}
          endedNote={endedNote}
          acceptNote={!offer ? acceptNote : ""}
          onGoOffline={() => void toggleOnline()}
          declinePanel={
            <DeclineReasonPanel
              orderId={!offer ? askWhy : null}
              onPick={(reason) => void sendWhy(reason)}
              onDismiss={() => setAskWhy(null)}
            />
          }
        />
        {/* Оффер — поверх всего: маршрут первым слоем решения, шторка с деньгами и кнопками. */}
        {offer && (
          <OfferOverlay
            order={offer}
            accepting={accepting}
            note={acceptNote}
            onAccept={accept}
            onSkip={skip}
          />
        )}
      </>
    );
  }

  // Тумблер выключен — часть кабинета водителя: готовность, тумблер, зона, смена.
  return (
    <>
      <SubHeader title={appText("Я на линии (такси)", "Мин линияла (такси)")} onBack={() => navigate(-1)} />

      {endedNote && (
        <div className="notice" role="status">
          {endedNote}
        </div>
      )}

      {/* Готовность на сегодня. Показываем ДО тумблера: это про «можно ли вообще ехать». */}
      {pretrip && !pretrip.confirmed && (
        <div className={"act-card " + (pretrip.required ? "act-card--warn" : "act-card--mint")}>
          <div className="act-card__title">
            <IconShield size={18} /> {appText("Готовность к работе", "Эшкә әҙерлек")}
          </div>
          <p className="act-card__text">
            {pretrip.required
              ? appText(
                  "Отметь готовность на сегодня — самочувствие, машина, без алкоголя.",
                  "Бөгөнгә әҙерлекте билдәлә — үҙ хәлең, машина, эсемлекһеҙ."
                )
              : appText(
                  "Можешь отметить готовность на сегодня — это остаётся твоим следом.",
                  "Бөгөнгә әҙерлекте билдәләй алаһың — был һинең эҙең булып ҡала."
                )}
          </p>
          <button
            type="button"
            className={pretrip.required ? "btn-primary" : "btn-soft"}
            style={{ width: "100%" }}
            onClick={() => navigate("/pretrip")}
          >
            {appText("Отметить готовность", "Әҙерлекте билдәләү")}
          </button>
        </div>
      )}

      {/* Долг по комиссии: пока он висит, такси заблокировано. Показываем до
          тумблера «на линии» — иначе водитель жмёт его и не понимает, почему тихо. */}
      <DebtCard />

      <div className="tdrive">
        {/* SettingSwitchRow «Я на линии» — как в кабинете приложения. */}
        <SettingsGroup>
          <SettingSwitchRow
            icon={<IconCar size={24} />}
            title={appText("Я на линии", "Мин эштә")}
            subtitle={appText("Пассажиры видят, что ты сейчас на линии", "Пассажирҙар һинең линияла икәнеңде күрә")}
            checked={online}
            onChange={() => void toggleOnline()}
            disabled={onlineBusy || Boolean(workday?.blocked)}
          />
        </SettingsGroup>

        {/* Где брать заказы. Без зоны они сыплются отовсюду, и человек читает
            каждый вручную — именно это и выжигает водителей. */}
        <WorkZoneCard />

        {/* Смена и деньги за день: дашборд, отдых или прогресс к лимиту — как TaxiDashboardCard /
            TaxiRestCard / TaxiShiftProgressCard в кабинете приложения. */}
        {workday && (workday.seconds_online > 0 || workday.orders_today > 0) && <TaxiDashboardCard wd={workday} />}
        {workday && workday.blocked ? (
          <TaxiRestCard wd={workday} ru={ru} onCreateRide={() => navigate("/create-ride")} />
        ) : workday && workday.week_blocked ? (
          <TaxiWeekRestCard wd={workday} ru={ru} onCreateRide={() => navigate("/create-ride")} />
        ) : workday && workday.seconds_online > 0 ? (
          <TaxiShiftProgressCard wd={workday} ru={ru} />
        ) : null}

        {!workday?.blocked && !workday?.week_blocked && (
          <div className="state tdrive__off">
            <div className="state__icon"><YuMoon size={34} /></div>
            <h2>{appText("Ты не на линии", "Һин линияла түгел")}</h2>
            <p>
              {appText(
                "Включи «Я на линии» — и начнём подбирать заказы поблизости.",
                "«Мин линияла»-ны ҡабыҙ — яҡындағы заказдарҙы табабыҙ."
              )}
            </p>
          </div>
        )}
      </div>
    </>
  );
}

// ----------------------------- Ожидание заказа: карта + шторка -----------------------------
/**
 * InstantDriverWaitingScreen: C = карта и короткая сводка (peek), A = подробности после свайпа
 * вверх (half). Поверх карты — пилюля «На линии · ищем заказ» и «вернуться к себе».
 */
function DriverWaiting({
  pos,
  geoNote,
  demandZones,
  demandLoading,
  demandError,
  workday,
  zone,
  connectionLost,
  blockedReason,
  endedNote,
  acceptNote,
  onGoOffline,
  declinePanel,
}: {
  pos: GeoPoint | null;
  geoNote: boolean;
  demandZones: DemandZone[];
  demandLoading: boolean;
  demandError: boolean;
  workday: Workday | null;
  zone: WorkZone | null;
  connectionLost: boolean;
  blockedReason: string | null;
  endedNote: string;
  acceptNote: string;
  onGoOffline: () => void;
  declinePanel: React.ReactNode;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [stop, setStop] = useState<TaxiSheetStop>("peek");
  const [recenterTick, setRecenterTick] = useState(0);

  const zoneName = zone?.work_district?.trim() || zone?.work_city?.trim() || appText("Рядом", "Яҡында");
  const income = workday ? kopExactLabel(Math.max(0, workday.net_today_kop)) : "—";
  const trips = workday?.orders_today;
  const todayLine =
    trips == null
      ? appText("Сегодня —", "Бөгөн —")
      : appText(`Сегодня ${income} · ${trips} ${pluralRu(trips, "поездка", "поездки", "поездок")}`, `Бөгөн ${income} · ${trips} сәфәр`);
  const shiftLine = workday
    ? appText(
        `Смена ${durationLabel(workday.seconds_online, true)} · отдых через ${durationLabel(workday.remaining_sec, true)}`,
        `Смена ${durationLabel(workday.seconds_online, false)} · ялға тиклем ${durationLabel(workday.remaining_sec, false)}`
      )
    : appText("Загружаем смену…", "Сменаны йөкләйбеҙ…");

  const problem = connectionLost || demandError;
  const requests = demandZones.reduce((sum, z) => sum + z.requests, 0);
  const demandHead = connectionLost
    ? appText("Нет связи", "Бәйләнеш юҡ")
    : demandLoading
      ? appText("Обновляем спрос рядом", "Яҡындағы ихтыяжды яңыртабыҙ")
      : demandError
        ? appText("Спрос пока не обновился", "Ихтыяж әлегә яңырманы")
        : demandZones.length === 0
          ? appText("Пока тихо рядом", "Яҡында әлегә тыныс")
          : appText("Спрос выше рядом", "Яҡында ихтыяж юғары");
  const demandDetail = problem
    ? appText("Повторим автоматически", "Үҙебеҙ ҡабатлап ҡарарбыҙ")
    : demandLoading
      ? appText("Никого не показываем — только общие зоны", "Кешеләрҙе күрһәтмәйбеҙ — тик дөйөм зоналар")
      : demandZones.length === 0
        ? appText("Сообщим, когда появится заказ", "Заказ барлыҡҡа килһә хәбәр итербеҙ")
        : appText(`Активных зон: ${demandZones.length} · запросов: ${requests}`, `Әүҙем зоналар: ${demandZones.length} · һорауҙар: ${requests}`);

  const pillText = connectionLost
    ? appText("Нет связи · переподключаемся", "Бәйләнеш юҡ · ҡабат тоташабыҙ")
    : !pos
      ? appText("Уточняем геолокацию", "Геолокацияны асыҡлайбыҙ")
      : appText("На линии · ищем заказ", "Линияла · заказ эҙләйбеҙ");
  const blocked = blockedReason
    ? BLOCKED_COPY[blockedReason] ?? {
        title: ["Допуск к такси сейчас закрыт", "Такси рөхсәте хәҙер ябыҡ"] as [string, string],
        detail: ["Проверь документы и разрешение в кабинете", "Кабинетта документтарҙы һәм рөхсәтте ҡара"] as [string, string],
      }
    : null;

  return (
    <TaxiSheet
      stop={stop}
      // Здесь ровно два осмысленных состояния: компактное C и подробное A.
      onStopChange={(next) => setStop(next === "full" ? "half" : next)}
      halfBodyFraction={0.43}
      map={<YandexMap from={pos} to={null} me={null} height="100%" zones={demandZones} recenterTick={recenterTick} />}
      overlay={
        <>
          <div className={"tdw-pill" + (connectionLost || !pos ? " is-gold" : "")} role="status">
            <span className="tdw-pill__dot" aria-hidden><i /></span>
            <span>{pillText}</span>
          </div>
          <button
            type="button"
            className="taxi-sheet__overlay-btn taxi-sheet__overlay-btn--right tdw-recenter"
            onClick={() => setRecenterTick((n) => n + 1)}
            aria-label={appText("Вернуться к себе", "Үҙеңә ҡайтыу")}
          >
            <IconLocate size={21} />
          </button>
          {declinePanel}
        </>
      }
      header={
        stop === "peek" ? (
          <div className="tdw-head" key="compact">
            <h1>{todayLine}</h1>
            <p>{shiftLine}</p>
            <small>{appText(`Зона: ${zoneName}`, `Зона: ${zoneName}`)}</small>
          </div>
        ) : (
          <div className="tdw-head" key="detailed">
            <h1>{appText("Ищем заказ рядом", "Яҡында заказ эҙләйбеҙ")}</h1>
            <p>
              {connectionLost
                ? appText("Восстанавливаем связь с сервером", "Сервер менән бәйләнеште тергеҙәбеҙ")
                : appText("Можно заниматься своими делами — позовём", "Үҙ эшең менән бул — заказ килһә саҡырырбыҙ")}
            </p>
          </div>
        )
      }
      body={
        <>
          {/* Стоит ПЕРВОЙ: молчащий сервер — временно, а закрытая линия не рассосётся сама. */}
          {blocked && (
            <div className="tdw-blocked" role="status">
              <strong>{appText(blocked.title[0], blocked.title[1])}</strong>
              <span>{appText(blocked.detail[0], blocked.detail[1])}</span>
            </div>
          )}
          {/* Веб: без разрешения на геолокацию сервер водителя не видит — говорим прямо. */}
          {geoNote && (
            <div className="tdw-blocked tdw-blocked--warn" role="status">
              <strong>{appText("Не видим твоё место — заказы не придут", "Урыныңды күрмәйбеҙ — заказдар килмәйәсәк")}</strong>
              <span>{appText("Разреши геолокацию в настройках браузера", "Браузер көйләүҙәрендә геолокацияға рөхсәт бир")}</span>
            </div>
          )}
          {(endedNote || acceptNote) && (
            <p className="tdw-note" role="status">
              {acceptNote || endedNote}
            </p>
          )}
          <div className={"tdw-demand" + (problem ? " is-gold" : "")}>
            <span className="tdw-demand__icon" aria-hidden>{problem ? <IconCloudOff size={24} /> : <IconSearch size={24} />}</span>
            <span className="tdw-demand__text">
              <strong>{demandHead}</strong>
              <small>{demandDetail}</small>
            </span>
          </div>
          {workday ? (
            <div className="tdw-metrics">
              <Metric icon={<IconClock size={21} />} label={appText("Смена", "Смена")} value={durationLabel(workday.seconds_online, ru)} />
              <Metric icon={<IconShield size={21} />} label={appText("До отдыха", "Ялға тиклем")} value={durationLabel(workday.remaining_sec, ru)} />
              <Metric icon={<IconGift size={21} />} label={appText("Сегодня", "Бөгөн")} value={income} highlighted />
              <Metric icon={<IconCar size={21} />} label={appText("Поездок", "Сәфәр")} value={String(workday.orders_today)} />
            </div>
          ) : (
            <div className="tdw-skel" aria-hidden>
              <span className="skeleton" />
              <span className="skeleton" />
            </div>
          )}
          <div className="tdw-zone">
            <IconPin size={22} />
            <span className="tdw-zone__text">
              <small>{appText("Рабочая зона", "Эш зонаһы")}</small>
              <strong>{zoneName}</strong>
            </span>
          </div>
        </>
      }
      footer={
        <button type="button" className="btn-soft tdw__off" onClick={onGoOffline}>
          {appText("Уйти с линии", "Линиянан сығыу")}
        </button>
      }
    />
  );
}

/** InstantWaitingMetric: плитка 92, иконка 21, подпись micro, значение Bold; «Сегодня» — мятная. */
function Metric({ icon, label, value, highlighted = false }: { icon: React.ReactNode; label: string; value: string; highlighted?: boolean }) {
  return (
    <div className={"tdw-metric" + (highlighted ? " is-hi" : "")}>
      <span className="tdw-metric__icon" aria-hidden>{icon}</span>
      <span className="tdw-metric__text">
        <small>{label}</small>
        <strong>{value}</strong>
      </span>
    </div>
  );
}

// ----------------------------- «Почему не взял?» — панель снизу -----------------------------
/**
 * InstantDeclineReasonPanel: отказ уже ушёл, панель ничего не держит — не ответил за десять
 * секунд, она молча исчезла. Шесть причин в две колонки, ответ одним касанием.
 */
function DeclineReasonPanel({
  orderId,
  onPick,
  onDismiss,
}: {
  orderId: number | null;
  onPick: (reason: DeclineReason) => void;
  onDismiss: () => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [picked, setPicked] = useState<string | null>(null);
  useEffect(() => {
    if (orderId == null) return;
    setPicked(null);
    const t = window.setTimeout(onDismiss, 10_000); // вопрос без ответа не висит над кабинетом
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId]);
  if (orderId == null) return null;
  return (
    <section className="tdecl" aria-label={appText("Почему не взял?", "Ниңә алманың?")}>
      <div className="tdecl__head">
        <div className="tdecl__text">
          <strong>{appText("Почему не взял?", "Ниңә алманың?")}</strong>
          <span>
            {appText(
              "Заказ уже ушёл дальше. Ответ не обязателен — он помогает не слать тебе лишнее.",
              "Заказ артабан китте инде. Яуап мотлаҡ түгел — ул һиңә артыҡ заказ килмәһен өсөн."
            )}
          </span>
        </div>
        <button type="button" className="tdecl__close" onClick={onDismiss} aria-label={appText("Закрыть вопрос", "Һорауҙы ябыу")}>
          <IconClose size={20} />
        </button>
      </div>
      <div className="tdecl__grid">
        {DECLINE_REASONS.map((r) => (
          <button
            key={r.key}
            type="button"
            className={"tdecl__chip" + (picked === r.key ? " is-on" : "")}
            disabled={picked != null}
            onClick={() => {
              setPicked(r.key);
              onPick(r.key);
            }}
          >
            {ru ? r.ru : r.ba}
          </button>
        ))}
      </div>
    </section>
  );
}

// ----------------------------- Оффер: маршрут + шторка решения -----------------------------
/** InstantOfferOverlay: карта маршрута (48 % экрана, без жестов), шторка 66 % с таймером и решением. */
function OfferOverlay({
  order,
  accepting,
  note,
  onAccept,
  onSkip,
}: {
  order: InstantOrder;
  accepting: boolean;
  note: string;
  onAccept: () => Promise<"accepted" | "retry" | "closed">;
  onSkip: (afterFailedAccept?: boolean) => void;
}) {
  const { appText } = useLang();
  const expiryRef = useRef(new DriverOfferExpiry(order.id));
  if (expiryRef.current.orderId !== order.id) {
    expiryRef.current = new DriverOfferExpiry(order.id);
  }
  const onSkipRef = useRef(onSkip);
  onSkipRef.current = onSkip;
  // Окно решения фиксируем при появлении карточки: по нему считается кольцо таймера.
  const expMs = serverMs(order.offer_expires_at);
  const exp = Number.isNaN(expMs) ? Date.now() + 20000 : expMs;
  const windowRef = useRef<{ id: number; ms: number }>({ id: order.id, ms: Math.max(1, exp - Date.now()) });
  if (windowRef.current.id !== order.id) windowRef.current = { id: order.id, ms: Math.max(1, exp - Date.now()) };
  const now = useNowMs();
  const remaining = Math.max(0, exp - now);
  const secondsLeft = remaining <= 0 ? 0 : Math.ceil(remaining / 1000);
  const canAccept = remaining > 0;
  const progress = Math.min(1, Math.max(0, remaining / windowRef.current.ms));

  const beginAccept = () => {
    if (!canAccept) return;
    if (!expiryRef.current.beginAccept(order.id)) return;
    void onAccept().then((result) => {
      const declineAfterFailure = expiryRef.current.finishAccept(order.id, result !== "retry");
      if (declineAfterFailure) onSkipRef.current(true);
    });
  };

  const skipOnce = () => {
    if (expiryRef.current.manualDecline(order.id)) onSkipRef.current();
  };

  useEffect(() => {
    // Дедлайн истёк (или пришёл битым) — безопасно пропускаем без вопросов.
    if (remaining <= 0 && expiryRef.current.expire(order.id)) onSkipRef.current(true);
  }, [remaining <= 0, order.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const fromPt: GeoPoint | null = order.from_lat != null && (order.from_lat !== 0 || order.from_lng !== 0) ? { lat: order.from_lat, lng: order.from_lng ?? 0 } : null;
  const toPt: GeoPoint | null = order.to_lat != null && (order.to_lat !== 0 || order.to_lng !== 0) ? { lat: order.to_lat, lng: order.to_lng ?? 0 } : null;

  const km = order.offer_pickup_km ?? null;
  const eta = order.offer_pickup_eta_min ?? null;
  const pickupLine =
    km != null && eta != null
      ? appText(`${km1(km)} км · ${eta} мин до пассажира`, `Пассажирға тиклем ${km1(km)} км · ${eta} мин`)
      : km != null
        ? appText(`${km1(km)} км до пассажира · время уточняется`, `Пассажирға тиклем ${km1(km)} км · ваҡыт асыҡлана`)
        : appText("Время подачи уточняется", "Килеп етеү ваҡыты асыҡлана");
  const tripMeta = [
    order.distance_km > 0 ? appText(`${km1(order.distance_km)} км поездка`, `${km1(order.distance_km)} км сәфәр`) : "",
    order.eta_min > 0 ? appText(`≈ ${Math.trunc(order.eta_min)} мин в пути`, `Юлда ≈ ${Math.trunc(order.eta_min)} мин`) : "",
  ]
    .filter(Boolean)
    .join("  ·  ");

  const gross = order.driver_gross_kop ?? 0;
  const hasServerNet = gross > 0;
  const income = hasServerNet ? kopExactLabel(order.driver_net_kop ?? 0) : `${order.price_estimate} ₽`;
  const feePct = order.driver_fee_percent ?? 0;
  const feePctLabel = String(Math.round(feePct * 100) / 100).replace(".", ",");
  const rating = order.passenger_rating;
  const trustHead = rating != null ? appText(`Пассажир ★ ${rating.toFixed(1).replace(".", ",")}`, `Пассажир ★ ${rating.toFixed(1).replace(".", ",")}`) : appText("Пассажир · новичок", "Пассажир · яңы юлсы");
  const trustDetail = order.passenger_trips > 0 ? appText(`${order.passenger_trips} завершённых поездок`, `${order.passenger_trips} тамамланған сәфәр`) : appText("Первая поездка в Юлдаш", "Юлдашта тәүге сәфәр");
  const notes: string[] = [];
  if (hasServerNet)
    notes.push(
      appText(
        `Пассажир платит ${kopExactLabel(gross)} · комиссия ${kopExactLabel(order.driver_fee_kop ?? 0)} (${feePctLabel}%)`,
        `Пассажир ${kopExactLabel(gross)} түләй · комиссия ${kopExactLabel(order.driver_fee_kop ?? 0)} (${feePctLabel}%)`
      )
    );
  if ((order.pickup_fee_kop ?? 0) > 0)
    notes.push(appText(`Подача: ${kopExactLabel(order.pickup_fee_kop ?? 0)} тебе сверху, без комиссии`, `Килеп алыу: ${kopExactLabel(order.pickup_fee_kop ?? 0)} һиңә өҫтәмә, комиссияһыҙ`));
  if (order.pickup_enroute) notes.push(appText("Тебе по пути — надбавка за подачу половинная", "Юл ыңғайы — килеп алыу өҫтәмәһе яртылаш"));
  if ((order.weather_fee_kop ?? 0) > 0)
    notes.push(appText(`Тяжёлая дорога: +${kopExactLabel(order.weather_fee_kop ?? 0)}, без комиссии`, `Ауыр юл: +${kopExactLabel(order.weather_fee_kop ?? 0)}, комиссияһыҙ`));
  if ((order.options_fee_kop ?? 0) > 0)
    notes.push(appText(`Кресло и опции: +${kopExactLabel(order.options_fee_kop ?? 0)}, без комиссии`, `Ултырғыс һәм өҫтәмәләр: +${kopExactLabel(order.options_fee_kop ?? 0)}, комиссияһыҙ`));
  if (order.promo_discount_kop > 0)
    notes.push(
      appText(
        `Пассажир отдаст ${kopExactLabel(passengerPayKop(order))}: скидку оплачивает Юлдаш, твой доход не меняется`,
        `Пассажир ${kopExactLabel(passengerPayKop(order))} бирә: ташламаны Юлдаш түләй, һинең килем үҙгәрмәй`
      )
    );

  const danger = !canAccept || secondsLeft <= 5;
  const R = 27;
  const C = 2 * Math.PI * R;

  return (
    <div className="toffer" role="dialog" aria-modal="true" aria-label={appText("Входящий заказ", "Яңы заказ")}>
      {/* Карта не интерактивна: случайный свайп в последние секунды не отнимет «Принять». */}
      <div className="toffer__map" aria-hidden>
        <YandexMap from={fromPt} to={toPt} route={!!(fromPt && toPt)} height="100%" />
      </div>
      <div className="toffer__chip">
        <IconMap size={18} />
        <span>{appText("Маршрут A → Б", "Юл А → Б")}</span>
      </div>

      <section className="toffer__sheet" key={order.id}>
        <span className="toffer__handle" aria-hidden />
        <div className="toffer__head">
          <div className="toffer__title">
            <h1>{appText("Входящий заказ", "Яңы заказ")}</h1>
            <p className={canAccept ? "" : "is-red"}>
              {canAccept ? appText(`Решение за ${secondsLeft} сек`, `${secondsLeft} секундта хәл ит`) : appText("Время вышло", "Ваҡыт үтте")}
            </p>
          </div>
          {/* InstantOfferTimer: кольцо 62, штрих 4, красное на последних пяти секундах. */}
          <div className={"toffer__timer" + (danger ? " is-red" : "")} role="timer" aria-label={appText(`${secondsLeft} сек`, `${secondsLeft} сек`)}>
            <svg viewBox="0 0 62 62" aria-hidden>
              <circle className="toffer__track" cx="31" cy="31" r={R} />
              <circle className="toffer__ring" cx="31" cy="31" r={R} strokeDasharray={C} strokeDashoffset={C * (1 - progress)} />
            </svg>
            <b>{secondsLeft}</b>
            <small>{appText("сек", "сек")}</small>
          </div>
        </div>

        <div className="toffer__scroll">
          {/* InstantOfferRouteCard */}
          <div className="toffer-card">
            <div className="toffer-card__row">
              <span className="toffer-card__pin" aria-hidden><IconPin size={22} /></span>
              <span className="toffer-card__text">
                <small>{appText("До пассажира", "Пассажирға тиклем")}</small>
                <strong>{pickupLine}</strong>
              </span>
            </div>
            <RouteTimeline from={order.from_text} to={order.to_text} fromLabel={appText("Точка A", "A нөктәһе")} toLabel={appText("Точка Б", "Б нөктәһе")} compact />
            {tripMeta && <p className="toffer-card__meta">{tripMeta}</p>}
          </div>

          {/* InstantOfferMoneyAndClass: две равные колонки одной высоты (116). */}
          <div className="toffer-money">
            <div className="toffer-money__income">
              <small>{appText("Предполагаемый доход", "Көтөлгән килем")}</small>
              <b>{income}</b>
              {order.promo_discount_kop > 0 && (
                <span>
                  {appText(
                    `Наличными от пассажира меньше: у него промокод −${kopExactLabel(order.promo_discount_kop)}, разницу платит Юлдаш`,
                    `Пассажирҙан наличный менән аҙыраҡ: унда промокод −${kopExactLabel(order.promo_discount_kop)}, айырманы Юлдаш түләй`
                  )}
                </span>
              )}
            </div>
            <div className="toffer-money__facts">
              <span className="toffer-fact">
                <IconCar size={19} />
                <span>{categoryTitle(order.category, appText)}</span>
              </span>
              <span className="toffer-fact">
                <PayMethodIcon method={order.payment_method} size={19} />
                <span>{payMethodTitle(order.payment_method, appText)}</span>
              </span>
            </div>
          </div>

          {/* InstantOfferPassengerTrust */}
          <div className="toffer-trust">
            <span className="toffer-trust__icon" aria-hidden><IconShield size={24} /></span>
            <span className="toffer-trust__text">
              <strong>{trustHead}</strong>
              <small>{trustDetail}</small>
            </span>
            <span className="toffer-trust__star" aria-label={appText("Рейтинг пассажира", "Пассажир рейтингы")}><IconStar size={24} /></span>
          </div>

          {/* InstantOfferHonestDetails */}
          {notes.length > 0 && (
            <ul className="toffer-notes">
              {notes.map((n) => (
                <li key={n}>{n}</li>
              ))}
            </ul>
          )}

          {/* Не взяли и не поняли почему — худшее, что может быть за рулём. Говорим прямо. */}
          {note && <p className="toffer__note" role="status">{note}</p>}
        </div>

        {/* Решение закреплено вне прокрутки: отказ не выглядит опасным, принятие — самое заметное. */}
        <div className="toffer__actions">
          <button type="button" className="btn-soft toffer__btn" onClick={skipOnce} disabled={accepting}>
            {appText("Пропустить", "Үткәреп ебәреү")}
          </button>
          <button type="button" className="btn-primary toffer__btn" onClick={beginAccept} disabled={accepting || !canAccept}>
            {accepting ? <span className="spinner spinner--sm spinner--on-filled" aria-hidden /> : appText("Принять", "Ҡабул итеү")}
          </button>
        </div>
      </section>
    </div>
  );
}

// ----------------------------- Активная поездка водителя -----------------------------
/**
 * InstantDriverTripScreen: до посадки — шапка «Поездка», карта 36 % и карточка с прогрессом,
 * пассажиром, «Как найти», деньгами, навигатором и таймером ожидания; главный переход фазы
 * закреплён снизу. После посадки — навигатор; финал — экраны «завершена» / «отменён».
 */
function DriverTrip({
  order,
  pos,
  note,
  busy,
  onAdvance,
  onCancel,
  onNoShow,
  onRefresh,
  onChat,
  onReturnToLine,
  onShiftFinished,
}: {
  order: InstantOrder;
  pos: GeoPoint | null;
  /** Действие не прошло — показываем прямо у кнопок, а не где-то в стороне. */
  note: string;
  busy: boolean;
  onAdvance: (n: "arrived" | "onboard" | "done") => void;
  onCancel: () => void;
  onNoShow: () => void;
  /** Перечитать заказ сразу после ответа на смену адреса или «Стоим». */
  onRefresh: () => void;
  onChat: () => void;
  onReturnToLine: () => void;
  onShiftFinished: () => void;
}) {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [confirmNoShow, setConfirmNoShow] = useState(false);
  const s = order.status;
  const now = useNowMs(s === "arriving");

  if (s === "done") {
    return (
      <TaxiDriverCompleted
        order={order}
        onReturnToLine={onReturnToLine}
        onShiftFinished={onShiftFinished}
        onOpenReceipt={() => navigate(`/taxi-receipt/${order.id}`)}
      />
    );
  }
  if (s === "cancelled") {
    return <TaxiDriverCancelled order={order} onReturnToLine={onReturnToLine} onShiftFinished={onShiftFinished} />;
  }
  if (s === "onboard") {
    return (
      <TaxiDriverOnboardNavigator
        order={order}
        pos={pos}
        busy={busy}
        actionError={note}
        onBack={() => navigate("/driver")}
        onChat={onChat}
        onSafety={() => navigate("/sos")}
        onOpenExternalNavigator={() => openNavigator(order.to_lat, order.to_lng)}
        onFinish={() => onAdvance("done")}
        tripControls={<TaxiDriverTripActions order={order} onChanged={onRefresh} />}
      />
    );
  }

  const fromPt: GeoPoint | null = order.from_lat != null ? { lat: order.from_lat, lng: order.from_lng ?? 0 } : null;
  const toPt: GeoPoint | null = order.to_lat != null ? { lat: order.to_lat, lng: order.to_lng ?? 0 } : null;
  const primary =
    s === "accepted"
      ? { label: appText("Я на месте", "Мин урында"), next: "arrived" as const }
      : s === "arriving"
        ? { label: appText("Пассажир сел", "Пассажир ултырҙы"), next: "onboard" as const }
        : { label: appText("Обновить", "Яңыртыу"), next: null };
  const payToDriver = kopExactLabel(passengerPayKop(order));
  const waitKop = order.waiting_fee_kop;
  const noShowAt = serverMs(order.no_show_at);
  const noShowReady = s === "arriving" && !Number.isNaN(noShowAt) && now >= noShowAt;
  const hasBreakdown = (order.driver_gross_kop ?? 0) > 0;

  return (
    <>
      <div className="ttrip">
        <SubHeader title={appText("Поездка", "Сәфәр")} onBack={() => navigate("/driver")} />
        <div className="ttrip__map">
          <YandexMap from={fromPt} to={toPt} me={pos} route={!!(fromPt && toPt)} height="100%" />
        </div>
        {/* Карточка 22 сверху с тенью sheet: прокручиваемые детали + закреплённая кнопка фазы. */}
        <div className="ttrip__card">
          <div className="ttrip__scroll">
            <TaxiTripProgress status={s} />
            {/* Смена адреса, способ расчёта, заезды — блок сам решает, что показать. */}
            <TaxiDriverTripActions order={order} onChanged={onRefresh} />

            {/* Пассажир + телефон (после accept) */}
            <div className="ttrip-who">
              <span className="ttrip-who__icon" aria-hidden><IconProfile size={20} /></span>
              <span className="ttrip-who__text">
                <span className="ttrip-who__name">
                  <strong>{order.passenger_name.trim() || appText("Пассажир", "Пассажир")}</strong>
                  {/* Заказ ДЛЯ ДРУГОГО: везём не заказчика — имя и телефон того, кого забираем. */}
                  {order.for_other && <em>{appText("заказ для другого", "икенсе кеше өсөн")}</em>}
                </span>
                <small>
                  {order.from_text.trim() || appText("Точка А", "А нөктәһе")} → {order.to_text.trim() || appText("Точка Б", "Б нөктәһе")}
                </small>
              </span>
              <button type="button" className="ttrip-who__btn" onClick={onChat} aria-label={appText("Написать пассажиру", "Пассажирға яҙырға")}>
                <IconChat size={20} />
              </button>
              {order.passenger_phone && (
                <a className="ttrip-who__btn ttrip-who__btn--call" href={`tel:${order.passenger_phone}`} aria-label={appText("Позвонить пассажиру", "Пассажирға шылтыратыу")}>
                  <IconPhone size={20} />
                </a>
              )}
            </div>

            {/* «Как меня найти» — подъезд и комментарий: чат открывается только ПОСЛЕ принятия. */}
            {(order.comment?.trim() || order.entrance?.trim()) && (
              <div className="ttrip-find">
                <span className="ttrip-find__head">
                  <IconPin size={16} /> {appText("Как найти", "Нисек табырға")}
                </span>
                {order.entrance?.trim() && <strong>{order.entrance}</strong>}
                {order.comment?.trim() && <p>{order.comment}</p>}
              </div>
            )}

            <p className="ttrip__pay">
              {appText(
                `Пассажир платит: ${payToDriver}${waitKop > 0 ? ` + ${kopExactLabel(waitKop)} ожидание` : ""} · наличными/переводом`,
                `Пассажир түләй: ${payToDriver}${waitKop > 0 ? ` + ${kopExactLabel(waitKop)} көтөү` : ""} · аҡсалата/күсереп`
              )}
            </p>
            {/* TaxiPromoPayRow (forDriver) */}
            {order.promo_discount_kop > 0 && (
              <div className="ttrip-promo">
                <span className="ttrip-promo__row">
                  <IconGift size={18} />
                  <strong>{appText(`Пассажир отдаёт на руки ${payToDriver}`, `Пассажир ҡулға ${payToDriver} бирә`)}</strong>
                  <s>{kopExactLabel(Math.max(0, order.price_final ?? order.price_estimate) * 100)}</s>
                </span>
                <small>
                  {appText(
                    `Промокод −${kopExactLabel(order.promo_discount_kop)} оплачивает Юлдаш: разницу берём из своей комиссии, не хватит — доплатим тебе в кошелёк. Ты получаешь столько же, как без промокода.`,
                    `Промокод −${kopExactLabel(order.promo_discount_kop)} хаҡын Юлдаш түләй: айырманы үҙ комиссиябыҙҙан алабыҙ, етмәһә — кеҫәңә өҫтәйбеҙ. Һин промокодһыҙҙағы кеүек үк алаһың.`
                  )}
                </small>
              </div>
            )}
            {hasBreakdown ? (
              <p className="ttrip__net">
                {appText(
                  `Ориентир чистыми: ${kopExactLabel(order.driver_net_kop ?? 0)} до платного ожидания`,
                  `Таҙа килем самаһы: ${kopExactLabel(order.driver_net_kop ?? 0)} түләүле көтөүгә тиклем`
                )}
              </p>
            ) : (
              /* Старый сервер без разбивки: честно говорим хотя бы ставку комиссии. */
              <p className="ttrip__pay">{appText(commissionLabel(order.driver_fee_percent).ru, commissionLabel(order.driver_fee_percent).ba)}</p>
            )}

            {/* «Навигатор»: до посадки ведём к подаче (А). */}
            <button type="button" className="ttrip-navi" onClick={() => openNavigator(order.from_lat, order.from_lng)}>
              <YuRoute size={17} />
              {appText("Навигатор · к пассажиру", "Навигатор · пассажирға")}
            </button>

            {/* «Я на месте» → таймер ожидания: бесплатное окно и платные минуты, как у пассажира. */}
            {s === "arriving" && <WaitingRow order={order} now={now} />}

            {/* «Пассажир не вышел» — по честному таймингу сервера (5 бесплатных минут + 3 сверх). */}
            {noShowReady && (
              <button type="button" className="ttrip-noshow" onClick={() => setConfirmNoShow(true)}>
                {appText("Пассажир не вышел", "Пассажир сыҡманы")}
              </button>
            )}

            {note && (
              <p className="ttrip__err" role="alert">
                {note}
              </p>
            )}

            {/* InstantSafetyRow: SOS — безопасность водителя тоже продукт. */}
            <button type="button" className="ttrip-sos" onClick={() => navigate("/sos")}>
              <IconWarn size={18} /> {appText("SOS", "SOS")}
            </button>

            <button type="button" className="ttrip-cancel" onClick={() => !busy && setConfirmCancel(true)} disabled={busy}>
              {appText("Отменить заказ", "Заказды кире алыу")}
            </button>
          </div>

          {/* Главный переход фазы закреплён снизу — не уезжает за край и при крупном шрифте. */}
          <div className="ttrip__bar">
            <button
              type="button"
              className="btn-primary ttrip__primary"
              onClick={() => primary.next && !busy && onAdvance(primary.next)}
              disabled={busy || !primary.next}
            >
              {busy ? <span className="spinner spinner--sm spinner--on-filled" aria-hidden /> : primary.label}
            </button>
          </div>
        </div>
      </div>

      {confirmCancel && (
        <AlertDialog
          title={appText("Отменить поездку?", "Сәфәрҙе кире алаһыңмы?")}
          text={appText(
            "Пассажир получит уведомление, заказ закроется. Это действие нельзя отменить. Один раз — ничего страшного, но если отменять принятые заказы часто, новые заказы какое-то время приходить не будут.",
            "Пассажирға хәбәр бара, заказ ябыла. Был эште кире ҡайтарып булмай. Бер тапҡыр — бер ни ҙә булмай, әммә ҡабул ителгән заказдарҙы йыш кире алһаң, яңы заказдар бер аҙ ваҡыт килмәйәсәк."
          )}
          onClose={() => !busy && setConfirmCancel(false)}
          confirm={{
            label: appText("Отменить поездку", "Сәфәрҙе кире алыу"),
            tone: "danger",
            disabled: busy,
            onClick: () => {
              setConfirmCancel(false);
              onCancel();
            },
          }}
          dismiss={{ label: appText("Продолжить поездку", "Сәфәрҙе дауам итеү"), onClick: () => setConfirmCancel(false), disabled: busy }}
        />
      )}
      {confirmNoShow && (
        <AlertDialog
          title={appText("Пассажир не вышел?", "Пассажир сыҡманымы?")}
          text={appText(
            "Заказ закроется, пассажиру зафиксируется плата за подачу. Позвони ему перед этим — вдруг уже бежит.",
            "Заказ ябыла, пассажирға килеү хаҡы яҙыла. Тәүҙә шылтыратып ҡара — бәлки, йүгереп килә лә."
          )}
          onClose={() => setConfirmNoShow(false)}
          confirm={{
            label: appText("Да, не вышел", "Эйе, сыҡманы"),
            tone: "danger",
            onClick: () => {
              setConfirmNoShow(false);
              onNoShow();
            },
          }}
          dismiss={{ label: appText("Ещё подожду", "Тағы көтәм"), onClick: () => setConfirmNoShow(false) }}
        />
      )}
    </>
  );
}

/** InstantWaitingRow: «Бесплатное ожидание 4:59» с убывающей полоской, потом «Платное ожидание · +N ₽/мин». */
function WaitingRow({ order, now }: { order: InstantOrder; now: number }) {
  const { appText } = useLang();
  const startMs = serverMs(order.waiting_started_at);
  if (Number.isNaN(startMs)) return null;
  const elapsed = Math.max(0, Math.floor((now - startMs) / 1000));
  const freeSec = order.wait_free_min * 60;
  const isFree = elapsed < freeSec;
  const left = freeSec - elapsed;
  const leftLabel = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
  const paidRub = Math.max(0, Math.floor(elapsed / 60) - order.wait_free_min) * order.wait_fee_rub_per_min;
  const freeLeft = freeSec > 0 ? Math.min(1, Math.max(0, left / freeSec)) : 0;
  return (
    <div className={"ttrip-wait" + (isFree ? "" : " is-paid")}>
      <span className="ttrip-wait__row">
        <IconClock size={18} />
        <strong>
          {isFree
            ? appText(`Бесплатное ожидание ${leftLabel}`, `Бушлай көтөү ${leftLabel}`)
            : appText(
                `Платное ожидание · +${order.wait_fee_rub_per_min} ₽/мин${paidRub > 0 ? ` (уже +${paidRub} ₽)` : ""}`,
                `Түләүле көтөү · +${order.wait_fee_rub_per_min} ₽/мин${paidRub > 0 ? ` (инде +${paidRub} ₽)` : ""}`
              )}
        </strong>
      </span>
      {isFree && (
        <span className="ttrip-wait__bar" aria-hidden>
          <i style={{ width: `${freeLeft * 100}%` }} />
        </span>
      )}
    </div>
  );
}

// ----------------------------- Кабинет: дашборд, отдых, прогресс смены -----------------------------
/** RU-плюрал «заказ/заказа/заказов». */
const ordersRu = (n: number) => pluralRu(n, "заказ", "заказа", "заказов");
const feePct = (v: number) => `${String(Math.round(v * 100) / 100).replace(".", ",")}%`;

/**
 * TaxiDashboardCard: тёмно-зелёная плашка «Чистыми сегодня» + лесенка комиссии по поездкам.
 * Комиссию показываем всегда — водитель видит, сколько с него берут, до конца дня.
 */
function TaxiDashboardCard({ wd }: { wd: Workday }) {
  const { appText } = useLang();
  const tiers = wd.fee_tiers?.length ? wd.fee_tiers : [3, 8, 15];
  const bounds = wd.fee_tier_trips ?? [];
  const activeIdx = bounds.length < 2 ? 0 : wd.trips_done < bounds[0] ? 0 : wd.trips_done < bounds[1] ? 1 : 2;
  const note = wd.promo_active
    ? (() => {
        const dl = wd.promo_days_left ?? 0;
        const after = wd.fee_after_promo_percent;
        return appText(
          `Сейчас ${feePct(wd.fee_percent)} — промо для первых водителей. Осталось ${dl} ${pluralRu(dl, "день", "дня", "дней")}.${after != null ? ` Потом — ${feePct(after)}.` : ""} Всё равно ниже, чем у агрегаторов.`,
          `Хәҙер ${feePct(wd.fee_percent)} — беренсе йөрөтөүселәр өсөн промо. ${dl} көн ҡалды.${after != null ? ` Шунан — ${feePct(after)}.` : ""} Барыбер агрегаторҙарҙан түбәнерәк.`
        );
      })()
    : wd.fee_next_percent != null && wd.fee_trips_to_next != null
      ? appText(
          `Сейчас ${feePct(wd.fee_percent)} — стартовая ставка. Через ${wd.fee_trips_to_next} ${pluralRu(wd.fee_trips_to_next, "поездку", "поездки", "поездок")} станет ${feePct(wd.fee_next_percent)}. Всё равно ниже, чем у агрегаторов.`,
          `Хәҙер ${feePct(wd.fee_percent)} — башланғыс. Тағы ${wd.fee_trips_to_next} юлдан ${feePct(wd.fee_next_percent)} булыр. Барыбер агрегаторҙарҙан түбәнерәк.`
        )
      : appText(`Текущая ставка комиссии — ${feePct(wd.fee_percent)}.`, `Хәҙерге комиссия ставкаһы — ${feePct(wd.fee_percent)}.`);
  return (
    <section className="tdash">
      <div className="tdash__hero">
        <div className="tdash__text">
          <small>{appText("Чистыми сегодня", "Бөгөн таҙа килем")}</small>
          <b>{kopExactLabel(wd.net_today_kop)}</b>
          <span>{appText(`${wd.orders_today} ${ordersRu(wd.orders_today)}`, `${wd.orders_today} заказ`)}</span>
          <em>
            {appText(
              `Пассажиры: ${kopExactLabel(wd.gross_today_kop)} · комиссия: ${kopExactLabel(wd.fee_today_kop)}`,
              `Пассажирҙар: ${kopExactLabel(wd.gross_today_kop)} · комиссия: ${kopExactLabel(wd.fee_today_kop)}`
            )}
          </em>
        </div>
        <span className="tdash__car" aria-hidden><IconCar size={30} /></span>
      </div>
      <div className="tdash__fee">
        <div className="tdash__fee-row">
          <span>{appText("Комиссия сервиса", "Сервис комиссияһы")}</span>
          <b>{feePct(wd.fee_percent)}</b>
        </div>
        <div className="tdash__tiers" aria-hidden>
          {tiers.map((t, i) => (
            <span key={i} className={"tdash__tier" + (i === activeIdx ? " is-on" : "")}>
              {feePct(t)}
            </span>
          ))}
        </div>
        <p>{note}</p>
      </div>
    </section>
  );
}

/** TaxiShiftProgressCard: «Смена за рулём», «За рулём 2 ч 15 мин», полоса к лимиту; последний час — тёплое предупреждение. */
function TaxiShiftProgressCard({ wd, ru }: { wd: Workday; ru: boolean }) {
  const { appText } = useLang();
  const warm = wd.remaining_sec <= 3600;
  const progress = Math.min(1, Math.max(0, wd.seconds_online / Math.max(1, wd.limit_sec)));
  return (
    <section className={"tshift" + (warm ? " is-warm" : "")}>
      <div className="tshift__head">
        <IconClock size={20} />
        <strong>{appText("Смена за рулём", "Руль артындағы смена")}</strong>
        <span>{appText(`из ${wd.limit_hours} ч`, `${wd.limit_hours} сәғәттән`)}</span>
      </div>
      <b className="tshift__time">{ru ? `За рулём ${durationLabel(wd.seconds_online, true)}` : `Руль артында ${durationLabel(wd.seconds_online, false)}`}</b>
      <span className="tshift__bar" aria-hidden>
        <i style={{ width: `${progress * 100}%` }} />
      </span>
      <p>
        {warm
          ? appText("До отдыха меньше часа 🌙 Спокойно заверши дела на линии.", "Ялға бер сәғәттән дә әҙерәк ҡалды 🌙 Линиялағы эштәреңде тыныс ҡына тамамла.")
          : appText(
              `Считаем поездки и доставки вместе. После ${wd.limit_hours} часов за рулём — отдых до утра. Попутка в лимит не входит: это твоя дорога, а не работа.`,
              `Сәфәрҙәрҙе һәм илтеүҙәрҙе бергә иҫәпләйбеҙ. Руль артында ${wd.limit_hours} сәғәттән һуң — иртәнгә тиклем ял. Юлдаш сәфәре иҫәпкә инмәй: был һинең юлың, эш түгел.`
            )}
      </p>
      {/* Неделя: день видно на полоске, а недельный потолок был невидим — говорим ДО блока. */}
      {(wd.week_seconds ?? 0) > 0 && (() => {
        const weekLimitH = wd.week_limit_hours ?? 0;
        const close = weekLimitH > 0 && (wd.week_seconds ?? 0) >= weekLimitH * 3600 - 4 * 3600;
        return (
          <p className={"tshift__week" + (close ? " is-close" : "")}>
            {ru
              ? `За неделю ${durationLabel(wd.week_seconds ?? 0, true)} из ${weekLimitH} ч${close ? " — недельный запас на исходе" : ""}`
              : `Аҙна эсендә ${durationLabel(wd.week_seconds ?? 0, false)}, ${weekLimitH} сәғәттән${close ? " — аҙналыҡ запас бөтөп бара" : ""}`}
          </p>
        );
      })()}
    </section>
  );
}

/** TaxiWeekRestCard: недельный потолок закрыл линию — окно скользящее, «завтра с 6» тут было бы враньём. */
function TaxiWeekRestCard({ wd, ru, onCreateRide }: { wd: Workday; ru: boolean; onCreateRide: () => void }) {
  const { appText } = useLang();
  const weekH = wd.week_limit_hours ?? 0;
  return (
    <section className="trest">
      <div className="trest__head">
        <span className="trest__moon" aria-hidden><YuMoon size={20} /></span>
        <strong>{appText("Недельный отдых", "Аҙналыҡ ял")}</strong>
      </div>
      <p>
        {ru
          ? `За неделю ты уже ${durationLabel(wd.week_seconds ?? 0, true)} за рулём — это потолок в ${weekH} часов 🌙 Линия откроется сама, как только самые старые часы выпадут из недели.`
          : `Аҙна эсендә һин ${durationLabel(wd.week_seconds ?? 0, false)} руль артында — был ${weekH} сәғәтлек сик 🌙 Иң иҫке сәғәттәр аҙнанан төшкәс, линия үҙе асыласаҡ.`}
      </p>
      {!wd.return_ride_used && (
        <button type="button" className="tdc__text-btn trest__text" onClick={onCreateRide}>
          {appText("Взять попутчика домой", "Өйгә юлдаш алырға")}
        </button>
      )}
    </section>
  );
}

/** TaxiRestCard: «Ты сегодня за рулём 8 часов 🌙», когда снова на линию, «один попутчик домой». */
function TaxiRestCard({ wd, ru, onCreateRide }: { wd: Workday; ru: boolean; onCreateRide: () => void }) {
  const { appText } = useLang();
  const unlock = wd.unlock_at ? formatWhen(wd.unlock_at, ru) : null;
  return (
    <section className="trest">
      <div className="trest__head">
        <span className="trest__moon" aria-hidden><YuMoon size={22} /></span>
        <strong>{appText(`Ты сегодня за рулём ${wd.limit_hours} часов 🌙`, `Һин бөгөн ${wd.limit_hours} сәғәт руль артында 🌙`)}</strong>
      </div>
      <p>
        {unlock
          ? appText(`Отдохни — снова на линию: ${unlock}. Хорошо поработал 👏`, `Ял ит — йәнә линияға: ${unlock}. Яҡшы эшләнең 👏`)
          : appText("Отдохни — завтра с 6 утра снова на линию. Хорошо поработал 👏", "Ял ит — иртәгә иртәнге 6-нан йәнә линияға. Яҡшы эшләнең 👏")}
      </p>
      {!wd.return_ride_used ? (
        <div className="trest__ride">
          <strong>{appText("Возьми одного попутчика домой", "Бер юлдашты өйгә алып ҡайт")}</strong>
          <p>
            {appText(
              "Машина всё равно едет назад — подвези земляка. Одна публикация попутки до конца отдыха.",
              "Машина барыбер кире ҡайта — яҡташыңды ултыртып ҡайт. Ял бөткәнсе бер генә юлдаш сәфәре."
            )}
          </p>
          <button type="button" className="btn-primary trest__btn" onClick={onCreateRide}>
            <IconCar size={20} /> {appText("Опубликовать поездку домой", "Өйгә сәфәр баҫтырыу")}
          </button>
        </div>
      ) : (
        <b className="trest__done">{appText("Попутчик домой уже опубликован 💚 Лёгкой дороги!", "Өйгә юлдаш инде баҫтырылған 💚 Юлың еңел булһын!")}</b>
      )}
    </section>
  );
}
