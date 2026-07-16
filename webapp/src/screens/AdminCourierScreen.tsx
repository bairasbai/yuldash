// ================================================================
//  Заявки курьеров → /admin/courier (RequireAdmin).
//  GET /admin/courier-applications?status= — очередь заявок (проверка L1).
//  Селфи с документом защищено (GET /secure/docs/{name}, только админ/владелец) —
//  грузим с Bearer через fetchSecureDoc → blob-URL (как в AdminDrivers).
//  Одобрить: POST /admin/courier-applications/{id}/approve.
//  Отклонить с причиной: POST /admin/courier-applications/{id}/reject {reason}.
//  Двуязычно, все состояния, тач-цели ≥48px.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchCourierApplications,
  approveCourierApp,
  rejectCourierApp,
  fetchSecureDoc,
  type AdminCourierApplication,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { formatRelative } from "../utils/format";
import { IconCheck, IconPhone, IconBox } from "../components/Icons";

type State = "loading" | "error" | "ready";

const FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "pending", ru: "На проверке", ba: "Тикшереүҙә" },
  { key: "approved", ru: "Одобренные", ba: "Хупланған" },
  { key: "rejected", ru: "Отклонённые", ba: "Кире ҡағылған" },
  { key: "all", ru: "Все", ba: "Барыһы" },
];

function transportLabel(t: string, appText: (r: string, b: string) => string): string {
  if (t === "car") return appText("Легковой", "Еңел авто");
  if (t === "cargo") return appText("Грузовой", "Йөк авто");
  return t;
}

function statusBadge(s: string): { cls: string; ru: string; ba: string } {
  switch (s) {
    case "approved":
      return { cls: "badge--mint", ru: "Одобрен", ba: "Хупланған" };
    case "pending":
      return { cls: "badge--gold", ru: "На проверке", ba: "Тикшереүҙә" };
    case "rejected":
      return { cls: "badge--danger", ru: "Отклонён", ba: "Кире" };
    default:
      return { cls: "badge--muted", ru: s, ba: s };
  }
}

/** Защищённое селфи: тянем с токеном → objectURL, чистим при размонтировании. */
function SecureImage({ url, alt }: { url: string; alt: string }) {
  const { appText } = useLang();
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!url) {
      setFailed(true);
      return;
    }
    let objUrl: string | null = null;
    const ac = new AbortController();
    fetchSecureDoc(url, ac.signal)
      .then((u) => {
        objUrl = u;
        setSrc(u);
      })
      .catch((e) => {
        if (ac.signal.aborted || e?.name === "AbortError") return;
        setFailed(true);
      });
    return () => {
      ac.abort();
      if (objUrl) URL.revokeObjectURL(objUrl);
    };
  }, [url]);

  if (failed) {
    return (
      <div className="doc-photo doc-photo--empty" role="img" aria-label={alt}>
        <span>{appText("Нет фото", "Фото юҡ")}</span>
      </div>
    );
  }
  if (!src) {
    return <div className="doc-photo skeleton" aria-hidden />;
  }
  return (
    <a href={src} target="_blank" rel="noreferrer" className="doc-photo">
      <img src={src} alt={alt} loading="lazy" />
    </a>
  );
}

export default function AdminCourierScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [filter, setFilter] = useState<string>("pending");
  const [state, setState] = useState<State>("loading");
  const [apps, setApps] = useState<AdminCourierApplication[]>([]);

  const load = useCallback((status: string, signal?: AbortSignal) => {
    setState("loading");
    fetchCourierApplications(status, signal)
      .then((list) => {
        setApps(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(filter, ac.signal);
    return () => ac.abort();
  }, [filter, load]);

  function patch(id: number, status: string) {
    // На узком фильтре одобренная/отклонённая уходит из списка; на «Все» — просто меняем статус.
    if (filter === "all") {
      setApps((prev) => prev.map((a) => (a.id === id ? { ...a, status } : a)));
    } else {
      setApps((prev) => prev.filter((a) => a.id !== id));
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Заявки курьеров", "Курьер заявкалары")}
        subtitle={appText("Проверка и одобрение", "Тикшереү һәм хуплау")}
        onBack={() => navigate(-1)}
      />

      <div className="chip-scroll" role="tablist" aria-label={appText("Фильтр заявок", "Заявка фильтры")}>
        {FILTERS.map((f) => (
          <button
            key={f.key}
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

      {state === "loading" && <LoadingList count={2} />}
      {state === "error" && <ErrorState onRetry={() => load(filter)} />}

      {state === "ready" && apps.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__emoji"><IconBox size={40} /></div>
          <h2>{appText("Здесь пусто", "Бында буш")}</h2>
          <p>{appText("Заявок курьеров в этом разделе нет.", "Был бүлектә курьер заявкалары юҡ.")}</p>
        </div>
      )}

      {state === "ready" && apps.length > 0 && (
        <div className="admin-cards">
          {apps.map((a) => (
            <CourierCard key={a.id} app={a} ru={ru} onPatch={patch} />
          ))}
        </div>
      )}
    </>
  );
}

function CourierCard({
  app,
  ru,
  onPatch,
}: {
  app: AdminCourierApplication;
  ru: boolean;
  onPatch: (id: number, status: string) => void;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<null | "approve" | "reject">(null);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");

  const badge = statusBadge(app.status);

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
      await approveCourierApp(app.id);
      onPatch(app.id, "approved");
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
      await rejectCourierApp(app.id, reason.trim());
      onPatch(app.id, "rejected");
      setRejecting(false);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  }

  const canModerate = app.status === "pending";

  return (
    <div className="admin-card">
      <div className="admin-card__head">
        <div className="admin-card__title">{app.name || appText("Курьер", "Курьер")}</div>
        <span className={`badge ${badge.cls}`}>{appText(badge.ru, badge.ba)}</span>
      </div>

      {app.phone && (
        <a className="admin-card__phone" href={`tel:${app.phone}`}>
          <IconPhone size={16} /> {app.phone}
        </a>
      )}
      <div className="admin-card__sub">
        {appText("Транспорт", "Транспорт")}: {transportLabel(app.transport, appText)}
      </div>
      {app.invited_by_name && (
        <div className="admin-card__sub">
          {appText("Пригласил", "Саҡырҙы")}: <b>{app.invited_by_name}</b>
        </div>
      )}
      {app.created_at && (
        <div className="admin-card__sub">{formatRelative(app.created_at, ru)}</div>
      )}

      <div className="doc-photos">
        <div className="doc-photos__item">
          <span className="doc-photos__cap">{appText("Селфи с документом", "Документ менән селфи")}</span>
          <SecureImage url={app.selfie_url} alt={appText("Селфи курьера", "Курьер селфиһы")} />
        </div>
      </div>

      {app.reject_reason && app.status === "rejected" && (
        <p className="admin-card__sub">{appText("Причина", "Сәбәп")}: {app.reject_reason}</p>
      )}

      {error && <div className="auth__error">{error}</div>}

      {canModerate && !rejecting && (
        <div className="field-row" style={{ marginTop: 12 }}>
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
      )}

      {canModerate && rejecting && (
        <>
          <textarea
            className="field__input field__area"
            style={{ marginTop: 12, minHeight: 76, paddingTop: 12 }}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={appText(
              "Причина отказа — курьер увидит и подаст снова",
              "Кире ҡағыу сәбәбе — курьер күрер һәм ҡабат ебәрер"
            )}
          />
          <div className="field-row" style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn-soft"
              style={{ flex: 1 }}
              onClick={() => setRejecting(false)}
              disabled={busy !== null}
            >
              {appText("Назад", "Кире")}
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
    </div>
  );
}
