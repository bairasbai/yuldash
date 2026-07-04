"use client";

import { createContext, useContext, useState, ReactNode } from "react";
import { AnimatePresence, m } from "framer-motion";
import { useLang } from "./lang";
import { APP_READY, APK_URL, SOCIAL } from "./config";
import { track } from "./analytics";

type Ctx = { ready: boolean; request: () => void };
const DownloadCtx = createContext<Ctx | null>(null);

export function useDownload() {
  const ctx = useContext(DownloadCtx);
  if (!ctx) throw new Error("useDownload must be used within DownloadProvider");
  return ctx;
}

export function DownloadProvider({ children }: { children: ReactNode }) {
  const { tr } = useLang();
  const [open, setOpen] = useState(false);

  const request = () => {
    if (APP_READY) {
      track("download");
      window.location.assign(APK_URL);
    } else {
      track("download_intent"); // спрос до релиза
      setOpen(true);
    }
  };

  return (
    <DownloadCtx.Provider value={{ ready: APP_READY, request }}>
      {children}

      <AnimatePresence>
        {open && (
          <m.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setOpen(false)}
            className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-5 backdrop-blur-sm"
            role="dialog"
            aria-modal="true"
            aria-label={tr("cs_title")}
          >
            <m.div
              initial={{ scale: 0.92, opacity: 0, y: 16 }}
              animate={{ scale: 1, opacity: 1, y: 0 }}
              exit={{ scale: 0.95, opacity: 0, y: 10 }}
              transition={{ type: "spring", stiffness: 300, damping: 26 }}
              onClick={(e) => e.stopPropagation()}
              className="glass relative w-full max-w-md overflow-hidden rounded-[28px] p-8 text-center"
            >
              <div className="pointer-events-none absolute -top-20 left-1/2 h-48 w-48 -translate-x-1/2 rounded-full bg-green-bright/25 blur-3xl" />

              <span className="relative inline-flex items-center gap-2 rounded-full bg-green-bright/15 px-3.5 py-1.5 text-sm font-semibold text-green-glow">
                <span className="relative flex h-2 w-2">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-green-bright opacity-75" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-green-bright" />
                </span>
                {tr("cs_badge")}
              </span>

              <h3 className="relative mt-5 font-display text-2xl font-extrabold">{tr("cs_title")}</h3>
              <p className="relative mt-3 text-[15px] leading-relaxed text-white/65">{tr("cs_text")}</p>

              <div className="relative mt-7 flex flex-col gap-3">
                <a
                  href={SOCIAL.telegram}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={() => track("notify_telegram")}
                  className="inline-flex items-center justify-center gap-2.5 rounded-canon bg-green-bright px-6 py-3.5 font-bold text-night shadow-glow transition-transform hover:scale-[1.03] active:scale-95"
                >
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M21.9 4.3 18.6 20c-.2 1.1-.9 1.4-1.8.9l-4.9-3.6-2.4 2.3c-.3.3-.5.5-1 .5l.3-5 9.1-8.2c.4-.4-.1-.6-.6-.2L6.3 13.9l-4.8-1.5c-1-.3-1-1 .2-1.5l18.8-7.2c.9-.3 1.6.2 1.4 1.6Z"/></svg>
                  {tr("cs_tg")}
                </a>
                <button
                  onClick={() => setOpen(false)}
                  className="rounded-canon px-6 py-3 text-sm font-semibold text-white/60 transition-colors hover:text-white"
                >
                  {tr("cs_close")}
                </button>
              </div>
            </m.div>
          </m.div>
        )}
      </AnimatePresence>
    </DownloadCtx.Provider>
  );
}
