// ================================================================
//  Карточка спора — GET /incidents/{id} + respond / appeal / withdraw.
//  Зеркало Android FairnessScreens.kt (часть IncidentDetail).
//
//  На экране обе версии рядом: что рассказал заявитель и что ответил
//  обвинённый. Право на защиту — с фото. Решение админа показываем
//  целиком, включая причину: «наказали и не объяснили» — худшее,
//  что можно сделать с человеком, который вёз соседа.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, API_BASE, isOwnApiUrl } from "../api/client";
import {
  appealIncident,
  fetchIncident,
  respondIncident,
  withdrawIncident,
  type Incident,
} from "../api/incidents";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconHeart, IconShield, IconWarn } from "../components/Icons";
import { formatWhen, rubLabel } from "../utils/format";
import { incidentStatusLabel } from "./FairnessCenterScreen";

type Status = "loading" | "error" | "ready";
type Sheet = "none" | "respond" | "appeal" | "peace";

/** Защищённые фото лежат на нашем сервере: путь → полный адрес. */
/**
 * Фото-доказательство → адрес для <img>. Только свой хост: ссылку в спор
 * кладёт вторая сторона, и подставить туда чужой адрес — способ узнать IP
 * того, кто откроет карточку. Чужой адрес не показываем вовсе.
 */
function evidenceSrc(url: string): string | null {
  if (!url) return null;
  if (url.startsWith("/")) return `${API_BASE}${url}`;
  return isOwnApiUrl(url) ? url : null;
}

export default function IncidentDetailScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id } = useParams();
  const incidentId = Number(id);

  const [status, setStatus] = useState<Status>("loading");
  const [inc, setInc] = useState<Incident | null>(null);
  const [sheet, setSheet] = useState<Sheet>("none");
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!incidentId) {
        setStatus("error");
        return;
      }
      setStatus("loading");
      fetchIncident(incidentId, signal)
        .then((i) => {
          setInc(i);
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus("error");
        });
    },
    [incidentId]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function submit(kind: Sheet) {
    if (busy || !inc) return;
    setBusy(true);
    setErr("");
    try {
      const text = draft.trim();
      const updated =
        kind === "respond"
          ? await respondIncident(inc.id, text)
          : kind === "appeal"
            ? await appealIncident(inc.id, text)
            : await withdrawIncident(inc.id);
      setInc(updated);
      setSheet("none");
      setDraft("");
    } catch (e) {
      setErr(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  const canRespond = inc?.status === "awaiting_response" && inc.my_role === "respondent";
  const canPeace =
    inc != null && inc.my_role === "reporter" && ["awaiting_response", "under_review"].includes(inc.status);
  const canAppeal = inc?.status === "resolved" && !inc.appeal_text;

  return (
    <>
      <SubHeader title={appText("Разбор спора", "Бәхәсте ҡарау")} onBack={() => navigate(-1)} />

      {status === "loading" && <LoadingList count={2} />}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon state__icon--warn">
            <IconShield size={34} />
          </div>
          <h2>{appText("Не получилось открыть разбор", "Ҡарауҙы асып булманы")}</h2>
          <p>{appText("Проверь сеть и попробуй снова.", "Селтәрҙе тикшереп ҡабат ҡара.")}</p>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" && inc && (
        <>
          <div className="act-card">
            <div className="act-card__title">
              {inc.severe ? <IconWarn size={18} /> : <IconShield size={18} />}
              {incidentStatusLabel(inc.status, appText)}
            </div>
            <p className="act-card__text" style={{ marginBottom: 0 }}>
              {appText("Вторая сторона: ", "Икенсе яҡ: ")}
              {inc.other_name} · {formatWhen(inc.created_at, ru)}
              {inc.booking_route && (
                <>
                  <br />
                  {inc.booking_route}
                </>
              )}
            </p>
          </div>

          {/* Версия заявителя */}
          <h2 className="section-title">
            {inc.my_role === "reporter"
              ? appText("Твоя версия", "Һинең версия")
              : appText(`Версия ${inc.other_name}`, `${inc.other_name} версияһы`)}
          </h2>
          <div className="money-row" style={{ marginTop: 8 }}>
            <p style={{ margin: 0 }}>
              {inc.description || appText("Без описания", "Тасуирламаһыҙ")}
            </p>
            {inc.evidence_urls.filter(evidenceSrc).length > 0 && (
              <div className="doc-photos" style={{ marginTop: 10 }}>
                {inc.evidence_urls.filter(evidenceSrc).map((u, i) => (
                  <a
                    key={u}
                    className="doc-photos__item"
                    href={evidenceSrc(u)!}
                    target="_blank"
                    rel="noreferrer"
                  >
                    <img
                      className="doc-photo"
                      src={evidenceSrc(u)!}
                      alt={appText(
                        `Фото ${i + 1} из ${inc.evidence_urls.filter(evidenceSrc).length} — открыть`,
                        `Фото ${i + 1} / ${inc.evidence_urls.filter(evidenceSrc).length} — асыу`
                      )}
                    />
                  </a>
                ))}
              </div>
            )}
          </div>

          {/* Объяснение обвинённого */}
          {(inc.respondent_statement || inc.respondent_evidence_urls.filter(evidenceSrc).length > 0) && (
            <>
              <h2 className="section-title">
                {inc.my_role === "respondent"
                  ? appText("Твоё объяснение", "Һинең аңлатма")
                  : appText(`Объяснение ${inc.other_name}`, `${inc.other_name} аңлатмаһы`)}
              </h2>
              <div className="money-row" style={{ marginTop: 8 }}>
                <p style={{ margin: 0 }}>
                  {inc.respondent_statement || appText("Без описания", "Тасуирламаһыҙ")}
                </p>
                {inc.responded_at && (
                  <div className="money-row__date">{formatWhen(inc.responded_at, ru)}</div>
                )}
                {inc.respondent_evidence_urls.filter(evidenceSrc).length > 0 && (
                  <div className="doc-photos" style={{ marginTop: 10 }}>
                    {inc.respondent_evidence_urls.filter(evidenceSrc).map((u, i) => (
                      <a
                        key={u}
                        className="doc-photos__item"
                        href={evidenceSrc(u)!}
                        target="_blank"
                        rel="noreferrer"
                      >
                        <img
                          className="doc-photo"
                          src={evidenceSrc(u)!}
                          alt={appText(
                            `Фото ${i + 1} из ${inc.respondent_evidence_urls.filter(evidenceSrc).length} — открыть`,
                            `Фото ${i + 1} / ${inc.respondent_evidence_urls.filter(evidenceSrc).length} — асыу`
                          )}
                        />
                      </a>
                    ))}
                  </div>
                )}
              </div>
            </>
          )}

          {/* Решение */}
          {inc.status === "resolved" && (
            <>
              <h2 className="section-title">{appText("Решение", "Ҡарар")}</h2>
              <div className="act-card act-card--mint">
                <p className="act-card__text" style={{ margin: 0 }}>
                  {inc.resolution_note || appText("Решение принято.", "Ҡарар ҡабул ителде.")}
                </p>
                {inc.compensation_kop > 0 && (
                  <div className="info-row" style={{ marginTop: 10 }}>
                    <span className="info-row__k">{appText("Компенсация", "Компенсация")}</span>
                    <span className="info-row__v">{rubLabel(inc.compensation_kop)}</span>
                  </div>
                )}
                {inc.resolved_at && (
                  <div className="money-row__date">
                    {appText("Решено ", "")}
                    {formatWhen(inc.resolved_at, ru)}
                    {appText("", " — хәл ителде")}
                  </div>
                )}
              </div>
            </>
          )}

          {inc.appeal_text && (
            <div className="act-card">
              <div className="act-card__title">
                <IconWarn size={18} /> {appText("Апелляция", "Ялыу")}
              </div>
              <p className="act-card__text" style={{ marginBottom: 0 }}>
                {inc.appeal_text}
              </p>
            </div>
          )}

          {/* Действия — по одному экрану за раз, без «стены кнопок» */}
          {sheet === "none" && (
            <>
              {canRespond && (
                <div className="act-card act-card--warn">
                  <div className="act-card__title">
                    <IconWarn size={18} /> {appText("Твоя очередь", "Һинең сират")}
                  </div>
                  <p className="act-card__text">
                    {appText(
                      "Расскажи, как было. Твоё объяснение читают до решения — это не формальность.",
                      "Нисек булғанын һөйлә. Аңлатмаңды ҡарар алдынан уҡыйҙар — был формальность түгел."
                    )}
                  </p>
                  <button
                    type="button"
                    className="btn-primary"
                    style={{ width: "100%" }}
                    onClick={() => {
                      setSheet("respond");
                      setDraft("");
                    }}
                  >
                    {appText("Отправить объяснение", "Аңлатманы ебәреү")}
                  </button>
                </div>
              )}

              {canPeace && (
                <div className="act-card">
                  <div className="act-card__title">
                    <IconHeart size={18} /> {appText("Договорились сами?", "Үҙегеҙ килештегеҙме?")}
                  </div>
                  <p className="act-card__text">
                    {appText(
                      "Если вопрос решён между вами — закрой разбор. Никаких последствий для второй стороны не будет.",
                      "Мәсьәлә үҙегеҙ араһында хәл ителһә — бәхәсте яп. Икенсе яҡҡа бер ниндәй ҙә эҙемтә булмаясаҡ."
                    )}
                  </p>
                  <button
                    type="button"
                    className="btn-soft"
                    style={{ width: "100%" }}
                    onClick={() => setSheet("peace")}
                  >
                    {appText("Мы решили миром", "Тыныслыҡ менән хәл иттек")}
                  </button>
                </div>
              )}

              {canAppeal && (
                <div className="act-card">
                  <div className="act-card__title">
                    <IconShield size={18} /> {appText("Не согласен с решением?", "Ҡарар менән килешмәйһеңме?")}
                  </div>
                  <p className="act-card__text">
                    {appText(
                      "Напиши, что, по-твоему, не учли. Разбор посмотрят ещё раз.",
                      "Нимә иҫәпкә алынмағанын яҙ. Бәхәсте тағы бер ҡарайҙар."
                    )}
                  </p>
                  <button
                    type="button"
                    className="btn-soft"
                    style={{ width: "100%" }}
                    onClick={() => {
                      setSheet("appeal");
                      setDraft("");
                    }}
                  >
                    {appText("Подать апелляцию", "Ялыу бирергә")}
                  </button>
                </div>
              )}
            </>
          )}

          {(sheet === "respond" || sheet === "appeal") && (
            <div className="act-card">
              <div className="act-card__title">
                {sheet === "respond"
                  ? appText("Как было на самом деле", "Ысынында нисек булды")
                  : appText("Что не учли при решении?", "Ҡарарҙа нимә иҫәпкә алынмаған?")}
              </div>
              <label className="field" style={{ marginTop: 8 }}>
                <textarea
                  className="field__area"
                  rows={5}
                  maxLength={2000}
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  placeholder={appText("Спокойно и по делу…", "Тыныс һәм эш буйынса…")}
                />
                <span className="field__hint">
                  {appText("До 2000 знаков", "2000 билдәгә тиклем")}
                </span>
              </label>
              {err && <div className="auth__error">{err}</div>}
              <div className="act-card__actions">
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => submit(sheet)}
                  disabled={busy || !draft.trim()}
                >
                  {busy
                    ? appText("Отправляем…", "Ебәрәбеҙ…")
                    : sheet === "respond"
                      ? appText("Отправить", "Ебәреү")
                      : appText("Подать", "Бирергә")}
                </button>
                <button
                  type="button"
                  className="btn-ghost"
                  onClick={() => {
                    setSheet("none");
                    setDraft("");
                    setErr("");
                  }}
                >
                  {appText("Отмена", "Баш тартыу")}
                </button>
              </div>
            </div>
          )}

          {sheet === "peace" && (
            <div className="act-card act-card--mint">
              <div className="act-card__title">
                <IconCheck size={18} /> {appText("Закрыть спор миром?", "Бәхәсте тыныслыҡ менән ябырғамы?")}
              </div>
              <p className="act-card__text">
                {appText(
                  "Разбор закроется, страйков и предупреждений не будет. Отменить это решение нельзя.",
                  "Бәхәс ябыла, страйк та, иҫкәртеү ҙә булмай. Был ҡарарҙы кире ҡайтарып булмай."
                )}
              </p>
              {err && <div className="auth__error">{err}</div>}
              <div className="act-card__actions">
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => submit("peace")}
                  disabled={busy}
                >
                  {busy ? appText("Закрываем…", "Ябабыҙ…") : appText("Да, решили миром", "Эйе, килештек")}
                </button>
                <button type="button" className="btn-ghost" onClick={() => setSheet("none")}>
                  {appText("Отмена", "Баш тартыу")}
                </button>
              </div>
            </div>
          )}

          <p className="receipt__foot">
            {appText(
              "Телефоны сторон в разборе не показываются — только имена. Связь идёт через поддержку.",
              "Бәхәстә телефондар күрһәтелмәй — исемдәр генә. Бәйләнеш ярҙам аша бара."
            )}
          </p>
        </>
      )}
    </>
  );
}
