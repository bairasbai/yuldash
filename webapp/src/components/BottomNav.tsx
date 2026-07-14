import { NavLink } from "react-router-dom";
import { useLang } from "../i18n/lang";
import type { DictKey } from "../i18n/dict";
import {
  IconChat,
  IconMap,
  IconProfile,
  IconRequest,
  IconRides,
} from "./Icons";

type Tab = {
  to: string;
  key: DictKey;
  Icon: (p: { size?: number }) => JSX.Element;
};

// Порядок 1:1 как в приложении: Карта · Поездки · Заявка · Чат · Профиль
const tabs: Tab[] = [
  { to: "/map", key: "navMap", Icon: IconMap },
  { to: "/rides", key: "navRides", Icon: IconRides },
  { to: "/request", key: "navRequest", Icon: IconRequest },
  { to: "/chat", key: "navChat", Icon: IconChat },
  { to: "/profile", key: "navProfile", Icon: IconProfile },
];

export default function BottomNav() {
  const { t } = useLang();
  return (
    <nav className="bottom-nav" aria-label="Основная навигация">
      {tabs.map(({ to, key, Icon }) => (
        <NavLink
          key={to}
          to={to}
          className={({ isActive }) => "nav-item" + (isActive ? " is-active" : "")}
          aria-label={t(key)}
        >
          <Icon size={24} />
          <span>{t(key)}</span>
        </NavLink>
      ))}
    </nav>
  );
}
