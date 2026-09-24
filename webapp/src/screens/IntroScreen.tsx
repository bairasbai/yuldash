import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { flags } from "../flags";
import { useLang } from "../i18n/lang";
import { useFontScale } from "../fontScale";
import { createIntroTimeline, introInitialStage } from "../utils/introTimeline";
import { cubicBezier, introMotionFrame, INTRO_EASE, MOTES, moteFrame, springValue } from "../utils/introMotion";

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

function currentDocumentZoom(): number {
  if (typeof document === "undefined") return 1;
  const value = Number.parseFloat(getComputedStyle(document.documentElement).zoom);
  return Number.isFinite(value) && value > 0 ? value : 1;
}

export default function IntroScreen() {
  const navigate = useNavigate();
  const { appText } = useLang();
  const [fontScale] = useFontScale();
  const [documentZoom, setDocumentZoom] = useState(currentDocumentZoom);
  const reduceMotion = useRef(typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches);
  const [stage, setStage] = useState(() => introInitialStage(reduceMotion.current));
  const timeline = useRef<ReturnType<typeof createIntroTimeline> | null>(null);
  const sceneRef = useRef<HTMLImageElement | null>(null);
  const columnRef = useRef<HTMLDivElement | null>(null);
  const markRef = useRef<HTMLSpanElement | null>(null);
  const brandRef = useRef<HTMLSpanElement | null>(null);
  const motesRef = useRef<HTMLSpanElement | null>(null);
  const vignetteRef = useRef<HTMLSpanElement | null>(null);

  useLayoutEffect(() => {
    const syncZoom = () => setDocumentZoom(currentDocumentZoom());
    syncZoom();
    window.addEventListener("resize", syncZoom);
    return () => window.removeEventListener("resize", syncZoom);
  }, [fontScale]);

  useEffect(() => {
    const pixelRatio = () => window.devicePixelRatio || 1;
    const setPhysicalSizes = () => {
      vignetteRef.current?.style.setProperty("--intro-vignette-radius", `${1500 / pixelRatio()}px`);
      if (brandRef.current) brandRef.current.style.backgroundSize = `${200 / pixelRatio()}px 100%, 100% 100%`;
    };
    setPhysicalSizes();
    window.addEventListener("resize", setPhysicalSizes);
    const started = performance.now();
    let brandStarted: number | null = null;
    let sheenStarted: number | null = null;
    let exitStarted: number | null = null;
    let frame = 0;
    if (!reduceMotion.current) {
      const moteNodes = Array.from(motesRef.current?.children ?? []) as HTMLElement[];
      const draw = (now: number) => {
        const elapsed = now - started;
        const values = introMotionFrame(elapsed, exitStarted === null ? null : now - exitStarted);
        if (sceneRef.current) {
          sceneRef.current.style.opacity = String(values.sceneAlpha);
          sceneRef.current.style.transform = `scale(${values.sceneScale})`;
        }
        if (columnRef.current) {
          columnRef.current.style.opacity = String(values.columnAlpha);
          columnRef.current.style.transform = `scale(${values.columnScale})`;
        }
        if (markRef.current) markRef.current.style.transform = `scale(${values.logoScale})`;
        if (brandRef.current && brandStarted !== null) {
          brandRef.current.style.transform = `scale(${springValue(0.92, 1, now - brandStarted, 0.82)})`;
        }
        if (brandRef.current && sheenStarted !== null) {
          const sheen = cubicBezier(Math.min(1, (now - sheenStarted) / 1600), INTRO_EASE.inOutSine);
          brandRef.current.style.backgroundPosition = `${(-260 + 980 * sheen) / pixelRatio()}px 0, 0 0`;
        }
        moteNodes.forEach((node, index) => {
          const mote = moteFrame(MOTES[index], values.moteProgress, values.sceneAlpha);
          node.style.left = `${mote.x * 100}%`;
          node.style.top = `${mote.y * 100}%`;
          node.style.opacity = String(mote.alpha);
        });
        frame = window.requestAnimationFrame(draw);
      };
      frame = window.requestAnimationFrame(draw);
    }
    const controller = createIntroTimeline(
      reduceMotion.current,
      (patch) => {
        const now = performance.now();
        if (patch.brand) brandStarted = now;
        if (patch.sheen) sheenStarted = now;
        if (patch.exiting) exitStarted = now;
        setStage((current) => ({ ...current, ...patch }));
      },
      () => {
        flags.setIntroSeen();
        navigate("/onboarding", { replace: true });
      },
    );
    timeline.current = controller;
    return () => {
      controller.dispose();
      window.cancelAnimationFrame(frame);
      window.removeEventListener("resize", setPhysicalSizes);
      if (timeline.current === controller) timeline.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <button type="button" className="intro" onClick={() => timeline.current?.skip()} aria-label={appText("Пропустить", "Үткәреү")}
      style={{ zoom: 1 / documentZoom, "--intro-font-scale": documentZoom } as React.CSSProperties}>
      {/* Пейзаж: проявляется из зелёного + мягкий push-in. Поверх — вуаль 20 % и виньетка. */}
      <img ref={sceneRef} className="intro__scene" src="/intro_landscape.svg" alt="" aria-hidden />
      <span className="intro__veil" aria-hidden />
      <span ref={vignetteRef} className="intro__vignette" aria-hidden
        style={{ "--intro-vignette-radius": `${1500 / (typeof window !== "undefined" ? window.devicePixelRatio || 1 : 1)}px` } as React.CSSProperties} />
      <span ref={motesRef} className="intro__motes" aria-hidden>
        {MOTES.map((mote, i) => {
          const initial = moteFrame(mote, 0, reduceMotion.current ? 1 : 0);
          return <i key={i} style={{
            left: `${initial.x * 100}%`, top: `${initial.y * 100}%`,
            width: initial.radius * 2, height: initial.radius * 2,
            opacity: initial.alpha,
          }} />;
        })}
      </span>

      <div ref={columnRef} className={"intro__column" + (stage.exiting ? " is-exiting" : "")}>
        {/* BrandHero: слот 224×158, круг 132 сверху через 24. */}
        <span className="intro__hero"><span ref={markRef} className="intro__mark">
          <img src="/yuldash_logo.webp" alt="Юлдаш" />
        </span></span>

        {/* Слот слова: «Попутчик» по буквам ↔ «Юлдаш» целым с бликом. */}
        <span className="intro__stage">
          <span className={"intro__meaning is-" + stage.meaningPhase} aria-hidden={stage.meaningPhase !== "in"}>
            {[...MEANING_WORD].map((ch, i) => (
              <i key={i} style={{ "--i": i } as React.CSSProperties}>
                {ch}
              </i>
            ))}
          </span>
          <span ref={brandRef} className={"intro__brand" + (stage.brand ? " is-in" : "")} aria-hidden={!stage.brand}>
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

      <span className={"intro__skip" + (stage.exiting ? " is-out" : stage.skip ? " is-in" : "")}>{appText("Пропустить", "Үткәреү")}</span>
    </button>
  );
}
