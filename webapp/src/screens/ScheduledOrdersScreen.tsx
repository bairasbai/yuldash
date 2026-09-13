// ================================================================
//  «Мои предзаказы» — такси на время. RequireAuth → /scheduled
//  (зеркало android/ScheduledOrdersScreen.kt и backend routers/instant.py:
//  GET /instant/scheduled, activate, cancel).
//
//  • маршрут + время подачи + обратный отсчёт («через 2 ч 10 мин» / «пора»);
//  • «Начать поиск сейчас» (activate → живой поиск) и «Отменить» с подтверждением;
//  • «Пора ехать» — уже активированные ко времени (сервер сам перевёл в поиск),
//    в том числе подхваченные из истории заказов;
//  • опрос раз в 30 с; сбой обновления виден плашкой, а не молчит.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchScheduled,
  fetchMyOrders,
  activateScheduled,
  cancelScheduled,
  type InstantOrder,
} from "../api/instant";
import { SubHeader } from "./ConsentsScreen";
import { RideCardSkeleton, ErrorState, EmptyStateCard } from "../components/States";
import { MobilityScreenIntro } from "../components/parcelForm";
import RouteTimeline from "../components/RouteTimeline";
import AlertDialog from "../components/AlertDialog";
import { IconClock, IconCloudOff, IconInfo } from "../components/Icons";
import { serverDate, serverMs } from "../utils/serverTime";

type Status = "loading" | "error" | "ready";

/** Воркер мог активировать предзаказ до открытия экрана — подхватываем его из истории. */
export function shouldShowActivatedScheduled(status: string, scheduledAt: string | null, waitUntil?: string | null): boolean {
  if (!scheduledAt || status === "scheduled") return false;
  const terminal = status === "done" || status === "cancelled" || status === "expired";
  const waitingQueue = !!waitUntil && status !== "done" && status !== "cancelled";
  return !terminal || waitingQueue;
}

/** Минуты до подачи (отрицательные = уже пора); null — время не разобрать. */
function minutesUntil(iso: string | null, nowMs: number): number | null {
  const ms = serverMs(iso);
  if (Number.isNaN(ms)) return null;
  return Math.floor((ms - nowMs) / 60_000);
}

/** «13.09, 08:30» — formatDepart из приложения: местное время человека. */
function formatDepart(iso: string | null): string {
  const d = serverDate(iso);
  if (!d) return "—";
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getDate())}.${p(d.getMonth() + 1)}, ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function CountdownChip({ iso, nowMs }: { iso: string | null; nowMs: number }) {
  const { appText } = useLang();
  const minutes = minutesUntil(iso, nowMs);
  const ready = minutes != null && minutes <= 0;
  let label: string;
  if (minutes == null) label = appText("на время", "ваҡытҡа");
  else if (ready) label = appText("пора", "ваҡыт");
  else if (minutes < 60) label = appText(`через ${minutes} мин`, `${minutes} мин эсендә`);
  else {
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    label = m === 0 ? appText(`через ${h} ч`, `${h} сәғәт эсендә`) : appText(`через ${h} ч ${m} мин`, `${h} сәғәт ${m} мин эсендә`);
  }
  return <span className={"sched-chip" + (ready ? " is-ready" : "")}>{label}</span>;
}

export default function ScheduledOrdersScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [rows, setRows] = useState<InstantOrder[]>([]);
  const [activated, setActivated] = useState<InstantOrder[]>([]);
  /** Данные есть, но последнее обновление не дошло — говорим прямо, отсчёт мог устареть. */
  const [stale, setStale] = useState(false);
  const [busyId, setBusyId] = useState(0);
  const [actionNote, setActionNote] = useState("");
  const [cancelTarget, setCancelTarget] = useState<InstantOrder | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());

  const mergeActivated = async (loadedActivated: InstantOrder[], signal?: AbortSignal) => {
    const recent = await fetchMyOrders(5, signal).catch(() => [] as InstantOrder[]);
    const already = recent.filter((o) => shouldShowActivatedScheduled(o.status, o.scheduled_at, o.wait_until));
    const seen = new Set<number>();
    return [...loadedActivated, ...already].filter((o) => (seen.has(o.id) ? false : (seen.add(o.id), true)));
  };

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchScheduled(signal)
      .then(async (r) => {
        setRows(r.scheduled);
        setActivated(await mergeActivated(r.activated, signal));
        setStale(false);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 404 = эндпоинта ещё нет на проде → мягко «пусто»; 401 — свой путь (выход из аккаунта).
        if (e instanceof ApiError && (e.status === 404 || e.status === 401)) {
          setRows([]);
          setActivated([]);
          setStatus("ready");
        } else setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Раз в 30 с: живой отсчёт и наступившие ко времени с сервера. Сбой сети обязан быть виден.
  useEffect(() => {
    const iv = window.setInterval(() => {
      setNowMs(Date.now());
      fetchScheduled()
        .then(async (r) => {
          setRows(r.scheduled);
          setActivated(await mergeActivated(r.activated));
          setStale(false);
        })
        .catch((e) => {
          if (!(e instanceof ApiError && e.status === 401)) setStale(true);
        });
    }, 30_000);
    return () => window.clearInterval(iv);
  }, []);

  const actionFail = appText("Не получилось. Проверь интернет и повтори.", "Булманы. Интернетты тикшереп ҡабатла.");
  function serverSaid(e: unknown): string {
    return e instanceof ApiError && e.message ? e.message : actionFail;
  }

  async function activate(id: number) {
    if (busyId) return;
    setBusyId(id);
    setActionNote("");
    try {
      await activateScheduled(id);
      navigate("/taxi"); // заказ ушёл в поиск → экран заказа восстановит его
    } catch (e) {
      setActionNote(serverSaid(e));
      load(); // гонка (уже активирован/отменён) → обновим список
    } finally {
      setBusyId(0);
    }
  }

  async function cancel(id: number) {
    if (busyId) return;
    setBusyId(id);
    setActionNote("");
    try {
      await cancelScheduled(id);
      setRows((cur) => cur.filter((r) => r.id !== id));
    } catch (e) {
      setActionNote(serverSaid(e));
      load();
    } finally {
      setBusyId(0);
    }
  }

  const empty = rows.length === 0 && activated.length === 0;

  return (
    <>
      <SubHeader title={appText("Мои предзаказы", "Минең алдан заказдар")} onBack={() => navigate(-1)} />

      <div className="sched">
        {stale && (
          <p className="app-notice" role="status">
            <IconCloudOff size={20} />
            <span>{appText("Не удалось обновить — время могло измениться. Повторим через полминуты.", "Яңырта алманыҡ — ваҡыт үҙгәргән булыуы мөмкин. Ярты минуттан ҡабатлайбыҙ.")}</span>
          </p>
        )}
        <MobilityScreenIntro
          mode="taxi"
          title={appText("Такси к нужному времени", "Кәрәкле ваҡытҡа такси")}
          subtitle={appText("Поиск запустится автоматически ко времени подачи.", "Эҙләү килеү ваҡытына автоматик башланыр.")}
          badge={appText("Предзаказ", "Алдан заказ")}
        />

        {status === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}

        {/* Ошибка — это ошибка, а не «пусто»: иначе человек заказывает такси второй раз. */}
        {status === "error" && (
          <ErrorState
            onRetry={() => load()}
            title={appText("Не удалось загрузить предзаказы", "Алдан заказдарҙы йөкләп булманы")}
            hint={appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла")}
          />
        )}

        {status === "ready" && empty && (
          <EmptyStateCard
            icon={<IconClock size={36} />}
            title={appText("Пока предзаказов нет", "Әлегә алдан заказ юҡ")}
            text={appText("Закажи такси «на время» — в экране заказа выбери «На время».", "Такси «ваҡытҡа» заказ ит — заказ экранында «Ваҡытҡа» һайла.")}
          />
        )}

        {status === "ready" && !empty && (
          <>
            {actionNote && <p className="rcpt-msg rcpt-msg--err">{actionNote}</p>}

            {/* «Пора ехать» — активированные ко времени (сервер уже перевёл в поиск). */}
            {activated.length > 0 && (
              <>
                <h2 className="sched__title">{appText("Пора ехать", "Барыр ваҡыт")}</h2>
                {activated.map((o) => (
                  <button key={`act-${o.id}`} type="button" className="sched-live" onClick={() => navigate("/taxi")}>
                    <span className="sched-live__dot" aria-hidden />
                    <span className="sched-live__text">
                      <strong>
                        {o.status === "accepted" || o.status === "arriving"
                          ? appText("Водитель едет к тебе", "Йөрөтөүсе һиңә килә")
                          : o.status === "onboard"
                            ? appText("Поездка началась", "Сәфәр башланды")
                            : appText("Пора ехать — ищем машину", "Барыр ваҡыт — машина эҙләйбеҙ")}
                      </strong>
                      <small>
                        {o.from_text.trim() || appText("Точка А", "А нөктәһе")} → {o.to_text.trim() || appText("Точка Б", "Б нөктәһе")}
                      </small>
                    </span>
                    <span className="sched-live__open">{appText("Открыть", "Асыу")}</span>
                  </button>
                ))}
              </>
            )}

            {rows.length > 0 && (
              <>
                <h2 className="sched__title">{appText("Ждут своего времени", "Ваҡытын көтә")}</h2>
                {rows.map((o) => {
                  const minutes = minutesUntil(o.scheduled_at, nowMs);
                  const ready = minutes != null && minutes <= 5; // за 5 минут до подачи — «пора»
                  return (
                    <article key={o.id} className={"sched-card" + (ready ? " is-ready" : "")}>
                      <RouteTimeline from={o.from_text} to={o.to_text} compact />
                      <div className="sched-card__when">
                        <IconClock size={18} />
                        <strong>{formatDepart(o.scheduled_at)}</strong>
                        <CountdownChip iso={o.scheduled_at} nowMs={nowMs} />
                      </div>
                      {o.price_estimate > 0 && (
                        <p className="sched-card__price">
                          {appText(`≈ ${o.price_estimate} ₽ · цену уточним при подаче`, `≈ ${o.price_estimate} ₽ · хаҡты килгәндә асыҡлайбыҙ`)}
                        </p>
                      )}
                      <div className="sched-card__actions">
                        <button type="button" className="btn-primary sched-card__btn" onClick={() => void activate(o.id)} disabled={busyId !== 0}>
                          {busyId === o.id ? <span className="spinner spinner--sm spinner--on-filled" aria-hidden /> : appText("Начать поиск сейчас", "Хәҙер эҙләргә")}
                        </button>
                        <button type="button" className="btn-soft sched-card__btn" onClick={() => setCancelTarget(o)} disabled={busyId !== 0}>
                          {appText("Отменить", "Кире алыу")}
                        </button>
                      </div>
                    </article>
                  );
                })}
              </>
            )}

            <div className="info-card sched__info">
              <span className="info-card__icon" aria-hidden><IconInfo size={22} /></span>
              <span className="info-card__main">
                <strong>{appText("Можно закрыть приложение", "Ҡушымтаны ябырға мөмкин")}</strong>
                <small>
                  {appText(
                    "Сервер сам начнёт поиск ко времени подачи. Открой Юлдаш ближе к поездке, чтобы следить за статусом.",
                    "Сервер килеү ваҡытына эҙләүҙе үҙе башлар. Статусты ҡарау өсөн Юлдашты сәфәргә яҡыныраҡ ас."
                  )}
                </small>
              </span>
            </div>
          </>
        )}
      </div>

      {cancelTarget && (
        <AlertDialog
          title={appText("Отменить предзаказ?", "Алдан заказды кире алырғамы?")}
          text={appText("Поиск машины в назначенное время не начнётся.", "Билдәләнгән ваҡытта машина эҙләү башланмаясаҡ.")}
          onClose={() => busyId === 0 && setCancelTarget(null)}
          confirm={{
            label: appText("Отменить заказ", "Заказды кире алыу"),
            tone: "danger",
            disabled: busyId !== 0,
            onClick: () => {
              const id = cancelTarget.id;
              setCancelTarget(null);
              void cancel(id);
            },
          }}
          dismiss={{ label: appText("Оставить", "Ҡалдырыу"), tone: "muted", onClick: () => setCancelTarget(null), disabled: busyId !== 0 }}
        />
      )}
    </>
  );
}
