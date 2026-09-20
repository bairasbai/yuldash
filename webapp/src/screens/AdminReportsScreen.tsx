// ================================================================
//  Жалобы → /admin/reports (RequireAdmin).
//  GET /admin/reports?status= — очередь жалоб (автора видит только админ).
//  Действия: подтвердить (resolve) / отклонить (reject); «оставить паузу»
//  (keep_pause) для тяжёлых категорий — §9 лестница мер срабатывает на бэке.
//  Меры вручную: пауза/снятие паузы такси цели (quality pause/unpause).
//  Вид — зеркало AdminReportsContent (SecondaryScreens.kt): вводная строка,
//  карточка с рамкой (тяжёлая открытая — красноватой), тег категории и статус
//  в одной строке с датой, «кто → на кого», текст, кнопки разбора, меры.
//  Фильтр по статусу есть только в вебе — оставлен рядом чипами.
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
import { AdminFilterChips, AdminIntro, AdminTag, ListedEmpty, ListedError, ListedLoading } from "../components/adminUi";

type State = "loading" | "error" | "ready";

const FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "new", ru: "Новые", ba: "Яңы" },
  { key: "reviewing", ru: "В разборе", ba: "Тикшереүҙә" },
  { key: "resolved", ru: "Подтверждены", ba: "Раҫланған" },
  { key: "rejected", ru: "Отклонены", ba: "Кире ҡағылған" },
  { key: "", ru: "Все", ba: "Барыһы" },
];

/** Категории §9 — те же id и слова, что в перечне жалобы (reportCategoriesAll). */
const CAT_LABEL: Record<string, [string, string]> = {
  rude: ["Нахамил", "Тупаҫланды"],
  kicked_out: ["Высадил в пути", "Юлда төшөрөп ҡалдырҙы"],
  dangerous_driving: ["Опасное вождение", "Хәүефле йөрөтөү"],
  price_fraud: ["Обман с ценой", "Хаҡ менән алдау"],
  dirty_car: ["Грязная машина", "Бысраҡ машина"],
  late: ["Опоздал", "Һуңланы"],
  safety_threat: ["Угроза безопасности", "Хәүефһеҙлеккә янау"],
  no_show: ["Не пришёл к машине", "Машинаға килмәне"],
  damage: ["Испортил машину", "Машинаны боҙҙо"],
  unpaid: ["Не заплатил", "Түләмәне"],
  other: ["Другое", "Башҡа"],
};

/** Тяжёлые категории (⛔ §9): мгновенная пауза такси до разбора. */
const SEVERE = new Set(["safety_threat", "kicked_out", "dangerous_driving"]);

export default function AdminReportsScreen() {
  const { appText } = useLang();
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
      <SubHeader title={appText("Жалобы", "Ялыуҙар")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Жалобы пользователей. Подтверди или отклони — лестница наказаний дальше считается сама. Автора видишь только ты.",
            "Ҡулланыусы ялыуҙары. Раҫла йәки кире ҡаҡ — язалар баҫҡысы артабан үҙе иҫәпләнә. Авторҙы тик һин күрәһең."
          )}
        </AdminIntro>

        <AdminFilterChips
          label={appText("Фильтр жалоб", "Ялыу фильтры")}
          options={FILTERS.map((f) => ({ key: f.key, label: appText(f.ru, f.ba) }))}
          value={filter}
          onChange={setFilter}
        />

        {state === "loading" && <ListedLoading />}
        {state === "error" && <ListedError onRetry={() => load(filter)} />}

        {state === "ready" && reports.length === 0 && (
          <ListedEmpty
            title={appText("Жалоб нет", "Ялыу юҡ")}
            subtitle={appText("Хороший знак — пользователи довольны.", "Яҡшы билдә — ҡулланыусылар риза.")}
          />
        )}

        {state === "ready" && reports.map((r) => <ReportCard key={r.id} report={r} onPatch={patch} />)}
      </div>
    </>
  );
}

function ReportCard({ report, onPatch }: { report: AdminReport; onPatch: (r: AdminReport) => void }) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<null | "resolve" | "reject" | "pause" | "unpause">(null);
  const [error, setError] = useState<string | null>(null);
  const [pauseMsg, setPauseMsg] = useState<string | null>(null);

  const open = report.status === "new" || report.status === "reviewing";
  const severe = SEVERE.has(report.category);
  const cat = CAT_LABEL[report.category] ?? CAT_LABEL.other;
  const [stLabel, stTone] =
    report.status === "resolved"
      ? [appText("Подтверждена", "Раҫланған"), "green"]
      : report.status === "rejected"
        ? [appText("Отклонена", "Кире ҡағылған"), "muted"]
        : report.status === "reviewing"
          ? [appText("В разборе", "Тикшереүҙә"), "warn"]
          : [appText("Новая", "Яңы"), "warn"];

  async function act(kind: "resolve" | "reject", keepPause = false) {
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
    <article className={"acard acard--tight" + (severe && open ? " acard--severe" : "")}>
      <div className="acard__row">
        <AdminTag tone={severe ? "red" : "green"}>{appText(cat[0], cat[1])}</AdminTag>
        <span className={`atext atext--${stTone}`}>{stLabel}</span>
        <span className="acard__spacer" />
        {report.created_at.length >= 10 && <small className="acard__date">{report.created_at.slice(0, 10)}</small>}
      </div>
      <strong className="acard__title">{report.reporter_name}  →  {report.target_name}</strong>
      {report.target_phone && (
        <a className="acard__sub acard__link" href={`tel:${report.target_phone}`}>
          {report.target_phone}
        </a>
      )}
      <p className="acard__text">{report.reason || appText("без деталей", "ентекһеҙ")}</p>
      {report.resolution && (
        <span className="acard__sub">{appText("Решение: ", "Ҡарар: ") + report.resolution}</span>
      )}

      {error && <div className="auth__error">{error}</div>}
      {pauseMsg && <span className="acard__sub">{pauseMsg}</span>}

      {open && (
        <>
          <div className="acard__actions">
            <button type="button" className="abtn" onClick={() => act("resolve")} disabled={busy !== null}>
              {busy === "resolve" ? appText("…", "…") : appText("Подтвердить", "Раҫлау")}
            </button>
            <button
              type="button"
              className="abtn abtn--outline abtn--red"
              onClick={() => act("reject")}
              disabled={busy !== null}
            >
              {busy === "reject" ? appText("…", "…") : appText("Отклонить", "Кире ҡағыу")}
            </button>
          </div>
          {/* ⛔ Тяжёлая: пауза стоит «до разбора» — можно подтвердить, ОСТАВИВ паузу. */}
          {severe && (
            <button
              type="button"
              className="abtn abtn--text abtn--warn"
              onClick={() => act("resolve", true)}
              disabled={busy !== null}
            >
              {appText("Подтвердить и оставить паузу такси", "Раҫлап такси паузаһын ҡалдырыу")}
            </button>
          )}
        </>
      )}

      {/* Ручные меры по цели — доступны всегда (в т.ч. после разбора). */}
      {report.target_user_id > 0 && (
        <div className="acard__actions">
          <button type="button" className="abtn abtn--text abtn--warn abtn--small" onClick={() => pause(true)} disabled={busy !== null}>
            {busy === "pause" ? appText("…", "…") : appText("⏸ Пауза такси 72ч", "⏸ Такси паузаһы 72сәғ")}
          </button>
          <button type="button" className="abtn abtn--text abtn--small" onClick={() => pause(false)} disabled={busy !== null}>
            {busy === "unpause" ? appText("…", "…") : appText("▶ Снять паузу", "▶ Паузаны алыу")}
          </button>
        </div>
      )}
    </article>
  );
}
