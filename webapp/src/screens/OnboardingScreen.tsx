// ================================================================
//  Онбординг первого запуска — зеркало Android OnboardingContent (YuldashApp.kt):
//  четыре слайда (маршрут · безопасность · заявка · старт), у каждого — геро-карточка с фото,
//  стеклянной плашкой «Юлдаш + подпись» и иконкой, заголовок 34/40, текст, карточки-шаги с
//  нумерованным кругом, мятная заметка; на последнем — выбор роли и карточка простого режима.
//  Внизу: «Назад» · точки · «Пропустить» и зелёная «Далее» / «Войти через Telegram».
//  Слова и порядок — те же, что в приложении (onboardingSlides()).
// ================================================================
import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { flags, type Role } from "../flags";
import { useLang } from "../i18n/lang";
import { useFontScale } from "../fontScale";
import { track, trackOnce } from "../analytics";
import { createOnboardingPagerController, createPagerFrameGate, enableSimpleOnboarding, finishOnboarding } from "../utils/onboardingFlow";
import {
  IconChatBubbleOutline, IconDirectionsCar, IconEditNote, IconHandshake,
  IconLock, IconMap, IconMoneyOff, IconNearMe, IconPerson, IconPin,
  IconQuestionAnswer, IconRocketLaunch, IconRoute, IconSearch, IconShare,
  IconShield, IconSOS, IconVerified, IconVisibilityOff, IconVolumeUp,
} from "../components/OnboardingIcons";

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
  const [fontScale] = useFontScale();
  const [documentZoom, setDocumentZoom] = useState(() => {
    if (typeof document === "undefined") return 1;
    const zoom = Number.parseFloat(getComputedStyle(document.documentElement).zoom);
    return Number.isFinite(zoom) && zoom > 0 ? zoom : 1;
  });
  const [pixelRatio, setPixelRatio] = useState(() => typeof window === "undefined" ? 1 : window.devicePixelRatio || 1);
  const [step, setStep] = useState(0);
  const [visited, setVisited] = useState([true, false, false, false]);
  const [role, setRole] = useState<Role>("passenger");
  const [simpleOffer, setSimpleOffer] = useState(true);
  const pagerRef = useRef<HTMLDivElement>(null);
  const panelsRef = useRef<(HTMLDivElement | null)[]>([]);
  const frameGateRef = useRef<ReturnType<typeof createPagerFrameGate> | null>(null);
  const controllerRef = useRef<ReturnType<typeof createOnboardingPagerController> | null>(null);
  if (!controllerRef.current) {
    controllerRef.current = createOnboardingPagerController(4, (next, old) => {
      const focused = document.activeElement;
      if (focused && panelsRef.current[old]?.contains(focused)) pagerRef.current?.focus({ preventScroll: true });
      setStep(next);
      setVisited((prior) => prior[next] ? prior : prior.map((seen, index) => seen || index === next));
    }, (position) => {
      const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
      const ratio = window.devicePixelRatio || 1;
      panelsRef.current.forEach((panel, index) => panel?.style.setProperty("--onb-parallax", reduced ? "0px" : `${(position - index) / ratio}px`));
    });
  }
  if (!frameGateRef.current) {
    frameGateRef.current = createPagerFrameGate(
      (callback) => window.requestAnimationFrame(callback),
      (frame) => window.cancelAnimationFrame(frame),
      () => {
        const pager = pagerRef.current;
        if (pager) controllerRef.current?.onScroll(pager.scrollLeft, pager.clientWidth);
      }
    );
  }

  useLayoutEffect(() => {
    const syncZoom = () => {
      const zoom = Number.parseFloat(getComputedStyle(document.documentElement).zoom);
      setDocumentZoom(Number.isFinite(zoom) && zoom > 0 ? zoom : 1);
      setPixelRatio(window.devicePixelRatio || 1);
    };
    syncZoom();
    window.addEventListener("resize", syncZoom);
    return () => window.removeEventListener("resize", syncZoom);
  }, [fontScale]);

  useLayoutEffect(() => {
    const pager = pagerRef.current;
    if (!pager) return;
    const align = () => {
      pager.scrollTo({ left: controllerRef.current!.align(pager.clientWidth), behavior: "auto" });
      controllerRef.current!.onScroll(pager.scrollLeft, pager.clientWidth);
    };
    const observer = new ResizeObserver(align);
    observer.observe(pager);
    pager.addEventListener("scrollend", settle);
    function settle() { controllerRef.current?.settle(pager!.scrollLeft, pager!.clientWidth); }
    align();
    return () => {
      observer.disconnect();
      pager.removeEventListener("scrollend", settle);
      frameGateRef.current?.cancel();
    };
  }, [documentZoom]);

  const slides: Slide[] = [
    {
      eyebrow: appText("Дорога по Башкортостану", "Башҡортостан буйлап юл"),
      title: appText("Едешь с юлдашом, не с незнакомцем", "Ят кеше менән түгел, юлдаш менән бараһың"),
      body: appText(
        "Поездки и заявки между своими: Баймак, Сибай, Уфа и другие привычные маршруты рядом.",
        "Үҙ кешеләр араһында сәфәрҙәр һәм заявкалар: Баймаҡ, Сибай, Өфө һәм яҡын маршруттар."
      ),
      hero: <IconNearMe size={38} />,
      items: [
        { icon: <IconSearch size={30} />, title: appText("Нашёл маршрут", "Маршрут таптың"), body: appText("Смотри ближайшие поездки или оставь заявку, если машины ещё нет.", "Яҡындағы сәфәрҙәрҙе ҡара йәки машина юҡ икән заявка ҡалдыр.") },
        { icon: <IconChatBubbleOutline size={30} />, title: appText("Договорился в чате", "Чатта килештең"), body: appText("После отклика можно спокойно уточнить место, время и багаж.", "Яуаптан һуң урын, ваҡыт һәм багаж тураһында һөйләшергә була.") },
        { icon: <IconDirectionsCar size={30} />, title: appText("Поехал спокойно", "Тыныс юлға сыҡтың"), body: appText("Важные детали поездки остаются внутри приложения.", "Сәфәрҙең мөһим деталдәре ҡушымта эсендә ҡала.") },
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
        { icon: <IconVerified size={30} />, title: appText("Проверка водителя", "Йөрөтөүсене тикшереү"), body: appText("Профиль водителя и фото машины уходят на модерацию.", "Йөрөтөүсе профиле һәм машина фотоһы модерацияға китә.") },
        { icon: <IconVisibilityOff size={30} />, title: appText("Номер скрыт", "Номер йәшерелгән"), body: appText("Контакты открываются только после подтверждения поездки.", "Контакттар сәфәр раҫланғандан һуң ғына асыла.") },
        { icon: <IconSOS size={30} />, title: appText("SOS рядом", "SOS яҡында"), body: appText("В экстренной ситуации можно быстро отправить сигнал помощи.", "Ашығыс хәлдә ярҙам сигналы ебәрергә була.") },
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
        { icon: <IconEditNote size={30} />, title: appText("Создай заявку", "Заявка булдыр"), body: appText("Укажи маршрут, время и что важно в дороге.", "Маршрутты, ваҡытты һәм юлдағы мөһим шарттарҙы күрһәт.") },
        { icon: <IconQuestionAnswer size={30} />, title: appText("Водитель откликнется", "Йөрөтөүсе яуап бирер"), body: appText("Отклики приходят к пассажиру, можно выбрать подходящий вариант.", "Яуаптар пассажирға килә, уңайлы вариантты һайларға була.") },
        { icon: <IconPin size={30} />, title: appText("Код посадки", "Ултырыу коды"), body: appText("Подтверждённая поездка получает чат и код посадки.", "Раҫланған сәфәрҙә чат һәм ултырыу коды була.") },
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
      hero: <IconRocketLaunch size={38} />,
      items: [],
    },
  ];
  const last = step === slides.length - 1;
  const slide = slides[step];

  // Начало онбординга — вход в воронку первого запуска. Раз за сессию.
  useEffect(() => {
    trackOnce("onboarding_start", "onboarding_start");
  }, []);

  const finished = useRef(false);
  function finish(simple = false) {
    if (finished.current) return;
    finished.current = true;
    if (simple) enableSimpleOnboarding(flags, track, navigate);
    else finishOnboarding(role, flags, track, navigate);
  }

  function observeScroll() {
    frameGateRef.current?.schedule();
  }

  function movePage(delta: number) {
    const pager = pagerRef.current;
    if (!pager) return;
    const target = controllerRef.current!.requestDelta(delta);
    pager.scrollTo({ left: target * pager.clientWidth, behavior: window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
  }

  function onPageKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.target !== event.currentTarget) return;
    if (event.key === "ArrowRight") { event.preventDefault(); movePage(1); }
    if (event.key === "ArrowLeft") { event.preventDefault(); movePage(-1); }
  }

  function onRoleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (["ArrowDown", "ArrowRight", "ArrowUp", "ArrowLeft", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      const next = event.key === "ArrowDown" || event.key === "ArrowRight" || event.key === "End" ? "driver" : "passenger";
      setRole(next);
      event.currentTarget.querySelector<HTMLButtonElement>(`[data-role="${next}"]`)?.focus();
    }
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
    <div className="onb" style={{ zoom: 1 / documentZoom, "--onb-font-scale": documentZoom, "--onb-appear-y": `${34 / pixelRatio}px` } as CSSProperties}>
      <div className="onb__top">
        <div className="login-lang onb__lang" role="group" aria-label={appText("Язык", "Тел")}>
          {langChip("ru", "РУС")}
          {langChip("ba", "БАШ")}
        </div>
      </div>

      <span className="onb__announce" role="status" aria-live="polite">{appText(`Шаг ${step + 1} из ${slides.length}: ${slide.title}`, `Аҙым ${step + 1} / ${slides.length}: ${slide.title}`)}</span>
      <div className="onb__pager" ref={pagerRef} tabIndex={0} aria-label={appText("Листайте влево или вправо для смены шага", "Аҙымды алыштырыу өсөн һулға йәки уңға күсерегеҙ")} onScroll={observeScroll} onPointerDown={() => { const pager = pagerRef.current; if (pager) controllerRef.current?.settle(pager.scrollLeft, pager.clientWidth); }} onKeyDown={onPageKeyDown}>
        {slides.map((slide, index) => {
          const active = index === step;
          const isFinal = index === slides.length - 1;
          const appear = visited[index] ? " onb-appear" : "";
          return <div className="onb__panel" key={index} ref={(node) => { panelsRef.current[index] = node; if (node) node.inert = !active; }} aria-hidden={!active}>
        <div className="onb__slide">
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

        <h1 className={"onb__title" + appear} style={{ "--onb-delay": 0 } as CSSProperties}>{slide.title}</h1>
        <p className={"onb__body" + appear} style={{ "--onb-delay": 1 } as CSSProperties}>{slide.body}</p>

        {!isFinal &&
          slide.items.map((it, i) => (
            <div key={it.title} className={"onb-item" + appear} style={{ "--onb-delay": i + 2 } as CSSProperties}>
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

        {isFinal && (
          <>
            {/* OnboardingRoleChooser: две карточки с радио справа; выбранная — мятная. */}
            <div className={"onb-roles" + appear} style={{ "--onb-delay": 2 } as CSSProperties} role="radiogroup" aria-label={appText("Кто ты в поездке", "Сәфәрҙә һин кем")} onKeyDown={onRoleKeyDown}>
              {(
                [
                  { r: "passenger" as Role, icon: <IconPerson size={30} />, t: appText("Я пассажир", "Мин пассажир"), s: appText("Ищу поездки, создаю заявки и общаюсь с водителями.", "Сәфәр эҙләйем, заявка булдырам һәм йөрөтөүселәр менән һөйләшәм.") },
                  { r: "driver" as Role, icon: <IconDirectionsCar size={30} />, t: appText("Я водитель", "Мин йөрөтөүсе"), s: appText("Публикую поездки, откликаюсь на заявки и прохожу проверку.", "Сәфәрҙәр ҡуям, заявкаларға яуап бирәм һәм тикшереү үтәм.") },
                ]
              ).map(({ r, icon, t, s }) => (
                <button
                  key={r}
                  data-role={r}
                  type="button"
                  role="radio"
                  aria-checked={role === r}
                  tabIndex={role === r ? 0 : -1}
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
              <div className="onb-trust" aria-label={appText("Между своими, без комиссии, весь Башкортостан", "Үҙ кеше араһында, комиссия юҡ, бөтә Башҡортостан")}>
                <span><IconHandshake size={24} />{appText("Между\nсвоими", "Үҙ кеше\nараһында")}</span>
                <span><IconMoneyOff size={24} />{appText("Без\nкомиссии", "Комиссия\nюҡ")}</span>
                <span><IconMap size={24} />{appText("Весь\nБашкортостан", "Бөтә\nБашҡортостан")}</span>
              </div>
            </div>

            {/* OnboardingSimpleModeCard: крупные кнопки и голос — предложение, а не тумблер. */}
            {simpleOffer && (
              <section className={"onb-simple" + appear} style={{ "--onb-delay": 3 } as CSSProperties}>
                <div className="onb-simple__head">
                  <span className="onb-simple__icon" aria-hidden><IconVolumeUp size={24} /></span>
                  <strong>{appText("Тебе удобнее крупные кнопки и голосовой заказ?", "Һиңә эре төймәләр һәм тауыш менән заказ уңайлыраҡмы?")}</strong>
                </div>
                <p>
                  {appText(
                    "Простой режим — большие кнопки, меньше шагов и заказ голосом. Включить можно и позже в профиле.",
                    "Ябай режим — эре төймәләр, аҙыраҡ аҙым һәм тауыш менән заказ. Һуңынан профилдә лә тоҡандырып була."
                  )}
                </p>
                <button type="button" className="btn-primary onb-simple__on" onClick={() => finish(true)}>
                  <IconVolumeUp size={20} /> {appText("Включить простой режим", "Ябай режимды тоҡандырыу")}
                </button>
                <button type="button" className="btn-ghost onb-simple__later" onClick={() => setSimpleOffer(false)}>
                  {appText("Не сейчас", "Хәҙер түгел")}
                </button>
              </section>
            )}
          </>
        )}

        {slide.note && (
          <div className={"onb-note" + appear} style={{ "--onb-delay": 5 } as CSSProperties}>
            <IconLock size={22} />
            <span>{slide.note}</span>
          </div>
        )}
        </div>
        </div>;
        })}
      </div>

      <div className="onb__foot">
        <div className="onb__nav">
          {step > 0 ? (
            <button type="button" className="btn-ghost onb__nav-btn" onClick={() => movePage(-1)}>
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
          <button type="button" className="btn-ghost onb__nav-btn" onClick={() => finish()}>
            {appText("Пропустить", "Үткәреп ебәреү")}
          </button>
        </div>
        <button
          type="button"
          className="btn-primary onb__next"
          onClick={() => (last ? finish() : movePage(1))}
        >
          {last ? appText("Войти через Telegram", "Telegram аша инеү") : appText("Далее", "Артабан")}
        </button>
      </div>
    </div>
  );
}
