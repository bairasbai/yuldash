// ================================================================
//  Кабинет админа → /admin (RequireAdmin).
//  Хаб-меню всех админ-разделов. Волны 8А/8Б/8В реализованы — вся админка
//  активна (модерация + рост + бизнесы/промо/посылки/курьеры).
//  Двуязычно, токены Canon, тач-цели ≥48px.
// ================================================================
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { SubHeader } from "./ConsentsScreen";
import {
  IconChevron,
  IconRequest,
  IconChat,
  IconWheel,
  IconFlag,
  IconWallet,
  IconStar,
  IconRocket,
  IconCar,
  IconClock,
  IconTrend,
  IconWork,
  IconGift,
  IconBox,
} from "../components/Icons";

interface AdminLink {
  key: string;
  to?: string; // есть → активен; нет → «скоро»
  icon: JSX.Element;
  title: string;
  sub: string;
}

export default function AdminCabinetScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  // Волна 8А — ядро модерации (реализовано).
  const ready: AdminLink[] = [
    {
      key: "request",
      to: "/admin/request",
      icon: <IconRequest size={22} />,
      title: appText("Заявка за юзера", "Юзер өсөн заявка"),
      sub: appText("Оформить поездку по телефону", "Телефон буйынса сәфәр рәтләү"),
    },
    {
      key: "responses",
      to: "/admin/responses",
      icon: <IconChat size={22} />,
      title: appText("Принять отклик за юзера", "Юзер өсөн яуап ҡабул итеү"),
      sub: appText("Выбрать водителя по заявке", "Заявка буйынса водитель һайлау"),
    },
    {
      key: "drivers",
      to: "/admin/drivers",
      icon: <IconWheel size={22} />,
      title: appText("Модерация водителей", "Водителдәрҙе тикшереү"),
      sub: appText("Права, авто — одобрить/отклонить", "Права, авто — раҫлау/кире ҡағыу"),
    },
    {
      key: "reports",
      to: "/admin/reports",
      icon: <IconFlag size={22} />,
      title: appText("Жалобы", "Зарланыуҙар"),
      sub: appText("Разбор и меры", "Тикшереү һәм саралар"),
    },
    {
      key: "payments",
      to: "/admin/payment-requests",
      icon: <IconWallet size={22} />,
      title: appText("Заявки на оплату", "Түләү заявкалары"),
      sub: appText("Подтвердить переводы (СБП)", "Күсереүҙәрҙе раҫлау (СБП)"),
    },
    // Волна 8Б — отзывы, реклама, такси, лист ожидания, пульс, доход.
    {
      key: "reviews",
      to: "/admin/reviews",
      icon: <IconStar size={22} />,
      title: appText("Отзывы", "Фекерҙәр"),
      sub: appText("Модерация текстов отзывов", "Фекер текстарын тикшереү"),
    },
    {
      key: "ads",
      to: "/admin/ads",
      icon: <IconRocket size={22} />,
      title: appText("Реклама", "Реклама"),
      sub: appText("Модерация объявлений партнёров", "Партнёр иғландарын тикшереү"),
    },
    {
      key: "taxi",
      to: "/admin/taxi",
      icon: <IconCar size={22} />,
      title: appText("Такси", "Такси"),
      sub: appText("Заявки таксистов и города", "Такси заявкалары һәм ҡалалар"),
    },
    {
      key: "waitlist",
      to: "/admin/waitlist",
      icon: <IconClock size={22} />,
      title: appText("Лист ожидания", "Көтөү исемлеге"),
      sub: appText("Ранний доступ и волны", "Иртә инеү һәм тулҡындар"),
    },
    {
      key: "pulse",
      to: "/admin/taxi-pulse",
      icon: <IconTrend size={22} />,
      title: appText("Пульс такси", "Такси тибеше"),
      sub: appText("Спрос и предложение вживую", "Ихтыяж һәм тәҡдим тере"),
    },
    {
      key: "income",
      to: "/admin/income",
      icon: <IconWallet size={22} />,
      title: appText("Калькулятор дохода", "Килем калькуляторы"),
      sub: appText("Прогноз выручки автора", "Автор килеме фаразы"),
    },
    // Волна 8В — бизнесы, промо, посылки, курьеры (завершает админку).
    {
      key: "business",
      to: "/admin/partners",
      icon: <IconWork size={22} />,
      title: appText("Бизнесы", "Бизнестар"),
      sub: appText("Модерация партнёрских компаний", "Партнёр компанияларын тикшереү"),
    },
    {
      key: "promo",
      to: "/admin/promo",
      icon: <IconGift size={22} />,
      title: appText("Промокоды и кампании", "Промокодтар һәм кампаниялар"),
      sub: appText("Блогеры, партнёры, акции", "Блогерҙар, партнёрҙар, акциялар"),
    },
    {
      key: "parcels",
      to: "/admin/parcels",
      icon: <IconBox size={22} />,
      title: appText("Доставки посылок", "Бандероль доставкалары"),
      sub: appText("Контроль и доход", "Контроль һәм килем"),
    },
    {
      key: "couriers",
      to: "/admin/courier",
      icon: <IconBox size={22} />,
      title: appText("Заявки курьеров", "Курьер заявкалары"),
      sub: appText("Проверка и одобрение", "Тикшереү һәм хуплау"),
    },
  ];

  return (
    <>
      <SubHeader
        title={appText("Кабинет админа", "Админ кабинеты")}
        subtitle={appText("Модерация и управление", "Тикшереү һәм идара итеү")}
        onBack={() => navigate(-1)}
      />

      <h2 className="section-title">{appText("Модерация", "Тикшереү")}</h2>
      <div className="list">
        {ready.map((r) => (
          <button
            key={r.key}
            type="button"
            className="list-row list-row--link"
            onClick={() => navigate(r.to!)}
          >
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
    </>
  );
}
