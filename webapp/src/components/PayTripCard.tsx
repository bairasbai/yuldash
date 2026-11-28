// ================================================================
//  💵 Оплата состоявшейся поездки. Зеркало Android PayOnlineCard.
//
//  Наличные — не «платёж», а отметка: деньги пассажир отдаёт водителю из рук
//  в руки, платформа их не держит и вернуть не может. Так у нас между своими,
//  и говорить об этом надо прямо.
//
//  У попутки нет банковских кнопок. Карта и СБП остаются только у заказа
//  такси. Неизвестный исход банковской попытки блокирует прямую оплату.
//
//  Оплачено — карточки нет вовсе.
// ================================================================
import { useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  payBooking,
  payInstantOrder,
  type PayMethodKey,
  type PayTripResult,
} from "../api/wallet";
import { IconWallet, IconCheck } from "./Icons";
import { rememberPayment } from "../utils/pendingPayment";

export default function PayTripCard({
  kind,
  id,
  amountLabel,
  onPaid,
  canAct,
  onlinePaymentPending = false,
}: {
  kind: "booking" | "order";
  id: number;
  /** Сумма человеческой строкой — считает вызывающий экран, он знает про скидку. */
  amountLabel: string;
  onPaid?: () => void;
  /** Receipt owner may change while an old button or bank request is retained. */
  canAct?: () => boolean;
  onlinePaymentPending?: boolean;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<PayMethodKey | "check" | null>(null);
  const inFlight = useRef(false);
  const [pendingOverride, setPending] = useState<boolean | null>(null);
  const pending = pendingOverride ?? onlinePaymentPending;
  const pendingText = appText(
    "Онлайн-оплата ещё проверяется. Не плати повторно.",
    "Онлайн түләү әле тикшерелә. Ҡабат түләмә."
  );
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [onlineOff, setOnlineOff] = useState(false);

  if (done) {
    return (
      <div className="consents__status ok" style={{ marginTop: 14 }}>
        <IconCheck size={16} /> {appText("Оплата отмечена", "Түләү билдәләнде")}
      </div>
    );
  }

  async function pay(method: PayMethodKey | "check") {
    if (inFlight.current || canAct?.() === false || (pending && method !== "check")) return;
    if (kind === "booking" && method !== "cash") return;
    inFlight.current = true;
    setBusy(method);
    setError(null);
    try {
      const r: PayTripResult =
        kind === "booking" ? await payBooking(id, "cash") : await payInstantOrder(id, method);
      if (canAct?.() === false) return;
      if (r.status === "paid" || r.status === "already_paid" || r.status === "succeeded") {
        setDone(true);
        onPaid?.();
      } else if (kind === "order" && r.status === "canceled" && method !== "cash") {
        setPending(false);
        setError(appText("Банк отменил оплату. Можно выбрать способ оплаты заново.",
          "Банк түләүҙе ғәмәлдән сығарҙы. Түләү ысулын яңынан һайлап була."));
      } else if (kind === "order" && method !== "cash") {
        setPending(true);
        if (r.status === "pending" && r.confirmation_url) {
          if (r.payment_id) rememberPayment(r.payment_id, "trip", window.location.pathname);
          window.location.href = r.confirmation_url;
        }
      } else {
        setError(appText("Оплата не подтверждена.", "Түләү раҫланманы."));
      }
    } catch (e) {
      if (canAct?.() === false) return;
      if (kind === "order" && !pending && method !== "cash" && method !== "check" &&
          e instanceof ApiError && e.status === 503 && e.code === "taxi_online_pay_off") {
        setOnlineOff(true);
        setError(appText("Оплата картой и СБП сейчас недоступна.", "Карта һәм СБП аша түләү әле мөмкин түгел."));
      } else if (kind === "order" && (method === "check" ||
          (e instanceof ApiError && e.code === "taxi_checkout_unresolved") ||
          (method !== "cash" && (!(e instanceof ApiError) || e.status === 0 || e.status >= 500)))) {
        setPending(true);
      } else {
        setError(e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла."));
      }
    } finally {
      inFlight.current = false;
      if (canAct?.() !== false) setBusy(null);
    }
  }

  return (
    <section className="pay-trip">
      <div className="pay-trip__head">
        <IconWallet size={18} />
        <span>{appText("Как оплатил поездку?", "Сәфәрҙе нисек түләнең?")}</span>
        <b>{amountLabel}</b>
      </div>

      {pending ? (
        <>
          <p className="pay-trip__note" role="status">{pendingText}</p>
          <button type="button" className="btn-primary" onClick={() => void pay("check")} disabled={busy !== null}>
            {appText("Проверить оплату", "Түләүҙе тикшереү")}
          </button>
        </>
      ) : (
        <>
          <button
            type="button"
            className="btn-primary"
            style={{ width: "100%" }}
            onClick={() => void pay("cash")}
            disabled={busy !== null}
          >
            {busy === "cash" ? appText("Отмечаем…", "Билдәләйбеҙ…") : appText("Наличными", "Наличный менән")}
          </button>
          {(busy === null || busy === "cash") && <p className="pay-trip__note">
            {appText(
              "Деньги идут напрямую водителю — Юлдаш их не держит. Это отметка, а не платёж.",
              "Аҡса тура йөрөтөүсегә бара — Юлдаш уны тотмай. Был түләү түгел, билдәләү генә."
            )}
          </p>}

          {kind === "order" && !onlineOff && (
            <div className="field-row" style={{ marginTop: 10 }}>
              <button
                type="button"
                className="btn-soft"
                style={{ flex: 1 }}
                onClick={() => void pay("card")}
                disabled={busy !== null}
              >
                {busy === "card" ? appText("Открываем…", "Асабыҙ…") : appText("Картой", "Карта менән")}
              </button>
              <button
                type="button"
                className="btn-soft"
                style={{ flex: 1 }}
                onClick={() => void pay("sbp")}
                disabled={busy !== null}
              >
                {busy === "sbp" ? appText("Открываем…", "Асабыҙ…") : appText("СБП", "СБП")}
              </button>
            </div>
          )}
        </>
      )}

      {error && <div className="auth__error">{error}</div>}
    </section>
  );
}
