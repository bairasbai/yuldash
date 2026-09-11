import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { flags, type Role } from "../flags";
import { useLang } from "../i18n/lang";
import { track, trackOnce } from "../analytics";
import BrandMark from "../components/BrandMark";
import { IconCheck } from "../components/Icons";
import { YuProfile, YuModeRideshare, YuAccessible, YuRoute, YuSafeTrip } from "../components/BrandIcons";

/** Онбординг: 3 слайда «что за Юлдаш» + выбор языка, роли и мягкое предложение простого режима. */
export default function OnboardingScreen() {
  const navigate = useNavigate();
  const { lang, setLang, appText } = useLang();
  const [step, setStep] = useState(0);
  const [role, setRole] = useState<Role>(flags.role());
  const [simple, setSimple] = useState(flags.simpleMode());

  const slides = [
    {
      accent: <YuRoute size={30} />,
      title: appText("Попутки между своими", "Үҙебеҙ араһында юлдаштар"),
      body: appText(
        "Юлдаш — это поездки по Башкортостану с соседями и знакомыми. По-доброму, без чужих.",
        "Юлдаш — Башҡортостан буйлап күршеләр һәм таныштар менән сәфәр. Йылы, ятлар юҡ."
      ),
    },
    {
      accent: <YuSafeTrip size={30} />,
      title: appText("Доверие — главное", "Иң мөһиме — ышаныс"),
      body: appText(
        "Проверенные попутчики, рейтинги и возможность поделиться поездкой с близкими.",
        "Тикшерелгән юлдаштар, баһалар һәм сәфәреңде яҡындарың менән бүлешеү."
      ),
    },
    {
      accent: <YuModeRideshare size={30} />,
      title: appText("Быстро и просто", "Тиҙ һәм еңел"),
      body: appText(
        "Нашёл поездку или создал свою за пару касаний. Оплата — напрямую, по-соседски.",
        "Сәфәр тап йәки үҙеңдекен яһа — бер-ике баҫыуҙа. Түләү — туранан, күршеләрсә."
      ),
    },
  ];

  const isSetup = step === slides.length; // финальный слайд-настройка

  // Начало онбординга — вход в воронку первого запуска. Раз за сессию.
  useEffect(() => {
    trackOnce("onboarding_start", "onboarding_start");
  }, []);

  const finish = () => {
    // Имя как в приложении: без него веб-онбординг не виден в общей воронке.
    // Имя как в приложении: без него веб-онбординг не виден в общей воронке.
    track("onboarding_complete");
    // Выбор простого режима считаем отдельно — по нему видно, сколько людей приходит
    // за крупными кнопками. Это не «настройка», а половина ответа на вопрос,
    // для кого мы делаем интерфейс.
    if (simple) track("onboarding_simple_mode");
    track("onboarding_done", { chosen_role: role, simple });
    flags.setRole(role);
    flags.setSimpleMode(simple);
    flags.setOnboarded();
    // Выбрал простой режим → сразу ведём в крупный хаб доступности.
    navigate(simple ? "/simple" : "/map", { replace: true });
  };

  const next = () => (isSetup ? finish() : setStep((s) => s + 1));

  return (
    <div className={"onb" + (simple ? " onb--big" : "")}>
      <div className="onb__top">
        <BrandMark size={44} />
        <div className="onb__lang" role="group" aria-label={appText("Язык", "Тел")}>
          <button type="button" data-active={lang === "ru"} onClick={() => setLang("ru")}>
            Русский
          </button>
          <button type="button" data-active={lang === "ba"} onClick={() => setLang("ba")}>
            Башҡортса
          </button>
        </div>
      </div>

      <div className="onb__body">
        {!isSetup ? (
          <div key={step} className="onb__slide">
            <img
              className="onb__hero"
              src="/onboarding_bashkir_hero.webp"
              alt=""
              aria-hidden
            />
            <div className="onb__hero-accent">{slides[step].accent}</div>
            <h1>{slides[step].title}</h1>
            <p>{slides[step].body}</p>
          </div>
        ) : (
          <div className="onb__slide onb__setup">
            <h1>{appText("Как поедешь?", "Нисек сәфәр итәсең?")}</h1>
            <p>{appText("Это можно поменять в любой момент.", "Быны теләһә ҡасан үҙгәртеп була.")}</p>

            <div className="onb__roles">
              {(
                [
                  { r: "passenger" as Role, e: <YuProfile size={30} />, t: appText("Пассажир", "Юлаусы"), s: appText("Ищу попутку", "Юлдаш эҙләйем") },
                  { r: "driver" as Role, e: <YuModeRideshare size={30} />, t: appText("Водитель", "Йөрөтөүсе"), s: appText("Беру попутчиков", "Юлдаштар алам") },
                ]
              ).map(({ r, e, t, s }) => (
                <button
                  key={r}
                  type="button"
                  className={"onb__role" + (role === r ? " is-active" : "")}
                  aria-pressed={role === r}
                  onClick={() => setRole(r)}
                >
                  <span className="onb__role-emoji">{e}</span>
                  <span className="onb__role-title">{t}</span>
                  <span className="onb__role-sub">{s}</span>
                  {role === r && <span className="onb__role-check"><IconCheck size={16} /></span>}
                </button>
              ))}
            </div>

            <button
              type="button"
              className={"onb__simple" + (simple ? " is-active" : "")}
              aria-pressed={simple}
              onClick={() => setSimple((v) => !v)}
            >
              <span className="onb__simple-emoji"><YuAccessible size={24} /></span>
              <span className="onb__simple-text">
                <b>{appText("Простой режим", "Ябай режим")}</b>
                <span>{appText("Крупный шрифт и меньше деталей", "Эре хәреф, кәм деталь")}</span>
              </span>
              <span className={"switch" + (simple ? " on" : "")} aria-hidden />
            </button>
          </div>
        )}
      </div>

      <div className="onb__dots" aria-hidden>
        {Array.from({ length: slides.length + 1 }).map((_, i) => (
          <span key={i} className={i === step ? "is-active" : ""} />
        ))}
      </div>

      <div className="onb__actions">
        {step > 0 ? (
          <button type="button" className="btn-ghost" onClick={() => setStep((s) => s - 1)}>
            {appText("Назад", "Артҡа")}
          </button>
        ) : (
          <button type="button" className="btn-ghost" onClick={finish}>
            {appText("Пропустить", "Үткәреп ебәрергә")}
          </button>
        )}
        <button type="button" className="btn-primary onb__next" onClick={next}>
          {isSetup ? appText("Начать", "Башларға") : appText("Далее", "Артабан")}
        </button>
      </div>
    </div>
  );
}
