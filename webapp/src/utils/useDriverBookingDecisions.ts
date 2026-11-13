import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api/client";
import {
  checkDriverDecision, decideDriverBooking, BookingDecisionProblem, repeatDriverDecision,
  type BookingDecisionOutcome,
} from "../api/bookingDecisions";
import { useLang } from "../i18n/lang";
import {
  isCurrentBookingOwner, readDriverDecisions,
  type BookingCommandOwner, type BookingDecision, type BookingDecisionAction,
} from "../utils/bookingCommandStore";

type Notice = { result: BookingDecisionOutcome; language: string }
  | { error: unknown; language: string }
  | { noOwner: true };

export function useDriverBookingDecisions(owner: BookingCommandOwner | null, onResult: () => void) {
  const { appText, lang } = useLang();
  const [pending, setPending] = useState<BookingDecision[]>([]);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [reload, setReload] = useState(0);
  const [notice, setNotice] = useState<Notice | null>(null);
  const screen = useRef<{ lifetime: AbortController; owner: BookingCommandOwner | null } | null>(null);
  const operation = useRef<AbortController | null>(null);

  const readPending = useCallback(async (signal: AbortSignal) => {
    if (!owner) return;
    try {
      const rows = await readDriverDecisions(owner);
      if (!signal.aborted && isCurrentBookingOwner(owner)) {
        setPending(rows.filter(row => row.state === "unknown" && row.action !== "passenger_cancel"));
        setLoadError(false);
      }
    } catch {
      if (!signal.aborted && isCurrentBookingOwner(owner)) setLoadError(true);
    }
  }, [owner]);

  useEffect(() => {
    const lifetime = new AbortController();
    screen.current = { lifetime, owner };
    setPending([]);
    setBusy(false);
    setNotice(null);
    setLoadError(false);
    void readPending(lifetime.signal);
    return () => {
      lifetime.abort();
      operation.current?.abort();
      operation.current = null;
    };
  }, [owner, readPending, reload]);

  function resultMessage(outcome: BookingDecisionOutcome, useServerText: boolean): string {
    if (useServerText && outcome.state !== "applied" && outcome.original?.message) return outcome.original.message;
    switch (outcome.state) {
      case "unseen": return appText("Решение пока неизвестно. Его можно повторить или закрыть.", "Ҡарар әлегә билдәһеҙ. Уны ҡабатларға йәки ябырға мөмкин.");
      case "rejected": return appText("Сервер отклонил действие. Проверь статус брони.", "Сервер ғәмәлде кире ҡаҡты. Бронь торошон тикшер.");
      case "closed_no_effect": return appText("Запрос закрыт. Можно ответить заново.", "Һорау ябылды. Яңынан яуап бирергә мөмкин.");
      case "applied":
        switch (outcome.booking?.status) {
          case "confirmed": return appText("Бронь подтверждена.", "Бронь раҫланды.");
          case "cancelled": return outcome.booking.declined
            ? appText("Бронь отклонена.", "Бронь кире ҡағылды.")
            : appText("Бронь отменена.", "Бронь кире алынды.");
          case "onboard": return appText("Пассажир уже в пути.", "Юлаусы инде юлда.");
          case "done": return appText("Поездка уже завершена.", "Сәфәр инде тамамланған.");
          case "pending": return appText("Бронь ждёт ответа.", "Бронь яуап көтә.");
          default: return appText("Бронь больше недоступна.", "Бронь бүтән асыҡ түгел.");
        }
    }
  }

  function noticeMessage(current: Notice | null): string {
    if (!current) return "";
    if ("noOwner" in current) return appText("Не удалось проверить аккаунт. Открой кабинет заново.", "Аккаунтты тикшереп булманы. Кабинетты яңынан ас.");
    const useServerText = current.language === lang;
    if ("result" in current) return resultMessage(current.result, useServerText);
    const error = current.error;
    if (error instanceof BookingDecisionProblem) {
      switch (error.kind) {
        case "changed": return appText("Статус брони изменился. Проверь список.", "Бронь торошо үҙгәрҙе. Исемлекте тикшер.");
        case "expired": return appText("Срок повтора истёк. Проверь или закрой запрос.", "Ҡабатлау ваҡыты үтте. Һорауҙы тикшер йәки яп.");
        case "pending": return appText("Ответ потерялся. Проверь решение.", "Яуап юғалды. Ҡарарҙы тикшер.");
      }
    }
    return useServerText && error instanceof ApiError && error.message ? error.message
      : appText("Не удалось проверить решение. Попробуй ещё раз.", "Ҡарарҙы тикшереп булманы. Тағы ҡабатла.");
  }

  async function run(action: (current: BookingCommandOwner, signal: AbortSignal) => Promise<BookingDecisionOutcome>) {
    if (operation.current) return;
    if (!owner || !isCurrentBookingOwner(owner)) {
      setNotice({ noOwner: true });
      return;
    }
    const currentScreen = screen.current;
    if (!currentScreen || currentScreen.owner !== owner || currentScreen.lifetime.signal.aborted) return;
    const { lifetime } = currentScreen;
    const controller = new AbortController();
    operation.current = controller;
    const active = () => operation.current === controller && !lifetime.signal.aborted && isCurrentBookingOwner(owner);
    setBusy(true);
    setNotice(null);
    try {
      const result = await action(owner, controller.signal);
      if (!active()) return;
      setNotice({ result, language: lang });
      if (result.state !== "unseen") onResult();
    } catch (error) {
      if (!active()) return;
      setNotice({ error, language: lang });
      if (error instanceof BookingDecisionProblem && error.kind === "changed") onResult();
    } finally {
      if (active()) {
        await readPending(lifetime.signal);
        if (active()) setBusy(false);
      }
      if (operation.current === controller) operation.current = null;
    }
  }

  const sameOwner = screen.current?.owner === owner;
  return {
    pending: sameOwner ? pending : [],
    busy: sameOwner && busy,
    note: sameOwner ? noticeMessage(notice) : "",
    loadError: sameOwner && loadError,
    retryLoad: () => setReload(n => n + 1),
    answer: (id: number, action: Exclude<BookingDecisionAction, "passenger_cancel">) =>
      run((o, signal) => decideDriverBooking(o, id, action, signal)),
    check: (decision: BookingDecision, close = false) => run((o, signal) => checkDriverDecision(o, decision, close, signal)),
    repeat: (decision: BookingDecision) => run((o, signal) => repeatDriverDecision(o, decision, signal)),
  };
}
