import type { MyBooking } from "../api/bookings";
import { useLang } from "../i18n/lang";
import { formatWhen, pluralRu } from "../utils/format";
import { IconCar, IconCheck, IconClock, IconProfile, IconShield, IconWarn } from "./Icons";

/** Те же статусы, что открывают экран активной поездки в Android (bookingStatusAllowsActiveTrip). */
export function bookingStatusAllowsActiveTrip(status: string): boolean {
  return status === "confirmed" || status === "onboard" || status === "done";
}

/**
 * Карточка моей брони — зеркало Android MyTripCard: мятная плитка с иконкой статуса,
 * маршрут 19/25 Bold, пилюля статуса, две строки метаданных и пара кнопок 44dp
 * (зелёная + мятная «тональная»).
 */
export default function MyTripCard({
  booking,
  index,
  primaryAction,
  secondaryAction,
  onPrimary,
  onSecondary,
}: {
  booking: MyBooking;
  index: number;
  primaryAction: string;
  secondaryAction: string;
  onPrimary: () => void;
  onSecondary: () => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const status = statusView(booking.status, appText);
  const seats = appText(
    `${booking.seats} ${pluralRu(booking.seats, "место", "места", "мест")}`,
    `${booking.seats} урын`
  );

  return (
    <article className="my-trip-card" style={{ animationDelay: `${Math.min(index, 8) * 40}ms` }}>
      <div className="my-trip-card__head">
        <span className="my-trip-card__tile" aria-hidden>{status.icon}</span>
        <div className="my-trip-card__main">
          <h3 className="my-trip-card__route">{booking.from_city} → {booking.to_city}</h3>
          <span className={"my-trip-card__status " + status.className}>
            <IconShield size={15} /> {status.label}
          </span>
          <span className="my-trip-card__meta"><IconClock size={16} /> {formatWhen(booking.depart_at, ru)}</span>
          <span className="my-trip-card__meta"><IconProfile size={16} /> {seats} · {booking.price} ₽</span>
        </div>
      </div>
      <div className="my-trip-card__actions">
        <button type="button" className="my-trip-card__primary" onClick={onPrimary}>{primaryAction}</button>
        {secondaryAction && (
          <button type="button" className="my-trip-card__tonal" onClick={onSecondary}>{secondaryAction}</button>
        )}
      </div>
    </article>
  );
}

function statusView(status: string, appText: (ru: string, ba: string) => string) {
  switch (status) {
    case "confirmed":
      return { label: appText("Подтверждена", "Раҫланды"), className: "is-mint", icon: <IconCar size={24} /> };
    case "onboard":
      return { label: appText("В пути", "Юлда"), className: "is-mint", icon: <IconCar size={24} /> };
    case "done":
      return { label: appText("Завершена", "Тамамланды"), className: "is-mint", icon: <IconCheck size={24} /> };
    case "cancelled":
      return { label: appText("Отменена", "Кире ҡағылды"), className: "is-danger", icon: <IconWarn size={24} /> };
    default:
      return { label: appText("Ожидает", "Көтә"), className: "is-warn", icon: <IconClock size={24} /> };
  }
}
