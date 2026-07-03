"use client";

import { m } from "framer-motion";
import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";
import { BorderBeam } from "./BorderBeam";
import { Tag, ShieldHeart, UserGlyph } from "./icons";
import { ReactNode } from "react";

// курсор-свечение для карточек (пишет CSS-переменные --mx/--my)
function spotMove(e: React.MouseEvent<HTMLElement>) {
  const r = e.currentTarget.getBoundingClientRect();
  e.currentTarget.style.setProperty("--mx", `${e.clientX - r.left}px`);
  e.currentTarget.style.setProperty("--my", `${e.clientY - r.top}px`);
}

const IconUsers = (
  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
    <circle cx="9" cy="7" r="4" />
    <path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" />
  </svg>
);
const IconMap = (
  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M9 18 3 21V6l6-3 6 3 6-3v15l-6 3-6-3Z" />
    <path d="M9 3v15M15 6v15" />
  </svg>
);
type Feat = { icon: ReactNode; t: DictKey; d: DictKey };
const feats: Feat[] = [
  { icon: IconUsers, t: "feat_1_t", d: "feat_1_d" }, // featured
  { icon: IconMap, t: "feat_2_t", d: "feat_2_d" },
  { icon: <Tag size={26} />, t: "feat_3_t", d: "feat_3_d" },
  { icon: <ShieldHeart size={26} />, t: "feat_4_t", d: "feat_4_d" },
];

export function Features() {
  const { tr } = useLang();
  const [featured, ...rest] = feats;

  return (
    <section id="features" className="relative px-6 py-24">
      <div className="mx-auto max-w-6xl">
        <Reveal className="mx-auto max-w-2xl text-center">
          <OrnamentKicker />
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">
            {tr("feat_title")}
          </h2>
          <p className="mt-4 text-lg text-white/60">{tr("feat_sub")}</p>
        </Reveal>

        {/* Bento-сетка: 1 крупная карточка + 3 стандартных */}
        <div className="mt-14 grid gap-5 sm:grid-cols-2 lg:grid-cols-4 lg:grid-rows-2">
          {/* Featured */}
          <Reveal className="lg:col-span-2 lg:row-span-2">
            <m.div
              onMouseMove={spotMove}
              whileHover={{ y: -8 }}
              transition={{ type: "spring", stiffness: 300, damping: 20 }}
              className="glass spotlight group relative flex h-full flex-col justify-between overflow-hidden rounded-canon p-7"
            >
              <BorderBeam />
              {/* декоративное свечение */}
              <div className="pointer-events-none absolute -right-16 -top-16 h-56 w-56 rounded-full bg-green-bright/15 blur-3xl transition-opacity group-hover:opacity-80" />

              <div className="relative">
                <div className="mb-6 inline-flex h-16 w-16 items-center justify-center rounded-2xl bg-green-bright/15 text-green-glow transition-transform duration-300 group-hover:scale-110">
                  {featured.icon}
                </div>
                <h3 className="font-display text-2xl font-extrabold sm:text-3xl">{tr(featured.t)}</h3>
                <p className="mt-3 max-w-md text-[15px] leading-relaxed text-white/65">
                  {tr(featured.d)}
                </p>
              </div>

              {/* ряд аватаров «свои» */}
              <div className="relative mt-8 flex items-center">
                <div className="flex -space-x-3">
                  {[0, 1, 2, 3].map((i) => (
                    <span
                      key={i}
                      className="flex h-10 w-10 items-center justify-center rounded-full border-2 border-forest bg-gradient-to-br from-green-bright/30 to-green-deep/40 text-green-glow"
                      style={{ opacity: 1 - i * 0.12 }}
                    >
                      <UserGlyph size={18} />
                    </span>
                  ))}
                </div>
                <span className="ml-3 inline-flex items-center gap-1.5 rounded-full bg-green-bright/15 px-3 py-1 text-xs font-semibold text-green-glow">
                  <span className="h-1.5 w-1.5 rounded-full bg-green-bright" />
                  4.9★
                </span>
              </div>
            </m.div>
          </Reveal>

          {/* Остальные */}
          {rest.map((f, i) => (
            <Reveal
              key={f.t}
              delay={i * 0.08}
              className={i === 0 ? "lg:col-span-2" : "lg:col-span-1"}
            >
              <m.div
                onMouseMove={spotMove}
                whileHover={{ y: -8 }}
                transition={{ type: "spring", stiffness: 300, damping: 20 }}
                className="glass spotlight group h-full rounded-canon p-6"
              >
                <div className="mb-5 inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-green-bright/15 text-green-glow transition-all duration-300 group-hover:scale-110 group-hover:bg-green-bright/25">
                  {f.icon}
                </div>
                <h3 className="font-display text-xl font-bold">{tr(f.t)}</h3>
                <p className="mt-2 text-sm leading-relaxed text-white/60">{tr(f.d)}</p>
              </m.div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}
