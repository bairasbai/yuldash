// ================================================================
//  Бейдж статуса брони/поездки. Единые цвета Canon (badge--*).
// ================================================================
import { useLang } from "../i18n/lang";
import type { BookingStatus, DriverPhase } from "../api/bookings";

const LABEL: Record<BookingStatus, [string, string]> = {
  pending: ["Ждём подтверждения", "Раҫлауҙы көтәбеҙ"],
  confirmed: ["Подтверждена", "Раҫланды"],
  onboard: ["В пути", "Юлда"],
  done: ["Завершена", "Тамамланды"],
  cancelled: ["Отменена", "Кире алынды"],
};

const CLASS: Record<BookingStatus, string> = {
  pending: "badge--gold",
  confirmed: "badge--mint",
  onboard: "badge--mint",
  done: "badge--mint",
  cancelled: "badge--danger",
};

/** Отказ водителя — не «отмена» (приёмка PR210): рядом с «Водитель отклонил бронь» пометка говорит то же. */
const DECLINED: [string, string] = ["Отклонена", "Кире ҡағылған"];

/** Живая фаза водителя поверх статуса (departed/arriving). */
const PHASE: Record<Exclude<DriverPhase, "">, [string, string]> = {
  departed: ["Водитель выехал к тебе", "Водитель юлға сыҡты"],
  arriving: ["Водитель подъезжает", "Водитель яҡынлаша"],
};

export function StatusPill({
  status,
  phase,
  arrivalVerified = false,
  declined = false,
}: {
  status: BookingStatus;
  phase?: DriverPhase;
  /** true → к «подъезжает» добавляем «✓ GPS»: сервер сверил позицию водителя с точкой подачи.
   *  Показываем ТОЛЬКО по факту проверки — ложная галочка хуже её отсутствия. */
  arrivalVerified?: boolean;
  /** true → бронь сняла не отмена, а отказ водителя (`declined` из деталей брони). */
  declined?: boolean;
}) {
  const { appText } = useLang();
  if (phase && status === "confirmed") {
    const p = PHASE[phase];
    const gps = arrivalVerified && phase === "arriving";
    return (
      <span className="badge badge--gold">
        {appText(p[0], p[1])}
        {gps ? appText(" · ✓ GPS", " · ✓ GPS") : ""}
      </span>
    );
  }
  const l = status === "cancelled" && declined ? DECLINED : LABEL[status] ?? LABEL.pending;
  const c = CLASS[status] ?? "badge--gold";
  return <span className={`badge ${c}`}>{appText(l[0], l[1])}</span>;
}
