import { useEffect, useRef, useState } from "react";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { getSessionGeneration } from "../api/client";
import { BOOKING_RETRY_WINDOW } from "../api/bookingCommands";
import type { BookingStatus } from "../api/bookings";
import {
  checkPassengerTripStatus, PassengerStatusProblem, repeatPassengerTripStatus, submitPassengerTripStatus,
  type PassengerStatusOutcome,
} from "../api/passengerTripStatus";
import {
  bookingCommandOwner, isCurrentBookingOwner, passengerTripStatuses,
  type BookingCommandOwner, type LegacyPassengerTripStatus, type PassengerTripStatusCommand,
} from "../utils/bookingCommandStore";
import { legacyTripStatuses, reconcileLegacyTripStatuses } from "../utils/outbox";

export function PassengerTripStatus({ bookingId, bookingStatus, forAnother, onChanged }: {
  bookingId: number;
  bookingStatus: BookingStatus;
  forAnother: boolean;
  onChanged: (status: BookingStatus) => void | Promise<void>;
}) {
  const { user } = useAuth();
  const generation = getSessionGeneration();
  const owner = bookingCommandOwner(user, generation);
  return owner && <StatusCard key={JSON.stringify([owner.namespace, generation, bookingId])}
    owner={owner} bookingId={bookingId} bookingStatus={bookingStatus} forAnother={forAnother} onChanged={onChanged} />;
}

function StatusCard({ owner, bookingId, bookingStatus, forAnother, onChanged }: {
  owner: BookingCommandOwner;
  bookingId: number;
  bookingStatus: BookingStatus;
  forAnother: boolean;
  onChanged: (status: BookingStatus) => void | Promise<void>;
}) {
  const { appText } = useLang();
  const [commands, setCommands] = useState<{ saved?: PassengerTripStatusCommand; legacy: LegacyPassengerTripStatus[] }>();
  const [loadingFailed, setLoadingFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const [loadAttempt, setLoadAttempt] = useState(0);
  const lifetime = useRef<AbortController>();
  const current = (signal: AbortSignal) => !signal.aborted && isCurrentBookingOwner(owner);

  async function load(signal: AbortSignal) {
    try {
      const [saved, legacy] = await Promise.all([
        passengerTripStatuses.read(owner), legacyTripStatuses(owner, bookingId, signal),
      ]);
      if (!current(signal)) return;
      setCommands({ saved: saved.find(row => row.bookingId === bookingId && row.state === "unknown"), legacy });
      setLoadingFailed(false);
    } catch {
      if (!current(signal)) return;
      setCommands(undefined);
      setLoadingFailed(true);
    }
  }

  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    void load(controller.signal);
    return () => controller.abort();
  }, [owner.namespace, owner.generation, bookingId, loadAttempt]);

  function outcomeNote(result: PassengerStatusOutcome): string {
    switch (result.state) {
      case "applied": return appText("Сохранено", "Һаҡланды");
      case "gone": return appText("Результат больше недоступен.", "Һөҙөмтә бүтән асыҡ түгел.");
      case "unseen": return appText("Результат пока неизвестен. Можно проверить или повторить запрос.",
        "Һөҙөмтә әлегә билдәһеҙ. Һорауҙы тикшерергә йәки ҡабатларға була.");
      case "closed_no_effect": return appText("Запрос закрыт без выполнения.", "Һорау башҡарылмайынса ябылды.");
      case "rejected": return result.refusal?.message ||
        appText("Сервер отклонил отметку.", "Сервер билдәне кире ҡаҡты.");
    }
  }

  const running = useRef(false);
  async function run(action: (signal: AbortSignal) => Promise<void>) {
    const signal = lifetime.current?.signal;
    if (!signal || running.current || !current(signal)) return;
    running.current = true;
    setBusy(true);
    setNote("");
    try {
      await action(signal);
    } catch (error) {
      if (!current(signal)) return;
      const reason = error instanceof PassengerStatusProblem ? error.reason : null;
      setNote(reason === "storage"
        ? appText("Не удалось сохранить отметку. Попробуй ещё раз.", "Билдәне һаҡлап булманы. Тағы ҡабатла.")
        : reason === "expired"
          ? appText("Срок повтора истёк. Проверь или закрой запрос.", "Ҡабатлау ваҡыты үтте. Һорауҙы тикшер йәки яп.")
          : appText("Не удалось подтвердить результат. Проверь отметку.", "Һөҙөмтәне раҫлап булманы. Билдәне тикшер."));
    } finally {
      if (current(signal)) {
        await load(signal);
        if (current(signal)) setBusy(false);
      }
      running.current = false;
    }
  }

  function perform(action: (signal: AbortSignal) => Promise<PassengerStatusOutcome>) {
    return run(async signal => {
      const result = await action(signal);
      if (!current(signal)) return;
      setNote(outcomeNote(result));
      if (result.state === "applied" && current(signal)) await Promise.resolve(onChanged(result.bookingStatus)).catch(() => {});
    });
  }

  const saved = commands?.saved;
  const legacy = !!commands?.legacy.length;
  const canMark = bookingStatus === "onboard" && commands && !saved && !legacy;
  const age = saved ? Date.now() - saved.createdAt : -1;
  const loading = bookingStatus === "onboard" && !commands && !loadingFailed;
  if (!canMark && !saved && !legacy && !loadingFailed && !loading && !note) return null;

  return (
    <section className="act-card" aria-label={appText("Отметки поездки", "Сәфәр билдәләре")}>
      {loading && <p role="status">{appText("Проверяем сохранённые отметки…", "Һаҡланған билдәләрҙе тикшерәбеҙ…")}</p>}
      {canMark && (
        <>
          <p className="act-card__text">{appText("Как идёт поездка?", "Сәфәр нисек бара?")}</p>
          <div className="chips">
            <button type="button" className="chip" disabled={busy}
              onClick={() => perform(signal => submitPassengerTripStatus(owner, bookingId, "arrived", signal))}>
              {forAnother ? appText("Пассажир доехал", "Юлсы барып етте") : appText("Доехала", "Барып еттем")}
            </button>
            <button type="button" className="chip" disabled={busy}
              onClick={() => perform(signal => submitPassengerTripStatus(owner, bookingId, "done", signal))}>
              {appText("Поездка завершена", "Сәфәр тамамланды")}
            </button>
          </div>
        </>
      )}
      {loadingFailed && (
        <p role="status">
          {appText("Не удалось прочитать сохранённые отметки.", "Һаҡланған билдәләрҙе уҡып булманы.")}{" "}
          <button type="button" className="btn-ghost" onClick={() => setLoadAttempt(n => n + 1)}>
            {appText("Повторить проверку", "Тикшереүҙе ҡабатлау")}
          </button>
        </p>
      )}
      {saved && (
        <>
          <p>{saved.stage === "arrived" ? appText("Отметка о прибытии", "Барып етеү билдәһе")
            : appText("Отметка о завершении", "Тамамлау билдәһе")}</p>
          <div className="act-card__actions">
            <button type="button" className="btn-ghost" disabled={busy}
              onClick={() => perform(signal => checkPassengerTripStatus(owner, saved, false, signal))}>
              {appText("Проверить результат", "Һөҙөмтәне тикшереү")}
            </button>
            {age >= 0 && age < BOOKING_RETRY_WINDOW && (
              <button type="button" className="btn-ghost" disabled={busy}
                onClick={() => perform(signal => repeatPassengerTripStatus(owner, saved, signal))}>
                {appText("Повторить отправку", "Ебәреүҙе ҡабатлау")}
              </button>
            )}
            <button type="button" className="btn-ghost" disabled={busy}
              onClick={() => perform(signal => checkPassengerTripStatus(owner, saved, true, signal))}>
              {appText("Закрыть запрос", "Һорауҙы ябыу")}
            </button>
          </div>
        </>
      )}
      {legacy && (
        <>
          <p>{appText("Есть отметка из прежней версии. Она могла сохраниться.", "Элекке версиянан билдә бар. Ул һаҡланған булыуы мөмкин.")}</p>
          <div className="act-card__actions">
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => run(async signal => {
              const left = await reconcileLegacyTripStatuses(owner, bookingId, signal);
              if (current(signal)) setNote(left.length
                ? appText("Старый результат пока не подтверждён.", "Элекке һөҙөмтә әлегә раҫланмаған.")
                : appText("Статус поездки проверен.", "Сәфәр торошо тикшерелде."));
            })}>{appText("Проверить старую отметку", "Элекке билдәне тикшереү")}</button>
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => run(async signal => {
              await reconcileLegacyTripStatuses(owner, bookingId, signal, true);
              if (current(signal)) setNote(appText("Старая запись снята с очереди.", "Элекке яҙма сираттан алынды."));
            })}>{appText("Снять с очереди", "Сираттан алыу")}</button>
          </div>
        </>
      )}
      {note && <p role="status">{note}</p>}
    </section>
  );
}
