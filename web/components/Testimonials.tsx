"use client";

import { useEffect, useState } from "react";
import { m } from "framer-motion";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";
import { DownloadButton } from "./DownloadButton";
import { LIVE_TESTIMONIALS, TESTIMONIALS_API, type Testimonial } from "./config";

// Нормализованный отзыв для отображения (одна строка текста под текущий язык).
type View = { quote: string; name: string; city?: string; stars: number };

function fromConfig(list: Testimonial[], lang: "ru" | "ba"): View[] {
  return list.map((t) => ({
    quote: lang === "ba" ? t.quoteBa : t.quoteRu,
    name: t.name,
    city: lang === "ba" ? t.cityBa : t.cityRu,
    stars: t.stars ?? 5,
  }));
}

function Stars({ n }: { n: number }) {
  return (
    <div className="mb-3 flex gap-0.5 text-gold-light" aria-label={`${n} из 5`}>
      {Array.from({ length: Math.max(0, Math.min(5, n)) }).map((_, i) => (
        <svg key={i} width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="m12 2 3 6.3 6.9.9-5 4.8 1.2 6.9L12 17.8 5.9 20.9 7.1 14l-5-4.8 6.9-.9L12 2Z" />
        </svg>
      ))}
    </div>
  );
}

export function Testimonials() {
  const { tr, lang } = useLang();
  // null = ещё грузим (когда задан API); иначе готовый список (возможно пустой)
  const [items, setItems] = useState<View[] | null>(TESTIMONIALS_API ? null : fromConfig(LIVE_TESTIMONIALS, lang));

  useEffect(() => {
    if (!TESTIMONIALS_API) {
      const timer = window.setTimeout(() => setItems(fromConfig(LIVE_TESTIMONIALS, lang)), 0);
      return () => window.clearTimeout(timer);
    }
    let alive = true;
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 3500);
    fetch(TESTIMONIALS_API, { cache: "no-store", signal: controller.signal })
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((data: unknown) => {
        if (!alive) return;
        window.clearTimeout(timer);
        const raw = Array.isArray(data) ? data : (data as { items?: unknown[] })?.items ?? [];
        const mapped: View[] = (raw as Record<string, unknown>[])
          .filter((r) => typeof r?.text === "string" && (r.text as string).trim())
          .map((r) => ({
            quote: String(r.text),
            name: String(r.name ?? "Аноним"),
            city: r.city ? String(r.city) : undefined,
            stars: Number(r.stars ?? 5),
          }));
        // Если сервер пуст — мягко падаем на ручной список
        setItems(mapped.length ? mapped : fromConfig(LIVE_TESTIMONIALS, lang));
      })
      .catch(() => alive && setItems(fromConfig(LIVE_TESTIMONIALS, lang)))
      .finally(() => window.clearTimeout(timer));
    return () => {
      alive = false;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [lang]);

  const loading = items === null;
  const list = items ?? [];
  const hasReviews = list.length > 0;

  return (
    <section className="relative px-6 py-24">
      <div className="mx-auto max-w-6xl">
        <Reveal className="mx-auto max-w-2xl text-center">
          <OrnamentKicker />
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">
            {tr("tst_title")}
          </h2>
          <p className="mt-4 text-lg text-white/60">{tr("tst_sub")}</p>
        </Reveal>

        {loading ? (
          /* скелетон, пока тянем отзывы с сервера */
          <div className="mt-14 grid gap-5 md:grid-cols-3" aria-busy="true">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="glass h-44 animate-pulse rounded-canon p-6">
                <div className="mb-4 h-4 w-24 rounded bg-white/10" />
                <div className="space-y-2">
                  <div className="h-3 w-full rounded bg-white/10" />
                  <div className="h-3 w-5/6 rounded bg-white/10" />
                  <div className="h-3 w-2/3 rounded bg-white/10" />
                </div>
              </div>
            ))}
          </div>
        ) : hasReviews ? (
          <div className="mt-14 grid gap-5 md:grid-cols-3">
            {list.map((it, i) => {
              const who = it.city ? `${it.name}, ${it.city}` : it.name;
              return (
                <Reveal key={i} delay={(i % 3) * 0.1}>
                  <m.figure
                    whileHover={{ y: -6 }}
                    transition={{ type: "spring", stiffness: 300, damping: 20 }}
                    className="glass flex h-full flex-col rounded-canon p-6"
                  >
                    <Stars n={it.stars} />
                    <blockquote className="flex-1 text-[15px] leading-relaxed text-white/80">
                      «{it.quote}»
                    </blockquote>
                    <figcaption className="mt-5 flex items-center gap-3">
                      <span className="flex h-10 w-10 items-center justify-center rounded-full bg-gradient-to-br from-green-bright/30 to-green-deep/30 font-display text-base font-extrabold text-green-glow ring-1 ring-green-bright/20">
                        {it.name.trim().charAt(0)}
                      </span>
                      <span className="text-sm font-semibold text-white/70">{who}</span>
                    </figcaption>
                  </m.figure>
                </Reveal>
              );
            })}
          </div>
        ) : (
          /* Честная заглушка до запуска — без выдуманных отзывов */
          <Reveal className="mx-auto mt-12 max-w-2xl">
            <div className="glass relative overflow-hidden rounded-canon p-8 text-center sm:p-12">
              <div className="pointer-events-none absolute -right-16 -top-20 h-56 w-56 rounded-full bg-green-bright/10 blur-[90px]" />
              <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-green-bright/12 text-green-glow">
                <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M3 11l19-9-9 19-2-8-8-2Z" />
                </svg>
              </span>
              <h3 className="mt-5 font-display text-2xl font-extrabold sm:text-3xl">{tr("tst_empty_t")}</h3>
              <p className="mx-auto mt-3 max-w-md leading-relaxed text-white/65">{tr("tst_empty_d")}</p>
              <div className="mt-7 flex justify-center">
                <DownloadButton label={tr("hero_cta")} sub={tr("hero_cta_sub")} size="md" />
              </div>
            </div>
          </Reveal>
        )}
      </div>
    </section>
  );
}
