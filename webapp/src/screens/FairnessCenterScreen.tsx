// ================================================================
//  «Центр справедливости» — GET /me/standing + GET /incidents/mine.
//  Зеркало Android FairnessScreens.kt (часть FairnessCenter).
//
//  Один экран отвечает на два вопроса, которые человек задаёт после
//  плохой поездки: «что теперь со мной?» (надёжность, страйки,
//  пауза) и «что с моим спором?» (чей сейчас ход).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchMyIncidents,
  fetchStanding,
  type Incident,
  type Standing,
} from "../api/incidents";
import { fetchMyRestrictions, type Restriction } from "../api/safety";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconChevron, IconShield, IconWarn } from "../components/Icons";
import { formatWhen } from "../utils/format";

type Status = "loading" | "error" | "soon" | "ready";

/** Подпись состояния спора — одинаковая на всех экранах. */
export function incidentStatusLabel(
  status: string,
  appText: (ru: string, ba: string) => string
): string {
  switch (status) {
    case "awaiting_response":
      return appText("Ждём объяснения", "Аңлатма көтәбеҙ");
    case "under_review":
      return appText("На разборе", "Ҡарала");
    case "appealed":
      return appText("Подана апелляция", "Ялыу бирелгән");
    case "resolved":
      return appText("Решение принято", "Ҡарар ҡабул ителде");
    case "closed":
      return appText("Закрыт миром", "Тыныслыҡ менән ябылды");
    default:
      return appText("Открыт", "Асыҡ");
  }
}

/** Мой ход? Обвинённый ещё не объяснился — значит ждут именно его. */
export function waitsForMe(i: Incident): boolean {
  return i.status === "awaiting_response" && i.my_role === "respondent";
}

export default function FairnessCenterScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [standing, setStanding] = useState<Standing | null>(null);
  const [incidents, setIncidents] = useState<Incident[]>([]);
  // Активные ограничения: что именно нельзя и до когда. Автора жалобы сервер не раскрывает.
  const [limits, setLimits] = useState<Restriction[]>([]);
  /** «Не согласен — напиши нам»: текст приходит с сервера, клиент его не сочиняет. */
  const [supportNote, setSupportNote] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    Promise.all([fetchStanding(signal), fetchMyIncidents(signal)])
      .then(([st, list]) => {
        setStanding(st);
        setIncidents(list);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(e instanceof ApiError && e.status === 404 ? "soon" : "error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Ограничения — отдельным запросом: их отсутствие не должно ломать экран.
  useEffect(() => {
    const ac = new AbortController();
    fetchMyRestrictions(ac.signal)
      .then((r) => {
        setLimits(r.items ?? r.restrictions ?? []);
        setSupportNote((ru ? r.support_ru : r.support_ba) ?? "");
      })
      .catch(() => setLimits([]));
    return () => ac.abort();
  }, []);

  const paused = standing ? !standing.can_act : false;
  const waitingForMe = incidents.filter(waitsForMe).length;

  return (
    <>
      <SubHeader
        title={appText("Центр справедливости", "Ғәҙеллек үҙәге")}
        subtitle={appText("Твоё положение и разборы", "Хәлең һәм бәхәстәр")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconShield size={34} />
          </div>
          <h2>{appText("Скоро здесь", "Тиҙҙән бында")}</h2>
          <p>
            {appText(
              "Раздел включится с ближайшим обновлением сервиса.",
              "Был бүлек яҡын яңыртыуҙа тоташа."
            )}
          </p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon state__icon--warn">
            <IconShield size={34} />
          </div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" && standing && (
        <>
          {/* Моё положение */}
          <div className={"act-card " + (paused ? "act-card--warn" : "act-card--mint")}>
            <div className="act-card__title">
              {paused ? <IconWarn size={18} /> : <IconCheck size={18} />}
              {paused
                ? appText("Аккаунт на паузе", "Аккаунт паузала")
                : appText("Всё в порядке", "Бөтәһе лә тәртиптә")}
            </div>
            <p className="act-card__text" style={{ marginBottom: 0 }}>
              {paused
                ? appText("Новые заказы пока недоступны", "Яңы заказдар әлегә юҡ")
                : appText("С тобой спокойно ехать", "Һинең менән тыныс барырға")}
              {paused && standing.suspended_until && (
                <>
                  <br />
                  {appText("До ", "")}
                  {formatWhen(standing.suspended_until, ru)}
                  {appText("", " ҡәҙәр")}
                </>
              )}
              {paused && standing.suspend_reason && (
                <>
                  <br />
                  {standing.suspend_reason}
                </>
              )}
            </p>
          </div>

          {/* Что именно ограничено и до когда — право знать причину */}
          {limits.map((r) => (
            <div key={r.kind + (r.until ?? "")} className="act-card act-card--warn">
              <div className="act-card__title">
                <IconWarn size={18} /> {ru ? r.title_ru : r.title_ba}
              </div>
              <p className="act-card__text" style={{ marginBottom: 0 }}>
                {ru ? r.note_ru : r.note_ba}
                {r.until && (
                  <>
                    <br />
                    {appText("До ", "")}
                    {formatWhen(r.until, ru)}
                    {appText("", " ҡәҙәр")}
                  </>
                )}
              </p>
            </div>
          ))}

          {/* Дверь для несогласного. Человеку, которому что-то запретили, нужен не только
              список запретов: без этой строки «Справедливость» читается как приговор. */}
          {limits.length > 0 && supportNote && (
            <button
              type="button"
              className="btn-soft"
              style={{ width: "100%" }}
              onClick={() => navigate("/support")}
            >
              {supportNote}
            </button>
          )}

          {/* Надёжность — шкала */}
          <div className="money-total">
            <div className="money-total__label">{appText("Надёжность", "Ышаныслылыҡ")}</div>
            <div className="money-total__value">{standing.reliability}%</div>
            <div className="trust-progress" style={{ marginTop: 10 }}>
              <div
                className="earn-bar__fill"
                style={{ width: `${Math.max(4, Math.min(100, standing.reliability))}%`, height: 8 }}
              />
            </div>
            <div className="money-total__rows">
              <div className="info-row">
                <span className="info-row__k">{appText("Предупреждений", "Иҫкәртеү")}</span>
                <span className="info-row__v">{standing.warnings}</span>
              </div>
              <div className="info-row">
                <span className="info-row__k">{appText("Страйков", "Страйк")}</span>
                <span className="info-row__v">{standing.strikes}</span>
              </div>
              {standing.rating_shield && (
                <div className="info-row">
                  <span className="info-row__k">{appText("Щит рейтинга", "Рейтинг ҡалҡаны")}</span>
                  <span className="info-row__v">
                    <span className="badge badge--mint">
                      <IconShield size={12} /> {appText("Включён", "Ҡабыҙылған")}
                    </span>
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* Мои разборы */}
          <h2 className="section-title">{appText("Мои разборы", "Минең бәхәстәр")}</h2>
          {waitingForMe > 0 && (
            <span className="bargain-turn bargain-turn--mine">
              {appText(`Ждут тебя: ${waitingForMe}`, `Һине көтә: ${waitingForMe}`)}
            </span>
          )}

          {incidents.length === 0 ? (
            <div className="state" style={{ paddingTop: 16 }}>
              <div className="state__icon">
                <IconCheck size={30} />
              </div>
              <h2>{appText("Споров нет", "Бәхәс юҡ")}</h2>
              <p>
                {appText(
                  "И пусть так и будет. Если что-то пойдёт не так — открой разбор с экрана поездки.",
                  "Шулай булһын. Берәй нәмә дөрөҫ бармаһа — сәфәр экранынан бәхәс ас."
                )}
              </p>
              <button type="button" className="btn-soft" onClick={() => load()}>
                {appText("Обновить", "Яңыртыу")}
              </button>
            </div>
          ) : (
            <div className="list" style={{ marginTop: 10 }}>
              {incidents.map((i) => {
                const mine = waitsForMe(i);
                return (
                  <button
                    key={i.id}
                    type="button"
                    className="money-row"
                    style={{ width: "100%", textAlign: "left" }}
                    onClick={() => navigate(`/incidents/${i.id}`)}
                  >
                    <div className="money-row__head">
                      <span className="money-row__route">
                        {i.booking_route || appText("Без маршрута", "Маршрутһыҙ")}
                      </span>
                      <span className={"badge " + (mine ? "badge--gold" : "badge--muted")}>
                        {incidentStatusLabel(i.status, appText)}
                      </span>
                    </div>
                    <div className="money-row__date">
                      {i.my_role === "reporter"
                        ? appText(`Ты открыл · ${i.other_name}`, `Һин астың · ${i.other_name}`)
                        : appText(`Открыл ${i.other_name}`, `${i.other_name} асҡан`)}
                      {" · "}
                      {formatWhen(i.created_at, ru)}
                    </div>
                    {mine && (
                      <div className="money-row__foot">
                        <span className="badge badge--gold">
                          {appText(
                            "Твоя очередь: расскажи, как было",
                            "Һинең сират: нисек булғанын һөйлә"
                          )}
                        </span>
                        <span style={{ marginLeft: "auto", display: "inline-flex" }}>
                          <IconChevron size={18} />
                        </span>
                      </div>
                    )}
                  </button>
                );
              })}
            </div>
          )}

          <p className="receipt__foot">
            {appText(
              "Разбор — это две версии одной истории. Мы читаем обе, прежде чем решить.",
              "Бәхәс — бер хәлдең ике версияһы. Ҡарар алдынан икеһен дә уҡыйбыҙ."
            )}
          </p>
        </>
      )}
    </>
  );
}
