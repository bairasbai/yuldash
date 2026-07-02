"use client";

import { useState } from "react";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";

const YULDASH_PER_KM = 7; // ₽/км — попутка (деление расходов)
const TAXI_PER_KM = 25; // ₽/км — такси (ориентир)

export function Savings() {
  const { tr } = useLang();
  const [km, setKm] = useState(130);

  const yul = Math.round((km * YULDASH_PER_KM) / 10) * 10;
  const taxi = Math.round((km * TAXI_PER_KM) / 10) * 10;
  const save = taxi - yul;
  const fmt = (n: number) => n.toLocaleString("ru-RU");

  return (
    <section className="relative px-6 py-24">
      <div className="mx-auto max-w-3xl">
        <Reveal className="mb-12 text-center">
          <OrnamentKicker />
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">{tr("save_title")}</h2>
          <p className="mt-4 text-lg text-white/60">{tr("save_sub")}</p>
        </Reveal>

        <Reveal>
          <div className="glass rounded-canon p-7 sm:p-10">
            {/* слайдер */}
            <div className="mb-8">
              <div className="mb-3 flex items-end justify-between">
                <span className="text-sm font-semibold text-white/60">{tr("save_distance")}</span>
                <span className="font-display text-2xl font-extrabold text-gold-light">{fmt(km)} км</span>
              </div>
              <input
                type="range"
                min={20}
                max={500}
                step={10}
                value={km}
                onChange={(e) => setKm(Number(e.target.value))}
                className="slider w-full"
                aria-label={tr("save_distance")}
              />
              <div className="mt-1 flex justify-between text-xs text-white/35">
                <span>20 км</span>
                <span>500 км</span>
              </div>
            </div>

            {/* сравнение */}
            <div className="grid gap-4 sm:grid-cols-3">
              <div className="rounded-canon bg-green-bright/10 p-5 text-center ring-1 ring-green-bright/20">
                <div className="text-xs font-semibold text-green-glow">{tr("save_yuldash")}</div>
                <div className="mt-1 font-display text-3xl font-extrabold text-white">{fmt(yul)} ₽</div>
              </div>
              <div className="rounded-canon bg-white/5 p-5 text-center">
                <div className="text-xs font-semibold text-white/50">{tr("save_taxi")}</div>
                <div className="mt-1 font-display text-3xl font-extrabold text-white/45">{fmt(taxi)} ₽</div>
                <div className="mt-1.5 inline-flex items-center gap-1 rounded-full bg-white/[0.06] px-2 py-0.5 text-[11px] font-semibold text-white/45">
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M12 19V5M6 11l6-6 6 6" />
                  </svg>
                  ×{(taxi / yul).toFixed(1)} {tr("save_pricier")}
                </div>
              </div>
              <div className="rounded-canon bg-gradient-to-br from-gold/20 to-transparent p-5 text-center ring-1 ring-gold/25">
                <div className="text-xs font-semibold text-gold-light">{tr("save_save")}</div>
                <div className="mt-1 font-display text-3xl font-extrabold text-gold-light">−{fmt(save)} ₽</div>
              </div>
            </div>

            <p className="mt-5 text-center text-xs text-white/35">{tr("save_note")}</p>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
