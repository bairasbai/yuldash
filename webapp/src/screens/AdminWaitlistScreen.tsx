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
import { RideCardSkeleton } from "../components/States";
import { AdminIntro, AdminStatCard, ListedEmpty, ListedError } from "../components/adminUi";
import { IconShare } from "../components/Icons";

type State = "loading" | "error" | "ready";

/** Фильтры как в приложении: роль — радио («Все / Пассажиры / Водители»), статус — переключаемые «Ждут» / «Позваны». */
type RoleFilter = "" | "passenger" | "driver";

export default function AdminWaitlistScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [roleFilter, setRoleFilter] = useState<RoleFilter>("");
  const [invitedFilter, setInvitedFilter] = useState<boolean | null>(false);
  const [state, setState] = useState<State>("loading");
  const [data, setData] = useState<WaitlistResponse | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [inviting, setInviting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((role: RoleFilter, invited: boolean | null, signal?: AbortSignal) => {
    setState("loading");
    setSelected(new Set());
    fetchWaitlist({ role: role || undefined, invited: invited ?? undefined, limit: 500, signal })
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
    load(roleFilter, invitedFilter, ac.signal);
    return () => ac.abort();
  }, [roleFilter, invitedFilter, load]);

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
      setNotice(appText(`Волна помечена: ${res.invited}`, `Тулҡын билдәләнде: ${res.invited}`)); // DRAFT
      load(roleFilter, invitedFilter);
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

  const rows = data?.items ?? [];

  return (
    <>
      <SubHeader title={appText("Лист ожидания", "Көтөү исемлеге")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Ранний доступ: кто ждёт запуска такси. Выбери записи и пометь волну — рассылку делаешь сам, СМС отсюда не уходят.",
            "Иртә инеү: такси асылыуын кем көтә. Яҙмаларҙы һайла ла тулҡынды билдәлә — хәбәрҙе үҙең ебәрәһең, СМС бынан китмәй."
          )}
        </AdminIntro>

        {/* Счётчики: всего / ждут / позваны, пассажиры / водители — по всей базе, не по фильтру. */}
        {data && (
          <>
            <div className="astat-row">
              <AdminStatCard label={appText("Всего", "Барлығы")} value={String(data.total)} />
              <AdminStatCard label={appText("Ждут", "Көтәләр")} value={String(data.total - data.invited)} />
              <AdminStatCard label={appText("Позваны", "Саҡырылған")} value={String(data.invited)} />
            </div>
            <div className="astat-row">
              <AdminStatCard label={appText("Пассажиры", "Пассажирҙар")} value={String(data.by_role.passenger)} />
              <AdminStatCard label={appText("Водители", "Йөрөтөүселәр")} value={String(data.by_role.driver)} />
            </div>
            {data.by_city.length > 0 && (
              <div className="afilter-row" aria-label={appText("По городам", "Ҡалалар буйынса")}>
                {data.by_city.map((c) => (
                  <span key={c.city} className="acity-chip">
                    {c.city} · {c.count}
                  </span>
                ))}
              </div>
            )}
          </>
        )}

        {/* Фильтры: роль — радио, «Ждут» / «Позваны» — переключаются повторным нажатием. */}
        <div className="afilter-row" role="group" aria-label={appText("Фильтр листа ожидания", "Көтөү фильтры")}>
          {(
            [
              ["", appText("Все", "Барыһы")],
              ["passenger", appText("Пассажиры", "Пассажирҙар")],
              ["driver", appText("Водители", "Йөрөтөүселәр")],
            ] as [RoleFilter, string][]
          ).map(([key, label]) => (
            <button
              key={key || "all"}
              type="button"
              role="radio"
              aria-checked={roleFilter === key}
              className={"afilter" + (roleFilter === key ? " is-on" : "")}
              onClick={() => setRoleFilter(key)}
            >
              {label}
            </button>
          ))}
          <button
            type="button"
            aria-pressed={invitedFilter === false}
            className={"afilter" + (invitedFilter === false ? " is-on" : "")}
            onClick={() => setInvitedFilter(invitedFilter === false ? null : false)}
          >
            {appText("Ждут", "Көтәләр")}
          </button>
          <button
            type="button"
            aria-pressed={invitedFilter === true}
            className={"afilter" + (invitedFilter === true ? " is-on" : "")}
            onClick={() => setInvitedFilter(invitedFilter === true ? null : true)}
          >
            {appText("Позваны", "Саҡырылған")}
          </button>
        </div>

        {notice && <p className="dl-hint">{notice}</p>}
        {error && <div className="auth__error">{error}</div>}

        {state === "loading" && rows.length === 0 && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && <ListedError onRetry={() => load(roleFilter, invitedFilter)} />}

        {state === "ready" && rows.length === 0 && (
          <ListedEmpty
            title={appText("Пока никого", "Әлегә бер кем дә юҡ")}
            subtitle={appText("Здесь появятся номера с лендинга и из приложения.", "Бында лендингтан һәм ҡушымтанан номерҙар күренер.")}
          />
        )}

        {state !== "error" && rows.map((e) => <WaitRow key={e.id} entry={e} selected={selected.has(e.id)} onToggle={() => toggle(e.id)} />)}

        {/* Пометить волну — появляется, когда что-то выбрано. */}
        {selected.size > 0 && (
          <button type="button" className="abtn abtn--tall" onClick={invite} disabled={inviting}>
            {inviting
              ? appText("…", "…")
              : appText(`Пометить волну (${selected.size})`, `Тулҡынды билдәләү (${selected.size})`)}
          </button>
        )}

        {/* Есть только в вебе: выгрузка CSV для рассылки. */}
        <button type="button" className="abtn abtn--text" onClick={exportCsv}>
          <IconShare size={18} /> {appText("Выгрузить CSV", "CSV төшөрөү")}
        </button>
      </div>
    </>
  );
}

/** Строка листа: чекбокс (позванных заново не помечаем), телефон 16 Bold, «город · роль · дата», бейдж «Позван». */
function WaitRow({ entry, selected, onToggle }: { entry: WaitlistEntry; selected: boolean; onToggle: () => void }) {
  const { appText } = useLang();
  const invited = !!entry.invited_at;
  const meta = [
    entry.city || "",
    entry.role === "driver" ? appText("водитель", "йөрөтөүсе") : appText("пассажир", "пассажир"),
    entry.created_at.slice(0, 10),
  ]
    .filter(Boolean)
    .join("  ·  ");
  return (
    <label className="await">
      <input
        type="checkbox"
        className="await__check"
        checked={selected}
        onChange={onToggle}
        disabled={invited}
        aria-label={appText("Выбрать для волны", "Тулҡынға һайлау")}
      />
      <span className="await__text">
        <strong>{entry.phone}</strong>
        <small>{meta}</small>
      </span>
      {invited && <span className="abadge abadge--ok">{appText("Позван", "Саҡырылған")}</span>}
    </label>
  );
}
