import { useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  proposeMeetingPoint, withdrawMeetingPoint,
  type BookingDetails, type MeetingPointInput,
} from "../api/bookings";
import { MeetingPointPicker } from "./MeetingPointPicker";

/** RS-03 Б: блок «место» на экране поездки пассажира (сайт).
 *  Есть предложение — «ждём ответа» + «Отозвать»; pending — сразу меняется;
 *  confirmed/onboard — предложение водителю (прежняя действует, пока тот не ответит). */
export function MeetingActions({ details, onChanged }: { details: BookingDetails; onChanged: () => void }) {
  const { appText } = useLang();
  const sending = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<"pickup" | "dropoff" | null>(null);
  const [editValue, setEditValue] = useState<MeetingPointInput | null>(null);

  if (details.role !== "passenger") return null;
  const st = details.status;
  if (st !== "pending" && st !== "confirmed" && st !== "onboard") return null;
  const proposal = details.meeting_proposal;

  const run = async (fn: () => Promise<unknown>, onSaved?: () => void) => {
    if (sending.current) return;
    sending.current = true;
    setBusy(true); setError(null);
    try {
      await fn();
      onSaved?.();
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : appText("Не получилось изменить место. Повторить?", "Урынды үҙгәртеп булманы. Ҡабатлайынмы?"));
      if (e instanceof ApiError && (e as any).code === "meeting_point_stale") onChanged();
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const saveEdit = () => {
    if (!editing) return;
    const kind = editing;
    const point = editValue ?? { clear: true };
    void run(() => proposeMeetingPoint(details.booking_id, kind, point, details.meeting_version ?? 0), () => setEditing(null));
  };

  return (
    <div className="act-card">
      <div className="act-card__title">{appText("Точное место", "Теүәл урын")}</div>
      {proposal ? (
        <>
          <p className="act-card__text">
            {appText("Ждём ответа водителя — пока действует прежнее место.", "Йөрөтөүсөнөң яуабын көтәбеҙ — әлегә иҫке урын ҡала.")}
          </p>
          <button type="button" className="btn-ghost" style={{ minHeight: 48 }} disabled={busy}
            onClick={() => void run(() => withdrawMeetingPoint(details.booking_id, proposal.proposal_id, details.meeting_version ?? 0))}>
            {appText("Отозвать предложение", "Тәҡдимде кире алыу")}
          </button>
        </>
      ) : editing ? (
        <>
          <MeetingPointPicker
            city={editing === "pickup" ? (details.pickup_stop || details.from_city) : (details.dropoff_stop || details.to_city)}
            title={editing === "pickup" ? appText("Где тебя забрать?", "Һине ҡайҙан алырға?") : appText("Где тебя высадить?", "Һине ҡайҙа төшөрөргә?")}
            value={editValue} disabled={busy} onChange={setEditValue} />
          <div style={{ display: "flex", gap: 8 }}>
            <button type="button" className="btn-primary" style={{ minHeight: 48, flex: 1 }} disabled={busy} aria-busy={busy} onClick={saveEdit}>
              {st === "pending" ? appText("Сохранить место", "Урынды һаҡлау") : appText("Предложить водителю", "Йөрөтөүсегә тәҡдим итеү")}
            </button>
            <button type="button" className="btn-ghost" style={{ minHeight: 48 }} disabled={busy} onClick={() => setEditing(null)}>
              {appText("Отмена", "Кире алыу")}
            </button>
          </div>
        </>
      ) : (
        <>
          {(st === "onboard" ? ["dropoff"] as const : ["pickup", "dropoff"] as const).map((kind) => (
            <button key={kind} type="button" className="btn-ghost" style={{ minHeight: 48 }} disabled={busy}
              onClick={() => {
                setEditValue({
                  lat: (kind === "pickup" ? details.meet_lat : details.alight_lat) ?? undefined,
                  lng: (kind === "pickup" ? details.meet_lng : details.alight_lng) ?? undefined,
                  note: (kind === "pickup" ? details.meet_note : details.alight_note) || undefined,
                  landmark_id: (kind === "pickup" ? details.meet_landmark_id : details.alight_landmark_id) ?? undefined,
                });
                setEditing(kind);
              }}>
              {kind === "pickup" ? appText("Изменить место посадки", "Ултырыу урынын үҙгәртеү") : appText("Изменить место высадки", "Төшөү урынын үҙгәртеү")}
            </button>
          ))}
          {(st === "confirmed" || st === "onboard") && (
            <p className="act-card__text">
              {appText("Пока водитель не ответил — действует прежнее место.", "Йөрөтөүсе яуап биргәнсе — иҫке урын ҡала.")}
            </p>
          )}
        </>
      )}
      {busy && <p className="muted" role="status">{appText("Отправляем…", "Ебәрәбеҙ…")}</p>}
      {error && <p className="form-error" role="alert">{error}</p>}
    </div>
  );
}
