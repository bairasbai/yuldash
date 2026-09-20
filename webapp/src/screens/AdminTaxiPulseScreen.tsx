// ================================================================
//  Пульс такси → /admin/taxi-pulse (RequireAdmin).
//  GET /admin/taxi/pulse — живая сводка: на линии / активные заказы / счётчики дня /
//  средний подбор / анти-фрод / воронка «смотрят цену → заказывают» / разбивка по городам.
//  Автообновление раз в 30 сек, тем же циклом — жалобы на цену (GET /admin/price-complaints):
//  как в AdminTaxiPulseScreen.kt, несогласие с ценой живёт рядом с подбором и машинами на линии.
//  Без Redis presence = 0 (панель честно показывает, не падает). Двуязычно, все состояния.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchTaxiPulse, fetchPriceComplaints, type TaxiPulse, type TaxiFunnel, type PriceComplaint } from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { EmptyStateCard, RideCardSkeleton } from "../components/States";
import { AdminIntro, AdminTag } from "../components/adminUi";
import { IconCar, IconPin } from "../components/Icons";

type State = "loading" | "error" | "ready";

const REFRESH_MS = 30_000;

/** «26.5» → «27%». Доля процента в такой метрике — шум, а не точность. */
const pct = (v: number | null): string => (v == null ? "—" : `${Math.round(v)}%`);

/** Средний подбор: до 100с — секундами, дальше — минутами (панель читается с одного взгляда). */
function formatSearchSec(sec: number, appText: (ru: string, ba: string) => string): string {
  return sec < 100 ? `${Math.trunc(sec)} ${appText("с", "с")}` : `${Math.trunc(sec / 60)} ${appText("мин", "мин")}`;
}

/** Строки счёта приходят как объект или как строка JSON — показываем и то и другое. */
function breakdownText(b: PriceComplaint["breakdown"]): string {
  if (!b) return "";
  const obj = typeof b === "string" ? safeParse(b) : b;
  if (!obj) return typeof b === "string" ? b : "";
  return Object.entries(obj)
    .filter(([, v]) => Number(v) > 0)
    .map(([k, v]) => `${k}: ${v}`)
    .join(" · ");
}

function safeParse(s: string): Record<string, unknown> | null {
  try {
    const v = JSON.parse(s);
    return v && typeof v === "object" ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Плитка цифры: крупное значение + спокойная подпись; акцентные — мятная подложка. */
function PulseTile({ value, label, accent = false, step = 0 }: { value: string; label: string; accent?: boolean; step?: number }) {
  return (
    <div className={"pulse-tile" + (accent ? " is-accent" : "")} style={{ animationDelay: `calc(var(--cascade-in) * ${step})` }}>
      <b>{value}</b>
      <span>{label}</span>
    </div>
  );
}

/** «● 3 на линии» — компактная пара точка-цифра-подпись в строке города. */
function PulseDotStat({ count, label, tone }: { count: number; label: string; tone: "green" | "taxi" }) {
  return (
    <span className={"pulse-dot pulse-dot--" + tone}>
      <i aria-hidden />
      <b>{count}</b>
      <small>{label}</small>
    </span>
  );
}

/**
 * Воронка «смотрят цену → заказывают». Главная цифра для правки тарифа: без неё падение
 * заказов после надбавки выглядит как «людей мало», а не как «дорого».
 * Проценты — number | null: null значит «никто не смотрел», и это НЕ 0%.
 */
function PulseFunnelCard({ f }: { f: TaxiFunnel }) {
  const { appText } = useLang();
  const days = [...f.by_day].reverse();
  const top = Math.max(...f.by_day.map((x) => x.views), 1);
  return (
    <div className="acard acard--tight pulse-funnel">
      <div className="acard__row">
        <strong className="acard__title acard__grow">{appText("Смотрят цену → заказывают", "Хаҡты ҡарайҙар → заказ бирәләр")}</strong>
        <b className="pulse-funnel__pct">{pct(f.percent_today)}</b>
      </div>
      <small className="acard__date">
        {f.views_today === 0
          ? appText("Сегодня цену ещё никто не смотрел", "Бөгөн хаҡты әле бер кем ҡараманы")
          : appText(
              `Сегодня: ${f.orders_today} из ${f.views_today} заказали`,
              `Бөгөн: ${f.views_today} кешенән ${f.orders_today} заказ бирҙе`
            )}
      </small>
      {f.views_period > 0 && (
        <>
          <small className="acard__date">
            {appText(
              `За ${f.window_days} дней: ${pct(f.percent_period)} · ${f.orders_period} из ${f.views_period}`,
              `${f.window_days} көнгә: ${pct(f.percent_period)} · ${f.views_period} кешенән ${f.orders_period}`
            )}
          </small>
          {/* Столбики по дням, старые слева: высота — просмотры, залитая часть снизу — заказы. */}
          <div className="funnel-bars">
            {days.map((d) => (
              <div key={d.day} className="funnel-bar">
                <div className="funnel-bar__track">
                  <div className="funnel-bar__views" style={{ height: `${(d.views / top) * 100}%` }}>
                    <div className="funnel-bar__orders" style={{ height: d.views ? `${(d.orders / d.views) * 100}%` : "0%" }} />
                  </div>
                </div>
                <span>{d.day.slice(-2)}</span>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

/** Одна жалоба на цену: сумма, причина словами и то, что человек дописал сам. */
export function PriceComplaintCard({ c, detailed = false }: { c: PriceComplaint; detailed?: boolean }) {
  const { appText } = useLang();
  const reason =
    c.reason === "expensive_for_distance"
      ? appText("дорого для такого расстояния", "был ара өсөн ҡиммәт")
      : c.reason === "was_cheaper"
        ? appText("минуту назад было дешевле", "бер минут элек арзаныраҡ ине")
        : c.reason === "line_unclear"
          ? appText("не понял строку в счёте", "иҫәптәге юлды аңламаған")
          : appText("другое", "башҡа");
  const breakdown = breakdownText(c.breakdown);
  return (
    <article className="acard acard--tight">
      <div className="acard__row">
        <strong className="acard__title">{c.price} ₽</strong>
        <small className="acard__date acard__grow">{reason}</small>
        {detailed && c.handled && <AdminTag tone="green">{appText("Разобрано", "Ҡаралған")}</AdminTag>}
      </div>
      {/* На отдельном веб-экране видно и очередь (такси/доставка), и номер заказа. */}
      {detailed && (
        <small className="acard__date">
          {c.kind === "courier" ? appText("Доставка", "Илтеү") : appText("Такси", "Такси")}
          {c.order_id ? ` · ${appText("заказ", "заказ")} #${c.order_id}` : ""}
        </small>
      )}
      {c.comment && <small className="acard__micro">{c.comment}</small>}
      {breakdown && <small className="acard__date">{breakdown}</small>}
    </article>
  );
}

export default function AdminTaxiPulseScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [pulse, setPulse] = useState<TaxiPulse | null>(null);
  const [complaints, setComplaints] = useState<PriceComplaint[]>([]);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback((silent: boolean, signal?: AbortSignal) => {
    if (!silent) setState("loading");
    fetchTaxiPulse(signal)
      .then((res) => {
        setPulse(res);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (!silent) setState("error"); // при живых данных сбой сети не пугает
      });
    // Жалобы на цену тем же циклом: сбой здесь не должен гасить сам пульс.
    fetchPriceComplaints(50, signal)
      .then((r) => setComplaints(r.items ?? []))
      .catch(() => {});
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(false, ac.signal);
    timer.current = setInterval(() => load(true), REFRESH_MS);
    return () => {
      ac.abort();
      if (timer.current) clearInterval(timer.current);
    };
  }, [load]);

  const p = pulse;

  return (
    <>
      <SubHeader title={appText("Пульс такси", "Такси пульсы")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>{appText("Живая сводка: обновляется каждые 30 секунд.", "Йәнле күҙәтеү: һәр 30 секунд һайын яңыра.")}</AdminIntro>

        {state === "loading" && !p && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && !p && (
          <EmptyStateCard
            icon={<IconCar size={34} />}
            title={appText("Не удалось загрузить пульс", "Пульсты йөкләп булманы")}
            text={appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла")}
            action={appText("Повторить", "Ҡабатлау")}
            onAction={() => load(false)}
          />
        )}

        {p && (
          <>
            <div className="pulse-row">
              <PulseTile value={String(p.drivers_online)} label={appText("на линии", "линияла")} accent />
              <PulseTile value={String(p.orders_active)} label={appText("активных заказов", "актив заказ")} accent />
            </div>
            <div className="pulse-row">
              <PulseTile value={String(p.orders_today)} label={appText("заказов сегодня", "бөгөн заказ")} step={1} />
              <PulseTile value={String(p.done_today)} label={appText("завершено", "тамамланды")} step={1} />
            </div>
            <div className="pulse-row">
              <PulseTile value={String(p.cancelled_today)} label={appText("отмен", "кире алыу")} step={2} />
              <PulseTile value={String(p.no_show_today)} label={appText("не вышли", "сыҡманы")} step={2} />
              <PulseTile
                value={p.avg_search_sec_today != null ? formatSearchSec(p.avg_search_sec_today, appText) : "—"}
                label={appText("средний подбор", "уртаса эҙләү")}
                step={2}
              />
            </div>
            {/* Есть только в вебе: анти-фрод счётчики дня — той же плиткой, чтобы не потерять. */}
            <div className="pulse-row">
              <PulseTile value={String(p.gps_suspects_today)} label={appText("GPS-подозрения", "GPS-шик")} step={3} />
              <PulseTile value={String(p.contact_then_cancel_today)} label={appText("контакт → отмена", "контакт → кире")} step={3} />
            </div>

            {p.funnel && <PulseFunnelCard f={p.funnel} />}

            <strong className="acard__title">{appText("По городам", "Ҡалалар буйынса")}</strong>
            {p.by_city.length === 0 ? (
              <EmptyStateCard
                icon={<IconCar size={34} />}
                title={appText("Пока тихо", "Әлегә тыныс")}
                text={appText("Нет водителей на линии и активных заказов", "Линияла йөрөтөүселәр һәм актив заказдар юҡ")}
              />
            ) : (
              p.by_city.map((c) => (
                <div key={c.city} className="pulse-city">
                  <span className="pulse-city__pin" aria-hidden><IconPin size={18} /></span>
                  <strong>{c.city}</strong>
                  <PulseDotStat count={c.online} label={appText("на линии", "линияла")} tone="green" />
                  <PulseDotStat count={c.active} label={appText("заказы", "заказдар")} tone="taxi" />
                </div>
              ))
            )}
          </>
        )}

        {/* Жалобы на цену: пульс — «как себя чувствует такси», и несогласие с ценой относится сюда же. */}
        {complaints.length > 0 && (
          <>
            <strong className="acard__title">{appText("Не согласны с ценой", "Хаҡ менән килешмәйҙәр")}</strong>
            {complaints.map((c) => (
              <PriceComplaintCard key={c.id} c={c} />
            ))}
          </>
        )}
      </div>
    </>
  );
}
