import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { flags } from "../flags";
import { useLang } from "../i18n/lang";
import { trackOnce } from "../analytics";
import { formatWhen } from "../utils/format";
import { listOfflineTripOptions } from "../utils/offlineTripPass";
import { scheduleSplashNavigation } from "../utils/splashTarget";

/** Зелёный мост Android Screen.Splash. Брендовый ролик живёт только в Intro. */
export default function SplashScreen() {
  const { status, retrySession } = useAuth();
  const navigate = useNavigate();
  const { appText, lang } = useLang();
  const [, redraw] = useState(0);
  const [expandedGeneration, setExpandedGeneration] = useState<string | null>(null);

  useEffect(() => {
    trackOnce("app_open", "app_open");
  }, []);

  useEffect(() => scheduleSplashNavigation(status, flags.onboarded(), navigate), [status, navigate]);

  useEffect(() => {
    if (status !== "unavailable") return;
    const changed = () => redraw(value => value + 1);
    const online = () => {
      setExpandedGeneration(null);
      changed();
    };
    window.addEventListener("online", online);
    window.addEventListener("offline", changed);
    window.addEventListener("storage", changed);
    window.addEventListener("focus", changed);
    document.addEventListener("visibilitychange", changed);
    return () => {
      window.removeEventListener("online", online);
      window.removeEventListener("offline", changed);
      window.removeEventListener("storage", changed);
      window.removeEventListener("focus", changed);
      document.removeEventListener("visibilitychange", changed);
    };
  }, [status]);

  const readOptions = () => status === "unavailable" && navigator.onLine === false
    ? listOfflineTripOptions()
    : null;
  const options = readOptions();
  const generation = options?.generation ?? null;
  const trips = options?.trips ?? [];
  const expanded = generation !== null && generation === expandedGeneration && trips.length > 1;

  useEffect(() => {
    if (expandedGeneration !== null && (expandedGeneration !== generation || trips.length < 2)) setExpandedGeneration(null);
  }, [generation, expandedGeneration, trips.length]);

  const openSavedTrip = (bookingId: number) => {
    const current = readOptions();
    if (!current || current.generation !== generation || !current.trips.some(trip => trip.bookingId === bookingId)) {
      redraw(value => value + 1);
      return;
    }
    navigate(`/trip/${bookingId}`);
  };

  const openSaved = appText("Открыть сохранённую поездку", "Һаҡланған сәфәрҙе асыу");
  const savedTrips = appText("Сохранённые поездки", "Һаҡланған сәфәрҙәр");

  return (
    <main className="splash">
      {status === "unavailable" ? (
        <div className="splash__unavailable">
          <p role="status">{appText("Не получилось проверить вход. Проверь связь и повтори.", "Инеүҙе тикшереп булманы. Бәйләнеште тикшер ҙә ҡабатла.")}</p>
          <button type="button" onClick={retrySession}>{appText("Повторить", "Ҡабатлау")}</button>
          {trips.length === 1 && (
            <button type="button" onClick={() => openSavedTrip(trips[0].bookingId)}>{openSaved}</button>
          )}
          {trips.length > 1 && (
            <button type="button" aria-expanded={expanded}
              onClick={() => setExpandedGeneration(expanded ? null : generation)}>{savedTrips}</button>
          )}
          {expanded && (
            <div className="splash__trip-options" role="group" aria-label={savedTrips}>
              {trips.map(trip => (
                <button type="button" key={trip.bookingId} onClick={() => openSavedTrip(trip.bookingId)}>
                  <span>{trip.fromCity} → {trip.toCity}</span>
                  <small>{formatWhen(trip.departAt, lang !== "ba")}</small>
                </button>
              ))}
            </div>
          )}
        </div>
      ) : <span role="status" aria-label={appText("Юлдаш загружается", "Юлдаш йөкләнә")} />}
    </main>
  );
}
