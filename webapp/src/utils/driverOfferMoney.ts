import type { InstantOrder } from "../api/instant";
import { kopExactLabel } from "./format";

type OfferMoney = Pick<InstantOrder,
  "driver_gross_kop" | "driver_net_kop" | "driver_fee_kop" | "driver_fee_percent">;
type AppText = (ru: string, ba: string) => string;

function isAmount(value: number | undefined): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

export function driverOfferMoney(order: OfferMoney, appText: AppText) {
  const gross = order.driver_gross_kop;
  const net = order.driver_net_kop;
  const fee = order.driver_fee_kop;
  const percent = order.driver_fee_percent;
  // Legacy/passenger payloads can carry zero-initialized driver fields.
  const grossKnown = isAmount(gross) && gross > 0;
  const income = grossKnown && isAmount(net) && net <= gross
    ? kopExactLabel(net)
    : appText("Уточняется", "Асыҡлана");
  const feeLabel = typeof percent === "number" && Number.isFinite(percent) && percent >= 0 && percent <= 100
    ? ` (${String(Math.round(percent * 100) / 100).replace(".", ",")}%)`
    : "";
  const breakdown = grossKnown && isAmount(fee) && fee <= gross
    ? appText(
      `Пассажир платит ${kopExactLabel(gross)} · комиссия ${kopExactLabel(fee)}${feeLabel}`,
      `Пассажир ${kopExactLabel(gross)} түләй · комиссия ${kopExactLabel(fee)}${feeLabel}`,
    )
    : null;
  return { income, breakdown };
}
