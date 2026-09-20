// ================================================================
//  Такси: заявки таксистов + города + журнал готовности → /admin/taxi (RequireAdmin).
//  Зеркало AdminTaxiScreen.kt — одна лента в три раздела:
//   • заявки «Стать таксистом» (580-ФЗ) — GET /admin/taxi-applications?status=;
//       одобрить/отклонить с комментарием POST /admin/taxi-applications/{id}/approve|reject.
//       Документы (разрешение/ОСАГО/селфи/справка) защищены → fetchSecureDoc → blob-URL;
//   • «Города такси» — GET/POST /admin/taxi-cities, DELETE /admin/taxi-cities/{id};
//   • «Готовность к работе» — GET /admin/taxi/pretrip?day= (журнал 580-ФЗ), день листается
//       стрелками назад/вперёд, не дальше сегодня.
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
  fetchPretripJournal,
  type TaxiApplication,
  type TaxiCity,
  type PretripJournalItem,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { SectionHeader } from "../components/cabinetUi";
import { AdminFilterChips, AdminIntro, AdminStatusBadge, ListedEmpty, ListedError } from "../components/adminUi";
import { RideCardSkeleton } from "../components/States";
import { serverDate } from "../utils/serverTime";
import { IconCar, IconChevron, IconShield, IconTrash } from "../components/Icons";

type State = "loading" | "error" | "ready";

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
    <>
      <span className="acard__label">{cap}</span>
      {failed ? (
        <small className="adoc-none">{appText("нет файла", "файл юҡ")}</small>
      ) : !src ? (
        <div className="adoc skeleton" aria-hidden />
      ) : (
        <a href={src} target="_blank" rel="noreferrer" className="adoc">
          <img src={src} alt={cap} loading="lazy" />
        </a>
      )}
    </>
  );
}

/** Полных лет по дате рождения (taxiAgeYears). Кривая дата → null. */
function ageYears(birthIso: string, nowMs: number): number | null {
  const b = serverDate(birthIso ? birthIso.slice(0, 10) + "T00:00:00" : null);
  if (!b) return null;
  const now = new Date(nowMs);
  let years = now.getFullYear() - b.getFullYear();
  if (now.getMonth() < b.getMonth() || (now.getMonth() === b.getMonth() && now.getDate() < b.getDate())) years -= 1;
  return years >= 0 ? years : null;
}

/** «14:35» из ISO-времени. Не разобралось — отдаём как есть, лишь бы не пусто. */
function shortTimeOf(iso: string): string {
  const t = iso.split("T")[1] ?? "";
  return t.length >= 5 ? t.slice(0, 5) : iso;
}

/** День для запроса журнала: 0 = сегодня (пусто — день выбирает сервер), иначе ГГГГ-ММ-ДД. */
function pretripDayParam(shift: number): string | undefined {
  if (shift <= 0) return undefined;
  const d = new Date(); // местное «сегодня» админа, как Calendar.getInstance() в приложении
  d.setDate(d.getDate() - shift);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

const APP_FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "pending", ru: "На проверке", ba: "Тикшереүҙә" },
  { key: "approved", ru: "Одобрены", ba: "Раҫланған" },
  { key: "rejected", ru: "Отклонены", ba: "Кире ҡағылған" },
  { key: "all", ru: "Все", ba: "Барыһы" },
];

export default function AdminTaxiScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  return (
    <>
      <SubHeader title={appText("Таксисты", "Таксистар")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Заявки «Стать таксистом» (580-ФЗ): сверь ИНН, разрешение и ОСАГО, потом одобри или отклони с комментарием.",
            "«Таксист булыу» заявкалары (580-ФЗ): ИНН, рөхсәт һәм ОСАГО-ны тикшер, аҙаҡ раҫла йәки комментарий менән кире ҡаҡ."
          )}
        </AdminIntro>
        <Applications />
        <TaxiCitiesSection />
        <PretripJournalSection />
      </div>
    </>
  );
}

function Applications() {
  const { appText } = useLang();
  const [filter, setFilter] = useState("pending");
  const [state, setState] = useState<State>("loading");
  const [apps, setApps] = useState<TaxiApplication[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState<{ id: number; msg: string } | null>(null);
  const [rejectingId, setRejectingId] = useState<number | null>(null);
  const [comment, setComment] = useState("");
  const [nowMs] = useState(() => Date.now());
  const currentYear = new Date(nowMs).getFullYear();

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
      <AdminFilterChips
        label={appText("Фильтр заявок", "Заявка фильтры")}
        options={APP_FILTERS.map((f) => ({ key: f.key, label: appText(f.ru, f.ba) }))}
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
          subtitle={appText("Здесь появятся водители, которые хотят возить такси.", "Бында такси йөрөтөргә теләгән йөрөтөүселәр күренер")}
        />
      )}

      {state === "ready" &&
        apps.map((a) => {
          const age = ageYears(a.birth_date, nowMs);
          const exp =
            a.license_since_year >= 1900 && a.license_since_year <= currentYear ? currentYear - a.license_since_year : null;
          const meta = [
            age != null ? appText(`${age} лет`, `${age} йәш`) : "",
            exp != null ? appText(`стаж ${exp} г.`, `стаж ${exp} йыл`) : "",
          ]
            .filter(Boolean)
            .join("  ·  ");
          return (
            <article key={a.id} className="acard">
              <div className="acard__row">
                <strong className="acard__title acard__grow">{a.name || appText("Без имени", "Исемһеҙ")}</strong>
                <AdminStatusBadge status={a.status} />
              </div>
              {a.phone && <span className="acard__sub">{a.phone}</span>}
              {/* Доверие «между своими»: кто пригласил этого водителя (если по реф-коду). */}
              {a.invited_by && <span className="atext atext--green acard__caption">{appText("Пригласил: ", "Саҡырҙы: ") + a.invited_by}</span>}
              <span className="acard__text">{appText("ИНН: ", "ИНН: ") + (a.inn || "—")}</span>
              <span className="acard__text">{appText("Разрешение: ", "Рөхсәт: ") + (a.permit_number || "—")}</span>
              {meta && <span className="acard__sub">{meta}</span>}
              {a.permit_photo_url && <SecureDoc url={a.permit_photo_url} cap={appText("Разрешение на такси", "Такси рөхсәте")} />}
              {a.osago_url && <SecureDoc url={a.osago_url} cap={appText("Полис ОСАГО", "ОСАГО полисы")} />}
              {a.selfie_url && <SecureDoc url={a.selfie_url} cap={appText("Селфи с правами (сверь лицо)", "Права менән селфи (йөҙҙө сағыштыр)")} />}
              {a.criminal_record_url && (
                <SecureDoc url={a.criminal_record_url} cap={appText("Справка о несудимости", "Судимлек юҡлығы белешмәһе")} />
              )}
              {a.status === "rejected" && a.comment && (
                <span className="acard__text acard__text--danger">{appText("Комментарий: ", "Комментарий: ") + a.comment}</span>
              )}

              {rowError?.id === a.id && <div className="auth__error">{rowError.msg}</div>}

              {a.status === "pending" && (
                <>
                  <div className="acard__actions">
                    <button type="button" className="abtn abtn--48" onClick={() => approve(a.id)} disabled={busyId !== null}>
                      {busyId === a.id ? appText("…", "…") : appText("Одобрить", "Раҫлау")}
                    </button>
                    <button
                      type="button"
                      className="abtn abtn--48 abtn--outline abtn--red"
                      onClick={() => {
                        if (rejectingId === a.id) setRejectingId(null);
                        else {
                          setRejectingId(a.id);
                          setComment("");
                        }
                      }}
                      disabled={busyId !== null}
                    >
                      {appText("Отклонить", "Кире ҡағыу")}
                    </button>
                  </div>
                  {/* Отклонение — с полем комментария (водитель увидит его в заявке). */}
                  {rejectingId === a.id && (
                    <div className="acard__reject">
                      <label className="field">
                        <span className="field__label">{appText("Почему отклоняешь (увидит водитель)", "Ниңә кире ҡағаһың (йөрөтөүсе күрер)")}</span>
                        <input
                          className="field__input"
                          value={comment}
                          onChange={(e) => setComment(e.target.value.slice(0, 300))}
                          autoFocus
                        />
                      </label>
                      <button
                        type="button"
                        className="abtn abtn--48 abtn--danger"
                        onClick={() => reject(a.id)}
                        disabled={busyId !== null || !comment.trim()}
                      >
                        {busyId === a.id ? appText("…", "…") : appText("Отклонить с комментарием", "Комментарий менән кире ҡағыу")}
                      </button>
                    </div>
                  )}
                </>
              )}
            </article>
          );
        })}
    </>
  );
}

/** «Города такси»: строка города с тумблером и корзиной, ниже поле + «Добавить». */
export function TaxiCitiesSection() {
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
      <SectionHeader
        title={appText("Города такси", "Такси ҡалалары")}
        subtitle={appText("Где пассажирам доступен быстрый заказ", "Пассажирҙарға тиҙ заказ ҡайҙа асыҡ")}
      />
      {error && <div className="auth__error">{error}</div>}
      {state === "loading" && <RideCardSkeleton />}
      {state === "error" && <ListedError onRetry={() => load()} />}
      {state === "ready" && cities.length === 0 && (
        <AdminIntro>
          {appText(
            "Городов пока нет — добавь первый ниже. Пока список пуст, такси решает глобальный флаг на сервере.",
            "Ҡалалар әлегә юҡ — тәүгеһен түбәндә өҫтә. Исемлек буш саҡта таксины серверҙағы дөйөм флаг хәл итә."
          )}
        </AdminIntro>
      )}
      {state === "ready" &&
        cities.map((c) => (
          <div key={c.id} className={"acity" + (c.enabled ? " is-on" : "")}>
            <span className="acity__icon" aria-hidden><IconCar size={22} /></span>
            <span className="acity__text">
              <strong>{c.city}</strong>
              <small>{c.enabled ? appText("Такси включено", "Такси ҡабыҙылған") : appText("Выключено", "Һүндерелгән")}</small>
            </span>
            <button
              type="button"
              role="switch"
              aria-checked={c.enabled}
              aria-label={c.city}
              className="acity__switch"
              onClick={() => toggle(c)}
              disabled={busyId !== null}
            >
              <span className={"switch" + (c.enabled ? " on" : "")} aria-hidden />
            </button>
            <button
              type="button"
              className="acity__del"
              onClick={() => remove(c)}
              disabled={busyId !== null}
              aria-label={appText("Удалить город", "Ҡаланы юйыу")}
            >
              <IconTrash size={20} />
            </button>
          </div>
        ))}
      <div className="alookup">
        <label className="field">
          <span className="field__label">{appText("Город", "Ҡала")}</span>
          <input
            className="field__input"
            value={name}
            onChange={(e) => setName(e.target.value.slice(0, 40))}
            onKeyDown={(e) => {
              if (e.key === "Enter") add();
            }}
          />
        </label>
        <button type="button" className="abtn" onClick={add} disabled={busy || !name.trim()}>
          {busy ? appText("…", "…") : appText("+ Добавить", "+ Өҫтәргә")}
        </button>
      </div>
    </>
  );
}

/** «Готовность к работе»: день стрелками, записи журнала строками с щитом. */
export function PretripJournalSection() {
  const { appText } = useLang();
  const [dayShift, setDayShift] = useState(0);
  const [journal, setJournal] = useState<{ day: string; items: PretripJournalItem[] } | null>(null);
  const [state, setState] = useState<State>("loading");

  const load = useCallback((shift: number, signal?: AbortSignal) => {
    setState("loading");
    fetchPretripJournal(pretripDayParam(shift), signal)
      .then((r) => {
        setJournal(r);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setJournal({ day: pretripDayParam(shift) ?? "", items: [] });
          setState("ready");
        } else setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(dayShift, ac.signal);
    return () => ac.abort();
  }, [dayShift, load]);

  // Подпись дня: «Сегодня» / «Вчера» / дата с сервера — чтобы админ не считал дни в уме.
  const dayLabel =
    dayShift === 0
      ? appText("Сегодня", "Бөгөн")
      : dayShift === 1
        ? appText("Вчера", "Кисә")
        : journal?.day || pretripDayParam(dayShift) || "";

  return (
    <>
      <SectionHeader
        title={appText("Готовность к работе", "Эшкә әҙерлек")}
        subtitle={appText(
          "Кто отметился перед сменой. Это след для разбора ДТП и проверки.",
          "Смена алдынан кем билдәләнгән. Был — юл ваҡиғаһын тикшереү өсөн эҙ."
        )}
      />
      {/* Переключатель дня: назад по дням, вперёд — не дальше сегодня. */}
      <div className="aday">
        <button type="button" className="aday__btn" onClick={() => setDayShift((s) => s + 1)} aria-label={appText("День раньше", "Иртәрәк көн")}>
          <IconChevron size={22} />
        </button>
        <strong>{dayLabel}</strong>
        <button
          type="button"
          className="aday__btn aday__btn--next"
          onClick={() => setDayShift((s) => (s > 0 ? s - 1 : s))}
          disabled={dayShift === 0}
          aria-label={appText("День позже", "Һуңғараҡ көн")}
        >
          <IconChevron size={22} />
        </button>
      </div>
      {state === "loading" && !journal && <RideCardSkeleton />}
      {state === "error" && !journal && <ListedError onRetry={() => load(dayShift)} />}
      {state !== "error" && journal && journal.items.length === 0 && (
        <AdminIntro>{appText("В этот день никто не отмечался.", "Был көндә бер кем дә билдәләнмәгән.")}</AdminIntro>
      )}
      {journal?.items.map((e) => (
        <div key={`${e.driver_id}-${journal.day}`} className="acity acity--pretrip">
          <span className="acity__icon" aria-hidden><IconShield size={22} /></span>
          <span className="acity__text">
            <strong>{e.name}</strong>
            <small>{appText("Отметился в ", "Билдәләнгән: ") + shortTimeOf(e.confirmed_at)}</small>
            {e.note && <small>{e.note}</small>}
          </span>
        </div>
      ))}
    </>
  );
}
