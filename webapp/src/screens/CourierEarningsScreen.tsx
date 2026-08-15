// ================================================================
//  «Мой заработок» курьера — GET /courier/earnings?period=.
//  Зеркало Android CourierEarningsScreen.kt.
//
//  У водителя такой экран был, у курьера — нет: он видел только
//  «должен Юлдашу столько-то», и работа выглядела одним сплошным
//  долгом. Здесь — чистыми, доставки и разбивка по дням.
//  Суммы приходят в КОПЕЙКАХ (в отличие от /driver/earnings).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchCourierEarnings, type CourierEarnings } from "../api/courier";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconBox, IconTrend } from "../components/Icons";
import { dayMonthShort, rubLabel } from "../utils/format";

type Status = "loading" | "error" | "soon" | "ready";
type Period = "week" | "month" | "all";

export default function CourierEarningsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [period, setPeriod] = useState<Period>("week");
  const [status, setStatus] = useState<Status>("loading");
  const [data, setData] = useState<CourierEarnings | null>(null);

  const load = useCallback((p: Period, signal?: AbortSignal) => {
    setStatus("loading");
    fetchCourierEarnings(p, signal)
      .then((d) => {
        setData(d);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 403 = не курьер (заявка не одобрена), 404 = эндпоинта ещё нет → мягко «скоро».
        setStatus(e instanceof ApiError && (e.status === 404 || e.status === 403) ? "soon" : "error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(period, ac.signal);
    return () => ac.abort();
  }, [load, period]);

  const periods: { key: Period; label: string }[] = [
    { key: "week", label: appText("Неделя", "Аҙна") },
    { key: "month", label: appText("Месяц", "Ай") },
    { key: "all", label: appText("Всё время", "Бөтә ваҡыт") },
  ];

  const maxNet = data ? Math.max(1, ...data.by_day.map((d) => d.net_kop)) : 1;

  function dayLabel(iso: string, ru: boolean): string {
    const d = new Date(iso + "T00:00:00");
    if (isNaN(d.getTime())) return iso;
    return dayMonthShort(d, ru);
  }

  function emptyTitle(): string {
    if (period === "week") return appText("За эту неделю доставок нет", "Был аҙнала илтеү юҡ");
    if (period === "month") return appText("За этот месяц доставок нет", "Был айҙа илтеү юҡ");
    return appText("Пока нет доставок", "Әлегә илтеү юҡ");
  }

  return (
    <>
      <SubHeader
        title={appText("Мой заработок", "Минең табыш")}
        subtitle={appText("Чистыми за доставки", "Илтеүҙәр өсөн таҙа")}
        onBack={() => navigate(-1)}
      />

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
              "Раздел включится после ближайшего обновления. Все доставки уже считаются.",
              "Был бүлек яҡын яңыртыуҙан һуң эшләй башлар. Барлыҡ илтеүҙәр иҫәпләнә инде."
            )}
          </p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon state__icon--warn">
            <IconTrend size={34} />
          </div>
          <h2>{appText("Не удалось обновить", "Яңырта алманыҡ")}</h2>
          <p>{appText("Цифры могут быть старыми.", "Һандар иҫке булыуы мөмкин.")}</p>
          <button type="button" className="btn-primary" onClick={() => load(period)}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" && data && (
        <>
          <div className="money-total">
            <div className="money-total__label">
              {appText("Заработано чистыми", "Таҙа эшләнде")}
            </div>
            <div className="money-total__value">{rubLabel(data.net_kop)}</div>
            <div className="money-total__rows">
              <div className="info-row">
                <span className="info-row__k">{appText("Доставок", "Илтеү")}</span>
                <span className="info-row__v">{data.deliveries}</span>
              </div>
              <div className="info-row">
                <span className="info-row__k">{appText("Комиссия", "Комиссия")}</span>
                <span className="info-row__v">{rubLabel(data.commission_kop)}</span>
              </div>
            </div>
          </div>

          {data.deliveries === 0 ? (
            <div className="state" style={{ paddingTop: 28 }}>
              <div className="state__icon">
                <IconBox size={34} />
              </div>
              <h2>{emptyTitle()}</h2>
              <p>
                {appText(
                  "Возьми первую доставку — и заработок появится здесь.",
                  "Беренсе илтеүҙе ал — табыш бында күренер."
                )}
              </p>
              <button type="button" className="btn-primary" onClick={() => navigate("/courier")}>
                <IconBox size={18} /> {appText("Смотреть заказы", "Заказдарҙы ҡарау")}
              </button>
            </div>
          ) : (
            <>
              <h2 className="section-title">{appText("По дням", "Көндәр буйынса")}</h2>
              <div className="earn-chart">
                {data.by_day.map((d) => (
                  <div key={d.date} className="earn-bar">
                    <div className="earn-bar__value">{Math.round(d.net_kop / 100).toLocaleString("ru-RU")}</div>
                    <div className="earn-bar__track">
                      <div
                        className="earn-bar__fill"
                        style={{ height: `${Math.max(6, Math.round((d.net_kop / maxNet) * 100))}%` }}
                      />
                    </div>
                    <div className="earn-bar__label">{dayLabel(d.date, ru)}</div>
                    <div className="earn-bar__trips">
                      {d.deliveries} {appText("д.", "и.")}
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}

          <p className="receipt__foot">
            {appText(
              "«Чистыми» — цена доставки минус комиссия платформы. Деньги идут напрямую тебе.",
              "«Таҙа» — илтеү хаҡы минус платформа комиссияһы. Аҡса тура һиңә бара."
            )}
          </p>
        </>
      )}
    </>
  );
}
