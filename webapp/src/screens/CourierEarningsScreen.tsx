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
import { LoadingList, ErrorState, EmptyStateCard } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { MoneyDayRow, MoneyLine, MoneyPeriodSwitch, MoneySectionHeader, MoneyTotalsCard } from "../components/moneyUi";
import { IconBox, IconTrend } from "../components/Icons";
import { rubLabel } from "../utils/format";

type Status = "loading" | "error" | "soon" | "ready";
type Period = "week" | "month" | "all";

export default function CourierEarningsScreen() {
  const { appText } = useLang();
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

  const maxNet = data ? Math.max(1, ...data.by_day.map((d) => d.net_kop)) : 1;
  const dayLabel = (iso: string) => (iso.length >= 10 && iso[4] === "-" && iso[7] === "-" ? `${iso.slice(8, 10)}.${iso.slice(5, 7)}` : iso);
  const emptyTitle =
    period === "week"
      ? appText("За эту неделю доставок нет", "Был аҙнала илтеү юҡ")
      : period === "month"
        ? appText("За этот месяц доставок нет", "Был айҙа илтеү юҡ")
        : appText("Пока нет доставок", "Әлегә илтеү юҡ");

  return (
    <>
      <SubHeader title={appText("Мой заработок", "Минең табыш")} onBack={() => navigate(-1)} />
      <div className="cabinet">
        <MoneyPeriodSwitch
          period={period}
          onSelect={setPeriod}
          labels={{ week: appText("Неделя", "Аҙна"), month: appText("Месяц", "Ай"), all: appText("Всё время", "Бөтә ваҡыт") }}
        />

        {status === "loading" && <LoadingList count={2} />}
        {status === "soon" && (
          <EmptyStateCard
            icon={<IconTrend size={30} />}
            title={appText("Заработок скоро появится", "Табыш тиҙҙән буласаҡ")}
            text={appText(
              "Раздел включится после ближайшего обновления. Все доставки уже считаются.",
              "Был бүлек яҡын яңыртыуҙан һуң эшләй башлар. Барлыҡ илтеүҙәр иҫәпләнә инде."
            )}
          />
        )}
        {status === "error" && (
          <ErrorState
            onRetry={() => load(period)}
            title={appText("Не удалось обновить", "Яңырта алманыҡ")}
            hint={appText("Цифры могут быть старыми.", "Һандар иҫке булыуы мөмкин.")}
          />
        )}

        {status === "ready" && data && (
          <>
            {data.deliveries === 0 ? (
              <EmptyStateCard
                icon={<IconBox size={30} />}
                title={emptyTitle}
                text={appText(
                  "Возьми первую доставку — и заработок появится здесь.",
                  "Беренсе илтеүҙе ал — табыш бында күренер."
                )}
                action={appText("Смотреть заказы", "Заказдарҙы ҡарау")}
                onAction={() => navigate("/courier")}
              />
            ) : (
              <>
                <MoneyTotalsCard label={appText("Заработано чистыми", "Таҙа эшләнде")} value={rubLabel(data.net_kop)}>
                  <MoneyLine icon={<IconBox size={16} />} label={appText("Доставок", "Илтеү")} value={String(data.deliveries)} />
                  <MoneyLine
                    icon={<IconTrend size={16} />}
                    tone="warn"
                    label={appText("Комиссия", "Комиссия")}
                    value={rubLabel(data.commission_kop)}
                    valueTone="warn"
                  />
                </MoneyTotalsCard>
                <MoneySectionHeader
                  title={appText("По дням", "Көндәр буйынса")}
                  caption={appText("Чистыми за каждый день и сколько было доставок.", "Һәр көн өсөн таҙа сумма һәм илтеү һаны.")}
                />
                <div className="money-days">
                  {data.by_day.map((d, i) => (
                    <MoneyDayRow
                      key={d.date}
                      index={i}
                      date={dayLabel(d.date)}
                      value={rubLabel(d.net_kop)}
                      fraction={d.net_kop / maxNet}
                      caption={appText(`Доставок: ${d.deliveries}`, `Илтеү: ${d.deliveries}`)}
                      captionBelow={false}
                    />
                  ))}
                </div>
              </>
            )}
            <p className="dl-hint">
              {appText(
                "«Чистыми» — цена доставки минус комиссия платформы. Деньги идут напрямую тебе.",
                "«Таҙа» — илтеү хаҡы минус платформа комиссияһы. Аҡса тура һиңә бара."
              )}
            </p>
          </>
        )}
      </div>
    </>
  );
}
