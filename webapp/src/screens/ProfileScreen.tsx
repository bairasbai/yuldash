import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { fetchNotifUnread } from "../api/notifications";
import { fetchSupportUnread } from "../api/support";
import ScreenHeader from "../components/ScreenHeader";
import BrandMark from "../components/BrandMark";
import { IconChevron, IconGift, IconLogout, IconShield, IconRides, IconHome, IconTrend, IconBell, IconFilter, IconHospital, IconWheel, IconClock, IconWallet, IconReceipt, IconSettings, IconTicket, IconStore, IconMegaphone, IconUsers, IconHeart, IconMic, IconPencil, IconPin } from "../components/Icons";
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
import { PartnerAdSlot } from "../components/PartnerAd";
import ReferralCard from "../components/ReferralCard";

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
      key: "my-data",
      to: "/my-data",
      icon: <IconShield size={22} />,
      title: appText("Мои данные", "Минең мәғлүмәттәр"),
      sub: appText("Что хранится и когда удалится", "Нимә һаҡлана һәм ҡасан юйыла"),
      authed: true,
    },
    {
      key: "driver",
      to: "/driver",
      icon: <IconWheel size={22} />,
      title: appText("Я водитель", "Мин йөрөтөүсе"),
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
      sub: appText("Дом, работа и любимые места", "Өй, эш һәм яҡын нөктәләр"),
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
      key: "payment-methods",
      to: "/payment-methods",
      icon: <IconWallet size={22} />,
      title: appText("Способы оплаты", "Түләү ысулдары"),
      sub: appText("Наличные, СБП или договориться", "Наличный, СБП йәки килешеү"),
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
      key: "support-yuldash",
      to: "/support-yuldash",
      icon: <IconHeart size={22} />,
      title: appText("Поддержать Юлдаш", "Юлдашҡа ярҙам итеү"),
      sub: appText("Серверы, карты, SMS и поддержка", "Серверҙар, карталар, SMS һәм ярҙам"),
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
      sub: appText("Твой уровень и круг «своих»", "Кимәлең һәм «үҙебеҙ араһында» түңәрәк"),
    },
    {
      // Хаб безопасности: в тревоге искать SOS по меню человек не должен.
      key: "safety",
      to: "/safety",
      icon: <IconShield size={22} />,
      title: appText("Безопасность", "Хәүефһеҙлек"),
      sub: appText("SOS, чёрный список, жалоба, правила", "SOS, ҡара исемлек, ялыу, ҡағиҙәләр"),
    },
    {
      // «Что теперь со мной?» после плохой поездки — один экран вместо тишины.
      key: "fairness",
      to: "/fairness",
      icon: <IconShield size={22} />,
      title: appText("Центр справедливости", "Ғәҙеллек үҙәге"),
      sub: appText("Твоё положение и разборы споров", "Хәлең һәм бәхәстәр"),
    },
    {
      key: "invites",
      to: "/invites",
      icon: <IconGift size={22} />,
      title: appText("Позови своего", "Үҙеңдекеләрҙе саҡыр"),
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
        <button
          type="button"
          className="profile-card"
          style={{ width: "100%", textAlign: "left" }}
          onClick={() => navigate("/profile/edit")}
        >
          <div className="profile-card__avatar">
            {user.avatar_url ? (
              <img src={user.avatar_url} alt="" />
            ) : (
              <span>{initials(user.name)}</span>
            )}
          </div>
          {/* Герой Android ProfileScreen: имя 24 Bold + карандаш, роль · ★ рейтинг, город, подсказка о телефоне. */}
          <div className="profile-card__info">
            <div className="profile-card__name">
              <span>{user.name || appText("Без имени", "Исемһеҙ")}</span>
              <span className="profile-card__edit" aria-hidden><IconPencil size={18} /></span>
            </div>
            <div className="profile-card__meta">
              <span className="profile-card__role">
                {user.role === "driver"
                  ? appText("Водитель", "Йөрөтөүсе")
                  : user.role === "admin"
                    ? appText("Администратор", "Администратор")
                    : appText("Пассажир", "Пассажир")}
              </span>
              {user.rating != null && (
                <span className="profile-card__rating">
                  <YuStar size={14} className="star" /> {user.rating.toFixed(1)}
                </span>
              )}
            </div>
            <div className="profile-card__meta">
              <IconPin size={15} />
              <span className={"profile-card__city" + (user.city ? "" : " is-empty")}>
                {user.city || appText("Указать город", "Ҡаланы күрһәтергә")}
              </span>
            </div>
            <div className="profile-card__note">
              {appText("Телефон скрыт до подтверждения поездки", "Телефон сәфәр раҫланғанға тиклем йәшерелгән")}
            </div>
          </div>
        </button>
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

      {/* Реферал под шапкой — как в приложении: «Позови своего» с кодом и бонусами. */}
      {isAuthed && <ReferralCard />}

      {(() => {
        const byKey = Object.fromEntries(rows.map((r) => [r.key, r]));
        const visible = (r: (typeof rows)[number] | undefined) =>
          !!r && (!r.authed || isAuthed) && (!r.admin || user?.role === "admin");
        // Разделы, как в настройках iOS: тихие подписи + сгруппированные карточки.
        const sections: { label: string; keys: string[] }[] = [
          { label: appText("Управление", "Идара итеү"), keys: ["admin"] },
          { label: appText("Аккаунт", "Иҫәп"), keys: ["settings", "notifications", "my-data"] },
          {
            label: appText("Заказать поездку", "Сәфәр заказ итеү"),
            keys: ["taxi", "parcels", "scheduled", "clinics", "family-order", "voice", "callback"],
          },
          {
            label: appText("Мои поездки", "Сәфәрҙәрем"),
            keys: ["cabinet", "watch", "filters", "places", "stats"],
          },
          {
            label: appText("Заработок", "Табыш"),
            keys: ["driver", "taxi-drive", "courier", "wallet"],
          },
          {
            label: appText("Выгода и бизнес", "Файҙа һәм бизнес"),
            keys: ["coupons", "promo", "invites", "partner", "ads", "payment-methods", "payment-info"],
          },
          {
            label: appText("Безопасность и доверие", "Именлек һәм ышаныс"),
            keys: ["sos", "safety", "trusted", "trust", "fairness"],
          },
          {
            label: appText("Приложение", "Ҡушымта"),
            keys: ["simple", "consents", "help", "support", "app-review"],
          },
        ];
        return sections.map((sec) => {
          const items = sec.keys.map((k) => byKey[k]).filter(visible);
          if (!items.length) return null;
          return (
            <section key={sec.label} className="list-group">
              <div className="list-group__label">{sec.label}</div>
              <div className="list">
                {items.map((r) => (
                  <button
                    key={r.key}
                    type="button"
                    className="list-row list-row--link"
                    onClick={() => navigate(r.to)}
                  >
                    <span className="list-row__icon">{r.icon}</span>
                    <div className="list-row__main">
                      <div className="list-row__title">{r.title}</div>
                      <div className="list-row__sub">{r.sub}</div>
                    </div>
                    {r.badge ? (
                      <span
                        className="list-row__badge"
                        aria-label={appText(`${r.badge} новых`, `${r.badge} яңы`)}
                      >
                        {r.badge > 99 ? "99+" : r.badge}
                      </span>
                    ) : null}
                    <span className="list-row__chev">
                      <IconChevron size={20} />
                    </span>
                  </button>
                ))}
              </div>
            </section>
          );
        });
      })()}

      <PartnerAdSlot placement="profile" />

      {isAuthed && (
        <button type="button" className="logout-btn" onClick={() => void logout()}>
          <IconLogout size={20} />
          {appText("Выйти", "Сығырға")}
        </button>
      )}
    </>
  );
}
