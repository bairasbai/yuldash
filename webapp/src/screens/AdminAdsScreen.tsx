// ================================================================
//  Модерация рекламы → /admin/ads (RequireAdmin).
//  GET /admin/ads — все объявления (кроме архива) + занятые founder-слоты.
//  GET /ads/stats — показы/клики по каждому.
//  Партнёрские (pending_review): одобрить (с erid-маркировкой ОРД) / отклонить (с причиной).
//  Активные/на паузе: пауза ⇄ публикация, архив. Двуязычно, все состояния, тач-цели ≥48px.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchAdminAds,
  fetchAdStats,
  approveAd,
  rejectAd,
  setAdStatus,
  archiveAd,
  type AdminAd,
  type AdStats,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { formatRelative } from "../utils/format";
import { IconCheck, IconRocket, IconTrend } from "../components/Icons";

type State = "loading" | "error" | "ready";

const FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "pending_review", ru: "На модерации", ba: "Тикшереүҙә" },
  { key: "active", ru: "Активные", ba: "Әүҙем" },
  { key: "rejected", ru: "Отклонённые", ba: "Кире ҡағылған" },
  { key: "", ru: "Все", ba: "Барыһы" },
];

function statusBadge(s: string): { cls: string; ru: string; ba: string } {
  switch (s) {
    case "active":
      return { cls: "badge--mint", ru: "Активно", ba: "Әүҙем" };
    case "pending_review":
      return { cls: "badge--gold", ru: "На модерации", ba: "Тикшереүҙә" };
    case "rejected":
      return { cls: "badge--danger", ru: "Отклонено", ba: "Кире" };
    case "paused":
      return { cls: "badge--muted", ru: "Пауза", ba: "Пауза" };
    case "draft":
      return { cls: "badge--muted", ru: "Черновик", ba: "Ҡаралама" };
    case "expired":
      return { cls: "badge--muted", ru: "Истёк", ba: "Бөткән" };
    default:
      return { cls: "badge--muted", ru: s, ba: s };
  }
}

export default function AdminAdsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [filter, setFilter] = useState<string>("pending_review");
  const [state, setState] = useState<State>("loading");
  const [ads, setAds] = useState<AdminAd[]>([]);
  const [founder, setFounder] = useState<{ used: number; limit: number } | null>(null);
  const [stats, setStats] = useState<AdStats>({});

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchAdminAds(signal)
      .then((res) => {
        setAds(res.items);
        setFounder({ used: res.founder_used, limit: res.founder_limit });
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
    // Статистика — не критична: ошибку глотаем.
    fetchAdStats(signal).then(setStats).catch(() => {});
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  function patch(id: string, next: Partial<AdminAd>) {
    setAds((prev) => prev.map((a) => (a.id === id ? { ...a, ...next } : a)));
  }
  function drop(id: string) {
    setAds((prev) => prev.filter((a) => a.id !== id));
  }

  const shown = filter ? ads.filter((a) => a.status === filter) : ads;

  return (
    <>
      <SubHeader
        title={appText("Модерация рекламы", "Рекламаны тикшереү")}
        subtitle={appText("Объявления партнёров", "Партнёр иғландары")}
        onBack={() => navigate(-1)}
      />

      {founder && (
        <div className="stat-grid" style={{ marginBottom: 4 }}>
          <div className="stat-tile">
            <b>{founder.used}/{founder.limit}</b>
            <span>{appText("слоты основателей", "нигеҙләүсе урындары")}</span>
          </div>
          <div className="stat-tile">
            <b>{ads.filter((a) => a.status === "active").length}</b>
            <span>{appText("активных", "әүҙем")}</span>
          </div>
          <div className="stat-tile">
            <b>{ads.filter((a) => a.status === "pending_review").length}</b>
            <span>{appText("ждут", "көтә")}</span>
          </div>
        </div>
      )}

      <div className="chip-scroll" role="tablist" aria-label={appText("Фильтр рекламы", "Реклама фильтры")}>
        {FILTERS.map((f) => (
          <button
            key={f.key || "all"}
            type="button"
            role="tab"
            aria-selected={filter === f.key}
            className={"chip" + (filter === f.key ? " chip--on" : "")}
            onClick={() => setFilter(f.key)}
          >
            {appText(f.ru, f.ba)}
          </button>
        ))}
      </div>

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && shown.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconRocket size={40} /></div>
          <h2>{appText("Здесь пусто", "Бында буш")}</h2>
          <p>{appText("Объявлений в этом разделе нет.", "Был бүлектә иғландар юҡ.")}</p>
        </div>
      )}

      {state === "ready" && shown.length > 0 && (
        <div className="admin-cards">
          {shown.map((a) => (
            <AdCard
              key={a.id}
              ad={a}
              ru={ru}
              stat={stats[a.id]}
              onPatch={patch}
              onDrop={drop}
            />
          ))}
        </div>
      )}
    </>
  );
}

function AdCard({
  ad,
  ru,
  stat,
  onPatch,
  onDrop,
}: {
  ad: AdminAd;
  ru: boolean;
  stat?: { impressions: number; clicks: number };
  onPatch: (id: string, next: Partial<AdminAd>) => void;
  onDrop: (id: string) => void;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<null | string>(null);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [erid, setErid] = useState(ad.erid || "");

  const badge = statusBadge(ad.status);
  const imp = stat?.impressions ?? 0;
  const clk = stat?.clicks ?? 0;
  const ctr = imp > 0 ? ((clk / imp) * 100).toFixed(1) : "0.0";

  function fail(e: unknown) {
    setError(
      e instanceof ApiError && e.message
        ? e.message
        : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара.") // DRAFT
    );
  }

  async function approve() {
    if (busy) return;
    setBusy("approve");
    setError(null);
    try {
      await approveAd(ad.id, erid.trim());
      onPatch(ad.id, { status: "active", erid: erid.trim() });
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  }

  async function reject() {
    if (busy) return;
    setBusy("reject");
    setError(null);
    try {
      await rejectAd(ad.id, reason.trim());
      onPatch(ad.id, { status: "rejected", reject_reason: reason.trim() });
      setRejecting(false);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  }

  async function status(next: string) {
    if (busy) return;
    setBusy(next);
    setError(null);
    try {
      await setAdStatus(ad.id, next);
      onPatch(ad.id, { status: next });
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  }

  async function archive() {
    if (busy) return;
    setBusy("archive");
    setError(null);
    try {
      await archiveAd(ad.id);
      onDrop(ad.id);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="admin-card">
      <div className="admin-card__head">
        <div className="admin-card__title">{ad.title || appText("Без названия", "Исемһеҙ")}</div>
        <span className={`badge ${badge.cls}`}>{appText(badge.ru, badge.ba)}</span>
      </div>

      {ad.partner && (
        <div className="admin-card__sub">
          {appText("Партнёр", "Партнёр")}: <b>{ad.partner}</b>
        </div>
      )}
      {ad.text && <p className="admin-card__reason">{ad.text}</p>}
      <div className="admin-card__sub">
        {ad.plan && <>{appText("Тариф", "Тариф")}: {ad.plan} · </>}
        {ad.paid ? appText("оплачено", "түләнгән") : appText("не оплачено", "түләнмәгән")}
        {ad.cities.length > 0 && <> · {ad.cities.join(", ")}</>}
      </div>
      {ad.erid && <div className="admin-card__sub">erid: {ad.erid}</div>}
      {ad.created_at && (
        <div className="admin-card__sub">{formatRelative(ad.created_at, ru)}</div>
      )}

      {/* Статистика показов/кликов */}
      <div className="ad-stat-row">
        <span><IconTrend size={14} /> {appText("Показы", "Күрһәтеү")}: <b>{imp.toLocaleString("ru-RU")}</b></span>
        <span>{appText("Клики", "Клик")}: <b>{clk.toLocaleString("ru-RU")}</b></span>
        <span>CTR: <b>{ctr}%</b></span>
      </div>

      {ad.reject_reason && ad.status === "rejected" && (
        <p className="admin-card__sub">{appText("Причина", "Сәбәп")}: {ad.reject_reason}</p>
      )}

      {error && <div className="auth__error">{error}</div>}

      {/* Действия для объявлений на модерации */}
      {ad.status === "pending_review" && !rejecting && (
        <>
          <label className="field__label" style={{ marginTop: 12, display: "block" }}>
            {appText("Маркировка (erid) — необязательно", "Маркировка (erid) — мотлаҡ түгел")}
          </label>
          <input
            className="field__input"
            value={erid}
            onChange={(e) => setErid(e.target.value)}
            placeholder={appText("erid из ОРД", "ОРД-нан erid")}
            style={{ marginTop: 6 }}
          />
          <div className="field-row" style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn-soft"
              style={{ flex: 1 }}
              onClick={() => setRejecting(true)}
              disabled={busy !== null}
            >
              {appText("Отклонить", "Кире ҡағыу")}
            </button>
            <button
              type="button"
              className="btn-primary"
              style={{ flex: 1 }}
              onClick={approve}
              disabled={busy !== null}
            >
              {busy === "approve" ? appText("…", "…") : (
                <><IconCheck size={18} /> {appText("Одобрить", "Раҫлау")}</>
              )}
            </button>
          </div>
        </>
      )}

      {ad.status === "pending_review" && rejecting && (
        <>
          <textarea
            className="field__input field__area"
            style={{ marginTop: 12, minHeight: 76, paddingTop: 12 }}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={appText("Причина отказа — партнёр увидит и исправит", "Кире ҡағыу сәбәбе — партнёр күрер һәм төҙәтер")}
          />
          <div className="field-row" style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn-soft"
              style={{ flex: 1 }}
              onClick={() => setRejecting(false)}
              disabled={busy !== null}
            >
              {appText("Назад", "Артҡа")}
            </button>
            <button
              type="button"
              className="btn-danger"
              style={{ flex: 1, marginTop: 0 }}
              onClick={reject}
              disabled={busy !== null}
            >
              {busy === "reject" ? appText("…", "…") : appText("Отклонить", "Кире ҡағыу")}
            </button>
          </div>
        </>
      )}

      {/* Управление активным/паузным объявлением */}
      {(ad.status === "active" || ad.status === "paused") && (
        <div className="field-row" style={{ marginTop: 12 }}>
          {ad.status === "active" ? (
            <button
              type="button"
              className="btn-soft btn-soft--sm"
              onClick={() => status("paused")}
              disabled={busy !== null}
            >
              {busy === "paused" ? appText("…", "…") : appText("Пауза", "Туҡтатылған")}
            </button>
          ) : (
            <button
              type="button"
              className="btn-soft btn-soft--sm"
              onClick={() => status("active")}
              disabled={busy !== null}
            >
              {busy === "active" ? appText("…", "…") : appText("Включить", "Ҡабыҙыу")}
            </button>
          )}
          <button
            type="button"
            className="btn-soft btn-soft--sm"
            onClick={archive}
            disabled={busy !== null}
          >
            {busy === "archive" ? appText("…", "…") : appText("В архив", "Архивға")}
          </button>
        </div>
      )}
    </div>
  );
}
