// ================================================================
//  Такси: заявки таксистов + города → /admin/taxi (RequireAdmin).
//  Две вкладки:
//   • «Заявки» (580-ФЗ) — GET /admin/taxi-applications?status=; одобрить/отклонить
//       POST /admin/taxi-applications/{id}/approve|reject. Документы (разрешение/ОСАГО/
//       селфи) защищены → грузим с Bearer через fetchSecureDoc → blob-URL.
//   • «Города» — где включено такси: GET/POST /admin/taxi-cities, DELETE /admin/taxi-cities/{id}.
//  Двуязычно, все состояния, тач-цели ≥48px.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchTaxiApplications,
  approveTaxiApp,
  rejectTaxiApp,
  fetchTaxiCities,
  upsertTaxiCity,
  deleteTaxiCity,
  fetchSecureDoc,
  type TaxiApplication,
  type TaxiCity,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { formatRelative } from "../utils/format";
import { IconCheck, IconPhone, IconCar, IconTrash } from "../components/Icons";

type State = "loading" | "error" | "ready";
type Tab = "apps" | "cities";

/** Защищённое фото документа: тянем с токеном → objectURL, чистим при размонтировании. */
function SecureDoc({ url, cap }: { url: string; cap: string }) {
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

  return (
    <div className="doc-photos__item">
      <span className="doc-photos__cap">{cap}</span>
      {failed ? (
        <div className="doc-photo doc-photo--empty" role="img" aria-label={cap}>
          <span>{appText("Нет фото", "Фото юҡ")}</span>
        </div>
      ) : !src ? (
        <div className="doc-photo skeleton" aria-hidden />
      ) : (
        <a href={src} target="_blank" rel="noreferrer" className="doc-photo">
          <img src={src} alt={cap} loading="lazy" />
        </a>
      )}
    </div>
  );
}

export default function AdminTaxiScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("apps");

  return (
    <>
      <SubHeader
        title={appText("Такси", "Такси")}
        subtitle={appText("Заявки таксистов и города", "Такси заявкалары һәм ҡалалар")}
        onBack={() => navigate(-1)}
      />

      <div className="seg" style={{ marginBottom: 4 }} role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === "apps"}
          className={"seg__item" + (tab === "apps" ? " is-active" : "")}
          onClick={() => setTab("apps")}
        >
          {appText("Заявки", "Заявкалар")}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "cities"}
          className={"seg__item" + (tab === "cities" ? " is-active" : "")}
          onClick={() => setTab("cities")}
        >
          {appText("Города", "Ҡалалар")}
        </button>
      </div>

      {tab === "apps" ? <Applications ru={ru} /> : <Cities />}
    </>
  );
}

const APP_FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "pending", ru: "На проверке", ba: "Тикшереүҙә" },
  { key: "approved", ru: "Одобрены", ba: "Раҫланған" },
  { key: "rejected", ru: "Отклонены", ba: "Кире" },
  { key: "all", ru: "Все", ba: "Барыһы" },
];

function Applications({ ru }: { ru: boolean }) {
  const { appText } = useLang();
  const [filter, setFilter] = useState("pending");
  const [state, setState] = useState<State>("loading");
  const [apps, setApps] = useState<TaxiApplication[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState<{ id: number; msg: string } | null>(null);
  const [rejectingId, setRejectingId] = useState<number | null>(null);
  const [comment, setComment] = useState("");

  const load = useCallback((status: string, signal?: AbortSignal) => {
    setState("loading");
    fetchTaxiApplications(status, signal)
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

  async function approve(id: number) {
    if (busyId) return;
    setBusyId(id);
    setRowError(null);
    try {
      await approveTaxiApp(id);
      setApps((prev) => (filter === "pending" ? prev.filter((a) => a.id !== id) : prev.map((a) => (a.id === id ? { ...a, status: "approved" } : a))));
    } catch (e) {
      setRowError({ id, msg: e instanceof ApiError && e.message ? e.message : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара.") /* DRAFT */ });
    } finally {
      setBusyId(null);
    }
  }

  async function reject(id: number) {
    if (busyId) return;
    setBusyId(id);
    setRowError(null);
    try {
      await rejectTaxiApp(id, comment.trim());
      setApps((prev) => (filter === "pending" ? prev.filter((a) => a.id !== id) : prev.map((a) => (a.id === id ? { ...a, status: "rejected", comment: comment.trim() } : a))));
      setRejectingId(null);
      setComment("");
    } catch (e) {
      setRowError({ id, msg: e instanceof ApiError && e.message ? e.message : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара.") /* DRAFT */ });
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <div className="chip-scroll" role="tablist" aria-label={appText("Фильтр заявок", "Заявка фильтры")}>
        {APP_FILTERS.map((f) => (
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
          <div className="state__icon"><IconCar size={40} /></div>
          <h2>{appText("Пусто", "Буш")}</h2>
          <p>{appText("Заявок в этом разделе нет.", "Был бүлектә заявкалар юҡ.")}</p>
        </div>
      )}

      {state === "ready" && apps.length > 0 && (
        <div className="admin-cards">
          {apps.map((a) => (
            <div key={a.id} className="admin-card">
              <div className="admin-card__head">
                <div className="admin-card__title">{a.name}</div>
                <span className={`badge ${a.status === "approved" ? "badge--mint" : a.status === "rejected" ? "badge--danger" : "badge--gold"}`}>
                  {a.status === "approved" ? appText("Одобрен", "Раҫланған") : a.status === "rejected" ? appText("Отклонён", "Кире") : appText("На проверке", "Тикшереүҙә")}
                </span>
              </div>

              {a.phone && (
                <a className="admin-card__phone" href={`tel:${a.phone}`}>
                  <IconPhone size={16} /> {a.phone}
                </a>
              )}
              <div className="admin-card__sub">
                {appText("ИНН", "ИНН")}: {a.inn} · {appText("разрешение", "рөхсәт")} {a.permit_number}
              </div>
              <div className="admin-card__sub">
                {appText("Класс", "Класс")}: {a.car_class === "comfort" ? appText("Комфорт", "Комфорт") : appText("Эконом", "Эконом")}
                {" · "}
                {appText("стаж с", "стаж")} {a.license_since_year}
              </div>
              {a.invited_by && (
                <div className="admin-card__sub">{appText("Пригласил", "Саҡырҙы")}: {a.invited_by}</div>
              )}
              <div className="admin-card__sub">{formatRelative(a.created_at, ru)}</div>

              {(a.permit_photo_url || a.osago_url || a.selfie_url || a.criminal_record_url) && (
                <div className="doc-photos" style={{ flexWrap: "wrap" }}>
                  {a.permit_photo_url && <SecureDoc url={a.permit_photo_url} cap={appText("Разрешение", "Рөхсәт")} />}
                  {a.osago_url && <SecureDoc url={a.osago_url} cap={appText("ОСАГО", "ОСАГО")} />}
                  {a.selfie_url && <SecureDoc url={a.selfie_url} cap={appText("Селфи с правами", "Права менән селфи")} />}
                  {a.criminal_record_url && <SecureDoc url={a.criminal_record_url} cap={appText("Справка", "Белешмә")} />}
                </div>
              )}

              {a.comment && a.status === "rejected" && (
                <p className="admin-card__sub">{appText("Комментарий", "Аңлатма")}: {a.comment}</p>
              )}

              {rowError?.id === a.id && <div className="auth__error">{rowError.msg}</div>}

              {a.status === "pending" && rejectingId !== a.id && (
                <div className="field-row" style={{ marginTop: 12 }}>
                  <button type="button" className="btn-soft" style={{ flex: 1 }} onClick={() => { setRejectingId(a.id); setComment(""); }} disabled={busyId !== null}>
                    {appText("Отклонить", "Кире ҡағыу")}
                  </button>
                  <button type="button" className="btn-primary" style={{ flex: 1 }} onClick={() => approve(a.id)} disabled={busyId !== null}>
                    {busyId === a.id ? appText("…", "…") : (<><IconCheck size={18} /> {appText("Одобрить", "Раҫлау")}</>)}
                  </button>
                </div>
              )}

              {a.status === "pending" && rejectingId === a.id && (
                <>
                  <textarea
                    className="field__input field__area"
                    style={{ marginTop: 12, minHeight: 76, paddingTop: 12 }}
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    placeholder={appText("Что поправить — водитель увидит и подаст снова", "Нимә төҙәтергә — водитель күрер һәм яңынан ебәрер")}
                  />
                  <div className="field-row" style={{ marginTop: 10 }}>
                    <button type="button" className="btn-soft" style={{ flex: 1 }} onClick={() => setRejectingId(null)} disabled={busyId !== null}>
                      {appText("Назад", "Кире")}
                    </button>
                    <button type="button" className="btn-danger" style={{ flex: 1, marginTop: 0 }} onClick={() => reject(a.id)} disabled={busyId !== null}>
                      {busyId === a.id ? appText("…", "…") : appText("Отклонить", "Кире ҡағыу")}
                    </button>
                  </div>
                </>
              )}
            </div>
          ))}
        </div>
      )}
    </>
  );
}

function Cities() {
  const { appText } = useLang();
  const [state, setState] = useState<State>("loading");
  const [cities, setCities] = useState<TaxiCity[]>([]);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchTaxiCities(signal)
      .then((list) => {
        setCities(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function add() {
    const city = name.trim();
    if (!city || busy) return;
    setBusy(true);
    setError(null);
    try {
      const row = await upsertTaxiCity(city, true);
      setCities((prev) => {
        const rest = prev.filter((c) => c.id !== row.id);
        return [...rest, row].sort((a, b) => a.id - b.id);
      });
      setName("");
    } catch (e) {
      setError(e instanceof ApiError && e.message ? e.message : appText("Не получилось добавить город.", "Ҡала өҫтәп булманы.") /* DRAFT */);
    } finally {
      setBusy(false);
    }
  }

  async function toggle(c: TaxiCity) {
    if (busyId) return;
    setBusyId(c.id);
    setError(null);
    try {
      const row = await upsertTaxiCity(c.city, !c.enabled);
      setCities((prev) => prev.map((x) => (x.id === row.id ? row : x)));
    } catch (e) {
      setError(e instanceof ApiError && e.message ? e.message : appText("Не получилось изменить город.", "Ҡаланы үҙгәртеп булманы.") /* DRAFT */);
    } finally {
      setBusyId(null);
    }
  }

  async function remove(c: TaxiCity) {
    if (busyId) return;
    setBusyId(c.id);
    setError(null);
    try {
      await deleteTaxiCity(c.id);
      setCities((prev) => prev.filter((x) => x.id !== c.id));
    } catch (e) {
      setError(e instanceof ApiError && e.message ? e.message : appText("Не получилось удалить город.", "Ҡаланы юйып булманы.") /* DRAFT */);
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <div className="field-row" style={{ marginTop: 12 }}>
        <input
          className="field__input"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") add(); }}
          placeholder={appText("Добавить город", "Ҡала өҫтәү")}
          style={{ flex: 1 }}
        />
        <button type="button" className="btn-primary" onClick={add} disabled={busy || !name.trim()} style={{ minWidth: 96 }}>
          {busy ? appText("…", "…") : appText("Добавить", "Өҫтәү")}
        </button>
      </div>

      {error && <div className="auth__error" style={{ marginTop: 8 }}>{error}</div>}

      {state === "loading" && <LoadingList count={2} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && cities.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconCar size={40} /></div>
          <h2>{appText("Городов пока нет", "Ҡалалар юҡ әле")}</h2>
          <p>{appText("Добавь первый город, где включаешь такси.", "Такси ҡабыҙған беренсе ҡаланы өҫтә.")}</p>
        </div>
      )}

      {state === "ready" && cities.length > 0 && (
        <div className="admin-cards">
          {cities.map((c) => (
            <div key={c.id} className="admin-card" style={{ padding: 14 }}>
              <div className="admin-card__head" style={{ marginBottom: 0 }}>
                <div className="admin-card__title" style={{ fontSize: "var(--font-body)" }}>
                  {c.city}
                  <span className={`badge ${c.enabled ? "badge--mint" : "badge--muted"}`} style={{ marginLeft: 8 }}>
                    {c.enabled ? appText("вкл", "ҡабыҙылған") : appText("выкл", "һүндерелгән")}
                  </span>
                </div>
              </div>
              <div className="field-row" style={{ marginTop: 12 }}>
                <button type="button" className="btn-soft btn-soft--sm" onClick={() => toggle(c)} disabled={busyId !== null}>
                  {busyId === c.id ? appText("…", "…") : c.enabled ? appText("Выключить", "Һүндереү") : appText("Включить", "Ҡабыҙыу")}
                </button>
                <button type="button" className="btn-soft btn-soft--sm" onClick={() => remove(c)} disabled={busyId !== null} aria-label={appText("Удалить город", "Ҡаланы юйыу")}>
                  <IconTrash size={16} /> {appText("Удалить", "Юйыу")}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
