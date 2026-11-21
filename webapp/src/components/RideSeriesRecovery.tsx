import { useState } from "react";
import { canRepeatRideSeries } from "../api/rideSeries";
import { useLang } from "../i18n/lang";
import { minDateTimeNow, toDateTimeInput } from "../utils/dateInput";
import type { RideSeriesIntent } from "../utils/rideSeriesIntent";
import { serverDate } from "../utils/serverTime";
import RideSeriesFields, { rideSeriesDateLabel, rideSeriesDepartures } from "./RideSeriesFields";

export default function RideSeriesRecovery({
  intent, busy, onCheck, onSend, onClose, onRetry, onDone,
}: {
  intent: RideSeriesIntent; busy: boolean;
  onCheck: () => void; onSend: () => void; onClose: (departAt: string) => void;
  onRetry: (departures: string[], replacedDates: string[]) => void; onDone: (published: number) => void;
}) {
  const { appText, lang } = useLang();
  const ba = lang === "ba";
  const [closing, setClosing] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [selectedRejected, setSelectedRejected] = useState<string[]>([]);
  const [days, setDays] = useState<string[]>([]);
  const [time, setTime] = useState("");
  const published = intent.items.filter(item => item.outcome === "applied").length;
  const rejected = intent.items.filter(item => item.outcome === "rejected");
  const unknown = intent.items.some(item => item.outcome === "unknown");
  const nextDates = rideSeriesDepartures(days, time);
  const occupied = nextDates.find(date => intent.items.some(item => item.depart_at === date &&
    !selectedRejected.includes(item.depart_at)));

  function editRejected() {
    const now = minDateTimeNow();
    const dates = rejected.flatMap(item => {
      const date = serverDate(item.depart_at);
      return date ? [toDateTimeInput(date)] : [];
    }).filter(value => value >= now);
    setDays([...new Set(dates.map(value => value.slice(0, 10)))]);
    setTime(dates[0]?.slice(11) ?? "");
    setSelectedRejected(rejected.map(item => item.depart_at));
    setEditing(true);
  }

  function selectRejected(departAt: string) {
    const next = selectedRejected.includes(departAt) ? selectedRejected.filter(value => value !== departAt)
      : [...selectedRejected, departAt];
    setSelectedRejected(next);
    setDays(value => value.slice(0, next.length));
  }

  return (
    <section className="state" aria-label={appText("Результат публикации", "Баҫтырыу һөҙөмтәһе")}>
      <p role="status">
        {appText(`Опубликовано ${published} из ${intent.items.length}`,
          `${intent.items.length} сәфәрҙең ${published} баҫтырылды`)}
      </p>
      <ul className="ride-series-results">
        {intent.items.map(item => {
          const date = serverDate(item.depart_at);
          const label = date ? `${rideSeriesDateLabel(date, ba)}, ${toDateTimeInput(date).slice(11)}`
            : appText("Дата недоступна", "Көндө уҡып булманы");
          const detail = item.outcome === "applied"
            ? item.code === "idempotency_result_gone"
              ? appText("Поездка была опубликована. Карточка недоступна.", "Сәфәр баҫтырылған ине. Карточка асыҡ түгел.")
              : item.already ? appText("Уже была опубликована", "Инде баҫтырылған ине")
                : appText("В ленте", "Таҫмала")
            : item.outcome === "unknown"
              ? appText("Результат пока неизвестен", "Һөҙөмтә әлегә билдәһеҙ")
              : (ba ? item.ba : item.ru) ||
                (item.code === "idempotency_closed_no_effect"
                  ? appText("Запрос закрыт", "Һорау ябылды")
                  : appText("Поездка не опубликована", "Сәфәр баҫтырылманы"));
          return (
            <li key={item.commandKey}>
              <strong>{label}</strong>
              <div>{detail}</div>
              {editing && item.outcome === "rejected" && (
                <label>
                  <input type="checkbox" className="checkbox" checked={selectedRejected.includes(item.depart_at)} disabled={busy}
                    aria-label={appText(`Заменить дату ${label}`, `${label} көнөн алмаштырырға`)}
                    onChange={() => selectRejected(item.depart_at)} />
                  {appText("Заменить дату", "Көндө алмаштырырға")}
                </label>
              )}
              {item.outcome === "unknown" && (closing === item.depart_at ? (
                <>
                  <p>{appText("Закрыть запрос на эту дату?", "Был көнгә һорауҙы ябырғамы?")}</p>
                  <button type="button" className="btn-soft" disabled={busy} onClick={() => onClose(item.depart_at)}>
                    {appText("Закрыть запрос", "Һорауҙы ябырға")}
                  </button>
                  <button type="button" className="btn-ghost" onClick={() => setClosing(undefined)}>
                    {appText("Отмена", "Кире алырға")}
                  </button>
                </>
              ) : (
                <button type="button" className="btn-ghost" disabled={busy} onClick={() => setClosing(item.depart_at)}>
                  {appText("Закрыть дату", "Көндө ябырға")}
                </button>
              ))}
            </li>
          );
        })}
      </ul>
      {unknown ? (
        <>
          <button type="button" className="btn-soft" disabled={busy} onClick={onCheck}>
            {appText("Проверить неизвестные даты", "Билдәһеҙ көндәрҙе тикшерергә")}
          </button>
          {canRepeatRideSeries(intent) && (
            <button type="button" className="btn-ghost" disabled={busy} onClick={onSend}>
              {appText("Повторить запрос неизвестных дат", "Билдәһеҙ көндәргә һорауҙы ҡабатларға")}
            </button>
          )}
        </>
      ) : (
        <>
          {rejected.length > 0 && !editing && (
            <button type="button" className="btn-soft" disabled={busy} onClick={editRejected}>
              {appText("Выбрать даты для новой попытки", "Яңы ынтылыш өсөн көндәрҙе һайларға")}
            </button>
          )}
          {editing && selectedRejected.length > 0 && (
            <>
              <RideSeriesFields days={days} time={time} onDays={setDays} onTime={setTime}
                disabled={busy} limit={selectedRejected.length} />
              {occupied && <p role="status">{appText(
                "Для этого времени уже есть результат. Выбери другую дату или время.",
                "Был ваҡытҡа һөҙөмтә бар инде. Башҡа көндө йәки ваҡытты һайла.",
              )}</p>}
              <button type="button" className="btn-primary"
                disabled={busy || !!occupied || nextDates.length !== selectedRejected.length}
                onClick={() => onRetry(nextDates, selectedRejected)}>
                {appText("Опубликовать выбранные даты заново", "Һайланған көндәрҙе яңынан баҫтырырға")}
              </button>
            </>
          )}
          <button type="button" className="btn-ghost" disabled={busy} onClick={() => onDone(published)}>
            {published > 0 ? appText("В кабинет водителя", "Йөрөтөүсе кабинетына")
              : appText("Вернуться к черновику", "Ҡараламаға ҡайтырға")}
          </button>
        </>
      )}
    </section>
  );
}
