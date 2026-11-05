import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { getToken } from "../api/client";
import { useLang } from "../i18n/lang";
import { loadOfflineTripPass } from "../utils/offlineTripPass";
import { hasOfflineInTransitCancel } from "../utils/inTransitCancel";
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
  const [, redraw] = useState(0);
  useEffect(() => {
    const changed = () => redraw(value => value + 1);
    window.addEventListener("online", changed);
    window.addEventListener("offline", changed);
    window.addEventListener("storage", changed);
    return () => {
      window.removeEventListener("online", changed);
      window.removeEventListener("offline", changed);
      window.removeEventListener("storage", changed);
    };
  }, []);
  if (status !== "unavailable" || navigator.onLine !== false) return null;
  try { if (!getToken()) return null; } catch { return null; }
  const bookingId = Number(id);
  if (hasOfflineInTransitCancel(bookingId)) return <OfflineCancellationHelp bookingId={bookingId} />;
  const pass = loadOfflineTripPass(bookingId);
  return pass ? <OfflineTripPass pass={pass} /> : null;
}
