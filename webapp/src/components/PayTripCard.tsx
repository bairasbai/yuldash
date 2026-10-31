// ================================================================
//  💵 Оплата состоявшейся поездки. Зеркало Android PayOnlineCard.
//
//  Наличные — не «платёж», а отметка: деньги пассажир отдаёт водителю из рук
//  в руки, платформа их не держит и вернуть не может. Так у нас между своими,
//  и говорить об этом надо прямо.
//
//  У попутки нет банковских кнопок. Карта и СБП остаются только у заказа
//  такси: при ответе 503 они скрываются с понятным объяснением.
//
//  Оплачено — карточки нет вовсе.
// ================================================================
import { useState } from "react";
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
}: {
  kind: "booking" | "order";
  id: number;
  /** Сумма человеческой строкой — считает вызывающий экран, он знает про скидку. */
  amountLabel: string;
  onPaid?: () => void;
  /** Receipt owner may change while an old button or bank request is retained. */
  canAct?: () => boolean;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<PayMethodKey | null>(null);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // У попутки банковских кнопок нет; 503 скрывает их в квитанции такси.
  const [onlineOff, setOnlineOff] = useState(false);

  if (done) {
    return (
      <div className="consents__status ok" style={{ marginTop: 14 }}>
        <IconCheck size={16} /> {appText("Оплата отмечена", "Түләү билдәләнде")}
      </div>
    );
  }

  async function pay(method: PayMethodKey) {
    if (busy || canAct?.() === false) return;
    if (kind === "booking" && method !== "cash") return;
    setBusy(method);
    setError(null);
    try {
      const r: PayTripResult =
        kind === "booking" ? await payBooking(id, method) : await payInstantOrder(id, method);
      if (canAct?.() === false) return;
      // Банк вернул ссылку — дальше платит человек в своём банке.
      if (r.confirmation_url) {
        if (r.payment_id) rememberPayment(r.payment_id, "trip", window.location.pathname);
        window.location.href = r.confirmation_url;
        return;
      }
      setDone(true);
      onPaid?.();
    } catch (e) {
      if (canAct?.() === false) return;
      if (e instanceof ApiError && e.status === 503) {
        setOnlineOff(true);
        setError(
          appText(
            "Оплата картой сейчас недоступна. Отдай наличными или переведи водителю.",
            "Карта менән түләү хәҙер мөмкин түгел. Наличный бир йәки йөрөтөүсегә күсер."
          )
        );
      } else {
        setError(
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
        );
      }
    } finally {
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

      <button
        type="button"
        className="btn-primary"
        style={{ width: "100%" }}
        onClick={() => void pay("cash")}
        disabled={busy !== null}
      >
        {busy === "cash" ? appText("Отмечаем…", "Билдәләйбеҙ…") : appText("Наличными", "Наличный менән")}
      </button>
      <p className="pay-trip__note">
        {appText(
          "Деньги идут напрямую водителю — Юлдаш их не держит. Это отметка, а не платёж.",
          "Аҡса тура йөрөтөүсегә бара — Юлдаш уны тотмай. Был түләү түгел, билдәләү генә."
        )}
      </p>

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

      {error && <div className="auth__error">{error}</div>}
    </section>
  );
}
