// ================================================================
//  Онбординг первого запуска — зеркало Android OnboardingContent (YuldashApp.kt):
//  четыре слайда (маршрут · безопасность · заявка · старт), у каждого — геро-карточка с фото,
//  стеклянной плашкой «Юлдаш + подпись» и иконкой, заголовок 34/40, текст, карточки-шаги с
//  нумерованным кругом, мятная заметка; на последнем — выбор роли и карточка простого режима.
//  Внизу: «Назад» · точки · «Пропустить» и зелёная «Далее» / «Войти через Telegram».
//  Слова и порядок — те же, что в приложении (onboardingSlides()).
// ================================================================
import { useEffect, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { flags, type Role } from "../flags";
import { useLang } from "../i18n/lang";
import { track, trackOnce } from "../analytics";
import {
  IconCar,
  IconChat,
  IconLock,
  IconMic,
  IconPencil,
  IconPin,
  IconRocket,
  IconRoute,
  IconSearch,
  IconShare,
  IconShield,
  IconTicket,
  IconWarn,
} from "../components/Icons";

interface SlideItem {
  icon: ReactNode;
  title: string;
  body: string;
}

interface Slide {
  eyebrow: string;
  title: string;
  body: string;
  hero: ReactNode;
  items: SlideItem[];
  note?: string;
}

export default function OnboardingScreen() {
  const navigate = useNavigate();
  const { lang, setLang, appText } = useLang();
  const [step, setStep] = useState(0);
  const [role, setRole] = useState<Role>(flags.role());
  const [simpleOffer, setSimpleOffer] = useState(true);

  const slides: Slide[] = [
    {
      eyebrow: appText("Дорога по Башкортостану", "Башҡортостан буйлап юл"),
      title: appText("Едешь с юлдашом, не с незнакомцем", "Ят кеше менән түгел, юлдаш менән бараһың"),
      body: appText(
        "Поездки и заявки между своими: Баймак, Сибай, Уфа и другие привычные маршруты рядом.",
        "Үҙ кешеләр араһында сәфәрҙәр һәм заявкалар: Баймаҡ, Сибай, Өфө һәм яҡын маршруттар."
      ),
      hero: <IconPin size={38} />,
      items: [
        { icon: <IconSearch size={30} />, title: appText("Нашёл маршрут", "Маршрут таптың"), body: appText("Смотри ближайшие поездки или оставь заявку, если машины ещё нет.", "Яҡындағы сәфәрҙәрҙе ҡара йәки машина юҡ икән заявка ҡалдыр.") },
        { icon: <IconChat size={30} />, title: appText("Договорился в чате", "Чатта килештең"), body: appText("После отклика можно спокойно уточнить место, время и багаж.", "Яуаптан һуң урын, ваҡыт һәм багаж тураһында һөйләшергә була.") },
        { icon: <IconCar size={30} />, title: appText("Поехал спокойно", "Тыныс юлға сыҡтың"), body: appText("Важные детали поездки остаются внутри приложения.", "Сәфәрҙең мөһим деталдәре ҡушымта эсендә ҡала.") },
      ],
    },
    {
      eyebrow: appText("Доверие важнее скорости", "Ышаныс тиҙлектән мөһимерәк"),
      title: appText("Безопасность перед дорогой", "Юл алдынан хәүефһеҙлек"),
      body: appText(
        "Водитель может пройти проверку, номер не раскрывается заранее, а в поездке есть SOS и связь с близкими.",
        "Йөрөтөүсе тикшереү үтә ала, номер алдан асылмай, ә сәфәрҙә SOS һәм яҡындар менән бәйләнеш бар."
      ),
      hero: <IconShield size={38} />,
      items: [
        { icon: <IconShield size={30} />, title: appText("Проверка водителя", "Йөрөтөүсене тикшереү"), body: appText("Профиль водителя и фото машины уходят на модерацию.", "Йөрөтөүсе профиле һәм машина фотоһы модерацияға китә.") },
        { icon: <IconLock size={30} />, title: appText("Номер скрыт", "Номер йәшерелгән"), body: appText("Контакты открываются только после подтверждения поездки.", "Контакттар сәфәр раҫланғандан һуң ғына асыла.") },
        { icon: <IconWarn size={30} />, title: appText("SOS рядом", "SOS яҡында"), body: appText("В экстренной ситуации можно быстро отправить сигнал помощи.", "Ашығыс хәлдә ярҙам сигналы ебәрергә була.") },
        { icon: <IconShare size={30} />, title: appText("Близкий видит поездку", "Яҡының сәфәрҙе күрә"), body: appText("Поделись маршрутом — родной человек на связи всю дорогу.", "Маршрут менән бүлеш — яҡының юл буйы бәйләнештә.") },
      ],
    },
    {
      eyebrow: appText("Когда машины ещё нет", "Машина әле юҡ икән"),
      title: appText("Заявка не пропадает в пустоту", "Заявка бушҡа юғалмай"),
      body: appText(
        "Пассажир оставляет маршрут, водитель видит заявку, откликается, а после принятия появляется поездка с чатом.",
        "Пассажир маршрут ҡалдыра, йөрөтөүсе заявканы күрә, яуап бирә, ҡабул иткәс чатлы сәфәр асыла."
      ),
      hero: <IconRoute size={38} />,
      items: [
        { icon: <IconPencil size={30} />, title: appText("Создай заявку", "Заявка булдыр"), body: appText("Укажи маршрут, время и что важно в дороге.", "Маршрутты, ваҡытты һәм юлдағы мөһим шарттарҙы күрһәт.") },
        { icon: <IconChat size={30} />, title: appText("Водитель откликнется", "Йөрөтөүсе яуап бирер"), body: appText("Отклики приходят к пассажиру, можно выбрать подходящий вариант.", "Яуаптар пассажирға килә, уңайлы вариантты һайларға була.") },
        { icon: <IconTicket size={30} />, title: appText("Код посадки", "Ултырыу коды"), body: appText("Подтверждённая поездка получает чат и код посадки.", "Раҫланған сәфәрҙә чат һәм ултырыу коды була.") },
      ],
      note: appText("Так закрывается путь: заявка → отклик → поездка", "Шулай юл ябыла: заявка → яуап → сәфәр"),
    },
    {
      eyebrow: appText("Готово к первой поездке", "Беренсе сәфәргә әҙер"),
      title: appText("Начнём?", "Башлайбыҙмы?"),
      body: appText(
        "Войди через Telegram: бот пришлёт код, а Юлдаш откроет карту, заявки, чат и профиль.",
        "Telegram аша ин: бот код ебәрер, ә Юлдаш карта, заявкалар, чат һәм профилде асыр."
      ),
      hero: <IconRocket size={38} />,
      items: [],
    },
  ];
  const last = step === slides.length - 1;
  const slide = slides[step];

  // Начало онбординга — вход в воронку первого запуска. Раз за сессию.
  useEffect(() => {
    trackOnce("onboarding_start", "onboarding_start");
  }, []);

  /** Онбординг пройден: роль и флаг — как в приложении, дальше вход или карта. */
  function finish(to: string, simple = false) {
    track("onboarding_complete");
    if (simple) track("onboarding_simple_mode");
    track("onboarding_done", { chosen_role: role, simple });
    flags.setRole(role);
    flags.setSimpleMode(simple);
    flags.setOnboarded();
    navigate(to, { replace: true });
  }

  const langChip = (code: "ru" | "ba", label: string) => (
    <button
      type="button"
      className={"login-lang__chip" + (lang === code ? " is-active" : "")}
      onClick={() => setLang(code)}
      aria-label={appText(`Сменить язык: ${label}`, `Телде алмаштырыу: ${label}`)}
    >
      {label}
    </button>
  );

  return (
    <div className="onb">
      <div className="onb__top">
        <div className="login-lang onb__lang" role="group" aria-label={appText("Язык", "Тел")}>
          {langChip("ru", "РУС")}
          {langChip("ba", "БАШ")}
        </div>
      </div>

      <div className="onb__page" key={step}>
        {/* OnboardingHeroCard: фото 318 с градиентами, плитка-логотип, стеклянная плашка, круг с иконкой. */}
        <section className="onb-hero">
          <img className="onb-hero__photo" src="/onboarding_bashkir_hero.webp" alt="" aria-hidden />
          <span className="onb-hero__shade" aria-hidden />
          <span className="onb-hero__logo" aria-hidden><img src="/yuldash_logo.webp" alt="" /></span>
          <span className="onb-hero__glass">
            <strong>Юлдаш</strong>
            <small>{slide.eyebrow}</small>
          </span>
          <span className="onb-hero__icon" aria-hidden>{slide.hero}</span>
        </section>

        <h1 className="onb__title">{slide.title}</h1>
        <p className="onb__body">{slide.body}</p>

        {!last &&
          slide.items.map((it, i) => (
            <div key={it.title} className="onb-item" style={{ animationDelay: `${(i + 2) * 60}ms` }}>
              <span className="onb-bubble" aria-hidden>
                {it.icon}
                <b>{i + 1}</b>
              </span>
              <span className="onb-item__text">
                <strong>{it.title}</strong>
                <small>{it.body}</small>
              </span>
            </div>
          ))}

        {last && (
          <>
            {/* OnboardingRoleChooser: две карточки с радио справа; выбранная — мятная. */}
            <div className="onb-roles" role="radiogroup" aria-label={appText("Кто ты в поездке", "Сәфәрҙә һин кем")}>
              {(
                [
                  { r: "passenger" as Role, icon: <IconSearch size={30} />, t: appText("Я пассажир", "Мин пассажир"), s: appText("Ищу поездки, создаю заявки и общаюсь с водителями.", "Сәфәр эҙләйем, заявка булдырам һәм йөрөтөүселәр менән һөйләшәм.") },
                  { r: "driver" as Role, icon: <IconCar size={30} />, t: appText("Я водитель", "Мин йөрөтөүсе"), s: appText("Публикую поездки, откликаюсь на заявки и прохожу проверку.", "Сәфәрҙәр ҡуям, заявкаларға яуап бирәм һәм тикшереү үтәм.") },
                ]
              ).map(({ r, icon, t, s }) => (
                <button
                  key={r}
                  type="button"
                  role="radio"
                  aria-checked={role === r}
                  className={"onb-role" + (role === r ? " is-active" : "")}
                  onClick={() => setRole(r)}
                >
                  <span className="onb-bubble" aria-hidden>{icon}</span>
                  <span className="onb-item__text">
                    <strong>{t}</strong>
                    <small>{s}</small>
                  </span>
                  <span className="onb-role__radio" aria-hidden />
                </button>
              ))}
            </div>

            {/* OnboardingSimpleModeCard: крупные кнопки и голос — предложение, а не тумблер. */}
            {simpleOffer && (
              <section className="onb-simple">
                <div className="onb-simple__head">
                  <span className="onb-simple__icon" aria-hidden><IconMic size={24} /></span>
                  <strong>{appText("Тебе удобнее крупные кнопки и голосовой заказ?", "Һиңә эре төймәләр һәм тауыш менән заказ уңайлыраҡмы?")}</strong>
                </div>
                <p>
                  {appText(
                    "Простой режим: крупный шрифт, меньше деталей, заказ голосом. Включить можно и позже — в профиле.",
                    "Ябай режим: эре хәреф, кәм деталь, тауыш менән заказ. Һуңынан да ҡабыҙып була — профилдә."
                  )}
                </p>
                <button type="button" className="btn-primary onb-simple__on" onClick={() => finish("/simple", true)}>
                  <IconMic size={20} /> {appText("Включить простой режим", "Ябай режимды тоҡандырыу")}
                </button>
                <button type="button" className="btn-ghost onb-simple__later" onClick={() => setSimpleOffer(false)}>
                  {appText("Не сейчас", "Хәҙер түгел")}
                </button>
              </section>
            )}
          </>
        )}

        {slide.note && (
          <div className="onb-note">
            <IconLock size={22} />
            <span>{slide.note}</span>
          </div>
        )}
      </div>

      <div className="onb__foot">
        <div className="onb__nav">
          {step > 0 ? (
            <button type="button" className="btn-ghost onb__nav-btn" onClick={() => setStep((s) => s - 1)}>
              {appText("Назад", "Артҡа")}
            </button>
          ) : (
            <span className="onb__nav-btn" />
          )}
          <div className="onb-dots" aria-hidden>
            {slides.map((_, i) => (
              <span key={i} className={i === step ? "is-active" : ""} />
            ))}
          </div>
          <button type="button" className="btn-ghost onb__nav-btn" onClick={() => finish("/map")}>
            {appText("Пропустить", "Үткәреп ебәреү")}
          </button>
        </div>
        <button
          type="button"
          className="btn-primary onb__next"
          onClick={() => (last ? finish("/login") : setStep((s) => s + 1))}
        >
          {last ? appText("Войти через Telegram", "Telegram аша инеү") : appText("Далее", "Артабан")}
        </button>
      </div>
    </div>
  );
}
