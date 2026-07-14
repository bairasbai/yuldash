import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";

const DISMISS_KEY = "yuldash.install.dismissed";

function isStandalone(): boolean {
  return (
    window.matchMedia("(display-mode: standalone)").matches ||
    // iOS Safari
    (window.navigator as unknown as { standalone?: boolean }).standalone === true
  );
}

function isIos(): boolean {
  const ua = window.navigator.userAgent;
  const iOS = /iPad|iPhone|iPod/.test(ua);
  // iPadOS 13+ маскируется под Mac — ловим по тачу
  const iPadOS = navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1;
  return iOS || iPadOS;
}

/**
 * Подсказка «Установи на экран „Домой“».
 * iOS Safari: инструкция (Поделиться → На экран «Домой»).
 * Android/десктоп: перехват beforeinstallprompt + кнопка «Установить».
 * Не назойлива: закрытие запоминается в localStorage.
 */
export default function InstallPrompt() {
  const { t } = useLang();
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null);
  const [show, setShow] = useState(false);

  useEffect(() => {
    if (isStandalone()) return;
    if (localStorage.getItem(DISMISS_KEY) === "1") return;

    // Android/Chrome — реальное событие установки
    const onБip = (e: Event) => {
      e.preventDefault();
      setDeferred(e as BeforeInstallPromptEvent);
      setShow(true);
    };
    window.addEventListener("beforeinstallprompt", onБip);

    // iOS не шлёт beforeinstallprompt — показываем инструкцию сами, но не сразу
    let timer: number | undefined;
    if (isIos()) {
      timer = window.setTimeout(() => setShow(true), 1500);
    }

    return () => {
      window.removeEventListener("beforeinstallprompt", onБip);
      if (timer) window.clearTimeout(timer);
    };
  }, []);

  if (!show) return null;

  const dismiss = () => {
    localStorage.setItem(DISMISS_KEY, "1");
    setShow(false);
  };

  const install = async () => {
    if (!deferred) return;
    await deferred.prompt();
    await deferred.userChoice;
    setDeferred(null);
    dismiss();
  };

  const ios = isIos() && !deferred;

  return (
    <div className="install-sheet" role="dialog" aria-label={t("installTitle")}>
      <div className="install-sheet__row">
        <img src="/apple-touch-icon.png" alt="Юлдаш" />
        <div>
          <h3>{t("installTitle")}</h3>
          <p>{ios ? t("installIosBody") : t("installAndroidBody")}</p>
        </div>
      </div>
      <div className="install-sheet__actions">
        <button type="button" className="btn-ghost" onClick={dismiss}>
          {t("installLater")}
        </button>
        {!ios && (
          <button type="button" className="btn-primary" style={{ marginTop: 0 }} onClick={install}>
            {t("installBtn")}
          </button>
        )}
      </div>
    </div>
  );
}
