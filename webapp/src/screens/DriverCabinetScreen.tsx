// ================================================================
//  Кабинет водителя. RequireAuth.
//  • Тумблер «Я на линии» (POST /driver/online)
//  • Плашка статуса проверки (GET /driver/status: docs_status)
//  • Опубликовать поездку → /create-ride
//  • Заявки пассажиров → /requests-feed
//  • Мои поездки / Архив (GET /driver/rides?status=all) — счётчики
//  • Регулярные маршруты (GET/POST/DELETE /driver/schedule)
//  • Ссылки: Мой заработок, Boost, стать таксистом (заглушка волны 4)
// ================================================================
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import PriorityCard from "../components/PriorityCard";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchDriverStatus,
  setDriverOnline,
  setDriverGender,
  setTipsSbp,
  fetchRideTips,
  type RideTips,
  fetchDriverRides,
  fetchMySchedules,
  createSchedule,
  deleteSchedule,
  cancelRide,
  completeRide,
  type DriverStatus,
  type DriverSchedule,
} from "../api/driver";
import {
  cancelBooking,
  confirmBooking,
  fetchDriverBookings,
  type DriverBooking,
} from "../api/bookings";
import type { Ride } from "../api/rides";
import { LoadingList, ErrorState, EmptyStateCard } from "../components/States";
import RideEditActions from "../components/RideEditActions";
import DriverPassengers from "../components/DriverPassengers";
import { SubHeader } from "./ConsentsScreen";
import { useAuth } from "../auth/AuthProvider";
import {
  ArchiveRideRow,
  CabinetMetric,
  SectionHeader,
  SettingSwitchRow,
  SettingsGroup,
  SettingsNavRow,
} from "../components/cabinetUi";
import { formatWhen, priceLabel } from "../utils/format";
import {
  IconArrow,
  IconRides,
  IconRequest,
  IconRocket,
  IconWallet,
  IconCalendar,
  IconTrash,
  IconCheck,
  IconChevron,
  IconCar,
  IconClock,
  IconWarn,
  IconIdCard,
  IconProfile,
  IconReceipt,
  IconShield,
  IconShare,
  IconTrend,
} from "../components/Icons";

type Status = "loading" | "error" | "ready";

const WD_RU = ["", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
const WD_BA = ["", "Дш", "Сш", "Шр", "Кс", "Йм", "Шб", "Йк"];

/** Плашка статуса модерации водителя. */
function VerifyBanner({ docs }: { docs: string }) {
  const { appText } = useLang();
  const navigate = useNavigate();
  if (docs === "verified") {
    return (
      <div className="consents__status" style={{ marginTop: 12 }}>
        <IconCheck size={16} /> {appText("Ты проверенный водитель", "Һин тикшерелгән йөрөтөүсе")}
      </div>
    );
  }
  const map: Record<string, { Icon: (p: { size?: number }) => JSX.Element; ru: string; ba: string; cta: boolean }> = {
    pending: {
      Icon: IconClock,
      ru: "Документы на проверке. Обычно это занимает недолго.",
      ba: "Документтар тикшереүҙә. Ғәҙәттә оҙаҡ түгел.",
      cta: false,
    },
    rejected: {
      Icon: IconWarn,
      ru: "Проверка не пройдена. Проверь фото и отправь снова.",
      ba: "Тикшереү үтмәне. Фотоны ҡара һәм ҡабат ебәр.",
      cta: true,
    },
    none: {
      Icon: IconIdCard,
      ru: "Чтобы возить пассажиров, пройди проверку водителя.",
      ba: "Юлаусы йөрөтөр өсөн водитель тикшереүен үт.",
      cta: true,
    },
  };
  const m = map[docs] ?? map.none;
  return (
    <button
      type="button"
      className={"trust-cta" + (m.cta ? "" : " is-static")}
      onClick={() => m.cta && navigate("/verify-driver")}
      disabled={!m.cta}
      style={{ marginTop: 12 }}
    >
      <span className="trust-cta__emoji"><m.Icon size={22} /></span>
      <span className="trust-cta__text">{appText(m.ru, m.ba)}</span>
      {m.cta && <IconChevron size={20} />}
    </button>
  );
}

export default function DriverCabinetScreen() {
  const { appText, lang } = useLang();
  const { user } = useAuth();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [driver, setDriver] = useState<DriverStatus | null>(null);
  const [womanBusy, setWomanBusy] = useState(false);

  /**
   * Заявить/снять «женщина за рулём». Снимаем в пустое значение, а не в «male»:
   * пол — добровольное поле, и передумать человек может без объяснений.
   */
  async function toggleWoman() {
    if (!driver || womanBusy) return;
    const next = driver.gender === "female" ? "" : "female";
    setWomanBusy(true);
    setDriver({ ...driver, gender: next }); // оптимистично
    try {
      const st = await setDriverGender(next);
      setDriver(st);
    } catch {
      setDriver({ ...driver, gender: driver.gender }); // откат
    } finally {
      setWomanBusy(false);
    }
  }
  const [rides, setRides] = useState<Ride[]>([]);
  const [schedules, setSchedules] = useState<DriverSchedule[]>([]);
  const [finishing, setFinishing] = useState<number | null>(null);
  const [finishNote, setFinishNote] = useState("");
  // Брони, ждущие ответа водителя. Раньше веб их не показывал вовсе: человек бронировал
  // место и ждал молчания, а водитель с сайта не мог ни подтвердить, ни отклонить.
  const [pending, setPending] = useState<DriverBooking[]>([]);
  const [bookingBusy, setBookingBusy] = useState<number | null>(null);
  const [bookingNote, setBookingNote] = useState("");

  /** Завершить рейс целиком (POST /rides/{id}/complete) — закрывает все брони разом. */
  async function finishRide(rideId: number) {
    if (finishing) return;
    setFinishing(rideId);
    setFinishNote("");
    try {
      await completeRide(rideId);
      setRides((prev) => prev.map((x) => (x.id === rideId ? { ...x, status: "done" } : x)));
    } catch (e) {
      setFinishNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось завершить. Проверь сеть.", "Тамамлап булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setFinishing(null);
    }
  }

  /**
   * Ответить на бронь: подтвердить или отклонить.
   *
   * Пассажир в это время сидит и ждёт — поэтому оба ответа делаем одинаково доступными,
   * а ошибку показываем словами сервера, а не «проверь сеть».
   */
  async function answerBooking(id: number, yes: boolean) {
    if (bookingBusy) return;
    setBookingBusy(id);
    setBookingNote("");
    try {
      if (yes) await confirmBooking(id);
      else await cancelBooking(id);
      setPending((prev) => prev.filter((b) => b.booking_id !== id));
    } catch (e) {
      setBookingNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось ответить на бронь. Проверь сеть.",
                    "Урын һаҡлауға яуап биреп булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBookingBusy(null);
    }
  }

  /**
   * Снять рейс целиком (POST /rides/{id}/cancel).
   *
   * Раньше этого в вебе не было вообще: сломалась машина — и снять поездку с сайта нечем,
   * а пассажиры выходят к дороге (аудит сценариев 30.08, P0). Спрашиваем подтверждение и
   * называем число людей, которых это касается: отмена за час до выезда — не то же самое,
   * что отмена за сутки, и человек должен это понимать до нажатия.
   */
  async function dropRide(r: Ride) {
    if (finishing) return;
    const занято = Math.max((r.seats_total ?? 0) - (r.seats_left ?? 0), 0);
    const вопрос = занято > 0
      ? appText(
          `Снять поездку? Брони отменятся у ${занято} чел., им придёт уведомление.`,
          `Сәфәрҙе алырғамы? ${занято} кешенең урыны кире алына, уларға хәбәр китә.`
        )
      : appText("Снять поездку? Её больше не будет видно в ленте.",
                "Сәфәрҙе алырғамы? Ул таҫмала күренмәйәсәк.");
    if (!window.confirm(вопрос)) return;
    setFinishing(r.id);
    setFinishNote("");
    try {
      await cancelRide(r.id);
      setRides((prev) => prev.map((x) => (x.id === r.id ? { ...x, status: "cancelled" } : x)));
    } catch (e) {
      setFinishNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось снять поездку. Проверь сеть.",
                    "Сәфәрҙе алып булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setFinishing(null);
    }
  }

  /**
   * Скинуть свою поездку в соседский чат. Половина пассажиров приходит
   * не из ленты, а из «Ринат в WhatsApp кинул» — поэтому текст сразу
   * готовый: маршрут, время, цена и ссылка.
   */
  async function shareRide(r: Ride) {
    const when = formatWhen(r.depart_at, ru);
    const text = appText(
      `Еду ${r.from_city} → ${r.to_city}, ${when}. ${priceLabel(r.price, true)}, свободно мест: ${r.seats_left}. Юлдаш: https://yulbash.ru`,
      `${r.from_city} → ${r.to_city} барам, ${when}. ${priceLabel(r.price, false)}, буш урын: ${r.seats_left}. Юлдаш: https://yulbash.ru`
    );
    if (navigator.share) {
      try {
        await navigator.share({ title: "Юлдаш", text });
        return;
      } catch {
        /* отменил — не ошибка */
      }
    }
    try {
      await navigator.clipboard.writeText(text);
      setFinishNote(appText("Ссылка скопирована", "Һылтанма күсерелде"));
      window.setTimeout(() => setFinishNote(""), 1600);
    } catch {
      /* буфер недоступен — молчим, экран цел */
    }
  }

  const [onlineBusy, setOnlineBusy] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    // Статус и поездки — на проде есть; расписание может появиться после мержа release (мягко).
    Promise.all([
      fetchDriverStatus(signal),
      fetchDriverRides("all", signal).catch(() => [] as Ride[]),
    ])
      .then(([st, rd]) => {
        setDriver(st);
        setRides(rd);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus("error");
      });
    fetchMySchedules(signal)
      .then(setSchedules)
      .catch(() => setSchedules([])); // 404 до релиза → без расписания
    // Ждущие брони — мягко: не смогли получить, кабинет всё равно работает.
    fetchDriverBookings(signal)
      .then((rows) => setPending(rows.filter((b) => b.status === "pending")))
      .catch(() => setPending([]));
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function toggleOnline() {
    if (!driver || onlineBusy) return;
    setOnlineBusy(true);
    const next = !driver.online;
    setDriver({ ...driver, online: next }); // оптимистично
    try {
      await setDriverOnline(next);
    } catch {
      setDriver({ ...driver, online: !next }); // откат
    } finally {
      setOnlineBusy(false);
    }
  }

  const activeRides = useMemo(
    () => rides.filter((r) => !r.status || r.status === "active"),
    [rides]
  );

  const doneRides = useMemo(() => rides.filter((r) => r.status === "done"), [rides]);
  const archive = useMemo(
    () => rides.filter((r) => r.status === "done" || r.status === "cancelled" || r.status === "expired"),
    [rides]
  );
  const passengersServed = useMemo(
    () => doneRides.reduce((sum, r) => sum + Math.max(0, r.seats_total - r.seats_left), 0),
    [doneRides]
  );
  const freeSeats = useMemo(() => activeRides.reduce((sum, r) => sum + Math.max(0, r.seats_left), 0), [activeRides]);
  const ratingText = user?.rating != null ? user.rating.toFixed(1) : "—";

  return (
    <>
      <SubHeader title={appText("Кабинет водителя", "Йөрөтөүсе кабинеты")} onBack={() => navigate(-1)} />

      {status === "loading" && <LoadingList count={2} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && driver && (
        <div className="cabinet">
          {/* Порядок блоков — как в Android DriverCabinetContent: заголовок, проверка, «на линии»,
              тумблеры, метрики, мои маршруты, брони, пассажиры, приоритет, архив, регулярные, разделы. */}
          <div className="cabinet__intro">
            <h2>{appText("Маршруты и проверка", "Маршруттар һәм тикшереү")}</h2>
            <p>{appText("Публикуй поездки, проходи проверку и поднимай маршрут выше.", "Сәфәр баҫтыр, тикшереү үт һәм маршрутты өҫкә күтәр.")}</p>
          </div>

          <VerifyBanner docs={driver.docs_status} />

          <SettingsGroup>
            <SettingSwitchRow
              icon={<IconCar size={24} />}
              title={appText("Я на линии", "Мин эштә")}
              subtitle={
                driver.online
                  ? appText("Пассажиры видят, что ты сейчас на линии", "Пассажирҙар һинең линияла икәнеңде күрә")
                  : appText("Включи, когда готов везти", "Йөрөтөргә әҙер булғас ҡабыҙ")
              }
              checked={driver.online}
              onChange={() => void toggleOnline()}
              disabled={onlineBusy}
            />
          </SettingsGroup>

          {/* «Я — женщина за рулём». Заявка, а не подтверждение: бейдж и женские
              заказы такси включает модератор по фото прав. Без этого тумблера на
              сайте фильтр «только женщина» некому было наполнять — женщина-водитель
              просто не могла о себе заявить. */}
          <SettingsGroup>
            <SettingSwitchRow
              icon={<IconProfile size={24} />}
              title={appText("Я — женщина за рулём", "Мин — рулдә ҡатын-ҡыҙ")}
              subtitle={
                driver.gender === "female"
                  ? appText(
                      "Бейдж включит модератор, сверив с фото прав. Так фильтр «только женщины» остаётся настоящим.",
                      "Билдәне модератор права фотоһы менән сағыштырып ҡабыҙа. Шулай «тик ҡатын-ҡыҙ» фильтры ысын булып ҡала."
                    )
                  : appText(
                      "По желанию: пассажирки увидят бейдж и смогут заказать такси только к женщине за рулём",
                      "Теләк буйынса: пассажир ҡатын-ҡыҙҙар билдәне күрер һәм тик ҡатын-ҡыҙ йөрөтөүсегә такси заказлай алыр"
                    )
              }
              checked={driver.gender === "female"}
              onChange={() => void toggleWoman()}
              disabled={womanBusy}
            />
          </SettingsGroup>

          {/* Денежные чаевые — по желанию. Без номера пассажир вообще не увидит
              такой возможности: телефон водителя до его согласия наружу не идёт. */}
          <TipsSbpRow />

          <div className="cab-metrics">
            <CabinetMetric label={appText("Мои маршруты", "Минең маршруттар")} value={String(activeRides.length)} />
            <CabinetMetric label={appText("Свободно", "Буш")} value={String(freeSeats)} />
            <CabinetMetric label={appText("Рейтинг", "Рейтинг")} value={ratingText} />
          </div>

          {activeRides.length === 0 ? (
            <EmptyStateCard
              icon={<IconCar size={30} />}
              title={appText("Твоих маршрутов пока нет", "Һинең маршруттар әлегә юҡ")}
              text={appText("Опубликуй поездку, чтобы пассажиры могли откликнуться.", "Пассажирҙар яуап бирһен өсөн сәфәр баҫтыр.")}
              action={appText("Опубликовать маршрут", "Маршрут баҫтырыу")}
              onAction={() => navigate("/create-ride")}
            />
          ) : (
            activeRides.slice(0, 12).map((r, i) => {
              const taken = Math.max(0, r.seats_total - r.seats_left);
              return (
                <div key={r.id} className="driver-ride">
                  <article className="my-trip-card" style={{ animationDelay: `${Math.min(i, 8) * 40}ms` }}>
                    <div className="my-trip-card__head">
                      <span className="my-trip-card__tile" aria-hidden><IconCar size={24} /></span>
                      <div className="my-trip-card__main">
                        <h3 className="my-trip-card__route">{r.from_city} → {r.to_city}</h3>
                        <span className="my-trip-card__status is-mint">
                          <IconShield size={15} /> {appText("Опубликована", "Баҫтырылды")}
                        </span>
                        <span className="my-trip-card__meta"><IconClock size={16} /> {formatWhen(r.depart_at, ru)}</span>
                        <span className="my-trip-card__meta">
                          <IconProfile size={16} />{" "}
                          {appText(`${taken} из ${r.seats_total} занято`, `${r.seats_total}-нән ${taken} банд`)} · {priceLabel(r.price, ru)}
                        </span>
                      </div>
                    </div>
                    <div className="my-trip-card__actions">
                      <button type="button" className="my-trip-card__primary" onClick={() => navigate("/boost", { state: { rideId: r.id } })}>
                        {appText("Поднять", "Күтәреү")}
                      </button>
                      <button type="button" className="my-trip-card__tonal" onClick={() => navigate("/create-ride")}>
                        {appText("Новый маршрут", "Яңы маршрут")}
                      </button>
                    </div>
                  </article>
                  {/* Управление рейсом — завершить (пассажирам «оцените») или снять (сломалась
                      машина, заболел, передумал). Раньше человеку с сайта оставалось только не приехать. */}
                  <div className="driver-ride__row">
                    <button type="button" className="driver-ride__outlined" onClick={() => finishRide(r.id)} disabled={finishing === r.id}>
                      {finishing === r.id ? appText("Завершаем…", "Тамамлайбыҙ…") : appText("Завершить рейс", "Рейсты тамамлау")}
                    </button>
                    <button type="button" className="driver-ride__outlined is-danger" onClick={() => void dropRide(r)} disabled={finishing === r.id}>
                      {appText("Снять поездку", "Сәфәрҙе алыу")}
                    </button>
                  </div>
                  {/* Исправить опечатку в цене: «300 ₽» вместо «30 ₽» жило до самого выезда. */}
                  <RideEditActions
                    ride={r}
                    editOnly
                    onChanged={(next) => setRides((prev) => prev.map((x) => (x.id === r.id ? (next ?? x) : x)))}
                  />
                  <div className="driver-ride__row">
                    <button type="button" className="driver-ride__ghost" onClick={() => void shareRide(r)}>
                      <IconShare size={18} /> {appText("Поделиться", "Бүлешеү")}
                    </button>
                  </div>
                  {/* Поездка висит без броней — сервер знает почему: нет фото, не пройдена
                      проверка, цена выше средней. Подсказка, а не упрёк: всё хорошо — блока нет. */}
                  {r.seats_left === r.seats_total && <RideTipsRow rideId={r.id} />}
                </div>
              );
            })
          )}

          {/* Брони, которые ждут ответа. Пока водитель молчит, человек сидит и не знает,
              поедет он или нет — это самое срочное, что есть в кабинете. */}
          {pending.length > 0 && (
            <>
              <SectionHeader
                title={appText("Ждут твоего ответа", "Яуабыңды көтәләр")}
                subtitle={appText("Подтверди — и пассажир увидит телефон и место встречи", "Раҫла — пассажир телефонды һәм осрашыу урынын күрер")}
              />
              {pending.map((b) => (
                <div key={b.booking_id} className="act-card">
                  <div className="act-card__title">
                    <IconRides size={18} /> {b.route}
                  </div>
                  <p className="act-card__text" style={{ margin: "6px 0 10px" }}>
                    {b.passenger_name || appText("Пассажир", "Юлаусы")}
                    {b.passenger_rating != null && ` · ★ ${b.passenger_rating.toFixed(1)}`}
                  </p>
                  <div className="act-card__actions">
                    <button
                      type="button"
                      className="btn-primary"
                      disabled={bookingBusy === b.booking_id}
                      onClick={() => void answerBooking(b.booking_id, true)}
                    >
                      {appText("Подтвердить", "Раҫлау")}
                    </button>
                    <button
                      type="button"
                      className="btn-ghost"
                      disabled={bookingBusy === b.booking_id}
                      onClick={() => void answerBooking(b.booking_id, false)}
                    >
                      {appText("Отклонить", "Кире ҡағыу")}
                    </button>
                  </div>
                </div>
              ))}
              {bookingNote && <p className="taxi-note">{bookingNote}</p>}
            </>
          )}
          {finishNote && <p className="taxi-note">{finishNote}</p>}

          {/* Кто едет со мной: подтвердить бронь, отметить неявку, поставить оценку. */}
          <DriverPassengers />

          {/* ⭐ Приоритет: кому заказ падает первым и за что. Показываем целиком —
              скрытый приоритет человек читает как «заказы раздают по блату». */}
          <PriorityCard />

          <SectionHeader title={appText("Архив", "Архив")} subtitle={appText("Что уже проехал", "Нимә үтелгән")} />
          <div className="cab-metrics">
            <CabinetMetric label={appText("Рейсов сделано", "Рейс эшләнде")} value={String(doneRides.length)} />
            <CabinetMetric label={appText("Пассажиров отвезено", "Пассажир йөрөтөлдө")} value={String(passengersServed)} />
          </div>
          {archive.length === 0 ? (
            <EmptyStateCard
              icon={<IconCheck size={30} />}
              title={appText("Архив пока пуст", "Архив әлегә буш")}
              text={appText("Завершённые и отменённые рейсы будут здесь.", "Тамамланған һәм кире ҡағылған рейстар бында булыр.")}
            />
          ) : (
            <div className="archive-list">
              {archive.slice(0, 12).map((r) => (
                <ArchiveRideRow
                  key={r.id}
                  from={r.from_city}
                  to={r.to_city}
                  when={formatWhen(r.depart_at, ru)}
                  status={r.status ?? "done"}
                  price={r.price}
                />
              ))}
            </div>
          )}

          {/* Регулярные маршруты */}
          <ScheduleSection schedules={schedules} onChange={setSchedules} wd={ru ? WD_RU : WD_BA} />

          <SettingsGroup>
            <SettingsNavRow
              icon={<IconTrend size={24} />}
              title={appText("Мой заработок", "Минең табыш")}
              subtitle={appText("Заработок по неделям, месяцам и дням", "Аҙна, ай һәм көн буйынса табыш")}
              onClick={() => navigate("/earnings")}
            />
            <SettingsNavRow
              icon={<IconReceipt size={24} />}
              title={appText("Мои поездки такси", "Такси сәфәрҙәрем")}
              subtitle={appText("Цена, комиссия и сколько осталось тебе", "Хаҡ, комиссия һәм һиңә күпме ҡалды")}
              onClick={() => navigate("/taxi-rides")}
            />
            <SettingsNavRow
              icon={<IconWallet size={24} />}
              title={appText("Кошелёк", "Янсыҡ")}
              subtitle={appText("Баланс и история операций", "Баланс һәм операциялар тарихы")}
              onClick={() => navigate("/wallet")}
            />
          </SettingsGroup>

          <SettingsGroup>
            <SettingsNavRow
              icon={<IconRequest size={24} />}
              title={appText("Заявки пассажиров", "Пассажир заявкалары")}
              subtitle={appText("Откликнуться и предложить поездку", "Яуап биреп сәфәр тәҡдим итеү")}
              onClick={() => navigate("/requests-feed")}
            />
            <SettingsNavRow
              icon={<IconRequest size={24} />}
              title={appText("Мои отклики", "Минең яуаптарым")}
              subtitle={appText("Торг о цене: принять встречную или предложить свою", "Хаҡ буйынса һатыулашыу: ҡаршы хаҡты ҡабул итеү йәки үҙеңдекен тәҡдим итеү")}
              onClick={() => navigate("/my-responses")}
            />
            <SettingsNavRow
              icon={<IconRides size={24} />}
              title={appText("Создать поездку", "Сәфәр булдырыу")}
              subtitle={appText("Маршрут, места, цена и время", "Маршрут, урын, хаҡ һәм ваҡыт")}
              onClick={() => navigate("/create-ride")}
            />
            <SettingsNavRow
              icon={<IconIdCard size={24} />}
              title={appText("Проверка водителя", "Йөрөтөүсене тикшереү")}
              subtitle={appText("Права, машина, фото и госномер", "Права, машина, фото һәм номер")}
              onClick={() => navigate("/verify-driver")}
            />
            <SettingsNavRow
              icon={<IconShield size={24} />}
              title={appText("Документы и сроки", "Документтар һәм ваҡыттар")}
              subtitle={appText("ОСАГО, разрешение, техосмотр — продлить без новой заявки", "ОСАГО, рөхсәт, техник ҡарау — яңы заявкаһыҙ оҙайтыу")}
              onClick={() => navigate("/taxi-docs")}
            />
            <SettingsNavRow
              icon={<IconCheck size={24} />}
              title={appText("Готовность к работе", "Эшкә әҙерлек")}
              subtitle={appText("Отметить перед выходом на линию: самочувствие, машина", "Линияға сығыр алдынан билдәләү: һаулыҡ, машина")}
              onClick={() => navigate("/pretrip")}
            />
            <SettingsNavRow
              icon={<IconRocket size={24} />}
              title={appText("Поднять маршрут", "Маршрутты күтәреү")}
              subtitle={appText("Показать выше в списке поездок", "Сәфәрҙәр исемлегендә өҫтәрәк күрһәтеү")}
              onClick={() => navigate("/boost")}
            />
          </SettingsGroup>

          {/* Есть только в вебе: линия такси, заявка таксиста, классы машины. Той же группой,
              чтобы дороги не пропали при выравнивании с приложением. */}
          <SettingsGroup>
            <SettingsNavRow
              icon={<IconCar size={24} />}
              title={appText("Я на линии (такси)", "Мин линияла (такси)")}
              subtitle={appText("Принимать быстрые заказы", "Тиҙ заказдар ҡабул итеү")}
              onClick={() => navigate("/taxi-drive")}
            />
            <SettingsNavRow
              icon={<IconRides size={24} />}
              title={appText("Стать таксистом Юлдаша", "Юлдаш таксисы булыу")}
              subtitle={appText("Заявка по 580-ФЗ: разрешение и документы", "580-ФЗ буйынса заявка: рөхсәт һәм документтар")}
              onClick={() => navigate("/taxi-onboarding")}
            />
            <SettingsNavRow
              icon={<IconCar size={24} />}
              title={appText("Что я вожу", "Нимә йөрөтәм")}
              subtitle={appText("Класс машины и опции салона", "Машина класы һәм салон опциялары")}
              onClick={() => navigate("/taxi-classes")}
            />
          </SettingsGroup>
        </div>
      )}
    </>
  );
}

// ----------------------------- Регулярные маршруты -----------------------------
function ScheduleSection({
  schedules,
  onChange,
  wd,
}: {
  schedules: DriverSchedule[];
  onChange: (s: DriverSchedule[]) => void;
  wd: string[];
}) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [days, setDays] = useState<Set<number>>(new Set());
  const [time, setTime] = useState("08:00");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canAdd = from.trim() && to.trim() && days.size > 0 && time && !busy;

  async function add() {
    if (!canAdd) return;
    setBusy(true);
    setError(null);
    try {
      const row = await createSchedule({
        from_city: from.trim(),
        to_city: to.trim(),
        weekdays: [...days].sort((a, b) => a - b).join(","),
        time,
      });
      onChange([row, ...schedules]);
      setFrom("");
      setTo("");
      setDays(new Set());
      setTime("08:00");
      setOpen(false);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить.", "Һаҡларға булманы.") // DRAFT
      );
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: number) {
    const prev = schedules;
    onChange(schedules.filter((s) => s.id !== id)); // оптимистично
    try {
      await deleteSchedule(id);
    } catch {
      onChange(prev); // откат
    }
  }

  function daysLabel(csv: string): string {
    return csv
      .split(",")
      .map((d) => wd[parseInt(d, 10)] ?? "")
      .filter(Boolean)
      .join(" ");
  }

  return (
    <>
      <SectionHeader
        title={appText("Регулярные маршруты", "Даими маршруттар")}
        subtitle={appText("Ездишь по расписанию — пассажиры подпишутся заранее", "Расписание буйынса йөрөһәң — пассажирҙар алдан яҙылыр")}
      />

      {schedules.length > 0 && (
        <div className="list">
          {schedules.map((s) => (
            <div key={s.id} className="list-row">
              <div className="list-row__main">
                <div className="repeat-route">
                  <span>{s.from_city}</span>
                  <span className="repeat-route__arrow"><IconArrow size={18} /></span>
                  <span>{s.to_city}</span>
                </div>
                <div className="list-row__sub">
                  {daysLabel(s.weekdays)} · {s.time}
                </div>
              </div>
              <button
                type="button"
                className="icon-btn"
                onClick={() => remove(s.id)}
                aria-label={appText("Удалить маршрут", "Маршрутты бетереү")}
              >
                <IconTrash size={20} />
              </button>
            </div>
          ))}
        </div>
      )}

      {!open ? (
        <button type="button" className="btn-soft" style={{ marginTop: 12 }} onClick={() => setOpen(true)}>
          <IconCalendar size={18} /> {appText("Добавить регулярный маршрут", "Даими маршрут өҫтәү")}
        </button>
      ) : (
        <div className="more-body" style={{ marginTop: 12 }}>
          <div className="field-row">
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("Откуда", "Ҡайҙан")}</span>
              <input className="field__input" value={from} onChange={(e) => setFrom(e.target.value)} autoComplete="off" />
            </label>
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("Куда", "Ҡайҙа")}</span>
              <input className="field__input" value={to} onChange={(e) => setTo(e.target.value)} autoComplete="off" />
            </label>
          </div>

          <span className="field__label" style={{ marginTop: 10 }}>{appText("Дни недели", "Аҙна көндәре")}</span>
          <div className="weekday-row">
            {[1, 2, 3, 4, 5, 6, 7].map((d) => (
              <button
                key={d}
                type="button"
                className={"weekday-chip" + (days.has(d) ? " is-active" : "")}
                onClick={() =>
                  setDays((prev) => {
                    const n = new Set(prev);
                    n.has(d) ? n.delete(d) : n.add(d);
                    return n;
                  })
                }
              >
                {wd[d]}
              </button>
            ))}
          </div>

          <label className="field" style={{ marginTop: 10 }}>
            <span className="field__label">{appText("Время выезда", "Сығыу ваҡыты")}</span>
            <input className="field__input" type="time" value={time} onChange={(e) => setTime(e.target.value)} />
          </label>

          {error && <div className="auth__error">{error}</div>}

          <div className="field-row" style={{ marginTop: 12 }}>
            <button type="button" className="btn-ghost" style={{ flex: 1 }} onClick={() => setOpen(false)}>
              {appText("Отмена", "Баш тартыу")}
            </button>
            <button type="button" className="btn-primary" style={{ flex: 1 }} onClick={add} disabled={!canAdd}>
              {busy ? (
                appText("Сохраняем…", "Һаҡлайбыҙ…")
              ) : (
                <>
                  <IconCheck size={18} /> {appText("Добавить", "Өҫтәргә")}
                </>
              )}
            </button>
          </div>
        </div>
      )}
    </>
  );
}

// ----------------------------- Денежные чаевые (по желанию) -----------------------------
/**
 * Водитель оставляет номер СБП — и только тогда пассажир после поездки увидит
 * возможность поблагодарить деньгами. Без номера этой кнопки у пассажира нет
 * вовсе: телефон водителя до его согласия наружу не идёт.
 *
 * Выключить так же просто: пустое поле — и номер стирается.
 */
function TipsSbpRow() {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [sbp, setSbp] = useState("");
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const r = await setTipsSbp(sbp.trim());
      setSbp(r.tips_sbp);
      setSaved(true);
      window.setTimeout(() => setSaved(false), 2000);
      if (!r.accepting) setOpen(false);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить. Проверь номер.", "Һаҡлап булманы. Номерҙы тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <SettingsGroup>
      <SettingSwitchRow
        icon={<IconWallet size={24} />}
        title={appText("Принимать чаевые", "Сәйлек алыу")}
        subtitle={
          open
            ? appText("Оставь номер СБП — пассажир сможет поблагодарить деньгами.", "СБП номерын ҡалдыр — юлаусы аҡса менән рәхмәт әйтә алыр.")
            : appText("По желанию. Номер увидит только пассажир после поездки.", "Теләк буйынса. Номерҙы тик юлаусы сәфәрҙән һуң күрә.")
        }
        checked={open}
        onChange={setOpen}
      />
      {open && (
        <div className="settings-group__body">
          <label className="field dl-field">
            <span className="field__label">{appText("Номер СБП", "СБП номеры")}</span>
            <input
              className="field__input"
              type="tel"
              autoComplete="tel"
              value={sbp}
              onChange={(e) => setSbp(e.target.value)}
              placeholder="+7"
            />
            <span className="field__hint">
              {appText(
                "Номер увидит только пассажир и только после завершённой поездки. Пустое поле — выключить.",
                "Номерҙы тик юлаусы, тик тамамланған сәфәрҙән һуң күрә. Буш ҡыр — һүндереү."
              )}
            </span>
          </label>
          {error && <div className="auth__error">{error}</div>}
          <button type="button" className="btn-soft" onClick={() => void save()} disabled={busy}>
            {saved ? <IconCheck size={18} /> : null}
            {busy ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить номер", "Номерҙы һаҡлау")}
          </button>
        </div>
      )}
    </SettingsGroup>
  );
}

// ----------------------------- «Как получить больше заявок» -----------------------------
/**
 * Поездка опубликована, а броней нет. Причина обычно бытовая: нет фото профиля,
 * не пройдена проверка, цена выше средней по маршруту, нет пары слов о поездке.
 *
 * Сервер это считает и отдаёт добрыми словами. Показываем только когда есть что
 * сказать: «всё хорошо» отдельной плашкой не пишем — она быстро перестаёт читаться.
 */
function RideTipsRow({ rideId }: { rideId: number }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [tips, setTips] = useState<RideTips | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const ac = new AbortController();
    fetchRideTips(rideId, ac.signal)
      .then(setTips)
      .catch(() => setTips(null)); // 404 / нет ручки → блока нет
    return () => ac.abort();
  }, [rideId]);

  if (!tips || tips.all_good || tips.tips.length === 0) return null;

  return (
    <div className="ride-tips">
      <button type="button" className="ride-tips__head" onClick={() => setOpen((v) => !v)}>
        {appText(
          `Как получить больше заявок · ${tips.tips.length}`,
          `Күберәк заявка алыу юлы · ${tips.tips.length}`
        )}
      </button>
      {open && (
        <ul className="ride-tips__list">
          {tips.tips.map((t) => (
            <li key={t.code}>{ru ? t.ru : t.ba}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
