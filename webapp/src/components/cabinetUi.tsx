// ================================================================
//  Кирпичи кабинетов — зеркало Android: SettingsGroup / SettingsNavRow
//  (BookingActiveTripScreen.kt), CabinetMetric (ProfileScreen.kt),
//  RestrictionsCard (ProfileScreen.kt). Размеры и цвета — токены Canon
//  (раздел «Кабинеты» в ui.css), надписи — appText(ru, ba).
// ================================================================
import type { ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import type { Restriction } from "../api/safety";
import { serverDate } from "../utils/serverTime";
import { IconCheck, IconChevron, IconLock, IconWarn } from "./Icons";

/** Группа строк меню: одна карточка CanonItemShape с тенью, внутри строки. */
export function SettingsGroup({ children }: { children: ReactNode }) {
  return <div className="settings-group">{children}</div>;
}

/** Строка меню: мятная плитка 48 с иконкой, заголовок 16 Bold, подпись 14 muted, шеврон. */
export function SettingsNavRow({
  icon,
  title,
  subtitle,
  onClick,
  badge = 0,
}: {
  icon: ReactNode;
  title: string;
  subtitle: string;
  onClick?: () => void;
  badge?: number;
}) {
  const body = (
    <>
      <span className="settings-row__icon" aria-hidden>{icon}</span>
      <span className="settings-row__text">
        <strong>{title}</strong>
        <small>{subtitle}</small>
      </span>
      {badge > 0 && <span className="settings-row__badge">{badge > 99 ? "99+" : badge}</span>}
      {onClick && <span className="settings-row__chev" aria-hidden><IconChevron size={20} /></span>}
    </>
  );
  if (!onClick) return <div className="settings-row">{body}</div>;
  return (
    <button type="button" className="settings-row" onClick={onClick}>
      {body}
    </button>
  );
}

/** Плитка метрики: значение 19 Bold зелёным, подпись 12 muted, рамка 1, радиус 14. */
export function CabinetMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="cab-metric">
      <b>{value}</b>
      <small>{label}</small>
    </div>
  );
}

/** «Мои ограничения» — жёлтая карточка: что ограничено, почему, до когда, и дверь в поддержку. */
export function RestrictionsCard({
  items,
  supportRu,
  supportBa,
}: {
  items: Restriction[];
  supportRu?: string;
  supportBa?: string;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  if (items.length === 0) return null;
  const dueLabel = (iso: string) => {
    const d = serverDate(iso);
    if (!d) return iso;
    return d.toLocaleString(ru ? "ru-RU" : "ba-RU", { day: "numeric", month: "long", hour: "2-digit", minute: "2-digit" });
  };
  return (
    <section className="restrictions">
      <div className="restrictions__head">
        <IconLock size={20} />
        <strong>{appText("Мои ограничения", "Минең сикләүҙәр")}</strong>
      </div>
      {items.map((it, i) => {
        const cat = ru ? (it.category_ru ?? it.category ?? "") : (it.category_ba ?? it.category ?? "");
        return (
          <div key={i} className="restrictions__item">
            <strong>{appText(it.title_ru, it.title_ba)}</strong>
            {cat && <span>{appText(`Причина: ${cat}`, `Сәбәп: ${cat}`)}</span>}
            <small>
              {it.until
                ? appText("До ", "Тиклем: ") + dueLabel(it.until)
                : appText("До разбора — решает живой человек", "Тикшергәнсе — тере кеше хәл итә")}
            </small>
            <small>{appText(it.note_ru, it.note_ba)}</small>
          </div>
        );
      })}
      {(supportRu || supportBa) && <p className="restrictions__support">{appText(supportRu ?? "", supportBa ?? supportRu ?? "")}</p>}
      <button type="button" className="restrictions__btn" onClick={() => navigate("/support")}>
        {appText("Написать в поддержку", "Ярҙамға яҙыу")}
      </button>
    </section>
  );
}

/** Строка с тумблером (SettingSwitchRow): та же плитка и подписи, справа переключатель. */
export function SettingSwitchRow({
  icon,
  title,
  subtitle,
  checked,
  onChange,
  disabled = false,
}: {
  icon: ReactNode;
  title: string;
  subtitle: string;
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      className="settings-row"
      onClick={() => onChange(!checked)}
      disabled={disabled}
    >
      <span className="settings-row__icon" aria-hidden>{icon}</span>
      <span className="settings-row__text">
        <strong>{title}</strong>
        <small>{subtitle}</small>
      </span>
      <span className={"switch" + (checked ? " on" : "")} aria-hidden />
    </button>
  );
}

/** Заголовок раздела (SectionHeader из UiKit): CanonHeading + подпись CanonCaption. */
export function SectionHeader({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div className="section-head">
      <h3 className="section-head__title">{title}</h3>
      {subtitle && <p className="section-head__sub">{subtitle}</p>}
    </div>
  );
}

/** Строка архива поездки (ArchiveRideCard): круг 38 (мятный/красный), маршрут 16 Bold, дата · итог, цена. */
export function ArchiveRideRow({
  from,
  to,
  when,
  status,
  price,
}: {
  from: string;
  to: string;
  when: string;
  status: string;
  price: number;
}) {
  const { appText } = useLang();
  const done = status === "done";
  const label =
    status === "done"
      ? appText("Завершена", "Тамамланды")
      : status === "expired"
        ? appText("Не состоялась", "Булманы")
        : appText("Отменена", "Баш тартылды");
  return (
    <div className="archive-row">
      <span className={"archive-row__icon" + (done ? " is-done" : " is-off")} aria-hidden>
        {done ? <IconCheck size={20} /> : <IconWarn size={20} />}
      </span>
      <span className="archive-row__text">
        <strong>{from} → {to}</strong>
        <small>{when} · {label}</small>
      </span>
      {price > 0 && <b className="archive-row__price">{price} ₽</b>}
    </div>
  );
}
