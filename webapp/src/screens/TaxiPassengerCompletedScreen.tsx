// ================================================================
//  Финал поездки глазами пассажира — зеркало android/TaxiCompletedScreen.kt
//  (TaxiPassengerCompletedScreen): тихая шапка с «закрыть», водитель крупно, звёзды
//  (контурные до выбора) с метками, «Отправить оценку» → «Готово», «Пропустить»,
//  «Итого» со способом расчёта, промо-строка, «Чек и детали», «Забыл вещь?».
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { useTaxiRating } from "../utils/useTaxiRating";
import { reportLostItem, type InstantOrder } from "../api/instant";
import { IconCheckCircle, IconChevron, IconClose, IconGift, IconReceipt, IconSearch, IconShield, IconStar, IconStarOutline } from "../components/Icons";
import { kopExactLabel, payMethodLabel, pluralRu } from "../utils/format";
import { fullPriceKop, passengerPayKop } from "./TaxiDriverCompletedScreen";

function serverSaid(e: unknown, fallback: string): string {
  return e instanceof ApiError && e.message ? e.message : fallback;
}

export default function TaxiPassengerCompleted(props: Parameters<typeof TaxiPassengerCompletedState>[0]) {
  return <TaxiPassengerCompletedState key={`${props.order.id}:${getSessionGeneration()}`} {...props} />;
}

function TaxiPassengerCompletedState({
  order,
  onClose,
  onNewOrder,
  onOpenReceipt,
  onOpenChat,
}: {
  order: InstantOrder;
  onClose: () => void;
  onNewOrder: () => void;
  onOpenReceipt: () => void;
  onOpenChat: () => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const rating = useTaxiRating(order);
  const { stars, setStars, tags, setTags, sending, sent } = rating;
  const [lostBusy, setLostBusy] = useState(false);
  const [error, setError] = useState("");

  const name = order.driver_name.trim() || appText("Водитель", "Йөрөтөүсе");
  const initial = order.driver_name.trim().charAt(0).toUpperCase() || "?";
  const car = [order.driver_car.trim(), (order.driver_plate ?? "").trim()].filter(Boolean).join("  ·  ");
  const starsText = (n: number) => appText(`${n} ${pluralRu(n, "звезда", "звезды", "звёзд")}`, `${n} йондоҙ`);
  const tagOptions =
    stars >= 4
      ? [
          { code: "polite", label: appText("Вежливо", "Әҙәпле") },
          { code: "safe", label: appText("Аккуратно", "Иғтибарлы") },
          { code: "clean", label: appText("Чистая машина", "Таҙа машина") },
        ]
      : [
          { code: "late", label: appText("Опоздание", "Һуңланы") },
          { code: "rude", label: appText("Грубость", "Ҡаты мөғәмәлә") },
          { code: "unsafe", label: appText("Опасное вождение", "Хәүефле йөрөтөү") },
        ];

  async function primary() {
    if (sent) {
      onNewOrder();
      return;
    }
    await rating.submit();
  }

  async function lostItem() {
    if (lostBusy) return;
    setLostBusy(true);
    try {
      await reportLostItem(order.id);
      setError("");
      onOpenChat();
    } catch (e) {
      setError(serverSaid(e, appText("Не получилось открыть чат. Проверь сеть и повтори.", "Чатты асып булманы. Селтәрҙе тикшереп ҡабатла.")));
    } finally {
      setLostBusy(false);
    }
  }

  return (
    <div className="tpc">
      {/* TaxiCompletedTopBar: круг «закрыть» слева, тихая подпись по центру. */}
      <header className="tpc__top">
        <button type="button" className="tpc__close" onClick={onClose} aria-label={appText("Закрыть", "Ябыу")}>
          <IconClose size={22} />
        </button>
        <span>{appText("Поездка завершена", "Сәфәр тамамланды")}</span>
      </header>

      <div className="tpc__body">
        {/* TaxiCompletedPerson */}
        <div className="taxi-done__person tpc__person">
          <span className="taxi-done__avatar" aria-hidden>
            {initial}
            {order.driver_verified && (
              <span className="taxi-done__verified" role="img" aria-label={appText("Водитель проверен", "Йөрөтөүсе тикшерелгән")}>
                <IconShield size={18} />
              </span>
            )}
          </span>
          <div className="taxi-done__who">
            <h2 className="taxi-done__name">{name}</h2>
            {car && <p className="taxi-done__car">{car}</p>}
          </div>
        </div>

        {sent ? (
          <div className="tpc-thanks" role="status">
            <span className="tpc-thanks__icon" aria-hidden><IconCheckCircle size={32} /></span>
            <h2>{appText("Спасибо за оценку", "Баһа өсөн рәхмәт")}</h2>
            <small>{starsText(stars)}</small>
          </div>
        ) : !rating.canRate ? (
            <div className="rate-card" role="status">
              <p>{rating.notice}</p>
              {rating.retryNeeded && <button type="button" className="btn-soft" onClick={rating.retry}>{appText("Повторить", "Ҡабатлау")}</button>}
            </div>
          ) : (
          /* TaxiRatingPicker: контурные звёзды до выбора, метки после, анонимность. */
          <div className="tpc-rate">
            <h2>{appText("Как прошла поездка?", "Сәфәр нисек үтте?")}</h2>
            <div className="tpc-rate__stars" role="radiogroup" aria-label={appText("Оценка", "Баһа")}>
              {[1, 2, 3, 4, 5].map((v) => (
                <button
                  key={v}
                  type="button"
                  role="radio"
                  disabled={sending}
                  aria-checked={v === stars}
                  aria-label={starsText(v)}
                  className={"tpc-rate__star" + (v <= stars ? " is-on" : "")}
                  onClick={() => {
                    setStars(v);
                    setTags([]);
                    setError("");
                  }}
                >
                  {v <= stars ? <IconStar size={40} /> : <IconStarOutline size={40} />}
                </button>
              ))}
            </div>
            {stars > 0 && (
              <div className="tpc-rate__tags">
                {tagOptions.map((t) => {
                  const on = tags.includes(t.code);
                  return (
                    <button
                      key={t.code}
                      type="button"
                      className={"tpc-rate__tag" + (on ? " is-on" : "")}
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
            <p className="tpc-rate__note">
              {appText("Оценка анонимна — водитель увидит только средний рейтинг.", "Баһа аноним — йөрөтөүсе тик уртаса рейтингты күрер.")}
            </p>
          </div>
        )}

        {/* TaxiCompletedMoney */}
        <div className="tpc-money">
          <span className="tpc-money__text">
            <strong>{appText("Итого", "Бөтәһе")}</strong>
            <small>{payMethodLabel(order.payment_method ?? "", ru)}</small>
          </span>
          <b>{kopExactLabel(passengerPayKop(order))}</b>
        </div>
        {/* TaxiPromoPayRow (пассажиру) */}
        {order.promo_discount_kop > 0 && (
          <div className="ttrip-promo">
            <span className="ttrip-promo__row">
              <IconGift size={18} />
              <strong>{appText(`К оплате водителю ${kopExactLabel(passengerPayKop(order))}`, `Йөрөтөүсегә түләргә ${kopExactLabel(passengerPayKop(order))}`)}</strong>
              <s>{kopExactLabel(fullPriceKop(order))}</s>
            </span>
            <small>
              {appText(
                `Скидка по промокоду −${kopExactLabel(order.promo_discount_kop)}. Её оплачивает Юлдаш — водитель получит своё полностью, спорить не о чем.`,
                `Промокод буйынса ташлама −${kopExactLabel(order.promo_discount_kop)}. Уны Юлдаш түләй — йөрөтөүсе үҙенекен тулыһынса ала, бәхәсләшер нәмә юҡ.`
              )}
            </small>
          </div>
        )}

        <button type="button" className="btn-primary tpc__primary" onClick={() => void primary()} disabled={!sent && (stars <= 0 || sending || !rating.canRate)}>
          {sending ? <span className="spinner spinner--sm spinner--on-filled" aria-hidden /> : sent ? appText("Готово", "Әҙер") : appText("Отправить оценку", "Баһаны ебәреү")}
        </button>
        {!sent && (
          <button type="button" className="tdc__text-btn" onClick={onNewOrder}>
            {appText("Пропустить", "Үткәреп ебәреү")}
          </button>
        )}

        {(error || rating.error) && (
          <p className="tdc-err" role="alert">
            {error || rating.error}
          </p>
        )}

        <div className="tpc__rows">
          <button type="button" className="rcpt-act" onClick={onOpenReceipt}>
            <span className="rcpt-act__icon" aria-hidden><IconReceipt size={20} /></span>
            <span className="rcpt-act__text">
              <strong>{appText("Чек и детали", "Чек һәм ентеклектәр")}</strong>
              <small>{appText("Сумма, маршрут и помощь", "Сумма, юл һәм ярҙам")}</small>
            </span>
            <span className="rcpt-act__chev" aria-hidden><IconChevron size={22} /></span>
          </button>
          <button type="button" className={"rcpt-act" + (lostBusy ? " is-off" : "")} onClick={() => void lostItem()} disabled={lostBusy}>
            <span className="rcpt-act__icon" aria-hidden>{lostBusy ? <span className="spinner spinner--sm" /> : <IconSearch size={20} />}</span>
            <span className="rcpt-act__text">
              <strong>{appText("Забыл вещь?", "Әйбер оноттоңмо?")}</strong>
              <small>{appText("Откроем чат поездки на 48 часов", "Сәфәр чатын 48 сәғәткә асабыҙ")}</small>
            </span>
            <span className="rcpt-act__chev" aria-hidden><IconChevron size={22} /></span>
          </button>
        </div>
      </div>
    </div>
  );
}
