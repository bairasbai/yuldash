// ================================================================
//  «Мой заработок» водителя (GET /driver/earnings?period=week|month|all).
//  Плитки заработано ₽ / поездок + разбивка по дням с барами.
//  Суммы — в РУБЛЯХ (debt.py::driver_earnings), не копейки.
//  Нули для новичка. Эндпоинт появится на проде после мержа release
//  (/driver/earnings) → мягкая деградация «скоро». RequireAuth.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchDriverEarnings,
  type DriverEarnings,
  type EarningsPeriod,
} from "../api/driver";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconTrend } from "../components/Icons";

import { dayMonthShort } from "../utils/format";
type Status = "loading" | "error" | "soon" | "ready";

export default function DriverEarningsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [period, setPeriod] = useState<EarningsPeriod>("week");
  const [status, setStatus] = useState<Status>("loading");
  const [data, setData] = useState<DriverEarnings | null>(null);

  const load = useCallback((p: EarningsPeriod, signal?: AbortSignal) => {
    setStatus("loading");
    fetchDriverEarnings(p, signal)
      .then((d) => {
        setData(d);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 404/501 до релиза → мягкое «скоро», иначе — ошибка с повтором.
        setStatus(e instanceof ApiError && (e.status === 404 || e.status === 405) ? "soon" : "error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(period, ac.signal);
    return () => ac.abort();
  }, [load, period]);

  const periods: { key: EarningsPeriod; label: string }[] = [
    { key: "week", label: appText("Неделя", "Аҙна") },
    { key: "month", label: appText("Месяц", "Ай") },
    { key: "all", label: appText("Всё время", "Бөтә ваҡыт") },
  ];

  const maxSum = data ? Math.max(1, ...data.by_day.map((d) => d.sum)) : 1;

  function dayLabel(iso: string, ru: boolean): string {
    const d = new Date(iso + "T00:00:00");
    if (isNaN(d.getTime())) return iso;
    return dayMonthShort(d, ru);
  }

  return (
    <>
      <SubHeader
        title={appText("Мой заработок", "Минең табыш")}
        subtitle={appText("Заработок с завершённых заказов", "Тамамланған заказдарҙан табыш")}
        onBack={() => navigate(-1)}
      />

      {/* Переключатель периода */}
      <div className="seg" style={{ marginTop: 4 }}>
        {periods.map((p) => (
          <button
            key={p.key}
            type="button"
            className={"seg__item" + (period === p.key ? " is-active" : "")}
            onClick={() => setPeriod(p.key)}
          >
            {p.label}
          </button>
        ))}
      </div>

      {status === "loading" && <LoadingList count={2} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon">
            <IconTrend size={34} />
          </div>
          <h2>{appText("Заработок скоро появится", "Табыш тиҙҙән буласаҡ")}</h2>
          <p>
            {appText(
              "Раздел включится после ближайшего обновления. Все завершённые поездки уже считаются.",
              "Был бүлек яҡын яңыртыуҙан һуң эшләй башлар. Тамамланған сәфәрҙәр иҫәпләнә инде."
            )}
          </p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon state__icon--warn">
            <IconTrend size={34} />
          </div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load(period)}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" && data && (
        <>
          <div className="stat-grid" style={{ marginTop: 16 }}>
            <div className="stat-tile">
              <b>{data.total.toLocaleString("ru-RU")} ₽</b>
              <span>{appText("заработано", "табылды")}</span>
            </div>
            <div className="stat-tile">
              <b>{data.trips}</b>
              <span>{appText("поездок", "сәфәр")}</span>
            </div>
          </div>

          {data.trips === 0 ? (
            <p className="stat-newbie">
              {appText(
                "Пока пусто. Возьми первый заказ — и заработок появится здесь.",
                "Әле буш. Беренсе заказды ал — табыш бында күренер."
              )}
            </p>
          ) : (
            <>
              <h2 className="section-title">{appText("По дням", "Көндәр буйынса")}</h2>
              <div className="earn-chart">
                {data.by_day.map((d) => (
                  <div key={d.date} className="earn-bar">
                    <div className="earn-bar__value">{d.sum.toLocaleString("ru-RU")}</div>
                    <div className="earn-bar__track">
                      <div
                        className="earn-bar__fill"
                        style={{ height: `${Math.max(6, Math.round((d.sum / maxSum) * 100))}%` }}
                      />
                    </div>
                    <div className="earn-bar__label">{dayLabel(d.date, ru)}</div>
                    <div className="earn-bar__trips">
                      {d.trips} {appText("п.", "с.")}
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}

          <p className="receipt__foot">
            {appText(
              "Суммы — по фактической цене завершённых заказов. Комиссия платформы — отдельно.",
              "Суммалар — тамамланған заказдарҙың факт хаҡы буйынса. Платформа комиссияһы — айырым."
            )}
          </p>
        </>
      )}
    </>
  );
}
