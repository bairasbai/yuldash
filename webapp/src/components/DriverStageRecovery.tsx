import { BOOKING_RETRY_WINDOW } from "../api/bookingCommands";
import { useLang } from "../i18n/lang";
import { type DriverStageControls, useDriverStageNotice } from "../utils/useDriverStages";
import type { TripState } from "../api/bookings";

export function DriverStageRecovery({ stages, onChanged }: {
  stages: DriverStageControls;
  onChanged: (trip: TripState | null) => void | Promise<void>;
}) {
  const { appText } = useLang();
  const message = useDriverStageNotice(stages.notice);
  return (
    <>
      {stages.loadFailed && (
        <p role="status">
          {appText("Не удалось прочитать сохранённые отметки.", "Һаҡланған билдәләрҙе уҡып булманы.")}{" "}
          <button type="button" className="btn-ghost" onClick={stages.reload}>
            {appText("Повторить проверку", "Тикшереүҙе ҡабатлау")}
          </button>
        </p>
      )}
      {stages.pending.map(command => {
        const age = Date.now() - command.createdAt;
        return (
          <section key={command.key} className="act-card">
            <p>{command.stage === "boarded"
              ? appText("Отметка о посадке", "Ултырыу билдәһе")
              : appText("Отметка о завершении", "Тамамлау билдәһе")} №{command.bookingId}</p>
            <div className="act-card__actions">
              <button type="button" className="btn-ghost" disabled={stages.busy} onClick={async () => {
                const result = await stages.check(command, false);
                if (result?.state === "applied") await onChanged(result.trip);
              }}>{appText("Проверить", "Тикшереү")}</button>
              {age >= 0 && age < BOOKING_RETRY_WINDOW && (
                <button type="button" className="btn-ghost" disabled={stages.busy} onClick={async () => {
                  const result = await stages.repeat(command);
                  if (result?.state === "applied") await onChanged(result.trip);
                }}>{appText("Повторить отправку", "Ебәреүҙе ҡабатлау")}</button>
              )}
              <button type="button" className="btn-ghost" disabled={stages.busy} onClick={async () => {
                const result = await stages.check(command, true);
                if (result?.state === "applied") await onChanged(result.trip);
              }}>{appText("Закрыть запрос", "Һорауҙы ябыу")}</button>
            </div>
          </section>
        );
      })}
      {message && <p role="status">{message}</p>}
    </>
  );
}
