// ================================================================
//  Публичный профиль водителя (GET /drivers/{id}/public). ПУБЛИЧНО.
//  Фото, бейдж «Проверен», стаж, число поездок, средний рейтинг,
//  последние отзывы (без телефона). Открывается тапом с карточки
//  поездки (из RideSheet). Плюс публичные регулярные маршруты.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { useAuth } from "../auth/AuthProvider";
import { fetchDriverPublic, type DriverPublic } from "../api/driver";
import { apiGet } from "../api/client";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconCalendar, IconFlag } from "../components/Icons";
import { YuStar } from "../components/BrandIcons";

type Status = "loading" | "error" | "ready";

interface PublicSchedule {
  id: number;
  from_city: string;
  to_city: string;
  weekdays: string;
  time: string;
  comment: string;
}

function initials(name: string): string {
  const p = name.trim().split(/\s+/).filter(Boolean);
  return p.length ? (p[0][0] + (p[1]?.[0] ?? "")).toUpperCase() : "?";
}

/** Стаж в Юлдаше: дни → «X лет / X мес / X дн», двуязычно. */
function serviceLabel(days: number, ru: boolean): string {
  if (days >= 365) {
    const y = Math.floor(days / 365);
    return ru ? `${y} ${plural(y, "год", "года", "лет")} в Юлдаше` : `Юлдашта ${y} йыл`;
  }
  if (days >= 30) {
    const m = Math.floor(days / 30);
    return ru ? `${m} ${plural(m, "месяц", "месяца", "месяцев")} в Юлдаше` : `Юлдашта ${m} ай`;
  }
  return ru ? `${days} ${plural(days, "день", "дня", "дней")} в Юлдаше` : `Юлдашта ${days} көн`;
}

function plural(n: number, one: string, few: string, many: string): string {
  const m10 = n % 10;
  const m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) return few;
  return many;
}

function timeAgo(iso: string, ru: boolean): string {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  return d.toLocaleDateString(ru ? "ru-RU" : "ru-RU", { day: "numeric", month: "short", year: "numeric" });
}

export default function DriverProfileScreen() {
  const { appText, lang } = useLang();
  const { isAuthed } = useAuth();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const driverId = Number(id);

  const [status, setStatus] = useState<Status>("loading");
  const [driver, setDriver] = useState<DriverPublic | null>(null);
  const [schedules, setSchedules] = useState<PublicSchedule[]>([]);

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!driverId) {
        setStatus("error");
        return;
      }
      setStatus("loading");
      fetchDriverPublic(driverId, signal)
        .then((d) => {
          setDriver(d);
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus("error");
        });
      // Публичные регулярные маршруты (мягко: нет на проде до релиза → пусто).
      apiGet<PublicSchedule[]>(`/drivers/${driverId}/schedule`, { auth: false, signal })
        .then(setSchedules)
        .catch(() => setSchedules([]));
    },
    [driverId]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const WD = ru
    ? ["", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]
    : ["", "Дш", "Сш", "Шр", "Кс", "Йм", "Шб", "Йк"];
  const daysLabel = (csv: string) =>
    csv.split(",").map((d) => WD[parseInt(d, 10)] ?? "").filter(Boolean).join(" ");

  return (
    <>
      <SubHeader
        title={appText("Профиль водителя", "Водитель профиле")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && driver && (
        <>
          <div className="dprofile-hero">
            <div className="dprofile-avatar">
              {driver.avatar_url ? (
                <img src={driver.avatar_url} alt="" />
              ) : (
                <span>{initials(driver.name)}</span>
              )}
            </div>
            <div className="dprofile-name">{driver.name}</div>
            {driver.verified && (
              <span className="badge badge--mint" style={{ marginTop: 6 }}>
                ✓ {appText("Проверен", "Тикшерелгән")}
              </span>
            )}
            <div className="dprofile-meta">
              {driver.rating != null ? (
                <span className="dprofile-rating">
                  <YuStar size={16} className="star" /> {driver.rating.toFixed(1)}
                  <span className="dprofile-rating__count">
                    {" "}
                    · {appText(`${driver.rating_count} оценок`, `${driver.rating_count} баһа`)}
                  </span>
                </span>
              ) : (
                <span className="dprofile-rating__count">
                  {appText("Пока нет оценок", "Әле баһа юҡ")}
                </span>
              )}
            </div>
            {driver.car && <div className="dprofile-car">{driver.car}</div>}
          </div>

          {/* Метрики доверия */}
          <div className="stat-grid" style={{ marginTop: 16 }}>
            <div className="stat-tile">
              <b>{driver.trips_count}</b>
              <span>{appText("поездок", "сәфәр")}</span>
            </div>
            <div className="stat-tile">
              <b>{driver.rating != null ? driver.rating.toFixed(1) : "—"}</b>
              <span>{appText("рейтинг", "рейтинг")}</span>
            </div>
          </div>

          <p className="dprofile-since">{serviceLabel(driver.days_in_service, ru)}</p>

          {/* Регулярные маршруты */}
          {schedules.length > 0 && (
            <>
              <h2 className="section-title" style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <IconCalendar size={20} /> {appText("Ездит регулярно", "Даими йөрөй")}
              </h2>
              <div className="list">
                {schedules.map((s) => (
                  <div key={s.id} className="list-row">
                    <div className="list-row__main">
                      <div className="repeat-route">
                        <span>{s.from_city}</span>
                        <span className="repeat-route__arrow"><IconArrow size={18} /></span>
                        <span>{s.to_city}</span>
                      </div>
                      <div className="list-row__sub">{daysLabel(s.weekdays)} · {s.time}</div>
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}

          {/* Отзывы */}
          <h2 className="section-title">{appText("Отзывы", "Кире бәйләнеш")}</h2>
          {driver.reviews.length === 0 ? (
            <div className="state" style={{ paddingTop: 12 }}>
              <div className="state__emoji">💬</div>
              <p>
                {appText(
                  "Пока нет отзывов. Проедьте вместе — и оставьте первый.",
                  "Әле кире бәйләнеш юҡ. Бергә барығыҙ — беренсеһен ҡалдырығыҙ."
                )}
              </p>
            </div>
          ) : (
            <div className="review-list">
              {driver.reviews.map((r, i) => (
                <div key={i} className="review-card">
                  <div className="review-card__head">
                    <span className="review-card__author">{r.author}</span>
                    <span className="review-card__stars" aria-label={`${r.stars}/5`}>
                      {Array.from({ length: r.stars }).map((_, k) => (
                        <YuStar key={k} size={14} className="amenity-ic" />
                      ))}
                      <span className="review-card__stars-off">
                        {Array.from({ length: 5 - r.stars }).map((_, k) => (
                          <YuStar key={k} size={14} className="amenity-ic" />
                        ))}
                      </span>
                    </span>
                  </div>
                  {r.text && <p className="review-card__text">{r.text}</p>}
                  <div className="review-card__date">{timeAgo(r.created_at, ru)}</div>
                </div>
              ))}
            </div>
          )}

          <p className="receipt__foot">
            {appText(
              "Телефон и точное место — только у участников подтверждённой поездки.",
              "Телефон һәм теүәл урын — тик раҫланған сәфәр ҡатнашыусыларында."
            )}
          </p>

          {/* Пожаловаться на водителя (только со входом; гостю — мягко на вход) */}
          <button
            type="button"
            className="report-link"
            onClick={() =>
              isAuthed
                ? navigate(`/report?user=${driverId}&name=${encodeURIComponent(driver.name)}`)
                : navigate("/login")
            }
          >
            <IconFlag size={17} />
            {appText("Пожаловаться на водителя", "Водителгә зарланырға")}
          </button>
        </>
      )}
    </>
  );
}
