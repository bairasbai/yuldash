import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, getSessionGeneration } from "../api/client";
import { fetchInstantOrder, type InstantOrder } from "../api/instant";

export type WaitReadState = "idle" | "loading" | "unconfirmed" | "denied";

/** Local publication ordering, NOT a server revision or a new search episode. */
export function useWaitConflictReconcile(orderId: number | undefined, generation: string, publish: (order: InstantOrder) => void) {
  const renderedOrder = useRef(orderId);
  renderedOrder.current = orderId;
  const [reconciled, setReconciled] = useState(false);
  const [state, setState] = useState<WaitReadState>("idle");
  const gate = useRef({ epoch: 0, state: "idle" as WaitReadState });
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  const current = useCallback(() => mounted.current && renderedOrder.current === orderId && generation === getSessionGeneration(), [generation, orderId]);
  useEffect(() => {
    mounted.current = true;
    gate.current.state = "idle";
    setState("idle");
    setReconciled(false);
    return () => {
      mounted.current = false;
      ++gate.current.epoch;
      controller.current?.abort();
    };
  }, [orderId, generation]);
  const read = useCallback(async () => {
    if (!current() || orderId == null || gate.current.state !== "idle") return;
    const epoch = gate.current.epoch;
    try {
      const order = await fetchInstantOrder(orderId, undefined, generation);
      if (current() && gate.current.state === "idle" && epoch === gate.current.epoch && order.id === orderId) publish(order);
    } catch { /* Ordinary tracking reads retain their existing retry-on-poll policy. */ }
  }, [current, orderId, generation, publish]);
  const reconcile = useCallback(async () => {
    if (!current() || orderId == null || gate.current.state === "loading") return;
    const epoch = ++gate.current.epoch;
    gate.current.state = "loading";
    setState("loading");
    const own = new AbortController();
    controller.current = own;
    const owns = () => current() && epoch === gate.current.epoch && controller.current === own;
    try {
      const order = await fetchInstantOrder(orderId, own.signal, generation);
      if (!owns()) return;
      if (order.id !== orderId) throw new Error("Unexpected order identity");
      // Synchronous publication + rebase: an older read cannot interleave here.
      publish(order);
      setReconciled(true);
      ++gate.current.epoch;
      gate.current.state = "idle";
      setState("idle");
    } catch (error) {
      if (!owns()) return;
      const next = error instanceof ApiError && (error.status === 403 || error.status === 404) ? "denied" : "unconfirmed";
      gate.current.state = next;
      setState(next);
    } finally {
      if (controller.current === own) controller.current = null;
    }
  }, [current, orderId, generation, publish]);
  const invalidate = useCallback(() => {
    ++gate.current.epoch;
    controller.current?.abort();
    controller.current = null;
    gate.current.state = "idle";
    setState("idle");
    setReconciled(false);
  }, []);
  const ticket = useCallback(() => gate.current.epoch, []);
  const canPublish = useCallback((epoch: number) => current() && gate.current.state === "idle" && epoch === gate.current.epoch, [current]);
  return { state, reconciled, read, reconcile, ticket, canPublish, invalidate };
}
