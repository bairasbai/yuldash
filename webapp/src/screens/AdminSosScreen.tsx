// ================================================================
//  Лента SOS → /admin/sos (RequireAdmin).
//  GET /admin/sos?status=open|handled + POST /admin/sos/{id}/handle.
//  Зеркало Android AdminSosScreen.kt: чипы «Открытые / Разобранные», красная
//  шапка «Ждут помощи: N» с дышащей иконкой, карточка с цветной полосой категории,
//  имя 19, маршрут, большая кнопка «Позвонить», слова человека в рамке, след
//  разбора мятным, «Отметить, что принял» → заметка и «Принял».
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
import { RideCardSkeleton, ErrorState, EmptyStateCard } from "../components/States";
import { NearbyChip } from "../components/adminUi";
import { IconCar, IconCheck, IconClock, IconHospital, IconInfo, IconPhone, IconUsers, IconWarn } from "../components/Icons";
import { formatWhen } from "../utils/format";

type State = "loading" | "error" | "ready";
type Filter = "open" | "handled" | "all";

/** Иконка и подпись категории сигнала (значения сервера: medical | breakdown | other). */
function categoryBadge(c: string, appText: (r: string, b: string) => string): { icon: JSX.Element; label: string } {
  if (c === "medical") return { icon: <IconHospital size={20} />, label: appText("Плохо человеку", "Кешегә насар") };
  if (c === "breakdown") return { icon: <IconCar size={20} />, label: appText("Машина сломалась", "Машина ватылған") };
  return { icon: <IconInfo size={20} />, label: appText("Другое", "Башҡа") };
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
  const [err, setErr] = useState("");

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
    setErr("");
    try {
      await handleSos(id, note.trim());
      setNoteFor(null);
      setNote("");
      load(filter);
    } catch {
      // Без этой ветки сбой сети выглядел как успех. Для SOS это худший из тихих провалов.
      setErr(
        appText(
          "Не удалось отметить сигнал. Проверь сеть и повтори — он остался открытым.",
          "Сигналды билдәләп булманы. Селтәрҙе тикшереп ҡабатла — ул асыҡ ҡалды."
        )
      );
    } finally {
      setBusyId(null);
    }
  }

  // Шапка списка говорит главное ещё до чтения карточек: «есть открытые» / «это архив».
  const banner = state === "loading" && rows.length === 0 ? "" : filter === "open" && rows.length > 0 ? "alarm" : filter !== "open" ? "history" : "";

  return (
    <>
      <SubHeader title={appText("Сигналы SOS", "SOS сигналдары")} onBack={() => navigate(-1)} />
      <div className="alist">
        {/* В приложении два чипа; «Все» — только в вебе, тем же чипом. */}
        <div className="afilter-row" role="group" aria-label={appText("Состояние сигнала", "Сигнал хәле")}>
          <NearbyChip icon={<IconWarn size={15} />} label={appText("Открытые", "Асыҡ")} active={filter === "open"} onClick={() => setFilter("open")} />
          <NearbyChip icon={<IconClock size={15} />} label={appText("Разобранные", "Ҡаралған")} active={filter === "handled"} onClick={() => setFilter("handled")} />
          <NearbyChip icon={<IconUsers size={15} />} label={appText("Все", "Барыһы")} active={filter === "all"} onClick={() => setFilter("all")} />
        </div>

        {banner === "alarm" && (
          /* Красная шапка: сколько сигналов ждут ответа. Иконка «дышит» — это тревога, а не декор. */
          <div className="asos-alarm" role="status">
            <span className="asos-alarm__icon" aria-label={appText("Открытые сигналы SOS", "Асыҡ SOS сигналдары")}><IconWarn size={24} /></span>
            <span className="asos-alarm__text">
              <strong>{appText(`Ждут помощи: ${rows.length}`, `Ярҙам көтә: ${rows.length}`)}</strong>
              <span>{appText("Сначала позвони — разбираться будешь потом.", "Башта шылтырат — аҙаҡ асыҡларһың.")}</span>
            </span>
          </div>
        )}
        {banner === "history" && (
          <p className="inc-hint">
            <IconClock size={16} /> {appText("Видно, кто принял сигнал и что сделал.", "Сигналды кем ҡабул иткәнен һәм нимә эшләгәнен күреп була.")}
          </p>
        )}

        {state === "loading" && rows.length === 0 && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && <ErrorState onRetry={() => load(filter)} />}
        {state === "ready" && rows.length === 0 && (
          <EmptyStateCard
            icon={<IconCheck size={36} />}
            title={filter === "open" ? appText("Открытых сигналов нет", "Асыҡ сигнал юҡ") : appText("Разобранных пока нет", "Ҡаралғандары әлегә юҡ")}
            text={
              filter === "open"
                ? appText("Тишина — это хорошая новость. Все дома.", "Тынлыҡ — ул яҡшы хәбәр. Бөтәһе лә өйҙә.")
                : appText("Здесь будут сигналы, которые ты уже принял.", "Бында һин ҡабул иткән сигналдар буласаҡ.")
            }
          />
        )}

        {state === "ready" &&
          rows.map((e, idx) => {
            const open = e.status === "open";
            const cat = categoryBadge(e.category, appText);
            const hasPhone = !!e.user_phone && e.user_phone !== "—";
            const stamp = e.handled_at ? formatWhen(e.handled_at, ru) : "";
            return (
              <article key={e.id} className="inc-card" style={{ animationDelay: `calc(var(--cascade-in) * ${Math.min(idx, 6)})` }}>
                {/* Полоса состояния во всю ширину: цвет карточки читается раньше, чем текст. */}
                <div className={"inc-card__strip " + (open ? "is-danger" : "is-ok")}>
                  {cat.icon}
                  <strong>{cat.label}</strong>
                  <small>{open ? appText("Открыт", "Асыҡ") : appText("Разобран", "Ҡаралған")}</small>
                </div>
                <div className="inc-card__body">
                  {/* Кто и когда. Имя — самое крупное на карточке: за сигналом стоит человек. */}
                  <div className="asos-who">
                    <strong>{e.user_name || appText("Без имени", "Исемһеҙ")}</strong>
                    <span>{formatWhen(e.created_at, ru)}</span>
                  </div>
                  {e.route && (
                    <span className="asos-route">
                      <IconCar size={16} /> {e.route}
                    </span>
                  )}

                  {/* Главное действие стоит ВЫШЕ текста сигнала намеренно: человеку в беде нужен голос. */}
                  {hasPhone ? (
                    <a className={open ? "btn-danger asos-call asos-call--open" : "btn-soft asos-call"} href={`tel:${e.user_phone}`}>
                      <IconPhone size={20} /> {appText("Позвонить", "Шылтыратыу")} {e.user_phone}
                    </a>
                  ) : (
                    <span className="acard__sub">
                      {appText("Телефон не передан — свяжись через чат поездки.", "Телефон бирелмәгән — сәфәр чаты аша бәйләнеш ҡор.")}
                    </span>
                  )}

                  {/* Слова человека: без красной заливки, но с красной волосяной рамкой у открытого. */}
                  {e.note && <p className={"asos-note" + (open ? " is-open" : "")}>{e.note}</p>}

                  {/* След разбора: важен сам факт «принято» и когда, даже без заметки. */}
                  {!open && (e.handled_note || stamp) && (
                    <div className="inc-resolution">
                      <small>
                        {(e.handled_note ? appText("Что сделали", "Нимә эшләнде") : appText("Принято", "Ҡабул ителгән")) + (stamp ? ` · ${stamp}` : "")}
                      </small>
                      {e.handled_note && <p>{e.handled_note}</p>}
                    </div>
                  )}

                  {err && noteFor === e.id && <div className="auth__error">{err}</div>}

                  {open &&
                    (noteFor === e.id ? (
                      <div className="asos-handle">
                        <label className="field">
                          <span className="field__label">{appText("Что сделали (для истории)", "Нимә эшләнде (тарих өсөн)")}</span>
                          <textarea className="field__input field__area" rows={2} maxLength={500} value={note} onChange={(e2) => setNote(e2.target.value)} />
                        </label>
                        <div className="acard__actions">
                          <button
                            type="button"
                            className="btn-soft"
                            onClick={() => {
                              setNoteFor(null);
                              setNote("");
                            }}
                            disabled={busyId === e.id}
                          >
                            {appText("Отмена", "Кире алыу")}
                          </button>
                          <button type="button" className="btn-primary" onClick={() => take(e.id)} disabled={busyId === e.id}>
                            {busyId === e.id ? appText("Отмечаем…", "Билдәләйбеҙ…") : <><IconCheck size={18} /> {appText("Принял", "Ҡабул иттем")}</>}
                          </button>
                        </div>
                      </div>
                    ) : (
                      <button
                        type="button"
                        className="asos-take"
                        onClick={() => {
                          setNoteFor(e.id);
                          setNote("");
                          setErr("");
                        }}
                      >
                        <IconCheck size={20} /> {appText("Отметить, что принял", "Ҡабул иттем тип билдәләү")}
                      </button>
                    ))}
                </div>
              </article>
            );
          })}
      </div>
    </>
  );
}
