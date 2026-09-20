// ================================================================
//  Кабинет админа → /admin (RequireAdmin).
//  Зеркало AdminCabinetScreen (SecondaryScreens.kt): вводная строка и три
//  группы SettingsGroup/SettingsNavRow в порядке приложения. Разделы, которых
//  в приложении нет (отзывы о приложении, жалобы на цену, фотоконтроль,
//  готовность, долги), — отдельной четвёртой группой, чтобы дороги к ним
//  не пропали при выравнивании. Двуязычно, токены Canon, тач-цели ≥48px.
// ================================================================
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { SubHeader } from "./ConsentsScreen";
import { SettingsGroup, SettingsNavRow } from "../components/cabinetUi";
import {
  IconRequest,
  IconChat,
  IconWheel,
  IconFlag,
  IconWallet,
  IconStar,
  IconRocket,
  IconCar,
  IconTrend,
  IconTicket,
  IconBox,
  IconWarn,
  IconShield,
  IconCheck,
  IconPhone,
  IconSignal,
  IconMegaphone,
  IconBlock,
  IconStore,
  IconRoute,
  IconCamera,
  IconReceipt,
} from "../components/Icons";

interface AdminLink {
  to: string;
  icon: JSX.Element;
  title: string;
  sub: string;
}

export default function AdminCabinetScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  // Группа 1 — заявка и отклики за человека без интернета.
  const manual: AdminLink[] = [
    {
      to: "/admin/request",
      icon: <IconPhone size={24} />,
      title: appText("Заявка за пользователя", "Ҡулланыусы өсөн заявка"),
      sub: appText("Создать заявку после «Попросить звонок»", "«Шылтыратыу һорау»ҙан һуң заявка булдырыу"),
    },
    {
      to: "/admin/responses",
      icon: <IconRequest size={24} />,
      title: appText("Отклики по заявке", "Заявка буйынса яуаптар"),
      sub: appText("Принять отклик за пользователя без интернета", "Интернетһыҙ ҡулланыусы өсөн яуап ҡабул итеү"),
    },
  ];

  // Группа 2 — модерация и безопасность, порядок как в приложении.
  const moderation: AdminLink[] = [
    {
      to: "/admin/drivers",
      icon: <IconWheel size={24} />,
      title: appText("Модерация водителей", "Йөрөтөүселәрҙе модерациялау"),
      sub: appText("Проверить права и фото, одобрить", "Права һәм фотоны тикшереп раҫлау"),
    },
    {
      to: "/admin/taxi",
      icon: <IconCar size={24} />,
      title: appText("Таксисты", "Таксистар"),
      sub: appText("Заявки 580-ФЗ и города, где включено такси", "580-ФЗ заявкалары һәм такси ҡабыҙылған ҡалалар"),
    },
    {
      to: "/admin/taxi-pulse",
      icon: <IconSignal size={24} />,
      title: appText("Пульс такси", "Такси пульсы"),
      sub: appText("На линии, активные заказы, счётчики дня по городам", "Линияла, актив заказдар, көн һандары ҡалалар буйынса"),
    },
    {
      to: "/admin/waitlist",
      icon: <IconMegaphone size={24} />,
      title: appText("Лист ожидания", "Көтөү исемлеге"),
      sub: appText("Ранний доступ: кто ждёт запуска, волны приглашений", "Иртә инеү: кем көтә, саҡырыу тулҡындары"),
    },
    {
      to: "/admin/reports",
      icon: <IconFlag size={24} />,
      title: appText("Жалобы", "Ялыуҙар"),
      sub: appText("Разобрать жалобы пользователей", "Ҡулланыусы ялыуҙарын тикшереү"),
    },
    {
      to: "/admin/text-flags",
      icon: <IconBlock size={24} />,
      title: appText("Помеченные тексты", "Билдәләнгән текстар"),
      sub: appText("Телефоны, мат и фишинг в открытых полях", "Асыҡ ҡырҙарҙа телефон, тупаҫлыҡ, фишинг"),
    },
    {
      to: "/admin/support",
      icon: <IconChat size={24} />,
      title: appText("Обращения в поддержку", "Ярҙамға мөрәжәғәттәр"),
      sub: appText("Ответить человеку и закрыть вопрос", "Кешегә яуап биреү һәм һорауҙы ябыу"),
    },
    {
      to: "/admin/ratings",
      icon: <IconStar size={24} />,
      title: appText("Отзывы на модерации", "Модерациялағы фекерҙәр"),
      sub: appText("Одобрить текст к показу в профиле", "Текстты профилдә күрһәтергә раҫлау"),
    },
    {
      to: "/admin/incidents",
      icon: <IconShield size={24} />,
      title: appText("Разбор споров", "Бәхәстәрҙе ҡарау"),
      sub: appText("Обе версии рядом, телефоны сторон, решение с объяснением", "Ике версия ҡатар, телефондар, аңлатмалы ҡарар"),
    },
    {
      to: "/admin/sos",
      icon: <IconWarn size={24} />,
      title: appText("Сигналы SOS", "SOS сигналдары"),
      sub: appText("Кто позвал на помощь: позвонить и отметить «принял»", "Кем ярҙам һораған: шылтыратып «ҡабул иттем» тип билдәләү"),
    },
    {
      to: "/admin/moderation",
      icon: <IconStore size={24} />,
      title: appText("Модерация витрины", "Витрина модерацияһы"),
      sub: appText("Что я ещё не смотрел: бизнесы и купоны", "Ҡарамағаным: бизнестар һәм купондар"),
    },
    {
      to: "/admin/partners",
      icon: <IconStore size={24} />,
      title: appText("Бизнесы-партнёры", "Партнёр-бизнестар"),
      sub: appText("Модерация: одобрить купонных партнёров", "Модерация: купон партнёрҙарын раҫлау"),
    },
    {
      to: "/admin/promo",
      icon: <IconTicket size={24} />,
      title: appText("Промокоды и кампании", "Промокодтар һәм акциялар"),
      sub: appText("Коды для блогеров и акций, статистика", "Блогерҙар һәм акциялар өсөн кодтар, статистика"),
    },
    {
      to: "/admin/parcels",
      icon: <IconBox size={24} />,
      title: appText("Посылки", "Бандеролдәр"),
      sub: appText("Доставки и собранный сбор", "Илтеүҙәр һәм йыйылған сбор"),
    },
    {
      to: "/admin/courier",
      icon: <IconRoute size={24} />,
      title: appText("Курьеры", "Курьерҙар"),
      sub: appText("Заявки курьеров: одобрить или отклонить", "Курьер заявкалары: раҫлау йәки кире ҡағыу"),
    },
  ];

  // Группа 3 — деньги и рост.
  const money: AdminLink[] = [
    {
      to: "/admin/payment-requests",
      icon: <IconWallet size={24} />,
      title: appText("Заявки на оплату", "Түләү заявкалары"),
      sub: appText("Подтвердить оплату буста и донаты", "Буст түләүен раҫлау һәм донаттар"),
    },
    {
      to: "/admin/ads",
      icon: <IconRocket size={24} />,
      title: appText("Реклама", "Реклама"),
      sub: appText("Объявления, erid, показы и клики", "Иғландар, erid, күрһәтеү һәм баҫыу"),
    },
    {
      to: "/admin/income",
      icon: <IconTrend size={24} />,
      title: appText("Калькулятор дохода", "Килем калькуляторы"),
      sub: appText("Прикинь месячную выручку и «чистыми» по маршруту", "Маршрут буйынса айлыҡ килемде һәм таҙаһын самала"),
    },
  ];

  // Есть только в вебе: отзывы о приложении, жалобы на цену, фотоконтроль, готовность, долги.
  const webOnly: AdminLink[] = [
    {
      to: "/admin/reviews",
      icon: <IconStar size={24} />,
      title: appText("Отзывы о приложении", "Ҡушымта тураһында фекерҙәр"),
      sub: appText("Отзывы для витрины Юлдаша", "Юлдаш витринаһы өсөн фекерҙәр"),
    },
    {
      to: "/admin/price-complaints",
      icon: <IconWallet size={24} />,
      title: appText("Жалобы на цену", "Хаҡҡа зарлар"),
      sub: appText("На какой сумме люди отваливаются", "Кешеләр ниндәй суммала китә"),
    },
    {
      to: "/admin/car-photo",
      icon: <IconCamera size={24} />,
      title: appText("Фотоконтроль машин", "Машина фотоконтроле"),
      sub: appText("Снимки на просмотр (580-ФЗ)", "Ҡарау өсөн рәсемдәр (580-ФЗ)"),
    },
    {
      to: "/admin/pretrip",
      icon: <IconCheck size={24} />,
      title: appText("Готовность к работе", "Эшкә әҙерлек"),
      sub: appText("Журнал предрейсовых отметок (580-ФЗ)", "Рейс алды билдәләр журналы"),
    },
    {
      to: "/admin/debts",
      icon: <IconReceipt size={24} />,
      title: appText("Долги по комиссии", "Комиссия бурыстары"),
      sub: appText("Подтвердить, отклонить или списать", "Раҫлау, кире ҡағыу йәки алып ташлау"),
    },
  ];

  const group = (items: AdminLink[]) => (
    <SettingsGroup>
      {items.map((r) => (
        <SettingsNavRow key={r.to} icon={r.icon} title={r.title} subtitle={r.sub} onClick={() => navigate(r.to)} />
      ))}
    </SettingsGroup>
  );

  return (
    <>
      <SubHeader title={appText("Кабинет админа", "Админ кабинеты")} onBack={() => navigate(-1)} />
      <div className="cabinet">
        <p className="dl-hint">
          {appText(
            "Единый центр управления Юлдашем. Виден только администратору.",
            "Юлдашты идара итеү үҙәге. Тик админға күренә."
          )}
        </p>
        {group(manual)}
        {group(moderation)}
        {group(money)}
        {group(webOnly)}
      </div>
    </>
  );
}
