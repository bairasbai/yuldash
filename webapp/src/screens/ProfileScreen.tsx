import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { fetchNotifUnread } from "../api/notifications";
import { fetchSupportUnread } from "../api/support";
import ScreenHeader from "../components/ScreenHeader";
import BrandMark from "../components/BrandMark";
import {
  IconChevron,
  IconGift,
  IconLogout,
  IconShield,
  IconRides,
  IconHome,
  IconTrend,
  IconBell,
  IconFilter,
  IconHospital,
  IconWheel,
  IconClock,
  IconWallet,
  IconReceipt,
  IconSettings,
  IconTicket,
  IconStore,
  IconMegaphone,
  IconUsers,
  IconHeart,
  IconMic,
} from "../components/Icons";
import {
  YuModeTaxi,
  YuModeParcel,
  YuModeCourier,
  YuSafeTrip,
  YuSupport,
  YuChat,
  YuAccessible,
  YuStar,
} from "../components/BrandIcons";

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

  // Бейджи непрочитанного (уведомления + поддержка). Мягко: ошибку глотаем.
  const [notifUnread, setNotifUnread] = useState(0);
  const [supportUnread, setSupportUnread] = useState(0);
  useEffect(() => {
    if (!isAuthed) {
      setNotifUnread(0);
      setSupportUnread(0);
      return;
    }
    const ac = new AbortController();
    fetchNotifUnread(ac.signal).then(setNotifUnread).catch(() => {});
    fetchSupportUnread(ac.signal).then(setSupportUnread).catch(() => {});
    return () => ac.abort();
  }, [isAuthed]);

  const rows: {
    key: string;
    to: string;
    icon: JSX.Element;
    title: string;
    sub: string;
    authed?: boolean;
    admin?: boolean;
    badge?: number;
  }[] = [
    {
      key: "admin",
      to: "/admin",
      icon: <IconShield size={22} />,
      title: appText("Кабинет админа", "Админ кабинеты"),
      sub: appText("Модерация и управление", "Тикшереү һәм идара итеү"),
      authed: true,
      admin: true,
    },
    {
      key: "settings",
      to: "/settings",
      icon: <IconSettings size={22} />,
      title: appText("Настройки", "Көйләүҙәр"),
      sub: appText("Язык, тема, размер текста, приватность", "Тел, тема, текст ҙурлығы, ҡупшылыҡ"),
      authed: true,
    },
    {
      key: "notifications",
      to: "/notifications",
      icon: <IconBell size={22} />,
      title: appText("Уведомления", "Хәбәрҙәр"),
      sub: appText("Отклики, сообщения и новости", "Яуаптар, хәбәрҙәр һәм яңылыҡтар"),
      authed: true,
      badge: notifUnread,
    },
    {
      key: "cabinet",
      to: "/cabinet",
      icon: <IconRides size={22} />,
      title: appText("Мои поездки", "Сәфәрҙәрем"),
      sub: appText("Кабинет пассажира: поездки и разделы", "Юлаусы кабинеты: сәфәрҙәр һәм бүлектәр"),
      authed: true,
    },
    {
      key: "driver",
      to: "/driver",
      icon: <IconWheel size={22} />,
      title: appText("Я водитель", "Мин водитель"),
      sub: appText("Публикация поездок, заявки, заработок", "Сәфәр баҫтырыу, заявкалар, табыш"),
      authed: true,
    },
    {
      key: "taxi",
      to: "/taxi",
      icon: <YuModeTaxi size={22} />,
      title: appText("Быстрый заказ", "Тиҙ заказ"),
      sub: appText("Вызвать такси между своими", "Үҙебеҙ араһында такси саҡырыу"),
      authed: true,
    },
    {
      key: "taxi-drive",
      to: "/taxi-drive",
      icon: <IconWheel size={22} />,
      title: appText("Я на линии (такси)", "Мин линияла (такси)"),
      sub: appText("Принимай быстрые заказы рядом", "Яҡындағы тиҙ заказдарҙы ал"),
      authed: true,
    },
    {
      key: "scheduled",
      to: "/scheduled",
      icon: <IconClock size={22} />,
      title: appText("Мои предзаказы", "Алдан заказдарым"),
      sub: appText("Такси на время — заранее", "Ваҡытҡа такси — алдан"),
      authed: true,
    },
    {
      key: "parcels",
      to: "/parcels",
      icon: <YuModeParcel size={22} />,
      title: appText("Посылки", "Бандеролдәр"),
      sub: appText("Отправить или довезти «между своими»", "Ебәр йәки еткер «үҙебеҙ араһында»"),
      authed: true,
    },
    {
      key: "courier",
      to: "/courier",
      icon: <YuModeCourier size={22} />,
      title: appText("Режим курьера", "Курьер режимы"),
      sub: appText("Бери доставки рядом и зарабатывай", "Яҡындағы доставкаларҙы ал һәм эшлә"),
      authed: true,
    },
    {
      key: "places",
      to: "/places",
      icon: <IconHome size={22} />,
      title: appText("Мои адреса", "Адрестарым"),
      sub: appText("Дом, работа и любимые точки", "Өй, эш һәм яҡын нөктәләр"),
      authed: true,
    },
    {
      key: "stats",
      to: "/stats",
      icon: <IconTrend size={22} />,
      title: appText("Мой Юлдаш", "Минең Юлдаш"),
      sub: appText("Км, поездки и сколько сэкономил", "Км, сәфәрҙәр һәм күпме янға ҡалды"),
      authed: true,
    },
    {
      key: "wallet",
      to: "/wallet",
      icon: <IconWallet size={22} />,
      title: appText("Кошелёк", "Янсыҡ"),
      sub: appText("Заработок с безналичных поездок", "Аҡсаһыҙ сәфәрҙәрҙән табыш"),
      authed: true,
    },
    {
      key: "coupons",
      to: "/coupons",
      icon: <IconTicket size={22} />,
      title: appText("Скидки по пути", "Юлда ташламалар"),
      sub: appText("Купоны от своих заведений", "Үҙ заведениеларҙан купондар"),
    },
    {
      key: "promo",
      to: "/promo",
      icon: <IconGift size={22} />,
      title: appText("Промокод", "Промокод"),
      sub: appText("Код друга или акции — и тебе бонус", "Дуҫ йәки акция коды — һиңә бонус"),
      authed: true,
    },
    {
      key: "partner",
      to: "/partner",
      icon: <IconStore size={22} />,
      title: appText("Мой бизнес", "Минең бизнесым"),
      sub: appText("Разместить свою скидку в Юлдаше", "Юлдашта үҙ ташламаңды урынлаштыр"),
      authed: true,
    },
    {
      key: "ads",
      to: "/ads",
      icon: <IconMegaphone size={22} />,
      title: appText("Реклама", "Реклама"),
      sub: appText("Рассказать о деле попутчикам", "Юлдаштарға эшең тураһында һөйләргә"),
      authed: true,
    },
    {
      key: "payment-info",
      to: "/payment-info",
      icon: <IconReceipt size={22} />,
      title: appText("Как оплатить", "Нисек түләргә"),
      sub: appText("Способы оплаты — честно и просто", "Түләү ысулдары — намыҫлы һәм ябай"),
    },
    {
      key: "watch",
      to: "/route-watches",
      icon: <IconBell size={22} />,
      title: appText("Подписки на маршрут", "Маршрут яҙылыуҙары"),
      sub: appText("Появится попутка — пришлём", "Юлдаш сыҡһа — хәбәр итәбеҙ"),
      authed: true,
    },
    {
      key: "clinics",
      to: "/clinics",
      icon: <IconHospital size={22} />,
      title: appText("Поездки к клинике", "Клиникаға сәфәр"),
      sub: appText("Доехать до больницы вместе", "Дауаханаға бергә барырға"),
    },
    {
      key: "filters",
      to: "/filters",
      icon: <IconFilter size={22} />,
      title: appText("Фильтры", "Фильтрҙар"),
      sub: appText("Настрой ленту под себя", "Таҫманы үҙеңә көйлә"),
    },
    {
      key: "simple",
      to: "/simple",
      icon: <YuAccessible size={22} />,
      title: appText("Простой режим", "Ябай режим"),
      sub: appText("Крупно и просто — и размер шрифта", "Ҙур һәм ябай — һәм шрифт ҙурлығы"),
    },
    {
      key: "sos",
      to: "/sos",
      icon: <IconShield size={22} />,
      title: appText("Экстренная помощь", "Ашығыс ярҙам"),
      sub: appText("SOS и звонок в службы", "SOS һәм хеҙмәткә шылтыратыу"),
    },
    {
      key: "trusted",
      to: "/trusted",
      icon: <IconUsers size={22} />,
      title: appText("Доверенные контакты", "Ышаныслы контакттар"),
      sub: appText("Кому сообщить в поездке", "Сәфәрҙә кемгә хәбәр итергә"),
      authed: true,
    },
    {
      key: "family-order",
      to: "/family-order",
      icon: <IconHeart size={22} />,
      title: appText("За близкого", "Яҡын өсөн"),
      sub: appText("Заказать поездку другому", "Башҡаға сәфәр заказ итергә"),
      authed: true,
    },
    {
      key: "voice",
      to: "/voice",
      icon: <IconMic size={22} />,
      title: appText("Голосовая заявка", "Тауышлы заявка"),
      sub: appText("Надиктуй поездку голосом", "Сәфәрҙе тауыш менән әйт"),
      authed: true,
    },
    {
      key: "callback",
      to: "/callback",
      icon: <YuSupport size={22} />,
      title: appText("Перезвоните мне", "Миңә шылтыратығыҙ"),
      sub: appText("Мы позвоним и всё оформим", "Беҙ шылтыратып рәтләйбеҙ"),
    },
    {
      key: "trust",
      to: "/trust",
      icon: <YuSafeTrip size={22} />,
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
    {
      key: "help",
      to: "/help",
      icon: <YuSupport size={22} />,
      title: appText("Помощь", "Ярҙам"),
      sub: appText("Частые вопросы и ответы", "Йыш бирелгән һорауҙар"),
    },
    {
      key: "support",
      to: "/support",
      icon: <YuChat size={22} />,
      title: appText("Поддержка Юлдаш", "Юлдаш ярҙамы"),
      sub: appText("Напиши нам — поможем с любым вопросом", "Беҙгә яҙ — теләһә ниҙә ярҙам итәбеҙ"),
      authed: true,
      badge: supportUnread,
    },
    {
      key: "app-review",
      to: "/app-review",
      icon: <YuStar size={22} />,
      title: appText("Оценить приложение", "Ҡушымтаны баһалау"),
      sub: appText("Поставь звёзды и оставь отзыв", "Йондоҙ ҡуй һәм фекер яҙ"),
      authed: true,
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
                  <YuStar size={15} className="star" /> {user.rating.toFixed(1)}
                </span>
              )}
            </div>
          </div>
        </div>
      ) : (
        <div className="profile-guest">
          <div className="profile-guest__mark">
            <BrandMark size={64} />
          </div>
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
        {rows
          .filter((r) => (!r.authed || isAuthed) && (!r.admin || user?.role === "admin"))
          .map((r) => (
          <button key={r.key} type="button" className="list-row list-row--link" onClick={() => navigate(r.to)}>
            <span className="list-row__icon">{r.icon}</span>
            <div className="list-row__main">
              <div className="list-row__title">{r.title}</div>
              <div className="list-row__sub">{r.sub}</div>
            </div>
            {r.badge ? (
              <span className="list-row__badge" aria-label={appText(`${r.badge} новых`, `${r.badge} яңы`)}>
                {r.badge > 99 ? "99+" : r.badge}
              </span>
            ) : null}
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
