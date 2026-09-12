// ================================================================
//  Кирпичи денежных экранов — зеркало Android CourierEarningsScreen.kt /
//  DriverEarningsScreen.kt: MoneyPeriodSwitch, EarnPeriodChip, MoneyTotalsCard +
//  MoneyLine, MoneySectionHeader, MoneyDayRow. Шкала MoneyType: герой 34/40 (-0.5),
//  значение 19/25, тело 14/20, подпись 12/17 — те же токены Canon (var(--…)).
// ================================================================
import { useEffect, useState, type ReactNode } from "react";

export type MoneyPeriod = "week" | "month" | "all";

/** Сегменты периода в пилюле (курьер): активный — мятный с зелёной подписью. */
export function MoneyPeriodSwitch({
  period,
  onSelect,
  labels,
}: {
  period: MoneyPeriod;
  onSelect: (p: MoneyPeriod) => void;
  labels: Record<MoneyPeriod, string>;
}) {
  return (
    <div className="money-switch" role="tablist">
      {(["week", "month", "all"] as MoneyPeriod[]).map((p) => (
        <button
          key={p}
          type="button"
          role="tab"
          aria-selected={period === p}
          className={"money-switch__seg" + (period === p ? " is-active" : "")}
          onClick={() => onSelect(p)}
        >
          {labels[p]}
        </button>
      ))}
    </div>
  );
}

/** Чипы периода (водитель): активный — залит CanonGreen2, иконка 16 слева. */
export function EarnPeriodChips({
  period,
  onSelect,
  items,
}: {
  period: MoneyPeriod;
  onSelect: (p: MoneyPeriod) => void;
  items: { key: MoneyPeriod; label: string; icon: ReactNode }[];
}) {
  return (
    <div className="earn-chips" role="tablist">
      {items.map((it) => (
        <button
          key={it.key}
          type="button"
          role="tab"
          aria-selected={period === it.key}
          className={"earn-chip" + (period === it.key ? " is-active" : "")}
          onClick={() => onSelect(it.key)}
        >
          <span className="earn-chip__icon" aria-hidden>{it.icon}</span>
          {it.label}
        </button>
      ))}
    </div>
  );
}

/** Итог периода: подпись, сумма-герой и строки MoneyLine (AppCard). */
export function MoneyTotalsCard({ label, value, children }: { label: string; value: string; children?: ReactNode }) {
  return (
    <section className="money-card">
      <div className="money-card__hero">
        <span className="money-card__label">{label}</span>
        <b className="money-card__value">{value}</b>
      </div>
      {children && <div className="money-card__rows">{children}</div>}
    </section>
  );
}

/** Строка «иконка в круге · подпись · значение» (MoneyLine). */
export function MoneyLine({
  icon,
  tone = "mint",
  label,
  value,
  valueTone = "text",
}: {
  icon: ReactNode;
  tone?: "mint" | "warn";
  label: string;
  value: string;
  valueTone?: "text" | "warn" | "green";
}) {
  return (
    <div className="money-line">
      <span className={"money-line__icon money-line__icon--" + tone} aria-hidden>{icon}</span>
      <span className="money-line__label">{label}</span>
      <b className={"money-line__value" + (valueTone === "warn" ? " is-warn" : valueTone === "green" ? " is-green" : "")}>{value}</b>
    </div>
  );
}

/** Заголовок раздела денежного экрана: 19 Bold + тихая подпись 12. */
export function MoneySectionHeader({ title, caption }: { title: string; caption: string }) {
  return (
    <div className="money-head">
      <h3>{title}</h3>
      <p>{caption}</p>
    </div>
  );
}

/**
 * Строка дня: дата жирным, сумма зелёным, мини-бар шириной ∝ сумме (растёт после первого кадра,
 * каскадом по индексу), подпись с числом поездок/доставок.
 */
export function MoneyDayRow({
  date,
  caption,
  value,
  fraction,
  index = 0,
  captionBelow = true,
}: {
  date: string;
  caption: string;
  value: string;
  fraction: number;
  index?: number;
  /** true — подпись под баром (водитель); false — под датой (курьер). */
  captionBelow?: boolean;
}) {
  const [grown, setGrown] = useState(false);
  useEffect(() => {
    const id = window.setTimeout(() => setGrown(true), 20);
    return () => window.clearTimeout(id);
  }, []);
  const width = grown ? Math.min(1, Math.max(0, fraction)) : 0;
  return (
    <div className={"money-day" + (captionBelow ? "" : " money-day--courier")}>
      <div className="money-day__row">
        <span className="money-day__text">
          <strong>{date}</strong>
          {!captionBelow && <small>{caption}</small>}
        </span>
        <b className="money-day__value">{value}</b>
      </div>
      <div className="money-day__track" aria-hidden>
        <div
          className="money-day__fill"
          style={{ width: `${width * 100}%`, transitionDelay: `${Math.min(index, 6) * 40}ms` }}
        />
      </div>
      {captionBelow && <small className="money-day__caption">{caption}</small>}
    </div>
  );
}
