import type { ReactNode } from "react";
import LangToggle from "./LangToggle";

export default function ScreenHeader({
  title,
  subtitle,
  eyebrow,
}: {
  title: string;
  subtitle?: ReactNode;
  /** Строка над заголовком (приветствие на карте) — CanonCaption приглушённым, как в Android HomeHeader. */
  eyebrow?: ReactNode;
}) {
  return (
    <header className="screen-header">
      <div className="screen-header__row">
        <div>
          {eyebrow && <p className="screen-header__eyebrow">{eyebrow}</p>}
          <h1>{title}</h1>
          {subtitle && <p>{subtitle}</p>}
        </div>
        <LangToggle />
      </div>
    </header>
  );
}
