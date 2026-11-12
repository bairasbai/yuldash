import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { getSessionGeneration } from "../api/client";
import { reconcileBookingCommand } from "../api/bookingCommands";
import { bookingRefusalMessage } from "../utils/bookingCommandLabels";
import { bookingCommandOwner, isCurrentBookingOwner, readBookingCommand, readPendingBookingCommands,
  type BookingCommand, type BookingCommandOwner } from "../utils/bookingCommandStore";

export function BookingCommandRecovery({ rideId }: { rideId?: number }) {
  const { user, isAuthed } = useAuth();
  const generation = getSessionGeneration();
  const owner = isAuthed ? bookingCommandOwner(user, generation) : null;
  return owner ? <Recovery key={JSON.stringify([owner.namespace, generation, rideId])} owner={owner} rideId={rideId} /> : null;
}

async function readRequests(owner: BookingCommandOwner, rideId?: number): Promise<BookingCommand[]> {
  if (rideId === undefined) return readPendingBookingCommands(owner);
  const row = await readBookingCommand(owner, rideId);
  return row?.state === "unknown" ? [row] : [];
}

function Recovery({ owner, rideId }: { owner: BookingCommandOwner; rideId?: number }) {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [rows, setRows] = useState<BookingCommand[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const action = useRef<AbortController | null>(null);

  useEffect(() => {
    let live = true;
    void readRequests(owner, rideId).then(result => {
      if (live && isCurrentBookingOwner(owner)) setRows(result);
    }).catch(() => {});
    return () => { live = false; action.current?.abort(); };
  }, [owner.namespace, owner.generation, rideId]);

  async function check(command: BookingCommand, close: boolean) {
    if (action.current || !isCurrentBookingOwner(owner)) return;
    const ac = new AbortController();
    action.current = ac;
    setBusy(true);
    setMessage("");
    const active = () => !ac.signal.aborted && isCurrentBookingOwner(owner);
    try {
      const result = await reconcileBookingCommand(owner, command, close, ac.signal);
      if (!active()) return;
      if (result.state === "unseen") {
        setMessage(appText("Результат пока неизвестен. Можно проверить снова или закрыть запрос.",
          "Һөҙөмтә әлегә билдәһеҙ. Ҡабат тикшерергә йәки һорауҙы ябырға була."));
        return;
      }
      setRows(current => current.filter(row => row.key !== command.key));
      if (result.booking) {
        navigate(`${result.booking.status === "pending" ? "/booking" : "/trip"}/${result.booking.id}`);
      } else {
        setMessage(result.state === "closed_no_effect"
          ? appText("Запрос закрыт. Бронь по нему не создана.", "Һорау ябылды. Уның буйынса бронь яһалманы.")
          : result.state === "rejected"
            ? bookingRefusalMessage(appText, result.code)
            : appText("Результат бронирования больше недоступен.", "Бронлау һөҙөмтәһе бүтән асыҡ түгел."));
      }
    } catch {
      if (active()) {
        const current = await readRequests(owner, rideId).catch(() => null);
        if (active()) {
          if (current) setRows(current);
          setMessage(current && !current.some(row => row.key === command.key)
            ? appText("Запроса больше нет в списке.", "Һорау исемлектә бүтән юҡ.")
            : appText("Не удалось подтвердить результат. Попробуй снова.", "Һөҙөмтәне раҫлап булманы. Ҡабатлап ҡара."));
        }
      }
    } finally {
      if (action.current === ac) action.current = null;
      if (active()) setBusy(false);
    }
  }

  if (rows.length === 0 && !message) return null;
  return (
    <section className="card">
      <h2>{appText("Незавершённые запросы на бронь", "Тамамланмаған бронлау һорауҙары")}</h2>
      {rows.length > 0 && (
        <p>{appText("Если бронь уже создана, покажем её.", "Бронь яһалған булһа, уны күрһәтербеҙ.")}</p>
      )}
      {rows.map(row => (
        <div className="list-row" key={row.key}>
          <span>{appText(`Поездка №${row.rideId}`, `Сәфәр №${row.rideId}`)}</span>
          <button type="button" className="btn-ghost" disabled={busy} onClick={() => check(row, false)}>
            {appText("Проверить", "Тикшереү")}
          </button>
          <button type="button" className="btn-ghost" disabled={busy} onClick={() => check(row, true)}>
            {appText("Закрыть запрос", "Һорауҙы ябыу")}
          </button>
        </div>
      ))}
      {message && <p role="status">{message}</p>}
    </section>
  );
}
