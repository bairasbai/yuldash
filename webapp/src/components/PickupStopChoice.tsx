import type { Ride } from "../api/rides";
import { useLang } from "../i18n/lang";
import { dropoffStops, initialDropoffStop, initialPickupStop, pickupStops } from "../utils/pickupStops";

export function PickupStopChoice({ ride, picked, onPick, disabled = false }: {
  ride: Ride; picked: string | null; onPick: (place: string) => void; disabled?: boolean;
}) {
  const { appText } = useLang();
  const stops = pickupStops(ride);
  if (!stops.length) return null;
  return <fieldset disabled={disabled} style={{ border: 0, padding: 0, margin: "var(--space-md) 0" }}>
    <legend className="list-row__title">{appText("Где садишься?", "Ҡайҙа ултыраһың?")}</legend>
    <p className="sheet__note">{appText("Выбери начало маршрута или остановку по пути.", "Юлдың башын йәки юл ыңғайындағы туҡталышты һайла.")}</p>
    {[ride.from_city, ...stops].map(place => <label key={place} className={"list-row list-row--check" + (picked === place ? " list-row--on" : "")} style={{ minHeight: 48 }}>
      <span className="list-row__main">{place === ride.from_city ? appText(`Начало маршрута · ${place}`, `Юлдың башы · ${place}`) : place}</span>
      <input type="radio" className="checkbox" name={`pickup-stop-${ride.id}`} value={place} checked={picked === place} onChange={() => onPick(place)} />
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
  const dropoff = initialDropoffStop(ride, stop);
  return <span className="list-row__sub" style={{ minWidth: 0, overflowWrap: "anywhere" }}>{dropoff
    ? appText(`Твой участок: ${stop} → ${dropoff}`, `Һинең юл өлөшө: ${stop} → ${dropoff}`)
    : ride.matched_from_start && stop === ride.from_city
    ? appText("Посадка: начало маршрута", "Ултырыу: юлдың башы")
    : appText(`Посадка по пути: ${stop}`, `Юл ыңғайында ултырыу: ${stop}`)}</span>;
}

export function DropoffStopChoice({ ride, pickup, picked, onPick, disabled = false }: {
  ride: Ride; pickup: string | null; picked: string | null; onPick: (place: string) => void; disabled?: boolean;
}) {
  const { appText } = useLang();
  if (!pickupStops(ride).length) return null;
  const places = dropoffStops(ride, pickup);
  return <fieldset disabled={disabled || pickup === null} style={{ border: 0, padding: 0, margin: "var(--space-md) 0" }}>
    <legend className="list-row__title">{appText("Где выходишь?", "Ҡайҙа төшәһең?")}</legend>
    <p className="sheet__note">{pickup === null
      ? appText("Сначала выбери место посадки.", "Башта ултырыу урынын һайла.")
      : appText("Выбери остановку после посадки или конец маршрута.", "Ултырғандан һуңғы туҡталышты йәки юлдың аҙағын һайла.")}</p>
    {places.map(place => <label key={place} className={"list-row list-row--check" + (picked === place ? " list-row--on" : "")} style={{ minHeight: 48 }}>
      <span className="list-row__main">{place === ride.to_city ? appText(`Конец маршрута · ${place}`, `Юлдың аҙағы · ${place}`) : place}</span>
      <input type="radio" className="checkbox" name={`dropoff-stop-${ride.id}`} value={place} checked={picked === place} onChange={() => onPick(place)} />
    </label>)}
    {pickup !== null && picked === null && <p className="sheet__note" role="status">{appText("Выбери место высадки, чтобы забронировать.", "Бронлау өсөн төшөү урынын һайла.")}</p>}
  </fieldset>;
}

/** Only pass private booking data here. Public ride cards use the server's match hint. */
export function BookingSegmentNote({ pickupStop, dropoffStop, rideHasStops, from = "", to = "" }: {
  pickupStop?: string | null; dropoffStop?: string | null; rideHasStops?: boolean; from?: string; to?: string;
}) {
  const { appText } = useLang();
  const fromPlace = pickupStop === "" ? from : pickupStop;
  const toPlace = dropoffStop === "" ? to : dropoffStop;
  return <div style={{ minWidth: 0, overflowWrap: "anywhere" }}>
    {fromPlace && toPlace && <p className="list-row__title">{appText("Участок пассажира", "Юлсының юл өлөшө")}: {fromPlace} → {toPlace}</p>}
    <PickupStopNote pickupStop={pickupStop} rideHasStops={rideHasStops} from={from} />
    {/* Зеркало защиты посадки: про высадку говорим, только если у рейса есть остановки или высадка названа.
        На рейсе без остановок и на старой брони (сервер без поля) «не указано — уточни в чате» было бы ложью. */}
    {(rideHasStops === true || dropoffStop != null) && <p className="list-row__sub">{dropoffStop == null
      ? appText("Место высадки не указано — уточни в чате", "Төшөү урыны күрһәтелмәгән — чатта асыҡла")
      : dropoffStop === ""
        ? appText("Высадка: конец маршрута", "Төшөү: юлдың аҙағы") + (to ? ` · ${to}` : "")
        : appText(`Высадка: ${dropoffStop}`, `Төшөү: ${dropoffStop}`)}</p>}
  </div>;
}
