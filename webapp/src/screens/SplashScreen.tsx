import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { flags } from "../flags";
import BrandMark from "../components/BrandMark";
import { useLang } from "../i18n/lang";

/**
 * Стартовый экран-гейт. Пока AuthProvider проверяет сохранённую сессию (GET /me) —
 * показываем брендовую заставку. Как только известен статус:
 *  - есть сессия → в приложение (лента);
 *  - гость и не видел интро → /intro → /onboarding;
 *  - гость, уже онбордился → лента (она публичная, вход по требованию).
 */
export default function SplashScreen() {
  const { status } = useAuth();
  const navigate = useNavigate();
  const { appText } = useLang();

  useEffect(() => {
    if (status === "loading") return;
    if (status === "authed") {
      navigate("/rides", { replace: true });
      return;
    }
    // гость
    if (!flags.introSeen()) navigate("/intro", { replace: true });
    else if (!flags.onboarded()) navigate("/onboarding", { replace: true });
    else navigate("/rides", { replace: true });
  }, [status, navigate]);

  return (
    <div className="splash">
      <div className="splash__mark">
        <BrandMark size={104} />
      </div>
      <div className="splash__word">Юлдаш</div>
      <p className="splash__slogan">
        {appText("Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу")}
      </p>
      <div className="splash__spin" aria-hidden />
    </div>
  );
}
