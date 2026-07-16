import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { flags } from "../flags";
import { useLang } from "../i18n/lang";
import { trackOnce } from "../analytics";

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

  // Старт сессии — вход в воронку. Раз за вкладку (Splash — первый экран).
  useEffect(() => {
    trackOnce("app_open", "app_open");
  }, []);

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
        <img
          src="/yuldash_logo.png"
          alt="Юлдаш"
          width={104}
          height={104}
          className="brand-logo-img"
        />
      </div>
      <div className="splash__word">Юлдаш</div>
      <p className="splash__slogan">
        {appText("Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу")}
      </p>
      <div className="splash__spin" aria-hidden />
    </div>
  );
}
