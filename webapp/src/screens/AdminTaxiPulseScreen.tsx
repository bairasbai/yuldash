// ================================================================
//  Пульс такси → /admin/taxi-pulse (RequireAdmin).
//  GET /admin/taxi/pulse — живая сводка: на линии / активные заказы / счётчики дня /
//  средний подбор / анти-фрод / разбивка по городам. Автообновление раз в 20 сек.
//  Без Redis presence = 0 (панель честно показывает, не падает). Двуязычно, все состояния.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchTaxiPulse, type TaxiPulse } from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconTrend, IconCar } from "../components/Icons";

type State = "loading" | "error" | "ready";

const REFRESH_MS = 20_000;

export default function AdminTaxiPulseScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [pulse, setPulse] = useState<TaxiPulse | null>(null);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback((silent: boolean, signal?: AbortSignal) => {
    if (!silent) setState("loading");
    fetchTaxiPulse(signal)
      .then((res) => {
        setPulse(res);
        setUpdatedAt(new Date());
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (!silent) setState("error");
      });
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

  const tiles: { label: [string, string]; value: number | string; hl?: boolean }[] = pulse
    ? [
        { label: ["На линии", "Линияла"], value: pulse.drivers_online, hl: true },
        { label: ["Активные заказы", "Әүҙем заказдар"], value: pulse.orders_active, hl: true },
        { label: ["Заказов сегодня", "Бөгөн заказ"], value: pulse.orders_today },
        { label: ["Завершено", "Тамамланды"], value: pulse.done_today },
        { label: ["Отменено", "Кире алынды"], value: pulse.cancelled_today },
        { label: ["Не вышли (no-show)", "Килмәне"], value: pulse.no_show_today },
        {
          label: ["Средний подбор", "Уртаса эҙләү"],
          value: pulse.avg_search_sec_today != null ? `${pulse.avg_search_sec_today} ${appText("сек", "сек")}` : "—",
        },
        { label: ["GPS-подозрения", "GPS-шик"], value: pulse.gps_suspects_today },
        { label: ["Увод мимо (контакт→отмена)", "Ситкә алыу"], value: pulse.contact_then_cancel_today },
      ]
    : [];

  return (
    <>
      <SubHeader
        title={appText("Пульс такси", "Такси тибеше")}
        subtitle={appText("Спрос и предложение вживую", "Ихтыяж һәм тәҡдим тере")}
        onBack={() => navigate(-1)}
      />

      {state === "loading" && <LoadingList count={2} />}
      {state === "error" && <ErrorState onRetry={() => load(false)} />}

      {state === "ready" && pulse && (
        <>
          <div className="pulse-grid">
            {tiles.map((t) => (
              <div key={t.label[0]} className={"stat-tile" + (t.hl ? " stat-tile--hl" : "")}>
                <b>{typeof t.value === "number" ? t.value.toLocaleString("ru-RU") : t.value}</b>
                <span>{appText(t.label[0], t.label[1])}</span>
              </div>
            ))}
          </div>

          <h2 className="section-title" style={{ marginTop: 20 }}>
            {appText("По городам", "Ҡалалар буйынса")}
          </h2>
          {pulse.by_city.length === 0 ? (
            <div className="state" style={{ paddingTop: 12 }}>
              <div className="state__emoji"><IconCar size={40} /></div>
              <h2>{appText("Пока тихо", "Әлегә тыныс")}</h2>
              <p>{appText("Никто не на линии и нет активных заказов.", "Бер кем дә линияла түгел, әүҙем заказдар юҡ.")}</p>
            </div>
          ) : (
            <div className="admin-cards">
              {pulse.by_city.map((c) => (
                <div key={c.city} className="admin-card" style={{ padding: 14 }}>
                  <div className="admin-card__head" style={{ marginBottom: 0 }}>
                    <div className="admin-card__title" style={{ fontSize: 15 }}>{c.city}</div>
                    <div style={{ display: "flex", gap: 8 }}>
                      <span className="badge badge--mint">{appText("на линии", "линияла")}: {c.online}</span>
                      <span className="badge badge--gold">{appText("заказы", "заказ")}: {c.active}</span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}

          {updatedAt && (
            <p className="pulse-updated">
              <IconTrend size={13} />{" "}
              {appText("Обновлено", "Яңыртылды")} {updatedAt.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}
              {" · "}
              {appText("каждые 20 сек", "һәр 20 сек")}
            </p>
          )}
        </>
      )}
    </>
  );
}
