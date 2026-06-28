"use client";

import { motion } from "framer-motion";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";
import { useDownload } from "./DownloadProvider";

// Популярные направления по Башкортостану. Цены/время — ПРИМЕРНЫЕ (помечено в подзаголовке).
type Route = {
  from: { ru: string; ba: string };
  to: { ru: string; ba: string };
  price: number; // ₽, «от»
  hours: number; // ~ часов
  seats: number;
};
const ROUTES: Route[] = [
  { from: { ru: "Уфа", ba: "Өфө" }, to: { ru: "Стерлитамак", ba: "Стәрлетамаҡ" }, price: 400, hours: 2, seats: 3 },
  { from: { ru: "Уфа", ba: "Өфө" }, to: { ru: "Сибай", ba: "Сибай" }, price: 1500, hours: 6, seats: 2 },
  { from: { ru: "Уфа", ba: "Өфө" }, to: { ru: "Белорецк", ba: "Белорет" }, price: 800, hours: 4, seats: 3 },
  { from: { ru: "Уфа", ba: "Өфө" }, to: { ru: "Нефтекамск", ba: "Нефтекама" }, price: 700, hours: 3, seats: 3 },
  { from: { ru: "Уфа", ba: "Өфө" }, to: { ru: "Октябрьский", ba: "Октябрьский" }, price: 600, hours: 3, seats: 4 },
  { from: { ru: "Сибай", ba: "Сибай" }, to: { ru: "Магнитогорск", ba: "Магнитогорск" }, price: 500, hours: 2, seats: 3 },
];

export function Routes() {
  const { tr, lang } = useLang();
  const { request } = useDownload();

  const onMove = (e: React.MouseEvent<HTMLButtonElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    e.currentTarget.style.setProperty("--mx", `${e.clientX - r.left}px`);
    e.currentTarget.style.setProperty("--my", `${e.clientY - r.top}px`);
  };

  return (
    <section id="routes" className="relative px-6 py-24">
      <div className="mx-auto max-w-6xl">
        <Reveal className="mx-auto max-w-2xl text-center">
          <OrnamentKicker />
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">
            {tr("routes_title")}
          </h2>
          <p className="mt-4 text-lg text-white/60">{tr("routes_sub")}</p>
        </Reveal>

        <div className="mt-14 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {ROUTES.map((r, i) => (
            <Reveal key={i} delay={(i % 3) * 0.08}>
              <motion.button
                type="button"
                onClick={request}
                onMouseMove={onMove}
                whileHover={{ y: -6 }}
                transition={{ type: "spring", stiffness: 300, damping: 20 }}
                className="spotlight glass group flex w-full flex-col rounded-canon p-6 text-left"
              >
                {/* маршрут */}
                <div className="flex items-center gap-3 font-display text-lg font-bold">
                  <span>{r.from[lang]}</span>
                  <svg className="text-green-glow" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M5 12h14M13 6l6 6-6 6" />
                  </svg>
                  <span>{r.to[lang]}</span>
                </div>

                {/* мета */}
                <div className="mt-5 flex items-center justify-between">
                  <div>
                    <div className="font-display text-2xl font-extrabold text-gold-light">
                      {lang === "ru" ? `от ${r.price} ₽` : `${r.price} ₽-дан`}
                    </div>
                    <div className="mt-1 flex items-center gap-3 text-xs text-white/50">
                      <span className="inline-flex items-center gap-1">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>
                        ~{r.hours} {lang === "ru" ? "ч" : "сәғ"}
                      </span>
                      <span className="inline-flex items-center gap-1">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><circle cx="9" cy="7" r="3.2" /><path d="M3.5 20a6 6 0 0 1 11 0" /><path d="M16 11a3 3 0 0 0 0-6" /></svg>
                        {r.seats} {tr("routes_seats")}
                      </span>
                    </div>
                  </div>
                  <span className="flex h-9 w-9 items-center justify-center rounded-full bg-green-bright/12 text-green-glow transition-colors group-hover:bg-green-bright/25">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
                  </span>
                </div>
              </motion.button>
            </Reveal>
          ))}
        </div>

        <Reveal className="mt-8 text-center">
          <button onClick={request} className="text-sm font-semibold text-green-glow transition-colors hover:text-white">
            {tr("routes_more")} →
          </button>
        </Reveal>
      </div>
    </section>
  );
}
