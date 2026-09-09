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
  { to: "/my-requests", key: "navRequest", Icon: YuRequestAdd },
  { to: "/chat", key: "navChat", Icon: YuChat },
  { to: "/profile", key: "navProfile", Icon: YuProfile },
];

export default function BottomNav({ debtBadge = false }: { debtBadge?: boolean }) {
  const { t, appText } = useLang();
  return (
    // Подпись для озвучки экрана — тоже надпись, и она была только по-русски:
    // незрячий человек с башкирским интерфейсом слышал чужой язык.
    <nav
      className="bottom-nav"
      aria-label={appText("Основная навигация", "Төп навигация")}
    >
      {tabs.map(({ to, key, Icon }) => (
        <NavLink
          key={to}
          to={to}
          className={({ isActive }) => "nav-item" + (isActive ? " is-active" : "")}
          aria-label={
            key === "navProfile" && debtBadge
              ? `${t(key)} · ${appText("К оплате сейчас", "Хәҙер түләргә")}`
              : t(key)
          }
        >
          <span className="nav-pill" aria-hidden>
            <Icon size={21} />
            {key === "navProfile" && debtBadge && <span className="nav-debt-badge" />}
          </span>
          <span className="nav-item__label">{t(key)}</span>
        </NavLink>
      ))}
    </nav>
  );
}
