import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { getSessionGeneration } from "../api/client";
import { fetchMyBookings } from "../api/bookings";
import { isLiveOwnBooking, type OwnSeatBooking } from "../utils/ownBookings";

interface OwnBookingsState {
  generation: string;
  userId: number;
  bookings: Map<number, OwnSeatBooking>;
}
interface OwnBookingsContext {
  state: OwnBookingsState | null;
  refresh: () => void;
}
const Context = createContext<OwnBookingsContext | null>(null);
const noop = () => {};

/** One private read for the feed/sheet, shared by every card on that page. */
export function OwnBookingsProvider({ children }: { children: ReactNode }) {
  const { isAuthed, user } = useAuth();
  const { pathname } = useLocation();
  const generation = getSessionGeneration();
  const userId = isAuthed ? user?.id ?? null : null;
  const owner = userId === null ? null : `${generation}:${userId}`;
  const ownerRef = useRef(owner);
  ownerRef.current = owner;
  const requestVersion = useRef(0);
  const [state, setState] = useState<OwnBookingsState | null>(null);
  const [revision, setRevision] = useState(0);
  const seatPage = ["/map", "/rides/feed", "/clinics", "/my-requests"].includes(pathname.replace(/\/+$/, "")) ||
    /^\/rides\/\d+\/?$/.test(pathname) || /^\/requests\/\d+\/responses\/?$/.test(pathname);

  const refresh = useCallback(() => {
    // A completed mutation from A must never invalidate or fetch B's bookings.
    if (owner === null || ownerRef.current !== owner || generation !== getSessionGeneration()) return;
    requestVersion.current++;
    setState(null);
    setRevision(value => value + 1);
  }, [owner, generation]);

  useEffect(() => {
    const version = ++requestVersion.current;
    setState(null);
    if (!seatPage || owner === null || userId === null) return;
    const controller = new AbortController();
    const current = () => !controller.signal.aborted && requestVersion.current === version &&
      ownerRef.current === owner && generation === getSessionGeneration();
    fetchMyBookings(controller.signal).then(rows => {
      if (!current()) return;
      const bookings = new Map<number, OwnSeatBooking>();
      rows.forEach(row => { if (isLiveOwnBooking(row)) bookings.set(row.ride_id, row); });
      setState({ generation, userId, bookings });
    }).catch(() => { if (current()) setState(null); });
    return () => controller.abort();
  }, [seatPage, pathname, owner, generation, userId, revision]);

  useEffect(() => {
    if (!seatPage || owner === null || typeof window === "undefined") return;
    window.addEventListener("focus", refresh);
    window.addEventListener("online", refresh);
    return () => {
      window.removeEventListener("focus", refresh);
      window.removeEventListener("online", refresh);
    };
  }, [seatPage, owner, refresh]);

  const currentState = owner !== null && state?.generation === generation && state.userId === userId ? state : null;
  return <Context.Provider value={{ state: currentState, refresh }}>{children}</Context.Provider>;
}

export function useOwnBooking(rideId: number): OwnSeatBooking | null {
  const context = useContext(Context);
  const { isAuthed, user } = useAuth();
  const state = context?.state;
  if (!isAuthed || !state || user?.id !== state.userId || state.generation !== getSessionGeneration()) return null;
  return state.bookings.get(rideId) ?? null;
}

export function useRefreshOwnBookings(): () => void {
  return useContext(Context)?.refresh ?? noop;
}
