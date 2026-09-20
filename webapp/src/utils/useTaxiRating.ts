import { useEffect, useRef, useState } from "react";
import { getSessionGeneration } from "../api/client";
import { fetchInstantOrder, rateInstantOrder, type InstantOrder } from "../api/instant";
import { useLang } from "../i18n/lang";

function known(order: InstantOrder): boolean {
  return Number.isInteger(order.my_stars) && order.my_stars! >= 0 && order.my_stars! <= 5 && typeof order.can_rate === "boolean";
}

/** Общая отправка финальных оценок двух сторон; состояние экрана привязано key к заказу/сессии. */
export function useTaxiRating(order: InstantOrder) {
  const { appText } = useLang();
  const [stars, setStarsValue] = useState(known(order) ? order.my_stars! : 0);
  const [saved, setSaved] = useState(known(order) ? order.my_stars! : 0);
  const [tags, setTagsValue] = useState<string[]>([]);
  const [allowed, setAllowed] = useState(known(order) && order.can_rate === true);
  const [sending, setSending] = useState(false);
  const [checking, setChecking] = useState(!known(order));
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState("");
  const alive = useRef(true);
  const owner = useRef(getSessionGeneration());
  const inFlight = useRef(false);
  const sequence = useRef(0);
  const current = () => alive.current && owner.current === getSessionGeneration();

  async function refresh(afterSend = false) {
    const request = ++sequence.current;
    setChecking(true);
    setFailed(false);
    try {
      const next = await fetchInstantOrder(order.id);
      if (!current() || sequence.current !== request) return;
      if (next.id !== order.id || !known(next)) throw new Error("Invalid taxi rating state");
      setAllowed(next.can_rate === true);
      setSaved(next.my_stars!);
      if (next.my_stars! > 0) setStarsValue(next.my_stars!);
      setError(afterSend && next.my_stars === 0
        ? appText("Не получилось отправить оценку. Проверь сеть и повтори.", "Баһаны ебәреп булманы. Селтәрҙе тикшереп ҡабатла.") : "");
    } catch {
      if (current() && sequence.current === request) setFailed(true);
    } finally {
      if (current() && sequence.current === request) setChecking(false);
    }
  }

  useEffect(() => {
    alive.current = true;
    if (!known(order)) void refresh();
    return () => { alive.current = false; sequence.current++; };
  }, []);

  useEffect(() => {
    if (!known(order) || inFlight.current) return;
    setAllowed(order.can_rate === true);
    // Запоздалый нулевой снимок поллинга не отменяет подтверждённую отправку.
    if (order.my_stars! > 0) { setSaved(order.my_stars!); setStarsValue(order.my_stars!); }
  }, [order.my_stars, order.can_rate]);

  async function submit() {
    if (!current() || inFlight.current || checking || failed || !allowed || saved > 0 || stars <= 0) return;
    const value = stars;
    inFlight.current = true;
    setSending(true);
    setError("");
    try {
      await rateInstantOrder(order.id, value, tags.join(","));
      if (current()) { setSaved(value); setStarsValue(value); }
    } catch {
      if (current()) await refresh(true);
    } finally {
      inFlight.current = false;
      if (current()) setSending(false);
    }
  }

  return {
    stars, tags, sending, sent: saved > 0, error,
    canRate: allowed && !checking && !failed,
    retryNeeded: failed,
    notice: checking ? appText("Проверяем оценку…", "Баһаны тикшерәбеҙ…")
      : failed ? appText("Не получилось проверить оценку. Повтори попытку.", "Баһаны тикшереп булманы. Ҡабатлап ҡара.")
      : appText("Оценка этой поездки сейчас недоступна.", "Был сәфәргә хәҙер баһа ҡуйып булмай."),
    retry: () => void refresh(), submit,
    setStars: (value: number) => { if (current() && !inFlight.current) { setStarsValue(value); setError(""); } },
    setTags: (value: string[] | ((prev: string[]) => string[])) => { if (current() && !inFlight.current) setTagsValue(value); },
  };
}
