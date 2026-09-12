// ================================================================
//  «Мои поездки такси» (водитель) — GET /driver/taxi-rides.
//  Зеркало Android DriverTaxiRidesScreen.kt.
//
//  Экран отвечает на один вопрос: «Юлдаш говорит 4200, я насчитал
//  4600 — где мои 400?». По каждой поездке видно цепочку
//  цена → комиссия → чистыми, и итог за все поездки сверху.
//  Комиссия — фактическая (из начисленного долга), не пересчёт.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchDriverTaxiRides, type DriverTaxiRides } from "../api/driver";
import { LoadingList, ErrorState, EmptyStateCard } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { MoneyLine, MoneySectionHeader, MoneyTotalsCard } from "../components/moneyUi";
import { IconCar, IconCheck, IconChevron, IconTrend, IconWallet } from "../components/Icons";
import { kopExactLabel, payMethodLabel, priceLabel, rubLabel } from "../utils/format";
import { serverDate } from "../utils/serverTime";

type Status = "loading" | "error" | "soon" | "ready";

/** Подпись состояния комиссии по поездке (пара цвет/текст — как в приложении). */
function feeBadge(
  status: string,
  appText: (ru: string, ba: string) => string
): { text: string; cls: string } | null {
  switch (status) {
    case "unpaid":
    case "pending":
      return { text: appText("Комиссия не оплачена", "Комиссия түләнмәгән"), cls: "ride-tag--warn" };
    case "declared":
      return { text: appText("Оплату проверяем", "Түләүҙе тикшерәбеҙ"), cls: "ride-tag--warn" };
    case "paid":
      return { text: appText("Комиссия оплачена", "Комиссия түләнгән"), cls: "ride-tag--mint" };
    case "void":
      return { text: appText("Комиссия списана", "Комиссия һүндерелгән"), cls: "ride-tag--mint" };
    default:
      return null;
  }
}

/** ISO → «03.08, 23:10». Пусто → прочерк, чтобы строка не «прыгала». */
function shortWhen(iso: string | null): string {
  if (!iso) return "—";
  const d = serverDate(iso);
  if (!d) return "—";
  const date = d.toLocaleDateString("ru-RU", { day: "2-digit", month: "2-digit" });
  const time = d.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
  return `${date}, ${time}`;
}

export default function DriverTaxiRidesScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [data, setData] = useState<DriverTaxiRides | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchDriverTaxiRides(100, signal)
      .then((d) => {
        setData(d);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && (e.status === 404 || e.status === 403)) {
          return setStatus("soon");
        }
        setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  return (
    <>
      <SubHeader title={appText("Мои поездки такси", "Такси сәфәрҙәрем")} onBack={() => navigate(-1)} />
      <div className="cabinet">
        {status === "loading" && <LoadingList count={3} />}
        {status === "soon" && (
          <EmptyStateCard
            icon={<IconWallet size={30} />}
            title={appText("Скоро здесь", "Тиҙҙән бында")}
            text={appText(
              "Раздел включится после ближайшего обновления. Все поездки уже считаются.",
              "Был бүлек яҡын яңыртыуҙан һуң эшләй башлар. Барлыҡ сәфәрҙәр иҫәпләнә инде."
            )}
          />
        )}
        {status === "error" && <ErrorState onRetry={() => load()} />}
        {status === "ready" && data && data.rides.length === 0 && (
          <EmptyStateCard
            icon={<IconCar size={30} />}
            title={appText("Поездок пока нет", "Әлегә сәфәр юҡ")}
            text={appText(
              "Здесь появится расшифровка по каждой поездке: цена, наша комиссия и сколько остаётся тебе.",
              "Бында һәр сәфәр буйынса яҙма күренәсәк: хаҡ, беҙҙең комиссия һәм һиңә күпме ҡала."
            )}
          />
        )}
        {status === "ready" && data && data.rides.length > 0 && (
          <>
            <MoneyTotalsCard label={appText("Чистыми за последние поездки", "Һуңғы сәфәрҙәр өсөн таҙа")} value={rubLabel(data.total_net_kop)}>
              <MoneyLine icon={<IconWallet size={16} />} label={appText("Пассажиры заплатили", "Юлаусылар түләне")} value={priceLabel(data.total_price, ru)} />
              <MoneyLine
                icon={<IconTrend size={16} />}
                tone="warn"
                label={appText("Комиссия Юлдаша", "Юлдаш комиссияһы")}
                value={"− " + rubLabel(data.total_fee_kop)}
                valueTone="warn"
              />
              <span className="money-card__rule" aria-hidden />
              <MoneyLine icon={<IconCheck size={16} />} label={appText("Осталось тебе", "Һиңә ҡалды")} value={rubLabel(data.total_net_kop)} valueTone="green" />
            </MoneyTotalsCard>

            <MoneySectionHeader
              title={appText("Каждая поездка", "Һәр сәфәр")}
              caption={appText("Цена пассажиру, наша комиссия и сколько осталось тебе.", "Юлаусыға хаҡ, беҙҙең комиссия һәм һиңә күпме ҡалғаны.")}
            />

            <div className="money-days">
              {data.rides.map((r) => {
                const badge = feeBadge(r.fee_status, appText);
                return (
                  <button
                    key={r.order_id}
                    type="button"
                    className="taxi-ride-row"
                    onClick={() => navigate(`/taxi-receipt/${r.order_id}`)}
                    aria-label={appText("Открыть чек поездки", "Сәфәр чеген асыу")}
                  >
                    <div className="taxi-ride-row__head">
                      <span className="taxi-ride-row__main">
                        <strong>{r.from || "—"} → {r.to || "—"}</strong>
                        <small>{shortWhen(r.done_at)}</small>
                      </span>
                      <span className="taxi-ride-row__net">
                        <b>{kopExactLabel(r.net_kop)}</b>
                        <small>{appText("тебе", "һиңә")}</small>
                      </span>
                      <span className="taxi-ride-row__chev" aria-hidden><IconChevron size={20} /></span>
                    </div>
                    <div className="taxi-ride-row__ledger">
                      <span className="taxi-ride-row__cell">
                        <small>{appText("Цена", "Хаҡ")}</small>
                        <b>{priceLabel(r.price, ru)}</b>
                      </span>
                      <span className="taxi-ride-row__cell">
                        <small>{appText("Комиссия", "Комиссия")}</small>
                        <b className="is-warn">− {kopExactLabel(r.fee_kop)}</b>
                      </span>
                      {r.promo_discount_kop > 0 && (
                        <span className="taxi-ride-row__cell">
                          <small>{appText("Промокод", "Промокод")}</small>
                          <b>− {kopExactLabel(r.promo_discount_kop)}</b>
                        </span>
                      )}
                      {r.promo_comp_kop > 0 && (
                        <span className="taxi-ride-row__cell">
                          <small>{appText("Возврат Юлдаша", "Юлдаш ҡайтарыуы")}</small>
                          <b>+ {kopExactLabel(r.promo_comp_kop)}</b>
                        </span>
                      )}
                    </div>
                    <div className="taxi-ride-row__tags">
                      {/* Разбор подтвердил, что денег не было. Такая поездка не должна
                          выглядеть как оплаченная: за неё уже сняли комиссию, и без метки
                          она смотрится выгоднее честной. */}
                      <span className={"ride-tag " + (r.unpaid_confirmed ? "ride-tag--danger" : r.paid ? "ride-tag--mint" : "ride-tag--warn")}>
                        {r.unpaid_confirmed
                          ? appText("Не заплатили — подтверждено", "Түләмәнеләр — раҫланған")
                          : r.paid
                            ? appText("Оплачено", "Түләнгән")
                            : appText("Не отмечено", "Билдәләнмәгән")}
                      </span>
                      {r.payment_method && <span className="ride-tag ride-tag--mint">{payMethodLabel(r.payment_method, ru)}</span>}
                      {badge && <span className={"ride-tag " + badge.cls}>{badge.text}</span>}
                    </div>
                  </button>
                );
              })}
            </div>

            <p className="dl-hint">
              {appText(
                "Комиссию мы не удерживаем из твоих денег: пассажир платит тебе целиком, а комиссия копится долгом и платится отдельно.",
                "Комиссияны һинең аҡсанан тотоп ҡалмайбыҙ: юлаусы һиңә тулыһынса түләй, комиссия айырым бурыс булып йыйыла."
              )}
            </p>
          </>
        )}
      </div>
    </>
  );
}
