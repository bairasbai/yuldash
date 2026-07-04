"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, m } from "framer-motion";
import { useLang } from "./lang";
import { useDownload } from "./DownloadProvider";
import { APK_URL } from "./config";
import { track } from "./analytics";

// Залипающая кнопка «Скачать» внизу — только мобайл, появляется после героя
// и ТОЛЬКО когда cookie-баннер уже принят (чтобы не накладывались).
export function StickyDownloadBar() {
  const { tr } = useLang();
  const { request, ready } = useDownload();
  const [scrolledPast, setScrolledPast] = useState(false);
  const [cookieDone, setCookieDone] = useState(false);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      try {
        setCookieDone(!!localStorage.getItem("yuldash_cookie_ok"));
      } catch {
        setCookieDone(true);
      }
    }, 0);
    const onScroll = () => setScrolledPast(window.scrollY > 700);
    const onCookie = () => setCookieDone(true);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("yuldash-cookie-ok", onCookie);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("yuldash-cookie-ok", onCookie);
    };
  }, []);

  const show = scrolledPast && cookieDone;
  const content = (
    <>
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 3v12" /><path d="m7 11 5 5 5-5" /><path d="M5 21h14" />
      </svg>
      {tr("sticky_cta")} Юлдаш
    </>
  );
  const className = "glass flex w-full items-center justify-center gap-2.5 rounded-canon bg-green-bright/95 py-3.5 text-base font-bold text-night shadow-glow active:scale-[0.98]";

  return (
    <AnimatePresence>
      {show && (
        <m.div
          initial={{ y: 90, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 90, opacity: 0 }}
          transition={{ type: "spring", stiffness: 320, damping: 30 }}
          className="fixed inset-x-0 bottom-0 z-40 p-3 md:hidden"
          style={{ paddingBottom: "max(0.75rem, env(safe-area-inset-bottom))" }}
        >
          {ready ? (
            <a href={APK_URL} download onClick={() => track("download")} className={className}>
              {content}
            </a>
          ) : (
            <button type="button" onClick={request} className={className}>
              {content}
            </button>
          )}
        </m.div>
      )}
    </AnimatePresence>
  );
}
