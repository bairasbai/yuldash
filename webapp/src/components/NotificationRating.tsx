import { useEffect, useRef, useState } from "react";
import { fetchNotificationRatingState, rateBookingFromNotification } from "../api/notificationRating";
import { ApiError, isAccountSession } from "../api/client";
import { useLang } from "../i18n/lang";
import { isCurrentRatingDraft, takeRatingDraft, type RatingDraft } from "../push/rateDraft";
import AlertDialog from "./AlertDialog";

type RatingState =
  | { kind: "confirm" | "busy" | "retry" | "refused" }
  | { kind: "accepted" | "kept" | "known"; stars: number };

export default function NotificationRating({ bookingId, userId, generation, onRated, onEdit }: {
  bookingId: number;
  userId: number;
  generation: string;
  onRated: () => void;
  onEdit: () => void;
}) {
  const { appText } = useLang();
  const [draft, setDraft] = useState<RatingDraft>();
  const [state, setState] = useState<RatingState>();
  const active = useRef<AbortController | null>(null);

  useEffect(() => {
    const lifetime = new AbortController();
    // React's development remount must not consume the click before the real mount.
    const timer = window.setTimeout(() => {
      void (async () => {
        const candidate = await takeRatingDraft(bookingId, userId, generation);
        if (!candidate || !isAccountSession(candidate) || lifetime.signal.aborted) return;
        try {
          const fresh = await fetchNotificationRatingState(bookingId, candidate, lifetime.signal);
          if (!fresh.allowed || !await isCurrentRatingDraft(candidate) || lifetime.signal.aborted) return;
        } catch { return; }
        setDraft(candidate);
        setState({ kind: "confirm" });
      })();
    }, 0);
    return () => {
      clearTimeout(timer);
      lifetime.abort();
      active.current?.abort();
      active.current = null;
    };
  }, [bookingId, userId, generation]);

  useEffect(() => {
    if (!draft || !state || !["confirm", "retry"].includes(state.kind)) return;
    const timer = setTimeout(() => setState(undefined), Math.max(0, draft.expiresAt - Date.now()));
    return () => clearTimeout(timer);
  }, [draft, state]);

  function close() {
    if (!active.current) setState(undefined);
  }

  async function submit() {
    if (!draft || active.current || (state?.kind !== "confirm" && state?.kind !== "retry")) return;
    const request = new AbortController();
    const { signal } = request;
    const current = () => !signal.aborted && isAccountSession(draft);
    active.current = request;
    setState({ kind: "busy" });
    try {
      if (!current()) return;
      const fresh = await fetchNotificationRatingState(bookingId, draft, signal);
      if (!await isCurrentRatingDraft(draft) || !current()) {
        if (current()) setState(undefined);
        return;
      }
      if (!fresh.allowed) {
        setState({ kind: "refused" });
        return;
      }
      const result = await rateBookingFromNotification(draft, signal);
      if (!current() || !await isCurrentRatingDraft(draft) || !current()) {
        setState(undefined);
        return;
      }
      if (result) {
        setState({ kind: result.kept ? "kept" : "accepted", stars: result.stars });
        onRated();
        return;
      }
      const latest = await fetchNotificationRatingState(bookingId, draft, signal);
      if (current() && await isCurrentRatingDraft(draft) && current() && latest.stars > 0) {
        setState({ kind: "known", stars: latest.stars });
        onRated();
      } else if (current()) setState({ kind: "retry" });
    } catch (error) {
      if (!current()) return;
      if (error instanceof ApiError && error.code === "command_scope_changed") {
        setState(undefined);
        return;
      }
      const refused = error instanceof ApiError && [400, 403, 404, 409, 410, 422].includes(error.status);
      setState({ kind: refused ? "refused" : "retry" });
    } finally {
      if (active.current === request) active.current = null;
    }
  }

  if (!draft || !state || !isAccountSession(draft)) return null;

  if ("stars" in state) {
    const title = state.kind === "accepted"
      ? appText(`Оценка ${state.stars} ★ принята`, `${state.stars} ★ баһа ҡабул ителде`)
      : state.kind === "kept"
        ? appText(`Оценка уже стоит: ${state.stars} ★`, `Баһа ҡуйылған: ${state.stars} ★`)
        : appText(`Текущая оценка: ${state.stars} ★`, `Әлеге баһа: ${state.stars} ★`);
    return <AlertDialog title={title}
      confirm={{ label: appText("Готово", "Әҙер"), onClick: close }}
      dismiss={{ label: appText("Изменить", "Үҙгәртергә"), onClick: () => { close(); onEdit(); } }}
      onClose={close} />;
  }
  if (state.kind === "refused") {
    return <AlertDialog title={appText("Эту поездку оценить уже нельзя", "Был сәфәрҙе хәҙер баһалап булмай")}
      confirm={{ label: appText("Закрыть", "Ябырға"), onClick: close }} onClose={close} />;
  }
  const busy = state.kind === "busy";
  const retry = state.kind === "retry";
  return <AlertDialog
    title={busy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Поставить 5 ★?", "5 ★ ҡуйырғамы?")}
    text={retry ? appText("Не удалось подтвердить оценку. Попробуй ещё раз.", "Баһаны раҫлап булманы. Тағы бер тапҡыр һынап ҡара.") : undefined}
    confirm={{ label: retry ? appText("Повторить", "Ҡабатларға") : appText("Да", "Эйе"), busy, onClick: () => void submit() }}
    dismiss={{ label: appText("Отмена", "Кире алыу"), onClick: close, disabled: busy }}
    onClose={close} />;
}
