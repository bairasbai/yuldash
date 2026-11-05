import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { respondBoardingUndo, type BoardingResult, type BoardingUndoTerms } from "../api/bookings";

export default function PassengerBoardingUndo({ bookingId, terms, onChanged }: {
  bookingId: number; terms: BoardingUndoTerms; onChanged: (result: BoardingResult) => void;
}) {
  const { appText } = useLang();
  const owner = useRef(getSessionGeneration());
  const live = useRef(false);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  const currentBooking = useRef(bookingId);
  currentBooking.current = bookingId;
  const request = useRef(terms.boarding_undo_request_id);
  request.current = terms.boarding_undo_request_id;
  const [left, setLeft] = useState(terms.boarding_undo_wait_sec ?? 0);
  const sending = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { setLeft(terms.boarding_undo_wait_sec ?? 0); setError(""); }, [bookingId, terms.boarding_undo_request_id, terms.boarding_undo_wait_sec]);
  useEffect(() => {
    if (left <= 0) return;
    const timer = setTimeout(() => setLeft(v => Math.max(0, v - 1)), 1000);
    return () => clearTimeout(timer);
  }, [left]);
  async function answer(confirm: boolean) {
    const id = terms.boarding_undo_request_id;
    if (sending.current || !id || left <= 0 || !live.current || owner.current !== getSessionGeneration()) return;
    sending.current = true; setBusy(true); setError("");
    try {
      const result = await respondBoardingUndo(bookingId, id, confirm, owner.current);
      if (live.current && currentBooking.current === bookingId && owner.current === getSessionGeneration() && request.current === id) onChanged(result);
    } catch (e) {
      if (live.current && currentBooking.current === bookingId && owner.current === getSessionGeneration() && request.current === id) setError(e instanceof ApiError ? e.message : appText("Не получилось ответить. Проверь сеть и повтори.", "Яуап биреп булманы. Селтәрҙе тикшер ҙә ҡабатла."));
    } finally { sending.current = false; if (live.current) setBusy(false); }
  }
  if (!terms.boarding_undo_pending || !terms.boarding_undo_request_id || left <= 0) return null;
  return <section className="act-card" aria-label={appText("Исправление посадки", "Ултырыуҙы төҙәтеү")}>
    <h2 className="act-card__title">{appText("Водитель просит исправить посадку", "Йөрөтөүсе ултырыу билдәһен төҙәтергә һорай")}</h2>
    <p className="act-card__text">{appText("Подтверди только если ты ещё не в машине.", "Машинаға әле ултырмаған булһаң ғына раҫла.")}</p>
    <button type="button" className="btn-primary" disabled={busy} onClick={() => void answer(true)}>{appText("Я ещё не сел", "Мин әле ултырманым")}</button>
    <button type="button" className="btn-soft" disabled={busy} onClick={() => void answer(false)}>{appText("Я в машине", "Мин машиналамын")}</button>
    {error && <p role="alert">{error}</p>}
  </section>;
}
