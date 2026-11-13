import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";

/** Use only with fresh private booking details or the assigned driver's list. */
export function BookingTraveler({ name, bookedBy, deadline }: {
  name?: string | null; bookedBy?: string; deadline?: number;
}) {
  const { appText } = useLang();
  const [expired, setExpired] = useState<number>();
  useEffect(() => {
    if (deadline === undefined || !name) return;
    const expiresAt = deadline;
    let timer: ReturnType<typeof setTimeout>;
    function update() {
      clearTimeout(timer);
      const remaining = expiresAt - performance.now();
      if (remaining <= 0) setExpired(expiresAt);
      else timer = setTimeout(update, Math.min(remaining, 2_147_483_647));
    }
    update();
    document.addEventListener("visibilitychange", update);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", update);
    };
  }, [deadline, name]);
  if (!name || deadline === undefined || expired === deadline || performance.now() >= deadline) return null;
  return <p className="list-row__sub" style={{ overflowWrap: "anywhere", whiteSpace: "normal" }}>
    {appText("Едет", "Юлсы")} <strong>{name}</strong>
    {bookedBy && <> · {appText("бронировал", "бронланы")} {bookedBy}</>}
  </p>;
}
