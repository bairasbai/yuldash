import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { flags } from "../flags";
import { useLang } from "../i18n/lang";
import { trackOnce } from "../analytics";
import { scheduleSplashNavigation } from "../utils/splashTarget";

/** Зелёный мост Android Screen.Splash. Брендовый ролик живёт только в Intro. */
export default function SplashScreen() {
  const { status, retrySession } = useAuth();
  const navigate = useNavigate();
  const { appText } = useLang();

  useEffect(() => {
    trackOnce("app_open", "app_open");
  }, []);

  useEffect(() => scheduleSplashNavigation(status, flags.onboarded(), navigate), [status, navigate]);

  return (
    <main className="splash" role="status" aria-label={status === "unavailable"
      ? appText("Не удалось проверить вход", "Инеүҙе тикшереп булманы")
      : appText("Юлдаш загружается", "Юлдаш йөкләнә")}>
      {status === "unavailable" && (
        <div className="splash__unavailable">
          <p>{appText("Не получилось проверить вход. Проверь связь и повтори.", "Инеүҙе тикшереп булманы. Бәйләнеште тикшер ҙә ҡабатла.")}</p>
          <button type="button" onClick={retrySession}>{appText("Повторить", "Ҡабатлау")}</button>
        </div>
      )}
    </main>
  );
}
