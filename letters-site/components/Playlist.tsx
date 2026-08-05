"use client";

import { motion } from "motion/react";
import type { Song } from "@/data/playlist";

/**
 * Наши песни. Главное здесь не треки, а подписи «почему эта» —
 * без них это просто чей-то плейлист.
 */
export default function Playlist({ songs }: { songs: Song[] }) {
  return (
    <section className="rounded-[var(--radius-card)] border border-panel-border bg-panel px-5 py-6 backdrop-blur-[2px]">
      <h2 className="mb-5 font-sans text-[0.66rem] uppercase tracking-[0.2em] text-sky-ink-soft/70">
        наши песни
      </h2>

      <ul className="space-y-5">
        {songs.map((s, i) => (
          <motion.li
            key={`${s.title}-${i}`}
            initial={{ opacity: 0, y: 10 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, margin: "-40px" }}
            transition={{ delay: i * 0.07, duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
          >
            <div className="flex items-baseline gap-2">
              <span className="font-serif text-[1.12rem] text-sky-ink">
                {s.title}
              </span>
              <span className="font-sans text-[0.7rem] uppercase tracking-[0.14em] text-sky-ink-soft/60">
                {s.artist}
              </span>
              {s.link && (
                <a
                  href={s.link}
                  target="_blank"
                  rel="noreferrer"
                  className="ml-auto shrink-0 font-sans text-[0.6rem] uppercase tracking-[0.16em] text-sky-ink-soft/70 transition-colors hover:text-sky-ink"
                >
                  слушать
                </a>
              )}
            </div>
            <p className="mt-1.5 font-serif text-[1.02rem] italic leading-relaxed text-sky-ink-soft">
              {s.why}
            </p>
          </motion.li>
        ))}
      </ul>
    </section>
  );
}
