// ================================================================
//  Разбор споров → /admin/incidents (RequireAdmin).
//  GET /admin/incidents?status= + POST /admin/incidents/{id}/resolve.
//  Зеркало Android AdminIncidentsScreen.kt: четыре состояния чипами с подсказкой,
//  карточка с цветной полосой состояния, две версии рядом (кнопка звонка 48),
//  апелляция и решение блоками, «Принять решение» → карточка решения как диалог:
//  «Что решаем», «Кто виноват», пауза в днях, объяснение для обеих сторон.
//
//  Обе версии рядом, телефоны сторон (чтобы позвонить и разобраться
//  по-человечески) и решение с обязательным объяснением: «наказали
//  и не сказали за что» — худшее, что можно сделать с человеком,
//  который вёз соседа.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, API_BASE, isOwnApiUrl } from "../api/client";
import {
  fetchAdminIncidents,
  resolveIncident,
  type AdminIncident,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { RideCardSkeleton, ErrorState, EmptyStateCard } from "../components/States";
import { NearbyChip } from "../components/adminUi";
import { SettingSwitchRow } from "../components/cabinetUi";
import { IconCheck, IconClock, IconPhone, IconShield, IconUsers, IconWarn } from "../components/Icons";
import { formatWhen } from "../utils/format";

type State = "loading" | "error" | "ready";
/**
 * Вкладки те же, что в приложении. «Апелляции» отдельно не для порядка:
 * человек не согласен с уже принятым решением, и такой спор надо перечитать,
 * а не искать его среди сотни решённых. «Все» — только в вебе.
 */
type Filter = "under_review" | "awaiting_response" | "appealed" | "resolved" | "all";

const FILTERS: Filter[] = ["under_review", "awaiting_response", "appealed", "resolved", "all"];

/** Иконка вкладки по смыслу: щит — разбор, часы — ожидание, сигнал — апелляция, галочка — архив. */
function tabIcon(key: Filter, size: number) {
  if (key === "under_review") return <IconShield size={size} />;
  if (key === "awaiting_response") return <IconClock size={size} />;
  if (key === "appealed") return <IconWarn size={size} />;
  if (key === "resolved") return <IconCheck size={size} />;
  return <IconUsers size={size} />;
}

/** Человеческое название типа спора — тот же перечень, что в приложении (FairnessScreens.kt). */
const TYPE_LABEL: Record<string, [string, string]> = {
  rude: ["Нагрубили", "Ҡупал һөйләште"],
  unsafe: ["Опасная езда", "Хәүефле йөрөтөү"],
  non_payment: ["Не заплатили", "Түләмәнеләр"],
  overcharge: ["Взяли больше договорённого", "Килешкәндән артыҡ алдылар"],
  route_detour: ["Повезли не той дорогой", "Икенсе юлдан алып барҙылар"],
  passenger_no_show: ["Пассажир не вышел", "Юлаусы сыҡманы"],
  driver_no_show: ["Водитель не приехал", "Йөрөтөүсе килмәне"],
  harassment: ["Приставания, угрозы", "Бәйләнеү, янау"],
  rules_violation: ["Нарушение правил", "Ҡағиҙәләрҙе боҙоу"],
  parcel_damage: ["Посылку повредили", "Бандеролде боҙғандар"],
  parcel_lost: ["Посылка пропала", "Бандероль юғалған"],
  parcel_delay: ["Сильно опоздали", "Бик һуңланылар"],
  recipient_absent: ["Получателя не было", "Алыусы юҡ ине"],
  wrong_contents: ["Внутри не то", "Эсендә башҡа нәмә"],
};

/** Исходы разбора (resolutionOptions): страйк и пауза — наказания. */
const RESOLUTIONS: { key: string; ru: string; ba: string; strike: boolean }[] = [
  { key: "dismissed", ru: "Не подтвердилось", ba: "Раҫланманы", strike: false },
  { key: "mutual_resolved", ru: "Договорились", ba: "Килештеләр", strike: false },
  { key: "warning", ru: "Предупреждение", ba: "Иҫкәртеү", strike: false },
  { key: "strike", ru: "Страйк", ba: "Страйк", strike: true },
  { key: "suspend", ru: "Пауза аккаунта", ba: "Аккаунт паузаһы", strike: true },
];

const FAULTS: { key: string; ru: string; ba: string }[] = [
  { key: "respondent", ru: "Вторая сторона", ba: "Икенсе яҡ" },
  { key: "reporter", ru: "Заявитель", ba: "Ялыусы" },
  { key: "both", ru: "Оба", ba: "Икеһе лә" },
  { key: "none", ru: "Никто", ba: "Бер кем дә" },
  { key: "unclear", ru: "Не ясно", ba: "Асыҡ түгел" },
];

const PUNISHED = new Set(["warning", "strike", "suspend", "ban"]);

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

/** Версия одной стороны: кто, кнопка звонка 48, текст (или «Пока без объяснения» жёлтым), фото. */
function SideBlock({ who, phone, text, photos }: { who: string; phone: string; text: string; photos: string[] }) {
  const { appText } = useLang();
  const srcs = photos.map(evidenceSrc).filter((u): u is string => !!u);
  return (
    <div className="inc-side">
      <div className="inc-side__head">
        <strong>{who}</strong>
        {phone && (
          <a className="inc-side__call" href={`tel:${phone}`} aria-label={appText("Позвонить — ", "Шылтыратыу — ") + who}>
            <IconPhone size={20} />
          </a>
        )}
      </div>
      {text ? (
        <p className="inc-side__text">{text}</p>
      ) : (
        <span className="inc-side__wait">
          <IconClock size={16} /> {appText("Пока без объяснения", "Әлегә аңлатмаһыҙ")}
        </span>
      )}
      {srcs.length > 0 && (
        <>
          <span className="inc-side__photos">{appText(`Фото: ${srcs.length}`, `Фотолар: ${srcs.length}`)}</span>
          <div className="doc-photos">
            {srcs.map((u, n) => (
              <a key={u} className="doc-photos__item" href={u} target="_blank" rel="noreferrer">
                <img className="doc-photo" src={u} alt={appText(`Фото ${n + 1}`, `Фото ${n + 1}`)} loading="lazy" />
              </a>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

/** ChoiceRow: строка-радио 48, выбранная — мятная с зелёной рамкой и жирной надписью. */
function ChoiceRow({ label, selected, onClick }: { label: string; selected: boolean; onClick: () => void }) {
  return (
    <button type="button" role="radio" aria-checked={selected} className={"choice-row" + (selected ? " is-on" : "")} onClick={onClick}>
      <span className="choice-row__dot" aria-hidden />
      {label}
    </button>
  );
}

export default function AdminIncidentsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [filter, setFilter] = useState<Filter>("under_review");
  const [state, setState] = useState<State>("loading");
  const [rows, setRows] = useState<AdminIncident[]>([]);
  const [openFor, setOpenFor] = useState<number | null>(null);
  const [resolution, setResolution] = useState("");
  const [fault, setFault] = useState("respondent");
  const [note, setNote] = useState("");
  const [days, setDays] = useState("3");
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

  function typeLabel(t: string): string {
    const l = TYPE_LABEL[t];
    return l ? appText(l[0], l[1]) : appText("Спор", "Бәхәс");
  }

  function tabLabel(k: Filter): string {
    if (k === "under_review") return appText("На разборе", "Ҡарала");
    if (k === "awaiting_response") return appText("Ждут ответа", "Яуап көтә");
    if (k === "appealed") return appText("Апелляции", "Ялыуҙар");
    if (k === "resolved") return appText("Решённые", "Хәл ителгән");
    return appText("Все", "Барыһы");
  }

  /** Одна строка про то, что вообще лежит в этой вкладке — чтобы не гадать спросонья. */
  function tabHint(k: Filter): string {
    if (k === "under_review") return appText("Обе версии есть — решение за тобой.", "Ике версия ла бар — ҡарар һиндә.");
    if (k === "awaiting_response") return appText("Ждём объяснения второй стороны.", "Икенсе яҡтың аңлатмаһын көтәбеҙ.");
    if (k === "appealed") return appText("Человек не согласен с решением — перечитай.", "Кеше ҡарар менән килешмәй — ҡабат уҡы.");
    if (k === "resolved") return appText("Архив: решения, которые уже приняты.", "Архив: ҡабул ителгән ҡарарҙар.");
    return appText("Все споры подряд, без фильтра.", "Бөтә бәхәстәр ҙә, фильтрһыҙ.");
  }

  function resolutionLabel(key: string): string {
    const r = RESOLUTIONS.find((x) => x.key === key);
    if (r) return appText(r.ru, r.ba);
    if (key === "ban") return appText("Блокировка", "Блоклау");
    return appText("Решение принято", "Ҡарар ҡабул ителде");
  }

  /** Тон карточки: живая апелляция и тяжёлое — красное, ждущее разбора — жёлтое, закрытое — мятное. */
  function tone(i: AdminIncident): { cls: string; icon: JSX.Element; word: string } {
    if (i.status === "appealed" || i.appeal_status === "requested")
      return { cls: "is-danger", icon: <IconWarn size={20} />, word: appText("Апелляция", "Ялыу") };
    if (i.status === "resolved" || i.status === "closed") return { cls: "is-ok", icon: <IconCheck size={20} />, word: "" };
    if (i.severe) return { cls: "is-danger", icon: <IconWarn size={20} />, word: appText("Срочно", "Ашығыс") };
    return { cls: "is-warn", icon: <IconShield size={20} />, word: "" };
  }

  function openResolve(i: AdminIncident) {
    setOpenFor(i.id);
    setResolution("");
    setFault("respondent");
    setNote("");
    setDays("3");
    setShield(false);
    setErr("");
  }

  async function decide(i: AdminIncident) {
    const chosen = RESOLUTIONS.find((r) => r.key === resolution);
    if (!chosen || !note.trim()) return; // причина обязательна — это правило, а не поле формы
    setBusyId(i.id);
    setErr("");
    try {
      await resolveIncident(i.id, {
        resolution,
        fault,
        note: note.trim(),
        strike: chosen.strike,
        suspend_days: resolution === "suspend" ? Math.max(1, Number(days) || 3) : null,
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
          : appText("Не получилось сохранить решение. Проверь сеть.", "Ҡарарҙы һаҡлап булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusyId(null);
    }
  }

  const chosen = RESOLUTIONS.find((r) => r.key === resolution);
  // Сервер отвергнет «виноват заявитель» вместе с наказанием — предупреждаем ДО отправки.
  const conflict = fault === "reporter" && !!chosen && ["warning", "strike", "suspend"].includes(chosen.key);
  const canSave = busyId === null && !!resolution && !!note.trim() && !conflict;

  return (
    <>
      <SubHeader title={appText("Разбор споров", "Бәхәстәрҙе ҡарау")} onBack={() => navigate(-1)} />
      <div className="alist">
        {/* Четыре состояния спора — одной строкой, а не сеткой 2×2: взгляд идёт слева направо. */}
        <div className="afilter-row" role="group" aria-label={appText("Состояние спора", "Бәхәс хәле")}>
          {FILTERS.map((k) => (
            <NearbyChip key={k} icon={tabIcon(k, 15)} label={tabLabel(k)} active={filter === k} onClick={() => setFilter(k)} />
          ))}
        </div>
        <p key={filter} className="inc-hint">
          {tabIcon(filter, 16)} {tabHint(filter)}
        </p>

        {state === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && <ErrorState onRetry={() => load(filter)} />}
        {state === "ready" && rows.length === 0 && (
          <EmptyStateCard
            icon={<IconUsers size={36} />}
            title={appText("Здесь пусто", "Бында буш")}
            text={appText("Споров в этом состоянии сейчас нет.", "Был хәлдәге бәхәстәр әлегә юҡ.")}
          />
        )}

        {state === "ready" &&
          rows.map((i, idx) => {
            const t = tone(i);
            const liveAppeal = i.status === "appealed" || i.appeal_status === "requested";
            const punished = PUNISHED.has(i.resolution);
            return (
              <article key={i.id} className="inc-card" style={{ animationDelay: `calc(var(--cascade-in) * ${Math.min(idx, 6)})` }}>
                {/* Полоса состояния: суть спора и его вес видно раньше, чем прочитал текст. */}
                <div className={"inc-card__strip " + t.cls}>
                  {t.icon}
                  <strong>{typeLabel(i.type)}</strong>
                  {t.word && <small>{t.word}</small>}
                </div>
                <div className="inc-card__body">
                  <span className="acard__sub">
                    #{i.id} · {i.booking_route || appText("Без маршрута", "Маршрутһыҙ")} · {formatWhen(i.created_at, ru)}
                  </span>

                  {/* Две версии рядом. Пустое объяснение — это ожидание, а не «ничего не было». */}
                  <SideBlock who={appText("Заявитель: ", "Ялыусы: ") + i.reporter_name} phone={i.reporter_phone} text={i.description} photos={i.evidence_urls} />
                  <SideBlock who={appText("Вторая сторона: ", "Икенсе яҡ: ") + i.respondent_name} phone={i.respondent_phone} text={i.respondent_statement} photos={i.respondent_evidence_urls} />

                  {i.appeal_text && (
                    /* Красная только ЖИВАЯ апелляция. Разобранная — обычный блок истории. */
                    <div className={"inc-appeal" + (liveAppeal ? " is-live" : "")}>
                      <small>
                        {appText("Апелляция", "Ялыу")}
                        {i.appeal_status === "accepted" ? appText(" · принята", " · ҡабул ителгән") : i.appeal_status === "rejected" ? appText(" · отклонена", " · кире ҡағылған") : ""}
                      </small>
                      <p>{i.appeal_text}</p>
                    </div>
                  )}

                  {i.resolution && (
                    /* Спор закрыт — карточка спокойная, но само НАКАЗАНИЕ не должно выглядеть как «всё хорошо». */
                    <div className={"inc-resolution" + (punished ? " is-punished" : "")}>
                      <small>
                        {punished ? <IconWarn size={16} /> : <IconCheck size={16} />} {resolutionLabel(i.resolution)}
                      </small>
                      {i.resolution_note && <p>{i.resolution_note}</p>}
                    </div>
                  )}

                  {i.status !== "closed" && openFor !== i.id && (
                    <button
                      type="button"
                      className={i.status === "resolved" ? "btn-soft" : "btn-primary inc-card__act"}
                      onClick={() => openResolve(i)}
                    >
                      <IconCheck size={20} />{" "}
                      {i.status === "resolved" ? appText("Пересмотреть решение", "Ҡарарҙы ҡабат ҡарау") : appText("Принять решение", "Ҡарар ҡабул итеү")}
                    </button>
                  )}

                  {openFor === i.id && (
                    /* ResolveIncidentDialog — в вебе карточкой под спором, содержимое то же. */
                    <div className="inc-resolve">
                      <h3>{appText(`Решение по спору #${i.id}`, `#${i.id} бәхәс буйынса ҡарар`)}</h3>
                      {/* Кто есть кто — иначе «Вторая сторона» ниже это просто слово без лица. */}
                      <div className="inc-resolve__who">
                        <span>{appText("Заявитель: ", "Ялыусы: ") + i.reporter_name}</span>
                        <span>{appText("Вторая сторона: ", "Икенсе яҡ: ") + i.respondent_name}</span>
                      </div>
                      <strong className="inc-resolve__label">{appText("Что решаем", "Нимә хәл итәбеҙ")}</strong>
                      <div className="inc-resolve__choices" role="radiogroup">
                        {RESOLUTIONS.map((r) => (
                          <ChoiceRow key={r.key} label={appText(r.ru, r.ba)} selected={resolution === r.key} onClick={() => setResolution(r.key)} />
                        ))}
                      </div>
                      {/* Наказание — не рядовой выбор: говорим вслух, что оно останется в истории человека. */}
                      {chosen?.strike && (
                        <p className="inc-resolve__warn">
                          <IconWarn size={16} />{" "}
                          {appText(
                            "Это наказание: останется в истории и повлияет на доступ к заказам.",
                            "Был яза: тарихта ҡала һәм заказдарға инеүгә тәьҫир итә."
                          )}
                        </p>
                      )}
                      <strong className="inc-resolve__label">{appText("Кто виноват", "Кем ғәйепле")}</strong>
                      <div className="inc-resolve__choices" role="radiogroup">
                        {FAULTS.map((f) => (
                          <ChoiceRow key={f.key} label={appText(f.ru, f.ba)} selected={fault === f.key} onClick={() => setFault(f.key)} />
                        ))}
                      </div>
                      {resolution === "suspend" && (
                        <label className="field">
                          <span className="field__label">{appText("Пауза, дней", "Пауза, көн")}</span>
                          <input
                            className="field__input"
                            inputMode="numeric"
                            value={days}
                            onChange={(e) => setDays(e.target.value.replace(/\D/g, "").slice(0, 3))}
                          />
                        </label>
                      )}
                      {/* Есть только в вебе: щит рейтинга — спорная оценка перестаёт влиять на средний балл. */}
                      <div className="acard__switch">
                        <SettingSwitchRow
                          icon={<IconShield size={24} />}
                          title={appText("Щит рейтинга", "Рейтинг ҡалҡаны")}
                          subtitle={appText("Снять спорную оценку со среднего — защита оболганного", "Бәхәсле баһаны уртасанан алыу — ғәйепләнгәнде яҡлау")}
                          checked={shield}
                          onChange={setShield}
                        />
                      </div>
                      <label className="field">
                        <span className="field__label">{appText("Объяснение для обеих сторон", "Ике яҡҡа ла аңлатма")}</span>
                        <textarea
                          className="field__input field__area"
                          rows={3}
                          maxLength={2000}
                          value={note}
                          onChange={(e) => setNote(e.target.value)}
                        />
                      </label>
                      <p className="dl-hint">
                        {appText(
                          "Этот текст увидят оба участника — напиши так, чтобы решение было понятно и тому, кто с ним не согласен.",
                          "Был текстты ике ҡатнашыусы ла күрә — килешмәгән кешегә лә аңлайышлы итеп яҙ."
                        )}
                      </p>
                      {conflict && (
                        <p className="inc-resolve__conflict">
                          <IconWarn size={16} />{" "}
                          {appText(
                            "Вина на заявителе — наказание легло бы на обвинённого. Заведи встречный спор, где заявитель будет второй стороной.",
                            "Ғәйеп ялыусыла — яза ғәйепләнеүсегә төшөр ине. Ялыусы икенсе яҡ булған ҡаршы бәхәс ас."
                          )}
                        </p>
                      )}
                      {err && <div className="auth__error">{err}</div>}
                      <div className="settings-confirm__row">
                        <button type="button" className="btn-ghost settings-confirm__muted" onClick={() => setOpenFor(null)} disabled={busyId === i.id}>
                          {appText("Отмена", "Кире алыу")}
                        </button>
                        <button type="button" className="btn-ghost inc-resolve__save" onClick={() => decide(i)} disabled={!canSave}>
                          {busyId === i.id ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить решение", "Ҡарарҙы һаҡлау")}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              </article>
            );
          })}
      </div>
    </>
  );
}
