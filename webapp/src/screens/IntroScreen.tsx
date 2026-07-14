import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { flags } from "../flags";
import BrandMark from "../components/BrandMark";
import { useLang } from "../i18n/lang";

/**
 * Брендовое интро (один раз). Морф: «Попутчик» (смысл) → «Юлдаш» (бренд) + слоган.
 * Зеркало android/IntroScreen.kt (те же слова и слоганы). По завершении — Онбординг.
 */
export default function IntroScreen() {
  const navigate = useNavigate();
  const { appText } = useLang();
  const [phase, setPhase] = useState<0 | 1>(0); // 0 — «Попутчик», 1 — «Юлдаш»

  const finish = () => {
    flags.setIntroSeen();
    navigate("/onboarding", { replace: true });
  };

  useEffect(() => {
    const t1 = window.setTimeout(() => setPhase(1), 1300);
    const t2 = window.setTimeout(finish, 3400);
    return () => {
      window.clearTimeout(t1);
      window.clearTimeout(t2);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <button type="button" className="intro" onClick={finish} aria-label={appText("Пропустить", "Үткәреп ебәрергә")}>
      <div className="intro__mark">
        <BrandMark size={92} />
      </div>

      <div className="intro__stage">
        <span key={phase} className={"intro__word " + (phase === 1 ? "intro__word--brand" : "intro__word--meaning")}>
          {phase === 1 ? "Юлдаш" : appText("Попутчик", "Юлдаш")}
        </span>
      </div>

      <div className="intro__rule" />
      <p className="intro__slogan">
        {appText("Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу")}
      </p>

      <span className="intro__skip">{appText("Пропустить", "Үткәреп ебәрергә")}</span>
    </button>
  );
}
