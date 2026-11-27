import type { TaxiReceipt } from "../api/instant";

type ReceiptMoney = Pick<TaxiReceipt,
  "role" | "amount_kop" | "promo_discount_kop" | "driver_gross_kop"
  | "driver_fee_kop" | "driver_net_kop" | "driver_promo_comp_kop"
>;

/** A missing promo breakdown is not an explicit zero on older servers. */
export function taxiReceiptDriverMoney(receipt: ReceiptMoney) {
  if (receipt.role !== "driver" || !(Number(receipt.driver_gross_kop) > 0)) return null;
  const passengerKop = receipt.amount_kop;
  const feeKop = receipt.driver_fee_kop ?? 0;
  const netKop = receipt.driver_net_kop ?? 0;
  const promoCompKop = receipt.driver_promo_comp_kop ?? 0;
  if (receipt.promo_discount_kop > 0) {
    if (receipt.driver_promo_comp_kop == null || receipt.driver_fee_kop == null
        || receipt.driver_net_kop == null) return null;
    if (![passengerKop, feeKop, promoCompKop, netKop].every(v => Number.isSafeInteger(v) && v >= 0)) return null;
    if (netKop !== Math.max(passengerKop - feeKop + promoCompKop, 0)) return null;
  }
  return { passengerKop, feeKop, promoCompKop, netKop };
}
