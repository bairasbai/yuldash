import { useCallback } from "react";
import { useParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { readOfflineTrip } from "../utils/offlineTripPass";
import { useStoredTripData } from "../utils/useStoredTripData";
import OfflineTripPass from "./OfflineTripPass";

/** General help only: no vehicle/contact snapshot, chat, code or mutation is mounted here. */
export function OfflineCancellationHelp(_props: { bookingId: number }) {
  const { appText } = useLang();
  return <section className="act-card act-card--warn" role="status">
    <h2 className="act-card__title">{appText("Поездку отменили в пути", "Сәфәр юлда кире алынды")}</h2>
    <p className="act-card__text">{appText(
      "Без сети сообщение SOS не отправится. Если помощь нужна срочно, попробуй позвонить 112. Данные поездки можно проверить, когда появится связь.",
      "Бәйләнешһеҙ SOS хәбәре ебәрелмәй. Ашығыс ярҙам кәрәк булһа, 112 номерына шылтыратып ҡара. Бәйләнеш булғас, сәфәр мәғлүмәттәрен ҡабат тикшер."
    )}</p>
    <a className="sos-112" href="tel:112">{appText("Позвонить 112", "112 — шылтыратыу")}</a>
  </section>;
}

/** Only /trip/:id offers saved, read-only content; private screen children remain unmounted. */
export default function OfflineTripFallback() {
  const { id } = useParams();
  const { status } = useAuth();
  const bookingId = Number(id);
  const read = useCallback(async () => navigator.onLine === false ? readOfflineTrip(bookingId) : null, [bookingId]);
  const saved = useStoredTripData(status === "unavailable", read);
  if (navigator.onLine !== false || !saved) return null;
  if (saved.cancelledInTransit) return <OfflineCancellationHelp bookingId={bookingId} />;
  return saved.pass ? <OfflineTripPass pass={saved.pass} /> : null;
}
