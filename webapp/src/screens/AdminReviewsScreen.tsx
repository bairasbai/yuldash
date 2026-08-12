// ================================================================
//  Модерация отзывов → /admin/reviews (RequireAdmin).
//  Две вкладки:
//   • «О поездках» — текстовые отзывы водителей/пассажиров (Rating.text):
//       GET /admin/ratings/pending, POST /admin/ratings/{id}/publish {published}
//   • «О приложении» — отзывы для лендинга (AppReview):
//       GET /admin/reviews/pending, POST /admin/reviews/{id}/publish {published}
//  Опубликовать = одобрить к показу. Двуязычно, все состояния, тач-цели ≥48px.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchPendingRatings,
  publishRating,
  excludeRating,
  fetchPendingAppReviews,
  publishAppReview,
  type PendingRating,
  type PendingAppReview,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { formatRelative } from "../utils/format";
import { IconCheck, IconShield } from "../components/Icons";
import { YuStar } from "../components/BrandIcons";

type State = "loading" | "error" | "ready";
type Tab = "rides" | "app";

/** Звёзды строкой (доступно и без картинок). */
function Stars({ n }: { n: number }) {
  const { appText } = useLang();
  const c = Math.max(0, Math.min(5, n));
  return (
    <span className="review-stars" aria-label={appText(`${c} из 5`, `5-тән ${c}`)}>
      {Array.from({ length: c }).map((_, k) => (
        <YuStar key={k} size={15} className="amenity-ic" />
      ))}
      <span className="review-stars__dim">
        {Array.from({ length: 5 - c }).map((_, k) => (
          <YuStar key={k} size={15} className="amenity-ic" />
        ))}
      </span>
    </span>
  );
}

export default function AdminReviewsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [tab, setTab] = useState<Tab>("rides");
  const [state, setState] = useState<State>("loading");
  const [rides, setRides] = useState<PendingRating[]>([]);
  const [apps, setApps] = useState<PendingAppReview[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState<{ id: number; msg: string } | null>(null);

  const load = useCallback((t: Tab, signal?: AbortSignal) => {
    setState("loading");
    const req = t === "rides" ? fetchPendingRatings(signal) : fetchPendingAppReviews(signal);
    req
      .then((list) => {
        if (t === "rides") setRides(list as PendingRating[]);
        else setApps(list as PendingAppReview[]);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(tab, ac.signal);
    return () => ac.abort();
  }, [tab, load]);

  async function publish(id: number, isApp: boolean) {
    if (busyId) return;
    setBusyId(id);
    setRowError(null);
    try {
      if (isApp) await publishAppReview(id, true);
      else await publishRating(id, true);
      if (isApp) setApps((prev) => prev.filter((r) => r.id !== id));
      else setRides((prev) => prev.filter((r) => r.id !== id));
    } catch (e) {
      setRowError({
        id,
        msg:
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара."), // DRAFT
      });
    } finally {
      setBusyId(null);
    }
  }

  /** «Щит рейтинга»: оценка перестаёт влиять на средний балл. Текст при этом не публикуется. */
  async function shield(id: number) {
    if (busyId) return;
    setBusyId(id);
    setRowError(null);
    try {
      await excludeRating(id, true);
      setRides((prev) => prev.filter((r) => r.id !== id));
    } catch (e) {
      setRowError({
        id,
        msg:
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара."),
      });
    } finally {
      setBusyId(null);
    }
  }

  const list = tab === "rides" ? rides : apps;

  return (
    <>
      <SubHeader
        title={appText("Модерация отзывов", "Фекерҙәрҙе тикшереү")}
        subtitle={appText("Одобрить текст к показу в профиле", "Текстарҙы күрһәтеүгә раҫлау")}
        onBack={() => navigate(-1)}
      />

      <div className="seg" style={{ marginBottom: 4 }} role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === "rides"}
          className={"seg__item" + (tab === "rides" ? " is-active" : "")}
          onClick={() => setTab("rides")}
        >
          {appText("О поездках", "Сәфәрҙәр")}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "app"}
          className={"seg__item" + (tab === "app" ? " is-active" : "")}
          onClick={() => setTab("app")}
        >
          {appText("О приложении", "Ҡушымта")}
        </button>
      </div>

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load(tab)} />}

      {state === "ready" && list.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><YuStar size={40} /></div>
          <h2>{appText("Очередь пуста", "Сират буш")}</h2>
          <p>
            {tab === "rides"
              ? appText("Новых отзывов о поездках нет.", "Сәфәр тураһында яңы фекерҙәр юҡ.")
              : appText("Новых отзывов о приложении нет.", "Ҡушымта тураһында яңы фекерҙәр юҡ.")}
          </p>
        </div>
      )}

      {state === "ready" && list.length > 0 && (
        <div className="admin-cards">
          {tab === "rides"
            ? rides.map((r) => (
                <div key={r.id} className="admin-card">
                  <div className="admin-card__head">
                    <div className="admin-card__title"><Stars n={r.stars} /></div>
                    <span className="admin-card__sub" style={{ marginTop: 0 }}>
                      {formatRelative(r.created_at, ru)}
                    </span>
                  </div>
                  <p className="admin-card__reason">«{r.text}»</p>
                  <div className="admin-card__sub">
                    {appText("Автор", "Автор")}: {r.author || appText("Аноним", "Аноним")}
                    {r.ratee && (
                      <>
                        {" · "}
                        {appText("о ком", "кем тураһында")}: {r.ratee}
                      </>
                    )}
                  </div>
                  {rowError?.id === r.id && <div className="auth__error">{rowError.msg}</div>}
                  <div className="act-card__actions" style={{ marginTop: 12 }}>
                    <button
                      type="button"
                      className="btn-primary"
                      onClick={() => publish(r.id, false)}
                      disabled={busyId !== null}
                    >
                      {busyId === r.id ? appText("…", "…") : (
                        <><IconCheck size={18} /> {appText("Опубликовать", "Баҫтырыу")}</>
                      )}
                    </button>
                    {/* Щит рейтинга: одна месть-оценка не должна рушить рейтинг честного. */}
                    <button
                      type="button"
                      className="btn-soft"
                      onClick={() => shield(r.id)}
                      disabled={busyId !== null}
                    >
                      <IconShield size={18} /> {appText("Снять с рейтинга", "Рейтингтан алыу")}
                    </button>
                  </div>
                </div>
              ))
            : apps.map((r) => (
                <div key={r.id} className="admin-card">
                  <div className="admin-card__head">
                    <div className="admin-card__title"><Stars n={r.stars} /></div>
                    <span className="admin-card__sub" style={{ marginTop: 0 }}>
                      {formatRelative(r.created_at, ru)}
                    </span>
                  </div>
                  <p className="admin-card__reason">«{r.text}»</p>
                  <div className="admin-card__sub">
                    {r.name || appText("Аноним", "Аноним")}
                    {r.city ? ` · ${r.city}` : ""}
                  </div>
                  {rowError?.id === r.id && <div className="auth__error">{rowError.msg}</div>}
                  <button
                    type="button"
                    className="btn-primary"
                    style={{ width: "100%", marginTop: 12 }}
                    onClick={() => publish(r.id, true)}
                    disabled={busyId !== null}
                  >
                    {busyId === r.id ? appText("…", "…") : (
                      <><IconCheck size={18} /> {appText("Опубликовать", "Баҫтырыу")}</>
                    )}
                  </button>
                </div>
              ))}
        </div>
      )}
    </>
  );
}
