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
import { RideCardSkeleton } from "../components/States";
import { AdminFilterChips, AdminIntro, AdminStatusBadge, ListedEmpty, ListedError } from "../components/adminUi";
import { IconBox, IconCar } from "../components/Icons";

type State = "loading" | "error" | "ready";

const FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "pending", ru: "На проверке", ba: "Тикшереүҙә" },
  { key: "approved", ru: "Одобрены", ba: "Раҫланған" },
  { key: "rejected", ru: "Отклонены", ba: "Кире ҡағылған" },
  { key: "all", ru: "Все", ba: "Барыһы" },
];

/** courierTransportLabel: легковой / грузовой, незнакомое — как есть. */
function transportLabel(t: string, appText: (r: string, b: string) => string): string {
  const k = t.toLowerCase();
  if (k === "car") return appText("Легковой", "Еңел машина");
  if (k === "cargo") return appText("Грузовой", "Йөк машинаһы");
  return t;
}

/** Защищённое селфи: тянем с токеном → objectURL, чистим при размонтировании. */
function SecureImage({ url, alt }: { url: string | null; alt: string }) {
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
    return <small className="adoc-none">{appText("нет файла", "файл юҡ")}</small>;
  }
  if (!src) {
    return <div className="adoc adoc--tall skeleton" aria-hidden />;
  }
  return (
    <a href={src} target="_blank" rel="noreferrer" className="adoc adoc--tall">
      <img src={src} alt={alt} loading="lazy" />
    </a>
  );
}

export default function AdminCourierScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [filter, setFilter] = useState<string>("pending");
  const [state, setState] = useState<State>("loading");
  const [apps, setApps] = useState<AdminCourierApplication[]>([]);

  const load = useCallback((status: string, signal?: AbortSignal) => {
    setState("loading");
    fetchCourierApplications(status, signal)
      .then((list) => {
        // pending — сверху, затем по дате (свежие выше), как в приложении.
        const rank = (a: AdminCourierApplication) => (a.status === "pending" ? 1 : 0);
        setApps([...list].sort((a, b) => rank(b) - rank(a) || (b.created_at ?? "").localeCompare(a.created_at ?? "")));
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
      <SubHeader title={appText("Курьеры", "Курьерҙар")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Заявки «Стать курьером»: сверь селфи с документом, транспорт и кто пригласил, потом одобри или отклони с причиной.",
            "«Курьер булыу» заявкалары: документ менән селфины, транспортты һәм кем саҡырғанын тикшер, аҙаҡ раҫла йәки сәбәп менән кире ҡаҡ."
          )}
        </AdminIntro>

        <AdminFilterChips
          label={appText("Фильтр заявок", "Заявка фильтры")}
          options={FILTERS.map((f) => ({ key: f.key, label: appText(f.ru, f.ba) }))}
          value={filter}
          onChange={setFilter}
        />

        {state === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && <ListedError onRetry={() => load(filter)} />}

        {state === "ready" && apps.length === 0 && (
          <ListedEmpty
            title={appText("Заявок нет", "Заявка юҡ")}
            subtitle={appText("Здесь появятся соседи, которые хотят возить посылки.", "Бында бандероль илтергә теләгән күршеләр күренер.")}
          />
        )}

        {state === "ready" && apps.map((a, i) => <CourierCard key={a.id} app={a} index={i} onPatch={patch} />)}
      </div>
    </>
  );
}

function CourierCard({
  app,
  index,
  onPatch,
}: {
  app: AdminCourierApplication;
  index: number;
  onPatch: (id: number, status: string) => void;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<null | "approve" | "reject">(null);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");

  function fail(e: unknown) {
    setError(
      e instanceof ApiError && e.message
        ? e.message
        : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
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

  const isCargo = app.transport === "cargo";

  return (
    <article className="acard" style={{ animationDelay: `calc(var(--cascade-in) * ${Math.min(index, 6)})` }}>
      <div className="acard__row">
        <strong className="acard__title acard__grow">{app.name || appText("Без имени", "Исемһеҙ")}</strong>
        <AdminStatusBadge status={app.status} />
      </div>
      {app.phone && <span className="acard__sub">{app.phone}</span>}
      <span className="acard__text acard__iconline acard__iconline--green">
        {isCargo ? <IconBox size={18} /> : <IconCar size={18} />}
        {appText("Транспорт: ", "Транспорт: ") + transportLabel(app.transport, appText)}
      </span>
      {app.invited_by_name && <span className="atext atext--green acard__caption">{appText("Пригласил: ", "Саҡырҙы: ") + app.invited_by_name}</span>}
      {app.selfie_url && (
        <>
          <span className="acard__label">{appText("Селфи с документом (сверь лицо)", "Документ менән селфи (йөҙҙө сағыштыр)")}</span>
          <SecureImage url={app.selfie_url} alt={appText("Фото документа курьера", "Курьер документы фотоһы")} />
        </>
      )}
      {app.status === "rejected" && app.reject_reason && (
        <span className="acard__text acard__text--danger">{appText("Причина: ", "Сәбәбе: ") + app.reject_reason}</span>
      )}

      {error && <div className="auth__error">{error}</div>}

      {app.status === "pending" && (
        <>
          <div className="acard__actions">
            <button type="button" className="abtn abtn--48" onClick={approve} disabled={busy !== null}>
              {busy === "approve" ? appText("…", "…") : appText("Одобрить", "Раҫлау")}
            </button>
            <button
              type="button"
              className="abtn abtn--48 abtn--outline abtn--red"
              onClick={() => {
                setRejecting((v) => !v);
                setReason("");
              }}
              disabled={busy !== null}
            >
              {appText("Отклонить", "Кире ҡағыу")}
            </button>
          </div>
          {rejecting && (
            <div className="acard__reject">
              <label className="field">
                <span className="field__label">{appText("Почему отклоняешь (увидит курьер)", "Ниңә кире ҡағаһың (курьер күрер)")}</span>
                <input className="field__input" value={reason} onChange={(e) => setReason(e.target.value.slice(0, 300))} autoFocus />
              </label>
              <button type="button" className="abtn abtn--48 abtn--danger" onClick={reject} disabled={busy !== null || !reason.trim()}>
                {busy === "reject" ? appText("…", "…") : appText("Отклонить с причиной", "Сәбәп менән кире ҡағыу")}
              </button>
            </div>
          )}
        </>
      )}
    </article>
  );
}
