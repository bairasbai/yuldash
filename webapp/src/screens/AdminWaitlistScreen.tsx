// ================================================================
//  Лист ожидания → /admin/waitlist (RequireAdmin).
//  GET /admin/waitlist?role=&invited= — счётчики (по всей базе) + список по фильтрам.
//  Отметить волну: POST /admin/waitlist/invite {ids} (проставит invited_at).
//  CSV-экспорт: GET /admin/waitlist.csv (с токеном → скачивание).
//  Телефоны видит только админ (152-ФЗ). Двуязычно, все состояния, тач-цели ≥48px.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { API_BASE, ApiError, getToken } from "../api/client";
import {
  fetchWaitlist,
  inviteWaitlist,
  type WaitlistResponse,
  type WaitlistEntry,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { formatRelative } from "../utils/format";
import { IconCheck, IconPhone, IconClock, IconShare } from "../components/Icons";

type State = "loading" | "error" | "ready";

const FILTERS: { key: string; role?: string; invited?: boolean; ru: string; ba: string }[] = [
  { key: "waiting", invited: false, ru: "Ждут", ba: "Көтә" },
  { key: "drivers", role: "driver", ru: "Водители", ba: "Водителдәр" },
  { key: "passengers", role: "passenger", ru: "Пассажиры", ba: "Юлсылар" },
  { key: "invited", invited: true, ru: "Позваны", ba: "Саҡырылған" },
  { key: "all", ru: "Все", ba: "Барыһы" },
];

export default function AdminWaitlistScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [filter, setFilter] = useState("waiting");
  const [state, setState] = useState<State>("loading");
  const [data, setData] = useState<WaitlistResponse | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [inviting, setInviting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((key: string, signal?: AbortSignal) => {
    setState("loading");
    setSelected(new Set());
    const f = FILTERS.find((x) => x.key === key)!;
    fetchWaitlist({ role: f.role, invited: f.invited, limit: 500, signal })
      .then((res) => {
        setData(res);
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

  function toggle(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function invite() {
    if (inviting || selected.size === 0) return;
    setInviting(true);
    setError(null);
    setNotice(null);
    try {
      const res = await inviteWaitlist([...selected]);
      setNotice(appText(`Отмечено в волне: ${res.invited}`, `Тулҡында билдәләнде: ${res.invited}`)); // DRAFT
      load(filter);
    } catch (e) {
      setError(e instanceof ApiError && e.message ? e.message : appText("Не получилось отметить волну.", "Тулҡынды билдәләп булманы.") /* DRAFT */);
    } finally {
      setInviting(false);
    }
  }

  async function exportCsv() {
    setError(null);
    try {
      const token = getToken();
      const res = await fetch(`${API_BASE}/admin/waitlist.csv`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!res.ok) throw new Error(`csv ${res.status}`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "waitlist.csv";
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch {
      setError(appText("Не удалось выгрузить CSV.", "CSV-ны төшөрөп булманы.")); // DRAFT
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Лист ожидания", "Көтөү исемлеге")}
        subtitle={appText("Ранний доступ и волны", "Иртә инеү һәм тулҡындар")}
        onBack={() => navigate(-1)}
      />

      {data && (
        <div className="stat-grid" style={{ marginBottom: 4 }}>
          <div className="stat-tile">
            <b>{data.total.toLocaleString("ru-RU")}</b>
            <span>{appText("всего", "барлығы")}</span>
          </div>
          <div className="stat-tile">
            <b>{data.by_role.driver.toLocaleString("ru-RU")}</b>
            <span>{appText("водители", "водителдәр")}</span>
          </div>
          <div className="stat-tile">
            <b>{data.invited.toLocaleString("ru-RU")}</b>
            <span>{appText("позваны", "саҡырылған")}</span>
          </div>
        </div>
      )}

      {data && data.by_city.length > 0 && (
        <div className="chip-scroll" aria-label={appText("По городам", "Ҡалалар буйынса")}>
          {data.by_city.slice(0, 12).map((c) => (
            <span key={c.city} className="chip" style={{ pointerEvents: "none" }}>
              {c.city} · {c.count}
            </span>
          ))}
        </div>
      )}

      <div className="chip-scroll" role="tablist" aria-label={appText("Фильтр листа ожидания", "Көтөү фильтры")}>
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

      <button type="button" className="btn-soft btn-soft--sm" onClick={exportCsv} style={{ marginBottom: 4 }}>
        <IconShare size={16} /> {appText("Выгрузить CSV", "CSV төшөрөү")}
      </button>

      {notice && <div className="safe-note" style={{ marginTop: 8 }}><p>{notice}</p></div>}
      {error && <div className="auth__error" style={{ marginTop: 8 }}>{error}</div>}

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load(filter)} />}

      {state === "ready" && data && data.items.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconClock size={40} /></div>
          <h2>{appText("Здесь пусто", "Бында буш")}</h2>
          <p>{appText("В этом фильтре никого нет.", "Был фильтрҙа бер кем дә юҡ.")}</p>
        </div>
      )}

      {state === "ready" && data && data.items.length > 0 && (
        <div className="admin-cards">
          {data.items.map((e) => (
            <WaitRow key={e.id} entry={e} ru={ru} selected={selected.has(e.id)} onToggle={() => toggle(e.id)} />
          ))}
        </div>
      )}

      {/* Плавающая панель приглашения выбранных */}
      {selected.size > 0 && (
        <div className="wait-invite-bar">
          <span>{appText(`Выбрано: ${selected.size}`, `Һайланды: ${selected.size}`)}</span>
          <button type="button" className="btn-primary" onClick={invite} disabled={inviting}>
            {inviting ? appText("…", "…") : (<><IconCheck size={18} /> {appText("Отметить волну", "Тулҡын билдәләү")}</>)}
          </button>
        </div>
      )}
    </>
  );
}

function WaitRow({
  entry,
  ru,
  selected,
  onToggle,
}: {
  entry: WaitlistEntry;
  ru: boolean;
  selected: boolean;
  onToggle: () => void;
}) {
  const { appText } = useLang();
  const invited = !!entry.invited_at;
  return (
    <div className="admin-card" style={{ padding: 14 }}>
      <label className="admin-check" style={{ marginTop: 0, alignItems: "flex-start" }}>
        <input type="checkbox" checked={selected} onChange={onToggle} disabled={invited} aria-label={appText("Выбрать для волны", "Тулҡынға һайлау")} />
        <span style={{ flex: 1 }}>
          <a className="admin-card__phone" href={`tel:${entry.phone}`} style={{ marginTop: 0 }}>
            <IconPhone size={14} /> {entry.phone}
          </a>
          <span className="admin-card__sub" style={{ display: "block" }}>
            {entry.role === "driver" ? appText("Водитель", "Водитель") : appText("Пассажир", "Юлсы")}
            {entry.city ? ` · ${entry.city}` : ""}
            {" · "}
            {formatRelative(entry.created_at, ru)}
          </span>
          {invited && (
            <span className="badge badge--mint" style={{ marginTop: 6, display: "inline-block" }}>
              {appText("позван", "саҡырылған")} · {formatRelative(entry.invited_at, ru)}
            </span>
          )}
        </span>
      </label>
    </div>
  );
}
