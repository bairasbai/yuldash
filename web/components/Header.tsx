"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, m } from "framer-motion";
import Image from "next/image";
import { useLang, type DictKey } from "./lang";

const NAV: { href: string; key: DictKey }[] = [
  { href: "#features", key: "nav_features" },
  { href: "#how", key: "hero_cta2" },
  { href: "#trust", key: "nav_trust" },
  { href: "#faq", key: "faq_title" },
];

function LangToggle({ id = "lang-pill" }: { id?: string }) {
  const { lang, setLang } = useLang();
  return (
    <div className="relative flex items-center rounded-full border border-white/10 bg-white/5 p-0.5 text-sm font-semibold">
      {(["ru", "ba"] as const).map((l) => (
        <button
          key={l}
          onClick={() => setLang(l)}
          className="relative z-10 rounded-full px-3 py-1 transition-colors"
          aria-label={l === "ru" ? "Русский" : "Башҡортса"}
          aria-pressed={lang === l}
        >
          {lang === l && (
            <m.span
              layoutId={id}
              className="absolute inset-0 -z-10 rounded-full bg-green-bright/90"
              transition={{ type: "spring", stiffness: 380, damping: 30 }}
            />
          )}
          <span className={lang === l ? "text-night" : "text-white/60"}>
            {l === "ru" ? "RU" : "БА"}
          </span>
        </button>
      ))}
    </div>
  );
}

export function Header() {
  const { tr } = useLang();
  const [scrolled, setScrolled] = useState(false);
  const [active, setActive] = useState<string>("");
  const [open, setOpen] = useState(false);

  // уплотнение шапки при скролле
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 20);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  // подсветка активной секции
  useEffect(() => {
    const ids = NAV.map((n) => n.href.slice(1));
    const obs = new IntersectionObserver(
      (entries) => {
        entries.forEach((e) => {
          if (e.isIntersecting) setActive(e.target.id);
        });
      },
      { rootMargin: "-45% 0px -50% 0px" }
    );
    ids.forEach((id) => {
      const el = document.getElementById(id);
      if (el) obs.observe(el);
    });
    return () => obs.disconnect();
  }, []);

  // блокируем скролл body при открытом меню
  useEffect(() => {
    document.body.style.overflow = open ? "hidden" : "";
    return () => {
      document.body.style.overflow = "";
    };
  }, [open]);

  return (
    <m.header
      initial={{ y: -24 }}
      animate={{ y: 0 }}
      transition={{ duration: 0.6, ease: "easeOut" }}
      className="fixed inset-x-0 top-0 z-[60]"
    >
      <div className="mx-auto mt-3 max-w-6xl px-3 sm:px-4">
        <div
          className={`flex items-center justify-between gap-4 rounded-full px-4 py-2.5 transition-all duration-300 ${
            scrolled
              ? "glass shadow-[0_10px_40px_-20px_rgba(0,0,0,0.7)]"
              : "border border-transparent bg-transparent"
          }`}
        >
          <a href="#top" className="flex items-center gap-2.5" aria-label={tr("nav_home")}>
            <Image src="/logo.png" alt="Юлдаш" width={34} height={34} priority className="rounded-lg" />
            <span className="font-display text-lg font-extrabold tracking-tight">Юлдаш</span>
          </a>

          {/* десктоп-навигация */}
          <nav className="hidden items-center gap-7 text-sm font-medium md:flex">
            {NAV.map((n) => {
              const isActive = active === n.href.slice(1);
              return (
                <a
                  key={n.href}
                  href={n.href}
                  className={`relative transition-colors ${
                    isActive ? "text-white" : "text-white/70 hover:text-white"
                  }`}
                >
                  {tr(n.key)}
                  {isActive && (
                    <m.span
                      layoutId="nav-underline"
                      className="absolute -bottom-1.5 left-0 right-0 h-0.5 rounded-full bg-green-bright"
                      transition={{ type: "spring", stiffness: 380, damping: 30 }}
                    />
                  )}
                </a>
              );
            })}
          </nav>

          <div className="flex items-center gap-2.5">
            <LangToggle />
            <a
              href="#download"
              className="hidden rounded-full bg-green-bright px-4 py-2 text-sm font-bold text-night transition-transform hover:scale-[1.04] active:scale-95 sm:block"
            >
              {tr("nav_download")}
            </a>

            {/* бургер — мобайл */}
            <button
              onClick={() => setOpen(true)}
              aria-label={tr("nav_menu")}
              aria-expanded={open}
              className="flex h-10 w-10 items-center justify-center rounded-full border border-white/10 bg-white/5 text-white md:hidden"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="M4 7h16M4 12h16M4 17h16" />
              </svg>
            </button>
          </div>
        </div>
      </div>

      {/* мобильное меню */}
      <AnimatePresence>
        {open && (
          <>
            <m.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setOpen(false)}
              className="fixed inset-0 z-[58] bg-black/60 backdrop-blur-sm md:hidden"
            />
            <m.div
              initial={{ x: "100%" }}
              animate={{ x: 0 }}
              exit={{ x: "100%" }}
              transition={{ type: "spring", stiffness: 320, damping: 34 }}
              className="fixed right-0 top-0 z-[60] flex h-[100dvh] w-[78%] max-w-xs flex-col bg-forest p-6 md:hidden"
              style={{ paddingTop: "max(1.5rem, env(safe-area-inset-top))" }}
            >
              <div className="mb-8 flex items-center justify-between">
                <span className="font-display text-lg font-extrabold">{tr("nav_menu")}</span>
                <button
                  onClick={() => setOpen(false)}
                  aria-label={tr("nav_close")}
                  className="flex h-10 w-10 items-center justify-center rounded-full border border-white/10 bg-white/5 text-white"
                >
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                    <path d="M6 6l12 12M18 6L6 18" />
                  </svg>
                </button>
              </div>

              <nav className="flex flex-col gap-1 text-lg font-semibold">
                {NAV.map((n, i) => (
                  <m.a
                    key={n.href}
                    href={n.href}
                    onClick={() => setOpen(false)}
                    initial={{ opacity: 0, x: 20 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: 0.08 + i * 0.06 }}
                    className="rounded-2xl px-4 py-3 text-white/80 transition-colors hover:bg-white/5 hover:text-white"
                  >
                    {tr(n.key)}
                  </m.a>
                ))}
              </nav>

              <div className="mt-auto flex flex-col gap-4 pt-6">
                <LangToggle id="lang-pill-mobile" />
                <a
                  href="#download"
                  onClick={() => setOpen(false)}
                  className="rounded-canon bg-green-bright px-5 py-3.5 text-center font-bold text-night shadow-glow"
                >
                  {tr("nav_download")}
                </a>
              </div>
            </m.div>
          </>
        )}
      </AnimatePresence>
    </m.header>
  );
}
