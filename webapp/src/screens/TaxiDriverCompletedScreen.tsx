// ================================================================
//  Финал поездки глазами таксиста — зеркало android/TaxiDriverCompletedScreen.kt
//  (вариант A «доход прежде всего»): чистыми за поездку, три цифры денег, оценка
//  пассажира с метками, «Чек и детали» → чек, «Пассажир не заплатил». Снизу
//  закреплено «Вернуться на линию» и текстовая «Завершить смену».
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { useTaxiRating } from "../utils/useTaxiRating";
import { type InstantOrder } from "../api/instant";
import { setDriverOnline } from "../api/driver";
import { sendReport } from "../api/safety";
import { IconCheck, IconCheckCircle, IconChevron, IconReceipt, IconStar, IconStarOutline } from "../components/Icons";
import { kopExactLabel, pluralRu } from "../utils/format";

/** Полная цена копейками (InstantOrderDto.fullPriceKop). */
export function fullPriceKop(o: InstantOrder): number {
  return Math.max(0, o.price_final ?? o.price_estimate) * 100;
}
/** Сколько пассажир отдаёт на руки (InstantOrderDto.passengerPayKop). */
export function passengerPayKop(o: InstantOrder): number {
  return o.passenger_price_kop > 0 || o.promo_discount_kop > 0 ? Math.max(0, o.passenger_price_kop) : fullPriceKop(o);
}
export function payMethodShortLabel(method: string | undefined, appText: (ru: string, ba: string) => string): string {
  switch (method) {
    case "cash":
      return appText("Наличные", "Ҡулаҡса");
    case "sbp":
      return appText("СБП", "СБП");
    default:
      return appText("По договору", "Килешеү буйынса");
  }
}
function serverSaid(e: unknown, fallback: string): string {
  return e instanceof ApiError && e.message ? e.message : fallback;
}
function ratingLabel(rating: number | null, ru: boolean): string {
  if (rating == null) return ru ? "Новичок" : "Яңы кеше";
  return rating.toFixed(1).replace(".", ",");
}

export default function TaxiDriverCompleted(props: Parameters<typeof TaxiDriverCompletedState>[0]) {
  return <TaxiDriverCompletedState key={`${props.order.id}:${getSessionGeneration()}`} {...props} />;
}

function TaxiDriverCompletedState({
  order,
  onReturnToLine,
  onShiftFinished,
  onOpenReceipt,
}: {
  order: InstantOrder;
  onReturnToLine: () => void;
  onShiftFinished: () => void;
  onOpenReceipt: () => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const rating = useTaxiRating(order);
  const { stars, setStars, tags, setTags, sending, sent } = rating;
  const [shiftEnding, setShiftEnding] = useState(false);
  const [error, setError] = useState("");

  const hasBreakdown = (order.driver_gross_kop ?? 0) > 0;
  const income = hasBreakdown ? order.driver_net_kop ?? 0 : passengerPayKop(order);
  const name = order.passenger_name.trim() || appText("Пассажир", "Пассажир");
  const initial = name.charAt(0).toUpperCase() || "•";
  const trips = Math.max(0, order.passenger_trips);
  const trust = ru
    ? `${ratingLabel(order.passenger_rating, true)} · ${trips} ${pluralRu(trips, "поездка", "поездки", "поездок")}`
    : `${ratingLabel(order.passenger_rating, false)} · ${trips} сәфәр`;
  const starsText = (n: number) => appText(`${n} ${pluralRu(n, "звезда", "звезды", "звёзд")}`, `${n} йондоҙ`);
  const tagOptions =
    stars >= 4
      ? [
          { code: "polite", label: appText("Вежливо", "Әҙәпле") },
          { code: "ontime", label: appText("Вовремя", "Ваҡытында") },
          { code: "safe", label: appText("Бережно", "Һаҡсыл") },
        ]
      : [
          { code: "late", label: appText("Опоздание", "Һуңланы") },
          { code: "rude", label: appText("Грубость", "Ҡаты мөғәмәлә") },
          { code: "unsafe", label: appText("Неаккуратно", "Һаҡһыҙ") },
        ];

  async function submitRating() {
    await rating.submit();
  }

  async function finishShift() {
    if (sending || shiftEnding) return;
    setShiftEnding(true);
    setError("");
    try {
      await setDriverOnline(false);
      onShiftFinished();
    } catch (e) {
      setError(serverSaid(e, appText("Не получилось завершить смену. Проверь сеть и повтори.", "Сменаны тамамлап булманы. Селтәрҙе тикшереп ҡабатла.")));
    } finally {
      setShiftEnding(false);
    }
  }

  return (
    <div className="tdc">
      <div className="tdc__scroll">
        <h1 className="tdc__title appear" style={{ "--i": 0 } as React.CSSProperties}>
          {appText("Поездка завершена", "Сәфәр тамамланды")}
        </h1>

        {/* TaxiDriverIncomeHero: мятная карточка, зелёный круг с галочкой, сумма Display. */}
        <section className="tdc-hero appear" style={{ "--i": 1 } as React.CSSProperties}>
          <span className="tdc-hero__icon" aria-hidden><IconCheck size={28} /></span>
          <span className="tdc-hero__label">
            {hasBreakdown ? appText("Чистыми за поездку", "Сәфәрҙән таҙа килем") : appText("Получено за поездку", "Сәфәр өсөн алынды")}
          </span>
          <span className="tdc-hero__amount">{kopExactLabel(income)}</span>
        </section>

        {/* TaxiDriverMoneySummary: три ячейки через вертикальные разделители 1×64. */}
        <section className="tdc-money appear" style={{ "--i": 2 } as React.CSSProperties}>
          <div className="tdc-money__cell">
            <small>{appText("Заплатил", "Түләне")}</small>
            <strong>{kopExactLabel(passengerPayKop(order))}</strong>
          </div>
          <span className="tdc-money__div" aria-hidden />
          <div className="tdc-money__cell">
            <small>{appText("Комиссия", "Комиссия")}</small>
            <strong>{hasBreakdown ? kopExactLabel(order.driver_fee_kop ?? 0) : "—"}</strong>
          </div>
          <span className="tdc-money__div" aria-hidden />
          <div className="tdc-money__cell">
            <small>{appText("Оплата", "Түләү")}</small>
            <strong>{payMethodShortLabel(order.payment_method, appText)}</strong>
          </div>
        </section>

        {order.promo_discount_kop > 0 && (
          <p className="tdc-promo appear" style={{ "--i": 3 } as React.CSSProperties}>
            {appText("Скидку пассажира оплатил Юлдаш — твой доход не уменьшился.", "Пассажирҙың ташламаһын Юлдаш түләне — һинең килемең кәмемәне.")}
          </p>
        )}

        {/* TaxiDriverPassengerRating */}
        <section className="tdc-rate appear" style={{ "--i": 4 } as React.CSSProperties}>
          <div className="tdc-rate__who">
            <span className="tdc-rate__avatar" aria-hidden>{initial}</span>
            <span className="tdc-rate__text">
              <strong>{name}</strong>
              <small>{trust}</small>
            </span>
          </div>
          {sent ? (
            <div className="tdc-rate__done">
              <IconCheckCircle size={44} />
              <strong>{appText("Спасибо за оценку", "Баһа өсөн рәхмәт")}</strong>
              <small>{starsText(stars)}</small>
            </div>
          ) : !rating.canRate ? (
            <div className="rate-card" role="status">
              <p>{rating.notice}</p>
              {rating.retryNeeded && <button type="button" className="btn-soft" onClick={rating.retry}>{appText("Повторить", "Ҡабатлау")}</button>}
            </div>
          ) : (
            <div className="tdc-rate__form">
              <h2>{appText("Как прошла поездка с пассажиром?", "Пассажир менән сәфәр нисек үтте?")}</h2>
              <div className="tdc-rate__stars" role="radiogroup" aria-label={appText("Оценка", "Баһа")}>
                {[1, 2, 3, 4, 5].map((v) => (
                  <button
                    key={v}
                    type="button"
                    role="radio"
                    aria-checked={v === stars}
                    aria-label={starsText(v)}
                    className={"tdc-rate__star" + (v <= stars ? " is-on" : "")}
                    disabled={sending}
                    onClick={() => {
                      setStars(v);
                      setTags([]);
                      setError("");
                    }}
                  >
                    {v <= stars ? <IconStar size={38} /> : <IconStarOutline size={38} />}
                  </button>
                ))}
              </div>
              {stars > 0 && (
                <div className="tdc-rate__tags">
                  {tagOptions.map((t) => {
                    const on = tags.includes(t.code);
                    return (
                      <button
                        key={t.code}
                        type="button"
                        className={"tdc-rate__tag" + (on ? " is-on" : "")}
                        aria-pressed={on}
                        disabled={sending}
                        onClick={() => setTags((cur) => (cur.includes(t.code) ? cur.filter((c) => c !== t.code) : [...cur, t.code]))}
                      >
                        {t.label}
                      </button>
                    );
                  })}
                </div>
              )}
              <p className="tdc-rate__note">
                {appText("Оценка анонимна — пассажир увидит только средний рейтинг.", "Баһа аноним — пассажир тик уртаса рейтингты күрер.")}
              </p>
              <button type="button" className="btn-soft tdc-rate__send" onClick={() => void submitRating()} disabled={stars <= 0 || sending}>
                {sending ? <span className="spinner spinner--sm" aria-hidden /> : appText("Отправить оценку", "Баһаны ебәреү")}
              </button>
            </div>
          )}
        </section>

        {(error || rating.error) && (
          <p className="tdc-err" role="alert">
            {error || rating.error}
          </p>
        )}

        <div className="tdc-actions appear" style={{ "--i": 5 } as React.CSSProperties}>
          <button type="button" className="rcpt-act rcpt-act--lg" onClick={onOpenReceipt}>
            <span className="rcpt-act__icon" aria-hidden><IconReceipt size={22} /></span>
            <span className="rcpt-act__text">
              <strong>{appText("Чек и детали", "Чек һәм ентеклектәр")}</strong>
              <small>
                {order.payment_method === "cash"
                  ? appText("Подтвердить наличные, маршрут и помощь", "Ҡулаҡсаны раҫлау, юл һәм ярҙам")
                  : appText("Сумма, маршрут и помощь", "Сумма, юл һәм ярҙам")}
              </small>
            </span>
            <span className="rcpt-act__chev" aria-hidden><IconChevron size={22} /></span>
          </button>
          <UnpaidReportButton orderId={order.id} />
        </div>
      </div>

      {/* bottomBar: «Вернуться на линию» 56 + текстовая «Завершить смену». */}
      <div className="tdc__bar">
        <button type="button" className="btn-primary tdc__primary" onClick={onReturnToLine} disabled={sending || shiftEnding}>
          {appText("Вернуться на линию", "Линияға ҡайтыу")}
        </button>
        <button type="button" className="tdc__text-btn" onClick={() => void finishShift()} disabled={sending || shiftEnding}>
          {shiftEnding ? <span className="spinner spinner--sm" aria-hidden /> : appText("Завершить смену", "Сменаны тамамлау")}
        </button>
      </div>
    </div>
  );
}

/** «Пассажир не заплатил» (UnpaidReportButton): красная рамка 48; после отправки — мятная строка. */
export function UnpaidReportButton({ orderId }: { orderId: number }) {
  const { appText } = useLang();
  const [sent, setSent] = useState(false);
  const [sending, setSending] = useState(false);
  const [note, setNote] = useState("");

  if (sent) {
    return (
      <p className="tdc-unpaid__sent" role="status">
        <IconCheckCircle size={18} />
        <span>{appText("Отмечено: пассажир не заплатил. Мы разберёмся.", "Билдәләнде: пассажир түләмәгән. Беҙ тикшерербеҙ.")}</span>
      </p>
    );
  }
  return (
    <>
      <button
        type="button"
        className="tdc-unpaid"
        disabled={sending}
        onClick={() => {
          if (sending) return;
          setSending(true);
          setNote("");
          sendReport({ category: "unpaid", order_id: orderId })
            .then(() => setSent(true))
            .catch((e) => setNote(serverSaid(e, appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер."))))
            .finally(() => setSending(false));
        }}
      >
        {appText("Пассажир не заплатил", "Пассажир түләмәне")}
      </button>
      {note && <p className="tdc-err" role="alert">{note}</p>}
    </>
  );
}
