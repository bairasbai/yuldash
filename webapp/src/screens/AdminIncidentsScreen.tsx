// ================================================================
//  Разбор споров → /admin/incidents (RequireAdmin).
//  GET /admin/incidents?status= + POST /admin/incidents/{id}/resolve.
//  Зеркало Android AdminIncidentsScreen.kt.
//
//  Обе версии рядом, телефоны сторон (чтобы позвонить и разобраться
//  по-человечески) и решение с обязательным объяснением: «наказали
//  и не сказали за что» — худшее, что можно сделать с человеком,
//  который вёз соседа.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, API_BASE } from "../api/client";
import {
  fetchAdminIncidents,
  resolveIncident,
  type AdminIncident,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconPhone, IconShield, IconWarn } from "../components/Icons";
import { formatRelative } from "../utils/format";
import { incidentStatusLabel } from "./FairnessCenterScreen";

type State = "loading" | "error" | "ready";
type Filter = "open" | "resolved" | "all";

const FILTERS: { key: Filter; ru: string; ba: string }[] = [
  { key: "open", ru: "Открытые", ba: "Асыҡ" },
  { key: "resolved", ru: "Решённые", ba: "Хәл ителгән" },
  { key: "all", ru: "Все", ba: "Барыһы" },
];

/** Исходы разбора. Каждый — с человеческой подписью, чтобы не выбирать вслепую. */
const OUTCOMES: { key: string; ru: string; ba: string; hint_ru: string; hint_ba: string }[] = [
  {
    key: "dismissed",
    ru: "Не подтвердилось",
    ba: "Раҫланманы",
    hint_ru: "Обвинение не нашло подтверждения — последствий нет",
    hint_ba: "Ғәйепләү раҫланманы — эҙемтә юҡ",
  },
  {
    key: "warning",
    ru: "Предупреждение",
    ba: "Иҫкәртеү",
    hint_ru: "Первый раз, без страйка — человек услышит и поправится",
    hint_ba: "Беренсе тапҡыр, страйкһыҙ — кеше ишетер ҙә төҙәлер",
  },
  {
    key: "strike",
    ru: "Страйк",
    ba: "Страйк",
    hint_ru: "Серьёзно: копится и влияет на надёжность",
    hint_ba: "Етди: йыйыла һәм ышаныслылыҡҡа тәьҫир итә",
  },
  {
    key: "suspend",
    ru: "Пауза аккаунта",
    ba: "Аккаунт паузаһы",
    hint_ru: "Временно без новых заказов — укажи число дней",
    hint_ba: "Ваҡытлыса яңы заказһыҙ — көн һанын күрһәт",
  },
  {
    key: "mutual_resolved",
    ru: "Решили миром",
    ba: "Тыныслыҡ менән",
    hint_ru: "Стороны договорились сами",
    hint_ba: "Яҡтар үҙҙәре килешкән",
  },
];

function evidenceSrc(url: string): string {
  return url.startsWith("http") ? url : `${API_BASE}${url}`;
}

export default function AdminIncidentsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [filter, setFilter] = useState<Filter>("open");
  const [state, setState] = useState<State>("loading");
  const [rows, setRows] = useState<AdminIncident[]>([]);
  const [openFor, setOpenFor] = useState<number | null>(null);
  const [outcome, setOutcome] = useState("dismissed");
  const [note, setNote] = useState("");
  const [days, setDays] = useState("7");
  const [shield, setShield] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [err, setErr] = useState("");

  const load = useCallback((f: Filter, signal?: AbortSignal) => {
    setState("loading");
    fetchAdminIncidents(f, signal)
      .then((list) => {
        setRows(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
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

  async function decide(i: AdminIncident) {
    if (!note.trim()) return; // причина обязательна — это правило, а не поле формы
    setBusyId(i.id);
    setErr("");
    try {
      await resolveIncident(i.id, {
        resolution: outcome,
        fault: outcome === "dismissed" ? "none" : "respondent",
        note: note.trim(),
        strike: outcome === "strike",
        suspend_days: outcome === "suspend" ? Math.max(1, Number(days) || 1) : null,
        shield,
      });
      setOpenFor(null);
      setNote("");
      setShield(false);
      load(filter);
    } catch (e) {
      setErr(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить решение.", "Ҡарарҙы һаҡлап булманы.")
      );
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Разбор споров", "Бәхәстәрҙе ҡарау")}
        subtitle={appText("Обе версии — рядом", "Ике версия — янәшә")}
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
            <h2>{appText("Споров нет", "Бәхәс юҡ")}</h2>
            <p>
              {appText(
                "Люди ездят спокойно. Новые разборы появятся здесь сами.",
                "Кешеләр тыныс йөрөй. Яңы бәхәстәр бында үҙе күренәсәк."
              )}
            </p>
          </div>
        ) : (
          <div className="admin-cards">
            {rows.map((i) => (
              <div key={i.id} className="admin-card">
                <div className="admin-card__head">
                  <div className="admin-card__title">
                    {i.severe ? <IconWarn size={16} /> : <IconShield size={16} />} {i.type}
                  </div>
                  <span
                    className={"badge " + (i.status === "resolved" ? "badge--mint" : "badge--gold")}
                  >
                    {incidentStatusLabel(i.status, appText)}
                  </span>
                </div>

                <div className="admin-card__sub">
                  {i.booking_route || appText("Без маршрута", "Маршрутһыҙ")} ·{" "}
                  {formatRelative(i.created_at, ru)}
                </div>

                {/* Версия заявителя */}
                <div className="money-row" style={{ marginTop: 10 }}>
                  <div className="money-row__head">
                    <span className="money-row__route">
                      {appText("Заявитель: ", "Ғариза биреүсе: ")}
                      {i.reporter_name}
                    </span>
                  </div>
                  <p style={{ margin: "8px 0 0" }}>
                    {i.description || appText("Без описания", "Тасуирламаһыҙ")}
                  </p>
                  {i.reporter_phone && (
                    <a className="admin-card__phone" href={`tel:${i.reporter_phone}`}>
                      <IconPhone size={18} /> {i.reporter_phone}
                    </a>
                  )}
                  {i.evidence_urls.length > 0 && (
                    <div className="doc-photos" style={{ marginTop: 10 }}>
                      {i.evidence_urls.map((u, n) => (
                        <a
                          key={u}
                          className="doc-photos__item"
                          href={evidenceSrc(u)}
                          target="_blank"
                          rel="noreferrer"
                        >
                          <img
                            className="doc-photo"
                            src={evidenceSrc(u)}
                            alt={appText(`Фото ${n + 1}`, `Фото ${n + 1}`)}
                          />
                        </a>
                      ))}
                    </div>
                  )}
                </div>

                {/* Версия второй стороны */}
                <div className="money-row">
                  <div className="money-row__head">
                    <span className="money-row__route">
                      {appText("Вторая сторона: ", "Икенсе яҡ: ")}
                      {i.respondent_name}
                    </span>
                  </div>
                  <p style={{ margin: "8px 0 0" }}>
                    {i.respondent_statement ||
                      appText("Ещё не объяснился", "Әле аңлатма бирмәгән")}
                  </p>
                  {i.respondent_phone && (
                    <a className="admin-card__phone" href={`tel:${i.respondent_phone}`}>
                      <IconPhone size={18} /> {i.respondent_phone}
                    </a>
                  )}
                  {i.respondent_evidence_urls.length > 0 && (
                    <div className="doc-photos" style={{ marginTop: 10 }}>
                      {i.respondent_evidence_urls.map((u, n) => (
                        <a
                          key={u}
                          className="doc-photos__item"
                          href={evidenceSrc(u)}
                          target="_blank"
                          rel="noreferrer"
                        >
                          <img
                            className="doc-photo"
                            src={evidenceSrc(u)}
                            alt={appText(`Фото ${n + 1}`, `Фото ${n + 1}`)}
                          />
                        </a>
                      ))}
                    </div>
                  )}
                </div>

                {i.appeal_text && (
                  <div className="admin-card__reason">
                    {appText("Апелляция: ", "Ялыу: ")}
                    {i.appeal_text}
                  </div>
                )}

                {i.status === "resolved" ? (
                  <div className="act-card act-card--mint" style={{ marginTop: 10 }}>
                    <p className="act-card__text" style={{ margin: 0 }}>
                      {i.resolution_note || appText("Решение принято.", "Ҡарар ҡабул ителде.")}
                    </p>
                  </div>
                ) : openFor === i.id ? (
                  <>
                    <span className="field__label" style={{ marginTop: 12, display: "block" }}>
                      {appText("Решение", "Ҡарар")}
                    </span>
                    <div className="chips" style={{ marginTop: 6 }}>
                      {OUTCOMES.map((o) => (
                        <button
                          key={o.key}
                          type="button"
                          className={"chip" + (outcome === o.key ? " chip--on" : "")}
                          onClick={() => setOutcome(o.key)}
                        >
                          {appText(o.ru, o.ba)}
                        </button>
                      ))}
                    </div>
                    <p className="demand__quiet">
                      {(() => {
                        const o = OUTCOMES.find((x) => x.key === outcome);
                        return o ? appText(o.hint_ru, o.hint_ba) : "";
                      })()}
                    </p>

                    {outcome === "suspend" && (
                      <label className="field" style={{ marginTop: 8 }}>
                        <span className="field__label">{appText("Дней паузы", "Пауза көндәре")}</span>
                        <input
                          className="field__input"
                          type="number"
                          min={1}
                          max={3650}
                          value={days}
                          onChange={(e) => setDays(e.target.value)}
                        />
                      </label>
                    )}

                    <label className="list-row list-row--check" style={{ marginTop: 8 }}>
                      <span className="list-row__icon">
                        <IconShield size={20} />
                      </span>
                      <div className="list-row__main">
                        <div className="list-row__title">{appText("Щит рейтинга", "Рейтинг ҡалҡаны")}</div>
                        <div className="list-row__sub">
                          {appText(
                            "Снять спорную оценку со среднего — защита оболганного",
                            "Бәхәсле баһаны уртасанан алыу — ғәйепләнгәнде яҡлау"
                          )}
                        </div>
                      </div>
                      <input
                        type="checkbox"
                        className="checkbox"
                        checked={shield}
                        onChange={() => setShield(!shield)}
                        aria-label={appText("Щит рейтинга", "Рейтинг ҡалҡаны")}
                      />
                    </label>

                    <label className="field" style={{ marginTop: 8 }}>
                      <span className="field__label">
                        {appText("Объяснение для обеих сторон", "Аңлатма (ике яҡ та күрәсәк)")}
                      </span>
                      <textarea
                        className="field__area"
                        rows={3}
                        maxLength={2000}
                        value={note}
                        onChange={(e) => setNote(e.target.value)}
                        placeholder={appText(
                          "Что решили и почему — по-человечески",
                          "Нимә хәл ителде һәм ниңә — кешесә"
                        )}
                      />
                      <span className="field__hint">
                        {appText("Без объяснения решение не сохранится", "Аңлатмаһыҙ ҡарар һаҡланмай")}
                      </span>
                    </label>

                    {err && <div className="auth__error">{err}</div>}

                    <div className="act-card__actions" style={{ marginTop: 10 }}>
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => decide(i)}
                        disabled={busyId === i.id || !note.trim()}
                      >
                        {busyId === i.id
                          ? appText("Сохраняем…", "Һаҡлайбыҙ…")
                          : appText("Принять решение", "Ҡарар ҡабул итеү")}
                      </button>
                      <button
                        type="button"
                        className="btn-ghost"
                        onClick={() => {
                          setOpenFor(null);
                          setNote("");
                          setErr("");
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
                      setOpenFor(i.id);
                      setOutcome("dismissed");
                      setNote("");
                      setShield(false);
                      setErr("");
                    }}
                  >
                    {appText("Разобрать", "Ҡарарға")}
                  </button>
                )}
              </div>
            ))}
          </div>
        ))}
    </>
  );
}
