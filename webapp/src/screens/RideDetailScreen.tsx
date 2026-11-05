import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { fetchRide, type Ride } from "../api/rides";
import { ApiError, getSessionGeneration } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import RideSheet from "../components/RideSheet";
import ScreenHeader from "../components/ScreenHeader";
import { ErrorState, LoadingList } from "../components/States";
import { preserveRideMatch, segmentReady } from "../utils/pickupStops";
import { consumeGuestBookingDraft, clearRestoredGuestBookingDraft, type GuestBookingSelection } from "../utils/guestBookingDraft";

/** Прямая ссылка сохраняет выбор поездки; гостевой выбор переносится один раз после входа. */
export default function RideDetailScreen() {
  const { id } = useParams();
  const rideId = Number(id);
  const navigate = useNavigate();
  const location = useLocation();
  // Subscribe to session changes even when this public route stays mounted.
  useAuth();
  const ownerGeneration = getSessionGeneration();
  const { appText } = useLang();
  const [ride, setRide] = useState<Ride | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error" | "gone">("loading");
  const [loadedOwner, setLoadedOwner] = useState(ownerGeneration);
  const requestVersion = useRef(0);
  const restored = useRef<{ rideId: number; owner: string; nonce: unknown; selection: GuestBookingSelection | null } | null>(null);
  const [initialSelection, setInitialSelection] = useState<GuestBookingSelection | null>(null);
  const guestNonce = (location.state as { guestBookingNonce?: unknown } | null)?.guestBookingNonce;
  const guestBooking = (location.state as { guestBooking?: unknown } | null)?.guestBooking === true || guestNonce !== undefined;
  const guestRouteActive = useRef({ active: false, nonce: guestNonce });
  useEffect(() => {
    guestRouteActive.current = { active: true, nonce: guestNonce };
    return () => {
      guestRouteActive.current.active = false;
      queueMicrotask(() => {
        if (!guestRouteActive.current.active || guestRouteActive.current.nonce !== guestNonce) {
          clearRestoredGuestBookingDraft(guestNonce);
        }
      });
    };
  }, [guestNonce]);
  const load = useCallback((signal?: AbortSignal) => {
    const version = ++requestVersion.current;
    const owner = ownerGeneration;
    if (owner !== getSessionGeneration()) return;
    setStatus("loading");
    setRide(null);
    setInitialSelection(null);
    setLoadedOwner(owner);
    if (!Number.isSafeInteger(rideId) || rideId <= 0) { setStatus("error"); return; }
    fetchRide(rideId, signal).then(value => {
      if (signal?.aborted || version !== requestVersion.current || owner !== getSessionGeneration()) return;
      if (!restored.current || restored.current.rideId !== rideId || restored.current.owner !== owner || restored.current.nonce !== guestNonce) {
        restored.current = { rideId, owner, nonce: guestNonce, selection: consumeGuestBookingDraft(guestNonce, rideId, owner) };
      }
      const selection = restored.current.selection;
      // The published route may have changed during login: reject the entire stale span.
      const accepted = selection && segmentReady(value, selection.pickupStop, selection.dropoffStop) ? selection : null;
      setInitialSelection(accepted);
      // A guest nonce can never fall back to reusable history metadata after consume/expiry.
      setRide(guestBooking ? value : preserveRideMatch(value, location.state, owner));
      setStatus("ready");
    }).catch(err => {
      if (signal?.aborted || version !== requestVersion.current || owner !== getSessionGeneration()) return;
      // «Нет такой / нет доступа / удалена» — повтор не поможет (так сервер отвечает и про поездку
      // заблокированного водителя). Честно «недоступна», как в Android, а не «проверь связь».
      const gone = err instanceof ApiError && [403, 404, 410].includes(err.status);
      setStatus(gone ? "gone" : "error");
    });
  }, [rideId, location.state, ownerGeneration, guestNonce, guestBooking]);
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => { controller.abort(); requestVersion.current++; };
  }, [load]);
  const close = () => navigate("/map");
  const currentOwner = loadedOwner === ownerGeneration;
  const available = ride && (!ride.status || ride.status === "active") && ride.seats_left > 0;
  return <>
    <ScreenHeader title={appText("Поездка", "Сәфәр")} />
    {(!currentOwner || status === "loading") && <LoadingList count={1} />}
    {currentOwner && status === "error" && <ErrorState onRetry={() => load()} />}
    {currentOwner && status === "gone" && <p role="status" className="sheet__note">{appText("Поездка недоступна", "Сәфәрҙе асып булмай")}</p>}
    {currentOwner && status === "ready" && ride && (available
      ? <RideSheet key={ownerGeneration + ":" + ride.id} ride={ride} onClose={close} initialSelection={initialSelection} />
      : <p role="status" className="sheet__note">{appText("Поездка больше недоступна для бронирования.", "Сәфәрҙе бүтән бронлап булмай.")}</p>)}
    <button type="button" className="btn-ghost" onClick={close}>{appText("К поездкам", "Сәфәрҙәргә")}</button>
  </>;
}
