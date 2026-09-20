import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { flags } from "../flags";
import { useLang } from "../i18n/lang";

/**
 * Брендовое интро (один раз). Зеркало android/IntroScreen.kt + IntroHero.kt:
 * пейзаж Башкортостана проявляется из зелёного и медленно «наезжает» (Ken-Burns), вуаль
 * и виньетка, золотая пыльца в небе; белый круг с логотипом, «Попутчик» по буквам →
 * каскадом уходит → «Юлдаш» приходит целым с золотым бликом → золотая черта из центра →
 * слоган RU → BA → плавный «улёт» сцены в онбординг. Тап = пропустить, «Пропустить»
 * приходит через 1,4 с. Reduced-motion → финал через секунду.
 */
const MEANING_WORD = "Попутчик";
const BRAND_WORD = "Юлдаш";
const SLOGAN_RU = "Поездки между своими";
const SLOGAN_BA = "Үҙебеҙҙекеләр араһында юллашыу";

/** Золотая пыльца в небе: x, y (доли экрана), радиус, темп мерцания. Точки — как MOTES в IntroHero.kt. */
const MOTES: [number, number, number, number][] = [
  [0.14, 0.1, 2.0, 0.6],
  [0.27, 0.3, 1.5, 0.85],
  [0.78, 0.16, 2.2, 0.5],
  [0.86, 0.36, 1.6, 0.7],
  [0.66, 0.24, 1.7, 0.9],
  [0.1, 0.4, 1.4, 0.55],
  [0.9, 0.5, 1.5, 0.65],
];

type Stage = {
  meaning: boolean;
  brand: boolean;
  sheen: boolean;
  underline: boolean;
  slogan: boolean;
  sloganBa: boolean;
  exiting: boolean;
  skip: boolean;
};

const START: Stage = { meaning: false, brand: false, sheen: false, underline: false, slogan: false, sloganBa: false, exiting: false, skip: false };

export default function IntroScreen() {
  const navigate = useNavigate();
  const { appText } = useLang();
  const [stage, setStage] = useState<Stage>(START);
  const done = useRef(false);

  const finish = () => {
    if (done.current) return;
    done.current = true;
    flags.setIntroSeen();
    navigate("/onboarding", { replace: true });
  };

  useEffect(() => {
    const timers: number[] = [];
    const at = (ms: number, fn: () => void) => timers.push(window.setTimeout(fn, ms));
    const set = (patch: Partial<Stage>) => setStage((s) => ({ ...s, ...patch }));

    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    if (reduce) {
      at(1000, finish);
      return () => timers.forEach(window.clearTimeout);
    }

    // Таймлайн IntroScreen.kt: логотип «садится» пружиной (~600 мс), дальше — по шагам.
    let t = 600;
    at(1400, () => set({ skip: true }));
    t += 160;
    at(t, () => set({ meaning: true })); // «Попутчик» по буквам
    t += 1200;
    at(t, () => set({ meaning: false })); // слово уходит каскадом
    t += 420;
    at(t, () => set({ brand: true })); // «Юлдаш» — целым
    at(t + 360, () => set({ sheen: true })); // медленный люкс-блик
    t += 500;
    at(t, () => set({ underline: true }));
    t += 780;
    at(t, () => set({ slogan: true }));
    t += 1400;
    at(t, () => set({ sloganBa: true })); // русский подышал — потом башкирский
    t += 1200;
    at(t, () => set({ exiting: true }));
    t += 500;
    at(t, finish);
    return () => timers.forEach(window.clearTimeout);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <button type="button" className="intro" onClick={finish} aria-label={appText("Пропустить", "Үткәреү")}>
      {/* Пейзаж: проявляется из зелёного + мягкий push-in. Поверх — вуаль 20 % и виньетка. */}
      <img className="intro__scene" src="/intro_landscape.svg" alt="" aria-hidden />
      <span className="intro__veil" aria-hidden />
      <span className="intro__vignette" aria-hidden />
      <span className="intro__motes" aria-hidden>
        {MOTES.map(([x, y, r, spd], i) => (
          <i
            key={i}
            style={{
              left: `${x * 100}%`,
              top: `${(0.06 + y * 0.46) * 100}%`,
              width: r * 2,
              height: r * 2,
              animationDuration: `calc(var(--motion-ambient) / ${spd})`,
              animationDelay: `calc(var(--motion-ambient) * -${(i * 0.13).toFixed(2)})`,
            }}
          />
        ))}
      </span>

      <div className={"intro__column" + (stage.exiting ? " is-exiting" : "")}>
        {/* BrandHero: белый круг 132 с логотипом, лёгкий settle. */}
        <span className="intro__mark">
          <img src="/yuldash_logo.webp" alt="Юлдаш" />
        </span>

        {/* Слот слова: «Попутчик» по буквам ↔ «Юлдаш» целым с бликом. */}
        <span className="intro__stage">
          <span className={"intro__meaning" + (stage.meaning ? " is-in" : "")} aria-hidden={!stage.meaning}>
            {[...MEANING_WORD].map((ch, i) => (
              <i key={i} style={{ "--i": i } as React.CSSProperties}>
                {ch}
              </i>
            ))}
          </span>
          <span className={"intro__brand" + (stage.brand ? " is-in" : "") + (stage.sheen ? " is-sheen" : "")} aria-hidden={!stage.brand}>
            {BRAND_WORD}
          </span>
        </span>

        <span className={"intro__rule" + (stage.underline ? " is-in" : "")} aria-hidden />

        {/* Слот слогана зарезервирован всегда — появление не двигает логотип. RU уходит, BA приходит. */}
        <span className="intro__slogans">
          <span className={"intro__slogan" + (stage.slogan && !stage.sloganBa ? " is-in" : stage.sloganBa ? " is-out" : "")}>{SLOGAN_RU}</span>
          <span className={"intro__slogan" + (stage.sloganBa ? " is-in" : "")}>{SLOGAN_BA}</span>
        </span>
      </div>

      <span className={"intro__skip" + (stage.skip ? " is-in" : "")}>{appText("Пропустить", "Үткәреү")}</span>
    </button>
  );
}
