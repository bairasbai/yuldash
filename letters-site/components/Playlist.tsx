"use client";

import { motion } from "motion/react";
import CardIcon from "./CardIcon";
import { NoteIcon } from "./Icons";
import type { Song } from "@/data/playlist";

/**
 * Наши песни. Главное здесь не треки, а подписи «почему эта» —
 * без них это просто чей-то плейлист.
 */
export default function Playlist({ songs }: { songs: Song[] }) {
  return (
    <section className="card px-5 py-6">
      <div className="mb-5 flex items-start gap-4">
        <CardIcon tone="calm">
          <NoteIcon size={21} />
        </CardIcon>
        <div className="flex-1">
          <h2 className="eyebrow">наши песни</h2>
          <p className="mt-1.5 font-serif text-[1.3rem] leading-snug text-sky-ink">
            Каждая за что-то отвечает
          </p>
        </div>
      </div>

      <ul className="space-y-5">
        {songs.map((s, i) => (
          <motion.li
            key={`${s.title}-${i}`}
            // Появление по обычному animate, а не по попаданию в кадр:
            // карточка невысокая, и при заходе с прокруткой песни
            // оставались невидимыми
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
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
