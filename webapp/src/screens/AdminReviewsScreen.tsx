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
import { RideCardSkeleton, ErrorState, EmptyStateCard } from "../components/States";
import { formatWhen } from "../utils/format";
import { IconCheck, IconInfo, IconShield, IconStar, IconWarn } from "../components/Icons";
import { YuStar } from "../components/BrandIcons";

type State = "loading" | "error" | "ready";
type Tab = "rides" | "app";

/** Пять звёзд (RatingStars): закрашенные — CanonStar, пустые — muted, чтобы оценка читалась сразу. */
function RatingStars({ n, size = 20 }: { n: number; size?: number }) {
  const { appText } = useLang();
  const c = Math.max(0, Math.min(5, n));
  return (
    <span className="rstars" aria-label={appText(`${c} из 5`, `5-тән ${c}`)}>
      {Array.from({ length: 5 }).map((_, k) => (
        <span key={k} className={k < c ? "rstars__on" : "rstars__off"}>
          <IconStar size={size} />
        </span>
      ))}
    </span>
  );
}

/** Звёзды отзыва о приложении: только закрашенные, золотые 16 (AdminReviewsContent). */
function GoldStars({ n }: { n: number }) {
  const { appText } = useLang();
  const c = Math.max(0, Math.min(5, n));
  return (
    <span className="rstars rstars--gold" aria-label={appText(`${c} из 5`, `5-тән ${c}`)}>
      {Array.from({ length: c }).map((_, k) => (
        <YuStar key={k} size={16} className="amenity-ic" />
      ))}
    </span>
  );
}

export default function AdminReviewsScreen({ initialTab = "rides" }: { initialTab?: Tab }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [tab, setTab] = useState<Tab>(initialTab);
  const [state, setState] = useState<State>("loading");
  const [rides, setRides] = useState<PendingRating[]>([]);
  const [apps, setApps] = useState<PendingAppReview[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState<{ id: number; msg: string } | null>(null);

  useEffect(() => setTab(initialTab), [initialTab]);

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

  // ---- /admin/reviews — отзывы о приложении (AdminReviewsScreen.kt) ----
  if (tab === "app") {
    return (
      <>
        <SubHeader title={appText("Модерация отзывов", "Фекерҙәрҙе модерациялау")} onBack={() => navigate(-1)} />
        <div className="alist">
          {state === "loading" && (
            <>
              <RideCardSkeleton />
              <RideCardSkeleton />
            </>
          )}
          {state === "error" && <ErrorState onRetry={() => load(tab)} />}
          {state === "ready" && apps.length === 0 && (
            <EmptyStateCard
              icon={<IconCheck size={48} />}
              title={appText("Новых отзывов нет", "Яңы фекерҙәр юҡ")}
              text={appText("Всё разобрано", "Барыһы ла ҡаралған")}
            />
          )}
          {state === "ready" &&
            apps.map((r) => (
              <article key={r.id} className="areview">
                <GoldStars n={r.stars} />
                <p className="areview__quote">«{r.text}»</p>
                <span className="areview__who">
                  {[r.name, r.city].filter(Boolean).join(", ") || appText("Аноним", "Аноним")}
                </span>
                {rowError?.id === r.id && <div className="auth__error">{rowError.msg}</div>}
                <button type="button" className="areview__ok" onClick={() => publish(r.id, true)} disabled={busyId !== null}>
                  {busyId === r.id ? appText("…", "…") : appText("Одобрить для сайта", "Сайт өсөн раҫларға")}
                </button>
              </article>
            ))}
        </div>
      </>
    );
  }

  // ---- /admin/ratings — текстовые отзывы о поездках (AdminRatingsScreen.kt) ----
  return (
    <>
      <SubHeader title={appText("Отзывы на модерации", "Модерациялағы фекерҙәр")} onBack={() => navigate(-1)} />
      <div className="alist">
        {/* Правила модерации — один раз вверху, а не абзацем на каждой карточке. */}
        <div className="rules-note">
          <span className="rules-note__icon" aria-hidden><IconInfo size={20} /></span>
          <span className="rules-note__text">
            <strong>
              {list.length > 0
                ? appText(`На модерации: ${list.length}`, `Модерацияла: ${list.length}`)
                : appText("Как работает модерация", "Модерация нисек эшләй")}
            </strong>
            <span>
              {appText(
                "Звёзды учитываются сразу, текст появляется в профиле только после твоего одобрения. Не одобряешь — просто пропусти: текст останется скрытым.",
                "Йондоҙҙар шунда уҡ иҫәпләнә, текст профилдә тик һин раҫлағас күренә. Раҫламайһыңмы — үтеп кит: текст йәшерен ҡала."
              )}
            </span>
          </span>
        </div>

        {state === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && <ErrorState onRetry={() => load(tab)} />}
        {state === "ready" && rides.length === 0 && (
          <EmptyStateCard
            icon={<IconCheck size={36} />}
            title={appText("Всё разобрано", "Бөтәһе лә ҡаралған")}
            text={appText("Новых отзывов на модерации нет.", "Модерацияла яңы фекерҙәр юҡ.")}
          />
        )}

        {state === "ready" &&
          rides.map((r, i) => {
            const stars = Math.max(0, Math.min(5, r.stars));
            const angry = stars >= 1 && stars <= 2; // жалоба: смотреть внимательнее
            return (
              <article key={r.id} className="rating-card" style={{ animationDelay: `calc(var(--cascade-in) * ${Math.min(i, 6)})` }}>
                <div className="rating-card__head">
                  <span className={"rating-card__mood" + (angry ? " is-angry" : "")} aria-label={appText(`Оценка ${stars} из 5`, `Баһа: ${stars}, 5-тән`)}>
                    {angry ? <IconWarn size={20} /> : <IconStar size={20} />}
                  </span>
                  <span className="rating-card__meta">
                    <RatingStars n={stars} />
                    {/* Кто → о ком: без этого решение «публиковать» принималось вслепую. */}
                    <strong>{r.ratee ? appText(`${r.author} → о ${r.ratee}`, `${r.author} → ${r.ratee} тураһында`) : r.author}</strong>
                    <small>{formatWhen(r.created_at, ru)}</small>
                  </span>
                </div>
                {/* Сам отзыв — то единственное, ради чего открыли карточку. */}
                <p className="rating-card__text">{r.text || appText("Текста нет — только звёзды.", "Текст юҡ — тик йондоҙҙар.")}</p>
                {rowError?.id === r.id && <div className="auth__error">{rowError.msg}</div>}
                <button type="button" className="btn-primary rating-card__publish" onClick={() => publish(r.id, false)} disabled={busyId !== null}>
                  {busyId === r.id ? appText("…", "…") : <><IconCheck size={20} /> {appText("Опубликовать в профиле", "Профилдә баҫтырыу")}</>}
                </button>
                {/* Кнопки «скрыть» нет намеренно: неодобренный текст и так не виден никому. */}
                <button type="button" className="rating-card__shield" onClick={() => shield(r.id)} disabled={busyId !== null}>
                  <IconShield size={16} /> {appText("Оценка мстительная — снять из рейтинга", "Баһа үс алыу өсөн — рейтингтан алыу")}
                </button>
              </article>
            );
          })}
      </div>
    </>
  );
}
