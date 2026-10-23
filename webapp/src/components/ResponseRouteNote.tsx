import type { ResponseItem } from "../api/requests";
import { useLang } from "../i18n/lang";
import { formatWhen } from "../utils/format";
import { BookingSegmentNote } from "./PickupStopChoice";

/** Both parties see the parent ride and their segment before accepting a response. */
export default function ResponseRouteNote({ response }: { response: ResponseItem }) {
  const { appText, lang } = useLang();
  if (!response.ride_id) return <p className="list-row__sub">{appText("Отдельная поездка", "Айырым сәфәр")}</p>;
  return <div className="info-list" style={{ overflowWrap: "anywhere" }}>
    <p className="list-row__title">{appText("Рейс водителя", "Йөрөтөүсенең сәфәре")}: {response.ride_from_city} → {response.ride_to_city}</p>
    {response.ride_depart_at && <p className="list-row__sub">{appText("Выезд рейса", "Сәфәргә сығыу")}: {formatWhen(response.ride_depart_at, lang !== "ba")}</p>}
    <BookingSegmentNote pickupStop={response.pickup_stop} dropoffStop={response.dropoff_stop}
      rideHasStops from={response.ride_from_city ?? ""} to={response.ride_to_city ?? ""} />
    <p className="sheet__note">{appText("Время посадки уточни в чате.", "Ултырыу ваҡытын чатта асыҡла.")}</p>
  </div>;
}
