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
import { LoadingList, ErrorState, EmptyStateCard } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { EarnPeriodChips, MoneyDayRow, MoneyLine, MoneySectionHeader, MoneyTotalsCard } from "../components/moneyUi";
import { IconCalendar, IconCar, IconClockCal, IconTrend } from "../components/Icons";

import { pluralRu } from "../utils/format";
type Status = "loading" | "error" | "soon" | "ready";

export default function DriverEarningsScreen() {
  const { appText } = useLang();
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

  const maxSum = data ? Math.max(1, ...data.by_day.map((d) => d.sum)) : 1;
  /** "2026-07-14" → "14.07", как shortDay в приложении; неожиданный формат — как есть. */
  const shortDay = (iso: string) => (iso.length >= 10 && iso[4] === "-" && iso[7] === "-" ? `${iso.slice(8, 10)}.${iso.slice(5, 7)}` : iso);
  const tripsWord = (n: number) => pluralRu(n, "поездка", "поездки", "поездок");
  const fmt = (n: number) => n.toLocaleString("ru-RU");

  return (
    <>
      <SubHeader title={appText("Мой заработок", "Минең табыш")} onBack={() => navigate(-1)} />
      <div className="cabinet">
        {/* Период — чипами с иконками, как EarnPeriodChip в приложении. */}
        <EarnPeriodChips
          period={period}
          onSelect={setPeriod}
          items={[
            { key: "week", label: appText("Неделя", "Аҙна"), icon: <IconCalendar size={16} /> },
            { key: "month", label: appText("Месяц", "Ай"), icon: <IconCalendar size={16} /> },
            { key: "all", label: appText("Всё время", "Бөтә ваҡыт"), icon: <IconClockCal size={16} /> },
          ]}
        />

        {status === "loading" && <LoadingList count={2} />}
        {status === "soon" && (
          <EmptyStateCard
            icon={<IconTrend size={30} />}
            title={appText("Заработок скоро появится", "Табыш тиҙҙән буласаҡ")}
            text={appText(
              "Раздел включится после ближайшего обновления. Все завершённые поездки уже считаются.",
              "Был бүлек яҡын яңыртыуҙан һуң эшләй башлар. Тамамланған сәфәрҙәр иҫәпләнә инде."
            )}
          />
        )}
        {status === "error" && <ErrorState onRetry={() => load(period)} />}

        {status === "ready" && data && (
          <>
            <MoneyTotalsCard label={appText("Заработано", "Табыш")} value={`${fmt(data.total)} ₽`}>
              <MoneyLine icon={<IconCar size={16} />} label={appText("Поездок", "Сәфәр")} value={fmt(data.trips)} />
            </MoneyTotalsCard>

            {data.by_day.length === 0 ? (
              <EmptyStateCard
                icon={<IconCar size={30} />}
                title={appText("Пока нет завершённых поездок", "Тамамланған сәфәрҙәр әлегә юҡ")}
                text={appText(
                  "Заверши первую поездку — и заработок появится здесь.",
                  "Беренсе сәфәрҙе тамамла — табыш бында күренер."
                )}
              />
            ) : (
              <>
                <MoneySectionHeader
                  title={appText("По дням", "Көндәр буйынса")}
                  caption={appText("Сумма и число поездок за каждый день.", "Һәр көн өсөн сумма һәм сәфәр һаны.")}
                />
                <div className="money-days">
                  {data.by_day.map((d, i) => (
                    <MoneyDayRow
                      key={d.date}
                      index={i}
                      date={shortDay(d.date)}
                      value={`${fmt(d.sum)} ₽`}
                      fraction={d.sum / maxSum}
                      caption={appText(`${d.trips} ${tripsWord(d.trips)}`, `${d.trips} сәфәр`)}
                    />
                  ))}
                </div>
              </>
            )}
            <p className="dl-hint">
              {appText(
                "Суммы — по фактической цене завершённых заказов. Комиссия платформы — отдельно.",
                "Суммалар — тамамланған заказдарҙың факт хаҡы буйынса. Платформа комиссияһы — айырым."
              )}
            </p>
          </>
        )}
      </div>
    </>
  );
}
