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
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchDriverStatus,
  setDriverOnline,
  fetchDriverRides,
  fetchMySchedules,
  createSchedule,
  deleteSchedule,
  type DriverStatus,
  type DriverSchedule,
} from "../api/driver";
import type { Ride } from "../api/rides";
import { LoadingList, ErrorState } from "../components/States";
import { StatusPill } from "../components/StatusPill";
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
        <IconCheck size={16} /> {appText("Ты проверенный водитель", "Һин тикшерелгән водитель")}
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
  const [rides, setRides] = useState<Ride[]>([]);
  const [schedules, setSchedules] = useState<DriverSchedule[]>([]);
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
        title={appText("Кабинет водителя", "Водитель кабинеты")}
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

          {/* Быстрый доступ */}
          <div className="cabinet-grid" style={{ marginTop: 16 }}>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/requests-feed")}>
              <span className="cabinet-tile__icon"><IconRequest size={22} /></span>
              <span className="cabinet-tile__title">{appText("Заявки пассажиров", "Юлаусы заявкалары")}</span>
            </button>
            <button type="button" className="cabinet-tile" onClick={() => navigate("/earnings")}>
              <span className="cabinet-tile__icon"><IconWallet size={22} /></span>
              <span className="cabinet-tile__title">{appText("Мой заработок", "Табышым")}</span>
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
              <span className="cabinet-tile__title">{appText("Стать таксистом", "Таксист булыу")}</span>
            </button>
          </div>

          {/* Регулярные маршруты */}
          <ScheduleSection
            schedules={schedules}
            onChange={setSchedules}
            wd={ru ? WD_RU : WD_BA}
          />

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
                  <IconCheck size={18} /> {appText("Добавить", "Өҫтәү")}
                </>
              )}
            </button>
          </div>
        </div>
      )}
    </>
  );
}
