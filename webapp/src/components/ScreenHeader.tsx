import type { ReactNode } from "react";
import LangToggle from "./LangToggle";

export default function ScreenHeader({
  title,
  subtitle,
}: {
  title: string;
  subtitle?: ReactNode;
}) {
  return (
    <header className="screen-header">
      <div className="screen-header__row">
        <div>
          <h1>{title}</h1>
          {subtitle && <p>{subtitle}</p>}
        </div>
        <LangToggle />
      </div>
    </header>
  );
}
