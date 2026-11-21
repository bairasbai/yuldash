import { hasBankPayment, type BankStatus } from "../api/rideshareCardPayment";
import { useLang } from "../i18n/lang";
import { kopExactLabel } from "../utils/format";
import type { PaymentRead } from "../utils/useRideshareCardPayment";

export default function RideshareCardPaymentSummary({ read, driver }: {
  read: PaymentRead & { refresh: () => void };
  driver: boolean;
}) {
  const { appText } = useLang();
  const labels: Record<BankStatus, string> = {
    unavailable: appText("Статус оплаты уточняется", "Түләү торошо асыҡлана"),
    not_started: appText("Статус оплаты уточняется", "Түләү торошо асыҡлана"),
    queued: appText("Оплата ожидает обработки", "Түләү эшкәртеүҙе көтә"),
    waiting_bank: appText("Ждём подтверждения банка", "Банктың раҫлауын көтәбеҙ"),
    reconciling: appText("Проверяем результат оплаты", "Түләү һөҙөмтәһен тикшерәбеҙ"),
    succeeded: appText("Банк подтвердил оплату", "Банк түләүҙе раҫланы"),
    canceled: appText("Оплата картой не состоялась", "Карта менән түләү үтмәне"),
  };
  function settlement(status: BankStatus | null, refund: boolean): string | null {
    if (status === "queued" || status === "waiting_bank" || status === "reconciling") {
      return refund ? appText("Банк обрабатывает возврат", "Банк аҡсаны кире ҡайтара")
        : appText("Банк обрабатывает выплату", "Банк түләүҙе башҡара");
    }
    if (status === "canceled") {
      return refund ? appText("Возврат не состоялся", "Аҡса кире ҡайтарылманы")
        : appText("Выплата не состоялась", "Түләү башҡарылманы");
    }
    return null;
  }
  const payment = hasBankPayment(read.payment) ? read.payment : null;
  const amount = payment && (payment.paid_kop > 0 ? payment.paid_kop : payment.amount_kop);
  const refund = payment && settlement(payment.refund_status, true);
  const payout = payment && driver && settlement(payment.payout_status, false);
  return (
    <section className="act-card" aria-busy={read.phase === "loading"}>
      <div className="act-card__title">{appText("Оплата картой", "Карта менән түләү")}</div>
      {payment && <div role="status">
        <p className="act-card__text">{labels[payment.payment_status]}</p>
        {amount !== null && <p className="list-row__title">{kopExactLabel(amount)}</p>}
        {payment.refund_coverage !== "none" && <p className="act-card__text">
          {payment.refund_coverage === "full" ? appText("Полный возврат", "Тулыһынса ҡайтарылды")
            : appText("Частичный возврат", "Өлөшләтә ҡайтарылды")} · {kopExactLabel(payment.refunded_kop)}
        </p>}
        {refund && <p className="act-card__text">{refund}</p>}
        {payout && <p className="act-card__text">{payout}</p>}
        {driver && payment.paid_out_kop !== null && payment.paid_out_kop > 0 && <p className="act-card__text">
          {appText("Выплачено", "Түләнде")} · {kopExactLabel(payment.paid_out_kop)}
        </p>}
        {driver && payment.payout_remaining_kop !== null && payment.payout_remaining_kop > 0 && <p className="act-card__text">
          {appText("Ожидает выплаты", "Түләү көтә")} · {kopExactLabel(payment.payout_remaining_kop)}
        </p>}
      </div>}
      {!payment && read.phase === "idle" && <p className="act-card__text">
        {appText("Статус оплаты уточняется", "Түләү торошо асыҡлана")}
      </p>}
      {read.phase === "error" && <p className="act-card__text" role="alert">
        {appText("Статус не обновился. Проверь связь и повтори.", "Торош яңырманы. Бәйләнеште тикшереп ҡабатла.")}
      </p>}
      <button type="button" className="btn-soft" onClick={read.refresh} disabled={read.phase === "loading"}>
        {read.phase === "loading" ? appText("Обновляем статус…", "Торошто яңыртабыҙ…")
          : appText("Обновить статус", "Торошто яңыртырға")}
      </button>
    </section>
  );
}
