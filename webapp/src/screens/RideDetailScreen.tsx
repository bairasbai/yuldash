import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { fetchRide, type Ride } from "../api/rides";
import { useLang } from "../i18n/lang";
import RideSheet from "../components/RideSheet";
import ScreenHeader from "../components/ScreenHeader";
import { ErrorState, LoadingList } from "../components/States";

/** Прямая ссылка сохраняет выбор поездки при входе и обновлении страницы. */
export default function RideDetailScreen() {
  const { id } = useParams();
  const rideId = Number(id);
  const navigate = useNavigate();
  const { appText } = useLang();
  const [ride, setRide] = useState<Ride | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const requestVersion = useRef(0);
  const load = useCallback((signal?: AbortSignal) => {
    const version = ++requestVersion.current;
    setStatus("loading");
    setRide(null);
    if (!Number.isSafeInteger(rideId) || rideId <= 0) { setStatus("error"); return; }
    fetchRide(rideId, signal).then(value => {
      if (signal?.aborted || version !== requestVersion.current) return;
      setRide(value);
      setStatus("ready");
    }).catch(() => {
      if (!signal?.aborted && version === requestVersion.current) setStatus("error");
    });
  }, [rideId]);
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => { controller.abort(); requestVersion.current++; };
  }, [load]);
  const close = () => navigate("/map");
  const available = ride && (!ride.status || ride.status === "active") && ride.seats_left > 0;
  return <>
    <ScreenHeader title={appText("Поездка", "Сәфәр")} />
    {status === "loading" && <LoadingList count={1} />}
    {status === "error" && <ErrorState onRetry={() => load()} />}
    {status === "ready" && ride && (available
      ? <RideSheet key={ride.id} ride={ride} onClose={close} />
      : <p role="status" className="sheet__note">{appText("Поездка больше недоступна для бронирования.", "Сәфәрҙе бүтән бронлап булмай.")}</p>)}
    <button type="button" className="btn-ghost" onClick={close}>{appText("К поездкам", "Сәфәрҙәргә")}</button>
  </>;
}
