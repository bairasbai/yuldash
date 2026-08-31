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
  completeRide,
  type DriverStatus,
  type DriverSchedule,
} from "../api/driver";
import type { Ride } from "../api/rides";
import { LoadingList, ErrorState } from "../components/States";
import { StatusPill } from "../components/StatusPill";
import RideEditActions from "../components/RideEditActions";
import DriverPassengers from "../components/DriverPassengers";
import ScreenHeader from "../components/ScreenHeader";
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
  const passengers = useMemo(
    () => rides.reduce((sum, r) => sum + Math.max(0, r.seats_total - r.seats_left), 0),
    [rides]
  );

  return (
    <>
      <ScreenHeader
        title={appText("Кабинет водителя", "Йөрөтөүсе кабинеты")}
        subtitle={appText("Поездки, заявки и заработок", "Сәфәрҙәр, заявкалар һәм табыш")}
      />

      {status === "loading" && <LoadingList count={2} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && driver && (
        <>
          {/* На линии */}
          <button
            type="button"
            className={"onb__simple" + (driver.online ? " is-active" : "")}
            onClick={toggleOnline}
            disabled={onlineBusy}
          >
            <span className={"status-dot" + (driver.online ? " status-dot--on" : "")} aria-hidden />
            <span className="onb__simple-text">
              <b>{appText("Я на линии", "Мин линияла")}</b>
              <span>
                {driver.online
                  ? appText("Пассажиры видят тебя как свободного", "Юлаусылар һине буш итеп күрә")
                  : appText("Включи, когда готов везти", "Йөрөтөргә әҙер булғас ҡабыҙ")}
              </span>
            </span>
            <span className={"switch" + (driver.online ? " on" : "")} />
          </button>

          {/* «Я — женщина за рулём». Заявка, а не подтверждение: бейдж и женские
              заказы такси включает модератор по фото прав. Без этого тумблера на
              сайте фильтр «только женщина» некому было наполнять — женщина-водитель
              просто не могла о себе заявить. */}
          <button
            type="button"
            className={"onb__simple" + (driver.gender === "female" ? " is-active" : "")}
            style={{ marginTop: 10 }}
            onClick={() => void toggleWoman()}
            disabled={womanBusy}
          >
            <span className="onb__simple-text">
              <b>{appText("Я — женщина за рулём", "Мин — рулдә ҡатын-ҡыҙ")}</b>
              <span>
                {appText(
                  "По желанию: пассажирки увидят бейдж и смогут заказать такси только к женщине за рулём",
                  "Теләк буйынса: пассажир ҡатын-ҡыҙҙар билдәне күрер һәм тик ҡатын-ҡыҙ йөрөтөүсегә такси заказлай алыр"
                )}
              </span>
            </span>
            <span className={"switch" + (driver.gender === "female" ? " on" : "")} />
          </button>
          {driver.gender === "female" && (
            <p className="verify-hint">
              {appText(
                "Бейдж включит модератор, сверив с фото прав. Так фильтр «только женщины» остаётся настоящим.",
                "Билдәне модератор права фотоһы менән сағыштырып ҡабыҙа. Шулай «тик ҡатын-ҡыҙ» фильтры ысын булып ҡала."
              )}
            </p>
          )}

          {/* Денежные чаевые — по желанию. Без номера пассажир вообще не увидит
              такой возможности: телефон водителя до его согласия наружу не идёт. */}
          <TipsSbpRow />

          <VerifyBanner docs={driver.docs_status} />

          {/* Опубликовать поездку — главный CTA */}
          <button
            type="button"
            className="btn-primary submit-btn"
            style={{ marginTop: 16 }}
            onClick={() => navigate("/create-ride")}
          >
            {appText("Опубликовать поездку", "Сәфәр баҫтырырға")}
          </button>

          {/* Счётчики */}
          <div className="stat-grid" style={{ marginTop: 16 }}>
            <div className="stat-tile">
              <b>{activeRides.length}</b>
              <span>{appText("активных рейсов", "әүҙем рейс")}</span>
            </div>
            <div className="stat-tile">
              <b>{passengers}</b>
              <span>{appText("пассажиров", "юлаусы")}</span>
            </div>
          </div>

          {/* ⭐ Приоритет: кому заказ падает первым и за что. Показываем целиком —
              скрытый приоритет человек читает как «заказы раздают по блату». */}
          <div style={{ marginTop: 16 }}>
            <PriorityCard />
          </div>

          {/* Быстрый доступ */}
          <div className="cabinet-grid" style={{ marginTop: 16 }}>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/requests-feed")}>
              <span className="cabinet-tile__icon"><IconRequest size={22} /></span>
              <span className="cabinet-tile__title">{appText("Заявки пассажиров", "Пассажир заявкалары")}</span>
            </button>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/earnings")}>
              <span className="cabinet-tile__icon"><IconWallet size={22} /></span>
              <span className="cabinet-tile__title">{appText("Мой заработок", "Минең табыш")}</span>
            </button>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/boost")}>
              <span className="cabinet-tile__icon"><IconRocket size={22} /></span>
              <span className="cabinet-tile__title">{appText("Поднять поездку", "Сәфәр күтәреү")}</span>
            </button>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/taxi-drive")}>
              <span className="cabinet-tile__icon"><IconCar size={22} /></span>
              <span className="cabinet-tile__title">{appText("Я на линии (такси)", "Мин линияла (такси)")}</span>
            </button>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/taxi-onboarding")}>
              <span className="cabinet-tile__icon"><IconRides size={22} /></span>
              <span className="cabinet-tile__title">{appText("Стать таксистом Юлдаша", "Юлдаш таксисы булыу")}</span>
            </button>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/my-responses")}>
              <span className="cabinet-tile__icon"><IconRequest size={22} /></span>
              <span className="cabinet-tile__title">{appText("Мои отклики", "Минең яуаптарым")}</span>
            </button>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/taxi-rides")}>
              <span className="cabinet-tile__icon"><IconReceipt size={22} /></span>
              <span className="cabinet-tile__title">{appText("Мои поездки такси", "Такси сәфәрҙәрем")}</span>
            </button>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/taxi-docs")}>
              <span className="cabinet-tile__icon"><IconIdCard size={22} /></span>
              <span className="cabinet-tile__title">{appText("Документы и сроки", "Документтар һәм ваҡыттар")}</span>
            </button>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/pretrip")}>
              <span className="cabinet-tile__icon"><IconShield size={22} /></span>
              <span className="cabinet-tile__title">{appText("Готовность к работе", "Эшкә әҙерлек")}</span>
            </button>
            {/* Классы машины и опции салона: видно, чего не хватает до Комфорта,
                и можно включить кресло, не пере-подавая заявку. */}
            <button type="button" className="cabinet-tile" onClick={() => navigate("/taxi-classes")}>
              <span className="cabinet-tile__icon"><IconCar size={22} /></span>
              <span className="cabinet-tile__title">{appText("Что я вожу", "Нимә йөрөтәм")}</span>
            </button>
          </div>

          {/* Регулярные маршруты */}
          <ScheduleSection
            schedules={schedules}
            onChange={setSchedules}
            wd={ru ? WD_RU : WD_BA}
          />

{finishNote && <p className="taxi-note">{finishNote}</p>}

          {/* Кто едет со мной: подтвердить бронь, отметить неявку, поставить оценку.
              До подтверждения пассажир не видит ни телефона, ни точки сбора — значит
              кнопка «Подтвердить» это не формальность, а то, с чего начинается поездка. */}
          <DriverPassengers />

                    {/* Мои поездки / Архив */}
          <h2 className="section-title">{appText("Мои поездки", "Сәфәрҙәрем")}</h2>
          {rides.length === 0 ? (
            <div className="state" style={{ paddingTop: 12 }}>
              <div className="state__icon">
                <IconCar size={34} />
              </div>
              <h2>{appText("Пока нет поездок", "Әле сәфәрҙәр юҡ")}</h2>
              <p>
                {appText(
                  "Опубликуй первую поездку — пассажиры увидят её в ленте и на карте.",
                  "Беренсе сәфәреңде баҫтыр — юлаусылар таҫмала һәм картала күрер."
                )}
              </p>
            </div>
          ) : (
            <div className="list">
              {rides.slice(0, 12).map((r) => {
                const st = r.status;
                const pill =
                  st === "done" ? "done" : st === "cancelled" ? "cancelled" : "confirmed";
                return (
                  <div key={r.id} className="list-row">
                    <div className="list-row__main">
                      <div className="repeat-route">
                        <span>{r.from_city}</span>
                        <span className="repeat-route__arrow"><IconArrow size={18} /></span>
                        <span>{r.to_city}</span>
                      </div>
                      <div className="list-row__sub">
                        {formatWhen(r.depart_at, ru)} · {priceLabel(r.price, ru)} ·{" "}
                        {appText(
                          `${Math.max(0, r.seats_total - r.seats_left)} из ${r.seats_total}`,
                          `${r.seats_total}-нән ${Math.max(0, r.seats_total - r.seats_left)}`
                        )}{" "}
                        {appText("занято", "банд")}
                      </div>
                    </div>
                    <StatusPill status={pill as never} />
                    {/* Поездка едет — её ещё можно показать людям: поднять в ленте
                        или скинуть ссылку в соседский чат. После рейса обе кнопки
                        бессмысленны, поэтому их там нет. */}
                    {st !== "done" && st !== "cancelled" && (
                      <>
                        <button
                          type="button"
                          className="icon-btn"
                          aria-label={appText("Поделиться поездкой", "Сәфәр менән бүлешеү")}
                          title={appText("Поделиться поездкой", "Сәфәр менән бүлешеү")}
                          onClick={() => void shareRide(r)}
                        >
                          <IconShare size={18} />
                        </button>
                        <button
                          type="button"
                          className="icon-btn"
                          aria-label={appText("Поднять объявление", "Иғланды өҫкә күтәреү")}
                          title={appText("Поднять объявление", "Иғланды өҫкә күтәреү")}
                          onClick={() => navigate("/boost", { state: { rideId: r.id } })}
                        >
                          <IconTrend size={18} />
                        </button>
                      </>
                    )}
                    {/* Завершить рейс целиком: пассажиры часто забывают нажать «Завершить»,
                        и тогда места висят занятыми, а поездка — в активных. */}
                    {st !== "done" && st !== "cancelled" && (
                      <button
                        type="button"
                        className="btn-soft btn-soft--sm"
                        onClick={() => finishRide(r.id)}
                        disabled={finishing === r.id}
                      >
                        {finishing === r.id
                          ? appText("Завершаем…", "Тамамлайбыҙ…")
                          : appText("Завершить", "Тамамлау")}
                      </button>
                    )}

                    {/* Исправить опечатку в цене и снять рейс. Раньше ни того, ни другого
                        в вебе не было: «300 ₽» вместо «30 ₽» жило до самого выезда, а
                        сломавшийся водитель просто не приезжал. */}
                    {st !== "done" && st !== "cancelled" && (
                      <RideEditActions
                        ride={r}
                        onChanged={(next) =>
                          setRides((prev) =>
                            prev.map((x) => (x.id === r.id ? (next ?? x) : x))
                          )
                        }
                      />
                    )}

                    {/* Поездка висит без броней — сервер знает почему: нет фото,
                        не пройдена проверка, цена выше средней, нет описания.
                        Это подсказка, а не упрёк: всё хорошо — блока просто нет. */}
                    {st !== "done" && st !== "cancelled" && r.seats_left === r.seats_total && (
                      <RideTipsRow rideId={r.id} />
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </>
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
      <h2 className="section-title" style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <IconCalendar size={20} /> {appText("Регулярные маршруты", "Даими маршруттар")}
      </h2>

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
    <div className="tips-sbp">
      <button type="button" className="tips-sbp__head" onClick={() => setOpen((v) => !v)}>
        <span>
          <b>{appText("Чаевые от пассажиров", "Юлаусыларҙан рәхмәт аҡсаһы")}</b>
          <span>
            {appText(
              "По желанию. Оставь номер СБП — пассажир сможет поблагодарить деньгами.",
              "Теләк буйынса. СБП номерын ҡалдыр — юлаусы аҡса менән рәхмәт әйтә алыр."
            )}
          </span>
        </span>
        <span className="workzone__action">
          {saved ? <IconCheck size={18} /> : open ? appText("Скрыть", "Йәшереү") : appText("Настроить", "Көйләү")}
        </span>
      </button>

      {open && (
        <div className="tips-sbp__body">
          <label className="field">
            <span className="field__label">{appText("Номер для СБП", "СБП өсөн номер")}</span>
            <input
              className="field__input"
              type="tel"
              autoComplete="tel"
              value={sbp}
              onChange={(e) => setSbp(e.target.value)}
              placeholder="+7 917 000-00-00"
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
            {busy ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить", "Һаҡлау")}
          </button>
        </div>
      )}
    </div>
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
