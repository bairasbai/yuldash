"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { useLang } from "./lang";
import { LEGAL } from "./config";

const KEY = "yuldash_cookie_ok";

export function CookieConsent() {
  const { tr } = useLang();
  const [show, setShow] = useState(false);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      try {
        if (!localStorage.getItem(KEY)) setShow(true);
      } catch {
        /* приватный режим — просто не показываем повторно в рамках сессии */
      }
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  const accept = () => {
    try {
      localStorage.setItem(KEY, "1");
    } catch {}
    setShow(false);
    // сообщаем sticky-кнопке, что баннер закрыт
    window.dispatchEvent(new Event("yuldash-cookie-ok"));
  };

  return (
    <AnimatePresence>
      {show && (
        <motion.div
          initial={{ y: 80, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 80, opacity: 0 }}
          transition={{ type: "spring", stiffness: 300, damping: 30 }}
          role="dialog"
          aria-live="polite"
          className="fixed inset-x-0 bottom-0 z-[55] p-3 md:bottom-4 md:left-1/2 md:right-auto md:w-[min(92vw,560px)] md:-translate-x-1/2"
        >
          <div className="glass flex flex-col items-center gap-3 rounded-canon px-5 py-4 sm:flex-row">
            <p className="flex-1 text-center text-sm text-white/70 sm:text-left">
              {tr("cookie_text")}{" "}
              <a href={LEGAL.privacy} className="text-green-glow underline-offset-2 hover:underline">
                {tr("foot_privacy")}
              </a>
            </p>
            <button
              onClick={accept}
              className="shrink-0 rounded-full bg-green-bright px-5 py-2 text-sm font-bold text-night transition-transform hover:scale-105 active:scale-95"
            >
              {tr("cookie_ok")}
            </button>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
