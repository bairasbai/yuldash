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
  IconWarn,
  IconShield,
  IconCheck,
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
      // Первым в списке намеренно: сигнал о помощи не должен ждать своей очереди.
      key: "sos",
      to: "/admin/sos",
      icon: <IconWarn size={22} />,
      title: appText("Сигналы SOS", "SOS сигналдары"),
      sub: appText("Кто просит помощи прямо сейчас", "Кем хәҙер ярҙам һорай"),
    },
    {
      key: "incidents",
      to: "/admin/incidents",
      icon: <IconShield size={22} />,
      title: appText("Разбор споров", "Бәхәстәрҙе ҡарау"),
      sub: appText("Обе версии, решение с объяснением", "Ике версия, аңлатмалы ҡарар"),
    },
    {
      key: "moderation",
      to: "/admin/moderation",
      icon: <IconCheck size={22} />,
      title: appText("Очередь модерации", "Модерация сираты"),
      sub: appText("Бизнесы и купоны без решения", "Ҡарарһыҙ бизнестар һәм купондар"),
    },
    {
      key: "text-flags",
      to: "/admin/text-flags",
      icon: <IconFlag size={22} />,
      title: appText("Помеченные тексты", "Билдәләнгән текстар"),
      sub: appText("Фишинг, увод контакта, грубость", "Фишинг, контакт алыу, тупаҫлыҡ"),
    },
    {
      key: "price-complaints",
      to: "/admin/price-complaints",
      icon: <IconWallet size={22} />,
      title: appText("Жалобы на цену", "Хаҡҡа зарлар"),
      sub: appText("На какой сумме люди отваливаются", "Кешеләр ниндәй суммала китә"),
    },
    {
      key: "car-photo",
      to: "/admin/car-photo",
      icon: <IconCar size={22} />,
      title: appText("Фотоконтроль машин", "Машина фотоконтроле"),
      sub: appText("Снимки на просмотр (580-ФЗ)", "Ҡарау өсөн рәсемдәр (580-ФЗ)"),
    },
    {
      key: "pretrip",
      to: "/admin/pretrip",
      icon: <IconCheck size={22} />,
      title: appText("Готовность к работе", "Эшкә әҙерлек"),
      sub: appText("Журнал предрейсовых отметок (580-ФЗ)", "Рейс алды билдәләр журналы"),
    },
    {
      key: "debts",
      to: "/admin/debts",
      icon: <IconWallet size={22} />,
      title: appText("Долги по комиссии", "Комиссия бурыстары"),
      sub: appText("Подтвердить, отклонить или списать", "Раҫлау, кире ҡағыу йәки алып ташлау"),
    },
    {
      key: "support",
      to: "/admin/support",
      icon: <IconChat size={22} />,
      title: appText("Обращения в поддержку", "Ярҙамға мөрәжәғәттәр"),
      sub: appText("Ответить человеку — ответ уйдёт сразу", "Кешегә яуап — шунда уҡ бара"),
    },
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
      sub: appText("Выбрать водителя по заявке", "Заявка буйынса йөрөтөүсе һайлау"),
    },
    {
      key: "drivers",
      to: "/admin/drivers",
      icon: <IconWheel size={22} />,
      title: appText("Модерация водителей", "Йөрөтөүселәрҙе тикшереү"),
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
      key: "ratings",
      to: "/admin/ratings",
      icon: <IconStar size={22} />,
      title: appText("Отзывы о поездках", "Сәфәрҙәр тураһында фекерҙәр"),
      sub: appText("Публикация и защита рейтинга", "Баҫтырыу һәм рейтингты һаҡлау"),
    },
    {
      key: "reviews",
      to: "/admin/reviews",
      icon: <IconStar size={22} />,
      title: appText("Отзывы о приложении", "Ҡушымта тураһында фекерҙәр"),
      sub: appText("Отзывы для витрины Юлдаша", "Юлдаш витринаһы өсөн фекерҙәр"),
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
