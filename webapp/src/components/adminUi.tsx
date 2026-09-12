// ================================================================
//  Кирпичи админки — зеркало Android: ListedEmpty / ListedError
//  (RidesRequestsChatScreens.kt), *FilterChip и *StatusBadge (AdminTaxi /
//  AdminCourier / AdminWaitlist), WaitlistStatCard, SmallAvatar, плоские
//  карточки Surface(CanonItemShape, рамка). Размеры и цвета — токены Canon
//  (раздел «Админка — общие кирпичи» в ui.css), надписи — appText(ru, ba).
// ================================================================
import type { ReactNode } from "react";
import { useLang } from "../i18n/lang";
import { IconRequest } from "./Icons";

/** Вводная строка экрана: CanonMuted 14/20 (первый item каждого админ-списка). */
export function AdminIntro({ children }: { children: ReactNode }) {
  return <p className="dl-hint">{children}</p>;
}

/** ListedEmpty: карточка с рамкой, иконка списка 34 muted, заголовок Bold, подпись 14 muted. */
export function ListedEmpty({ title, subtitle, icon }: { title: string; subtitle: string; icon?: ReactNode }) {
  return (
    <div className="listed">
      <span className="listed__icon" aria-hidden>{icon ?? <IconRequest size={34} />}</span>
      <strong>{title}</strong>
      <span>{subtitle}</span>
    </div>
  );
}

/** ListedError: текст ошибки Bold 14 + зелёная «Повторить» — чтобы сбой сети не выглядел как «пусто». */
export function ListedError({ message, onRetry }: { message?: string; onRetry: () => void }) {
  const { appText } = useLang();
  return (
    <div className="listed listed--error" role="alert">
      <strong>{message ?? appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")}</strong>
      <button type="button" className="abtn" onClick={onRetry}>
        {appText("Повторить", "Ҡабатлау")}
      </button>
    </div>
  );
}

/** Строка «Загрузка…» muted — так Android показывает загрузку в админ-лентах. */
export function ListedLoading() {
  const { appText } = useLang();
  return <p className="dl-hint">{appText("Загрузка…", "Йөкләнә…")}</p>;
}

/** Ряд фильтр-чипов: 48dp, радиус 14, активный — мятный с зелёной рамкой и надписью. */
export function AdminFilterChips<T extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: { key: T; label: string }[];
  value: T;
  onChange: (key: T) => void;
  label: string;
}) {
  return (
    <div className="afilter-row" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.key}
          type="button"
          role="radio"
          aria-checked={value === o.key}
          className={"afilter" + (value === o.key ? " is-on" : "")}
          onClick={() => onChange(o.key)}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

/** Бейдж статуса заявки (таксист / курьер / партнёр): Одобрен · Отклонён · На проверке. */
export function AdminStatusBadge({ status }: { status: string }) {
  const { appText } = useLang();
  const tone = status === "approved" ? "ok" : status === "rejected" ? "bad" : "wait";
  const label =
    status === "approved"
      ? appText("Одобрен", "Раҫланған")
      : status === "rejected"
        ? appText("Отклонён", "Кире ҡағылған")
        : appText("На проверке", "Тикшереүҙә");
  return <span className={`abadge abadge--${tone}`}>{label}</span>;
}

/** Маленький тег 12 Bold на полупрозрачной подложке цвета (категория жалобы, вердикт автопроверки). */
export function AdminTag({ tone, children }: { tone: "green" | "red" | "warn" | "muted"; children: ReactNode }) {
  return <span className={`atag atag--${tone}`}>{children}</span>;
}

/** Карточка-счётчик: значение 19 Bold, подпись 12 muted, по центру. */
export function AdminStatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="astat">
      <b>{value}</b>
      <span>{label}</span>
    </div>
  );
}

/** Кружок с первой буквой имени (SmallAvatar): мятный фон, зелёная буква, либо фото. */
export function SmallAvatar({ src, name, size = 42 }: { src?: string | null; name: string; size?: number }) {
  const initial = (name.trim().charAt(0) || "?").toUpperCase();
  const style = { width: size, height: size };
  if (src) return <img className="sm-avatar" src={src} alt="" style={style} loading="lazy" />;
  return (
    <span className="sm-avatar" style={style} aria-hidden>
      {initial}
    </span>
  );
}

/** NearbyFilterChip: пилюля с иконкой 15 и надписью 12 Bold; активная — CanonGreen2 с белым. */
export function NearbyChip({ icon, label, active, onClick }: { icon: ReactNode; label: string; active: boolean; onClick: () => void }) {
  return (
    <button type="button" aria-pressed={active} className={"fchip" + (active ? " is-on" : "")} onClick={onClick}>
      <span className="fchip__icon" aria-hidden>{icon}</span>
      {label}
    </button>
  );
}
