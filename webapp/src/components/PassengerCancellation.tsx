import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, getSessionGeneration } from "../api/client";
import { CANCEL_REASONS, type CancelReason } from "../api/bookings";
import {
  BookingDecisionProblem, cancelPassengerBooking, checkDriverDecision, preparePassengerCancellation,
  repeatDriverDecision, type BookingDecisionOutcome, type PassengerCancellationStatus, type PassengerCancellationTerms,
} from "../api/bookingDecisions";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { track } from "../analytics";
import {
  bookingCommandOwner, isCurrentBookingOwner, readDriverDecisions, type BookingCommandOwner, type BookingDecision,
} from "../utils/bookingCommandStore";
import AlertDialog from "./AlertDialog";
import { IconCheck } from "./Icons";

type Props = {
  bookingId?: number;
  status?: PassengerCancellationStatus;
  onChanged: (result?: BookingDecisionOutcome) => void;
  onSendingStart?: () => void;
  enabled?: boolean;
};

export function usePassengerCancellation({ bookingId, status, onChanged, onSendingStart, enabled = true }: Props) {
  const { user } = useAuth();
  const { appText } = useLang();
  const generation = getSessionGeneration();
  const userId = user?.id;
  const createdAt = user?.created_at;
  const owner = useMemo(() => bookingCommandOwner(
    !enabled || userId === undefined ? null : { id: userId, created_at: createdAt }, generation,
  ), [enabled, userId, createdAt, generation]);
  const [pending, setPending] = useState<BookingDecision[]>([]);
  const [loadError, setLoadError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState(false);
  const [dialog, setDialog] = useState(false);
  const [terms, setTerms] = useState<PassengerCancellationTerms | null>(null);
  const [reason, setReason] = useState<CancelReason>("");
  const [issue, setIssue] = useState<{ error: unknown } | { result: BookingDecisionOutcome } | null>(null);
  const operation = useRef<AbortController | null>(null);
  const screen = useRef<{ owner: BookingCommandOwner | null; bookingId?: number; lifetime: AbortController } | null>(null);
  const liveStatus = useRef(status);
  liveStatus.current = status;

  const reload = useCallback(async (signal: AbortSignal) => {
    if (!owner) return;
    try {
      const rows = await readDriverDecisions(owner);
      if (signal.aborted || !isCurrentBookingOwner(owner)) return;
      setPending(rows.filter(row => row.action === "passenger_cancel" && row.state === "unknown" &&
        (bookingId === undefined || row.bookingId === bookingId)));
      setLoadError(false);
    } catch {
      if (!signal.aborted && isCurrentBookingOwner(owner)) setLoadError(true);
    }
  }, [owner, bookingId]);

  useEffect(() => {
    const lifetime = new AbortController();
    screen.current = { owner, bookingId, lifetime };
    setPending([]);
    setLoadError(false);
    setBusy(false);
    setSending(false);
    setDialog(false);
    setTerms(null);
    setIssue(null);
    void reload(lifetime.signal);
    return () => { lifetime.abort(); operation.current?.abort(); operation.current = null; };
  }, [owner, bookingId, reload]);

  useEffect(() => {
    if (!status || (terms && terms.status !== status)) setDialog(false);
  }, [status, terms]);

  async function run<T>(
    request: (owner: BookingCommandOwner, signal: AbortSignal) => Promise<T>,
    onSuccess: (result: T) => void,
    onError?: (error: unknown) => void,
  ) {
    const mounted = screen.current;
    if (operation.current || !owner || mounted?.owner !== owner || mounted.bookingId !== bookingId ||
        mounted.lifetime.signal.aborted || !isCurrentBookingOwner(owner)) return;
    const controller = new AbortController();
    operation.current = controller;
    const active = () => operation.current === controller && !mounted.lifetime.signal.aborted && isCurrentBookingOwner(owner);
    setBusy(true);
    setIssue(null);
    try {
      const result = await request(owner, controller.signal);
      if (active()) onSuccess(result);
    } catch (error) {
      if (active()) {
        setIssue({ error });
        onError?.(error);
        if (error instanceof BookingDecisionProblem && error.kind === "changed") onChanged();
      }
    } finally {
      if (active()) {
        await reload(mounted.lifetime.signal);
        if (active()) setBusy(false);
      }
      if (operation.current === controller) operation.current = null;
    }
  }

  function open() {
    if (!bookingId || !status || !owner || operation.current || pending.length) return;
    setDialog(true);
    setTerms(null);
    setReason("");
    void run((current, signal) => preparePassengerCancellation(current, bookingId, status, signal), shown => {
      if (liveStatus.current === shown.status) setTerms(shown);
      else { setDialog(false); onChanged(); }
    }, error => {
      if (error instanceof BookingDecisionProblem && ["changed", "pending"].includes(error.kind)) setDialog(false);
    });
  }

  function accept(result: BookingDecisionOutcome, submitted = false) {
    setIssue({ result });
    if (result.state === "unseen") return;
    if (submitted && result.state === "applied" && result.booking?.status === "cancelled") track("booking_cancel");
    onChanged(result);
  }

  function submit() {
    if (!terms || operation.current || terms.status !== liveStatus.current) return;
    setDialog(false);
    const input = terms;
    void run(async (current, signal) => {
      onSendingStart?.();
      setSending(true);
      try { return await cancelPassengerBooking(current, input, reason, signal); }
      finally { if (!signal.aborted && isCurrentBookingOwner(current)) setSending(false); }
    }, result => accept(result, true));
  }

  function message(): string {
    if (!issue) return "";
    if ("error" in issue) {
      const error = issue.error;
      if (error instanceof BookingDecisionProblem) {
        if (error.kind === "changed") return appText("Статус брони изменился. Проверь поездку.", "Бронь торошо үҙгәрҙе. Сәфәрҙе тикшер.");
        if (error.kind === "pending") return appText("Результат отмены пока неизвестен. Проверь запрос.", "Кире алыу һөҙөмтәһе әлегә билдәһеҙ. Һорауҙы тикшер.");
        if (error.kind === "expired") return appText("Срок повтора истёк. Проверь или закрой запрос.", "Ҡабатлау ваҡыты үтте. Һорауҙы тикшер йәки яп.");
      }
      return error instanceof ApiError && error.message ? error.message
        : appText("Не получилось проверить отмену. Попробуй ещё раз.", "Кире алыуҙы тикшереп булманы. Тағы ҡабатла.");
    }
    const result = issue.result;
    if (result.state === "unseen") return appText("Сервер ещё не видит запрос. Его можно повторить или закрыть.", "Сервер һорауҙы әлегә күрмәй. Уны ҡабатларға йәки ябырға мөмкин.");
    if (result.state === "closed_no_effect") return appText("Запрос закрыт. Можно начать отмену заново.", "Һорау ябылды. Кире алыуҙы яңынан башларға мөмкин.");
    if (result.state === "rejected") return result.rejectionCode === "cancel_terms_changed"
      ? appText("Условия отмены изменились. Открой отмену заново.", "Кире алыу шарттары үҙгәрҙе. Кире алыуҙы яңынан ас.")
      : appText("Отмена отклонена. Проверь текущий статус брони.", "Кире алыу кире ҡағылды. Бронь торошон тикшер.");
    if (result.state !== "applied") return "";
    if (result.booking?.status === "done") return appText("Поездка уже завершена.", "Сәфәр инде тамамланған.");
    if (result.booking?.status !== "cancelled") return appText("Обнови поездки, чтобы увидеть текущий статус.", "Ағымдағы торошто күрер өсөн сәфәрҙәрҙе яңырт.");
    return result.booking.contact_then_cancel
      ? appText("Поездка отменена. Если договорились ехать, заверши поездку в приложении, чтобы работали защита и SOS.", "Сәфәр кире алынды. Барырға һөйләшкән булһаң, яҡлау һәм SOS эшләһен өсөн сәфәрҙе ҡушымтала тамамла.")
      : appText("Бронь отменена.", "Бронь кире алынды.");
  }

  const current = !!owner && screen.current?.owner === owner && screen.current.bookingId === bookingId;
  const showDialog = current && dialog && !!status && (!terms || terms.status === status);
  const previewText = terms?.consequence === "driver_departed"
    ? appText("Водитель уже в пути. При отмене он получит уведомление, а место освободится.", "Йөрөтөүсе инде юлда. Кире алһаң, уға хәбәр барасаҡ һәм урын бушаясаҡ.")
    : terms?.consequence === "late"
      ? appText("До выезда осталось мало времени. При отмене водитель получит уведомление, а место освободится.", "Юлға сығырға аҙ ваҡыт ҡалды. Кире алһаң, йөрөтөүсе хәбәр аласаҡ һәм урын бушаясаҡ.")
      : appText("Место освободится, а водитель получит уведомление.", "Урын бушаясаҡ, ә йөрөтөүсе хәбәр аласаҡ.");
  const close = () => {
    operation.current?.abort();
    operation.current = null;
    setBusy(false);
    setDialog(false);
  };

  const content = <>
    {current && loadError && <div className="act-card">
      <p role="status">{appText("Не удалось открыть сохранённые запросы отмены.", "Кире алыуҙың һаҡланған һорауҙарын асып булманы.")}</p>
      <button type="button" className="btn-ghost" onClick={() => { const mounted = screen.current; if (mounted) void reload(mounted.lifetime.signal); }}>
        {appText("Повторить проверку", "Тикшереүҙе ҡабатлау")}
      </button>
    </div>}
    {current && pending.map(row => <div className="act-card" key={row.key}>
      <p>{appText("Запрос отмены брони", "Бронды кире алыу һорауы")} №{row.bookingId}</p>
      <div className="act-card__actions">
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => void run((o, signal) => checkDriverDecision(o, row, false, signal), accept)}>
          {appText("Проверить запрос", "Һорауҙы тикшереү")}
        </button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => void run((o, signal) => repeatDriverDecision(o, row, signal), accept)}>
          {appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау")}
        </button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => void run((o, signal) => checkDriverDecision(o, row, true, signal), accept)}>
          {appText("Закрыть запрос", "Һорауҙы ябыу")}
        </button>
      </div>
    </div>)}
    {current && !showDialog && issue && <div className="notice" role="status">{message()}</div>}
    {showDialog && <AlertDialog
      title={status === "pending" ? appText("Отменить бронь?", "Бронде кире алырғамы?") : appText("Отменить поездку?", "Сәфәрҙе кире аларғамы?")}
      text={terms ? previewText : issue ? message() : appText("Проверяем условия отмены…", "Кире алыу шарттарын тикшерәбеҙ…")}
      onClose={close}
      confirm={terms
        ? { label: appText("Да, отменить", "Эйе, кире алырға"), tone: "danger", onClick: submit, disabled: busy }
        : issue ? { label: appText("Повторить проверку", "Тикшереүҙе ҡабатлау"), onClick: open, disabled: busy } : undefined}
      dismiss={{ label: appText("Оставить бронь", "Бронде ҡалдырырға"), onClick: close }}
    >
      {terms?.status === "confirmed" && <div className="adlg__reasons" role="group" aria-label={appText("Причина (по желанию)", "Сәбәбе (теләгәнсә)")}>
        <p className="adlg__reasons-label">{appText("Причина (по желанию)", "Сәбәбе (теләгәнсә)")}</p>
        {CANCEL_REASONS.map(option => <button key={option.code} type="button"
          className={"adlg__reason" + (reason === option.code ? " is-on" : "")}
          aria-pressed={reason === option.code} disabled={busy}
          onClick={() => setReason(reason === option.code ? "" : option.code)}>
          <span>{appText(option.ru, option.ba)}</span>
          {reason === option.code && <IconCheck size={18} />}
        </button>)}
      </div>}
    </AlertDialog>}
  </>;
  return { open, disabled: !current || busy || pending.length > 0, sending: current && sending, content };
}

export default function PassengerCancellationRecovery(props: Props) {
  return usePassengerCancellation(props).content;
}
