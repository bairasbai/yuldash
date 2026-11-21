import { useCallback, useEffect, useRef, useState } from "react";
import type { TripReceipt } from "../api/bookings";
import { ApiError, getSessionGeneration } from "../api/client";
import { fetchRideshareCardPayment, type RideshareCardPayment } from "../api/rideshareCardPayment";

export interface PaymentRead {
  payment: RideshareCardPayment | null;
  phase: "idle" | "loading" | "error";
}

export type CardPaymentReader = PaymentRead & {
  refresh: () => void;
  acceptObserved: (payment: RideshareCardPayment) => boolean;
};

const emptyRead: PaymentRead = { payment: null, phase: "idle" };

export function useRideshareCardPayment(
  receipt: TripReceipt | null,
  generation: string,
  canRead: (receipt: TripReceipt) => boolean,
) {
  const active = useRef({ receipt, generation, canRead });
  active.current = { receipt, generation, canRead };
  const inflight = useRef<AbortController | null>(null);
  const [ownedRead, setRead] = useState<{ receipt: TripReceipt; generation: string; read: PaymentRead } | null>(null);
  const current = useCallback(() => receipt !== null && active.current.receipt === receipt &&
    active.current.generation === generation && getSessionGeneration() === generation && active.current.canRead(receipt),
  [receipt, generation]);

  const refresh = useCallback(() => {
    if (!receipt || !current() || inflight.current) return;
    const request = new AbortController();
    inflight.current = request;
    const setPhase = (phase: PaymentRead["phase"], payment?: RideshareCardPayment | null) => {
      if (!current() || request.signal.aborted || inflight.current !== request) return;
      setRead((prior) => ({ receipt, generation, read: {
        phase,
        payment: payment !== undefined ? payment :
          (prior?.receipt === receipt && prior.generation === generation ? prior.read.payment : null),
      } }));
    };
    setPhase("loading");
    void fetchRideshareCardPayment(receipt.booking_id, generation, request.signal)
      .then((payment) => setPhase("idle", payment), (error) => {
        const denied = error instanceof ApiError && [401, 403, 404].includes(error.status);
        setPhase("error", denied ? null : undefined);
      })
      .finally(() => { if (inflight.current === request) inflight.current = null; });
  }, [receipt, generation, current]);

  const acceptObserved = useCallback((payment: RideshareCardPayment) => {
    if (!receipt || !current()) return false;
    inflight.current?.abort();
    inflight.current = null;
    setRead({ receipt, generation, read: { payment, phase: "idle" } });
    return true;
  }, [receipt, generation, current]);

  useEffect(() => {
    if (!receipt) return;
    const foreground = () => { if (globalThis.document?.visibilityState !== "hidden") refresh(); };
    foreground();
    globalThis.window?.addEventListener?.("focus", foreground);
    globalThis.document?.addEventListener?.("visibilitychange", foreground);
    return () => {
      globalThis.window?.removeEventListener?.("focus", foreground);
      globalThis.document?.removeEventListener?.("visibilitychange", foreground);
      inflight.current?.abort();
      inflight.current = null;
    };
  }, [receipt, refresh]);

  const read = ownedRead?.receipt === receipt && ownedRead.generation === generation ? ownedRead.read : emptyRead;
  return { ...read, refresh, acceptObserved };
}
