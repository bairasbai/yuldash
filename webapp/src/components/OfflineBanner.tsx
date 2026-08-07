import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";

/**
 * Индикатор офлайна. Слушает navigator.onLine + события online/offline.
 * Когда сети нет — показывает аккуратный баннер вместо «тихого» провала
 * запросов (app shell закеширован SW, экран остаётся живым). При возврате
 * сети коротко подтверждает «Сеть вернулась» и сам прячется.
 */
export default function OfflineBanner() {
  const { appText } = useLang();
  const [online, setOnline] = useState(() => navigator.onLine);
  const [justBack, setJustBack] = useState(false);

  useEffect(() => {
    const goOffline = () => {
      setOnline(false);
      setJustBack(false);
    };
    const goOnline = () => {
      setOnline(true);
      setJustBack(true);
      // Короткое подтверждение и прячемся.
      const t = window.setTimeout(() => setJustBack(false), 2200);
      return () => window.clearTimeout(t);
    };
    window.addEventListener("offline", goOffline);
    window.addEventListener("online", goOnline);
    return () => {
      window.removeEventListener("offline", goOffline);
      window.removeEventListener("online", goOnline);
    };
  }, []);

  if (online && !justBack) return null;

  return (
    <div
      className={"offline-banner" + (online ? " offline-banner--back" : "")}
      role="status"
      aria-live="polite"
    >
      <span className="offline-banner__dot" aria-hidden />
      {online
        ? appText("Сеть вернулась", "Бәйләнеш ҡайтты")
        : appText("Нет сети — показываем сохранённое", "Бәйләнеш юҡ — һаҡланғанды күрһәтәбеҙ")}
    </div>
  );
}
