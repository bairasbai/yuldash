"use client";

import { useEffect, useRef, useState } from "react";
import { useInView, useReducedMotion } from "framer-motion";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { STATS_API, LIVE_STATS } from "./config";

type Stat = { value: string; ru: string; ba: string };

// Count-up для числовой части значения ("1 200+" → бежит до 1 200, плюс суффикс).
function AnimatedValue({ raw }: { raw: string }) {
  const ref = useRef<HTMLSpanElement>(null);
  const inView = useInView(ref, { once: true, margin: "-40px" });
  const reduce = useReducedMotion();
  const [val, setVal] = useState(0);

  const m = raw.match(/^(\d[\d\s]*)(.*)$/);
  const target = m ? parseInt(m[1].replace(/\s/g, ""), 10) : null;
  const suffix = m ? m[2] : "";

  useEffect(() => {
    if (target === null) return;
    if (!inView || reduce) {
      setVal(target);
      return;
    }
    let raf = 0;
    let start = 0;
    const step = (ts: number) => {
      if (!start) start = ts;
      const p = Math.min((ts - start) / 1400, 1);
      const eased = 1 - Math.pow(1 - p, 3);
      setVal(Math.round(target * eased));
      if (p < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [inView, target, reduce]);

  if (target === null) return <span ref={ref}>{raw}</span>;
  return (
    <span ref={ref}>
      {val.toLocaleString("ru-RU")}
      {suffix}
    </span>
  );
}

export function StatsBand() {
  const { tr, lang } = useLang();
  const [live, setLive] = useState<Stat[]>(LIVE_STATS);

  // Тянем живые цифры с сервера (если задан STATS_API). CORS должен быть разрешён.
  useEffect(() => {
    if (!STATS_API) return;
    let alive = true;
    fetch(STATS_API)
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        const items: Stat[] = Array.isArray(data) ? data : data?.items;
        if (alive && Array.isArray(items) && items.length) setLive(items.slice(0, 4));
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  const items =
    live.length > 0
      ? live.map((s) => ({ value: s.value, label: s[lang] }))
      : [
          { value: tr("stats_1n"), label: tr("stats_1l") },
          { value: tr("stats_2n"), label: tr("stats_2l") },
          { value: tr("stats_3n"), label: tr("stats_3l") },
          { value: tr("stats_4n"), label: tr("stats_4l") },
        ];

  return (
    <section id="stats" className="relative px-6 py-16">
      <Reveal className="mx-auto max-w-5xl">
        <div className="glass grid grid-cols-2 gap-y-8 rounded-canon px-6 py-10 sm:grid-cols-4 sm:divide-x sm:divide-white/10">
          {items.map((it, i) => (
            <div key={i} className="text-center sm:px-4">
              <div className="font-display text-3xl font-extrabold text-gold-light sm:text-4xl">
                <AnimatedValue raw={it.value} />
              </div>
              <div className="mt-2 text-sm text-white/55">{it.label}</div>
            </div>
          ))}
        </div>
      </Reveal>
    </section>
  );
}
