// ================================================================
//  Жалобы → /admin/reports (RequireAdmin).
//  GET /admin/reports?status= — очередь жалоб (автора видит только админ).
//  Действия: подтвердить (resolve) / отклонить (reject); «оставить паузу»
//  (keep_pause) для тяжёлых категорий — §9 лестница мер срабатывает на бэке.
//  Меры вручную: пауза/снятие паузы такси цели (quality pause/unpause).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchAdminReports,
  resolveReport,
  rejectReport,
  qualityPause,
  qualityUnpause,
  type AdminReport,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { formatRelative } from "../utils/format";
import { IconCheck, IconFlag, IconPhone, IconShield } from "../components/Icons";

type State = "loading" | "error" | "ready";

const FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "new", ru: "Новые", ba: "Яңы" },
  { key: "reviewing", ru: "В работе", ba: "Эштә" },
  { key: "resolved", ru: "Приняты", ba: "Ҡабул" },
  { key: "rejected", ru: "Отклонены", ba: "Кире" },
  { key: "", ru: "Все", ba: "Барыһы" },
];

const CAT_LABEL: Record<string, [string, string]> = {
  safety: ["Безопасность", "Именлек"],
  fraud: ["Обман с ценой", "Хаҡ менән алдау"],
  noshow: ["Не приехал", "Килмәне"],
  unpaid: ["Не заплатил", "Түләмәне"],
  dirty: ["Грязная машина", "Бысраҡ машина"],
  late: ["Опоздание", "Һуңланы"],
  other: ["Другое", "Башҡа"],
};

function statusBadge(s: string): string {
  switch (s) {
    case "resolved":
      return "badge--mint";
    case "rejected":
      return "badge--danger";
    case "reviewing":
      return "badge--gold";
    default:
      return "badge--gold";
  }
}

export default function AdminReportsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [filter, setFilter] = useState<string>("new");
  const [state, setState] = useState<State>("loading");
  const [reports, setReports] = useState<AdminReport[]>([]);

  const load = useCallback(
    (status: string, signal?: AbortSignal) => {
      setState("loading");
      fetchAdminReports({ status: status || undefined, signal })
        .then((list) => {
          setReports(list);
          setState("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setState("error");
        });
    },
    []
  );

  useEffect(() => {
    const ac = new AbortController();
    load(filter, ac.signal);
    return () => ac.abort();
  }, [filter, load]);

  function patch(updated: AdminReport) {
    setReports((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
  }

  return (
    <>
      <SubHeader
        title={appText("Жалобы", "Зарланыуҙар")}
        subtitle={appText("Разбор и меры", "Тикшереү һәм саралар")}
        onBack={() => navigate(-1)}
      />

      <div className="chip-scroll" role="tablist" aria-label={appText("Фильтр жалоб", "Зарланыу фильтры")}>
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
      {state === "error" && <ErrorState onRetry={() => load(filter)} />}

      {state === "ready" && reports.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconShield size={34} /></div>
          <h2>{appText("Здесь пусто", "Бында буш")}</h2>
          <p>{appText("Жалоб в этом разделе нет.", "Был бүлектә зарланыуҙар юҡ.")}</p>
        </div>
      )}

      {state === "ready" && reports.length > 0 && (
        <div className="admin-cards">
          {reports.map((r) => (
            <ReportCard key={r.id} report={r} ru={ru} onPatch={patch} />
          ))}
        </div>
      )}
    </>
  );
}

function ReportCard({
  report,
  ru,
  onPatch,
}: {
  report: AdminReport;
  ru: boolean;
  onPatch: (r: AdminReport) => void;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<null | "resolve" | "reject" | "pause" | "unpause">(null);
  const [error, setError] = useState<string | null>(null);
  const [keepPause, setKeepPause] = useState(false);
  const [pauseMsg, setPauseMsg] = useState<string | null>(null);

  const open = report.status === "new" || report.status === "reviewing";
  const cat = CAT_LABEL[report.category] ?? CAT_LABEL.other;

  async function act(kind: "resolve" | "reject") {
    if (busy) return;
    setBusy(kind);
    setError(null);
    try {
      const updated =
        kind === "resolve"
          ? await resolveReport(report.id, { keep_pause: keepPause })
          : await rejectReport(report.id);
      onPatch(updated);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара.") // DRAFT
      );
    } finally {
      setBusy(null);
    }
  }

  async function pause(on: boolean) {
    if (busy) return;
    setBusy(on ? "pause" : "unpause");
    setError(null);
    setPauseMsg(null);
    try {
      if (on) {
        await qualityPause(report.target_user_id, 72);
        setPauseMsg(appText("Такси на паузе 72 ч (попутка работает).", "Такси 72 сәғ паузала (юлдаш эшләй).")); // DRAFT
      } else {
        await qualityUnpause(report.target_user_id);
        setPauseMsg(appText("Пауза снята.", "Пауза алынды.")); // DRAFT
      }
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 404
          ? appText("У пользователя нет профиля водителя.", "Ҡулланыусының йөрөтөүсе профиле юҡ.") // DRAFT
          : appText("Не получилось изменить паузу.", "Паузаны үҙгәртеп булманы.") // DRAFT
      );
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="admin-card">
      <div className="admin-card__head">
        <div className="admin-card__title">
          <IconFlag size={16} /> {appText(cat[0], cat[1])}
        </div>
        <span className={`badge ${statusBadge(report.status)}`}>
          {report.status === "resolved"
            ? appText("Принята", "Ҡабул")
            : report.status === "rejected"
            ? appText("Отклонена", "Кире ҡағылған")
            : report.status === "reviewing"
            ? appText("В работе", "Эштә")
            : appText("Новая", "Яңы")}
        </span>
      </div>

      <div className="admin-card__sub">
        {appText("На кого", "Кемгә")}: <b>{report.target_name}</b>
        {report.target_phone && (
          <a className="admin-card__phone" href={`tel:${report.target_phone}`} style={{ marginLeft: 8 }}>
            <IconPhone size={14} /> {report.target_phone}
          </a>
        )}
      </div>
      <div className="admin-card__sub">
        {appText("От кого", "Кемдән")}: {report.reporter_name} · {formatRelative(report.created_at, ru)}
      </div>
      {report.reason && <p className="admin-card__reason">«{report.reason}»</p>}
      {report.resolution && (
        <p className="admin-card__sub">{appText("Итог", "Һөҙөмтә")}: {report.resolution}</p>
      )}

      {error && <div className="auth__error">{error}</div>}
      {pauseMsg && <div className="safe-note" style={{ marginTop: 8 }}><p>{pauseMsg}</p></div>}

      {open && (
        <>
          <label className="admin-check">
            <input type="checkbox" checked={keepPause} onChange={(e) => setKeepPause(e.target.checked)} />
            <span>{appText("Оставить паузу такси при подтверждении", "Раҫлағанда такси паузаһын ҡалдырырға")}</span>
          </label>
          <div className="field-row" style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn-soft"
              style={{ flex: 1 }}
              onClick={() => act("reject")}
              disabled={busy !== null}
            >
              {busy === "reject" ? appText("…", "…") : appText("Отклонить", "Кире ҡағыу")}
            </button>
            <button
              type="button"
              className="btn-primary"
              style={{ flex: 1 }}
              onClick={() => act("resolve")}
              disabled={busy !== null}
            >
              {busy === "resolve" ? (
                appText("…", "…")
              ) : (
                <><IconCheck size={18} /> {appText("Подтвердить", "Раҫлау")}</>
              )}
            </button>
          </div>
        </>
      )}

      {/* Ручные меры по цели — доступны всегда (в т.ч. после разбора). */}
      <div className="field-row" style={{ marginTop: 10 }}>
        <button
          type="button"
          className="btn-soft btn-soft--sm"
          onClick={() => pause(true)}
          disabled={busy !== null}
        >
          {busy === "pause" ? appText("…", "…") : appText("Пауза такси 72 ч", "Такси паузаһы 72 сәғ")}
        </button>
        <button
          type="button"
          className="btn-soft btn-soft--sm"
          onClick={() => pause(false)}
          disabled={busy !== null}
        >
          {busy === "unpause" ? appText("…", "…") : appText("Снять паузу", "Паузаны алыу")}
        </button>
      </div>
    </div>
  );
}
