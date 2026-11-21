import { useEffect, useRef, useState } from "react";
import {
  CARD_CONFIRM_RETRY_MS, CARD_CONFIRM_SCOPE, readCardCheckoutLink, readCardCheckoutOffer, readCardConfirmationResult,
  sendCardConfirmation, type CardCheckoutOffer,
} from "../api/rideshareCardCheckout";
import { fetchRideshareCardPayment } from "../api/rideshareCardPayment";
import {
  isCurrentBookingOwner, readCardConfirmation, releaseCardConfirmation, reserveCardConfirmation,
  type BookingCommandOwner, type SavedCardConfirmation,
} from "./bookingCommandStore";
import { commandFingerprint } from "./commandFingerprint";
import type { CardPaymentReader, PaymentRead } from "./useRideshareCardPayment";

type CheckoutError = "read" | "storage" | "link" | "confirm" | null;
type CheckoutView = {
  checked: boolean;
  offer: CardCheckoutOffer | null;
  selected: CardCheckoutOffer | null;
  pending: SavedCardConfirmation | null;
  confirmationState: "unseen" | "applied" | "closed_no_effect" | null;
  error: CheckoutError;
  busy: boolean;
};

const empty: CheckoutView = {
  checked: false, offer: null, selected: null, pending: null, confirmationState: null, error: null, busy: false,
};
type Session = { key: string; signal: AbortSignal; busy: boolean };

function eligibleOffer(view: CheckoutView, bank: PaymentRead): CardCheckoutOffer | null {
  const status = bank.payment?.payment_status;
  if (!view.checked || view.pending || view.error === "storage" || bank.phase !== "idle" || !status) return null;
  const offer = view.offer;
  if (offer?.action === "retry") {
    return status === "canceled" && bank.payment?.payment_attempt_id === offer.confirmation.retry_after_payment_id ? offer : null;
  }
  if (offer?.action === "resume") return status === "queued" ? offer : null;
  return status === "unavailable" || status === "not_started" || status === "canceled" ? offer : null;
}

export function useRideshareCardCheckout(
  bookingId: number | null,
  owner: BookingCommandOwner | null,
  canAct: () => boolean,
  bank: CardPaymentReader,
) {
  const key = owner && bookingId !== null ? JSON.stringify([owner.namespace, owner.generation, bookingId]) : "";
  const input = useRef({ key, owner, bookingId, canAct, bank });
  input.current = { key, owner, bookingId, canAct, bank };
  const lifetime = useRef<Session | null>(null);
  const [owned, setOwned] = useState<{ key: string; view: CheckoutView }>({ key: "", view: empty });
  const view = owned.key === key ? owned.view : empty;

  function current(session: Session) {
    const now = input.current;
    return lifetime.current === session && !session.signal.aborted && key === session.key && now.key === session.key &&
      now.owner !== null && isCurrentBookingOwner(now.owner) && now.canAct();
  }

  function update(session: Session, patch: Partial<CheckoutView>) {
    if (current(session)) setOwned(prior => ({
      key: session.key, view: { ...(prior.key === session.key ? prior.view : empty), ...patch },
    }));
  }

  async function resolve(session: Session, actor: BookingCommandOwner, saved: SavedCardConfirmation, close = false) {
    const outcome = await readCardConfirmationResult(actor, saved, session.signal, close);
    update(session, { confirmationState: outcome });
    if (outcome === "unseen" || !current(session)) return saved;
    const observed = await fetchRideshareCardPayment(saved.bookingId, actor.generation, session.signal);
    if (!current(session)) return saved;
    if (outcome === "applied") {
      const prior = JSON.parse(saved.wireBody).retry_after_payment_id;
      if (observed.payment_status === "unavailable" || observed.payment_status === "not_started" ||
          (prior !== null && (observed.payment_attempt_id == null || observed.payment_attempt_id === prior))) return saved;
    }
    if (!input.current.bank.acceptObserved(observed)) return saved;
    if (!await releaseCardConfirmation(actor, saved)) return readCardConfirmation(actor, saved.bookingId);
    return undefined;
  }

  async function read(session: Session, actor: BookingCommandOwner, id: number) {
    let saved: SavedCardConfirmation | undefined;
    try {
      saved = await readCardConfirmation(actor, id);
    } catch {
      update(session, { error: "storage", offer: null });
      return;
    }
    if (!current(session)) return;
    update(session, { checked: true, pending: saved ?? null, offer: null, error: null, confirmationState: null });
    try {
      if (saved) saved = await resolve(session, actor, saved);
      if (current(session)) update(session, {
        pending: saved ?? null,
        offer: saved ? null : await readCardCheckoutOffer(id, actor, session.signal),
      });
    } catch {
      update(session, { error: "read" });
    }
  }

  function run(action: (session: Session, actor: BookingCommandOwner, id: number) => Promise<void>, error: CheckoutError) {
    const session = lifetime.current;
    const { owner: actor, bookingId: id } = input.current;
    if (!session || !actor || id === null || session.busy || !current(session)) return;
    session.busy = true;
    update(session, { busy: true, error: null });
    void action(session, actor, id).catch(() => update(session, { error })).finally(() => {
      session.busy = false;
      update(session, { busy: false });
    });
  }

  useEffect(() => {
    if (!key) return;
    const controller = new AbortController();
    lifetime.current = { key, signal: controller.signal, busy: false };
    const onReturn = () => {
      if (globalThis.document?.visibilityState !== "hidden") run(read, "read");
    };
    onReturn();
    globalThis.window?.addEventListener?.("focus", onReturn);
    globalThis.document?.addEventListener?.("visibilitychange", onReturn);
    return () => {
      controller.abort();
      globalThis.window?.removeEventListener?.("focus", onReturn);
      globalThis.document?.removeEventListener?.("visibilitychange", onReturn);
    };
  }, [key]);

  function confirm() {
    const selected = view.selected;
    if (!selected) return;
    run(async (session, actor, id) => {
      update(session, { selected: null, offer: null });
      const fresh = await readCardCheckoutOffer(id, actor, session.signal);
      if (!current(session)) return;
      if (JSON.stringify(fresh) !== JSON.stringify(selected)) {
        update(session, { offer: fresh, error: "confirm" });
        input.current.bank.refresh();
        return;
      }
      const snapshot = await commandFingerprint(CARD_CONFIRM_SCOPE, id, selected.confirmation);
      if (!snapshot.wireBody || !current(session)) return;
      const command = await reserveCardConfirmation(actor, id, snapshot.payloadHash, snapshot.wireBody);
      if (!current(session)) return;
      if (!command) {
        await read(session, actor, id);
        return;
      }
      update(session, { pending: command });
      await submit(session, actor, id, command);
    }, "confirm");
  }

  async function submit(session: Session, actor: BookingCommandOwner, id: number, command: SavedCardConfirmation) {
    try {
      await sendCardConfirmation(actor, command, session.signal);
    } catch {
      // The pending command is already durable. Only its receipt can resolve an uncertain write.
    }
    if (current(session)) {
      input.current.bank.refresh();
      await read(session, actor, id);
    }
  }

  const offer = eligibleOffer(view, bank);
  const payment = bank.payment?.payment_status === "waiting_bank" && bank.phase === "idle"
    ? bank.payment.payment_attempt_id : null;
  const age = view.pending ? Date.now() - view.pending.createdAt : -1;
  const canRepeat = view.confirmationState === "unseen" && age >= 0 && age < CARD_CONFIRM_RETRY_MS;
  return {
    view, offer, canOpenBank: Boolean(key && payment), canRepeat,
    holdsDirect: Boolean(key && (!view.checked || view.busy || view.selected || view.pending || view.error === "storage")),
    refresh: () => { bank.refresh(); run(read, "read"); },
    choose: () => { if (offer && lifetime.current) update(lifetime.current, { selected: offer }); },
    dismiss: () => { if (lifetime.current) update(lifetime.current, { selected: null }); },
    confirm,
    repeat: () => {
      const pending = view.pending;
      if (!pending || !canRepeat) return;
      run(async (session, actor, id) => {
        if ((await readCardConfirmation(actor, id))?.key === pending.key && current(session)) {
          const result = await readCardConfirmationResult(actor, pending, session.signal);
          if (result === "unseen" && current(session)) await submit(session, actor, id, pending);
          else if (current(session)) await read(session, actor, id);
        }
      }, "confirm");
    },
    close: () => {
      const pending = view.pending;
      if (!pending) return;
      run(async (session, actor, id) => {
        if ((await readCardConfirmation(actor, id))?.key === pending.key && current(session)) {
          await resolve(session, actor, pending, true);
          if (current(session)) {
            input.current.bank.refresh();
            await read(session, actor, id);
          }
        }
      }, "read");
    },
    openBank: () => {
      if (!payment) return;
      run(async (session, actor, id) => {
        const url = await readCardCheckoutLink(id, payment, actor, session.signal);
        const latest = input.current.bank.payment;
        if (!current(session) || latest?.payment_attempt_id !== payment || latest.payment_status !== "waiting_bank") return;
        if (!url) {
          update(session, { error: "link" });
          input.current.bank.refresh();
          return;
        }
        globalThis.window.location.assign(url);
      }, "link");
    },
  };
}
