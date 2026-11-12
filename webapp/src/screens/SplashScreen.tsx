import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { flags } from "../flags";
import { useLang } from "../i18n/lang";
import { trackOnce } from "../analytics";
import { formatWhen } from "../utils/format";
import { listOfflineTripOptions } from "../utils/offlineTripPass";
import { useStoredTripData } from "../utils/useStoredTripData";
import { getSessionGeneration } from "../api/client";
import { scheduleSplashNavigation } from "../utils/splashTarget";

async function readOptions() {
  return navigator.onLine === false ? listOfflineTripOptions() : null;
}

/** Зелёный мост Android Screen.Splash. Брендовый ролик живёт только в Intro. */
export default function SplashScreen() {
  const { status, retrySession } = useAuth();
  const navigate = useNavigate();
  const { appText, lang } = useLang();
  const [expandedGeneration, setExpandedGeneration] = useState<string | null>(null);

  useEffect(() => {
    trackOnce("app_open", "app_open");
  }, []);

  useEffect(() => scheduleSplashNavigation(status, flags.onboarded(), navigate), [status, navigate]);

  const options = useStoredTripData(status === "unavailable", readOptions);
  const generation = options?.generation ?? null;
  const trips = options?.trips ?? [];
  const expanded = generation !== null && generation === expandedGeneration && trips.length > 1;

  useEffect(() => {
    if (expandedGeneration !== null && (expandedGeneration !== generation || trips.length < 2)) setExpandedGeneration(null);
  }, [generation, expandedGeneration, trips.length]);

  const openSavedTrip = async (bookingId: number) => {
    const current = await readOptions();
    if (navigator.onLine === false && current && current.generation === generation && generation === getSessionGeneration() &&
        current.trips.some(trip => trip.bookingId === bookingId)) navigate(`/trip/${bookingId}`);
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
            <button type="button" onClick={() => void openSavedTrip(trips[0].bookingId)}>{openSaved}</button>
          )}
          {trips.length > 1 && (
            <button type="button" aria-expanded={expanded}
              onClick={() => setExpandedGeneration(expanded ? null : generation)}>{savedTrips}</button>
          )}
          {expanded && (
            <div className="splash__trip-options" role="group" aria-label={savedTrips}>
              {trips.map(trip => (
                <button type="button" key={trip.bookingId} onClick={() => void openSavedTrip(trip.bookingId)}>
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
