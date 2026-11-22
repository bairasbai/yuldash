import { useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Link } from "react-router-dom";
import { ApiError, getAccountSession, isAccountSession } from "../api/client";
import { subscribeSessionChanges } from "../utils/sessionCredentials";
import { fetchInstantOrder, type InstantOrder, type OrderInput } from "../api/instant";
import { closeTaxiConsentAttempt, confirmTaxiConsent, fetchTaxiConsent, fetchTaxiConsentResult, localTaxiConsentEnvironment, type TaxiConsentOwner, type TaxiConsentQuote } from "../api/taxiConsent";
import { beginTaxiClose, clearOwnedTaxiPending, consentGeneration, markTaxiPending, ownsTaxiPending, pendingTaxiMarkers, pendingTaxiResults, readRecoveredTaxiOrder,
  TAXI_CONSENT_MARKER_PREFIX, taxiConsentFresh, type ConsentStamp, type TaxiPendingMarker } from "../utils/taxiConsent";
import { serverMs } from "../utils/serverTime";
import { kopExactLabel } from "../utils/format";
import { useLang } from "../i18n/lang";
import AlertDialog from "./AlertDialog";

export function TaxiConsentFields({ enabled, locked, children }: {
  enabled: boolean; locked: boolean; children: ReactNode;
}) {
  return enabled ? <fieldset className="taxi-consent-fields" disabled={locked}>{children}</fieldset> : <>{children}</>;
}

type Quoted = { data: TaxiConsentQuote; owner: TaxiConsentOwner; input: string; stamp: ConsentStamp };
type Attempt = { quote: TaxiConsentQuote; owner: TaxiConsentOwner; body: string; marker: string; environment: string; input: string; unknown: boolean };
type RecoveryAction = "lookup" | "close";
type RecoveryReport = { marker: string; state: "lookupUnknown" | "retired" | "unverified" | "closed" };
type Review = { kind: "first" | "updated"; quote: Quoted } | { kind: "retry"; attempt: Attempt };

/** Deliberately RAM-only request state. A marker without this body blocks a new POST. */
export default function TaxiConsentAction({ body, environment, onOrdered, onLockChange, onSessionReset, onQuote }: {
  body: OrderInput | null; environment: string | null; onOrdered: (order: InstantOrder) => void;
  onLockChange: (locked: boolean) => void; onSessionReset: () => void; onQuote: (quote: TaxiConsentQuote | null) => void;
}) {
  const { appText } = useLang();
  const [, render] = useState(0);
  const owner = getAccountSession();
  const generation = owner?.generation ?? "";
  const input = JSON.stringify(body);
  const scope = JSON.stringify([generation, owner?.userId, environment, input]);
  const current = useRef({ body, owner, environment, scope });
  current.current = { body, owner, environment, scope };
  const callbacks = useRef({ onOrdered, onLockChange, onSessionReset, onQuote });
  callbacks.current = { onOrdered, onLockChange, onSessionReset, onQuote };
  const alive = useRef(true);
  const reviewRoot = useRef<HTMLDivElement>(null);
  const previousGeneration = useRef(JSON.stringify([generation, owner?.userId, environment]));
  const sessionBarrier = useRef(false);
  const quoteEpoch = useRef(0);
  const quoteFlight = useRef("");
  const sending = useRef(false);
  const recovering = useRef(false);
  const recoveryEpoch = useRef(0);
  const resolved = useRef(false);
  const manualQuote = useRef(false);
  const [reading, setReading] = useState<RecoveryAction | null>(null);
  const [closeReview, setCloseReview] = useState<TaxiPendingMarker | null>(null);
  const [report, setReport] = useState<RecoveryReport | null>(null);
  const [selectedMarker, setSelectedMarker] = useState("");
  const pending = useRef<Attempt | null>(null);
  const [quoted, setQuoted] = useState<Quoted | null>(null);
  const [review, setReview] = useState<Review | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [unknown, setUnknown] = useState(false);
  const [blocked, setBlocked] = useState(true);
  const [error, setError] = useState("");

  function markers(excludeOwn = true): string[] {
    const now = current.current;
    if (!now.owner || !now.environment || !consentGeneration(now.owner.generation)) throw new Error("No local session");
    return pendingTaxiMarkers(localStorage, now.owner.generation, now.environment)
      .filter(key => !excludeOwn || key !== pending.current?.marker);
  }
  function syncMarkers() {
    if (!alive.current) return;
    try { setBlocked(markers().length > 0); }
    catch { setBlocked(true); }
  }
  useEffect(() => {
    alive.current = true;
    const session = () => render(n => n + 1);
    const changed = (event: StorageEvent) => {
      if (event.key === null || event.key.startsWith(TAXI_CONSENT_MARKER_PREFIX)) {
        syncMarkers(); render(n => n + 1);
      }
    };
    const focus = () => { syncMarkers(); render(n => n + 1); };
    const unsubscribe = subscribeSessionChanges(session);
    window.addEventListener("storage", changed);
    window.addEventListener("focus", focus);
    document.addEventListener("visibilitychange", focus);
    const timer = window.setInterval(() => render(n => n + 1), 1000);
    return () => {
      alive.current = false; ++quoteEpoch.current; ++recoveryEpoch.current;
      unsubscribe(); window.removeEventListener("storage", changed); window.removeEventListener("focus", focus);
      document.removeEventListener("visibilitychange", focus); window.clearInterval(timer);
      callbacks.current.onLockChange(false);
    };
  }, []); // refs always point to the current scope

  useEffect(() => {
    const identity = JSON.stringify([generation, owner?.userId, environment]);
    if (previousGeneration.current !== identity) {
      sessionBarrier.current = true; ++recoveryEpoch.current; recovering.current = false; resolved.current = false; manualQuote.current = false;
      setReading(null); setCloseReview(null); setReport(null); setSelectedMarker(""); setError("");
      pending.current = null; ++quoteEpoch.current; quoteFlight.current = "";
      setQuoted(null); setReview(null); setUnknown(false); setBusy(false); sending.current = false;
      callbacks.current.onSessionReset();
    }
    previousGeneration.current = identity;
    syncMarkers();
  }, [generation, owner?.userId, environment]);

  useEffect(() => { callbacks.current.onLockChange(busy || unknown || (blocked && !!environment && !!owner) || !!reading); }, [busy, unknown, blocked, reading, environment, generation]);

  async function loadQuote(showReview: boolean) {
    const now = current.current;
    if (!alive.current || recovering.current || resolved.current || sessionBarrier.current || manualQuote.current || !now.body || !now.owner || !now.environment || pending.current
        || !isAccountSession(now.owner) || !consentGeneration(now.owner.generation)) return;
    try { if (markers(false).length) { syncMarkers(); return; } }
    catch { setBlocked(true); return; }
    if (quoteFlight.current === now.scope) return;
    const epoch = ++quoteEpoch.current;
    quoteFlight.current = now.scope;
    const frozenBody: OrderInput = JSON.parse(JSON.stringify(now.body));
    const start = { wall: Date.now(), monotonic: performance.now() };
    const valid = () => alive.current && epoch === quoteEpoch.current && current.current.scope === now.scope
      && isAccountSession(now.owner!) && !pending.current && !recovering.current && !resolved.current;
    setLoading(true); setQuoted(null); callbacks.current.onQuote(null); setError("");
    try {
      const data = await fetchTaxiConsent(frozenBody, now.owner, async () => valid() && markers(false).length === 0);
      if (!valid() || markers(false).length) return;
      const next = { data, owner: now.owner, input: now.scope,
        stamp: { ...start, validForMs: Math.max(0, serverMs(data.expires_at) - start.wall) } };
      setQuoted(next); callbacks.current.onQuote(data);
      if (showReview) setReview({ kind: "updated", quote: next });
    } catch (reason) {
      if (valid()) setError(reason instanceof ApiError && reason.status === 409 && reason.code === "taxi_quote_price_pending"
        ? appText("Стоимость подачи ещё неизвестна. Повтори расчёт, когда появится водитель. Заказ не отправляем.",
          "Машина килеү хаҡы әлегә билдәһеҙ. Йөрөтөүсе күренгәс, иҫәпте ҡабатла. Заказды ебәрмәйбеҙ.")
        : appText("Не удалось получить сумму. Повтори расчёт.", "Сумманы алып булманы. Иҫәпте ҡабатла."));
    } finally {
      if (epoch === quoteEpoch.current) {
        quoteFlight.current = "";
        if (alive.current) setLoading(false);
      }
    }
  }

  useEffect(() => {
    ++quoteEpoch.current; quoteFlight.current = ""; setQuoted(null); callbacks.current.onQuote(null); setReview(null); setLoading(false);
    if (!current.current.body) sessionBarrier.current = false;
    if (!blocked && !pending.current && !recovering.current && !resolved.current) void loadQuote(false);
  }, [scope, blocked]);

  const usable = quoted && quoted.input === scope && isAccountSession(quoted.owner) ? quoted : null;
  const fresh = usable && taxiConsentFresh(usable.stamp);

  async function send(attempt: Attempt) {
    if (recovering.current || resolved.current || sending.current || !isAccountSession(attempt.owner) || current.current.environment !== attempt.environment) return;
    // Freeze controls in the same event as closing the dialog, before a second tap.
    sending.current = true; callbacks.current.onLockChange(true);
    setBusy(true); setError(""); setReview(null);
    try {
      const order = await confirmTaxiConsent(attempt.body, attempt.quote, attempt.owner, async () =>
        isAccountSession(attempt.owner) && current.current.environment === attempt.environment
        && pending.current === attempt && (attempt.unknown || current.current.scope === attempt.input)
        && ownsTaxiPending(localStorage, { key: attempt.marker, version: 2, requestKey: attempt.quote.request_key }, attempt.owner.generation, attempt.environment)
        && (attempt.unknown || markers().length === 0));
      if (!alive.current || pending.current !== attempt || !isAccountSession(attempt.owner) || current.current.environment !== attempt.environment) return;
      // A valid receipt proves the result even if removing the local marker fails.
      try { clearOwnedTaxiPending(localStorage, { key: attempt.marker, version: 2, requestKey: attempt.quote.request_key }, attempt.owner.generation, attempt.environment); } catch { /* Keep the conservative marker. */ }
      pending.current = null;
      if (alive.current) {
        setUnknown(false); setBusy(false); syncMarkers();
        if (current.current.scope === attempt.input) callbacks.current.onOrdered(order);
        else setError(appText("Подтверждение получено. Проверь поездки.", "Раҫлау алынды. Сәфәрҙәрҙе тикшер."));
      }
    } catch (reason) {
      if (!alive.current || !isAccountSession(attempt.owner) || current.current.environment !== attempt.environment || pending.current !== attempt) return;
      const knownFirstRejection = !attempt.unknown && reason instanceof ApiError
        && ((reason.status === 409 && ["taxi_quote_refresh_required", "taxi_consent_active_order"].includes(reason.code))
          || (reason.status === 422 && ["taxi_quote_payment_unavailable", "taxi_quote_request_invalid"].includes(reason.code)));
      if (knownFirstRejection) {
        try { clearOwnedTaxiPending(localStorage, { key: attempt.marker, version: 2, requestKey: attempt.quote.request_key }, attempt.owner.generation, attempt.environment); }
        catch { attempt.unknown = true; setUnknown(true); return; }
        pending.current = null; setQuoted(null); callbacks.current.onQuote(null); setUnknown(false);
        setError(appText("Подтверждение не принято. Обнови сумму и проверь поездки.", "Раҫлау ҡабул ителмәне. Сумманы яңырт һәм сәфәрҙәрҙе тикшер."));
      } else {
        // AFTER unknown, even an explicit denial cannot establish that no order existed.
        attempt.unknown = true; setUnknown(true);
      }
      syncMarkers();
    } finally {
      if (alive.current && isAccountSession(attempt.owner) && current.current.environment === attempt.environment) {
        sending.current = false; setBusy(false);
      }
    }
  }

  async function accept(quote: Quoted) {
    if (recovering.current || resolved.current || sending.current || pending.current || current.current.scope !== quote.input || !isAccountSession(quote.owner)) return;
    // Expiry during an open dialog gets another review, never an automatic create.
    if (!taxiConsentFresh(quote.stamp)) { setReview(null); await loadQuote(true); return; }
    try {
      if (markers(false).length) { setReview(null); syncMarkers(); return; }
      const now = current.current;
      if (!now.body || !now.environment) return;
      const marker = markTaxiPending(localStorage, quote.owner.generation, now.environment, quote.data.request_key);
      const attempt: Attempt = { quote: quote.data, owner: quote.owner, marker, environment: now.environment,
        unknown: false, input: quote.input, body: JSON.stringify({ ...now.body, quote_id: quote.data.quote_id, request_key: quote.data.request_key }) };
      pending.current = attempt;
      await send(attempt);
    } catch {
      setReview(null); setBlocked(true);
      setError(appText("Не удалось сохранить состояние подтверждения. Запрос не отправляем.", "Раҫлау хәлен һаҡлап булманы. Һорауҙы ебәрмәйбеҙ."));
    }
  }

  function primary() {
    if (recovering.current || resolved.current || sending.current) return;
    const attempt = pending.current;
    // Deliberately BEFORE TTL/quote refresh. Nothing is reconstructed for this replay.
    if (attempt) {
      if (canRetry && isAccountSession(attempt.owner)) setReview({ kind: "retry", attempt });
      return;
    }
    try { if (markers(false).length) { syncMarkers(); return; } } catch { setBlocked(true); return; }
    manualQuote.current = false; setReport(null);
    if (!usable || !taxiConsentFresh(usable.stamp)) { void loadQuote(true); return; }
    setReview({ kind: "first", quote: usable });
  }

  function recoverable(): TaxiPendingMarker[] {
    const now = current.current;
    if (!now.owner || !now.environment) return [];
    try { return pendingTaxiResults(localStorage, now.owner.generation, now.environment); }
    catch { return []; } // Malformed storage blocks creation; it does not authorize a lookup.
  }
  function missingAttemptMarker(): TaxiPendingMarker | undefined {
    const attempt = pending.current;
    if (!attempt?.unknown || !isAccountSession(attempt.owner)
        || current.current.environment !== attempt.environment
        || localTaxiConsentEnvironment() !== attempt.environment) return;
    try {
      // The key is the one issued to this in-memory request. A removed marker
      // says nothing about the order. A replaced or unreadable marker stays untouched.
      pendingTaxiResults(localStorage, attempt.owner.generation, attempt.environment);
      if (localStorage.getItem(attempt.marker) !== null) return;
      return { key: attempt.marker, requestKey: attempt.quote.request_key, version: 3 };
    } catch { return; }
  }
  async function recover(initial: TaxiPendingMarker, action: RecoveryAction) {
    const { owner, environment } = current.current;
    if (recovering.current || sending.current || resolved.current || !owner || !environment
        || !initial.requestKey || initial.version === 1) return;
    const epoch = ++recoveryEpoch.current;
    let marker = initial;
    const valid = () => alive.current && epoch === recoveryEpoch.current && isAccountSession(owner)
      && current.current.environment === environment && localTaxiConsentEnvironment() === environment
      && (!pending.current || (pending.current.unknown && pending.current.marker === marker.key))
      && ownsTaxiPending(localStorage, marker, owner.generation, environment);
    try {
      if (!valid()) {
        const missing = missingAttemptMarker();
        if (!alive.current || epoch !== recoveryEpoch.current || !isAccountSession(owner)
            || localTaxiConsentEnvironment() !== environment || !missing || missing.key !== initial.key
            || missing.requestKey !== initial.requestKey) return;
        // Restore a recovery-only barrier on explicit action. No body or old
        // confirmation is restored. markTaxiPending refuses any occupied slot.
        markTaxiPending(localStorage, owner.generation, environment, initial.requestKey, 3);
        marker = missing;
        if (!valid()) return;
      }
    } catch {
      setError(appText("Не удалось проверить сохранённую попытку. Проверь поездки.",
        "Һаҡланған ынтылышты тикшереп булманы. Сәфәрҙәрҙе тикшер."));
      return;
    }
    recovering.current = true; setReading(action); setCloseReview(null); setReview(null);
    callbacks.current.onLockChange(true); setError("");
    ++quoteEpoch.current; quoteFlight.current = ""; setQuoted(null); callbacks.current.onQuote(null);
    let committed = false;
    try {
      // Record the stop intent before transmission. Its receipt may be lost, and another
      // tab holding the original request must not offer to confirm it again.
      if (action === "close") marker = beginTaxiClose(localStorage, marker, owner.generation, environment);
      const result = action === "close"
        ? await closeTaxiConsentAttempt(initial.requestKey, owner, async () => valid())
        : await fetchTaxiConsentResult(initial.requestKey, owner, async () => valid());
      if (!valid()) return;
      if (result.state === "committed") {
        committed = true;
        const order = readRecoveredTaxiOrder(await fetchInstantOrder(result.order_id, undefined,
          { owner, allowAttempt: async () => valid() }), result.order_id, owner.userId);
        if (!valid()) return;
        resolved.current = true;
        try { clearOwnedTaxiPending(localStorage, marker, owner.generation, environment); }
        catch { /* The authorized current card is proven; retain the conservative marker. */ }
        pending.current = null; callbacks.current.onOrdered(order);
      } else if (result.state === "closed") {
        // Only closed/not_created releases this slot. Require a new explicit calculation.
        clearOwnedTaxiPending(localStorage, marker, owner.generation, environment);
        manualQuote.current = true; pending.current = null; setUnknown(false); syncMarkers();
        setReport({ marker: marker.key, state: "closed" });
      } else if (result.schema === "taxi-consent-close-v1") {
        setReport({ marker: marker.key, state: result.state === "retired" ? "retired" : "unverified" });
      } else {
        // A read can neither prove absence nor erase an earlier, stronger close result.
        setReport(previous => previous?.marker === marker.key && previous.state !== "lookupUnknown"
          ? previous : { marker: marker.key, state: "lookupUnknown" });
      }
    } catch {
      let currentScope = false;
      try { currentScope = valid(); } catch { /* Retain the barrier on storage failure. */ }
      if (currentScope) setError(committed
        ? appText("Заказ был создан, но карточку загрузить не удалось. Проверь результат ещё раз.",
          "Заказ булдырылған, ләкин карточканы асып булманы. Һөҙөмтәне тағы тикшер.")
        : action === "close" ? appText("Закрытие не подтверждено. Проверь результат или повтори завершение попытки.",
          "Ябыу раҫланманы. Һөҙөмтәне тикшер йәки ынтылышты тамамлауҙы ҡабатла.")
        : appText("Не удалось проверить результат. Повтори проверку.", "Һөҙөмтәне тикшереп булманы. Тикшереүҙе ҡабатла."));
    } finally {
      if (epoch === recoveryEpoch.current) {
        recovering.current = false;
        if (alive.current && isAccountSession(owner) && current.current.environment === environment) setReading(null);
      }
    }
  }

  const unavailable = !environment || !owner || !consentGeneration(generation);
  const attempt = pending.current;
  const savedResults = recoverable();
  const readable = savedResults.filter(marker => marker.version !== 1).sort((a, b) => a.key.localeCompare(b.key));
  const storedAttempt = attempt && readable.find(marker => marker.key === attempt.marker && attempt.unknown);
  const missing = attempt && !storedAttempt ? missingAttemptMarker() : undefined;
  const lookupMarker = attempt ? storedAttempt ?? missing
    : readable.find(marker => marker.key === selectedMarker) ?? readable[0];
  const legacyMarker = !attempt && savedResults.some(marker => marker.version === 1);
  const canRetry = !!attempt && lookupMarker?.version === 2;
  const activeReport = report?.marker === lookupMarker?.key ? report?.state : null;
  const closed = report?.state === "closed" && !lookupMarker && !blocked;
  const closeDialog = closeReview && lookupMarker?.key === closeReview.key && lookupMarker.version === closeReview.version;
  const activeReview = review && (review.kind === "retry"
    ? canRetry && pending.current === review.attempt && isAccountSession(review.attempt.owner)
    : review.quote.input === scope && isAccountSession(review.quote.owner)) ? review : null;
  const reviewOpen = !!activeReview || !!closeDialog;
  useEffect(() => {
    if (!reviewOpen) return;
    const root = reviewRoot.current;
    const previous = document.activeElement;
    const controls = () => Array.from(root?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? []);
    controls()[0]?.focus();
    const key = (event: KeyboardEvent) => {
      if (event.key !== "Tab") return;
      const buttons = controls(), first = buttons[0], last = buttons.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    root?.addEventListener("keydown", key);
    return () => {
      root?.removeEventListener("keydown", key);
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, [reviewOpen]);
  const data = activeReview?.kind === "retry" ? activeReview.attempt.quote : activeReview?.quote.data;
  const total = data ? kopExactLabel(data.payable_kop) : "";
  const expiredReview = activeReview && activeReview.kind !== "retry" && !taxiConsentFresh(activeReview.quote.stamp);
  const description = data ? appText(
    `${kopExactLabel(data.price_kop)} − скидка ${kopExactLabel(data.promo_discount_kop)}. Итого к подтверждению: ${total}.`,
    `${kopExactLabel(data.price_kop)} − ташлама ${kopExactLabel(data.promo_discount_kop)}. Раҫларға бөтәһе: ${total}.`,
  ) : "";
  return (
    <div className="taxi-consent-action">
      <p className="taxi-consent-note" aria-live="polite" role={unknown || blocked ? "status" : undefined}>
        {unavailable ? appText("Подтверждение суммы сейчас недоступно.", "Сумманы раҫлау әлегә мөмкин түгел.")
          : activeReport === "retired" ? appText("Заказ был создан. Его карточка больше недоступна. По этой попытке второй заказ не появится.",
            "Заказ булдырылған. Уның карточкаһы хәҙер асылмай. Был ынтылыш буйынса икенсе заказ булмаясаҡ.")
          : activeReport === "unverified" ? appText("Сервер закрыл возможность позднего заказа по этой попытке. Неизвестно, был ли заказ создан раньше.",
            "Сервер был ынтылыш буйынса һуңынан заказ биреү мөмкинлеген япты. Заказ элегерәк булдырылғанмы — билдәһеҙ.")
          : closed ? appText("Попытка завершена. Заказ не был создан.", "Ынтылыш тамамланды. Заказ булдырылманы.")
          : activeReport === "lookupUnknown" ? appText("Сервер пока не подтвердил заказ. Можно проверить ещё раз или завершить попытку.",
            "Сервер заказды әлегә раҫламаны. Тағы тикшерергә йәки ынтылышты тамамларға мөмкин.")
          : missing ? appText("Сохранённая отметка изменилась. Проверь результат исходной попытки на сервере.",
            "Һаҡланған билдә үҙгәргән. Тәүге ынтылыштың һөҙөмтәһен серверҙа тикшер.")
          : lookupMarker?.version === 3 ? appText("Завершение попытки требует проверки. Новый заказ пока недоступен.",
            "Ынтылыштың тамамланыуын тикшерергә кәрәк. Әлегә яңы заказ биреп булмай.")
          : lookupMarker ? appText("Результат подтверждения неизвестен. Проверь заказ или заверши попытку.",
            "Раҫлау һөҙөмтәһе билдәһеҙ. Заказды тикшер йәки ынтылышты тамамла.")
          : legacyMarker ? appText("У прошлой попытки нет серверного ключа. Проверь поездки перед новым заказом.",
            "Элекке ынтылыштың сервер асҡысы юҡ. Яңы заказ бирер алдынан сәфәрҙәрҙе тикшер.")
          : unknown || blocked ? appText("Результат подтверждения неизвестен. Проверь поездки.",
            "Раҫлау һөҙөмтәһе билдәһеҙ. Сәфәрҙәрҙе тикшер.")
          : usable ? appText(`Итого ${kopExactLabel(usable.data.payable_kop)}${fresh ? "" : " · срок расчёта истёк"}`,
            `Бөтәһе ${kopExactLabel(usable.data.payable_kop)}${fresh ? "" : " · иҫәп ваҡыты үтте"}`) : ""}
      </p>
      {error && <p className="auth__error" role="alert">{error}</p>}
      {!attempt && readable.length > 1 && <label className="taxi-consent-select">
        {appText("Попытка заказа", "Заказ биреү ынтылышы")}
        <select value={lookupMarker?.key} disabled={!!reading || busy} onChange={event => {
          setSelectedMarker(event.target.value); setCloseReview(null); setError("");
        }}>
          {readable.map((marker, index) => <option key={marker.key} value={marker.key}>
            {appText(`Попытка ${index + 1}`, `${index + 1}-се ынтылыш`)}
          </option>)}
        </select>
      </label>}
      {lookupMarker && <>
        <button type="button" className="btn-soft submit-btn" disabled={unavailable || !!reading || busy}
          onClick={() => void recover(lookupMarker, "lookup")}>
          {reading === "lookup" ? appText("Проверяем результат…", "Һөҙөмтәне тикшерәбеҙ…")
            : appText("Проверить результат заказа", "Заказ һөҙөмтәһен тикшереү")}
        </button>
        <button type="button" className="btn-soft submit-btn" disabled={unavailable || !!reading || busy}
          onClick={() => setCloseReview(lookupMarker)}>
          {reading === "close" ? appText("Завершаем попытку…", "Ынтылышты тамамлайбыҙ…")
            : appText("Завершить попытку", "Ынтылышты тамамлау")}
        </button>
      </>}
      {(lookupMarker || legacyMarker || unknown || blocked) && !unavailable &&
        <Link className="taxi-consent-history" to="/my-taxi">{appText("Мои поездки на такси", "Таксиҙағы сәфәрҙәрем")}</Link>}
      {(busy || canRetry || (!attempt && !blocked)) && <button type="button" className="btn-primary submit-btn taxi-order-footer__cta" onClick={primary}
        disabled={unavailable || busy || !!reading || (!attempt && (loading || !body))}>
        {busy ? appText("Подтверждаем…", "Раҫлайбыҙ…")
          : attempt ? appText("Повторить подтверждение", "Раҫлауҙы ҡабатлау")
          : loading ? appText("Получаем сумму…", "Сумманы алабыҙ…")
          : closed ? appText("Рассчитать новую цену", "Хаҡты яңынан иҫәпләү")
          : fresh && usable ? appText(`Заказать за ${kopExactLabel(usable.data.payable_kop)}`, `${kopExactLabel(usable.data.payable_kop)} — заказ итеү`)
          : appText("Обновить сумму", "Сумманы яңыртыу")}
      </button>}
      {closeDialog && createPortal(<div ref={reviewRoot}><AlertDialog
        title={appText("Завершить попытку заказа?", "Заказ биреү ынтылышын тамамларғамы?")}
        text={appText("Если заказ уже создан, откроется его карточка. Если заказ ещё не создан, эта попытка больше не вызовет машину.",
          "Заказ булдырылған булһа, уның карточкаһы асыласаҡ. Заказ әлегә булдырылмаған булһа, был ынтылыш буйынса машина саҡырылмаясаҡ.")}
        descriptionId="taxi-consent-close-description" onClose={() => setCloseReview(null)}
        dismiss={{ label: appText("Назад", "Артҡа"), onClick: () => setCloseReview(null) }}
        confirm={{ label: appText("Завершить попытку", "Ынтылышты тамамлау"), onClick: () => void recover(closeReview, "close") }}
      /></div>, document.body)}
      {activeReview && data && createPortal(<div ref={reviewRoot}><AlertDialog
        title={activeReview.kind === "retry" ? appText(`Повторить подтверждение ${total}`, `${total} раҫлауҙы ҡабатлау`)
          : activeReview.kind === "updated" ? appText("Сумма обновлена", "Сумма яңыртылды") : appText("Подтверди итог", "Йомғаҡты раҫла")}
        descriptionId="taxi-consent-description"
        text={activeReview.kind === "retry" ? appText("Результат предыдущего запроса неизвестен. Повторяем то же подтверждение. ",
          "Элекке һорауҙың һөҙөмтәһе билдәһеҙ. Шул уҡ раҫлауҙы ҡабатлайбыҙ. ") + description : description + (expiredReview ? appText(" Срок истёк: сначала обновим сумму.", " Ваҡыт үтте: тәүҙә сумманы яңыртабыҙ.") : "")}
        onClose={() => { if (!sending.current) setReview(null); }}
        dismiss={{ label: appText("Назад", "Артҡа"), onClick: () => setReview(null), disabled: busy }}
        confirm={{ label: expiredReview ? appText("Обновить сумму", "Сумманы яңыртыу") : appText(`Подтвердить ${total}`, `${total} раҫлау`), busy,
          onClick: () => { if (activeReview.kind === "retry") void send(activeReview.attempt); else void accept(activeReview.quote); } }}
      /></div>, document.body)}
    </div>
  );
}

export function TaxiConsentSummary({ quote }: { quote: TaxiConsentQuote }) {
  const { appText } = useLang();
  return <div className="price-why taxi-consent-summary">
    <strong>{appText(`Итого ${kopExactLabel(quote.payable_kop)}`, `Бөтәһе ${kopExactLabel(quote.payable_kop)}`)}</strong>
    <div>{appText(`Поездка ${kopExactLabel(quote.breakdown.ride_price_kop)}`, `Сәфәр ${kopExactLabel(quote.breakdown.ride_price_kop)}`)}</div>
    {quote.breakdown.pickup_fee_kop > 0 && <div>{appText(`Подача ${kopExactLabel(quote.breakdown.pickup_fee_kop)}`, `Машина килеү ${kopExactLabel(quote.breakdown.pickup_fee_kop)}`)}</div>}
    {quote.breakdown.options_fee_kop > 0 && <div>{appText(`Опции ${kopExactLabel(quote.breakdown.options_fee_kop)}`, `Өҫтәмәләр ${kopExactLabel(quote.breakdown.options_fee_kop)}`)}</div>}
    {quote.breakdown.weather_fee_kop > 0 && <div>{appText(`Зимняя дорога ${kopExactLabel(quote.breakdown.weather_fee_kop)}`, `Ҡышҡы юл ${kopExactLabel(quote.breakdown.weather_fee_kop)}`)}</div>}
    {quote.promo_discount_kop > 0 && <div>{appText(`Скидка −${kopExactLabel(quote.promo_discount_kop)}`, `Ташлама −${kopExactLabel(quote.promo_discount_kop)}`)}</div>}
  </div>;
}
