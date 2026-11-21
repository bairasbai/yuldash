import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { getSessionGeneration } from "../api/client";
import { fetchReturnTemplate } from "../api/driver";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import {
  bookingCommandOwner, isCurrentBookingOwner, readPublicationSlot, releaseRidePublication,
} from "../utils/bookingCommandStore";
import { readDraft, writeDraft } from "../utils/formDraft";
import { RIDE_DRAFT, returnRideDraft } from "../utils/rideTemplate";
import { IconSwap } from "./Icons";

type Failure = "previous" | "load" | "draft";

export function useReturnRideAction() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const request = useRef<AbortController | null>(null);
  const [busy, setBusy] = useState<number>();
  const [failure, setFailure] = useState<{ rideId: number; kind: Failure }>();
  const generation = getSessionGeneration();

  useEffect(() => {
    setBusy(undefined);
    setFailure(undefined);
    return () => { request.current?.abort(); request.current = null; };
  }, [generation]);

  async function open(rideId: number) {
    const owner = bookingCommandOwner(user, generation);
    if (request.current || !owner || !isCurrentBookingOwner(owner)) return;
    const active = new AbortController();
    request.current = active;
    const current = () => !active.signal.aborted && isCurrentBookingOwner(owner);
    setBusy(rideId);
    setFailure(undefined);
    try {
      const before = await readPublicationSlot(owner);
      if (!current()) return;
      if (before.command?.state === "unknown") {
        setFailure({ rideId, kind: "previous" });
        return;
      }
      const template = await fetchReturnTemplate(rideId, active.signal);
      const latest = await readPublicationSlot(owner);
      if (!current()) return;
      if (latest.revision !== before.revision || latest.command?.state === "unknown" ||
          (latest.command && !await releaseRidePublication(owner, latest.command))) {
        if (current()) setFailure({ rideId, kind: "previous" });
        return;
      }
      if (!current()) return;
      const nextDraft = returnRideDraft(template);
      writeDraft(RIDE_DRAFT, nextDraft);
      if (JSON.stringify(readDraft(RIDE_DRAFT)) !== JSON.stringify(nextDraft)) {
        setFailure({ rideId, kind: "draft" });
        return;
      }
      navigate("/create-ride", { replace: pathname === "/create-ride" });
    } catch {
      if (current()) setFailure({ rideId, kind: "load" });
    } finally {
      if (request.current === active) {
        request.current = null;
        if (current()) setBusy(undefined);
      }
    }
  }

  return { busy, failure, open, review: () => navigate("/create-ride") };
}

export function ReturnRideAction({ rideId, controls, published, className = "btn-soft" }: {
  rideId: number;
  controls: ReturnType<typeof useReturnRideAction>;
  published?: boolean;
  className?: string;
}) {
  const { appText } = useLang();
  const failure = controls.failure?.rideId === rideId ? controls.failure.kind : undefined;
  const label = published
    ? appText("Опубликовать обратно", "Кире яҡҡа сәфәр баҫтырырға")
    : appText("Обратно", "Кире яҡҡа");
  return (
    <div>
      <button type="button" className={className} disabled={controls.busy !== undefined}
        aria-busy={controls.busy === rideId} onClick={() => void controls.open(rideId)}>
        <IconSwap size={18} />
        {controls.busy === rideId ? appText("Готовим черновик…", "Ҡаралама әҙерләйбеҙ…")
          : failure && failure !== "previous" ? appText("Повторить", "Ҡабатларға") : label}
      </button>
      {failure && <p role="status">
        {failure === "previous"
          ? appText("Сначала проверь прошлую публикацию.", "Тәүҙә алдағы баҫтырыуҙы тикшер.")
          : failure === "draft"
            ? appText("Не удалось сохранить черновик.", "Ҡараламаны һаҡлап булманы.")
            : appText("Не удалось загрузить обратный маршрут.", "Кире маршрутты тейәп булманы.")}
      </p>}
      {failure === "previous" && <button type="button" className="btn-soft" onClick={controls.review}>
        {appText("Проверить публикацию", "Баҫтырыуҙы тикшерергә")}
      </button>}
    </div>
  );
}
