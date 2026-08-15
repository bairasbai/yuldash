import { NavLink } from "react-router-dom";
import { useLang } from "../i18n/lang";
import type { DictKey } from "../i18n/dict";
import {
  YuChat,
  YuMapTab,
  YuProfile,
  YuRequestAdd,
  YuTripList,
} from "./BrandIcons";

type Tab = {
  to: string;
  key: DictKey;
  Icon: (p: { size?: number }) => JSX.Element;
};

// Иконки — брендовый набор из Android (res/drawable/yu_*.xml), 1:1 с приложением.
// Порядок 1:1 как в приложении: Карта · Поездки · Заявка · Чат · Профиль
const tabs: Tab[] = [
  { to: "/map", key: "navMap", Icon: YuMapTab },
  { to: "/rides", key: "navRides", Icon: YuTripList },
  { to: "/request", key: "navRequest", Icon: YuRequestAdd },
  { to: "/chat", key: "navChat", Icon: YuChat },
  { to: "/profile", key: "navProfile", Icon: YuProfile },
];

export default function BottomNav() {
  const { t, appText } = useLang();
  return (
    // Подпись для озвучки экрана — тоже надпись, и она была только по-русски:
    // незрячий человек с башкирским интерфейсом слышал чужой язык.
    <nav className="bottom-nav" aria-label={appText("Основная навигация", "Төп навигация")}>
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
