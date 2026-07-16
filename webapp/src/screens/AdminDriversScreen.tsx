// ================================================================
//  Модерация водителей → /admin/drivers (RequireAdmin).
//  GET /admin/drivers/pending — очередь на проверку (docs_status=pending).
//  Фото прав/авто защищены (GET /secure/docs/{name}, только админ/владелец) —
//  грузим с Bearer-токеном через fetchSecureDoc → blob-URL (<img> не шлёт заголовки).
//  Одобрить/отклонить: POST /admin/drivers/{id}/moderate {approve}.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchPendingDrivers,
  moderateDriver,
  fetchSecureDoc,
  type PendingDriver,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconPhone } from "../components/Icons";

type State = "loading" | "error" | "ready";

/** Автопроверка → цвет/подпись подсказки админу. */
function autocheckBadge(result: string, appText: (r: string, b: string) => string): { cls: string; label: string } | null {
  switch (result) {
    case "pass":
      return { cls: "badge--mint", label: appText("Авто: ок", "Авто: ок") };
    case "needs_human":
      return { cls: "badge--gold", label: appText("Нужен глаз", "Кеше кәрәк") };
    case "reject":
      return { cls: "badge--danger", label: appText("Авто: отказ", "Авто: кире") };
    case "error":
      return { cls: "badge--gold", label: appText("Ошибка проверки", "Тикшереү хатаһы") };
    default:
      return null;
  }
}

/** Защищённое фото документа: тянем с токеном → objectURL, чистим при размонтировании. */
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

export default function AdminDriversScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [drivers, setDrivers] = useState<PendingDriver[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState<{ id: number; msg: string } | null>(null);
  const mounted = useRef(true);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchPendingDrivers(signal)
      .then((list) => {
        setDrivers(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    mounted.current = true;
    const ac = new AbortController();
    load(ac.signal);
    return () => {
      mounted.current = false;
      ac.abort();
    };
  }, [load]);

  async function moderate(userId: number, approve: boolean) {
    if (busyId) return;
    setBusyId(userId);
    setRowError(null);
    try {
      await moderateDriver(userId, approve);
      if (mounted.current) setDrivers((prev) => prev.filter((d) => d.user_id !== userId));
    } catch (e) {
      setRowError({
        id: userId,
        msg:
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара."), // DRAFT
      });
    } finally {
      if (mounted.current) setBusyId(null);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Модерация водителей", "Водителдәрҙе тикшереү")}
        subtitle={appText("Права и авто на проверке", "Права һәм авто тикшереүҙә")}
        onBack={() => navigate(-1)}
      />

      {state === "loading" && <LoadingList count={2} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && drivers.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__emoji">🎉</div>
          <h2>{appText("Очередь пуста", "Сират буш")}</h2>
          <p>{appText("Все заявки водителей проверены.", "Бар водитель заявкалары тикшерелгән.")}</p>
        </div>
      )}

      {state === "ready" && drivers.length > 0 && (
        <div className="admin-cards">
          {drivers.map((d) => {
            const ac = autocheckBadge(d.autocheck_result, appText);
            return (
              <div key={d.user_id} className="admin-card">
                <div className="admin-card__head">
                  <div className="admin-card__title">{d.name}</div>
                  {ac && <span className={`badge ${ac.cls}`}>{ac.label}</span>}
                </div>

                {d.phone && (
                  <a className="admin-card__phone" href={`tel:${d.phone}`}>
                    <IconPhone size={16} /> {d.phone}
                  </a>
                )}
                {d.car && <div className="admin-card__sub">{d.car}</div>}
                {d.autocheck_score > 0 && (
                  <div className="admin-card__sub">
                    {appText("Оценка автопроверки", "Автотикшереү баһаһы")}: {d.autocheck_score.toFixed(2)}
                  </div>
                )}

                <div className="doc-photos">
                  <div className="doc-photos__item">
                    <span className="doc-photos__cap">{appText("Права", "Права")}</span>
                    <SecureImage url={d.license_url} alt={appText("Водительские права", "Водитель праваһы")} />
                  </div>
                  <div className="doc-photos__item">
                    <span className="doc-photos__cap">{appText("Авто", "Авто")}</span>
                    <SecureImage url={d.car_photo_url} alt={appText("Фото авто", "Авто фотоһы")} />
                  </div>
                </div>

                {rowError?.id === d.user_id && <div className="auth__error">{rowError.msg}</div>}

                <div className="field-row" style={{ marginTop: 12 }}>
                  <button
                    type="button"
                    className="btn-danger"
                    style={{ flex: 1, marginTop: 0 }}
                    onClick={() => moderate(d.user_id, false)}
                    disabled={busyId !== null}
                  >
                    {busyId === d.user_id ? appText("…", "…") : appText("Отклонить", "Кире ҡағыу")}
                  </button>
                  <button
                    type="button"
                    className="btn-primary"
                    style={{ flex: 1 }}
                    onClick={() => moderate(d.user_id, true)}
                    disabled={busyId !== null}
                  >
                    {busyId === d.user_id ? (
                      appText("…", "…")
                    ) : (
                      <><IconCheck size={18} /> {appText("Одобрить", "Раҫлау")}</>
                    )}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
