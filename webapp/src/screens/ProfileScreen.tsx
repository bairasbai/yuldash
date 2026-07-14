import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import ScreenHeader from "../components/ScreenHeader";
import {
  IconChevron,
  IconGift,
  IconLogout,
  IconShield,
  IconStar,
} from "../components/Icons";

function initials(name: string): string {
  const p = name.trim().split(/\s+/).filter(Boolean);
  return p.length ? (p[0][0] + (p[1]?.[0] ?? "")).toUpperCase() : "?";
}

/**
 * Профиль-хаб (волна 1 — облегчённый, полноценный в волне 7).
 * Точка входа в Согласия / Доверие / Инвайты и вход/выход.
 * Публичный: гостю показываем приглашение войти; приватные разделы защищены RequireAuth.
 */
export default function ProfileScreen() {
  const { appText } = useLang();
  const { user, isAuthed, logout } = useAuth();
  const navigate = useNavigate();

  const rows: {
    key: string;
    to: string;
    icon: JSX.Element;
    title: string;
    sub: string;
  }[] = [
    {
      key: "trust",
      to: "/trust",
      icon: <IconShield size={22} />,
      title: appText("Доверие", "Ышаныс"),
      sub: appText("Твой уровень и круг «между своими»", "Кимәлең һәм «үҙебеҙ араһында» түңәрәк"),
    },
    {
      key: "invites",
      to: "/invites",
      icon: <IconGift size={22} />,
      title: appText("Позови своих", "Үҙеңдекеләрҙе саҡыр"),
      sub: appText("Инвайт-код и бонусы", "Саҡырыу коды һәм бонустар"),
    },
    {
      key: "consents",
      to: "/consents",
      icon: <IconShield size={22} />,
      title: appText("Согласия", "Ризалыҡтар"),
      sub: appText("Оферта, приватность, гео (152-ФЗ)", "Оферта, ҡупшылыҡ, гео (152-ФЗ)"),
    },
  ];

  return (
    <>
      <ScreenHeader title={appText("Профиль", "Профиль")} />

      {isAuthed && user ? (
        <div className="profile-card">
          <div className="profile-card__avatar">
            {user.avatar_url ? (
              <img src={user.avatar_url} alt="" />
            ) : (
              <span>{initials(user.name)}</span>
            )}
          </div>
          <div className="profile-card__info">
            <div className="profile-card__name">
              {user.name || appText("Без имени", "Исемһеҙ")}
            </div>
            <div className="profile-card__meta">
              {user.verified && (
                <span className="badge badge--mint">{appText("Проверен", "Тикшерелгән")}</span>
              )}
              {user.rating != null && (
                <span className="profile-card__rating">
                  <IconStar size={15} /> {user.rating.toFixed(1)}
                </span>
              )}
            </div>
          </div>
        </div>
      ) : (
        <div className="profile-guest">
          <div className="profile-guest__emoji">👋</div>
          <h2>{appText("Войди в Юлдаш", "Юлдашҡа ин")}</h2>
          <p>
            {appText(
              "Чтобы бронировать поездки, звать своих и видеть доверие — войди через Telegram.",
              "Сәфәр бронларға, үҙеңдекеләрҙе саҡырырға һәм ышанысты күрергә — Telegram аша ин."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/login")}>
            {appText("Войти", "Инеү")}
          </button>
        </div>
      )}

      <div className="list">
        {rows.map((r) => (
          <button key={r.key} type="button" className="list-row list-row--link" onClick={() => navigate(r.to)}>
            <span className="list-row__icon">{r.icon}</span>
            <div className="list-row__main">
              <div className="list-row__title">{r.title}</div>
              <div className="list-row__sub">{r.sub}</div>
            </div>
            <span className="list-row__chev">
              <IconChevron size={20} />
            </span>
          </button>
        ))}
      </div>

      {isAuthed && (
        <button type="button" className="logout-btn" onClick={() => void logout()}>
          <IconLogout size={20} />
          {appText("Выйти", "Сығырға")}
        </button>
      )}
    </>
  );
}
