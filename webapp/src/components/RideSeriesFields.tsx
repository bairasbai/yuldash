import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useLang } from "../i18n/lang";
import { minDateTimeNow, toDateTimeInput } from "../utils/dateInput";
import { MAX_RIDE_SERIES_DATES } from "../utils/rideSeriesIntent";

const BA_MONTHS = [
  "ғинуар", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
];
const DAY_OFFSETS: Partial<Record<string, number>> = {
  ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7,
};

export function rideSeriesDateLabel(date: Date, ba: boolean): string {
  if (ba) return `${date.getDate()} ${BA_MONTHS[date.getMonth()]} ${date.getFullYear()}`;
  return new Intl.DateTimeFormat("ru-RU", { dateStyle: "full" }).format(date);
}

export function rideSeriesDepartures(days: string[], time: string): string[] {
  if (!/^\d{2}:\d{2}$/.test(time)) return [];
  const departures = days.map(day => new Date(day + "T" + time));
  if (departures.some((date, index) => !Number.isFinite(date.getTime()) ||
      toDateTimeInput(date) !== days[index] + "T" + time)) return [];
  return departures.map(date => date.toISOString()).sort();
}

export default function RideSeriesFields({
  days, time, onDays, onTime, disabled, limit = MAX_RIDE_SERIES_DATES,
}: {
  days: string[]; time: string; onDays: (days: string[]) => void;
  onTime: (time: string) => void; disabled?: boolean; limit?: number;
}) {
  const { appText, lang } = useLang();
  const today = minDateTimeNow().slice(0, 10);
  const [month, setMonth] = useState(() => {
    const first = new Date((days[0] ?? today) + "T12:00");
    return new Date(first.getFullYear(), first.getMonth(), 1, 12);
  });
  const grid = useRef<HTMLDivElement>(null);
  const keyboardDay = useRef<string>();
  const offset = (month.getDay() + 6) % 7;
  const count = new Date(month.getFullYear(), month.getMonth() + 1, 0, 12).getDate();
  const monthLabel = lang === "ba" ? `${BA_MONTHS[month.getMonth()]} ${month.getFullYear()}` :
    new Intl.DateTimeFormat("ru-RU", {
    year: "numeric", month: "long",
  }).format(month);
  const firstOfMonth = toDateTimeInput(month).slice(0, 7);
  const weekdays = lang === "ba"
    ? ["Дш", "Шш", "Шр", "Кс", "Йм", "Шб", "Йк"]
    : ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];

  function toggle(day: string) {
    if (days.includes(day)) onDays(days.filter(value => value !== day));
    else if (days.length < limit) onDays([...days, day].sort());
  }

  function focusDay(day: string) {
    grid.current?.querySelector<HTMLButtonElement>(`button[data-day="${day}"]:not(:disabled)`)?.focus();
  }

  useEffect(() => {
    if (keyboardDay.current) {
      focusDay(keyboardDay.current);
      keyboardDay.current = undefined;
    }
  }, [month]);

  function moveDay(event: KeyboardEvent<HTMLDivElement>) {
    const target = event.target;
    const step = DAY_OFFSETS[event.key];
    if (!step || !(target instanceof HTMLButtonElement) || !target.dataset.day) return;
    event.preventDefault();
    const next = new Date(target.dataset.day + "T12:00");
    next.setDate(next.getDate() + step);
    const day = toDateTimeInput(next).slice(0, 10);
    if (day < today || (days.length >= limit && !days.includes(day))) return;
    if (day.slice(0, 7) === firstOfMonth) focusDay(day);
    else {
      keyboardDay.current = day;
      setMonth(new Date(next.getFullYear(), next.getMonth(), 1, 12));
    }
  }

  return (
    <section className="ride-series-picker" aria-label={appText("Даты выезда", "Сығыу көндәре")}>
      <div className="ride-series-month">
        <button type="button" className="opt-chip"
          aria-label={appText("Предыдущий месяц", "Алдағы ай")}
          disabled={disabled || firstOfMonth <= today.slice(0, 7)}
          onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1, 12))}>‹</button>
        <strong aria-live="polite">{monthLabel}</strong>
        <button type="button" className="opt-chip"
          aria-label={appText("Следующий месяц", "Киләһе ай")} disabled={disabled}
          onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1, 12))}>›</button>
      </div>
      <div ref={grid} className="ride-series-grid" role="grid" aria-label={monthLabel} aria-multiselectable="true"
        onKeyDown={moveDay}>
        <div className="ride-series-week" role="row">
          {weekdays.map(day => <span key={day} role="columnheader">{day}</span>)}
        </div>
        {Array.from({ length: Math.ceil((offset + count) / 7) }, (_, week) => (
          <div className="ride-series-week" role="row" key={week}>
            {Array.from({ length: 7 }, (_, weekday) => {
              const day = week * 7 + weekday + 1 - offset;
              if (day < 1 || day > count) return <span key={weekday} role="gridcell" />;
              const date = new Date(month.getFullYear(), month.getMonth(), day, 12);
              const value = toDateTimeInput(date).slice(0, 10);
              const selected = days.includes(value);
              return (
                <div key={weekday} role="gridcell" aria-selected={selected}>
                  <button type="button" data-day={value} className={"opt-chip" + (selected ? " is-active" : "")}
                    aria-pressed={selected}
                    aria-label={rideSeriesDateLabel(date, lang === "ba")}
                    disabled={disabled || value < today || (!selected && days.length >= limit)}
                    onClick={() => toggle(value)}>{day}</button>
                </div>
              );
            })}
          </div>
        ))}
      </div>
      <p role="status" className="ride-series-count">
        {appText(`Выбрано дат: ${days.length} из ${limit}`, `Һайланған көндәр: ${days.length} / ${limit}`)}
      </p>
      <label className="field">
        <span className="field__label">{appText("Время выезда в каждый день", "Һәр көн сығыу ваҡыты")}</span>
        <input type="time" className="field__input" value={time} disabled={disabled}
          onChange={event => onTime(event.target.value)} />
      </label>
    </section>
  );
}
