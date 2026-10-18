import type { Ride } from "../api/rides";
import { useLang } from "../i18n/lang";
import { initialPickupStop, pickupStops } from "../utils/pickupStops";

export function PickupStopChoice({ ride, picked, onPick, disabled = false }: {
  ride: Ride; picked: string | null; onPick: (place: string) => void; disabled?: boolean;
}) {
  const { appText } = useLang();
  const stops = pickupStops(ride);
  if (!stops.length) return null;
  return <fieldset disabled={disabled} style={{ border: 0, padding: 0, margin: "var(--space-md) 0" }}>
    <legend className="list-row__title">{appText("Где садишься?", "Ҡайҙа ултыраһың?")}</legend>
    <p className="sheet__note">{appText("Выбери начало маршрута или остановку по пути.", "Юлдың башын йәки юл ыңғайындағы туҡталышты һайла.")}</p>
    {[ride.from_city, ...stops].map(place => <label key={place} className="list-row list-row--check" style={{ minHeight: 48 }}>
      <span className="list-row__main">{place === ride.from_city ? appText(`Начало маршрута · ${place}`, `Юлдың башы · ${place}`) : place}</span>
      <input type="radio" name={`pickup-stop-${ride.id}`} value={place} checked={picked === place} onChange={() => onPick(place)} />
    </label>)}
    {picked === null && <p className="sheet__note" role="status">{appText("Выбери место посадки, чтобы забронировать.", "Бронлау өсөн ултырыу урынын һайла.")}</p>}
  </fieldset>;
}

export function PickupStopNote({ pickupStop, rideHasStops, from = "" }: { pickupStop?: string | null; rideHasStops?: boolean; from?: string }) {
  const { appText } = useLang();
  if (pickupStop == null && !rideHasStops) return null;
  const text = pickupStop == null
    ? appText("Место посадки не указано — уточни в чате", "Ултырыу урыны күрһәтелмәгән — чатта асыҡла")
    : pickupStop === ""
      ? appText("Посадка: начало маршрута", "Ултырыу: юлдың башы") + (from ? ` · ${from}` : "")
      : appText(`Посадка: ${pickupStop}`, `Ултырыу: ${pickupStop}`);
  return <p className="list-row__sub">{text}</p>;
}

export function MatchedPickupStopNote({ ride }: { ride: Ride }) {
  const { appText } = useLang();
  const stop = initialPickupStop(ride);
  if (!stop) return null;
  return <span className="list-row__sub" style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{ride.matched_from_start && stop === ride.from_city
    ? appText("Посадка: начало маршрута", "Ултырыу: юлдың башы")
    : appText(`Посадка по пути: ${stop}`, `Юл ыңғайында ултырыу: ${stop}`)}</span>;
}
