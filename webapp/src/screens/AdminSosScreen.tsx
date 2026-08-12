// ================================================================
//  Лента SOS → /admin/sos (RequireAdmin).
//  GET /admin/sos?status=open|handled + POST /admin/sos/{id}/handle.
//  Зеркало Android AdminSosScreen.kt.
//
//  Раньше этой ленты НЕ СУЩЕСТВОВАЛО: сигнал уходил одним сообщением
//  в Telegram, статус не менялся никем и никогда, и если сообщение
//  не прочитали (ночь, шумный чат) — следа о происшествии не
//  оставалось нигде. Телефон здесь виден намеренно: админ должен
//  позвонить человеку.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchSosEvents, handleSos, type AdminSosEvent } from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconPhone, IconWarn } from "../components/Icons";
import { formatRelative, formatWhen } from "../utils/format";

type State = "loading" | "error" | "ready";
type Filter = "open" | "handled" | "all";

const FILTERS: { key: Filter; ru: string; ba: string }[] = [
  { key: "open", ru: "Открытые", ba: "Асыҡ" },
  { key: "handled", ru: "Разобранные", ba: "Ҡаралған" },
  { key: "all", ru: "Все", ba: "Барыһы" },
];

/** Категория сигнала — человеческой строкой. */
function categoryLabel(c: string, appText: (r: string, b: string) => string): string {
  switch (c) {
    case "danger":
      return appText("Мне угрожают", "Миңә ҡурҡыныс янай");
    case "accident":
      return appText("ДТП / авария", "Юл-транспорт ваҡиғаһы");
    case "medical":
      return appText("Плохо со здоровьем", "Һаулыҡ насар");
    case "stuck":
      return appText("Застряли в дороге", "Юлда ҡалдыҡ");
    case "other":
      return appText("Другое", "Башҡа");
    default:
      return c || appText("Сигнал SOS", "SOS сигналы");
  }
}

export default function AdminSosScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [filter, setFilter] = useState<Filter>("open");
  const [state, setState] = useState<State>("loading");
  const [rows, setRows] = useState<AdminSosEvent[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [noteFor, setNoteFor] = useState<number | null>(null);
  const [note, setNote] = useState("");

  const load = useCallback((f: Filter, signal?: AbortSignal) => {
    setState("loading");
    fetchSosEvents(f, signal)
      .then((list) => {
        setRows(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 404 = ручки ещё нет на проде → показываем пусто, а не ошибку.
        if (e instanceof ApiError && e.status === 404) {
          setRows([]);
          setState("ready");
        } else setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(filter, ac.signal);
    return () => ac.abort();
  }, [load, filter]);

  async function take(id: number) {
    setBusyId(id);
    try {
      await handleSos(id, note.trim());
      setNoteFor(null);
      setNote("");
      load(filter);
    } catch {
      setBusyId(null);
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Сигналы SOS", "SOS сигналдары")}
        subtitle={appText("Кто просит помощи прямо сейчас", "Кем хәҙер ярҙам һорай")}
        onBack={() => navigate(-1)}
      />

      <div className="seg" style={{ marginTop: 4 }}>
        {FILTERS.map((f) => (
          <button
            key={f.key}
            type="button"
            className={"seg__item" + (filter === f.key ? " is-active" : "")}
            onClick={() => setFilter(f.key)}
          >
            {appText(f.ru, f.ba)}
          </button>
        ))}
      </div>

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load(filter)} />}

      {state === "ready" &&
        (rows.length === 0 ? (
          <div className="state" style={{ paddingTop: 32 }}>
            <div className="state__icon">
              <IconCheck size={34} />
            </div>
            <h2>
              {filter === "open"
                ? appText("Открытых сигналов нет", "Асыҡ сигналдар юҡ")
                : appText("Пока пусто", "Әлегә буш")}
            </h2>
            <p>
              {appText(
                "И пусть так и будет. Каждый сигнал здесь — человек, которому нужна помощь.",
                "Шулай булһын. Бындағы һәр сигнал — ярҙам кәрәк булған кеше."
              )}
            </p>
          </div>
        ) : (
          <div className="admin-cards">
            {rows.map((e) => {
              const open = e.status === "open";
              return (
                <div key={e.id} className="admin-card">
                  <div className="admin-card__head">
                    <div className="admin-card__title">
                      {open && <IconWarn size={16} />} {categoryLabel(e.category, appText)}
                    </div>
                    <span className={"badge " + (open ? "badge--danger" : "badge--mint")}>
                      {open ? appText("Открыт", "Асыҡ") : appText("Принят", "Ҡабул ителгән")}
                    </span>
                  </div>

                  <div className="admin-card__sub">
                    {e.user_name} · {formatRelative(e.created_at, ru)}
                    {e.route && (
                      <>
                        <br />
                        {e.route}
                      </>
                    )}
                  </div>

                  {e.note && <div className="admin-card__reason">{e.note}</div>}

                  {e.user_phone && e.user_phone !== "—" && (
                    <a className="admin-card__phone" href={`tel:${e.user_phone}`}>
                      <IconPhone size={18} /> {e.user_phone}
                    </a>
                  )}

                  {!open && e.handled_at && (
                    <div className="admin-card__sub">
                      {appText("Принят: ", "Ҡабул ителде: ")}
                      {formatWhen(e.handled_at, ru)}
                      {e.handled_note && (
                        <>
                          <br />
                          {e.handled_note}
                        </>
                      )}
                    </div>
                  )}

                  {open &&
                    (noteFor === e.id ? (
                      <>
                        <label className="field" style={{ marginTop: 10 }}>
                          <span className="field__label">
                            {appText("Что сделали (для истории)", "Нимә эшләнең (тарих өсөн)")}
                          </span>
                          <textarea
                            className="field__area"
                            rows={2}
                            maxLength={500}
                            value={note}
                            onChange={(e2) => setNote(e2.target.value)}
                            placeholder={appText(
                              "«Дозвонился, всё в порядке»",
                              "«Шылтыраттым, бөтәһе лә яҡшы»"
                            )}
                          />
                        </label>
                        <div className="act-card__actions" style={{ marginTop: 10 }}>
                          <button
                            type="button"
                            className="btn-primary"
                            onClick={() => take(e.id)}
                            disabled={busyId === e.id}
                          >
                            {busyId === e.id
                              ? appText("Отмечаем…", "Билдәләйбеҙ…")
                              : appText("Принял", "Ҡабул иттем")}
                          </button>
                          <button
                            type="button"
                            className="btn-ghost"
                            onClick={() => {
                              setNoteFor(null);
                              setNote("");
                            }}
                          >
                            {appText("Отмена", "Кире алыу")}
                          </button>
                        </div>
                      </>
                    ) : (
                      <button
                        type="button"
                        className="btn-primary"
                        style={{ width: "100%", marginTop: 10 }}
                        onClick={() => {
                          setNoteFor(e.id);
                          setNote("");
                        }}
                      >
                        <IconCheck size={18} /> {appText("Принять в работу", "Эшкә алыу")}
                      </button>
                    ))}
                </div>
              );
            })}
          </div>
        ))}
    </>
  );
}
